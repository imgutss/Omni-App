package com.v2ray.ang.omni.dns

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.omni.lab.OmniLatencyStats
import com.v2ray.ang.omni.lab.OmniScanTarget
import com.v2ray.ang.omni.lab.OmniTargetKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class OmniDnsScannerLogicTest {
    @Test
    fun nonZeroRcodeIsNotAWorkingCandidateEvenWhenTheServerAnswers() = runBlocking {
        val failure = probeResponse(2)
        assertEquals(2, failure.responseCode)
        assertEquals(2, failure.stats.attempted)
        assertEquals(0, failure.stats.succeeded)
        assertFalse(failure.working)

        val noError = probeResponse(0)
        assertEquals(0, noError.responseCode)
        assertEquals(2, noError.stats.succeeded)
        assertTrue(noError.working)
    }

    @Test
    fun explicitNonZeroRcodeCannotBeMarkedWorkingBySyntheticStats() {
        val result = result("192.0.2.1", 10, rcode = 3)
        assertFalse(result.working)
    }

    @Test
    fun e2eShortlistIsBoundedAndUsesDnsQualityOrder() {
        val slow = result("192.0.2.1", 100)
        val fastest = result("192.0.2.2", 20)
        val middle = result("192.0.2.3", 50)
        val refused = result("192.0.2.4", 1, rcode = 5)
        val selected = dnsE2eShortlist(listOf(slow, refused, middle, fastest), 2)
        assertEquals(listOf(fastest.target.endpointId, middle.target.endpointId), selected.map { it.target.endpointId })
        assertEquals(1, dnsE2eShortlist(listOf(slow, fastest, middle), 1).size)
        assertTrue(dnsE2eShortlist(listOf(refused), 6).isEmpty())
    }

    @Test
    fun probeUsesTheActualTunnelAuthorityForEachEngine() {
        val master = ProfileItem(configType = EConfigType.MASTERDNS, server = "master.example.com", dnsttDomain = "wrong.example.com")
        val dnstt = ProfileItem(configType = EConfigType.DNSTT, server = "127.0.0.1", dnsttDomain = "dnstt.example.com")
        assertEquals("master.example.com", dnsProfileProbeDomain(master))
        assertEquals("dnstt.example.com", dnsProfileProbeDomain(dnstt))
    }

    @Test
    fun scanBudgetTracksWorkButHasAGlobalCap() {
        assertEquals(71_000L, dnsScanBudgetMs(256, 3, 1_500, 24))
        assertEquals(30_000L, dnsScanBudgetMs(1, 1, 100, 1))
        assertEquals(180_000L, dnsScanBudgetMs(100_000, 20, 15_000, 1))
    }

    private suspend fun probeResponse(rcode: Int): OmniDnsResolverResult {
        return DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "dns-rcode-fixture", isDaemon = true) {
                repeat(2) {
                    runCatching {
                        val input = ByteArray(512)
                        val packet = DatagramPacket(input, input.size)
                        server.receive(packet)
                        val response = packet.data.copyOf(packet.length)
                        response[2] = (response[2].toInt() or 0x80).toByte()
                        response[3] = ((response[3].toInt() and 0xf0) or rcode).toByte()
                        server.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                }
            }
            val target = OmniScanTarget("127.0.0.1", server.localPort, OmniTargetKind.IPV4)
            val result = OmniDnsResolverProbe().scan(
                listOf(target),
                OmniDnsResolverProbeOptions("t.example.com", samples = 2, warmup = 0, timeoutMs = 500, concurrency = 1),
            ).toList().single()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            result
        }
    }

    private fun result(host: String, latency: Long, rcode: Int? = 0) = OmniDnsResolverResult(
        OmniScanTarget(host, 53, OmniTargetKind.IPV4),
        OmniLatencyStats(listOf(latency), 1, 1),
        responseCode = rcode,
    )
}
