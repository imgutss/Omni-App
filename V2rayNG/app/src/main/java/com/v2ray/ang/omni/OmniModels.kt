package com.v2ray.ang.omni

import java.util.UUID

/** A user-owned panel. The subscription URL is sensitive and is never rendered in the Hub list. */
data class OmniPanel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val subscriptionUrl: String,
    val subscriptionId: String,
    val allowInsecure: Boolean = false,
    val lastStatus: String = OmniPanelStatus.NOT_CHECKED.name,
    val lastStatusDetail: String = "",
    val lastCheckedAt: Long = 0L,
    val lastSyncAt: Long = 0L,
)

enum class OmniPanelStatus {
    NOT_CHECKED,
    READY,
    AUTH_OR_EDGE_REFUSED,
    NOT_FOUND,
    TIMEOUT,
    DNS_ERROR,
    TLS_ERROR,
    NETWORK_ERROR,
    HTTP_ERROR,
    INVALID,
}

data class OmniPanelHealth(
    val status: OmniPanelStatus,
    val httpStatus: Int? = null,
    val elapsedMillis: Long = 0L,
    /** Technical evidence safe to show: status, exception class and timing, never credentials. */
    val rawDetail: String,
)

class OmniHubException(message: String, val httpStatus: Int? = null) : Exception(message)

/** Compatibility filter for status text written by older builds, which could include exception URLs. */
fun omniSafePanelStatusDetail(raw: String): String {
    if (raw.isBlank() || raw.length > 512) return ""
    val sensitive = Regex("(?i)(https?://|bearer\\s+|access[_-]?token|subscription[_-]?url|token\\s*=)")
    return if (sensitive.containsMatchIn(raw)) "" else raw
}

enum class OmniDoctorStatus { RUNNING, PASS, WARN, FAIL, SKIPPED }

data class OmniDoctorStage(
    val id: String,
    val title: String,
    val status: OmniDoctorStatus,
    val summary: String,
    val raw: String = "",
)
