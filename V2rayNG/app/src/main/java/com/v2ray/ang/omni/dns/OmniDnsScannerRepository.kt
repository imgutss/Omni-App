package com.v2ray.ang.omni.dns

import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.omni.OmniScannerTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OmniDnsScannerRepository {
    suspend fun templates(): List<OmniScannerTemplate> = withContext(Dispatchers.IO) {
        MmkvManager.decodeAllServerList().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.takeIf { it.configType == EConfigType.DNSTT || it.configType == EConfigType.MASTERDNS }
                ?.let { OmniScannerTemplate(guid, it) }
        }.sortedBy { it.profile.remarks.ifBlank { it.guid } }
    }

    suspend fun applyResolver(guid: String, candidate: com.v2ray.ang.dto.entities.ProfileItem) = withContext(Dispatchers.IO) {
        val current = MmkvManager.decodeServerConfig(guid)
        require(current?.configType == candidate.configType && current.configType in setOf(EConfigType.DNSTT, EConfigType.MASTERDNS)) {
            "The selected DNS profile no longer exists or changed type"
        }
        // Keep identity, remarks and subscription; only the measured resolver and its matching metadata change.
        MmkvManager.encodeServerConfig(guid, candidate.copy(subscriptionId = current.subscriptionId))
    }
}
