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

/**
 * Runs measurements through a live or bounded test GUTS client; never probes an empty port.
 *
 * A GUTS profile's number is only honest if a real client carries it: the probe goes through the
 * config's own SOCKS outbound, which points at the loopback listener of a client this object
 * started (or of the session that already runs), so a door that is down or a key that is wrong
 * reads as "no response" instead of a green number for a port nobody serves.
 */
object GutsDelayTester {
    internal const val TEST_BUDGET_MS = 12_000L

    /** A client scans for its Google edge at startup, so it needs longer than a DNS engine to listen. */
    internal const val STARTUP_BUDGET_MS = 45_000L
    private const val POLL_INTERVAL_MS = 200L
    private val clients = Mutex()

    /**
     * The samples of one profile through the client that carries them: it starts once and answers
     * for every sample, so the test measures the profile instead of the startup of its client again.
     */
    suspend fun measureVia(
        context: Context,
        guid: String,
        core: GutsCore,
        samples: Int = 1,
        probe: suspend (port: Int, deadline: Long) -> Long,
    ): List<Long> {
        // The startup budget covers a scan for the Google edge; only then does a probe budget start.
        return withCore(context, core, STARTUP_BUDGET_MS, guid) {
            OmniPingStatistics.probeSamples(probe, core.port, deadlineAfter(TEST_BUDGET_MS), samples)
        } ?: OmniPingStatistics.untestedSamples(samples)
    }

    suspend fun <T> withCore(
        context: Context,
        core: GutsCore,
        startupTimeoutMs: Long = TEST_BUDGET_MS,
        guid: String? = null,
        block: suspend () -> T,
    ): T? = clients.withLock {
        if (!GutsCoreManager.isSupported(context)) return@withLock null
        val live = withContext(Dispatchers.IO) { GutsCoreManager.liveProcess(context) }
        if (live != null) {
            if (live.identity != core.identity || live.port != core.port) {
                LogUtil.i(AppConfig.TAG, "GutsTest: another client owns the listener, guid=$guid")
                return@withLock null
            }
            if (!awaitLive(core, startupTimeoutMs)) return@withLock null
            return@withLock try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "GutsTest: live-session probe failed", error)
                null
            }
        }
        if (withContext(Dispatchers.IO) { GutsCoreManager.checkSessionPort(core.port) } != GutsCoreManager.PortCheck.Free) {
            LogUtil.i(AppConfig.TAG, "GutsTest: listener is unavailable, guid=$guid")
            return@withLock null
        }
        var owned = false
        try {
            owned = true
            GutsCoreManager.start(context, core, protect = null, reapStaleProcesses = false) {}
            if (!GutsCoreManager.awaitListening(startupTimeoutMs)) {
                LogUtil.i(AppConfig.TAG, "GutsTest: client did not become ready, guid=$guid")
                null
            } else {
                block()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "GutsTest: test tunnel failed", error)
            null
        } finally {
            if (owned) {
                GutsCoreManager.stop()
                withContext(NonCancellable + Dispatchers.IO) { GutsCoreManager.awaitStopped(5_000L) }
            }
        }
    }

    /** Runs an independently owned scanner child; the foreground VPN session is never reused or stopped. */
    suspend fun <T> withIsolatedCore(
        context: Context,
        core: GutsCore,
        startupTimeoutMs: Long = STARTUP_BUDGET_MS,
        block: suspend () -> T,
    ): T? {
        if (!GutsCoreManager.isSupported(context)) return null
        if (withContext(Dispatchers.IO) { GutsCoreManager.checkSessionPort(core.port) } != GutsCoreManager.PortCheck.Free) {
            return null
        }
        var owned: GutsCoreManager.Session? = null
        return try {
            val target = GutsCoreManager.startIsolated(context, core)
            owned = target
            if (GutsCoreManager.awaitIsolatedListening(target, startupTimeoutMs)) block() else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "GutsTest: isolated scanner tunnel failed", error)
            null
        } finally {
            owned?.let { target ->
                GutsCoreManager.stopIsolated(target)
                withContext(NonCancellable + Dispatchers.IO) { GutsCoreManager.awaitStopped(5_000L) }
            }
        }
    }

    private suspend fun awaitLive(core: GutsCore, timeoutMs: Long): Boolean {
        val deadline = deadlineAfter(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (withContext(Dispatchers.IO) { GutsCoreManager.answersSocks(core.port) }) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun deadlineAfter(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
}
