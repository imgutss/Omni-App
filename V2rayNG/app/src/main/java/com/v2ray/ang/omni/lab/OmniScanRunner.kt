package com.v2ray.ang.omni.lab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

data class OmniScanOptions(
    val ports: List<Int> = listOf(443),
    val concurrency: Int = 24,
    val maxTargets: Int = 1_000,
    val probe: OmniProbeOptions = OmniProbeOptions(),
) {
    init {
        require(ports.isNotEmpty() && ports.all { it in 1..65535 }) { "At least one valid port is required" }
        require(concurrency in 1..128) { "Concurrency must be between 1 and 128" }
        require(maxTargets in 1..100_000) { "Target limit must be between 1 and 100000" }
    }
}

/** A bounded, cancellable stream. The parser's lazy CIDR/range sequence is never converted to a large list. */
class OmniScanRunner(private val probe: OmniEdgeProbe = OmniDefaultEdgeProbe()) {
    fun scan(plan: OmniTargetPlan, options: OmniScanOptions): Flow<OmniEdgeProbeResult> = channelFlow {
        val queue = Channel<OmniScanTarget>(capacity = options.concurrency * 2)
        val producer = launch {
            try {
                var emitted = 0
                val iterator = plan.concreteTargets(options.ports).iterator()
                while (emitted < options.maxTargets && iterator.hasNext()) {
                    queue.send(iterator.next())
                    emitted++
                }
            } finally {
                queue.close()
            }
        }
        repeat(options.concurrency) {
            launch {
                try {
                    for (target in queue) {
                        send(probe.probe(target, options.probe))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
        }
        producer.join()
    }
}

fun List<OmniEdgeProbeResult>.rankByEdgeQuality(): List<OmniEdgeProbeResult> = sortedWith(
    compareByDescending<OmniEdgeProbeResult> { it.verified }
        .thenByDescending { it.stats.successRate }
        .thenBy { it.stats.medianMs ?: Long.MAX_VALUE }
        .thenBy { it.stats.jitterMs ?: Long.MAX_VALUE }
        .thenByDescending { it.stats.succeeded }
        .thenBy { it.target.endpointId },
)
