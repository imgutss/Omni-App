package com.v2ray.ang.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Serves the `protect_path` protocol of the sing-box and shadowquic clients over a FILESYSTEM Unix
 * socket (both connect with `UnixStream::connect(path)` / `connect(SockaddrUnix)`, which the abstract
 * namespace [MasterDnsProtectServer] uses cannot satisfy).
 *
 * The two clients differ only in the dummy byte they send and how they read the reply:
 *  - sing-box sends 0x01 and treats any single reply byte as success;
 *  - shadowquic sends 0x00 and treats 0xFF as failure, anything else as success.
 *
 * Accepting either dummy byte and replying 0x01 on success / 0xFF on failure therefore satisfies
 * both. The peer must have this app's UID; a public ParcelFileDescriptor duplicate exposes the fd
 * number for VpnService.protect without any hidden API.
 */
internal class SidecarProtectServer(
    private val socketFile: File,
    private val protect: (Int) -> Boolean,
    private val expectedUid: Int = Process.myUid(),
    private val onError: (Exception) -> Unit = {},
) : Closeable {
    /** The filesystem path the client config names as protect_path / protect-path. */
    val configPath: String = socketFile.absolutePath

    private val boundSocket = LocalSocket()
    private val server: LocalServerSocket
    private val closed = AtomicBoolean(false)
    private val clientLock = Any()
    private var currentClient: LocalSocket? = null
    private val acceptThread: Thread

    init {
        socketFile.parentFile?.mkdirs()
        // A stale socket file from a previous session would make bind fail with EADDRINUSE.
        runCatching { Os.unlink(socketFile.absolutePath) }
        boundSocket.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        // LocalServerSocket(fd) calls listen() itself; the fd stays owned by boundSocket.
        server = LocalServerSocket(boundSocket.fileDescriptor)
        acceptThread = thread(name = "QuicProtect", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed.get()) onError(e)
                break
            }
            val accepted = synchronized(clientLock) {
                if (closed.get()) false else {
                    currentClient = client
                    true
                }
            }
            if (!accepted) {
                runCatching { client.close() }
                break
            }
            try {
                serve(client)
            } catch (e: Exception) {
                if (!closed.get()) onError(e)
            } finally {
                runCatching { client.close() }
                synchronized(clientLock) { if (currentClient === client) currentClient = null }
            }
        }
    }

    private fun serve(client: LocalSocket) {
        client.soTimeout = READ_TIMEOUT_MS
        val sameOwner = runCatching { client.peerCredentials?.uid == expectedUid }.getOrDefault(false)
        val request = client.inputStream.read()
        val descriptors = runCatching { client.ancillaryFileDescriptors?.toList().orEmpty() }
            .getOrElse { emptyList() }
        var status = FAILURE
        try {
            if (accepts(request, descriptors.size, sameOwner)) {
                status = protectDescriptor(descriptors[0])
            }
            client.outputStream.write(status)
            client.outputStream.flush()
        } finally {
            descriptors.forEach(::closeDescriptor)
        }
    }

    private fun protectDescriptor(descriptor: FileDescriptor): Int = try {
        ParcelFileDescriptor.dup(descriptor).use { duplicate ->
            if (protect(duplicate.fd)) SUCCESS else FAILURE
        }
    } catch (e: Exception) {
        onError(e)
        FAILURE
    }

    private fun closeDescriptor(descriptor: FileDescriptor) {
        try {
            Os.close(descriptor)
        } catch (e: Exception) {
            if (!closed.get()) onError(e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { boundSocket.close() }
        synchronized(clientLock) { runCatching { currentClient?.close() } }
        if (Thread.currentThread() !== acceptThread) acceptThread.join(1_000)
        runCatching { Os.unlink(socketFile.absolutePath) }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 5_000
        private const val SUCCESS = 0x01
        private const val FAILURE = 0xFF

        internal fun accepts(request: Int, descriptorCount: Int, sameOwner: Boolean): Boolean =
            (request == 0x00 || request == 0x01) && descriptorCount == 1 && sameOwner
    }
}
