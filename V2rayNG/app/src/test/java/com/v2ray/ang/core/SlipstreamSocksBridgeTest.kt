package com.v2ray.ang.core

import com.v2ray.ang.fmt.DnsttFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SlipstreamSocksBridgeTest {
    @Test
    fun publicNoAuthSocksPerformsRemoteAuthenticationAndRelaysThroughRawPort() {
        val core = DnsttCore(
            transport = DnsttFmt.Transport.UDP,
            resolver = "1.1.1.1:53",
            domain = "t.example.com",
            publicKey = "",
            utls = "",
            port = freeAdjacentPort(),
            engine = DnsttFmt.ENGINE_SLIPSTREAM,
            socksUsername = "user",
            socksPassword = "pass",
        )
        val failed = AtomicBoolean(false)
        val rawServer = ServerSocket(core.enginePort, 4, InetAddress.getByName("127.0.0.1"))
        val remote = thread(name = "test-slipstream-raw", isDaemon = true) {
            runCatching {
                rawServer.accept().use { socket ->
                    val greeting = ByteArray(4)
                    socket.getInputStream().readFully(greeting)
                    assertEquals(byteArrayOf(5, 2, 0, 2).toList(), greeting.toList())
                    socket.getOutputStream().write(byteArrayOf(5, 2))
                    val expected = byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray()
                    val auth = ByteArray(expected.size)
                    socket.getInputStream().readFully(auth)
                    assertEquals(expected.toList(), auth.toList())
                    socket.getOutputStream().write(byteArrayOf(1, 0))
                    val request = ByteArray(10)
                    socket.getInputStream().readFully(request)
                    assertEquals(1, request[1].toInt())
                    socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    val payload = ByteArray(4)
                    socket.getInputStream().readFully(payload)
                    socket.getOutputStream().write(payload)
                    socket.getOutputStream().flush()
                }
            }.onFailure { failed.set(true) }
        }

        SlipstreamSocksBridge.start(core) { failed.set(true) }
        try {
            var client: Socket? = null
            repeat(50) {
                if (client != null) return@repeat
                runCatching {
                    Socket().also { it.connect(InetSocketAddress("127.0.0.1", core.port), 100) }
                }.onSuccess { client = it }
                if (client == null) Thread.sleep(20)
            }
            checkNotNull(client) { "Bridge did not bind its public port" }.use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                val choice = ByteArray(2)
                socket.getInputStream().readFully(choice)
                assertEquals(byteArrayOf(5, 0).toList(), choice.toList())
                socket.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 80))
                val response = ByteArray(10)
                socket.getInputStream().readFully(response)
                assertEquals(0, response[1].toInt())
                socket.getOutputStream().write("ping".toByteArray())
                val echo = ByteArray(4)
                socket.getInputStream().readFully(echo)
                assertEquals("ping", String(echo))
            }
        } finally {
            SlipstreamSocksBridge.stop(core.key)
            rawServer.close()
            remote.join(1_000)
        }
        assertFalse(failed.get())
    }

    @Test
    fun standaloneBridgeAlsoPerformsRemoteNoAuthNegotiation() {
        val core = DnsttCore(
            transport = DnsttFmt.Transport.UDP,
            resolver = "1.1.1.1:53",
            domain = "t.example.com",
            publicKey = "",
            utls = "",
            port = freeAdjacentPort(),
            engine = DnsttFmt.ENGINE_SLIPSTREAM,
        )
        val failed = AtomicBoolean(false)
        val rawServer = ServerSocket(core.enginePort, 4, InetAddress.getByName("127.0.0.1"))
        val remote = thread(name = "test-slipstream-noauth", isDaemon = true) {
            runCatching {
                rawServer.accept().use { socket ->
                    val greeting = ByteArray(3)
                    socket.getInputStream().readFully(greeting)
                    assertEquals(byteArrayOf(5, 1, 0).toList(), greeting.toList())
                    socket.getOutputStream().write(byteArrayOf(5, 0))
                    val request = ByteArray(10)
                    socket.getInputStream().readFully(request)
                    assertEquals(1, request[1].toInt())
                    socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    val payload = ByteArray(32 * 1024)
                    socket.getInputStream().readFully(payload)
                    socket.getOutputStream().write(payload)
                    socket.getOutputStream().flush()
                }
            }.onFailure { failed.set(true) }
        }

        SlipstreamSocksBridge.start(core) { failed.set(true) }
        try {
            var client: Socket? = null
            repeat(50) {
                if (client != null) return@repeat
                runCatching { Socket().also { it.connect(InetSocketAddress("127.0.0.1", core.port), 100) } }
                    .onSuccess { client = it }
                if (client == null) Thread.sleep(20)
            }
            checkNotNull(client) { "Bridge did not bind its public port" }.use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                val choice = ByteArray(2)
                socket.getInputStream().readFully(choice)
                assertEquals(byteArrayOf(5, 0).toList(), choice.toList())
                socket.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 80))
                val response = ByteArray(10)
                socket.getInputStream().readFully(response)
                assertEquals(0, response[1].toInt())
                val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
                socket.getOutputStream().write(payload)
                val echoed = ByteArray(payload.size)
                socket.getInputStream().readFully(echoed)
                assertEquals(payload.toList(), echoed.toList())
            }
        } finally {
            SlipstreamSocksBridge.stop(core.key)
            rawServer.close()
            remote.join(1_000)
        }
        assertFalse(failed.get())
    }

    private fun freeAdjacentPort(): Int {
        for (candidate in 20_000..50_000) {
            val first = runCatching { ServerSocket(candidate).use { true } }.getOrDefault(false)
            val second = runCatching { ServerSocket(candidate + 1).use { true } }.getOrDefault(false)
            if (first && second) return candidate
        }
        error("No adjacent test ports")
    }
}

private fun InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = read(target, offset, target.size - offset)
        if (count < 0) throw EOFException("Unexpected end of test stream")
        offset += count
    }
}
