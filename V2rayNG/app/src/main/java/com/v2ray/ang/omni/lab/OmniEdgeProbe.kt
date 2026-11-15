package com.v2ray.ang.omni.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val DEFAULT_PORT = 443

enum class OmniProbeMode { TCP, TLS, HTTP, WEBSOCKET }

data class OmniProbeOptions(
    val mode: OmniProbeMode = OmniProbeMode.TCP,
    val samples: Int = 3,
    val warmup: Int = 1,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 5_000,
    val sni: String? = null,
    val httpPath: String = "/",
    val hostHeader: String? = null,
    /** Null keeps port/SNI inference for manual scans; a template sets its actual plain/TLS transport explicitly. */
    val useTls: Boolean? = null,
) {
    init {
        require(samples in 1..20) { "Samples must be between 1 and 20" }
        require(warmup in 0..5) { "Warm-up must be between 0 and 5" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "Timeouts must be positive" }
        require(httpPath.length <= 2_048 && httpPath.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid HTTP path" }
        require((sni?.length ?: 0) <= 253 && sni?.none { it == '\r' || it == '\n' || it == '\u0000' } != false) { "Invalid SNI" }
        require((hostHeader?.length ?: 0) <= 2_048 && hostHeader?.none { it == '\r' || it == '\n' || it == '\u0000' } != false) { "Invalid Host header" }
    }
}

data class OmniEdgeProbeResult(
    val target: OmniScanTarget,
    val mode: OmniProbeMode,
    val stats: OmniLatencyStats,
    val evidence: String? = null,
    val httpStatus: Int? = null,
    val error: String? = null,
) {
    val success: Boolean get() = stats.succeeded > 0
    val verified: Boolean
        get() = stats.attempted > 0 && stats.succeeded == stats.attempted &&
            (mode != OmniProbeMode.WEBSOCKET || httpStatus == 101)
}

fun interface OmniEdgeProbe {
    suspend fun probe(target: OmniScanTarget, options: OmniProbeOptions): OmniEdgeProbeResult
}

class OmniDefaultEdgeProbe : OmniEdgeProbe {
    override suspend fun probe(target: OmniScanTarget, options: OmniProbeOptions): OmniEdgeProbeResult = withContext(Dispatchers.IO) {
        val samples = ArrayList<Long?>(options.samples)
        var lastError: String? = null
        var evidence: String? = null
        var status: Int? = null
        repeat(options.warmup + options.samples) { iteration ->
            val started = System.nanoTime()
            try {
                val observation = observe(target, options)
                val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                if (iteration >= options.warmup) {
                    samples += elapsed
                    evidence = observation.evidence
                    status = observation.status
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (iteration >= options.warmup) samples += null
                lastError = error.message ?: error.javaClass.simpleName
            }
        }
        val stats = OmniLatencyStats.from(samples, options.samples)
        OmniEdgeProbeResult(
            target = target,
            mode = options.mode,
            stats = stats,
            evidence = evidence,
            httpStatus = status,
            error = if (stats.succeeded == options.samples) null else lastError ?: "No response",
        )
    }

    private data class Observation(val evidence: String, val status: Int? = null)

    private fun observe(target: OmniScanTarget, options: OmniProbeOptions): Observation {
        return when (options.mode) {
            OmniProbeMode.TCP -> Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), options.connectTimeoutMs)
                Observation("TCP connect")
            }
            OmniProbeMode.TLS -> tlsSocket(target, options).use { socket ->
                socket.startHandshake()
                Observation("TLS ${socket.session.protocol}")
            }
            OmniProbeMode.HTTP, OmniProbeMode.WEBSOCKET -> httpObservation(target, options)
        }
    }

    private fun httpObservation(target: OmniScanTarget, options: OmniProbeOptions): Observation {
        return Socket().use { raw ->
            raw.connect(InetSocketAddress(target.host, target.port), options.connectTimeoutMs)
            raw.soTimeout = options.readTimeoutMs
            val isTls = options.useTls ?: (options.sni != null || target.port == DEFAULT_PORT)
            val ioSocket = if (isTls) {
                val tls = tlsSocket(raw, target, options)
                tls.startHandshake()
                tls
            } else raw
            ioSocket.use { stream ->
                val path = options.httpPath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
                val host = options.hostHeader?.takeIf { it.isNotBlank() } ?: target.host
                val key = if (options.mode == OmniProbeMode.WEBSOCKET) {
                    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("${target.endpointId}:${System.nanoTime()}".toByteArray()).copyOf(16))
                } else null
                val request = buildString {
                    append("GET ").append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host).append("\r\n")
                    if (key == null) {
                        append("Connection: close\r\n")
                    } else {
                        append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ").append(key).append("\r\n")
                    }
                    append("\r\n")
                }
                stream.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                stream.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(stream.getInputStream(), StandardCharsets.ISO_8859_1))
                val statusLine = reader.readLine() ?: throw java.io.IOException("Empty HTTP response")
                val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("Invalid HTTP response")
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.associate { line ->
                    val colon = line.indexOf(':')
                    if (colon < 0) "" to "" else line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
                }
                if (options.mode == OmniProbeMode.WEBSOCKET) {
                    require(status == 101) { "WebSocket upgrade returned HTTP $status" }
                    val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()))
                    require(headers["sec-websocket-accept"] == expected) { "Invalid WebSocket accept" }
                }
                Observation("HTTP $status", status)
            }
        }
    }

    private fun tlsSocket(target: OmniScanTarget, options: OmniProbeOptions): SSLSocket {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(target.host, target.port), options.connectTimeoutMs)
            tlsSocket(socket, target, options)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun tlsSocket(raw: Socket, target: OmniScanTarget, options: OmniProbeOptions): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val host = options.sni?.takeIf { it.isNotBlank() } ?: target.host
        return (factory.createSocket(raw, host, target.port, true) as SSLSocket).apply {
            soTimeout = options.readTimeoutMs
            val parameters: SSLParameters = sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            sslParameters = parameters
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
