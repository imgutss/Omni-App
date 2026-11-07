package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.OmniPingStatistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Runs measurements through a live or bounded test DNSTT process; never probes an empty port. */
object DnsttDelayTester {
    internal const val TEST_BUDGET_MS = 12_000L
    internal const val STARTUP_BUDGET_MS = 45_000L
    internal const val SSH_STARTUP_BUDGET_MS = 120_000L
    const val UNTESTED = 0L
    private const val POLL_INTERVAL_MS = 200L
    private val tunnels = Mutex()

    /**
     * The samples of one profile through the engine that carries them: it starts once and answers for
     * every sample, so the test measures the profile instead of the startup of its engine again.
     */
    suspend fun measureVia(
        context: Context,
        guid: String,
        core: DnsttCore,
        samples: Int = 1,
        probe: suspend (port: Int, deadline: Long) -> Long,
    ): List<Long> {
        // Startup can take much longer than an individual HTTP probe on a censored DNS path.
        // Start the probe budget only after the real engine and any SSH session are ready.
        return withCore(context, core, STARTUP_BUDGET_MS, guid) {
            OmniPingStatistics.probeSamples(probe, core.port, deadlineAfter(TEST_BUDGET_MS), samples)
        } ?: OmniPingStatistics.untestedSamples(samples)
    }

    suspend fun <T> withCore(
        context: Context,
        core: DnsttCore,
        startupTimeoutMs: Long = TEST_BUDGET_MS,
        guid: String? = null,
        block: suspend () -> T,
    ): T? = tunnels.withLock {
        if (!DnsttCoreManager.isSupported(context, core.engine)) return@withLock null
        val live = withContext(Dispatchers.IO) { DnsttCoreManager.liveProcess(context) }
        if (live != null) {
            if (live.key != core.key || live.port != core.enginePort) {
                LogUtil.i(AppConfig.TAG, "DnsttTest: another core owns the listener, guid=$guid")
                return@withLock null
            }
            if (!awaitLive(core, startupTimeoutMs)) return@withLock null
            return@withLock try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "DnsttTest: live-session probe failed", error)
                null
            }
        }
        val ports = core.sessionPorts
        ports.forEach { port ->
            if (withContext(Dispatchers.IO) { DnsttCoreManager.checkSessionPort(port) } != DnsttCoreManager.PortCheck.Free) {
                LogUtil.i(AppConfig.TAG, "DnsttTest: listener is unavailable, guid=$guid")
                return@withLock null
            }
        }
        val effectiveStartupTimeout = if (core.ssh == null) startupTimeoutMs else maxOf(startupTimeoutMs, SSH_STARTUP_BUDGET_MS)
        var owned = false
        try {
            owned = true
            DnsttCoreManager.start(context, core, protect = null, reapStaleProcesses = false) {}
            if (!DnsttCoreManager.awaitListening(effectiveStartupTimeout)) {
                LogUtil.i(AppConfig.TAG, "DnsttTest: core did not become ready, guid=$guid")
                null
            } else {
                block()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "DnsttTest: test tunnel failed", error)
            null
        } finally {
            if (owned) {
                DnsttCoreManager.stop()
                withContext(NonCancellable + Dispatchers.IO) { DnsttCoreManager.awaitStopped(5_000L) }
            }
        }
    }

    /** Runs an independently owned scanner child; the foreground VPN session is never reused or stopped. */
    suspend fun <T> withIsolatedCore(
        context: Context,
        core: DnsttCore,
        startupTimeoutMs: Long = STARTUP_BUDGET_MS,
        block: suspend () -> T,
    ): T? {
        if (!DnsttCoreManager.isSupported(context, core.engine)) return null
        core.sessionPorts.forEach { port ->
            if (withContext(Dispatchers.IO) { DnsttCoreManager.checkSessionPort(port) } != DnsttCoreManager.PortCheck.Free) return null
        }
        var owned: DnsttCoreManager.Session? = null
        return try {
            val target = DnsttCoreManager.startIsolated(context, core)
            owned = target
            val effectiveStartupTimeout = if (core.ssh == null) startupTimeoutMs else maxOf(startupTimeoutMs, SSH_STARTUP_BUDGET_MS)
            if (DnsttCoreManager.awaitIsolatedListening(target, effectiveStartupTimeout)) block() else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "DnsttTest: isolated scanner tunnel failed", error)
            null
        } finally {
            owned?.let { target ->
                DnsttCoreManager.stopIsolated(target)
                withContext(NonCancellable + Dispatchers.IO) { DnsttCoreManager.awaitStopped(5_000L) }
            }
        }
    }

    private suspend fun awaitLive(core: DnsttCore, timeoutMs: Long): Boolean {
        val deadline = deadlineAfter(if (core.ssh == null) timeoutMs else maxOf(timeoutMs, SSH_STARTUP_BUDGET_MS))
        while (System.nanoTime() < deadline) {
            if (withContext(Dispatchers.IO) { DnsttCoreManager.answersSocks(core.port) }) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun deadlineAfter(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
}
