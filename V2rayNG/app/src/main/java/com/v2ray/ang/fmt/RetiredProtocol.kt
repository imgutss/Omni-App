package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import java.net.URI

/** Only retirement guards remain: never reinterpret removed AWG settings as plain WireGuard. */
object RetiredProtocol {
    private val keys = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
    fun isAwgConf(text: String): Boolean = text.lineSequence().any {
        it.substringBefore('=').trim().lowercase() in keys
    }
    fun isAwgUri(text: String): Boolean {
        if (text.startsWith("awg://", true) || text.startsWith("amneziawg://", true)) return true
        return runCatching { URI(text).rawQuery.orEmpty().split('&').any {
            it.substringBefore('=').lowercase() in keys
        } }.getOrDefault(false)
    }
    // Retain old payloads inertly rather than deleting user data. Hide them before enum decoding.
    fun isRetiredProfile(json: String?): Boolean = runCatching {
        val type = JsonParser.parseString(json ?: return false).asJsonObject.get("configType") ?: return false
        type.asString == "AMNEZIAWG" || type.asString == "505"
    }.getOrDefault(false)
}
