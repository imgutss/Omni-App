package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Public no-auth SOCKS5 in front of Slipstream's raw loopback TCP listener.
 * Remote negotiation always happens through the live engine, so a successful
 * greeting is evidence of a working QUIC path and remote SOCKS service.
 */
object SlipstreamSocksBridge {
    private const val HANDSHAKE_TIMEOUT_MS = 60_000
    private const val MAX_CLIENTS = 32

    @Volatile private var active: Active? = null

    @Synchronized
    fun start(core: DnsttCore, onFailure: (Exception) -> Unit) {
        stop()
        val next = Active(core, onFailure)
        active = next
        next.start()
    }

    @Synchronized
    fun stop(key: String? = null) {
        val current = active ?: return
        if (key != null && current.core.key != key) return
        active = null
        current.close()
    }

    private class Active(
        val core: DnsttCore,
        private val onFailure: (Exception) -> Unit,
    ) {
        private val running = AtomicBoolean(true)
        private val closed = AtomicBoolean(false)
        private val clients = CopyOnWriteArrayList<Socket>()
        private val slots = Semaphore(MAX_CLIENTS)
        @Volatile private var server: ServerSocket? = null
        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "SlipstreamSocksWorker").apply { isDaemon = true }
        }

        fun start() {
            thread(name = "SlipstreamSocksAccept", isDaemon = true) {
                try {
                    val listener = ServerSocket()
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress(AppConfig.LOOPBACK, core.port))
                    server = listener
                    while (running.get()) {
                        val client = listener.accept()
                        clients += client
                        if (!slots.tryAcquire()) {
                            clients.remove(client)
                            runCatching { client.close() }
                            continue
                        }
                        executor.execute {
                            try {
                                handle(client)
                            } catch (_: Exception) {
                                // A failed stream does not kill other clients or the engine.
                            } finally {
                                slots.release()
                                clients.remove(client)
                                runCatching { client.close() }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (running.get()) fail(error)
                }
            }
        }

        private fun handle(client: Socket) {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val methods = readGreeting(input)
            if (0.toByte() !in methods) {
                output.write(byteArrayOf(5, 0xff.toByte()))
                output.flush()
                return
            }

            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(AppConfig.LOOPBACK, core.enginePort), HANDSHAKE_TIMEOUT_MS)
                raw.soTimeout = HANDSHAKE_TIMEOUT_MS
                raw.tcpNoDelay = true
                val rawInput = raw.getInputStream()
                val rawOutput = raw.getOutputStream()
                val hasCredentials = core.socksUsername.isNotEmpty()
                rawOutput.write(if (hasCredentials) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
                rawOutput.flush()
                val choice = ByteArray(2)
                rawInput.readFully(choice)
                if (choice[0].toInt() != 5) throw IOException("Invalid remote SOCKS version")
                when (choice[1].toInt()) {
                    0 -> Unit
                    2 -> {
                        if (!hasCredentials) throw IOException("Remote SOCKS requires credentials")
                        val user = core.socksUsername.toByteArray(Charsets.UTF_8)
                        val pass = core.socksPassword.toByteArray(Charsets.UTF_8)
                        if (user.size > 255 || pass.size > 255) throw IOException("SOCKS credentials are too long")
                        rawOutput.write(byteArrayOf(1, user.size.toByte()))
                        rawOutput.write(user)
                        rawOutput.write(byteArrayOf(pass.size.toByte()))
                        rawOutput.write(pass)
                        rawOutput.flush()
                        val auth = ByteArray(2)
                        rawInput.readFully(auth)
                        if (auth[0].toInt() != 1 || auth[1].toInt() != 0) {
                            throw IOException("Remote SOCKS authentication failed")
                        }
                    }
                    else -> throw IOException("Remote SOCKS rejected authentication")
                }

                // Do not report local success before the remote tunnel answered.
                output.write(byteArrayOf(5, 0))
                output.flush()
                val request = readControlMessage(input)
                if (request[1].toInt() != 1) {
                    output.write(reply(7))
                    output.flush()
                    return
                }
                rawOutput.write(request)
                rawOutput.flush()
                val response = readControlMessage(rawInput)
                output.write(response)
                output.flush()
                if (response[1].toInt() != 0) return

                client.soTimeout = 0
                raw.soTimeout = 0
                relay(client, raw)
            } finally {
                runCatching { raw.close() }
            }
        }

        private fun relay(client: Socket, raw: Socket) {
            val upload = executor.submit {
                runCatching { copy(client.getInputStream(), raw.getOutputStream()) }
                runCatching { raw.shutdownOutput() }
            }
            try {
                copy(raw.getInputStream(), client.getOutputStream())
            } finally {
                runCatching { client.close() }
                runCatching { raw.close() }
                upload.cancel(true)
            }
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(65_536)
            while (!Thread.currentThread().isInterrupted) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        }

        private fun fail(error: Exception) {
            if (!running.compareAndSet(true, false)) return
            close()
            if (active === this) onFailure(error)
        }

        fun close() {
            running.set(false)
            if (!closed.compareAndSet(false, true)) return
            runCatching { server?.close() }
            clients.forEach { runCatching { it.close() } }
            clients.clear()
            executor.shutdownNow()
        }
    }

    private fun readGreeting(input: InputStream): ByteArray {
        val header = ByteArray(2)
        input.readFully(header)
        if (header[0].toInt() != 5 || header[1].toInt() == 0) throw IOException("Invalid SOCKS greeting")
        return ByteArray(header[1].toInt() and 0xff).also(input::readFully)
    }

    /** Reads a complete CONNECT request/reply, including its variable address. */
    private fun readControlMessage(input: InputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        if (header[0].toInt() != 5) throw IOException("Invalid SOCKS control message")
        val tail = when (header[3].toInt()) {
            1 -> ByteArray(6).also(input::readFully)
            4 -> ByteArray(18).also(input::readFully)
            3 -> {
                val length = input.read()
                if (length <= 0) throw IOException("Invalid SOCKS domain")
                byteArrayOf(length.toByte()) + ByteArray(length + 2).also(input::readFully)
            }
            else -> throw IOException("Invalid SOCKS address type")
        }
        return header + tail
    }

    private fun reply(code: Int): ByteArray =
        byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)
}

private fun InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = read(target, offset, target.size - offset)
        if (count < 0) throw EOFException("Unexpected end of SOCKS stream")
        offset += count
    }
}
