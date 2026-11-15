package com.v2ray.ang.firefox.shield

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.firefox.FirefoxEngineApi
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hybrid Shield — the reason Omni's Firefox path is safer than a single-engine client's.
 *
 * While the Firefox account row carries the tunnel, this watches the engine. If the account path
 * genuinely cannot carry traffic anymore — every account's allowance is spent, Mozilla refuses the
 * region, the sign-in expired, or the engine stopped answering — it hands the tunnel to another Omni
 * engine instead. It **never** falls back to a silent direct route: that would leak the person's
 * traffic while the screen claimed a tunnel. The switch is logged and shown, so it is transparent.
 *
 * The engine already fails over between accounts and between servers of a location on its own; this
 * is the last resort, when the whole Firefox path is spent, and it acts once per session so a failing
 * fallback cannot start a switching loop.
 */
object HybridShield {

    private const val TAG = "HybridShield"
    private const val CHECK_INTERVAL_MS = 5_000L
    private const val DOWN_THRESHOLD = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    @Volatile
    private var failedOverThisSession = false

    /** Whether the person keeps the shield on (it is on by default). */
    fun enabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREFOX_HYBRID_SHIELD, true)

    fun setEnabled(value: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_HYBRID_SHIELD, value)
    }

    /** Called when a Firefox direct session comes up; starts watching it if the shield is on. */
    fun onSessionStarted(context: Context, guid: String) {
        failedOverThisSession = false
        watchJob?.cancel()
        if (!enabled()) return
        val app = context.applicationContext
        watchJob = scope.launch {
            var consecutiveDown = 0
            while (isActive && FirefoxBridge.isSelected()) {
                delay(CHECK_INTERVAL_MS)
                if (!FirefoxBridge.isSelected()) break
                val status = runCatching { FirefoxEngineApi.liveStatus(app) }.getOrNull()
                if (status == null) {
                    consecutiveDown++
                } else {
                    consecutiveDown = 0
                }
                val reason = failureReason(status, consecutiveDown)
                if (reason != null && !failedOverThisSession) {
                    failedOverThisSession = true
                    failOver(app, reason)
                    break
                }
            }
        }
    }

    /** Called when the session ends; stops watching. */
    fun onSessionStopped() {
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * Why the Firefox path should hand the tunnel to another engine, or null while it can still carry
     * traffic. A moment's queue is not a failure: only a spent or refused path, or an engine that has
     * stopped answering altogether, counts.
     */
    internal fun failureReason(status: FirefoxEngineApi.LiveStatus?, consecutiveDown: Int): String? {
        if (consecutiveDown >= DOWN_THRESHOLD) return "engine stopped answering"
        if (status == null) return null
        if (status.noNetwork) return null // the phone has no network; another engine would not help
        val code = status.connectFailure.ifBlank { status.wakeError }
        return when {
            isHardFailure(code) -> code
            status.quotaLimitBytes > 0 && !status.quotaUnlimited && status.quotaRemainingBytes <= 0 ->
                "quota"
            else -> null
        }
    }

    /** Failure codes that mean the whole Firefox path is spent, not a moment's trouble. */
    internal fun isHardFailure(code: String): Boolean = code in setOf(
        "quota", "no_account", "no_accounts", "sign_in", "not_entitled", "region",
    )

    /**
     * Hands the tunnel to another Omni engine. Picks the configured fallback, or the first non-Firefox
     * profile, and restarts the service on it. Never a direct route.
     */
    private fun failOver(context: Context, reason: String) {
        val fallback = pickFallback(context)
        if (fallback == null) {
            LogUtil.w(TAG, "Firefox path failed ($reason) but there is no other engine to fall back to; keeping the tunnel down rather than going direct")
            MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
            return
        }
        LogUtil.i(TAG, "Firefox path failed ($reason); falling back to another Omni engine, guid=$fallback")
        Blackbox.record("shield_fallback", "reason=$reason guid=$fallback")
        MmkvManager.setSelectServer(fallback)
        // Restart re-reads the selected server; a non-Firefox row takes the normal protected path.
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /**
     * The profile to fall back to: the one the person configured, or else the first usable non-Firefox
     * row. Returns null when there is nothing else, in which case the tunnel stays down rather than
     * leaking traffic directly.
     */
    internal fun pickFallback(context: Context): String? {
        val configured = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_SHIELD_FALLBACK)
        if (!configured.isNullOrBlank() && !FirefoxBridge.isFirefoxRow(configured) &&
            MmkvManager.decodeServerConfig(configured) != null
        ) {
            return configured
        }
        for (subscription in MmkvManager.decodeSubscriptions()) {
            if (subscription.subscription.remarks == FirefoxBridge.GROUP_NAME) continue
            for (guid in MmkvManager.decodeServerList(subscription.guid)) {
                if (FirefoxBridge.isFirefoxRow(guid)) continue
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                if (!profile.server.isNullOrBlank()) return guid
            }
        }
        return null
    }
}
