package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Omni's DNS tunnel profile format for DNSTT, NoizDNS, VayDNS, and Slipstream.
 * A standalone remote end must forward the raw tunnel to a SOCKS5 service for
 * Omni's loopback SOCKS outbound to be meaningful. The URI is Omni's own
 * format, not a claim that another application's share link is identical.
 */
object DnsttFmt : FmtBase() {
    const val DEFAULT_UTLS = "4*random,3*Firefox_120,1*Firefox_105,3*Chrome_120,1*Chrome_102,1*iOS_14,1*iOS_13"
    const val ENGINE_OFFICIAL = "official"
    const val ENGINE_NOIZDNS = "noizdns"
    const val ENGINE_DNSTT = "dnstt"
    const val ENGINE_VAYDNS = "vaydns"
    const val ENGINE_SLIPSTREAM = "slipstream"
    const val ENGINE_SOAR = "soar"
    private const val DEFAULT_REMARKS = "DNSTT"
    private val VAY_RECORD_TYPES = setOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv", "null", "caa")

    enum class Transport(val type: String, val defaultPort: Int) {
        DOH("doh", 443), DOT("dot", 853), UDP("udp", 53), TCP("tcp", 53);

        companion object {
            fun from(value: String?): Transport? = entries.firstOrNull { it.type.equals(value?.trim(), true) }
        }
    }

    enum class Problem {
        DOMAIN_MISSING,
        DOMAIN_INVALID,
        TRANSPORT_INVALID,
        RESOLVER_MISSING,
        RESOLVER_INVALID,
        PUBLIC_KEY_MISSING,
        PUBLIC_KEY_INVALID,
        UTLS_INVALID,
        SSH_INVALID,
    }

    fun parse(str: String): ProfileItem? {
        val uri = runCatching { URI(str.trim()) }.getOrNull() ?: return null
        if (uri.scheme.equals("soar", true)) return SoarProfileFmt.parse(str)
        if (!uri.scheme.equals("dnstt", true)) return null
        val query = queryParameters(uri.rawQuery.orEmpty())
        val transport = Transport.from(query["transport"])
            ?: Transport.entries.firstOrNull { query[it.type] != null }
            ?: return null
        val resolver = query["resolver"] ?: query[transport.type] ?: return null
        val domain = query["domain"] ?: query["d"] ?: return null
        val publicKey = query["pubkey"] ?: query["key"] ?: ""
        val remarks = uri.rawFragment
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_REMARKS
        // Build the complete profile before validating it. In particular, the
        // NoizDNS engine and its multi-resolver/authoritative options must be
        // present during the first normalization; validating a temporary
        // official-DNSTT profile would reject valid Omni URIs prematurely.
        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = remarks
            it.dnsttTransport = transport.type
            it.dnsttResolver = resolver
            it.dnsttResolverAuthorities = query["resolverAuthorities"]
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttUtls = query["utls"] ?: DEFAULT_UTLS
            it.dnsttEngine = query["engine"]
            it.dnsttAuthoritative = query["authoritative"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttNoiz = query["noiz"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttStealth = query["stealth"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttMaxPayload = query["maxPayload"]?.toIntOrNull()
            it.vaydnsDnsttCompat = query["vayCompat"]?.let { value -> value == "1" || value.equals("true", true) }
            it.vaydnsRecordType = query["vayRecordType"]
            it.vaydnsMaxQnameLen = query["vayMaxQnameLen"]?.toIntOrNull()
            it.vaydnsRps = query["vayRps"]?.toDoubleOrNull()
            it.vaydnsIdleTimeout = query["vayIdleTimeout"]?.toIntOrNull()
            it.vaydnsKeepAlive = query["vayKeepAlive"]?.toIntOrNull()
            it.vaydnsUdpTimeout = query["vayUdpTimeout"]?.toIntOrNull()
            it.vaydnsMaxNumLabels = query["vayMaxNumLabels"]?.toIntOrNull()
            it.vaydnsClientIdSize = query["vayClientIdSize"]?.toIntOrNull()
            it.slipstreamKeepAlive = query["slipKeepAlive"]?.toIntOrNull()
            it.slipstreamCongestionControl = query["slipCc"]
            it.slipstreamGsoEnabled = query["slipGso"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttResolverMode = query["resolverMode"]
            it.dnsttRrSpreadCount = query["rrSpread"]?.toIntOrNull()
            it.dnsttSocksUsername = query["socksUsername"]
            it.dnsttSocksPassword = query["socksPassword"]
            it.dnsttSshEnabled = query["ssh"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttSshForwardDns = query["sshForwardDns"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttSshUsername = query["sshUsername"]
            it.dnsttSshPassword = query["sshPassword"]
            it.dnsttSshHost = query["sshHost"]
            it.dnsttSshPort = query["sshPort"]?.toIntOrNull()
            it.dnsttSshAuthType = query["sshAuthType"]
            it.dnsttSshPrivateKey = query["sshPrivateKey"]
            it.dnsttSshKeyPassphrase = query["sshKeyPassphrase"]
        }
        return profile.takeIf { normalize(it) == null }
    }

    /** Only the explicit dnstt:// form is accepted here; raw CLI text is not guessed as a profile. */
    fun parseAny(str: String): ProfileItem? = parse(str)

    fun toUri(config: ProfileItem): String {
        if (config.dnsttEngine == ENGINE_SOAR) return SoarProfileFmt.toUri(config)
        val normalized = config.copy()
        normalize(normalized)
        val query = linkedMapOf(
            "transport" to normalized.dnsttTransport.orEmpty(),
            "resolver" to normalized.dnsttResolver.orEmpty(),
            "domain" to normalized.dnsttDomain.orEmpty(),
            "pubkey" to normalized.dnsttPublicKey.orEmpty(),
            "utls" to normalized.dnsttUtls.orEmpty(),
        )
        if (normalized.dnsttEngine != ENGINE_OFFICIAL) query["engine"] = normalized.dnsttEngine.orEmpty()
        normalized.dnsttResolverAuthorities?.takeIf { it.isNotBlank() }?.let { query["resolverAuthorities"] = it }
        if (normalized.dnsttAuthoritative == true) query["authoritative"] = "1"
        if (normalized.dnsttNoiz == true) query["noiz"] = "1"
        if (normalized.dnsttStealth == true) query["stealth"] = "1"
        normalized.dnsttMaxPayload?.takeIf { it > 0 }?.let { query["maxPayload"] = it.toString() }
        if (normalized.dnsttEngine == ENGINE_VAYDNS) {
            if (normalized.vaydnsDnsttCompat == true) query["vayCompat"] = "1"
            query["vayRecordType"] = normalized.vaydnsRecordType ?: "txt"
            query["vayMaxQnameLen"] = (normalized.vaydnsMaxQnameLen ?: 101).toString()
            query["vayRps"] = (normalized.vaydnsRps ?: 0.0).toString()
            query["vayIdleTimeout"] = (normalized.vaydnsIdleTimeout ?: 0).toString()
            query["vayKeepAlive"] = (normalized.vaydnsKeepAlive ?: 0).toString()
            query["vayUdpTimeout"] = (normalized.vaydnsUdpTimeout ?: 0).toString()
            query["vayMaxNumLabels"] = (normalized.vaydnsMaxNumLabels ?: 0).toString()
            query["vayClientIdSize"] = (normalized.vaydnsClientIdSize ?: 0).toString()
        }
        if (normalized.dnsttEngine == ENGINE_SLIPSTREAM) {
            query["slipKeepAlive"] = (normalized.slipstreamKeepAlive ?: 5_000).toString()
            query["slipCc"] = normalized.slipstreamCongestionControl ?: "bbr"
            if (normalized.slipstreamGsoEnabled == true) query["slipGso"] = "1"
        }
        normalized.dnsttResolverMode?.takeIf { it.isNotBlank() }?.let { query["resolverMode"] = it }
        normalized.dnsttRrSpreadCount?.takeIf { it > 0 }?.let { query["rrSpread"] = it.toString() }
        normalized.dnsttSocksUsername?.takeIf { it.isNotEmpty() }?.let { query["socksUsername"] = it }
        normalized.dnsttSocksPassword?.takeIf { it.isNotEmpty() }?.let { query["socksPassword"] = it }
        if (normalized.dnsttSshEnabled == true) {
            query["ssh"] = "1"
            if (normalized.dnsttSshForwardDns == true) query["sshForwardDns"] = "1"
            query["sshUsername"] = normalized.dnsttSshUsername.orEmpty()
            query["sshPassword"] = normalized.dnsttSshPassword.orEmpty()
            query["sshHost"] = normalized.dnsttSshHost ?: "127.0.0.1"
            query["sshPort"] = (normalized.dnsttSshPort ?: 22).toString()
            query["sshAuthType"] = normalized.dnsttSshAuthType ?: "password"
            if (normalized.dnsttSshAuthType == "key") {
                query["sshPrivateKey"] = normalized.dnsttSshPrivateKey.orEmpty()
                normalized.dnsttSshKeyPassphrase?.takeIf { it.isNotEmpty() }?.let { query["sshKeyPassphrase"] = it }
            }
        }
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "?$encoded#${encode(normalized.remarks.ifBlank { DEFAULT_REMARKS })}"
    }

    fun fromValues(
        remarks: String,
        transport: String,
        resolver: String,
        domain: String,
        publicKey: String,
        utls: String = DEFAULT_UTLS,
    ): ProfileItem? {
        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = remarks
            it.dnsttTransport = transport
            it.dnsttResolver = resolver
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttUtls = utls
        }
        return profile.takeIf { normalize(it) == null }
    }

    /** Validates and canonicalizes the fields consumed by DnsttCore. */
    fun normalize(config: ProfileItem): Problem? {
        val domain = normalizeDomain(config.dnsttDomain ?: config.server)
            ?: return if (config.dnsttDomain.isNullOrBlank() && config.server.isNullOrBlank()) {
                Problem.DOMAIN_MISSING
            } else {
                Problem.DOMAIN_INVALID
            }
        val engine = config.dnsttEngine?.trim()?.lowercase()?.ifEmpty { ENGINE_OFFICIAL } ?: ENGINE_OFFICIAL
        if (engine !in setOf(ENGINE_OFFICIAL, ENGINE_NOIZDNS, ENGINE_DNSTT, ENGINE_VAYDNS, ENGINE_SLIPSTREAM, ENGINE_SOAR)) return Problem.TRANSPORT_INVALID
        val transport = Transport.from(config.dnsttTransport) ?: return Problem.TRANSPORT_INVALID
        if (engine == ENGINE_SLIPSTREAM && transport != Transport.UDP) return Problem.TRANSPORT_INVALID
        if (engine == ENGINE_SOAR && transport !in setOf(Transport.UDP, Transport.TCP)) return Problem.TRANSPORT_INVALID
        val noiz = config.dnsttNoiz == true
        val stealth = config.dnsttStealth == true
        val maxPayload = config.dnsttMaxPayload ?: 0
        val resolverMode = config.dnsttResolverMode?.trim()?.lowercase()
        val rrSpread = config.dnsttRrSpreadCount ?: 3
        if (stealth && !noiz) return Problem.TRANSPORT_INVALID
        if (maxPayload !in 0..4096 || rrSpread !in 1..5) return Problem.TRANSPORT_INVALID
        if (!resolverMode.isNullOrBlank() && resolverMode !in setOf("fanout", "roundrobin")) {
            return Problem.TRANSPORT_INVALID
        }
        // The official upstream client has no TCP transport or SlipNet tuning knobs.
        if (engine == ENGINE_OFFICIAL && (transport == Transport.TCP ||
                config.dnsttAuthoritative == true || noiz || stealth || maxPayload != 0 ||
                (!resolverMode.isNullOrBlank() && resolverMode != "fanout") || rrSpread != 3 ||
                !config.dnsttSocksUsername.isNullOrEmpty() || !config.dnsttSocksPassword.isNullOrEmpty())
        ) return Problem.TRANSPORT_INVALID
        val soarSettings = if (engine == ENGINE_SOAR) {
            SoarProfileFmt.normalizeSettings(config.soarSettings) ?: return Problem.TRANSPORT_INVALID
        } else null
        val rawResolver = config.dnsttResolver?.trim().orEmpty()
        if (rawResolver.isEmpty() && (engine != ENGINE_SOAR || !SoarProfileFmt.discoveryEnabled(soarSettings))) {
            return Problem.RESOLVER_MISSING
        }
        val resolver = if (rawResolver.isEmpty() && engine == ENGINE_SOAR) {
            ""
        } else {
            normalizeResolver(transport, rawResolver, allowMultiple = engine != ENGINE_OFFICIAL)
                ?: return Problem.RESOLVER_INVALID
        }
        val resolverCount = resolver.split(',').size
        val storedAuthorities = authorityFlags(config.dnsttResolverAuthorities, resolverCount)
        val legacyAuthorities = resolverAuthorities(rawResolver).takeIf { it.size == resolverCount }
        val key = config.dnsttPublicKey?.trim().orEmpty()
        if (engine != ENGINE_SLIPSTREAM && key.isEmpty()) return Problem.PUBLIC_KEY_MISSING
        val soarKey = if (engine == ENGINE_SOAR) SoarProfileFmt.normalizePublicKey(key) else null
        if (engine == ENGINE_SOAR && soarKey == null) return Problem.PUBLIC_KEY_INVALID
        if (engine != ENGINE_SLIPSTREAM && engine != ENGINE_SOAR && !key.matches(Regex("(?i)[0-9a-f]{64}"))) return Problem.PUBLIC_KEY_INVALID
        if (engine == ENGINE_SOAR && (config.dnsttSshEnabled == true || noiz || stealth || maxPayload != 0 ||
                !config.dnsttSocksUsername.isNullOrEmpty() || !config.dnsttSocksPassword.isNullOrEmpty())
        ) return Problem.TRANSPORT_INVALID

        val vayRecordType = config.vaydnsRecordType?.trim()?.lowercase()?.ifEmpty { "txt" } ?: "txt"
        val vayMaxQname = config.vaydnsMaxQnameLen ?: 101
        val vayRps = config.vaydnsRps ?: 0.0
        val vayIdle = config.vaydnsIdleTimeout ?: 0
        val vayKeepAlive = config.vaydnsKeepAlive ?: 0
        val vayUdpTimeout = config.vaydnsUdpTimeout ?: 0
        val vayMaxLabels = config.vaydnsMaxNumLabels ?: 0
        val vayClientId = config.vaydnsClientIdSize ?: 0
        if (engine == ENGINE_VAYDNS) {
            if (noiz || stealth || vayRecordType !in VAY_RECORD_TYPES ||
                vayMaxQname !in 0..253 || !vayRps.isFinite() || vayRps < 0.0 || vayRps > 1_000_000.0 ||
                vayIdle !in 0..86_400 || vayKeepAlive !in 0..86_400 || vayUdpTimeout !in 0..600_000 ||
                vayMaxLabels !in 0..127 || vayClientId !in 0..32 ||
                (maxPayload != 0 && maxPayload < 25)
            ) return Problem.TRANSPORT_INVALID
        }
        val slipKeepAlive = config.slipstreamKeepAlive ?: 5_000
        val slipCongestionControl = config.slipstreamCongestionControl?.trim()?.lowercase()?.ifEmpty { "bbr" } ?: "bbr"
        if (engine == ENGINE_SLIPSTREAM && (noiz || stealth || maxPayload != 0 ||
                slipKeepAlive !in 100..60_000 || slipCongestionControl !in setOf("bbr", "dcubic"))
        ) return Problem.TRANSPORT_INVALID
        if (engine != ENGINE_OFFICIAL && (
                config.dnsttSocksUsername.orEmpty().toByteArray(Charsets.UTF_8).size > 255 ||
                config.dnsttSocksPassword.orEmpty().toByteArray(Charsets.UTF_8).size > 255)
        ) return Problem.TRANSPORT_INVALID

        val utls = config.dnsttUtls?.trim().orEmpty().ifEmpty { DEFAULT_UTLS }
        if (utls.length > 512 || utls.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return Problem.UTLS_INVALID
        }

        val sshEnabled = config.dnsttSshEnabled == true
        // The official client exposes SOCKS5, not the raw TCP stream that
        // JSch needs. Never let the editor save that incompatible pairing.
        if (sshEnabled && engine == ENGINE_OFFICIAL) return Problem.TRANSPORT_INVALID
        if (sshEnabled) {
            val sshUsername = config.dnsttSshUsername?.trim().orEmpty()
            val sshPassword = config.dnsttSshPassword.orEmpty()
            val sshHost = config.dnsttSshHost?.trim()?.ifEmpty { "127.0.0.1" } ?: "127.0.0.1"
            val sshPort = config.dnsttSshPort ?: 22
            val sshAuthType = config.dnsttSshAuthType?.trim()?.lowercase()?.ifEmpty { "password" } ?: "password"
            val sshPrivateKey = config.dnsttSshPrivateKey.orEmpty()
            val sshKeyPassphrase = config.dnsttSshKeyPassphrase.orEmpty()
            if (sshUsername.isEmpty() || sshUsername.length > 256 || sshUsername.any { it.isISOControl() } ||
                sshPassword.length > 16_384 || sshPassword.contains('\u0000') ||
                !validHost(sshHost) || sshPort !in 1..65535 ||
                (sshAuthType != "password" && sshAuthType != "key") ||
                (sshAuthType == "key" && sshPrivateKey.isBlank()) ||
                sshPrivateKey.length > 262_144 || sshPrivateKey.contains('\u0000') ||
                sshKeyPassphrase.length > 16_384 || sshKeyPassphrase.contains('\u0000')
            ) return Problem.SSH_INVALID
            config.dnsttSshForwardDns = config.dnsttSshForwardDns == true
            config.dnsttSshUsername = sshUsername
            config.dnsttSshPassword = sshPassword
            config.dnsttSshHost = sshHost
            config.dnsttSshPort = sshPort
            config.dnsttSshAuthType = sshAuthType
            config.dnsttSshPrivateKey = sshPrivateKey
            config.dnsttSshKeyPassphrase = sshKeyPassphrase
        } else {
            config.dnsttSshForwardDns = null
            config.dnsttSshUsername = null
            config.dnsttSshPassword = null
            config.dnsttSshHost = null
            config.dnsttSshPort = null
            config.dnsttSshAuthType = null
            config.dnsttSshPrivateKey = null
            config.dnsttSshKeyPassphrase = null
        }

        config.dnsttDomain = domain
        config.dnsttTransport = if (engine == ENGINE_SOAR) {
            if (SoarProfileFmt.settingsTransport(soarSettings) == "only") Transport.TCP.type else Transport.UDP.type
        } else {
            transport.type
        }
        config.dnsttResolver = resolver
        config.dnsttResolverAuthorities = if (engine == ENGINE_OFFICIAL || engine == ENGINE_SOAR || transport == Transport.DOH) {
            null
        } else {
            (storedAuthorities ?: legacyAuthorities)?.joinToString(",") { if (it) "1" else "0" }
        }
        config.dnsttPublicKey = when (engine) {
            ENGINE_SLIPSTREAM -> ""
            ENGINE_SOAR -> soarKey
            else -> key.lowercase()
        }
        config.soarSettings = soarSettings
        config.dnsttUtls = if (engine == ENGINE_SOAR) "" else utls
        config.dnsttEngine = engine
        config.dnsttAuthoritative = engine != ENGINE_SOAR && config.dnsttAuthoritative == true
        config.dnsttNoiz = noiz
        config.dnsttStealth = stealth
        config.dnsttMaxPayload = if (engine == ENGINE_SLIPSTREAM) 0 else maxPayload
        config.dnsttResolverMode = if (engine == ENGINE_SLIPSTREAM || engine == ENGINE_SOAR) "fanout" else resolverMode?.takeIf { it.isNotBlank() } ?: "fanout"
        config.dnsttRrSpreadCount = if (engine == ENGINE_SLIPSTREAM || engine == ENGINE_SOAR) 3 else rrSpread
        if (engine == ENGINE_SLIPSTREAM) {
            config.slipstreamKeepAlive = slipKeepAlive
            config.slipstreamCongestionControl = slipCongestionControl
            config.slipstreamGsoEnabled = config.slipstreamGsoEnabled == true
            config.dnsttNoiz = false
            config.dnsttStealth = false
        } else {
            config.slipstreamKeepAlive = null
            config.slipstreamCongestionControl = null
            config.slipstreamGsoEnabled = null
        }
        if (engine == ENGINE_VAYDNS) {
            config.vaydnsDnsttCompat = config.vaydnsDnsttCompat == true
            config.vaydnsRecordType = vayRecordType
            config.vaydnsMaxQnameLen = vayMaxQname
            config.vaydnsRps = vayRps
            config.vaydnsIdleTimeout = vayIdle
            config.vaydnsKeepAlive = vayKeepAlive
            config.vaydnsUdpTimeout = vayUdpTimeout
            config.vaydnsMaxNumLabels = vayMaxLabels
            config.vaydnsClientIdSize = vayClientId
            config.dnsttNoiz = false
            config.dnsttStealth = false
        } else {
            config.vaydnsDnsttCompat = null
            config.vaydnsRecordType = null
            config.vaydnsMaxQnameLen = null
            config.vaydnsRps = null
            config.vaydnsIdleTimeout = null
            config.vaydnsKeepAlive = null
            config.vaydnsUdpTimeout = null
            config.vaydnsMaxNumLabels = null
            config.vaydnsClientIdSize = null
        }
        config.dnsttSshEnabled = sshEnabled
        config.server = domain
        config.serverPort = null
        config.remarks = config.remarks.trim().ifEmpty { DEFAULT_REMARKS }
        return null
    }

    internal fun normalizeDomain(value: String?): String? {
        val domain = value?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        if (domain.isEmpty() || domain.length > 253 || domain.startsWith('.') || domain.endsWith('.')) return null
        val labels = domain.split('.')
        return domain.takeIf {
            labels.all { label ->
                label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                    !label.startsWith('-') && !label.endsWith('-')
            }
        }
    }

    private fun normalizeResolver(transport: Transport, value: String, allowMultiple: Boolean = false): String? {
        if (value.any { it.isWhitespace() || it == '\u0000' }) return null
        if (transport == Transport.DOH) {
            if (value.contains(',')) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true)) return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            return value
        }
        val values = if (allowMultiple) value.split(',') else listOf(value)
        if (values.isEmpty() || values.any { it.isBlank() }) return null
        if (allowMultiple) {
            val marked = values.map { authoritySuffix(it) != null }
            if (marked.any { it } && !marked.all { it }) return null
        }
        val normalized = values.map {
            if (allowMultiple) {
                normalizeNoizEndpoint(it, transport.defaultPort) ?: return null
            } else {
                normalizeEndpoint(it, transport.defaultPort) ?: return null
            }
        }
        return normalized.joinToString(",")
    }

    /** Accepts a legacy authority suffix but returns only the printable DNS endpoint. */
    private fun normalizeNoizEndpoint(value: String, defaultPort: Int): String? {
        val authority = authoritySuffix(value)
        val endpoint = if (authority == null) value else value.removeSuffix(":$authority")
        return normalizeEndpoint(endpoint, defaultPort)
    }

    /** Strips profile authority suffixes before constructing engine arguments. */
    internal fun engineResolver(value: String): String = value.split(',').joinToString(",") { entry ->
        val authority = authoritySuffix(entry)
        if (authority == null) entry else entry.removeSuffix(":$authority")
    }

    /** Reads separate authority metadata, migrating legacy endpoint markers when necessary. */
    internal fun resolverAuthorities(config: ProfileItem): List<Boolean> {
        val raw = config.dnsttResolver.orEmpty()
        val count = engineResolver(raw).split(',').size
        return authorityFlags(config.dnsttResolverAuthorities, count)
            ?: resolverAuthorities(raw).takeIf { it.size == count }
            ?: emptyList()
    }

    private fun authorityFlags(value: String?, expectedCount: Int): List<Boolean>? {
        if (value.isNullOrBlank()) return null
        val flags = value.split(',')
        if (flags.size != expectedCount || flags.any { it != "0" && it != "1" }) return null
        return flags.map { it == "1" }
    }

    /** Returns legacy per-resolver modes, or an empty list for unmarked/mixed profiles. */
    internal fun resolverAuthorities(value: String): List<Boolean> {
        val values = value.split(',')
        if (values.isEmpty()) return emptyList()
        val flags = values.map { entry -> authoritySuffix(entry) ?: return emptyList() }
        return flags.map { it == "1" }
    }

    private fun authoritySuffix(value: String): String? {
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val pieces = value.substring(close + 1).split(':')
            return pieces.getOrNull(2)?.takeIf {
                pieces.size == 3 && pieces[1].toIntOrNull() != null && (it == "0" || it == "1")
            }
        }
        val pieces = value.split(':')
        return pieces.getOrNull(2)?.takeIf {
            pieces.size == 3 && pieces[0].isNotEmpty() &&
                pieces[1].toIntOrNull() != null && (it == "0" || it == "1")
        }
    }

    /** Accepts host:port, bracketed IPv6, and bare host/IP with the transport's standard port. */
    internal fun normalizeEndpoint(value: String, defaultPort: Int): String? {
        var host: String
        var port = defaultPort
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return null
            host = value.substring(1, end)
            val tail = value.substring(end + 1)
            if (tail.isNotEmpty()) {
                if (!tail.startsWith(":")) return null
                port = tail.substring(1).toIntOrNull() ?: return null
            }
        } else {
            val colons = value.count { it == ':' }
            when {
                colons == 0 -> host = value
                colons == 1 -> {
                    host = value.substringBefore(':')
                    port = value.substringAfter(':').toIntOrNull() ?: return null
                }
                else -> host = value // bare IPv6, using the standard port
            }
        }
        if (port !in 1..65535 || !validHost(host)) return null
        val printableHost = if (host.contains(':')) "[$host]" else host
        return "$printableHost:$port"
    }

    private fun validHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() || it == '/' || it == '[' || it == ']' }) return false
        if (host.contains(':')) return MasterDnsFmt.validResolver(host)
        if (host.all { it.isDigit() || it == '.' }) return MasterDnsFmt.validResolver(host)
        return normalizeDomain(host) != null
    }

    internal fun redact(line: String): String = line.trim().let {
        if (it.length > 2_000) it.take(2_000) + "…" else it
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun queryParameters(raw: String): Map<String, String> = raw.split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            runCatching {
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrNull()
        }.toMap()
}
