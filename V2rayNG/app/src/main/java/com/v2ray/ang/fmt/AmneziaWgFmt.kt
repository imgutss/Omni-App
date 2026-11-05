package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Reads and writes AmneziaWG profiles: the `awg://` share link and the awg-quick `.conf` file with
 * the AmneziaWG obfuscation keys (Jc/Jmin/Jmax/S1/S2/H1..H4/I1..I5). AmneziaWG is WireGuard with
 * packet junking and magic headers; Xray's plain WireGuard outbound cannot speak it, so Omni runs it
 * on a sing-box sidecar with the AmneziaWG endpoint (see [com.v2ray.ang.core.QuicCore]).
 *
 * The WireGuard fields are reused (secretKey/publicKey/preSharedKey/localAddress/mtu/reserved); the
 * obfuscation knobs travel as JSON in [ProfileItem.awgParameters].
 */
object AmneziaWgFmt {
    const val SCHEME = AppConfig.AMNEZIAWG

    private val INT_KEYS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    private val HEADER_KEYS = listOf("h1", "h2", "h3", "h4")
    private val STRING_KEYS = listOf("i1", "i2", "i3", "i4", "i5", "header_protection_key")
    private val AWG_KEYS = (INT_KEYS + HEADER_KEYS + STRING_KEYS).toSet()

    /** True when a wireguard:// or awg:// link carries AmneziaWG obfuscation parameters. */
    fun looksLikeAwg(str: String): Boolean {
        val lower = str.lowercase()
        if (lower.startsWith(SCHEME)) return true
        return AWG_KEYS.any { lower.contains("$it=") }
    }

    fun parse(str: String): ProfileItem? {
        val text = str.trim()
        val lower = text.lowercase()
        if (!lower.startsWith(SCHEME) && !lower.startsWith(AppConfig.WIREGUARD)) return null
        return try {
            val uri = URI(Utils.fixIllegalUrl(text))
            val query = TuicFmt.queryParams(uri)
            val config = ProfileItem.create(EConfigType.AMNEZIAWG)
            config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
            config.server = uri.idnHost
            config.serverPort = (if (uri.port > 0) uri.port else 51820).toString()
            config.secretKey = uri.userInfo.orEmpty()
            config.publicKey = (query["publickey"] ?: query["public_key"] ?: query["peer_public_key"]).orEmpty()
            config.preSharedKey = (query["presharedkey"] ?: query["pre_shared_key"] ?: query["psk"])?.nullIfBlank()
            config.localAddress = (query["address"] ?: query["ip"] ?: query["local_address"]) ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
            config.mtu = Utils.parseInt(query["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
            config.remoteDNS = query["dns"] ?: AppConfig.WIREGUARD_LOCAL_REMOTE_DNS
            config.reserved = query["reserved"] ?: "0,0,0"
            config.awgParameters = paramsFromQuery(query).toString().takeIf { it != "{}" }
            config
        } catch (_: Exception) {
            null
        }
    }

    private fun paramsFromQuery(query: Map<String, String>): JsonObject {
        val params = JsonObject()
        INT_KEYS.forEach { key -> query[key]?.toIntOrNull()?.let { params.addProperty(key, it) } }
        HEADER_KEYS.forEach { key ->
            query[key]?.nullIfBlank()?.let { value ->
                val asInt = value.toIntOrNull()
                if (asInt != null) params.addProperty(key, asInt) else params.addProperty(key, value)
            }
        }
        STRING_KEYS.forEach { key -> query[key]?.nullIfBlank()?.let { params.addProperty(key, it) } }
        (query["persistent_keepalive_interval"] ?: query["persistent_keepalive"] ?: query["keepalive"])?.toIntOrNull()
            ?.let { params.addProperty("persistent_keepalive_interval", it) }
        return params
    }

    /** Parses an awg-quick `.conf`; returns null when it has no AmneziaWG keys (plain WireGuard). */
    fun parseConf(str: String): ProfileItem? {
        val interfaceParams = mutableMapOf<String, String>()
        val peerParams = mutableMapOf<String, String>()
        var section: String? = null
        str.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) return@forEach
            when {
                trimmed.startsWith("[Interface]", true) -> section = "Interface"
                trimmed.startsWith("[Peer]", true) -> section = "Peer"
                else -> {
                    val parts = trimmed.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2 && section != null) {
                        val key = parts[0].lowercase()
                        if (section == "Interface") interfaceParams[key] = parts[1] else peerParams[key] = parts[1]
                    }
                }
            }
        }
        val hasAwg = (interfaceParams.keys + peerParams.keys).any { it in AWG_KEYS }
        if (!hasAwg) return null

        val config = ProfileItem.create(EConfigType.AMNEZIAWG)
        config.remarks = System.currentTimeMillis().toString()
        config.secretKey = interfaceParams["privatekey"].orEmpty()
        config.localAddress = interfaceParams["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.remoteDNS = interfaceParams["dns"] ?: AppConfig.WIREGUARD_LOCAL_REMOTE_DNS
        config.publicKey = peerParams["publickey"].orEmpty()
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()
        config.reserved = peerParams["reserved"] ?: "0,0,0"
        val endpoint = peerParams["endpoint"].orEmpty()
        val endpointParts = endpoint.split(":", limit = 2)
        if (endpointParts.size == 2) {
            config.server = endpointParts[0]
            config.serverPort = endpointParts[1]
        } else {
            config.server = endpoint
            config.serverPort = "51820"
        }
        val merged = interfaceParams + peerParams
        val params = JsonObject()
        INT_KEYS.forEach { key -> merged[key]?.toIntOrNull()?.let { params.addProperty(key, it) } }
        HEADER_KEYS.forEach { key ->
            merged[key]?.nullIfBlank()?.let { value ->
                val asInt = value.toIntOrNull()
                if (asInt != null) params.addProperty(key, asInt) else params.addProperty(key, value)
            }
        }
        STRING_KEYS.forEach { key -> merged[key]?.nullIfBlank()?.let { params.addProperty(key, it) } }
        peerParams["persistentkeepalive"]?.toIntOrNull()?.let { params.addProperty("persistent_keepalive_interval", it) }
        if (params.size() > 0) config.awgParameters = params.toString()
        return config
    }

    fun toUri(config: ProfileItem): String {
        val query = linkedMapOf<String, String>()
        query["publickey"] = config.publicKey.orEmpty()
        query["address"] = config.localAddress.orEmpty()
        config.mtu?.let { query["mtu"] = it.toString() }
        config.preSharedKey?.nullIfBlank()?.let { query["presharedkey"] = it }
        config.remoteDNS?.nullIfBlank()?.let { query["dns"] = it }
        if (!config.reserved.isNullOrBlank() && config.reserved != "0,0,0") query["reserved"] = config.reserved.orEmpty()
        val params = runCatching { JsonParser.parseString(config.awgParameters.orEmpty()).asJsonObject }.getOrNull()
        params?.entrySet()?.forEach { (key, value) -> if (value.isJsonPrimitive) query[key] = value.asString }
        return TuicFmt.buildUri(SCHEME, Utils.encodeURIComponent(config.secretKey.orEmpty()), config, query)
    }
}
