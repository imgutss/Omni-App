package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.omni.lab.OmniTransferResult

const val OMNI_SPEED_CLOUDFLARE_DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=4194304"
const val OMNI_SPEED_CLOUDFLARE_UPLOAD = "https://speed.cloudflare.com/__up"
const val OMNI_SPEED_OVH_DOWNLOAD = "https://proof.ovh.net/files/100Mb.dat"

data class OmniSpeedProfile(
    val guid: String,
    val profile: ProfileItem,
)

data class OmniSpeedSubscription(
    val id: String,
    val name: String,
    val profiles: List<OmniSpeedProfile>,
)

enum class OmniSpeedStatus { UNTESTED, RUNNING, SUCCESS, FAILED }

data class OmniSpeedPing(
    val delayMs: Long?,
    val sampleCount: Int,
    val successfulSamples: Int,
    val jitterMs: Long?,
)

data class OmniSpeedProfileResult(
    val profile: OmniSpeedProfile,
    val status: OmniSpeedStatus = OmniSpeedStatus.UNTESTED,
    val transfer: OmniTransferResult? = null,
    val downloadHost: String? = null,
    val usedFallback: Boolean = false,
)

data class OmniSpeedLabUiState(
    val subscriptions: List<OmniSpeedSubscription> = emptyList(),
    val selectedSubscriptionId: String? = null,
    val results: List<OmniSpeedProfileResult> = emptyList(),
    val pingResults: Map<String, OmniSpeedPing> = emptyMap(),
    val loading: Boolean = false,
    val downloadUrlInput: String = OMNI_SPEED_CLOUDFLARE_DOWNLOAD,
    val uploadUrlInput: String = OMNI_SPEED_CLOUDFLARE_UPLOAD,
    val downloadSizeInput: String = "4194304",
    val uploadSizeInput: String = "1048576",
    val plannedDataBytes: Long? = 0L,
    val speedRunning: Boolean = false,
    val pingRunning: Boolean = false,
    val completedCount: Int = 0,
    val message: OmniSpeedLabMessage? = null,
) {
    val totalCount: Int get() = results.size
}

enum class OmniSpeedLabMessage {
    LOAD_FAILED,
    NO_PROFILE,
    INVALID_INPUT,
    SPEED_FAILED,
    PING_FAILED,
    BATCH_FINISHED,
    BATCH_PARTIAL,
    BATCH_CANCELLED,
}

fun parseOmniTransferSize(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1_024..16 * 1_024 * 1_024 }

fun omniSpeedPlannedDataBytes(
    profileCount: Int,
    downloadSizeInput: String,
    uploadSizeInput: String,
    uploadUrlInput: String,
    downloadUrlInput: String,
): Long? {
    if (profileCount < 0) return null
    val download = parseOmniTransferSize(downloadSizeInput) ?: return null
    val upload = if (uploadUrlInput.isBlank()) 0 else parseOmniTransferSize(uploadSizeInput) ?: return null
    val maxAttempts = if (downloadUrlInput.trim() == OMNI_SPEED_CLOUDFLARE_DOWNLOAD) 2L else 1L
    return profileCount.toLong() * (download.toLong() + upload.toLong()) * maxAttempts
}

fun rankOmniSpeedResults(results: List<OmniSpeedProfileResult>): List<OmniSpeedProfileResult> = results.sortedWith(
    compareByDescending<OmniSpeedProfileResult> { it.status == OmniSpeedStatus.SUCCESS && it.transfer?.success == true }
        .thenBy {
            when (it.status) {
                OmniSpeedStatus.RUNNING -> 0
                OmniSpeedStatus.UNTESTED -> 1
                OmniSpeedStatus.FAILED -> 2
                OmniSpeedStatus.SUCCESS -> 3
            }
        }
        .thenByDescending { if (it.status == OmniSpeedStatus.SUCCESS) it.transfer?.downloadMbps ?: -1.0 else -1.0 }
        .thenByDescending { if (it.status == OmniSpeedStatus.SUCCESS) it.transfer?.uploadMbps ?: -1.0 else -1.0 }
        .thenBy { if (it.status == OmniSpeedStatus.SUCCESS) it.transfer?.downloadTtfbMs ?: Long.MAX_VALUE else Long.MAX_VALUE }
        .thenBy { it.profile.profile.remarks }
        .thenBy { it.profile.guid },
)
