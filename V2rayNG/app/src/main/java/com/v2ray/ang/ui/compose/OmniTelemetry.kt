package com.v2ray.ang.ui.compose

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class OmniTelemetry(val uptimeSeconds: Long? = null, val bytesPerSecond: Double? = null, val history: List<Double> = emptyList())
/** Never query/reset the native cores' destructive counters, or steal notification samples. */
internal fun omniByteRate(previous: Long, current: Long, elapsedMillis: Long): Double? =
    if (previous < 0 || current < previous || elapsedMillis <= 0) null else (current - previous).toDouble() * 1000.0 / elapsedMillis

@Suppress("DEPRECATION")
private fun serviceStart(context: Context): Long? = runCatching {
    context.getSystemService(ActivityManager::class.java).getRunningServices(128)
        .firstOrNull { it.foreground && it.service.className.substringAfterLast('.') in setOf("CoreVpnService", "CoreProxyOnlyService", "CoreRootService") }
        ?.activeSince?.takeIf { it > 0L }
}.getOrNull()

@Composable
internal fun rememberOmniTelemetry(running: Boolean): OmniTelemetry {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot by produceState(OmniTelemetry(), running, lifecycle) {
        if (!running) { value = OmniTelemetry(0L, 0.0, List(40) { 0.0 }); return@produceState }
        withContext(Dispatchers.IO) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fun bytes(): Long {
                    val rx = TrafficStats.getUidRxBytes(Process.myUid()); val tx = TrafficStats.getUidTxBytes(Process.myUid())
                    return if (rx < 0L || tx < 0L) -1L else rx + tx
                }
                var start = serviceStart(context)
                var previous = bytes(); var at = SystemClock.elapsedRealtime()
                val points = ArrayDeque<Double>()
                value = OmniTelemetry(start?.let { ((at - it) / 1000L).coerceAtLeast(0L) })
                while (true) {
                    delay(1000L)
                    val now = SystemClock.elapsedRealtime(); val current = bytes()
                    val rate = omniByteRate(previous, current, now - at)
                    if (rate != null) { points.addLast(rate); while (points.size > 40) points.removeFirst() }
                    if (start == null) start = serviceStart(context)
                    value = OmniTelemetry(start?.let { ((now - it) / 1000L).coerceAtLeast(0L) }, rate, points.toList())
                    previous = current; at = now
                }
            }
        }
    }
    return snapshot
}
