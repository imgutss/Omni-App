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
 * Reads and writes the `shadowquic://` share link for ShadowQUIC, which Omni runs on the official
 * shadowquic sidecar tuned for gaming (see [com.v2ray.ang.core.QuicCore]).
 *
 * Layout: `shadowquic://username:password@host:port?sni=&alpn=&congestion_control=&over_stream=#remark`.
 * `sni` is the ShadowQUIC server-name (it must equal the server's JLS upstream domain). The gaming
 * tuning that has no dedicated field travels as JSON in [ProfileItem.shadowquicParameters]; absent
 * keys fall back to the gaming baseline the sidecar config builder applies.
 */
object ShadowQuicFmt {
    const val SCHEME = AppConfig.SHADOWQUIC

    private val PARAM_KEYS = listOf(
        "congestion_control", "over_stream", "zero_rtt", "initial_mtu", "min_mtu",
        "mtu_discovery", "keep_alive_interval", "gso", "blackhole_detection",
    )

    fun parse(str: String): ProfileItem? {
        val text = str.trim()
        if (!text.lowercase().startsWith(SCHEME)) return null
        return try {
            val uri = URI(Utils.fixIllegalUrl(text))
            val config = ProfileItem.create(EConfigType.SHADOWQUIC)
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
            val query = TuicFmt.queryParams(uri)
            config.sni = (query["sni"] ?: query["server-name"] ?: query["server_name"])?.nullIfBlank()
            config.alpn = query["alpn"]?.nullIfBlank() ?: "h3"
            val params = JsonObject()
            PARAM_KEYS.forEach { key ->
                val value = query[key] ?: query[key.replace('_', '-')] ?: return@forEach
                if (value.isBlank()) return@forEach
                when (key) {
                    "initial_mtu", "min_mtu", "keep_alive_interval" -> value.toIntOrNull()?.let { params.addProperty(key, it) }
                    "over_stream", "zero_rtt", "mtu_discovery", "gso", "blackhole_detection" ->
                        params.addProperty(key, value == "1" || value.equals("true", true))
                    else -> params.addProperty(key, value)
                }
            }
            if (params.size() > 0) config.shadowquicParameters = params.toString()
            config
        } catch (_: Exception) {
            null
        }
    }

    fun toUri(config: ProfileItem): String {
        val query = linkedMapOf<String, String>()
        config.sni?.nullIfBlank()?.let { query["sni"] = it }
        query["alpn"] = config.alpn?.nullIfBlank() ?: "h3"
        val params = runCatching { JsonParser.parseString(config.shadowquicParameters.orEmpty()).asJsonObject }.getOrNull()
        params?.entrySet()?.forEach { (key, value) -> if (value.isJsonPrimitive) query[key] = value.asString }
        val userInfo = "${Utils.encodeURIComponent(config.username.orEmpty())}:${Utils.encodeURIComponent(config.password.orEmpty())}"
        return TuicFmt.buildUri(SCHEME, userInfo, config, query)
    }
}
