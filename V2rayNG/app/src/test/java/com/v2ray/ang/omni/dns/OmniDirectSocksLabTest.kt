package com.v2ray.ang.omni.dns

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OmniDirectSocksLabTest {
    @Test
    fun connectivityAndPingPassThroughTheSpecifiedSocksListener() = runBlocking {
        MiniHttpOrigin().use { origin ->
            MiniSocksProxy().use { proxy ->
                val url = "http://127.0.0.1:${origin.port}/generate_204"
                val lab = OmniDirectSocksLab()
                val connectivity = lab.connectivity(proxy.port, url, 5_000)
                assertTrue(connectivity.success, connectivity.error)
                assertEquals(204, connectivity.httpStatus)

                val ping = lab.ping(proxy.port, url, samples = 3, warmup = 1, timeoutMs = 5_000)
                assertEquals(3, ping.stats.attempted)
                assertEquals(3, ping.stats.succeeded)
                assertEquals(204, ping.lastHttpStatus)
                assertNull(ping.error)
                assertTrue(ping.stats.medianMs != null)
                assertTrue(proxy.connections.get() >= 4)
            }
        }
    }

    @Test
    fun httpFailureAndClosedListenerAreNotTunnelEvidence() = runBlocking {
        MiniHttpOrigin().use { origin ->
            MiniSocksProxy().use { proxy ->
                val url = "http://127.0.0.1:${origin.port}/failure"
                val result = OmniDirectSocksLab().connectivity(proxy.port, url, 5_000)
                assertFalse(result.success)
                assertEquals(503, result.httpStatus)
                assertEquals("HTTP 503", result.error)
            }
        }
        val port = ServerSocket(0).use { it.localPort }
        val result = OmniDirectSocksLab().connectivity(port, "http://127.0.0.1:1/", 5_000)
        assertFalse(result.success)
        assertTrue(result.error != null)
    }
}

/** Minimal local RFC1928 CONNECT fixture; no external network or simulated measurements. */
private class MiniSocksProxy : Closeable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = ServerSocket(0, 32, loopback)
    val port: Int get() = server.localPort
    val connections = AtomicInteger()
    @Volatile private var closed = false
    private val acceptThread = thread(name = "mini-socks-accept", isDaemon = true) {
        while (!closed) {
            try {
                val client = server.accept()
                connections.incrementAndGet()
                thread(name = "mini-socks-session", isDaemon = true) { runCatching { relay(client) } }
            } catch (_: Exception) {
                if (!closed) continue
                break
            }
        }
    }

    private fun relay(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream()
        val output = client.getOutputStream()
        check(input.read() == 5)
        val methods = ByteArray(input.read())
        check(methods.isNotEmpty() && input.read(methods) == methods.size)
        output.write(byteArrayOf(5, 0))
        output.flush()
        val header = ByteArray(4)
        input.readFully(header)
        check(header[0].toInt() == 5 && header[1].toInt() == 1)
        val host = when (header[3].toInt()) {
            1 -> {
                val raw = ByteArray(4)
                input.readFully(raw)
                InetAddress.getByAddress(raw).hostAddress
            }
            3 -> {
                val raw = ByteArray(input.read())
                input.readFully(raw)
                String(raw, Charsets.US_ASCII)
            }
            else -> error("unsupported test address")
        }
        val portRaw = ByteArray(2)
        input.readFully(portRaw)
        val targetPort = (portRaw[0].toInt() and 255) * 256 + (portRaw[1].toInt() and 255)
        Socket().use { remote ->
            remote.connect(InetSocketAddress(host, targetPort), 5_000)
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            val response = thread(name = "mini-socks-downlink", isDaemon = true) {
                runCatching { remote.getInputStream().copyTo(output) }
                runCatching { client.close() }
                runCatching { remote.close() }
            }
            runCatching { input.copyTo(remote.getOutputStream()) }
            response.join(5_000)
            runCatching { client.close() }
        }
    }

    override fun close() {
        closed = true
        server.close()
        acceptThread.join(2_000)
    }
}

private fun java.io.InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = read(target, offset, target.size - offset)
        check(count > 0)
        offset += count
    }
}

private class MiniHttpOrigin : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var closed = false
    private val acceptThread = thread(name = "mini-http-origin", isDaemon = true) {
        while (!closed) {
            try {
                val client = server.accept()
                thread(name = "mini-http-request", isDaemon = true) {
                    client.use { socket ->
                        runCatching {
                            socket.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                            val request = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val failed = request.contains("/failure")
                            val status = if (failed) "503 Service Unavailable" else "204 No Content"
                            socket.getOutputStream().write(
                                "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                            )
                            socket.getOutputStream().flush()
                        }
                    }
                }
            } catch (_: Exception) {
                if (closed) break
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
        acceptThread.join(2_000)
    }
}
