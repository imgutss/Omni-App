package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import java.net.URI
import java.security.MessageDigest

/**
 * Validated inputs and the loopback topology of one GUTS session.
 *
 * A GUTS profile carries the doors (Google Apps Script web apps), the key they check, and the
 * Google edge the client pins. Exit mode also has an exit node check that key; fetch mode has no
 * exit node and instead terminates HTTPS with the user's local CA. The native client owns
 * everything else: it opens a SOCKS5 listener on [port], and Xray dials that listener as an
 * ordinary SOCKS outbound, so the core knows nothing about doors or Google fronting.
 *
 * The client is told the key through the environment rather than on its command line, because a
 * command line is readable by every process of the same user; see GutsCoreManager.
 */
data class GutsCore(
    val doors: List<String>,
    val key: String,
    val googleIp: String = AUTO_EDGE,
    val sni: String = DEFAULT_SNI,
    val frontHost: String = DEFAULT_FRONT_HOST,
    val port: Int = DEFAULT_SOCKS_PORT,
    val mode: Mode = Mode.EXIT,
) {
    init {
        require(port in 1024..65534) { "Invalid GUTS listen port" }
        require(doors.size in 1..MAX_DOORS) { "Invalid GUTS door list" }
        require(doors.all { validDoor(it) }) { "Invalid GUTS door" }
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Invalid GUTS key" }
        require(validSni(sni)) { "Invalid GUTS SNI" }
        require(validHost(frontHost)) { "Invalid GUTS front host" }
        require(validEdge(googleIp)) { "Invalid GUTS Google edge address" }
    }

    /** The only loopback port the session reserves: the client's SOCKS5 listener. */
    val sessionPorts: List<Int> get() = listOf(port)

    /** Stable identity for matching a live process without logging a door or the key. */
    val identity: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        (doors + listOf(key, googleIp, sni, frontHost, port.toString(), mode.wire)).forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** How a door carries traffic: raw TCP through an exit node, or web fetches by Apps Script itself. */
    enum class Mode(val wire: String) {
        EXIT("exit"),
        FETCH("fetch"),
        ;

        companion object {
            fun fromWire(raw: String?): Mode? = when (raw?.trim()?.lowercase()) {
                null, "" -> EXIT
                EXIT.wire -> EXIT
                FETCH.wire -> FETCH
                else -> null
            }
        }
    }

    /** What the editor reports about a profile; the order is the order the screen checks in. */
    enum class Problem { DOOR_MISSING, DOOR_INVALID, KEY_MISSING, KEY_INVALID, MODE_INVALID, EDGE_INVALID, SNI_INVALID }

    /** A log line of the client with the key taken out, in case an engine ever echoes it. */
    fun redact(text: String): String =
        if (key.isEmpty()) text else text.replace(key, REDACTED)

    companion object {
        /** Above MasterDNS (18000) and DNSTT (18001) so a session of each could coexist. */
        const val DEFAULT_SOCKS_PORT = 18002
        const val AUTO_EDGE = "auto"
        const val DEFAULT_SNI = "www.google.com"
        const val DEFAULT_FRONT_HOST = "script.google.com"
        /**
         * Fetch mode's readiness probe is deliberately plain HTTP and not a Google host:
         * UrlFetchApp refuses Google-owned destinations, and Xray's own TLS stack need not trust
         * the user's local CA for Omni to prove that the fetch path itself works.
         */
        const val FETCH_TEST_URL = "http://cp.cloudflare.com/generate_204"
        const val MAX_DOORS = 16
        const val MAX_EDGES = 32
        const val MAX_DOOR_LENGTH = 2048
        const val MAX_KEY_LENGTH = 512
        private const val REDACTED = "***"
        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.\\-]*[A-Za-z0-9])?$")

        /** @return null when [profile] can start a GUTS session, else the first problem found. */
        fun normalize(profile: ProfileItem): Problem? {
            val doors = doorsOf(profile)
            if (doors.isEmpty()) return Problem.DOOR_MISSING
            if (doors.size > MAX_DOORS || !doors.all { validDoor(it) }) return Problem.DOOR_INVALID
            val key = profile.gutsKey.orEmpty().trim()
            if (key.isEmpty()) return Problem.KEY_MISSING
            if (key.length > MAX_KEY_LENGTH || key.any { it.isWhitespace() }) return Problem.KEY_INVALID
            if (Mode.fromWire(profile.gutsMode) == null) return Problem.MODE_INVALID
            val edge = profile.gutsGoogleIp.orEmpty().trim().ifBlank { AUTO_EDGE }
            if (!validEdge(edge)) return Problem.EDGE_INVALID
            val sni = profile.gutsSni.orEmpty().trim().ifBlank { DEFAULT_SNI }
            if (!validSni(sni)) return Problem.SNI_INVALID
            return null
        }

        fun of(profile: ProfileItem, listenPort: Int = DEFAULT_SOCKS_PORT): GutsCore {
            val normalized = profile.copy()
            require(normalize(normalized) == null) { "Invalid GUTS profile" }
            val rawEdge = normalized.gutsGoogleIp.orEmpty().trim().ifBlank { AUTO_EDGE }
            return GutsCore(
                doors = doorsOf(normalized),
                key = normalized.gutsKey.orEmpty().trim(),
                googleIp = if (rawEdge == AUTO_EDGE) AUTO_EDGE else edgesIn(rawEdge).joinToString(","),
                sni = normalized.gutsSni.orEmpty().trim().ifBlank { DEFAULT_SNI },
                port = listenPort,
                mode = Mode.fromWire(normalized.gutsMode) ?: Mode.EXIT,
            )
        }

        /** The doors of [profile], in the order the client should try them; see [doorsIn]. */
        fun doorsOf(profile: ProfileItem): List<String> = doorsIn(profile.gutsDoors)

        /** The mode of [profile]; an old profile with no mode is an exit-mode profile. */
        fun modeOf(profile: ProfileItem): Mode? = Mode.fromWire(profile.gutsMode)

        /**
         * Doors in the order the client should try them: one per line, or separated by commas,
         * semicolons, or spaces, which is how a pasted list usually arrives.
         */
        fun doorsIn(text: String?): List<String> =
            text.orEmpty()
                .split('\n', '\r', ',', ';', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /**
         * Edge addresses in the order to test them: one per line, or separated by commas,
         * semicolons, or spaces, which is how a manual list for a national network usually arrives.
         */
        fun edgesIn(text: String?): List<String> =
            text.orEmpty()
                .split('\n', '\r', ',', ';', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** A door is an https web app URL; http is allowed for a door emulator on the desk. */
        fun validDoor(raw: String): Boolean {
            if (raw.length > MAX_DOOR_LENGTH || raw.any { it.isWhitespace() }) return false
            val uri = runCatching { URI(raw) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
        }

        fun validSni(raw: String): Boolean = validHost(raw)

        /**
         * "auto" scans the built-in and line-resolved pools. A literal address pins one edge; a
         * short list of them is the manual national-network list, scanned as a closed set.
         */
        fun validEdge(raw: String): Boolean {
            if (raw.trim() == AUTO_EDGE) return true
            val edges = edgesIn(raw)
            return edges.size in 1..MAX_EDGES && edges.all { it != AUTO_EDGE && validSingleEdge(it) }
        }

        private fun validSingleEdge(raw: String): Boolean = validHost(raw) || validDottedLiteral(raw)

        private fun validHost(raw: String): Boolean =
            raw.isNotEmpty() && raw.length <= 253 && HOST.matches(raw) && !raw.startsWith("-")

        /** An IPv4 or IPv6 literal keeps a pin that no name resolution can move. */
        private fun validDottedLiteral(raw: String): Boolean {
            val host = raw.removePrefix("[").substringBefore(']')
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) return false
            return host.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }
}
