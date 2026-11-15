package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.omni.lab.OmniBuiltConfiguration
import com.v2ray.ang.omni.lab.OmniProbeMode

/** Real-ping evidence keyed by the saved profile's stable configuration fingerprint. */
data class OmniBuiltPing(
    val delayMs: Long?,
    val sampleCount: Int,
    val successfulSamples: Int,
    val jitterMs: Long?,
) {
    val successful: Boolean get() = delayMs != null && delayMs > 0L && successfulSamples > 0
}

fun rankOmniBuiltConfigurations(
    configurations: List<OmniBuiltConfiguration>,
    pings: Map<String, OmniBuiltPing>,
): List<OmniBuiltConfiguration> = configurations.sortedWith(
    compareByDescending<OmniBuiltConfiguration> { pings[it.fingerprint]?.successful == true }
        .thenByDescending {
            val ping = pings[it.fingerprint]
            if (ping == null || ping.sampleCount <= 0) 0.0 else ping.successfulSamples.toDouble() / ping.sampleCount
        }
        .thenBy { pings[it.fingerprint]?.delayMs?.takeIf { delay -> delay > 0L } ?: Long.MAX_VALUE }
        .thenBy { pings[it.fingerprint]?.jitterMs?.takeIf { jitter -> jitter >= 0L } ?: Long.MAX_VALUE }
        .thenBy { it.target.endpointId },
)

data class OmniScannerProbeDefaults(
    val mode: OmniProbeMode,
    val sni: String,
    val hostHeader: String,
    val path: String,
    val useTls: Boolean?,
)

/** Uses the selected profile's real transport instead of probing every CDN endpoint with unrelated defaults. */
fun omniScannerProbeDefaults(profile: ProfileItem): OmniScannerProbeDefaults {
    val network = profile.network?.trim()?.lowercase().orEmpty()
    val security = profile.security?.trim()?.lowercase().orEmpty()
    val mode = when {
        security.isNotEmpty() && security !in setOf("tls", "none") -> OmniProbeMode.TCP
        network == "ws" -> OmniProbeMode.WEBSOCKET
        network in setOf("httpupgrade", "xhttp") -> OmniProbeMode.HTTP
        security == "tls" -> OmniProbeMode.TLS
        else -> OmniProbeMode.TCP
    }
    val useTls = when {
        mode == OmniProbeMode.TCP -> null
        security == "tls" -> true
        network in setOf("ws", "httpupgrade", "xhttp") -> false
        else -> null
    }
    return OmniScannerProbeDefaults(
        mode = mode,
        sni = profile.sni?.ifBlank { null }
            ?: profile.host?.takeIf { security == "tls" }?.ifBlank { null }
            .orEmpty(),
        hostHeader = profile.host.orEmpty(),
        path = profile.path?.ifBlank { "/" } ?: "/",
        useTls = useTls,
    )
}
