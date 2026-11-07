package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTorBridges
import com.v2ray.ang.enums.EConfigType
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Reader for SlipNet's public, plain-text profile exchange format.
 *
 * SlipNet's plain URI is not a URL with query parameters: the part after
 * slipnet:// is Base64 of a pipe-delimited record. This reader deliberately
 * lives in Omni and does not import SlipNet code. It handles the stable field
 * prefix used by versions 2–29 and ignores unknown trailing fields, just as
 * SlipNet's forward-compatible importer does.
 *
 * Only protocols with a real Omni engine are returned. A dnstt_ssh record is
 * imported only with its SSH credentials and the real chained lifecycle; an
 * otherwise unsupported record is never made to look usable just because its
 * Base64 decoded successfully.
 */
object SlipNetFmt : FmtBase() {
    private const val SCHEME = "slipnet://"
    private const val MAX_ENCODED_CHARS = 2_000_000
    private const val MODE_DNSTT = "dnstt"
    private const val MODE_DNSTT_SSH = "dnstt_ssh"
    private const val MODE_NOIZDNS = "sayedns"
    private const val MODE_NOIZDNS_SSH = "sayedns_ssh"
    private const val MODE_VAYDNS = "vaydns"
    private const val MODE_VAYDNS_SSH = "vaydns_ssh"
    private const val MODE_SLIPSTREAM = "ss"
    private const val MODE_SLIPSTREAM_SSH = "slipstream_ssh"
    private const val MODE_SNOWFLAKE = "snowflake"

    /**
     * SlipNet's blank Snowflake mode is a built-in Snowflake configuration, not Aether's automatic
     * public-relay mode. These are ordinary Tor bridge lines, so the imported profile remains
     * editable and the pinned lyrebird can supply the same rendezvous parameters.
     */
    private const val SNOWFLAKE_ICE =
        "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443,stun:stun.sipgate.net:3478," +
            "stun:stun.sipgate.net:10000,stun:stun.sonetel.com:3478,stun:stun.voipia.net:3478," +
            "stun:stun.ucsb.edu:3478,stun:stun.schlund.de:3478"
    private const val SNOWFLAKE_FINGERPRINT = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"

    /** AMP is a rendezvous option of the same Snowflake transport, supported by lyrebird's ampcache arg. */
    private val builtInSnowflakeAmpBridge =
        "snowflake 192.0.2.3:80 $SNOWFLAKE_FINGERPRINT " +
            "fingerprint=$SNOWFLAKE_FINGERPRINT url=https://snowflake-broker.torproject.net/ " +
            "fronts=www.google.com ice=$SNOWFLAKE_ICE utls-imitate=hellorandomizedalpn " +
            "ampcache=https://cdn.ampproject.org/"
    private val builtInSnowflakeBridges =
        "snowflake 192.0.2.3:80 $SNOWFLAKE_FINGERPRINT " +
            "fingerprint=$SNOWFLAKE_FINGERPRINT url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=www.cdn77.com ice=$SNOWFLAKE_ICE utls-imitate=hellorandomizedalpn"

    /** Parses one original slipnet:// URI, without a conversion step. */
    fun parse(str: String): ProfileItem? {
        val trimmed = str.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val encoded = trimmed.substring(SCHEME.length).trim()
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
        val decoded = decode(encoded) ?: return null
        return parseDecoded(decoded)
    }

    fun parseAny(str: String): ProfileItem? = parse(str)

    /**
     * Decodes the common v2+ record prefix. Versions before v2 predate DNSTT;
     * newer versions are accepted when their known prefix remains intact.
     */
    internal fun parseDecoded(data: String): ProfileItem? {
        if (data.isBlank() || data.length > MAX_ENCODED_CHARS) return null
        val fields = data.split('|')
        if (fields.size < 14) return null
        val version = fields[0].toIntOrNull() ?: return null
        if (version < 2) return null

        val mode = fields[1].trim().lowercase()
        if (mode == MODE_SNOWFLAKE) return parseSnowflake(fields)
        val engine = when (mode) {
            MODE_DNSTT, MODE_DNSTT_SSH -> DnsttFmt.ENGINE_DNSTT
            MODE_NOIZDNS, MODE_NOIZDNS_SSH -> DnsttFmt.ENGINE_NOIZDNS
            MODE_VAYDNS, MODE_VAYDNS_SSH -> DnsttFmt.ENGINE_VAYDNS
            MODE_SLIPSTREAM, MODE_SLIPSTREAM_SSH -> DnsttFmt.ENGINE_SLIPSTREAM
            else -> return null
        }
        val isNoiz = engine == DnsttFmt.ENGINE_NOIZDNS
        val isSsh = mode in setOf(MODE_DNSTT_SSH, MODE_NOIZDNS_SSH, MODE_VAYDNS_SSH, MODE_SLIPSTREAM_SSH)
        // A legacy sshEnabled marker without the dedicated tunnel type is not
        // enough evidence to choose a different lifecycle.
        if (!isSsh && fields.getOrNull(14) == "1") return null

        val name = fields[2].trim()
        val domain = fields[3].trim()
        val publicKey = fields[11].trim()
        if (name.isEmpty() || domain.isEmpty() || (engine != DnsttFmt.ENGINE_SLIPSTREAM && publicKey.isEmpty())) return null

        val transport = fields.getOrNull(22)?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: "udp"
        if (transport !in setOf("udp", "tcp", "dot", "doh")) return null

        // v17 moved hidden resolvers to field 37 while leaving field 4 empty.
        val resolverData = if (fields.getOrNull(36) == "1" &&
            !fields.getOrNull(37).isNullOrBlank()
        ) fields[37] else fields[4]
        var resolverAuthorities: List<Boolean> = emptyList()
        val resolver = if (transport == "doh") {
            fields.getOrNull(21)?.trim().orEmpty()
        } else {
            val parsed = canonicalResolvers(resolverData, transport) ?: return null
            resolverAuthorities = parsed.authoritativeFlags
            parsed.endpoints
        }
        if (resolver.isEmpty()) return null

        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = name
            // Each record keeps its actual protocol/engine instead of collapsing to NoizDNS.
            it.dnsttEngine = engine
            it.dnsttTransport = transport
            it.dnsttResolver = resolver
            it.dnsttResolverAuthorities = resolverAuthorities.takeIf { flags ->
                flags.isNotEmpty() && flags.size == resolver.split(',').size
            }?.joinToString(",") { flag -> if (flag) "1" else "0" }
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttUtls = DnsttFmt.DEFAULT_UTLS
            // The ordinary engine switch is pool-wide. Infer it only when
            // every marker agrees; mixed markers stay in the profile and are
            // handled by the per-resolver composite at process start.
            it.dnsttAuthoritative = (engine == DnsttFmt.ENGINE_SLIPSTREAM && fields.getOrNull(5) == "1") ||
                fields.getOrNull(27) == "1" ||
                (resolverAuthorities.isNotEmpty() && resolverAuthorities.all { flag -> flag })
            it.dnsttNoiz = isNoiz
            it.dnsttStealth = isNoiz && fields.getOrNull(38) == "1"
            it.dnsttMaxPayload = fields.getOrNull(39)?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
            if (engine == DnsttFmt.ENGINE_SLIPSTREAM) {
                it.slipstreamKeepAlive = fields.getOrNull(6)?.toIntOrNull() ?: 5_000
                it.slipstreamCongestionControl = fields.getOrNull(7)?.trim()?.lowercase()?.ifEmpty { "bbr" } ?: "bbr"
                it.slipstreamGsoEnabled = fields.getOrNull(10) == "1"
            }
            if (engine == DnsttFmt.ENGINE_VAYDNS) {
                it.vaydnsDnsttCompat = fields.getOrNull(41) == "1"
                it.vaydnsRecordType = fields.getOrNull(42)?.trim()?.ifEmpty { "txt" } ?: "txt"
                it.vaydnsMaxQnameLen = fields.getOrNull(43)?.toIntOrNull() ?: 101
                it.vaydnsRps = fields.getOrNull(44)?.toDoubleOrNull() ?: 0.0
                it.vaydnsIdleTimeout = fields.getOrNull(45)?.toIntOrNull() ?: 0
                it.vaydnsKeepAlive = fields.getOrNull(46)?.toIntOrNull() ?: 0
                it.vaydnsUdpTimeout = fields.getOrNull(47)?.toIntOrNull() ?: 0
                it.vaydnsMaxNumLabels = fields.getOrNull(48)?.toIntOrNull() ?: 0
                it.vaydnsClientIdSize = fields.getOrNull(49)?.toIntOrNull() ?: 0
            }
            it.dnsttResolverMode = fields.getOrNull(60)?.trim()?.lowercase()
                ?.takeIf { mode -> mode == "fanout" || mode == "roundrobin" } ?: "fanout"
            it.dnsttRrSpreadCount = fields.getOrNull(61)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
            it.dnsttSocksUsername = if (isSsh) null else fields.getOrNull(12)?.takeIf { value -> value.isNotEmpty() }
            it.dnsttSocksPassword = if (isSsh) null else fields.getOrNull(13)?.takeIf { value -> value.isNotEmpty() }
            it.dnsttSshEnabled = isSsh
            if (isSsh) {
                it.dnsttSshUsername = fields.getOrNull(15)
                it.dnsttSshPassword = fields.getOrNull(16)
                it.dnsttSshPort = fields.getOrNull(17)?.toIntOrNull() ?: 22
                it.dnsttSshForwardDns = fields.getOrNull(18) == "1"
                it.dnsttSshHost = fields.getOrNull(19)
                it.dnsttSshAuthType = fields.getOrNull(23)?.trim()?.lowercase()?.ifEmpty { "password" } ?: "password"
                it.dnsttSshPrivateKey = fields.getOrNull(24)?.takeIf { value -> value.isNotEmpty() }?.let(::decode).orEmpty()
                it.dnsttSshKeyPassphrase = fields.getOrNull(25)?.takeIf { value -> value.isNotEmpty() }?.let(::decode).orEmpty()
            }
        }
        return profile.takeIf { DnsttFmt.normalize(it) == null }
    }

    /** Imports SlipNet's Tor record without routing it through the DNSTT validator. */
    private fun parseSnowflake(fields: List<String>): ProfileItem? {
        val name = fields.getOrNull(2)?.trim().orEmpty()
        if (name.isEmpty()) return null
        val encoded = fields.getOrNull(26)?.trim().orEmpty()
        val decoded = if (encoded.isEmpty()) "" else decode(encoded) ?: return null
        if (decoded.indexOf('\u0000') >= 0) return null

        val mode = decoded.trim()
        val direct = mode.equals("DIRECT", ignoreCase = true)
        val amp = mode.equals("SNOWFLAKE_AMP", ignoreCase = true)
        val smart = mode.equals("SMART", ignoreCase = true)
        val bridgeText = when {
            direct || smart -> null
            amp -> builtInSnowflakeAmpBridge
            decoded.isBlank() -> builtInSnowflakeBridges
            else -> decoded
        }
        if (bridgeText != null && AetherFmt.bridgeLines(bridgeText).isEmpty()) return null

        val profile = ProfileItem.create(EConfigType.AETHER).also {
            it.remarks = name
            it.aetherTor = AetherTor.ONLY.type
            it.aetherTorBridges = when {
                direct -> AetherTorBridges.NEVER.type
                // The native SMART policy preserves Direct → obfs4 → meek_lite → Snowflake,
                // including the per-transport timeouts, rather than substituting Aether AUTO.
                smart -> AetherTorBridges.SMART.type
                else -> AetherTorBridges.OWN.type
            }
            // Keep custom records byte-for-byte; AetherFmt normalizes torrc-compatible whitespace
            // and Bridge prefixes only when the editor subsequently saves the profile.
            it.aetherTorBridgeLines = bridgeText
        }
        // Validate a copy so normalization cannot alter the preserved bridge text in the import.
        val validation = profile.copy()
        return profile.takeIf { AetherFmt.normalize(validation) == null }
    }

    private data class CanonicalResolvers(
        val endpoints: String,
        val authoritativeFlags: List<Boolean>,
    )

    /** Returns printable host:port endpoints and keeps each authority flag as separate metadata. */
    private fun canonicalResolvers(raw: String, transport: String): CanonicalResolvers? {
        if (raw.isBlank()) return null
        val result = mutableListOf<String>()
        val authoritativeFlags = mutableListOf<Boolean>()
        raw.split(',').forEach { entry ->
            val value = entry.trim()
            if (value.isEmpty() || value.any { it.isWhitespace() || it == '\u0000' }) return null
            val hostPort = when {
                value.startsWith('[') -> {
                    val close = value.indexOf(']')
                    if (close < 0) return null
                    val host = value.substring(1, close)
                    val rest = value.substring(close + 1)
                    val pieces = rest.split(':')
                    if (pieces.size > 3 || pieces.getOrNull(0) != "") return null
                    val port = pieces.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 53
                    if (host.isBlank() || port !in 1..65535 ||
                        (pieces.size == 3 && pieces[2] !in setOf("0", "1"))) return null
                    val authoritative = pieces.getOrNull(2) == "1"
                    authoritativeFlags += authoritative
                    "[$host]:${dnsPort(port, transport)}"
                }
                else -> {
                    val pieces = value.split(':')
                    if (pieces.size !in 2..3) return null
                    val host = pieces[0]
                    val port = pieces[1].toIntOrNull() ?: 53
                    if (host.isBlank() || port !in 1..65535 || host.contains(':') ||
                        (pieces.size == 3 && pieces[2] !in setOf("0", "1"))) return null
                    val authoritative = pieces.getOrNull(2) == "1"
                    authoritativeFlags += authoritative
                    "$host:${dnsPort(port, transport)}"
                }
            }
            result += hostPort
        }
        return result.takeIf { it.isNotEmpty() }?.let {
            CanonicalResolvers(it.joinToString(","), authoritativeFlags)
        }
    }

    private fun dnsPort(port: Int, transport: String): Int =
        if (transport == "dot" && port == 53) 853 else port

    private fun decode(encoded: String): String? {
        val compact = encoded.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact.length % 4 == 1) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = listOf(
            runCatching { Base64.getDecoder().decode(padded) },
            runCatching { Base64.getUrlDecoder().decode(padded) },
        ).firstNotNullOfOrNull { it.getOrNull() } ?: return null
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
    }
}
