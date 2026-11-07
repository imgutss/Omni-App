package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * An opaque TCP relay, not a TLS terminator. Official NaiveProxy does Chromium TLS, HTTP/2,
 * authentication and padding end-to-end. Only this relay opens WAN sockets, protecting every
 * descriptor before connect so Naive cannot route its transport recursively into the VPN.
 */
internal class NaiveTcpBridge(
    private val context: Context,
    private val remoteHost: String,
    private val remotePort: Int,
    private val protect: ((Int) -> Boolean)?,
    private val onError: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(16)
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "NaiveRelay").apply { isDaemon = true }
    }
    private val server = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    val port: Int get() = server.localPort
    private val acceptThread = thread(name = "NaiveAccept", isDaemon = true) {
        while (!closed.get()) {
            val client = try { server.accept() } catch (_: Exception) { break }
            if (!slots.tryAcquire()) { client.close(); continue }
            sockets.add(client)
            try {
                workers.execute {
                    var remote: Socket? = null
                    var downstreamStarted = false
                    try {
                        remote = connect()
                        val outgoing = remote
                        workers.execute {
                            try {
                                outgoing.getInputStream().copyTo(client.getOutputStream())
                            } catch (_: Exception) {
                            } finally {
                                closeSocket(client)
                                closeSocket(outgoing)
                                slots.release()
                            }
                        }
                        downstreamStarted = true
                        try { client.getInputStream().copyTo(outgoing.getOutputStream()) }
                        finally { runCatching { outgoing.shutdownOutput() } }
                    } catch (_: Exception) {
                        if (!closed.get()) onError()
                        closeSocket(client)
                        remote?.let(::closeSocket)
                    } finally {
                        if (!downstreamStarted) slots.release()
                    }
                }
            } catch (_: Exception) {
                slots.release()
                closeSocket(client)
            }
        }
    }

    private fun connect(): Socket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull { candidate ->
            cm.getNetworkCapabilities(candidate)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        // Lookup on the physical network; never ask a VPN-routed system DNS resolver recursively.
        val addresses = network?.getAllByName(remoteHost)
            ?: if (protect == null) InetAddress.getAllByName(remoteHost)
            else throw IllegalStateException("No physical network for NaiveProxy")
        for (address in addresses) {
            val socket = Socket()
            sockets.add(socket)
            try {
                check(!closed.get())
                NaiveSocketConnector.connect(socket, address, remotePort) { opened ->
                    if (protect == null) true
                    else ParcelFileDescriptor.fromSocket(opened).use { protect.invoke(it.fd) }
                }
                return socket
            } catch (_: Exception) {
                closeSocket(socket)
            }
        }
        throw IllegalStateException("NaiveProxy server unreachable")
    }

    private fun closeSocket(socket: Socket) {
        sockets.remove(socket)
        runCatching { socket.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.toList().forEach(::closeSocket)
        workers.shutdownNow()
        if (Thread.currentThread() !== acceptThread) acceptThread.join(1_000)
    }
}

/** Testable ordering invariant: create/bind -> protect succeeds -> connect, never fail-open. */
internal object NaiveSocketConnector {
    fun connect(socket: Socket, address: InetAddress, port: Int, protect: (Socket) -> Boolean) {
        try {
            socket.bind(InetSocketAddress(0))
            check(protect(socket)) { "NaiveProxy socket protection rejected" }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), 10_000)
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }
}
