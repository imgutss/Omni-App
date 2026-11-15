package com.v2ray.ang.core

/** Engine-owned VayDNS options; values are validated by DnsttFmt before use. */
data class VayDnsConfig(
    val dnsttCompat: Boolean = false,
    val recordType: String = "txt",
    val maxQnameLen: Int = 101,
    val rps: Double = 0.0,
    val idleTimeoutSeconds: Int = 0,
    val keepAliveSeconds: Int = 0,
    val udpTimeoutMs: Int = 0,
    val maxNumLabels: Int = 0,
    val clientIdSize: Int = 0,
) {
    fun identityValues(): List<String> = listOf(
        dnsttCompat.toString(), recordType, maxQnameLen.toString(), rps.toString(),
        idleTimeoutSeconds.toString(), keepAliveSeconds.toString(), udpTimeoutMs.toString(),
        maxNumLabels.toString(), clientIdSize.toString(),
    )
}
