package com.v2ray.ang.omni

enum class OmniDnsProvider(val endpoint: String?) {
    SYSTEM(null),
    GOOGLE("https://dns.google/resolve"),
    CLOUDFLARE("https://cloudflare-dns.com/dns-query"),
    CUSTOM(null),
}

enum class OmniDnsRecordType(val wireType: Int) { A(1), AAAA(28) }

enum class OmniDnsStatus { PASS, NXDOMAIN, FAIL }

data class OmniDnsAnswer(
    val name: String,
    val type: Int,
    val ttl: Long?,
    val data: String,
)

data class OmniDnsResult(
    val provider: OmniDnsProvider,
    val status: OmniDnsStatus,
    val elapsedMs: Long,
    val answers: List<OmniDnsAnswer> = emptyList(),
    val httpStatus: Int? = null,
    val responseStatus: Int? = null,
    val error: String? = null,
)

data class OmniDnsLabUiState(
    val hostInput: String = "example.com",
    val recordType: OmniDnsRecordType = OmniDnsRecordType.A,
    val selectedProviders: Set<OmniDnsProvider> = setOf(OmniDnsProvider.SYSTEM, OmniDnsProvider.GOOGLE, OmniDnsProvider.CLOUDFLARE),
    val customUrlInput: String = "",
    val running: Boolean = false,
    val activeProvider: OmniDnsProvider? = null,
    val results: Map<OmniDnsProvider, OmniDnsResult> = emptyMap(),
    val message: OmniDnsMessage? = null,
)

enum class OmniDnsMessage { INVALID_INPUT, NO_PROVIDER, COMPLETE, PARTIAL_FAILURE }

fun normalizeOmniDnsName(raw: String): String? {
    val value = raw.trim().trimEnd('.')
    if (value.isEmpty() || value.length > 253 || value.any { it.isWhitespace() }) return null
    val labels = value.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
    if (labels.any { label -> label.any { !(it.isLetterOrDigit() || it == '-' || it == '_' || it.code > 127) } }) return null
    return value.lowercase()
}
