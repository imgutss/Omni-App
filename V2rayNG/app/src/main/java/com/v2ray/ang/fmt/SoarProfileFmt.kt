package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI
import java.util.Base64

/** Apache-2.0 Soar-Server profile links, backed by Omni's real Soar launcher. */
internal object SoarProfileFmt {
    private const val MAX_SETTINGS_CHARS = 65_536
    private val defaults = linkedMapOf(
        "transport" to "auto",
        "discovery" to true,
        "discoveryWant" to 2,
        "discoveryConcurrency" to 2,
        "discoveryPaceMs" to 1000,
        "safeQName" to 101,
        "maxQName" to 253,
        "maxAnswer" to 1232,
        "holdMs" to 500,
        "maxInflight" to 128,
        "maxPathWindow" to 32,
        "maxStreams" to 128,
        "streamWindowKiB" to 256,
        "sendBufferKiB" to 64,
        "sessionIdleSeconds" to 90,
        "dialTimeoutSeconds" to 90,
    )

    fun parse(str: String): ProfileItem? = with(WhiteDnsProfileFmt) {
        val uri = runCatching { URI(str.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("soar", true)) return null
        val envelope = fromUri(uri) ?: return null
        val engine = envelope.text("engine")?.trim()?.lowercase()
        if (engine != null && engine != "soar") return null
        val server = envelope.get("server")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val domain = server.text("domain") ?: return null
        val publicKey = server.text("public_key") ?: return null
        val settings = envelope.get("soarSettings")?.takeIf { it.isJsonObject }?.asJsonObject
        val canonicalSettings = normalizeSettings(settings?.toString()) ?: return null
        val resolvers = envelope.stringArray("resolvers") ?: emptyList()
        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = envelope.text("name")?.trim().takeUnless { name -> name.isNullOrEmpty() } ?: "Soar"
            it.dnsttEngine = DnsttFmt.ENGINE_SOAR
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttResolver = resolvers.joinToString(",")
            it.soarSettings = canonicalSettings
            it.dnsttTransport = if (settingsTransport(canonicalSettings) == "only") "tcp" else "udp"
            it.dnsttUtls = ""
        }
        return profile.takeIf { DnsttFmt.normalize(it) == null }
    }

    fun toUri(config: ProfileItem): String {
        val settings = normalizeSettings(config.soarSettings) ?: return ""
        val profile = JsonObject().apply {
            addProperty("id", "${config.addedTime}:soar")
            addProperty("name", config.remarks.ifBlank { "Soar" })
            add("server", JsonObject().apply {
                addProperty("domain", config.dnsttDomain.orEmpty())
                addProperty("public_key", config.dnsttPublicKey.orEmpty())
            })
            add("resolvers", WhiteDnsProfileFmt.strings(config.dnsttResolver.orEmpty().split(',').filter { it.isNotBlank() }))
            add("soarSettings", JsonParser.parseString(settings).asJsonObject)
        }
        return WhiteDnsProfileFmt.toPayload(profile)
    }

    fun normalizeSettings(raw: String?): String? = with(WhiteDnsProfileFmt) {
        val obj = if (raw.isNullOrBlank()) {
            JsonObject()
        } else {
            if (raw.length > MAX_SETTINGS_CHARS) return null
            runCatching { JsonParser.parseString(raw) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        }
        val transport = if (obj.has("transport")) obj.text("transport") ?: return null else defaults.getValue("transport") as String
        if (transport !in setOf("auto", "on", "only", "off")) return null
        val discovery = if (obj.has("discovery")) obj.bool("discovery") ?: return null else defaults.getValue("discovery") as Boolean
        val values = mutableMapOf<String, Int>()
        for ((key, default) in defaults) {
            if (key == "transport" || key == "discovery") continue
            values[key] = if (obj.has(key)) obj.number(key) ?: return null else default as Int
        }
        val safeQName = values.getValue("safeQName")
        val bounds = mapOf(
            "discoveryWant" to (1..32), "discoveryConcurrency" to (1..16),
            "discoveryPaceMs" to (100..5000), "safeQName" to (101..253),
            "maxQName" to (safeQName..253), "maxAnswer" to (512..1232),
            "holdMs" to (100..1500), "maxInflight" to (8..1024),
            "maxPathWindow" to (2..128), "maxStreams" to (8..128),
            "streamWindowKiB" to (64..1024), "sendBufferKiB" to (32..1024),
            "sessionIdleSeconds" to (15..110), "dialTimeoutSeconds" to (5..120),
        )
        if (bounds.any { (key, range) -> values.getValue(key) !in range }) return null
        obj.addProperty("transport", transport)
        obj.addProperty("discovery", discovery)
        values.forEach { (key, value) -> obj.addProperty(key, value) }
        obj.toString()
    }

    fun updateSettings(raw: String?, transport: String? = null, discovery: Boolean? = null): String {
        val canonical = normalizeSettings(raw) ?: return raw.orEmpty()
        val obj = JsonParser.parseString(canonical).asJsonObject
        transport?.let { obj.addProperty("transport", it) }
        discovery?.let { obj.addProperty("discovery", it) }
        return normalizeSettings(obj.toString()) ?: raw.orEmpty()
    }

    fun settingsTransport(raw: String?): String? = with(WhiteDnsProfileFmt) {
        val obj = runCatching { JsonParser.parseString(raw.orEmpty()) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        obj?.text("transport") ?: "auto"
    }

    fun discoveryEnabled(raw: String?): Boolean = with(WhiteDnsProfileFmt) {
        val obj = runCatching { JsonParser.parseString(raw.orEmpty()) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        obj?.bool("discovery") ?: true
    }

    fun normalizePublicKey(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > 128) return null
        if (value.matches(Regex("(?i)[0-9a-f]{64}"))) {
            val bytes = runCatching { value.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
            if (bytes?.size == 32) return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        val decoded = sequenceOf(
            runCatching { Base64.getUrlDecoder().decode(value) },
            runCatching { Base64.getMimeDecoder().decode(value) },
        ).mapNotNull(Result<ByteArray>::getOrNull).firstOrNull() ?: return null
        if (decoded.size != 32) return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
    }
}
