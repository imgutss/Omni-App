package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Runs real measurements through a live or bounded test MasterDNS process; never probes Xray's port alone. */
object MasterDnsDelayTester {
    internal const val TEST_BUDGET_MS = 12_000L
    internal const val STARTUP_BUDGET_MS = 45_000L
    const val UNTESTED = 0L
    private const val POLL_INTERVAL_MS = 200L
    private val tunnels = Mutex()

    suspend fun measureVia(
        context: Context,
        guid: String,
        core: MasterDnsCore,
        probe: suspend (port: Int, deadline: Long) -> Long,
    ): Long {
        val deadline = deadlineAfter(TEST_BUDGET_MS)
        return withCore(context, core, TEST_BUDGET_MS, guid) {
            probe(core.port, deadline)
        } ?: UNTESTED
    }

    /**
     * Uses the same live session when its environment key matches, otherwise creates one serialized
     * test process. A live VPN process is never reaped or stopped by this path. Null means no honest
     * measurement was possible (unsupported core, occupied port, failed startup, or not ready).
     */
    suspend fun <T> withCore(
        context: Context,
        core: MasterDnsCore,
        startupTimeoutMs: Long = TEST_BUDGET_MS,
        guid: String? = null,
        block: suspend () -> T,
    ): T? = tunnels.withLock {
        if (!MasterDnsCoreManager.isSupported(context, core.engine)) return@withLock null
        val live = withContext(Dispatchers.IO) { MasterDnsCoreManager.liveProcess(context) }
        if (live != null) {
            if (live.key != core.key || live.port != core.port) {
                LogUtil.i(AppConfig.TAG, "MasterDnsTest: another core owns the listener, guid=$guid")
                return@withLock null
            }
            if (!awaitLive(live.port, startupTimeoutMs)) return@withLock null
            return@withLock try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "MasterDnsTest: live-session probe failed", error)
                null
            }
        }

        // A port that cannot be checked or is already owned is not evidence of a working tunnel.
        if (withContext(Dispatchers.IO) { MasterDnsCoreManager.checkSessionPort(core.port) } != MasterDnsCoreManager.PortCheck.Free) {
            LogUtil.i(AppConfig.TAG, "MasterDnsTest: listener is unavailable, guid=$guid")
            return@withLock null
        }
        var owned = false
        try {
            owned = true
            MasterDnsCoreManager.start(context, core, protect = null, reapStaleProcesses = false) {}
            if (!MasterDnsCoreManager.awaitListening(startupTimeoutMs)) {
                LogUtil.i(AppConfig.TAG, "MasterDnsTest: core did not become ready, guid=$guid")
                null
            } else {
                block()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "MasterDnsTest: test tunnel failed", error)
            null
        } finally {
            if (owned) {
                MasterDnsCoreManager.stop()
                // This is a bounded blocking wait on the test coroutine, deliberately outside the UI.
                withContext(NonCancellable + Dispatchers.IO) { MasterDnsCoreManager.awaitStopped(5_000L) }
            }
        }
    }

    private suspend fun awaitLive(port: Int, timeoutMs: Long): Boolean {
        val deadline = deadlineAfter(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (withContext(Dispatchers.IO) { MasterDnsCoreManager.answersSocks(port) }) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun deadlineAfter(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
}
