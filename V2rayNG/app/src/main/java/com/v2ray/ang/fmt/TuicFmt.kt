package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Reads and writes the `tuic://` share link for TUIC v5, which Xray cannot speak natively and which
 * Omni runs on a sing-box sidecar (see [com.v2ray.ang.core.QuicCore]).
 *
 * Layout: `tuic://uuid:password@host:port?sni=&alpn=&congestion_control=&udp_relay_mode=&allow_insecure=#remark`.
 * The uuid is kept in [ProfileItem.username] and the password in [ProfileItem.password]; the tuning
 * that has no dedicated field travels as a small JSON document in [ProfileItem.tuicParameters].
 */
object TuicFmt {
    const val SCHEME = AppConfig.TUIC

    fun parse(str: String): ProfileItem? {
        val text = str.trim()
        if (!text.lowercase().startsWith(SCHEME)) return null
        return try {
            val uri = URI(Utils.fixIllegalUrl(text))
            val config = ProfileItem.create(EConfigType.TUIC)
            config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
            config.server = uri.idnHost
            config.serverPort = (if (uri.port > 0) uri.port else AppConfig.DEFAULT_PORT).toString()
            val userInfo = uri.userInfo.orEmpty()
            val separator = userInfo.indexOf(':')
            if (separator >= 0) {
                config.username = userInfo.substring(0, separator)
                config.password = userInfo.substring(separator + 1)
            } else {
                config.username = userInfo
            }
            config.security = AppConfig.TLS
            val query = queryParams(uri)
            config.sni = query["sni"]?.nullIfBlank()
            config.alpn = query["alpn"]?.nullIfBlank() ?: "h3"
            config.insecure = query["allow_insecure"] == "1" || query["insecure"] == "1" || query["allowInsecure"] == "1"
            val params = JsonObject()
            query["congestion_control"]?.nullIfBlank()?.let { params.addProperty("congestion_control", it) }
            query["udp_relay_mode"]?.nullIfBlank()?.let { params.addProperty("udp_relay_mode", it) }
            query["zero_rtt_handshake"]?.nullIfBlank()?.let { params.addProperty("zero_rtt_handshake", it == "1" || it.equals("true", true)) }
            if (params.size() > 0) config.tuicParameters = params.toString()
            config
        } catch (_: Exception) {
            null
        }
    }

    fun toUri(config: ProfileItem): String {
        val query = linkedMapOf<String, String>()
        config.sni?.nullIfBlank()?.let { query["sni"] = it }
        query["alpn"] = config.alpn?.nullIfBlank() ?: "h3"
        query["allow_insecure"] = if (config.insecure == true) "1" else "0"
        val params = runCatching { com.google.gson.JsonParser.parseString(config.tuicParameters.orEmpty()).asJsonObject }
            .getOrNull()
        params?.entrySet()?.forEach { (key, value) ->
            if (value.isJsonPrimitive) query[key] = value.asString
        }
        val userInfo = "${Utils.encodeURIComponent(config.username.orEmpty())}:${Utils.encodeURIComponent(config.password.orEmpty())}"
        return buildUri(SCHEME, userInfo, config, query)
    }

    internal fun buildUri(scheme: String, userInfo: String, config: ProfileItem, query: Map<String, String>): String {
        val host = Utils.getIpv6Address(HttpUtil.toIdnDomain(config.server.orEmpty()))
        val queryString = query.entries.joinToString("&") { "${it.key}=${Utils.encodeURIComponent(it.value)}" }
        val fragment = Utils.encodeURIComponent(config.remarks)
        return "$scheme$userInfo@$host:${config.serverPort}?$queryString#$fragment"
    }

    internal fun queryParams(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        return raw.split("&").filter { it.isNotEmpty() }.associate { part ->
            val idx = part.indexOf('=')
            if (idx >= 0) part.substring(0, idx) to Utils.decodeURIComponent(part.substring(idx + 1))
            else part to ""
        }
    }
}
