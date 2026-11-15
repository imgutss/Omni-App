package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.MasterDnsFmt
import java.security.MessageDigest

/** The official MasterDnsVPN document a loopback SOCKS outbound depends on. */
data class MasterDnsCore(
    val config: String,
    val resolvers: String,
    val engine: String = MasterDnsFmt.ENGINE_MASTERDNS,
    val port: Int = DEFAULT_SOCKS_PORT,
) {
    init {
        require(port in 1..65535) { "Invalid MasterDNS listen port" }
    }

    /** Stable identity for lifecycle matching; the secret configuration itself is never logged. */
    val key: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(engine.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(config.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(resolvers.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(port.toString().toByteArray(Charsets.UTF_8))
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun redact(line: String): String = MasterDnsFmt.redact(line, config)

    companion object {
        const val DEFAULT_SOCKS_PORT = 18000

        fun of(profile: ProfileItem, listenPort: Int = DEFAULT_SOCKS_PORT): MasterDnsCore {
            val normalized = profile.copy()
            val problem = MasterDnsFmt.normalize(normalized)
            require(problem == null) { "Invalid MasterDNS profile: $problem" }
            return MasterDnsCore(
                config = normalized.masterDnsConfig.orEmpty(),
                resolvers = MasterDnsFmt.normalizeResolvers(normalized.masterDnsResolvers),
                engine = normalized.masterDnsEngine ?: MasterDnsFmt.ENGINE_MASTERDNS,
                port = listenPort,
            )
        }
    }
}
