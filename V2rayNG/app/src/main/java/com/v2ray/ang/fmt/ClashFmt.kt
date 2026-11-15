package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Imports a Clash / Mihomo YAML subscription (or a single `proxies:` document) into Omni profiles.
 *
 * Each proxy is mapped straight onto a [ProfileItem] of the matching protocol, including the QUIC
 * family Omni runs on sidecars (TUIC, ShadowQUIC, NaiveProxy). Proxy types Omni cannot run are
 * skipped rather than imported broken. The converter is deliberately tolerant: a malformed proxy
 * never aborts the whole subscription.
 */
object ClashFmt {

    /** True when [text] looks like a Clash/Mihomo YAML document rather than a share link. */
    fun looksLikeClash(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        return trimmed.contains("proxies:") || (trimmed.contains("proxy-groups:") && trimmed.contains("- name:"))
    }

    fun parse(text: String): List<ProfileItem> {
        if (!looksLikeClash(text)) return emptyList()
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val root = runCatching { yaml.load<Any?>(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val proxies = root["proxies"] as? List<*> ?: return emptyList()
        return proxies.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            runCatching { proxyToProfile(map) }.getOrNull()
        }
    }

    private fun proxyToProfile(raw: Map<*, *>): ProfileItem? {
        val type = raw.str("type")?.lowercase() ?: return null
        val server = raw.str("server") ?: return null
        val port = raw.int("port") ?: return null
        val name = raw.str("name") ?: "$server:$port"
        val configType = when (type) {
            "ss", "shadowsocks" -> EConfigType.SHADOWSOCKS
            "vmess" -> EConfigType.VMESS
            "vless" -> EConfigType.VLESS
            "trojan" -> EConfigType.TROJAN
            "socks5", "socks" -> EConfigType.SOCKS
            "http" -> EConfigType.HTTP
            "hysteria2", "hy2" -> EConfigType.HYSTERIA2
            "tuic" -> EConfigType.TUIC
            "wireguard" -> if (raw.map("amnezia-wg-option") != null) return null else EConfigType.WIREGUARD
            "shadowquic", "shadow-quic" -> EConfigType.SHADOWQUIC
            else -> return null
        }
        val config = ProfileItem.create(configType)
        config.remarks = name
        config.server = server
        config.serverPort = port.toString()

        when (configType) {
            EConfigType.SHADOWSOCKS -> {
                config.method = raw.str("cipher")
                config.password = raw.str("password")
            }
            EConfigType.VMESS -> {
                config.password = raw.str("uuid")
                config.method = raw.str("cipher") ?: "auto"
                applyTransport(config, raw)
                applyTls(config, raw)
            }
            EConfigType.VLESS -> {
                config.password = raw.str("uuid")
                config.method = raw.str("encryption") ?: "none"
                config.flow = raw.str("flow")
                applyTransport(config, raw)
                applyTls(config, raw)
            }
            EConfigType.TROJAN -> {
                config.password = raw.str("password")
                applyTransport(config, raw)
                applyTls(config, raw)
            }
            EConfigType.SOCKS, EConfigType.HTTP -> {
                config.username = raw.str("username")
                config.password = raw.str("password")
                if (configType == EConfigType.HTTP) applyTls(config, raw)
            }
            EConfigType.HYSTERIA2 -> {
                config.password = raw.str("password")
                config.security = AppConfig.TLS
                config.sni = raw.str("sni")
                config.insecure = raw.bool("skip-cert-verify") ?: false
                config.alpn = raw.list("alpn")?.joinToString(",")
                config.obfsPassword = raw.str("obfs-password")
                config.portHopping = raw.str("ports") ?: raw.str("mport")
                config.bandwidthUp = raw.str("up")
                config.bandwidthDown = raw.str("down")
            }
            EConfigType.TUIC -> {
                config.username = raw.str("uuid")
                config.password = raw.str("password")
                config.security = AppConfig.TLS
                config.sni = raw.str("sni") ?: raw.str("servername")
                config.insecure = raw.bool("skip-cert-verify") ?: false
                config.alpn = raw.list("alpn")?.joinToString(",") ?: "h3"
                val params = JsonObject()
                raw.str("congestion-controller")?.let { params.addProperty("congestion_control", it.replace('-', '_')) }
                raw.str("udp-relay-mode")?.let { params.addProperty("udp_relay_mode", it) }
                if (params.size() > 0) config.tuicParameters = params.toString()
            }
            EConfigType.SHADOWQUIC -> {
                config.username = raw.str("username")
                config.password = raw.str("password")
                config.sni = raw.str("server-name") ?: raw.str("sni")
                config.alpn = raw.list("alpn")?.joinToString(",") ?: "h3"
                val params = JsonObject()
                raw.str("congestion-controller")?.let { params.addProperty("congestion_control", it.replace('-', '_')) }
                raw.bool("udp-over-stream")?.let { params.addProperty("over_stream", it) }
                if (params.size() > 0) config.shadowquicParameters = params.toString()
            }
            EConfigType.WIREGUARD -> {
                config.secretKey = raw.str("private-key")
                config.publicKey = raw.str("public-key")
                config.preSharedKey = raw.str("pre-shared-key")
                val addresses = buildList {
                    raw.str("ip")?.let { add(it) }
                    raw.str("ipv6")?.let { add(it) }
                }
                config.localAddress = addresses.joinToString(",").ifBlank { AppConfig.WIREGUARD_LOCAL_ADDRESS_V4 }
                config.mtu = raw.int("mtu") ?: 1420
                config.reserved = raw.list("reserved")?.joinToString(",") ?: raw.str("reserved") ?: "0,0,0"
            }
            else -> return null
        }
        return config
    }

    private fun applyTransport(config: ProfileItem, raw: Map<*, *>) {
        val network = raw.str("network")?.lowercase() ?: NetworkType.TCP.type
        config.network = network
        when (network) {
            "ws" -> {
                val opts = raw.map("ws-opts")
                config.path = opts?.str("path")
                config.host = opts?.map("headers")?.str("Host") ?: opts?.map("headers")?.str("host")
            }
            "grpc" -> {
                config.serviceName = raw.map("grpc-opts")?.str("grpc-service-name")
            }
            "h2", "http" -> {
                val opts = raw.map("h2-opts") ?: raw.map("http-opts")
                config.path = opts?.str("path")
                config.host = opts?.list("host")?.firstOrNull()?.toString() ?: opts?.str("host")
            }
            else -> config.headerType = "none"
        }
    }

    private fun applyTls(config: ProfileItem, raw: Map<*, *>) {
        val reality = raw.map("reality-opts")
        when {
            reality != null -> {
                config.security = AppConfig.REALITY
                config.publicKey = reality.str("public-key")
                config.shortId = reality.str("short-id")
                config.sni = raw.str("servername") ?: raw.str("sni")
                config.fingerPrint = raw.str("client-fingerprint") ?: raw.str("fingerprint")
            }
            raw.bool("tls") == true -> {
                config.security = AppConfig.TLS
                config.sni = raw.str("sni") ?: raw.str("servername")
                config.insecure = raw.bool("skip-cert-verify") ?: false
                config.alpn = raw.list("alpn")?.joinToString(",")
                config.fingerPrint = raw.str("client-fingerprint") ?: raw.str("fingerprint")
            }
            else -> config.security = null
        }
    }

    // ── tolerant YAML accessors ─────────────────────────────────────────────────────────────────

    private fun Map<*, *>.str(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun Map<*, *>.int(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun Map<*, *>.bool(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.equals("true", true) || value == "1"
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private fun Map<*, *>.list(key: String): List<*>? = this[key] as? List<*>
}
