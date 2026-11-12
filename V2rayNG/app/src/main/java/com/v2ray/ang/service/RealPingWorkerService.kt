package com.v2ray.ang.service

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.DnsttDelayTester
import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.core.GutsDelayTester
import com.v2ray.ang.core.QuicDelayTester
import com.v2ray.ang.core.MasterDnsDelayTester
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.OmniPingStatistics
import com.v2ray.ang.util.OmniPingStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs.
        // Parallel teardown can abort the native probe process, so serialize their
        // JNI measurements globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { block() }
        } else {
            block()
        }
    }
}

/**
 * The shape of one batch: how many samples each profile runs and how many threads the batch holds.
 *
 * Every sample of a profile runs **one after the other on the same thread**, never several at once: samples
 * that overlap share one uplink and report each other's queueing as latency, and the old code launched
 * `concurrency × samples` native probes at once (2988 threads at the defaults) which is what made a test of
 * a few hundred profiles take minutes. The samples of *different* profiles still run in parallel, which is
 * where the wall-clock win is.
 */
internal object RealPingBatchPlan {
    /** A native probe blocks its thread and cannot be cancelled from Kotlin, so the threads are bounded. */
    const val MAX_THREADS = 16

    /** The most concurrent real-ping tests a batch may run, whatever the settings ask for. */
    const val MAX_CONCURRENCY = 16

    /**
     * The samples launched for one profile: the requested count, never padded and never hidden.
     *
     * The cap is three and not five because one native sample is already the **best of two** requests
     * (libv2ray's `measureInstDelay` runs two attempts and keeps the smaller one), so five samples meant ten
     * round trips for one row. Three samples still give a minimum, a median and a maximum to report.
     */
    fun samples(requested: Int): Int = requested.coerceIn(1, 3)

    /** How many concurrent tests a batch runs, clamped to what the device and the core can carry. */
    fun concurrency(requested: Int): Int = requested.coerceIn(1, MAX_CONCURRENCY)

    /** Threads a batch needs: one per concurrent test. */
    fun threads(concurrency: Int): Int = concurrency.coerceIn(1, MAX_THREADS)

    /**
     * How long one profile may spend answering before its result is given up as "no response".
     *
     * A native probe can block for up to twelve seconds on its own; a whole test must not.
     */
    const val PROFILE_BUDGET_MS = 8_000L

    /** How long a whole batch may run before the profiles left are reported as not measured, not as failed. */
    const val BATCH_BUDGET_MS = 75_000L
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = RealPingBatchPlan.concurrency(SettingsManager.getRealPingConcurrency())
    private val samples = RealPingBatchPlan.samples(SettingsManager.getRealPingSamples())
    private val dispatcher = Executors.newFixedThreadPool(
        RealPingBatchPlan.threads(concurrency)
    ).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    // Names the measurements of this batch in the native core, so that cancel() ends them and no other batch's
    private val batch = UUID.randomUUID().toString()

    private val startedAt = SystemClock.elapsedRealtime()
    private val doneCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(guids.size)

    fun start() {
        // A queue of the profiles left, drained by a fixed number of workers: no profile waits for a
        // thread of its own and no worker sits idle while the rest of the batch is still queued.
        val queue = ArrayDeque(guids)

        val workers = List(concurrency.coerceAtMost(totalCount.get().coerceAtLeast(1))) {
            scope.launch {
                while (scope.isActive) {
                    val guid = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    val measurement = try {
                        if (onlyTcp) startTcping(guid) else startRealPing(guid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        untested(onlyTcp)
                    }
                    if (!scope.isActive) break
                    onEvent(
                        RealPingEvent.Result(
                            guid = guid,
                            delayMillis = measurement.delayMillis,
                            sampleCount = measurement.sampleCount,
                            successfulSamples = measurement.successfulSamples,
                            jitterMillis = measurement.jitterMillis,
                            kind = measurement.kind,
                            medianMillis = measurement.medianMillis,
                            maxMillis = measurement.maxMillis,
                            partial = measurement.partial,
                        )
                    )
                    val done = doneCount.incrementAndGet()
                    onEvent(RealPingEvent.Progress("$done / ${totalCount.get()}"))
                }
            }
        }

        scope.launch {
            try {
                joinAll(*workers.toTypedArray())
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
        // A measurement blocks its thread in the native core, where a cancelled coroutine does not reach it. The
        // native call runs on a thread of its own: cancel() is often called on the main thread of a service.
        thread(name = "RealPingCancel") { CoreNativeManager.cancelOutboundDelays(batch) }
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** True while this batch may still start measuring a profile. */
    private fun withinBatchBudget(): Boolean =
        SystemClock.elapsedRealtime() - startedAt < RealPingBatchPlan.BATCH_BUDGET_MS

    private fun untested(onlyTcp: Boolean) = OmniPingStats(
        delayMillis = OmniPingStatistics.UNTESTED_SAMPLE,
        sampleCount = RealPingBatchPlan.samples(samples),
        successfulSamples = 0,
        jitterMillis = 0L,
        kind = if (onlyTcp) OmniPingStatistics.KIND_TCP else OmniPingStatistics.KIND_HTTP,
        partial = true,
    )

    private suspend fun startRealPing(guid: String): OmniPingStats {
        val retFailure = OmniPingStats(-1L, 0, 0, 0L, OmniPingStatistics.KIND_HTTP)

        // Out of time for the batch: say so plainly instead of reporting a failure the profile did not have.
        if (!withinBatchBudget()) return untested(onlyTcp = false)

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        // An Aether profile is measured through its core alone, unless its subscription chains it with
        // other hops: then it is measured as the chain it runs in, as every chained profile is.
        if (config.configType == EConfigType.AETHER && !CoreConfigContextBuilder.isChained(config)) {
            // One tunnel carries every sample of this profile instead of a tunnel per sample.
            return summarizeHttp(
                AetherDelayTester.measureSamples(context, guid, config, SettingsManager.getDelayTestUrl(), samples)
            )
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        val masterDns = configResult.masterDnsCore
        if (masterDns != null) {
            // The Xray probe must run beside the real MasterDNS engine, not against its empty port.
            return summarizeHttp(
                MasterDnsDelayTester.measureVia(context, guid, masterDns, samples) { _, _ ->
                    RealPingExecutionLimiter.run(config.configType) {
                        CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl(), batch)
                    }
                }
            )
        }
        val dnstt = configResult.dnsttCore
        if (dnstt != null) {
            // The Xray probe runs beside the real DNSTT process and its remote SOCKS5 endpoint.
            return summarizeHttp(
                DnsttDelayTester.measureVia(context, guid, dnstt, samples) { _, _ ->
                    RealPingExecutionLimiter.run(config.configType) {
                        CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl(), batch)
                    }
                }
            )
        }
        val guts = configResult.gutsCore
        if (guts != null) {
            // The Xray probe runs beside the real GUTS client and the door it posts to. Fetch
            // mode is probed over plain HTTP at a non-Google host: Apps Script cannot fetch
            // Google's own generate_204, and Xray need not trust the user's CA to time the door.
            val delayUrl = if (guts.mode == GutsCore.Mode.FETCH) {
                GutsCore.FETCH_TEST_URL
            } else {
                SettingsManager.getDelayTestUrl()
            }
            return summarizeHttp(
                GutsDelayTester.measureVia(context, guid, guts, samples) { _, _ ->
                    RealPingExecutionLimiter.run(config.configType) {
                        CoreNativeManager.measureOutboundDelay(configResult.content, delayUrl, batch)
                    }
                }
            )
        }
        configResult.quicCore?.let { core ->
            return summarizeHttp(QuicDelayTester.measureVia(context, core, samples) {
                CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl(), batch)
            })
        }
        val aether = configResult.aetherCore
        if (aether != null) {
            // The configuration reaches the internet through an Aether outbound, so it is measured behind
            // that core: the live session, or a test tunnel on the same port, which its Aether outbounds
            // dial either way, and which dials out through a hop of the chain, as the session's would.
            // Its own server is not probed: it is only reachable through that core.
            return summarizeHttp(
                AetherDelayTester.measureViaSamples(context, guid, aether, configResult.content, samples) { _, _ ->
                    RealPingExecutionLimiter.run(config.configType) {
                        CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl(), batch)
                    }
                }
            )
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            // The gate only skips a dead endpoint quickly; it is not the measurement and it is not the
            // report either. It now runs with the same dual-stack connect the rest of the app uses, so a
            // profile whose IPv6 route is broken is not thrown away before its IPv4 path is tried.
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, GATE_TIMEOUT_MS)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        return measureRealSamples(samples) {
            RealPingExecutionLimiter.run(config.configType) {
                CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl(), batch)
            }
        }
    }

    /**
     * Runs the samples of one profile one after the other, on this profile's own thread.
     *
     * Sequential and not overlapping: overlapping samples of one profile queue behind each other on the same
     * uplink and report that waiting as latency, and they used to need `samples` extra threads per profile.
     * Each sample still has a deadline, so a probe that never answers costs one budget instead of the batch.
     */
    private suspend fun measureRealSamples(samples: Int, block: suspend () -> Long): OmniPingStats {
        val results = ArrayList<Long>(samples)
        repeat(samples) {
            if (!withinBatchBudget()) {
                results += OmniPingStatistics.UNTESTED_SAMPLE
                return@repeat
            }
            val sample = withTimeoutOrNull(RealPingBatchPlan.PROFILE_BUDGET_MS) { sampleOf(block) }
            // A sample the deadline took away is not a -1 the core reported: keep the two apart.
            results += sample ?: OmniPingStatistics.UNTESTED_SAMPLE
        }
        return summarizeHttp(results)
    }

    /** One real sample: a response in milliseconds, or -1 when this attempt got none. */
    private suspend fun sampleOf(block: suspend () -> Long): Long = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        -1L
    }

    private fun summarizeHttp(samples: List<Long>): OmniPingStats =
        OmniPingStatistics.summarize(samples, OmniPingStatistics.KIND_HTTP)

    private fun startTcping(guid: String): OmniPingStats {
        val retFailure = OmniPingStats(-1L, 0, 0, 0L, OmniPingStatistics.KIND_TCP)

        if (!withinBatchBudget()) return untested(onlyTcp = true)

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.AETHER) {
            return tcpStats(AetherDelayTester.reachability(config))
        }
        // A DNS-tunnel profile has no ordinary TCP endpoint to ping; leave it explicitly untested.
        if (config.configType == EConfigType.MASTERDNS) return tcpStats(AetherDelayTester.UNTESTED)
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, GATE_TIMEOUT_MS)

            return tcpStats(tcpTime)
        }

        return retFailure
    }

    private fun tcpStats(value: Long): OmniPingStats =
        OmniPingStatistics.summarize(listOf(value), OmniPingStatistics.KIND_TCP)

    private companion object {
        /** The reachability gate before a real test: long enough for a slow handshake, short enough to skip. */
        const val GATE_TIMEOUT_MS = 2_500
    }
}
