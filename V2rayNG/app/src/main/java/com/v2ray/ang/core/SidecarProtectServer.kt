package com.v2ray.ang.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * SCM_RIGHTS protection bridge in the abstract namespace, shared by sing-box and the patched
 * ShadowQUIC client. The namespace avoids filesystem path lifetime and label dependencies;
 * device-side counters remain necessary to diagnose whether VpnService.protect actually succeeds.
 */
internal class SidecarProtectServer(
    name: String,
    private val protect: (Int) -> Boolean,
    private val expectedUid: Int = Process.myUid(),
    private val onError: (Exception) -> Unit = {},
) : Closeable {
    /** The `@`-prefixed abstract address the client config names as protect_path / protect-path. */
    val configPath: String = "@$name"

    val requests = java.util.concurrent.atomic.AtomicInteger()
    val failures = java.util.concurrent.atomic.AtomicInteger()

    private val server = LocalServerSocket(name)
    private val closed = AtomicBoolean(false)
    private val clientLock = Any()
    private var currentClient: LocalSocket? = null
    private val acceptThread = thread(name = "QuicProtect", isDaemon = true) { acceptLoop() }

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
        requests.incrementAndGet()
        var status = FAILURE
        try {
            if (accepts(request, descriptors.size, sameOwner)) {
                status = protectDescriptor(descriptors[0])
            }
            if (status == FAILURE) {
                failures.incrementAndGet()
                onError(IllegalStateException("Socket protection rejected: owner=$sameOwner descriptors=${descriptors.size} request=$request"))
            }
            client.outputStream.write(status)
            client.outputStream.flush()
        } finally {
            descriptors.forEach(::closeDescriptor)
        }
    }

    private fun protectDescriptor(descriptor: FileDescriptor): Int = try {
        // Protecting a duplicate marks the same underlying socket. Both the duplicate and the
        // SCM_RIGHTS descriptor received by this process are closed exactly once afterwards.
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
        synchronized(clientLock) { runCatching { currentClient?.close() } }
        // The close above wakes accept/read. A bounded join keeps teardown from returning while a
        // new protect request can still enter, without holding the service indefinitely.
        if (Thread.currentThread() !== acceptThread) acceptThread.join(1_000)
    }

    companion object {
        private const val READ_TIMEOUT_MS = 5_000
        private const val SUCCESS = 0x01
        private const val FAILURE = 0xFF

        internal fun accepts(request: Int, descriptorCount: Int, sameOwner: Boolean): Boolean =
            (request == 0x00 || request == 0x01) && descriptorCount == 1 && sameOwner
    }
}
