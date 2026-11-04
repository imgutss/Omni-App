package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.DnsttDelayTester
import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.core.GutsDelayTester
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** The shape of one batch: how many samples each profile runs and how many threads the batch holds. */
internal object RealPingBatchPlan {
    /** A native probe blocks its thread and cannot be cancelled from Kotlin, so the threads are bounded. */
    const val MAX_THREADS = 64

    /** The samples launched for one profile: the requested count, never padded and never hidden. */
    fun samples(requested: Int): Int = requested.coerceIn(1, 5)

    /**
     * Every sample of every profile runs at the same time in its own thread until it answers, so the
     * pool has to hold them all: a smaller pool would queue the samples that run together again.
     * TCP ping connects one socket per profile and needs no room for samples.
     */
    fun threads(concurrency: Int, samples: Int, onlyTcp: Boolean): Int =
        if (onlyTcp) {
            (concurrency * 2).coerceIn(1, MAX_THREADS)
        } else {
            (concurrency * samples).coerceIn(1, MAX_THREADS)
        }
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
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val samples = RealPingBatchPlan.samples(SettingsManager.getRealPingSamples())
    private val dispatcher = Executors.newFixedThreadPool(
        RealPingBatchPlan.threads(concurrency, samples, onlyTcp)
    ).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    // Names the measurements of this batch in the native core, so that cancel() ends them and no other batch's
    private val batch = UUID.randomUUID().toString()

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = if (onlyTcp) startTcping(guid) else startRealPing(guid)
                    if (scope.isActive) {
                        onEvent(
                            RealPingEvent.Result(
                                guid = guid,
                                delayMillis = result.delayMillis,
                                sampleCount = result.sampleCount,
                                successfulSamples = result.successfulSamples,
                                jitterMillis = result.jitterMillis,
                                kind = result.kind,
                            )
                        )
                    }
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
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

    private suspend fun startRealPing(guid: String): OmniPingStats {
        val retFailure = OmniPingStats(-1L, 0, 0, 0L, OmniPingStatistics.KIND_HTTP)

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
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)
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
     * Launches every sample of one profile at the same time. A native probe blocks its thread for as
     * long as its own HTTP client takes, so overlapping the samples overlaps their connect and TLS
     * time instead of adding it up; the batch is still cancellable, which ends them in the native core.
     * No sample is a hidden warm-up any more: every reported number is a real response.
     */
    private suspend fun measureRealSamples(samples: Int, block: suspend () -> Long): OmniPingStats =
        coroutineScope {
            val results = List(samples) { async { sampleOf(block) } }.awaitAll()
            summarizeHttp(results)
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
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)

            return tcpStats(tcpTime)
        }

        return retFailure
    }

    private fun tcpStats(value: Long): OmniPingStats =
        OmniPingStatistics.summarize(listOf(value), OmniPingStatistics.KIND_TCP)
}
