package com.v2ray.ang.core

import com.v2ray.ang.fmt.DnsttFmt
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** Real JSch handshake and direct-tcpip forwarding through a raw DNSTT-shaped hop. */
class DnsttSshTunnelTest {
    @Test
    fun realSshSessionProvidesSocksConnectThroughTheRawHop() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val echo = ServerSocket(0, 8, loopback)
        val echoWorker = thread(name = "test-echo", isDaemon = true) {
            runCatching {
                echo.accept().use { client ->
                    val request = ByteArray(4)
                    client.getInputStream().readFully(request)
                    client.getOutputStream().write(request)
                    client.getOutputStream().flush()
                }
            }
        }

        val sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, _ ->
                username == "test-user" && password == "test-password"
            }
            forwardingFilter = ForwardingFilter.asForwardingFilter(null, null, TcpForwardingFilter.DEFAULT)
        }
        sshd.start()

        val pair = freeAdjacentPorts(loopback)
        val raw = RawTcpForwarder(pair.second, "127.0.0.1", sshd.port)
        raw.start()
        val core = DnsttCore(
            transport = DnsttFmt.Transport.UDP,
            resolver = "1.1.1.1:53",
            domain = "t.example.com",
            publicKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
            utls = DnsttFmt.DEFAULT_UTLS,
            port = pair.first,
            engine = DnsttFmt.ENGINE_NOIZDNS,
            ssh = DnsttSshConfig(username = "test-user", password = "test-password"),
        )
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        try {
            DnsttSshTunnel.start(core) { failures += it }
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!DnsttSshTunnel.isReady(core.key) && System.nanoTime() < deadline && failures.isEmpty()) {
                Thread.sleep(50)
            }
            assertTrue(failures.isEmpty(), failures.joinToString { it.message ?: it.javaClass.simpleName })
            assertTrue(DnsttSshTunnel.isReady(core.key), "SSH SOCKS listener did not become ready")

            Socket().use { client ->
                client.connect(java.net.InetSocketAddress(loopback, core.port), 5_000)
                client.soTimeout = 10_000
                val input = client.getInputStream()
                val output = client.getOutputStream()
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(listOf(5, 0), input.readNBytes(2).map { it.toInt() })
                val port = echo.localPort
                output.write(byteArrayOf(
                    0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                    ((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte(),
                ))
                output.flush()
                val response = input.readNBytes(10)
                assertEquals(
                    listOf(5, 0),
                    response.take(2).map { it.toInt() },
                    "response=${response.toList()} failures=${failures.map { it.message }}",
                )
                val payload = byteArrayOf(1, 2, 3, 4)
                output.write(payload)
                output.flush()
                assertTrue(input.readNBytes(4).contentEquals(payload))
            }
        } finally {
            DnsttSshTunnel.stop(core.key)
            raw.close()
            runCatching { sshd.stop() }
            runCatching { echo.close() }
            echoWorker.join(2_000)
        }
    }

    private fun freeAdjacentPorts(loopback: InetAddress): Pair<Int, Int> {
        repeat(100) {
            val first = ServerSocket(0, 1, loopback).use { it.localPort }
            if (first < 65_535) {
                val free = runCatching { ServerSocket(first + 1, 1, loopback).close() }.isSuccess
                if (free) return first to first + 1
            }
        }
        error("No adjacent loopback ports")
    }

    private class RawTcpForwarder(
        private val listenPort: Int,
        private val targetHost: String,
        private val targetPort: Int,
    ) {
        private val server = ServerSocket(listenPort, 8, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true
        private var worker: Thread? = null

        fun start() {
            worker = thread(name = "test-raw-dnstt-hop", isDaemon = true) {
                runCatching {
                    while (running) {
                        val client = server.accept()
                        thread(name = "test-raw-connection", isDaemon = true) {
                            val remote = runCatching { Socket(targetHost, targetPort) }.getOrNull() ?: return@thread
                            val upload = thread(name = "test-raw-upload", isDaemon = true) {
                                runCatching { copy(client.getInputStream(), remote.getOutputStream()) }
                            }
                            runCatching { copy(remote.getInputStream(), client.getOutputStream()) }
                            upload.join(2_000)
                            runCatching { client.close() }
                            runCatching { remote.close() }
                        }
                    }
                }
            }
        }

        fun close() {
            running = false
            runCatching { server.close() }
            worker?.interrupt()
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(16_384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        }
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count < 0) error("EOF")
            offset += count
        }
    }
}
