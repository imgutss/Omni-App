package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.fmt.DnsttFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class DnsttCoreManagerTest {
    private val core = DnsttCore(
        transport = DnsttFmt.Transport.UDP,
        resolver = "1.1.1.1:53",
        domain = "t.example.com",
        publicKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        utls = DnsttFmt.DEFAULT_UTLS,
    )

    @Test
    fun processArgumentsUseTheOfficialClientFlagsAndAppOwnedListener() {
        val arguments = DnsttCoreManager.processArguments(
            binary = File("/data/app/lib/libdnstt.so"),
            core = core,
            protectAddress = "@omni-test",
        )
        assertEquals("/data/app/lib/libdnstt.so", arguments.first())
        assertTrue("-udp=1.1.1.1:53" in arguments)
        assertTrue("-pubkey=${core.publicKey}" in arguments)
        assertTrue("-utls=${core.utls}" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-test" in arguments)
        assertEquals("t.example.com", arguments[arguments.lastIndex - 1])
        assertEquals("127.0.0.1:18001", arguments.last())
    }

    @Test
    fun noizProcessArgumentsPreserveSlipNetControls() {
        val noiz = core.copy(
            engine = DnsttFmt.ENGINE_NOIZDNS,
            transport = DnsttFmt.Transport.DOT,
            resolver = "1.1.1.1:853,8.8.8.8:853",
            authoritative = true,
            noiz = true,
            stealth = true,
            maxPayload = 80,
            resolverMode = "roundrobin",
            rrSpreadCount = 2,
        )
        val arguments = DnsttCoreManager.processArguments(
            binary = File("/data/app/lib/libnoizdns.so"),
            core = noiz,
            protectAddress = "@omni-test",
        )
        assertTrue("-dot=1.1.1.1:853,8.8.8.8:853" in arguments)
        assertTrue("-authoritative" in arguments)
        assertTrue("-noiz" in arguments)
        assertTrue("-stealth" in arguments)
        assertTrue("-max-payload=80" in arguments)
        assertTrue("-resolver-mode=roundrobin" in arguments)
        assertTrue("-rr-spread=2" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-test" in arguments)
        assertFalse(arguments.any { it.startsWith("-udp=") })
    }

    @Test
    fun mixedResolverModesReachTheCompositeWithoutInvalidDnsSuffixes() {
        val noiz = core.copy(
            engine = DnsttFmt.ENGINE_DNSTT,
            resolver = "1.1.1.1:53,9.9.9.9:53",
            resolverAuthorities = listOf(false, true),
            authoritative = false,
            noiz = true,
        )
        val arguments = DnsttCoreManager.processArguments(
            binary = File("/data/app/lib/libnoizdns.so"),
            core = noiz,
            protectAddress = null,
        )
        assertTrue("-resolver-authoritative=0,1" in arguments)
        assertTrue("-udp=1.1.1.1:53,9.9.9.9:53" in arguments)
        assertFalse("-authoritative" in arguments)
        assertFalse(arguments.any { it.contains("1.1.1.1:53:0") || it.contains("9.9.9.9:53:1") })
    }

    @Test
    fun sshChainUsesSeparateRawTunnelAndPublicSocksPorts() {
        val chained = core.copy(
            engine = DnsttFmt.ENGINE_NOIZDNS,
            ssh = DnsttSshConfig(username = "test-user", password = "test-password"),
        )
        val arguments = DnsttCoreManager.processArguments(
            binary = File("/data/app/lib/libnoizdns.so"),
            core = chained,
            protectAddress = null,
        )
        assertEquals("127.0.0.1:18002", arguments.last())
        assertTrue(arguments.contains("-raw-tcp"))
        assertEquals(18_001, chained.port)
        assertEquals(18_002, chained.tunnelPort)
        assertFalse(arguments.any { it.contains("test-user") || it.contains("test-password") })
    }

    @Test
    fun vaydnsUsesRawOrSocksBridgeListenersWithoutLeakingCredentialsIntoArgv() {
        val authenticated = core.copy(
            engine = DnsttFmt.ENGINE_VAYDNS,
            resolver = "1.1.1.1:53,9.9.9.9:53",
            socksUsername = "secret-user",
            socksPassword = "secret-pass",
            vaydns = VayDnsConfig(dnsttCompat = true, recordType = "cname", maxQnameLen = 120, rps = 20.0),
        )
        val arguments = DnsttCoreManager.processArguments(File("/data/app/lib/libvaydns.so"), authenticated, "@omni-test")
        assertTrue("-udp=1.1.1.1:53,9.9.9.9:53" in arguments)
        assertTrue("-dnstt-compat" in arguments)
        assertTrue("-record-type=cname" in arguments)
        assertTrue("-max-qname-len=120" in arguments)
        assertTrue("-listen=127.0.0.1:18002" in arguments)
        assertTrue("-socks-listen=127.0.0.1:18001" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-test" in arguments)
        assertFalse(arguments.any { it.contains("secret-user") || it.contains("secret-pass") })

        val standalone = authenticated.copy(socksUsername = "", socksPassword = "")
        val standaloneArguments = DnsttCoreManager.processArguments(File("/data/app/lib/libvaydns.so"), standalone, null)
        assertEquals(18_002, standalone.enginePort)
        assertEquals(listOf(18_001, 18_002), standalone.sessionPorts)
        assertTrue("-listen=127.0.0.1:18002" in standaloneArguments)
        assertTrue("-socks-listen=127.0.0.1:18001" in standaloneArguments)
        assertFalse("-raw-tcp" in standaloneArguments)

        val chained = authenticated.copy(
            socksUsername = "",
            socksPassword = "",
            ssh = DnsttSshConfig(username = "ssh-user", password = "ssh-pass"),
        )
        val sshArguments = DnsttCoreManager.processArguments(File("/data/app/lib/libvaydns.so"), chained, null)
        assertTrue("-raw-tcp" in sshArguments)
        assertTrue("-listen=127.0.0.1:18002" in sshArguments)
        assertFalse(sshArguments.any { it.startsWith("-socks-listen=") })
    }

    @Test
    fun slipstreamArgumentsUseRealQuicCliAndSeparateAuthorityMetadata() {
        val slipstream = core.copy(
            engine = DnsttFmt.ENGINE_SLIPSTREAM,
            publicKey = "",
            resolver = "1.1.1.1:53,9.9.9.9:53",
            resolverAuthorities = listOf(false, true),
            slipstream = SlipstreamConfig(keepAliveIntervalMs = 4_000, congestionControl = "dcubic", gsoEnabled = true),
        )
        val arguments = DnsttCoreManager.processArguments(File("/data/app/lib/libslipstream.so"), slipstream, "@omni-test")
        assertTrue("--tcp-listen-port=18002" in arguments)
        assertTrue("--resolver=1.1.1.1:53" in arguments)
        assertTrue("--authoritative=9.9.9.9:53" in arguments)
        assertTrue("--congestion-control=dcubic" in arguments)
        assertTrue("--keep-alive-interval=4000" in arguments)
        assertTrue("--gso" in arguments)
        assertFalse(arguments.any { it.startsWith("-pubkey=") })
        assertFalse(arguments.any { it.startsWith("-fd-control-unix-socket=") })
        assertFalse(arguments.any { it.contains(":53:0") || it.contains(":53:1") })
    }

    @Test
    fun soarUsesTheRealLauncherAndIncludesTuningInSessionIdentity() {
        val soar = core.copy(
            engine = DnsttFmt.ENGINE_SOAR,
            publicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
            utls = "",
            soarSettings = "{\"discovery\":false,\"transport\":\"off\"}",
        )
        val arguments = DnsttCoreManager.processArguments(File("/data/app/lib/libsoar.so"), soar, "@omni-test")
        assertTrue("-domain=t.example.com" in arguments)
        assertTrue("-public-key=${soar.publicKey}" in arguments)
        assertTrue("-resolvers=soar_resolvers.txt" in arguments)
        assertTrue("-settings=soar_settings.json" in arguments)
        assertTrue("-listen=127.0.0.1:18001" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-test" in arguments)
        assertFalse(arguments.any { it.startsWith("-pubkey=") || it.startsWith("-udp=") })
        assertFalse(soar.key == soar.copy(soarSettings = "{\"discovery\":true,\"transport\":\"off\"}").key)
    }

    @Test
    fun proxyAndRootModesDoNotInventAProtectEndpoint() {
        val arguments = DnsttCoreManager.processArguments(File("/bin/libdnstt.so"), core, null)
        assertFalse(arguments.any { it.startsWith("-fd-control-unix-socket=") })
    }

    @Test
    fun portFallbackRewritesOnlyTheSingleLoopbackDnsttOutbound() {
        val content = """
            {"inbounds":[],"outbounds":[
              {"tag":"proxy","protocol":"socks","settings":{"address":"127.0.0.1","port":18001}},
              {"tag":"other","protocol":"socks","settings":{"address":"127.0.0.1","port":19000}},
              {"tag":"direct","protocol":"freedom","settings":{}}
            ]}
        """.trimIndent()
        val rewritten = DnsttCoreManager.withDnsttPort(content, 18001, 28001) ?: error("DNSTT outbound should be rewritten")
        val outbounds = JsonParser.parseString(rewritten).asJsonObject.getAsJsonArray("outbounds")
        assertEquals(28001, outbounds[0].asJsonObject.getAsJsonObject("settings").get("port").asInt)
        assertEquals(19000, outbounds[1].asJsonObject.getAsJsonObject("settings").get("port").asInt)
        assertEquals("freedom", outbounds[2].asJsonObject.get("protocol").asString)
        assertNull(DnsttCoreManager.withDnsttPort(content, 17000, 28001))
        assertNull(DnsttCoreManager.withDnsttPort("{}", 18001, 28001))
    }

    @Test
    fun readinessRequiresARealSocksNoAuthGreetingFromTheRemoteEnd() {
        assertTrue(answersWith(byteArrayOf(0x05, 0x00)))
        assertFalse(answersWith(byteArrayOf(0x05, 0x02)))
        assertFalse(answersWith(byteArrayOf(0x04, 0x00)))
        assertFalse(answersWith(byteArrayOf(0x05)))
    }

    private fun answersWith(response: ByteArray): Boolean {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "test-dnstt-socks-greeting", isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val request = ByteArray(3)
                        client.getInputStream().read(request)
                        if (request.contentEquals(byteArrayOf(0x05, 0x01, 0x00))) {
                            client.getOutputStream().write(response)
                        }
                        client.getOutputStream().flush()
                    }
                }
            }
            val result = DnsttCoreManager.answersSocks(server.localPort)
            worker.join(2_000)
            assertFalse(worker.isAlive)
            return result
        }
    }
}
