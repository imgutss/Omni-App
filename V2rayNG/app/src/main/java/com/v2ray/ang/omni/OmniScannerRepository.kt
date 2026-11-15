package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.omni.lab.OmniBuiltConfiguration
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OmniScannerTemplate(val guid: String, val profile: ProfileItem)

/** MMKV remains the owner of profile persistence; scanner results never become a second profile database. */
class OmniScannerRepository {
    suspend fun templates(): List<OmniScannerTemplate> = withContext(Dispatchers.IO) {
        MmkvManager.decodeAllServerList().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.takeIf {
                it.configType in setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)
            }?.let { OmniScannerTemplate(guid, it) }
        }.sortedBy { it.profile.remarks.ifBlank { it.guid } }
    }

    suspend fun saveBuilt(configurations: List<OmniBuiltConfiguration>): List<String> = withContext(Dispatchers.IO) {
        val saved = ArrayList<String>(configurations.size)
        try {
            configurations.forEach { built ->
                val guid = Utils.getUuid()
                MmkvManager.encodeServerConfig(guid, built.profile)
                saved += guid
            }
            saved
        } catch (error: Exception) {
            saved.forEach { guid ->
                runCatching { MmkvManager.removeServer(guid) }
                    .onFailure { rollback -> LogUtil.e("Omni", "Scanner save rollback failed for $guid", rollback) }
            }
            throw error
        }
    }
}
