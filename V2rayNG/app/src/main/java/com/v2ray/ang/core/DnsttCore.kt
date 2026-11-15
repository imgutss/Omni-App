package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.fmt.SoarProfileFmt
import java.security.MessageDigest

/** Validated inputs and loopback topology for each supported DNS tunnel engine. */
data class DnsttCore(
    val transport: DnsttFmt.Transport,
    val resolver: String,
    val domain: String,
    val publicKey: String,
    val utls: String,
    val port: Int = DEFAULT_SOCKS_PORT,
    val engine: String = DnsttFmt.ENGINE_OFFICIAL,
    val authoritative: Boolean = false,
    val resolverAuthorities: List<Boolean> = emptyList(),
    val noiz: Boolean = false,
    val stealth: Boolean = false,
    val maxPayload: Int = 0,
    val vaydns: VayDnsConfig = VayDnsConfig(),
    val slipstream: SlipstreamConfig = SlipstreamConfig(),
    val soarSettings: String = "",
    val resolverMode: String = "fanout",
    val rrSpreadCount: Int = 3,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val ssh: DnsttSshConfig? = null,
) {
    init {
        val needsAdjacentPort = ssh != null || engine == DnsttFmt.ENGINE_SLIPSTREAM ||
            engine == DnsttFmt.ENGINE_VAYDNS
        require(port in 1..65535 && (!needsAdjacentPort || port < 65535)) { "Invalid DNSTT listen port" }
        require(engine in setOf(DnsttFmt.ENGINE_OFFICIAL, DnsttFmt.ENGINE_NOIZDNS, DnsttFmt.ENGINE_DNSTT, DnsttFmt.ENGINE_VAYDNS, DnsttFmt.ENGINE_SLIPSTREAM, DnsttFmt.ENGINE_SOAR)) {
            "Unknown DNSTT engine"
        }
        require(resolverMode == "fanout" || resolverMode == "roundrobin") {
            "Invalid DNSTT resolver mode"
        }
        require(rrSpreadCount in 1..5) { "Invalid DNSTT resolver spread" }
    }

    /** Raw listener used by SSH; standalone VayDNS and Slipstream also use an adjacent engine port. */
    val tunnelPort: Int get() = if (ssh == null) port else port + 1

    /** Actual native engine listener; a local auth bridge may own the public port instead. */
    val enginePort: Int get() = when {
        engine == DnsttFmt.ENGINE_SLIPSTREAM -> port + 1
        engine == DnsttFmt.ENGINE_VAYDNS -> port + 1
        else -> tunnelPort
    }

    /** Loopback ports that must be reserved before Xray and the native process start. */
    val sessionPorts: List<Int> get() = listOf(port, enginePort).distinct()

    /** Stable identity for matching a live process without logging the configuration. */
    val key: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            transport.type, resolver, domain, publicKey, utls, port.toString(), engine,
            authoritative.toString(), resolverAuthorities.joinToString(","), noiz.toString(), stealth.toString(), maxPayload.toString(),
            if (engine == DnsttFmt.ENGINE_VAYDNS) vaydns.identityValues().joinToString(",") else "",
            if (engine == DnsttFmt.ENGINE_SLIPSTREAM) slipstream.identityValues().joinToString(",") else "",
            if (engine == DnsttFmt.ENGINE_SOAR) soarSettings else "",
            resolverMode, rrSpreadCount.toString(), socksUsername, socksPassword,
        ).plus(ssh?.identityValues().orEmpty()).forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_SOCKS_PORT = 18001

        fun of(profile: ProfileItem, listenPort: Int = DEFAULT_SOCKS_PORT): DnsttCore {
            val normalized = profile.copy()
            require(DnsttFmt.normalize(normalized) == null) { "Invalid DNSTT profile" }
            val ssh = if (normalized.dnsttSshEnabled == true) {
                DnsttSshConfig(
                    username = normalized.dnsttSshUsername.orEmpty(),
                    password = normalized.dnsttSshPassword.orEmpty(),
                    host = normalized.dnsttSshHost ?: "127.0.0.1",
                    port = normalized.dnsttSshPort ?: 22,
                    authType = normalized.dnsttSshAuthType ?: DnsttSshConfig.AUTH_PASSWORD,
                    privateKey = normalized.dnsttSshPrivateKey.orEmpty(),
                    keyPassphrase = normalized.dnsttSshKeyPassphrase.orEmpty(),
                    forwardDns = normalized.dnsttSshForwardDns == true,
                )
            } else {
                null
            }
            return DnsttCore(
                transport = DnsttFmt.Transport.from(normalized.dnsttTransport)!!,
                resolver = normalized.dnsttResolver.orEmpty(),
                domain = normalized.dnsttDomain.orEmpty(),
                publicKey = normalized.dnsttPublicKey.orEmpty(),
                utls = normalized.dnsttUtls.orEmpty(),
                port = listenPort,
                engine = normalized.dnsttEngine ?: DnsttFmt.ENGINE_OFFICIAL,
                authoritative = normalized.dnsttAuthoritative == true,
                resolverAuthorities = DnsttFmt.resolverAuthorities(normalized),
                noiz = normalized.dnsttNoiz == true,
                stealth = normalized.dnsttStealth == true,
                maxPayload = normalized.dnsttMaxPayload ?: 0,
                slipstream = SlipstreamConfig(
                    keepAliveIntervalMs = normalized.slipstreamKeepAlive ?: 5_000,
                    congestionControl = normalized.slipstreamCongestionControl ?: "bbr",
                    gsoEnabled = normalized.slipstreamGsoEnabled == true,
                ),
                vaydns = VayDnsConfig(
                    dnsttCompat = normalized.vaydnsDnsttCompat == true,
                    recordType = normalized.vaydnsRecordType ?: "txt",
                    maxQnameLen = normalized.vaydnsMaxQnameLen ?: 101,
                    rps = normalized.vaydnsRps ?: 0.0,
                    idleTimeoutSeconds = normalized.vaydnsIdleTimeout ?: 0,
                    keepAliveSeconds = normalized.vaydnsKeepAlive ?: 0,
                    udpTimeoutMs = normalized.vaydnsUdpTimeout ?: 0,
                    maxNumLabels = normalized.vaydnsMaxNumLabels ?: 0,
                    clientIdSize = normalized.vaydnsClientIdSize ?: 0,
                ),
                soarSettings = if (normalized.dnsttEngine == DnsttFmt.ENGINE_SOAR) {
                    // Canonicalization also migrates pre-country profiles to the documented
                    // restricted-network discovery default without changing explicit GLOBAL.
                    SoarProfileFmt.normalizeSettings(normalized.soarSettings).orEmpty()
                } else {
                    normalized.soarSettings.orEmpty()
                },
                resolverMode = normalized.dnsttResolverMode ?: "fanout",
                rrSpreadCount = normalized.dnsttRrSpreadCount ?: 3,
                socksUsername = if (ssh == null) normalized.dnsttSocksUsername.orEmpty() else "",
                socksPassword = if (ssh == null) normalized.dnsttSocksPassword.orEmpty() else "",
                ssh = ssh,
            )
        }
    }
}
