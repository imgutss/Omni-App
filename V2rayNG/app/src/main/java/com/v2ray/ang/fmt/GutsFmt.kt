package com.v2ray.ang.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Reads and writes the `guts://` profile the client itself emits and consumes.
 *
 * The body is the same JSON document `guts/config` uses on the command line — `door` (a list), and
 * the optional `key`, `google_ip`, `sni`, `listen` and `note` — Base64URL encoded without padding.
 * One codec therefore serves the QR code the panel prints, the link a user pastes, and the
 * `-export` / `-import` flags of the desktop client: a profile made by either is read by the other.
 */
object GutsFmt {

    const val SCHEME = "guts://"

    private const val DOOR = "door"
    private const val KEY = "key"
    private const val GOOGLE_IP = "google_ip"
    private const val SNI = "sni"
    private const val NOTE = "note"

    /**
     * The body of [profile]'s `guts://` URI, without the scheme — [SCHEME] plus this is the whole
     * URI, as the other formats of this package are written. Null when there is no door to carry.
     */
    fun toUri(profile: ProfileItem): String? {
        val doors = GutsCore.doorsOf(profile)
        if (doors.isEmpty()) return null
        val body = JsonObject().apply {
            add(DOOR, JsonArray().apply { doors.forEach { add(it) } })
            // Absent optional fields stay absent, so the URI stays short enough for a QR code.
            profile.gutsKey?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty(KEY, it) }
            profile.gutsGoogleIp?.trim()?.takeIf { it.isNotEmpty() && it != GutsCore.AUTO_EDGE }
                ?.let { addProperty(GOOGLE_IP, it) }
            profile.gutsSni?.trim()?.takeIf { it.isNotEmpty() && it != GutsCore.DEFAULT_SNI }
                ?.let { addProperty(SNI, it) }
            profile.remarks.takeIf { it.isNotBlank() }?.let { addProperty(NOTE, it) }
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /** Reads a `guts://` URI, or null when it is not one or carries no usable door. */
    fun parse(uri: String): ProfileItem? {
        val text = uri.trim()
        if (!text.lowercase().startsWith(SCHEME)) return null
        val body = text.substring(SCHEME.length).trimEnd('/')
        if (body.isEmpty()) return null
        val decoded = decode(body) ?: return null
        val root = runCatching { JsonParser.parseString(String(decoded, StandardCharsets.UTF_8)).asJsonObject }
            .getOrNull() ?: return null

        val doors = doorsOf(root) ?: return null
        val profile = ProfileItem.create(EConfigType.GUTS).apply {
            gutsDoors = doors.joinToString("\n")
            gutsKey = root.string(KEY)
            gutsGoogleIp = root.string(GOOGLE_IP)
            gutsSni = root.string(SNI)
            remarks = root.string(NOTE) ?: hostOf(doors.first()).orEmpty()
            server = hostOf(doors.first()).orEmpty()
        }
        // A document that cannot start the client is refused here rather than by the core later.
        if (GutsCore.normalize(profile) != null) return null
        return profile
    }

    /** True when [text] is a `guts://` URI, so a batch import can tell it from a subscription. */
    fun looksLikeUri(text: String): Boolean = text.trim().lowercase().startsWith(SCHEME)

    /** The first door of a pasted list, which is the one the profile list shows for the profile. */
    fun firstDoor(doorsText: String?): String? = GutsCore.doorsIn(doorsText).firstOrNull()

    /** The host of a door URL, which is what the profile list shows for it. */
    fun hostOf(door: String?): String? =
        door?.let { runCatching { URI(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun doorsOf(root: JsonObject): List<String>? {
        val list = mutableListOf<String>()
        val array = runCatching { root.getAsJsonArray(DOOR) }.getOrNull()
        if (array != null) {
            array.forEach { element ->
                runCatching { element.asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(list::add)
            }
        }
        // A hand-written URI may carry one door as a plain string.
        root.string(DOOR)?.let { list += it }
        return list.distinct().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.string(name: String): String? =
        runCatching { get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Tolerates the padding some scanners keep, and the standard alphabet some tools emit. */
    private fun decode(body: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull()
            ?: runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull()
}
