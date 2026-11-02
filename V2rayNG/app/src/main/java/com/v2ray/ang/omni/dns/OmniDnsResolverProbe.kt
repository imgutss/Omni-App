package com.v2ray.ang.omni.dns

import com.v2ray.ang.omni.lab.OmniLatencyStats
import com.v2ray.ang.omni.lab.OmniScanTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

data class OmniDnsResolverProbeOptions(
    val domain: String,
    val samples: Int = 3,
    val warmup: Int = 1,
    val timeoutMs: Int = 1_500,
    val concurrency: Int = 24,
) {
    init {
        require(samples in 1..20 && warmup in 0..5 && timeoutMs in 100..15_000 && concurrency in 1..64)
    }
}

data class OmniDnsResolverResult(
    val target: OmniScanTarget,
    val stats: OmniLatencyStats,
    val responseCode: Int? = null,
) {
    val working: Boolean get() = stats.succeeded > 0 && (responseCode == null || responseCode == 0)
}

class OmniDnsResolverProbe(private val random: SecureRandom = SecureRandom()) {
    fun scan(targets: List<OmniScanTarget>, options: OmniDnsResolverProbeOptions): Flow<OmniDnsResolverResult> = channelFlow {
        val queue = Channel<OmniScanTarget>(capacity = options.concurrency * 2)
        val producer = launch {
            try {
                targets.forEach { queue.send(it) }
            } finally {
                queue.close()
            }
        }
        repeat(options.concurrency.coerceAtMost(targets.size.coerceAtLeast(1))) {
            launch {
                for (target in queue) send(probe(target, options))
            }
        }
        producer.join()
    }

    private suspend fun probe(target: OmniScanTarget, options: OmniDnsResolverProbeOptions): OmniDnsResolverResult = withContext(Dispatchers.IO) {
        val samples = ArrayList<Long?>(options.samples)
        var responseCode: Int? = null
        repeat(options.warmup + options.samples) { iteration ->
            val attempt = runCatching { query(target, options.domain, options.timeoutMs) }.getOrNull()
            if (iteration >= options.warmup) {
                val code = attempt?.second
                // A syntactically valid NXDOMAIN/SERVFAIL is a response, not a working
                // tunnel candidate. Count only NOERROR replies as successful samples;
                // E2E still has to prove that the native tunnel carries real HTTP.
                samples += attempt?.first?.takeIf { code == 0 }
                if (code != null) responseCode = code
            }
        }
        OmniDnsResolverResult(target, OmniLatencyStats.from(samples, options.samples), responseCode)
    }

    private fun query(target: OmniScanTarget, domain: String, timeoutMs: Int): Pair<Long?, Int?> {
        val id = random.nextInt(65536)
        val query = buildDnsQuery(domain, id)
        val started = System.nanoTime()
        DatagramSocket().use { socket ->
            val address = InetAddress.getByName(target.host)
            socket.connect(address, target.port)
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(query, query.size, address, target.port))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val response = packet.data.copyOf(packet.length)
            if (!validDnsResponse(query, response)) return null to null
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            return elapsed to (response[3].toInt() and 0x0f)
        }
    }
}

internal fun buildDnsQuery(domain: String, id: Int): ByteArray {
    val labels = domain.trim().trimEnd('.').split('.')
    require(labels.isNotEmpty() && labels.all { it.isNotEmpty() && it.length <= 63 })
    val output = java.io.ByteArrayOutputStream()
    output.write((id ushr 8) and 255)
    output.write(id and 255)
    output.write(byteArrayOf(1, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // RD, one question, no other sections.
    labels.forEach { label ->
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..63)
        output.write(bytes.size)
        output.write(bytes)
    }
    output.write(0)
    output.write(byteArrayOf(0, 1, 0, 1)) // A, IN.
    require(output.size() <= 12 + 255 + 4)
    return output.toByteArray()
}

internal fun validDnsResponse(query: ByteArray, response: ByteArray): Boolean {
    if (query.size < 17 || response.size < query.size) return false
    if (response[0] != query[0] || response[1] != query[1]) return false
    if (response[2].toInt() and 0x80 == 0) return false
    if (response[4] != query[4] || response[5] != query[5] || response[5] != 1.toByte()) return false
    return response.copyOfRange(12, query.size).contentEquals(query.copyOfRange(12, query.size))
}
