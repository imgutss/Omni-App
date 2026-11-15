package com.v2ray.ang.firefox.shield

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.firefox.FirefoxEngineApi
import com.v2ray.ang.firefox.hub.AutoBestPolicy
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto Best Location + Session Guardian, running beside the Firefox tunnel in the daemon.
 *
 * Auto Best periodically measures the reachable locations and moves the exit only when the policy
 * says so (never mid-download, respecting the country lock and favourites, only on a severe drop or a
 * disconnect, with a cooldown). Session Guardian notices a stalled upstream and restarts the engine's
 * proxy in place once before the Hybrid Shield would hand the tunnel to another engine.
 *
 * It spends as little quota as it can: it pings at most every [PING_INTERVAL_MS], only the countries
 * the policy may choose, and it never pings while the tunnel is busy.
 */
object FirefoxAutoPilot {

    private const val TAG = "FirefoxAutoPilot"
    private const val LOOP_MS = 15_000L
    private const val PING_INTERVAL_MS = 120_000L
    private const val BUSY_BYTES_PER_SEC = 200_000L // ~200 KB/s: a download or stream is using the link
    private const val GUARDIAN_RESTART_COOLDOWN_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var lastPingAt = 0L
    @Volatile private var lastSwitchAt = 0L
    @Volatile private var lastGuardianRestartAt = 0L

    fun onSessionStarted(context: Context) {
        job?.cancel()
        lastPingAt = 0L
        lastSwitchAt = 0L
        lastGuardianRestartAt = 0L
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

    private suspend fun tick(app: Context) {
        val status = FirefoxEngineApi.liveStatus(app) ?: return
        val busy = status.downRate >= BUSY_BYTES_PER_SEC || status.upRate >= BUSY_BYTES_PER_SEC

        // Session Guardian: a running proxy whose upstream is erroring gets one in-place restart.
        if (sessionGuardianEnabled() && status.phase == "running" && !busy &&
            status.wakeError.isNotBlank() &&
            System.currentTimeMillis() - lastGuardianRestartAt > GUARDIAN_RESTART_COOLDOWN_MS
        ) {
            lastGuardianRestartAt = System.currentTimeMillis()
            LogUtil.i(TAG, "Session Guardian: restarting a stalled upstream (${status.wakeError})")
            Blackbox.record("session_guardian_restart", status.wakeError)
            FirefoxEngineApi.restartProxy(app)
            return
        }

        if (!autoBestEnabled() || busy || status.phase != "running") return
        val quotaExhausted = !status.quotaUnlimited && status.quotaLimitBytes > 0 && status.quotaRemainingBytes <= 0
        if (quotaExhausted) return

        val now = System.currentTimeMillis()
        if (now - lastPingAt < PING_INTERVAL_MS) return
        lastPingAt = now

        val config = readConfig()
        val poolCodes = poolCodes(config)
        val ping = FirefoxEngineApi.pingLocations(app, poolCodes) ?: return
        val candidates = ping.locations.map { AutoBestPolicy.Candidate(it.code, it.pingMs, it.ok) }
        val currentOk = candidates.firstOrNull { it.code.equals(status.countryCode, true) }?.ok
            ?: (status.wakeError.isBlank() && status.connectFailure.isBlank())
        val situation = AutoBestPolicy.Situation(
            current = status.countryCode.ifBlank { status.selection },
            currentOk = currentOk,
            currentPingMs = status.latencyMs,
            candidates = candidates,
            busy = busy,
            sinceLastSwitchMs = now - lastSwitchAt,
            quotaExhausted = quotaExhausted,
        )
        val target = AutoBestPolicy.decide(config, situation) ?: return
        val problem = FirefoxEngineApi.setLocation(app, target)
        if (problem == null) {
            lastSwitchAt = now
            LogUtil.i(TAG, "Auto Best moved the exit to $target")
            Blackbox.record("auto_best_switch", "to=$target from=${status.countryCode}")
        } else {
            LogUtil.w(TAG, "Auto Best could not move to $target: $problem")
        }
    }

    private fun poolCodes(config: AutoBestPolicy.Config): List<String> {
        config.lockedCountry?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        return config.favorites.toList()
    }

    fun readConfig(): AutoBestPolicy.Config = AutoBestPolicy.Config(
        enabled = autoBestEnabled(),
        lockedCountry = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_COUNTRY_LOCK)
            ?.takeIf { it.isNotBlank() },
        favorites = favorites(),
    )

    fun favorites(): Set<String> =
        MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_FAVORITES)
            ?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()

    fun setFavorites(codes: Set<String>) {
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_FAVORITES, codes.joinToString(",") { it.uppercase() })
    }

    fun toggleFavorite(code: String) {
        val current = favorites().toMutableSet()
        val key = code.uppercase()
        if (!current.add(key)) current.remove(key)
        setFavorites(current)
    }

    fun countryLock(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_COUNTRY_LOCK).orEmpty()

    fun setCountryLock(code: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_COUNTRY_LOCK, code.uppercase())
    }

    fun autoBestEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREFOX_AUTO_BEST, false)
    fun setAutoBestEnabled(value: Boolean) = MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_AUTO_BEST, value)

    fun sessionGuardianEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREFOX_SESSION_GUARDIAN, true)
    fun setSessionGuardianEnabled(value: Boolean) =
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_SESSION_GUARDIAN, value)
}
