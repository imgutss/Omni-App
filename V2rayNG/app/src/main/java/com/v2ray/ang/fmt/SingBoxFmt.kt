package com.v2ray.ang.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType

/**
 * Imports a sing-box JSON configuration into Omni profiles by reading its `outbounds` (and the
 * `endpoints` a WireGuard profile lives in). Each outbound becomes a profile of the
 * matching protocol; the QUIC family Omni runs on sidecars (TUIC, NaiveProxy) is included. Outbound
 * types Omni cannot run are skipped. Inbounds, routing and DNS are ignored: Omni rebuilds those.
 */
object SingBoxFmt {

    /** True when [text] is a sing-box JSON document (has outbounds/endpoints with a type field). */
    fun looksLikeSingBox(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return false
        if (!trimmed.contains("\"outbounds\"") && !trimmed.contains("\"endpoints\"")) return false
        // A V2Ray/Xray custom config also has outbounds; tell them apart by the sing-box shape.
        val root = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return false
        val outbounds = root.array("outbounds") ?: root.array("endpoints") ?: return false
        return outbounds.any { it is JsonObject && it.string("type") != null && it.string("protocol") == null }
    }

    fun parse(text: String): List<ProfileItem> {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return emptyList()
        val entries = buildList {
            root.array("outbounds")?.forEach { add(it) }
            root.array("endpoints")?.forEach { add(it) }
        }
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            runCatching { outboundToProfile(obj) }.getOrNull()
        }
    }

    private fun outboundToProfile(obj: JsonObject): ProfileItem? {
        val type = obj.string("type")?.lowercase() ?: return null
        val configType = when (type) {
            "shadowsocks" -> EConfigType.SHADOWSOCKS
            "vmess" -> EConfigType.VMESS
            "vless" -> EConfigType.VLESS
            "trojan" -> EConfigType.TROJAN
            "socks" -> EConfigType.SOCKS
            "http" -> EConfigType.HTTP
            "hysteria2" -> EConfigType.HYSTERIA2
            "tuic" -> EConfigType.TUIC
            "wireguard" -> if (hasAwg(obj)) return null else EConfigType.WIREGUARD
            else -> return null
        }
        // A WireGuard endpoint names its server in the first peer, not at the root.
        val firstPeer = obj.array("peers")?.firstOrNull() as? JsonObject
        val server = obj.string("server") ?: firstPeer?.string("address")
        val port = obj.int("server_port") ?: firstPeer?.int("port")
        if (server.isNullOrBlank() || port == null) return null
        val config = ProfileItem.create(configType)
        config.remarks = obj.string("tag") ?: "$server:$port"
        config.server = server
        config.serverPort = port.toString()

        when (configType) {
            EConfigType.SHADOWSOCKS -> {
                config.method = obj.string("method")
                config.password = obj.string("password")
            }
            EConfigType.VMESS -> {
                config.password = obj.string("uuid")
                config.method = obj.string("security") ?: "auto"
                applyTransport(config, obj.obj("transport"))
                applyTls(config, obj.obj("tls"))
            }
            EConfigType.VLESS -> {
                config.password = obj.string("uuid")
                config.method = "none"
                config.flow = obj.string("flow")
                applyTransport(config, obj.obj("transport"))
                applyTls(config, obj.obj("tls"))
            }
            EConfigType.TROJAN -> {
                config.password = obj.string("password")
                applyTransport(config, obj.obj("transport"))
                applyTls(config, obj.obj("tls"))
            }
            EConfigType.SOCKS, EConfigType.HTTP -> {
                config.username = obj.string("username")
                config.password = obj.string("password")
                if (configType == EConfigType.HTTP) applyTls(config, obj.obj("tls"))
            }
            EConfigType.HYSTERIA2 -> {
                config.password = obj.string("password")
                config.security = AppConfig.TLS
                val tls = obj.obj("tls")
                config.sni = tls?.string("server_name")
                config.insecure = tls?.bool("insecure") ?: false
                config.alpn = tls?.array("alpn")?.joinToString(",") { it.asString }
                config.obfsPassword = obj.obj("obfs")?.string("password")
                obj.int("up_mbps")?.let { config.bandwidthUp = it.toString() }
                obj.int("down_mbps")?.let { config.bandwidthDown = it.toString() }
            }
            EConfigType.TUIC -> {
                config.username = obj.string("uuid")
                config.password = obj.string("password")
                config.security = AppConfig.TLS
                val tls = obj.obj("tls")
                config.sni = tls?.string("server_name")
                config.insecure = tls?.bool("insecure") ?: false
                config.alpn = tls?.array("alpn")?.joinToString(",") { it.asString } ?: "h3"
                val params = JsonObject()
                obj.string("congestion_control")?.let { params.addProperty("congestion_control", it) }
                obj.string("udp_relay_mode")?.let { params.addProperty("udp_relay_mode", it) }
                obj.bool("zero_rtt_handshake")?.let { params.addProperty("zero_rtt_handshake", it) }
                if (params.size() > 0) config.tuicParameters = params.toString()
            }
            EConfigType.WIREGUARD -> {
                config.secretKey = obj.string("private_key")
                config.localAddress = obj.array("address")?.joinToString(",") { it.asString }
                    ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
                config.mtu = obj.int("mtu") ?: 1420
                val peer = obj.array("peers")?.firstOrNull() as? JsonObject
                if (peer != null) {
                    config.publicKey = peer.string("public_key")
                    config.preSharedKey = peer.string("pre_shared_key")
                    config.reserved = peer.array("reserved")?.joinToString(",") { it.asInt.toString() } ?: "0,0,0"
                    // The endpoint names its peer separately; a sing-box outbound carries server/server_port.
                    if (config.server.isNullOrBlank()) config.server = peer.string("address")
                    peer.int("port")?.let { config.serverPort = it.toString() }
                }
            }
            else -> return null
        }
        return config
    }

    private fun hasAwg(obj: JsonObject): Boolean =
        listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
            .any { obj.get(it) != null }

    private fun applyTransport(config: ProfileItem, transport: JsonObject?) {
        if (transport == null) {
            config.network = NetworkType.TCP.type
            return
        }
        when (transport.string("type")?.lowercase()) {
            "ws" -> {
                config.network = NetworkType.WS.type
                config.path = transport.string("path")
                config.host = transport.obj("headers")?.string("Host")
            }
            "grpc" -> {
                config.network = NetworkType.GRPC.type
                config.serviceName = transport.string("service_name")
            }
            "http" -> {
                config.network = NetworkType.H2.type
                config.path = transport.string("path")
                config.host = transport.array("host")?.firstOrNull()?.asString
            }
            "httpupgrade" -> {
                config.network = NetworkType.HTTP_UPGRADE.type
                config.path = transport.string("path")
                config.host = transport.string("host")
            }
            else -> config.network = NetworkType.TCP.type
        }
    }

    private fun applyTls(config: ProfileItem, tls: JsonObject?) {
        if (tls == null || tls.bool("enabled") != true) {
            config.security = null
            return
        }
        val reality = tls.obj("reality")
        if (reality?.bool("enabled") == true) {
            config.security = AppConfig.REALITY
            config.publicKey = reality.string("public_key")
            config.shortId = reality.string("short_id")
        } else {
            config.security = AppConfig.TLS
        }
        config.sni = tls.string("server_name")
        config.insecure = tls.bool("insecure") ?: false
        config.alpn = tls.array("alpn")?.joinToString(",") { it.asString }
        config.fingerPrint = tls.obj("utls")?.string("fingerprint")
    }

    // ── tolerant JSON accessors ─────────────────────────────────────────────────────────────────

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
