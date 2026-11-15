package com.v2ray.ang.omni

import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads existing MMKV subscriptions directly; batch measurements never create a shadow profile store. */
class OmniSpeedLabRepository {
    suspend fun loadSubscriptions(): List<OmniSpeedSubscription> = withContext(Dispatchers.IO) {
        val stored = MmkvManager.decodeSubscriptions()
        val subscriptions = stored.map { subscription ->
            OmniSpeedSubscription(subscription.guid, subscription.subscription.remarks, loadProfiles(subscription.guid))
        }.toMutableList()
        // Manual imports and scanner-built profiles can live in the default group without a SubscriptionItem.
        if (stored.none { it.guid == DEFAULT_SUBSCRIPTION_ID }) {
            subscriptions.add(0, OmniSpeedSubscription(DEFAULT_SUBSCRIPTION_ID, "", loadProfiles(DEFAULT_SUBSCRIPTION_ID)))
        }
        subscriptions
    }

    private fun loadProfiles(subscriptionId: String): List<OmniSpeedProfile> =
        MmkvManager.decodeServerList(subscriptionId).asSequence()
            .distinct()
            .mapNotNull { guid ->
                MmkvManager.decodeServerConfig(guid)?.let { OmniSpeedProfile(guid, it) }
            }
            .toList()

    suspend fun recordPing(result: RealPingEvent.Result) = withContext(Dispatchers.IO) {
        MmkvManager.encodeServerTestResult(
            guid = result.guid,
            testResult = result.delayMillis,
            sampleCount = result.sampleCount,
            successfulSamples = result.successfulSamples,
            jitterMillis = result.jitterMillis,
            kind = result.kind,
        )
    }
}
