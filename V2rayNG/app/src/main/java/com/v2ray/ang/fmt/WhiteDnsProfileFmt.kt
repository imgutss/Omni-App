package com.v2ray.ang.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Independent reader/writer for the Apache-2.0 Soar-Server profile envelope.
 * Only the documented whitedns.profile v1 fields are consumed; no proprietary
 * WhiteDNS-Android code, resource, or binary is used here.
 */
internal object WhiteDnsProfileFmt {
    private const val MAX_CHARS = 1_048_576

    fun fromUri(uri: URI): JsonObject? {
        val payload = uri.rawSchemeSpecificPart.orEmpty().removePrefix("//")
        if (payload.isBlank() || payload.startsWith('?')) return null
        return fromPayload(payload)
    }

    fun fromPayload(payload: String): JsonObject? {
        val compact = payload.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || compact.length > MAX_CHARS * 2) return null
        val bytes = sequenceOf(
            runCatching { Base64.getUrlDecoder().decode(compact) },
            runCatching { Base64.getMimeDecoder().decode(compact) },
        ).mapNotNull(Result<ByteArray>::getOrNull).firstOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_CHARS) return null
        val root = runCatching {
            JsonParser.parseString(String(bytes, StandardCharsets.UTF_8))
        }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (root.text("schema") != "whitedns.profile" || root.number("version") != 1) return null
        return root.get("profile")?.takeIf { it.isJsonObject }?.asJsonObject
    }

    fun toPayload(profile: JsonObject): String {
        val root = JsonObject().apply {
            addProperty("schema", "whitedns.profile")
            addProperty("version", 1)
            add("profile", profile)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(root.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun JsonObject.number(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()

    fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    fun JsonObject.stringArray(key: String): List<String>? {
        val array = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return array.map { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
            element.asString
        }
    }

    fun strings(values: List<String>): JsonArray = JsonArray().apply { values.forEach(::add) }
}
