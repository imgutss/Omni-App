package com.v2ray.ang.core

/** SSH authentication and endpoint metadata for a raw DNSTT -> SSH -> SOCKS5 chain. */
data class DnsttSshConfig(
    val username: String,
    val password: String = "",
    val host: String = "127.0.0.1",
    val port: Int = 22,
    val authType: String = AUTH_PASSWORD,
    val privateKey: String = "",
    val keyPassphrase: String = "",
    /** Preserved from SlipNet; Omni's Xray DNS configuration remains authoritative. */
    val forwardDns: Boolean = false,
) {
    init {
        require(username.isNotBlank() && username.length <= 256 && username.none { it.isISOControl() })
        require(password.length <= 16_384 && !password.contains('\u0000'))
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '\u0000' || it == '/' })
        require(port in 1..65535)
        require(authType == AUTH_PASSWORD || authType == AUTH_KEY)
        require(authType != AUTH_KEY || privateKey.isNotBlank())
        require(privateKey.length <= 262_144 && !privateKey.contains('\u0000'))
        require(keyPassphrase.length <= 16_384 && !keyPassphrase.contains('\u0000'))
    }

    /** Values included only in the session identity digest, never in logs. */
    fun identityValues(): List<String> = listOf(
        username, password, host, port.toString(), authType, privateKey, keyPassphrase, forwardDns.toString(),
    )

    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }
}
