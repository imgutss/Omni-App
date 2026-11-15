package com.v2ray.ang.firefox.shield

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.firefox.FirefoxEngineApi
import com.v2ray.ang.firefox.hub.QuotaMath
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Quota Autopilot — keeps an eye on the month's allowance while the Firefox tunnel is up.
 *
 * The engine already moves to the next account at the switch threshold on its own; this guard adds
 * what the person sees and what happens at the very end: a heads-up as the allowance runs low (once
 * per band, not a nag every loop), a record in the Blackbox, and — when it is fully spent and there is
 * no other account — a hand-off to the Hybrid Shield, which moves the tunnel to another Omni engine
 * rather than letting it die or leak.
 */
object FirefoxQuotaGuard {

    private const val TAG = "FirefoxQuotaGuard"
    private const val LOOP_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // The highest band already announced this session, so each band speaks once.
    @Volatile private var announcedBand = 0

    fun onSessionStarted(context: Context) {
        job?.cancel()
        announcedBand = 0
        val app = context.applicationContext
        job = scope.launch {
            while (isActive && FirefoxBridge.isSelected()) {
                delay(LOOP_MS)
                if (!FirefoxBridge.isSelected()) break
                runCatching { tick(app) }.onFailure { LogUtil.d(TAG, "tick: ${it.message}") }
            }
        }
    }

    fun onSessionStopped() {
        job?.cancel()
        job = null
    }

    fun enabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREFOX_QUOTA_AUTOPILOT, true)
    fun setEnabled(value: Boolean) = MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_QUOTA_AUTOPILOT, value)

    private suspend fun tick(app: Context) {
        if (!enabled()) return
        val status = FirefoxEngineApi.liveStatus(app) ?: return
        if (status.quotaUnlimited || status.quotaLimitBytes <= 0) return
        val used = QuotaMath.usedPercent(status.quotaLimitBytes, status.quotaRemainingBytes)
        val band = when {
            used > 95 -> 4
            used > 85 -> 3
            used > 60 -> 2
            else -> 0
        }
        if (band > announcedBand) {
            announcedBand = band
            val remaining = QuotaMath.formatBytes(status.quotaRemainingBytes.coerceAtLeast(0))
            Blackbox.record("quota_band", "used=${"%.0f".format(used)}% remaining=$remaining")
            notify(app, band, remaining)
        }
        if (status.quotaRemainingBytes <= 0) {
            // Spent: refresh so the engine's own account rotation has the latest word, then let the
            // Hybrid Shield decide whether another engine must take the tunnel.
            Blackbox.record("quota_exhausted", "autopilot")
            val active = status.activeAccountEmail
            if (active.isNotBlank()) {
                FirefoxEngineApi.accounts(app)?.firstOrNull { it.active }?.let {
                    FirefoxEngineApi.refreshQuota(app, it.id)
                }
            }
        }
    }

    private fun notify(app: Context, band: Int, remaining: String) {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val (title, content) = when (band) {
            4 -> "سهمیهٔ فایرفاکس رو به پایان" to "کمتر از ۵٪ مانده ($remaining). به‌زودی به موتور دیگری منتقل می‌شود."
            3 -> "سهمیهٔ فایرفاکس کم شده" to "بیش از ۸۵٪ مصرف شده؛ $remaining باقی مانده."
            else -> "سهمیهٔ فایرفاکس" to "بیش از ۶۰٪ مصرف شده؛ $remaining باقی مانده."
        }
        runCatching {
            NotificationHelper.notify(NotificationChannelType.FIREFOX_QUOTA, app, title, content)
        }.onFailure { LogUtil.w(TAG, "quota notification not delivered", it) }
    }
}
