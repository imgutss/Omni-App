package com.v2ray.ang.core

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Real DNSTT + SSH composition used by SlipNet's dnstt_ssh profile type.
 *
 * The DNSTT process owns a raw loopback TCP listener on core.tunnelPort. This
 * class completes an authenticated SSH session through that listener, then
 * serves SOCKS5 CONNECT on core.port by opening SSH direct-tcpip channels.
 * It never performs a SOCKS handshake with the raw DNSTT endpoint and never
 * dials the configured SSH host directly from Android.
 */
object DnsttSshTunnel {
    private const val CONNECT_TIMEOUT_MS = 60_000
    private const val CHANNEL_TIMEOUT_MS = 30_000
    private const val HANDSHAKE_TIMEOUT_MS = 30_000
    private const val START_ATTEMPTS = 3
    private const val RAW_READY_ATTEMPTS = 20
    private const val MAX_CHANNELS = 32
    private const val BUFFER_SIZE = 65_536
    private const val AUTO_CIPHERS =
        "aes128-gcm@openssh.com,chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes256-ctr"
    private const val KEX_ORDER =
        "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256"

    init {
        runCatching {
            val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
            if (java.security.Security.getProvider(provider.name) == null) {
                java.security.Security.addProvider(provider)
            }
        }.onFailure { LogUtil.w(AppConfig.TAG, "DNSTT+SSH: BouncyCastle provider unavailable", it) }
        JSch.setConfig("CheckKexes", "")
        JSch.setConfig("kex", KEX_ORDER)
    }

    @Volatile
    private var active: Active? = null

    @Synchronized
    fun start(core: DnsttCore, onFailure: (Exception) -> Unit) {
        val ssh = requireNotNull(core.ssh) { "SSH configuration required" }
        stop()
        val next = Active(core, ssh, onFailure)
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

    fun isReady(key: String): Boolean {
        val current = active ?: return false
        if (current.core.key != key || !current.ready.get()) return false
        val session = current.session ?: return false
        val server = current.server ?: return false
        return session.isConnected && !server.isClosed
    }

    private class Active(
        val core: DnsttCore,
        val ssh: DnsttSshConfig,
        val onFailure: (Exception) -> Unit,
    ) {
        val ready = AtomicBoolean(false)
        private val running = AtomicBoolean(true)
        private val closed = AtomicBoolean(false)
        private val channels = Semaphore(MAX_CHANNELS)
        private val clients = CopyOnWriteArrayList<Socket>()

        @Volatile var session: Session? = null
        @Volatile var server: ServerSocket? = null
        @Volatile private var executor: java.util.concurrent.ExecutorService? = null
        @Volatile private var starter: Thread? = null
        @Volatile private var acceptor: Thread? = null
        @Volatile private var monitor: Thread? = null

        fun start() {
            starter = thread(name = "DnsttSshStart", isDaemon = true) { run() }
        }

        private fun run() {
            var lastFailure: Exception? = null
            if (!awaitRawListener()) {
                fail(IOException("Raw DNSTT listener did not become ready"))
                return
            }
            repeat(START_ATTEMPTS) { attempt ->
                if (!running.get()) return
                try {
                    val next = createSession()
                    session = next
                    next.connect(CONNECT_TIMEOUT_MS)
                    if (!running.get()) return
                    if (!next.isConnected) throw IOException("SSH session did not connect")

                    val listener = ServerSocket()
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress(AppConfig.LOOPBACK, core.port))
                    server = listener
                    executor = Executors.newCachedThreadPool { runnable ->
                        Thread(runnable, "DnsttSshWorker").apply { isDaemon = true }
                    }
                    ready.set(true)
                    acceptor = thread(name = "DnsttSshAccept", isDaemon = true) { acceptLoop(listener) }
                    monitor = thread(name = "DnsttSshMonitor", isDaemon = true) { monitorLoop(next) }
                    runCatching {
                        LogUtil.i(AppConfig.TAG, "DNSTT+SSH: authenticated session ready; SOCKS5 on ${AppConfig.LOOPBACK}:${core.port}")
                    }
                    return
                } catch (error: Exception) {
                    lastFailure = error
                    ready.set(false)
                    closeAttempt()
                    if (!running.get()) return
                    runCatching {
                        LogUtil.w(AppConfig.TAG, "DNSTT+SSH: start attempt ${attempt + 1}/$START_ATTEMPTS failed", error)
                    }
                    if (attempt < START_ATTEMPTS - 1) {
                        try {
                            Thread.sleep((attempt + 1) * 1_000L)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                }
            }
            fail(lastFailure ?: IOException("SSH start failed"))
        }

        private fun awaitRawListener(): Boolean {
            repeat(RAW_READY_ATTEMPTS) { attempt ->
                if (!running.get()) return false
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(AppConfig.LOOPBACK, core.tunnelPort), 200)
                        return true
                    }
                } catch (_: Exception) {
                    if (attempt + 1 < RAW_READY_ATTEMPTS) {
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            return false
                        }
                    }
                }
            }
            return false
        }

        private fun createSession(): Session {
            val jsch = JSch()
            if (ssh.authType == DnsttSshConfig.AUTH_KEY) {
                jsch.addIdentity(
                    "omni-dnstt-ssh",
                    ssh.privateKey.toByteArray(Charsets.UTF_8),
                    null,
                    ssh.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
                )
            }
            // This is deliberately the raw DNSTT loopback port. ssh.host and
            // ssh.port describe the remote forward destination, not a direct dial.
            val next = jsch.getSession(ssh.username, AppConfig.LOOPBACK, core.tunnelPort)
            if (ssh.authType == DnsttSshConfig.AUTH_KEY) {
                next.setConfig("PreferredAuthentications", "publickey")
            } else {
                next.setPassword(ssh.password)
            }
            next.setConfig("StrictHostKeyChecking", "no")
            next.setConfig("cipher.s2c", AUTO_CIPHERS)
            next.setConfig("cipher.c2s", AUTO_CIPHERS)
            next.setConfig("compression.s2c", "none")
            next.setConfig("compression.c2s", "none")
            next.setServerAliveInterval(15_000)
            next.setServerAliveCountMax(3)
            return next
        }

        private fun acceptLoop(listener: ServerSocket) {
            while (running.get()) {
                try {
                    val client = listener.accept()
                    clients += client
                    val pool = executor ?: break
                    try {
                        pool.execute { handle(client) }
                    } catch (_: Exception) {
                        clients.remove(client)
                        runCatching { client.close() }
                    }
                } catch (error: Exception) {
                    if (running.get() && ready.get()) fail(error)
                    return
                }
            }
        }

        private fun monitorLoop(current: Session) {
            try {
                while (running.get()) {
                    Thread.sleep(5_000)
                    if (!current.isConnected) {
                        fail(IOException("SSH session closed"))
                        return
                    }
                }
            } catch (_: InterruptedException) {
                // Normal teardown.
            } catch (error: Exception) {
                fail(error)
            }
        }

        private fun handle(client: Socket) {
            var channel: ChannelDirectTCPIP? = null
            var acquired = false
            var replied = false
            try {
                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val greeting = ByteArray(2)
                input.readFully(greeting)
                if (greeting[0].toInt() != 0x05) return
                val methods = ByteArray(greeting[1].toInt() and 0xFF)
                input.readFully(methods)
                if (methods.none { it.toInt() == 0 }) {
                    output.write(byteArrayOf(0x05, 0xFF.toByte()))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                val header = ByteArray(4)
                input.readFully(header)
                if (header[0].toInt() != 0x05 || header[1].toInt() != 0x01) {
                    writeReply(output, 0x07)
                    return
                }
                val host = when (header[3].toInt()) {
                    0x01 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xFF).toString() }
                    0x03 -> {
                        val length = input.read()
                        if (length <= 0) throw IOException("Invalid SOCKS domain length")
                        String(ByteArray(length).also(input::readFully), Charsets.UTF_8)
                    }
                    0x04 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                    else -> {
                        writeReply(output, 0x08)
                        return
                    }
                }
                val portBytes = ByteArray(2)
                input.readFully(portBytes)
                val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                if (port == 0) {
                    writeReply(output, 0x05)
                    return
                }

                val current = session
                if (current == null || !current.isConnected) {
                    writeReply(output, 0x01)
                    return
                }
                acquired = channels.tryAcquire(CHANNEL_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                if (!acquired) {
                    writeReply(output, 0x05)
                    return
                }
                channel = (current.openChannel("direct-tcpip") as ChannelDirectTCPIP).also {
                    it.setHost(host)
                    it.setPort(port)
                    it.connect(CHANNEL_TIMEOUT_MS)
                }

                writeReply(output, 0x00)
                replied = true
                client.soTimeout = 0
                relay(client, input, output, channel)
            } catch (_: Exception) {
                if (!replied) runCatching { writeReply(client.getOutputStream(), 0x05) }
            } finally {
                runCatching { channel?.disconnect() }
                if (acquired) channels.release()
                clients.remove(client)
                runCatching { client.close() }
            }
        }

        private fun relay(client: Socket, clientInput: InputStream, clientOutput: OutputStream, channel: ChannelDirectTCPIP) {
            val channelInput = channel.inputStream
            val channelOutput = channel.outputStream
            val upload = executor?.submit {
                try {
                    copy(clientInput, channelOutput)
                } catch (_: Exception) {
                    // The other direction and teardown close the channel.
                } finally {
                    runCatching { channelOutput.close() }
                }
            }
            try {
                copy(channelInput, clientOutput)
            } finally {
                runCatching { channel.disconnect() }
                upload?.cancel(true)
                runCatching { client.close() }
            }
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(BUFFER_SIZE)
            while (!Thread.currentThread().isInterrupted) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (input.available() == 0) output.flush()
            }
            output.flush()
        }

        private fun writeReply(output: OutputStream, code: Int) {
            output.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
        }

        private fun closeAttempt() {
            runCatching { server?.close() }
            server = null
            runCatching { session?.disconnect() }
            session = null
            executor?.shutdownNow()
            executor = null
        }

        private fun fail(error: Exception) {
            if (!running.compareAndSet(true, false)) return
            ready.set(false)
            close()
            if (active === this) onFailure(error)
        }

        fun close() {
            running.set(false)
            ready.set(false)
            if (!closed.compareAndSet(false, true)) return
            runCatching { server?.close() }
            clients.forEach { runCatching { it.close() } }
            clients.clear()
            runCatching { session?.disconnect() }
            acceptor?.interrupt()
            monitor?.interrupt()
            starter?.interrupt()
            executor?.shutdownNow()
        }
    }
}

private fun InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = read(target, offset, target.size - offset)
        if (count < 0) throw EOFException("Unexpected end of SOCKS stream")
        offset += count
    }
}
