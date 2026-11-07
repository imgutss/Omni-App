package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

/** Official NaiveProxy over HTTPS/HTTP2. QUIC URLs are intentionally not accepted. */
object NaiveFmt {
    fun parse(text: String): ProfileItem? = runCatching {
        val value = text.trim()
        if (!value.startsWith("naive+https://", true) && !value.startsWith("naive://", true)) return null
        val uri = URI(Utils.fixIllegalUrl(value))
        val info = uri.userInfo ?: return null
        val separator = info.indexOf(':')
        if (separator <= 0 || separator == info.lastIndex) return null
        val params = TuicFmt.queryParams(uri)
        if (params["insecure"] == "1" || params["allow_insecure"] == "1") return null
        ProfileItem.create(EConfigType.NAIVE).apply {
            server = uri.idnHost ?: return null
            serverPort = (if (uri.port == -1) 443 else uri.port).toString()
            username = info.substring(0, separator)
            password = info.substring(separator + 1)
            sni = params["sni"]?.takeIf { it.isNotBlank() }
            security = AppConfig.TLS
            remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifBlank { "NaiveProxy" }
        }
    }.getOrNull()

    fun toUri(profile: ProfileItem): String {
        val userInfo = "${Utils.encodeURIComponent(profile.username.orEmpty())}:${Utils.encodeURIComponent(profile.password.orEmpty())}"
        return TuicFmt.buildUri(AppConfig.NAIVE, userInfo, profile,
            profile.sni?.takeIf { it.isNotBlank() }?.let { mapOf("sni" to it) } ?: emptyMap())
    }
}
