package com.v2ray.ang.omni.dns

import com.v2ray.ang.omni.lab.OmniLatencyStats
import com.v2ray.ang.omni.lab.OmniTunnelConnectivity
import com.v2ray.ang.omni.lab.OmniTunnelPing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Measures HTTP through a DNS engine's own loopback SOCKS listener. Unlike the
 * generic transfer lab, this does not start Xray or reuse a different tunnel:
 * a successful response is evidence for this exact native process and resolver.
 */
class OmniDirectSocksLab(
    private val clientFactory: (port: Int, timeoutMs: Long) -> OkHttpClient = ::directSocksClient,
) {
    suspend fun connectivity(port: Int, url: String, timeoutMs: Long = 30_000L): OmniTunnelConnectivity =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                clientFactory(port, timeoutMs).newCall(request(url)).await().use { response ->
                    OmniTunnelConnectivity(
                        success = response.isSuccessful,
                        ttfbMs = elapsedMs(started),
                        httpStatus = response.code,
                        error = if (response.isSuccessful) null else "HTTP ${response.code}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniTunnelConnectivity(false, elapsedMs(started), error = error.message ?: error.javaClass.simpleName)
            }
        }

    suspend fun ping(
        port: Int,
        url: String,
        samples: Int = 5,
        warmup: Int = 1,
        timeoutMs: Long = 30_000L,
    ): OmniTunnelPing = withContext(Dispatchers.IO) {
        require(samples in 1..20 && warmup in 0..5 && timeoutMs in 1_000L..180_000L)
        val measured = ArrayList<Long?>(samples)
        var lastStatus: Int? = null
        var lastError: String? = null
        val client = clientFactory(port, timeoutMs)
        repeat(warmup + samples) { iteration ->
            val started = System.nanoTime()
            var value: Long? = null
            try {
                client.newCall(request(url)).await().use { response ->
                    lastStatus = response.code
                    if (response.isSuccessful) {
                        value = elapsedMs(started)
                    } else {
                        lastError = "HTTP ${response.code}"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            }
            if (iteration >= warmup) measured += value
        }
        OmniTunnelPing(
            stats = OmniLatencyStats.from(measured, samples),
            lastHttpStatus = lastStatus,
            error = if (measured.any { it != null }) null else lastError,
        )
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Connection", "close")
        .header("Accept-Encoding", "identity")
        .build()

    private fun elapsedMs(started: Long): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
}

private fun directSocksClient(port: Int, timeoutMs: Long): OkHttpClient {
    require(port in 1..65535 && timeoutMs in 1_000L..180_000L)
    return OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
