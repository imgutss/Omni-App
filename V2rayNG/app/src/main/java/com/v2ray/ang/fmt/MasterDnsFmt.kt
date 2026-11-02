package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Import and export of the official MasterDnsVPN client configuration. The engine reads the same
 * JSON or TOML document at runtime; Omni only owns the surrounding profile and resolver list.
 *
 * The masterdns:// URI is Omni's own transport for that official document, not a claim of
 * compatibility with WhiteDNS's proprietary configuration format.
 */
object MasterDnsFmt : FmtBase() {

    const val ENGINE_MASTERDNS = "masterdns"
    const val ENGINE_COTTENDNS = "cottendns"
    private const val DEFAULT_REMARKS = "MasterDNS"
    private const val DEFAULT_RESOLVERS = "8.8.8.8"
    private const val MAX_CONFIG_CHARS = 1_048_576

    enum class Problem {
        CONFIG_EMPTY,
        CONFIG_TOO_LARGE,
        CONFIG_MALFORMED,
        DOMAINS_MISSING,
        KEY_MISSING,
        RESOLVERS_INVALID,
    }

    internal enum class Format { JSON, TOML }

    internal data class Document(
        val raw: String,
        val format: Format,
        val domains: List<String>,
        val encryptionMethod: Int,
        val encryptionKey: String?,
        val socksPass: String?,
    )

    /** Parses an Omni masterdns:// URI. Returns null when its payload is not an official client config. */
    fun parse(str: String): ProfileItem? {
        val uri = runCatching { URI(str.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf(ENGINE_MASTERDNS, ENGINE_COTTENDNS)) return null
        val query = queryParameters(uri.rawQuery.orEmpty())
        val encoded = query["config"] ?: return fromProfileEnvelope(uri, scheme)
        val raw = decodePayload(encoded) ?: return null
        val resolvers = query["resolvers"]?.let(::decodePayload) ?: DEFAULT_RESOLVERS
        val remarks = uri.rawFragment
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_REMARKS
        return fromDocument(raw, remarks, resolvers)
    }

    /**
     * Parses a raw official JSON/TOML document or its standard/Base64URL representation. Returns
     * null for unrelated JSON and text so the ordinary custom-config importer can handle it.
     */
    fun parseAny(str: String, remarks: String = DEFAULT_REMARKS): ProfileItem? {
        val trimmed = str.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CONFIG_CHARS * 2) return null
        fromDocument(trimmed, remarks, DEFAULT_RESOLVERS)?.let { return it }
        val decoded = decodePayload(trimmed) ?: return null
        if (decoded == trimmed) return null
        return fromDocument(decoded, remarks, DEFAULT_RESOLVERS)
    }

    /** The URI payload following masterdns://, with both the official document and its resolvers. */
    fun toUri(config: ProfileItem): String {
        val raw = config.masterDnsConfig.orEmpty().trim()
        val resolvers = normalizeResolvers(config.masterDnsResolvers)
        config.masterDnsEngine?.let { engine ->
            val document = document(raw) ?: return ""
            val profile = JsonObject().apply {
                addProperty("id", "${config.addedTime}:$engine")
                addProperty("name", config.remarks.ifBlank { engine })
                addProperty("engine", engine)
                add("server", JsonObject().apply {
                    addProperty("domain", document.domains.firstOrNull() ?: return "")
                    document.encryptionKey?.let { addProperty("encryption_key", it) }
                    addProperty("encryption_method", document.encryptionMethod)
                })
                add("resolvers", WhiteDnsProfileFmt.strings(resolvers.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }))
            }
            return WhiteDnsProfileFmt.toPayload(profile)
        }
        val encodedConfig = encodePayload(raw)
        val encodedResolvers = encodePayload(resolvers)
        val remarks = URLEncoder.encode(config.remarks.ifBlank { DEFAULT_REMARKS }, StandardCharsets.UTF_8.name())
        return "?config=$encodedConfig&resolvers=$encodedResolvers#$remarks"
    }

    /** Normalizes the profile and returns the first problem the editor or importer must show. */
    fun normalize(config: ProfileItem): Problem? {
        val engine = config.masterDnsEngine?.trim()?.lowercase()
        if (engine != null && engine !in setOf(ENGINE_MASTERDNS, ENGINE_COTTENDNS)) return Problem.CONFIG_MALFORMED
        val raw = config.masterDnsConfig?.trim().orEmpty()
        if (raw.isEmpty()) return Problem.CONFIG_EMPTY
        if (raw.length > MAX_CONFIG_CHARS) return Problem.CONFIG_TOO_LARGE
        val document = document(raw) ?: return Problem.CONFIG_MALFORMED
        if ((engine == ENGINE_MASTERDNS && document.format != Format.JSON) ||
            (engine == ENGINE_COTTENDNS && document.format != Format.TOML)
        ) return Problem.CONFIG_MALFORMED
        if (document.domains.isEmpty()) return Problem.DOMAINS_MISSING
        if (document.encryptionMethod !in 0..5) return Problem.CONFIG_MALFORMED
        if (document.encryptionMethod != 0 && document.encryptionKey.isNullOrBlank()) return Problem.KEY_MISSING
        val resolvers = config.masterDnsResolvers
        if (!validResolvers(resolvers)) return Problem.RESOLVERS_INVALID

        config.masterDnsEngine = engine
        config.masterDnsConfig = raw
        config.masterDnsResolvers = normalizeResolvers(resolvers)
        config.server = document.domains.first()
        config.serverPort = null
        config.remarks = config.remarks.trim().ifEmpty { DEFAULT_REMARKS }
        return null
    }

    /** True when the text is an official config with the fields needed to identify it as MasterDNS. */
    internal fun fromDocument(raw: String, remarks: String, resolvers: String?): ProfileItem? {
        val document = document(raw.trim()) ?: return null
        // A generic TOML/JSON document is not claimed as MasterDNS merely because it has a domains key.
        if (document.domains.isEmpty() || (document.encryptionKey == null && document.encryptionMethod != 0)) return null
        return ProfileItem.create(EConfigType.MASTERDNS).also { profile ->
            profile.remarks = remarks.trim().ifEmpty { DEFAULT_REMARKS }
            profile.masterDnsConfig = document.raw
            profile.masterDnsResolvers = normalizeResolvers(resolvers)
            profile.server = document.domains.first()
            profile.serverPort = null
        }.takeIf { normalize(it) == null }
    }

    private fun fromProfileEnvelope(uri: URI, scheme: String): ProfileItem? = with(WhiteDnsProfileFmt) {
        val envelope = fromUri(uri) ?: return null
        val engine = envelope.text("engine")?.trim()?.lowercase() ?: scheme
        if (engine != scheme || engine !in setOf(ENGINE_MASTERDNS, ENGINE_COTTENDNS)) return null
        val server = envelope.get("server")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val domain = server.text("domain")?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        val key = server.text("encryption_key")
        val method = server.number("encryption_method") ?: 1
        if (method !in 0..5 || (method != 0 && key.isNullOrBlank())) return null
        val remarks = envelope.text("name")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: if (engine == ENGINE_COTTENDNS) "CottenDNS" else DEFAULT_REMARKS
        val resolvers = envelope.stringArray("resolvers")?.joinToString("\n") ?: DEFAULT_RESOLVERS
        val raw = if (engine == ENGINE_COTTENDNS) {
            buildString {
                append("DOMAINS = [\"").append(domain).append("\"]\n")
                append("DATA_ENCRYPTION_METHOD = ").append(method).append('\n')
                if (key != null) append("ENCRYPTION_KEY = \"").append(key.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
            }
        } else {
            JsonObject().apply {
                add("DOMAINS", WhiteDnsProfileFmt.strings(listOf(domain)))
                addProperty("DATA_ENCRYPTION_METHOD", method)
                key?.let { addProperty("ENCRYPTION_KEY", it) }
            }.toString()
        }
        return fromDocument(raw, remarks, resolvers)?.also { profile ->
            profile.masterDnsEngine = engine
            normalize(profile)
        }
    }

    internal fun document(raw: String): Document? = when {
        raw.startsWith("{") -> jsonDocument(raw)
        else -> tomlDocument(raw)
    }

    private fun jsonDocument(raw: String): Document? {
        val json = try {
            JsonParser.parseString(raw)
        } catch (_: Exception) {
            return null
        }
        if (!json.isJsonObject) return null
        val obj = json.asJsonObject
        val domains = obj.stringArray("DOMAINS") ?: return null
        val key = if (obj.has("ENCRYPTION_KEY")) obj.text("ENCRYPTION_KEY") ?: return null else null
        val method = if (obj.has("DATA_ENCRYPTION_METHOD")) {
            obj.number("DATA_ENCRYPTION_METHOD") ?: return null
        } else {
            1
        }
        return Document(raw, Format.JSON, normalizeDomains(domains), method, key, obj.text("SOCKS5_PASS"))
    }

    private fun tomlDocument(raw: String): Document? {
        if (!raw.contains('=')) return null
        val statements = tomlStatements(raw)
        val domainsValue = statements["DOMAINS"] ?: return null
        val domains = tomlStringArray(domainsValue) ?: return null
        val key = if (statements.containsKey("ENCRYPTION_KEY")) {
            tomlString(statements.getValue("ENCRYPTION_KEY")) ?: return null
        } else {
            null
        }
        val method = if (statements.containsKey("DATA_ENCRYPTION_METHOD")) {
            statements.getValue("DATA_ENCRYPTION_METHOD")
                .substringBefore('#')
                .trim()
                .replace("_", "")
                .toIntOrNull() ?: return null
        } else {
            1
        }
        return Document(
            raw = raw,
            format = Format.TOML,
            domains = normalizeDomains(domains),
            encryptionMethod = method,
            encryptionKey = key,
            socksPass = statements["SOCKS5_PASS"]?.let(::tomlString),
        )
    }

    /** Collects top-level TOML assignments, including arrays spread over several lines. */
    private fun tomlStatements(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val lines = raw.lines()
        var index = 0
        val assignment = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(.*)$")
        while (index < lines.size) {
            val line = lines[index]
            val match = assignment.matchEntire(line)
            if (match == null) {
                index++
                continue
            }
            val key = match.groupValues[1].uppercase()
            val value = StringBuilder(match.groupValues[2])
            var depth = bracketDepth(value.toString())
            while (depth > 0 && index + 1 < lines.size) {
                index++
                value.append('\n').append(lines[index])
                depth = bracketDepth(value.toString())
            }
            result[key] = value.toString()
            index++
        }
        return result
    }

    /** Bracket depth outside quoted TOML strings; comments do not affect it. */
    private fun bracketDepth(text: String): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var comment = false
        for (char in text) {
            if (comment) {
                if (char == '\n') comment = false
                continue
            }
            if (quote != null) {
                if (quote == '"' && char == '\\' && !escaped) {
                    escaped = true
                    continue
                }
                if (char == quote && !escaped) quote = null
                escaped = false
                continue
            }
            when (char) {
                '#' -> comment = true
                '"', '\'' -> quote = char
                '[' -> depth++
                ']' -> depth--
            }
        }
        return depth
    }

    private fun tomlStringArray(value: String): List<String>? {
        val reader = TomlReader(value)
        reader.skipSpaceAndComments()
        if (!reader.consumeIf('[')) return null
        val entries = mutableListOf<String>()
        reader.skipSpaceAndComments()
        if (reader.consumeIf(']')) return entries.takeIf { reader.atEndAfterComments() }
        while (true) {
            reader.skipSpaceAndComments()
            val entry = reader.readString() ?: return null
            entries += entry
            reader.skipSpaceAndComments()
            when {
                reader.consumeIf(',') -> {
                    reader.skipSpaceAndComments()
                    if (reader.consumeIf(']')) break
                }
                reader.consumeIf(']') -> break
                else -> return null
            }
        }
        return entries.takeIf { reader.atEndAfterComments() }
    }

    private fun tomlString(value: String): String? {
        val reader = TomlReader(value)
        reader.skipSpaceAndComments()
        val result = reader.readString() ?: return null
        return result.takeIf { reader.atEndAfterComments() }
    }

    /** The small TOML lexical subset needed for the official top-level string/array fields. */
    private class TomlReader(private val text: String) {
        private var index = 0

        fun skipSpaceAndComments() {
            while (index < text.length) {
                when {
                    text[index].isWhitespace() -> index++
                    text[index] == '#' -> {
                        while (index < text.length && text[index] != '\n') index++
                    }
                    else -> return
                }
            }
        }

        fun atEndAfterComments(): Boolean {
            skipSpaceAndComments()
            return index == text.length
        }

        fun consumeIf(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index++
            return true
        }

        fun readString(): String? {
            if (index >= text.length) return null
            val start = index
            val quote = text[index]
            if (quote != '\"' && quote != '\'') return null
            index++
            var escaped = false
            while (index < text.length) {
                val char = text[index++]
                if (char == '\n' || char == '\r') return null
                if (quote == '\"' && char == '\\' && !escaped) {
                    escaped = true
                    continue
                }
                if (char == quote && !escaped) {
                    val raw = text.substring(start, index)
                    return if (quote == '\'') raw.substring(1, raw.length - 1) else decodeJsonString(raw)
                }
                escaped = false
            }
            return null
        }
    }

    private fun normalizeDomains(domains: List<String>): List<String> = domains
        .map { it.trim().lowercase().removeSuffix(".") }
        .filter { it.isNotEmpty() && it != "." }
        .distinct()
        .sortedWith(compareByDescending<String> { it.length }.thenBy { it })

    private fun decodeJsonString(text: String): String? = try {
        JsonParser.parseString(text).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.number(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()

    private fun JsonObject.stringArray(key: String): List<String>? {
        val array = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val values = mutableListOf<String>()
        for (element in array) {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
            values += element.asString
        }
        return values
    }

    /** Resolver lines as the upstream client_resolvers.txt accepts them. */
    fun normalizeResolvers(text: String?): String {
        val lines = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val withDefault = if (lines.none { !it.startsWith("#") }) lines + DEFAULT_RESOLVERS else lines
        return withDefault.joinToString("\n") + "\n"
    }

    fun validResolvers(text: String?): Boolean {
        val entries = text?.lines()?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }
            .orEmpty()
        // Blank means the documented upstream sample default, not an invalid profile.
        return entries.all(::validResolver)
    }

    /** IPv4/IPv6 addresses and CIDR ranges, with the optional port forms of the official client. */
    internal fun validResolver(value: String): Boolean {
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        var text = value
        var port: String? = null
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) return false
            val tail = text.substring(end + 1)
            // Upstream accepts brackets only for the host:port form, not as a bare address.
            if (!tail.startsWith(":")) return false
            port = tail.substring(1)
            text = text.substring(1, end)
        } else {
            val colonCount = text.count { it == ':' }
            if (colonCount == 1) {
                port = text.substringAfterLast(':')
                text = text.substringBeforeLast(':')
            } else if (colonCount > 1) {
                // A bare IPv6 address or prefix has no port.
            }
        }
        if (port != null && (port.toIntOrNull() !in 1..65535)) return false

        val address = text.substringBefore('/')
        val prefix = text.substringAfter('/', missingDelimiterValue = "")
        if ('/' in text && prefix.isEmpty()) return false
        if (prefix.isNotEmpty()) {
            val number = prefix.toIntOrNull() ?: return false
            val ipv6 = address.contains(':')
            val max = if (ipv6) 128 else 32
            if (number !in 0..max) return false
            val hostBits = max - number
            // LoadClientResolvers skips prefixes expanding beyond 65536 hosts. Its IPv4 /1
            // special case is retained; all other oversized ranges are refused before startup.
            if (hostBits > 16 && !(!ipv6 && hostBits == 31)) return false
        }
        return if (address.contains(':')) validIpv6(address) else validIpv4(address)
    }

    private fun validIpv4(address: String): Boolean {
        val parts = address.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                (part.length == 1 || !part.startsWith('0')) && part.toIntOrNull() in 0..255
        }
    }

    private fun validIpv6(address: String): Boolean {
        if (address.count { it == ':' } < 2 || address.any {
                !(it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.')
            }
        ) return false
        val double = address.indexOf("::")
        if (double >= 0 && address.indexOf("::", double + 2) >= 0) return false
        val left = if (double >= 0) address.substring(0, double) else address
        val right = if (double >= 0) address.substring(double + 2) else ""
        if (left.contains("::") || right.contains("::")) return false
        val groups = (if (left.isEmpty()) emptyList() else left.split(':')) +
            (if (right.isEmpty()) emptyList() else right.split(':'))
        if (groups.any { it.isEmpty() }) return false
        val hasIpv4 = groups.lastOrNull()?.contains('.') == true
        if (address.contains('.') && (!hasIpv4 || address.endsWith("::"))) return false
        val hexadecimal = if (hasIpv4) groups.dropLast(1) else groups
        if (hexadecimal.any { group -> group.length !in 1..4 || group.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' } }) return false
        if (hasIpv4 && !validIpv4(groups.last())) return false
        val groupCount = hexadecimal.size + if (hasIpv4) 2 else 0
        return if (double >= 0) groupCount < 8 else groupCount == 8
    }

    /** Values which must never reach the application log from a core error or diagnostic line. */
    internal fun secrets(raw: String): List<String> {
        val doc = document(raw) ?: return emptyList()
        return listOfNotNull(doc.encryptionKey, doc.socksPass)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
    }

    internal fun redact(line: String, rawConfig: String): String {
        var text = line
        secrets(rawConfig).forEach { secret -> text = text.replace(secret, "***") }
        return if (text.length > 2_000) text.take(2_000) + "…" else text
    }

    private fun encodePayload(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    private fun decodePayload(text: String): String? {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || compact.length > MAX_CONFIG_CHARS * 2) return null
        val bytes = sequenceOf(
            runCatching { Base64.getUrlDecoder().decode(compact) },
            runCatching { Base64.getMimeDecoder().decode(compact) },
        ).mapNotNull(Result<ByteArray>::getOrNull).firstOrNull() ?: return null
        if (bytes.size > MAX_CONFIG_CHARS) return null
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun queryParameters(raw: String): Map<String, String> = raw.split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            runCatching {
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrNull()
        }
        .toMap()
}
