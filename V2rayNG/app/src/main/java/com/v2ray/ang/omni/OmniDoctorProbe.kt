package com.v2ray.ang.omni

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

/** Low-level evidence for one Doctor stage; the activity supplies localized human summaries. */
data class OmniProbeOutcome(
    val status: OmniDoctorStatus,
    val raw: String,
    val value: String = "",
    val ping: RealPingResult? = null,
)

object OmniDoctorProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun dns(baseUrl: String): OmniProbeOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull() ?: return@withContext OmniProbeOutcome(OmniDoctorStatus.FAIL, "Invalid panel URL")
        val started = System.nanoTime()
        try {
            val addresses = InetAddress.getAllByName(url.host)
            val elapsed = elapsed(started)
            val resolved = addresses.joinToString { it.hostAddress ?: "unknown" }
            OmniProbeOutcome(
                OmniDoctorStatus.PASS,
                "DNS ${url.host} -> $resolved; ${elapsed} ms",
                resolved,
            )
        } catch (error: Exception) {
            failure("DNS", error, started)
        }
    }

    suspend fun tcp(baseUrl: String): OmniProbeOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull() ?: return@withContext OmniProbeOutcome(OmniDoctorStatus.FAIL, "Invalid panel URL")
        val started = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(url.host, url.port), 7000)
                val elapsed = elapsed(started)
                OmniProbeOutcome(OmniDoctorStatus.PASS, "TCP ${url.host}:${url.port} connected; ${elapsed} ms", "$elapsed ms")
            }
        } catch (error: Exception) {
            failure("TCP ${url.host}:${url.port}", error, started)
        }
    }

    suspend fun tls(baseUrl: String): OmniProbeOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull() ?: return@withContext OmniProbeOutcome(OmniDoctorStatus.FAIL, "Invalid panel URL")
        if (url.scheme != "https") {
            return@withContext OmniProbeOutcome(OmniDoctorStatus.SKIPPED, "TLS not applicable to an HTTP panel URL")
        }
        val started = System.nanoTime()
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = 8000
                socket.connect(InetSocketAddress(url.host, url.port), 7000)
                // A bare handshake alone does not establish that this certificate belongs to the panel.
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                val elapsed = elapsed(started)
                OmniProbeOutcome(
                    OmniDoctorStatus.PASS,
                    "TLS certificate+hostname verified ${url.host}:${url.port}; protocol=${socket.session.protocol}; ${elapsed} ms",
                    socket.session.protocol,
                )
            }
        } catch (error: Exception) {
            failure("TLS ${url.host}:${url.port}", error, started)
        }
    }

    suspend fun http(baseUrl: String): OmniProbeOutcome = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            client.newCall(
                Request.Builder().url("$baseUrl/api/version").header("Accept", "application/json").get().build()
            ).execute().use { response ->
                val elapsed = elapsed(started)
                val status = when (response.code) {
                    in 200..299 -> OmniDoctorStatus.PASS
                    401, 403, 404 -> OmniDoctorStatus.WARN
                    else -> OmniDoctorStatus.FAIL
                }
                OmniProbeOutcome(
                    status,
                    "HTTP ${response.code} ${response.message}; endpoint=/api/version; ${elapsed} ms",
                    response.code.toString(),
                )
            }
        } catch (error: Exception) {
            failure("HTTP /api/version", error, started)
        }
    }

    suspend fun exitIp(): OmniProbeOutcome = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val info = SpeedtestManager.getRemoteIPInfo()
            if (info?.ipAddress.isNullOrBlank()) {
                OmniProbeOutcome(OmniDoctorStatus.SKIPPED, "No local-proxy exit response; ${elapsed(started)} ms")
            } else {
                OmniProbeOutcome(
                    OmniDoctorStatus.PASS,
                    "Local proxy IP API response; ip=${info.ipAddress}; country=${info.country ?: "unknown"}; ${elapsed(started)} ms",
                    "${info.ipAddress}|${info.country.orEmpty()}",
                )
            }
        } catch (error: Exception) {
            failure("Exit IP", error, started)
        }
    }

    /** Uses the existing :tasks CoreTestService, rather than starting a second core in the UI process. */
    suspend fun realHttp(context: Context, guid: String): RealPingResult = withTimeout(90_000L) {
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            val requestId = "omni-doctor-${UUID.randomUUID()}"
            var result: RealPingResult? = null
            var finished = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    val safeIntent = intent ?: return
                    if (safeIntent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) != requestId) return
                    when (safeIntent.getIntExtra("key", 0)) {
                        AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                            result = safeIntent.serializable<RealPingResult>("content")
                        }
                        AppConfig.MSG_MEASURE_CONFIG_FINISH -> finish(result)
                        AppConfig.MSG_MEASURE_CONFIG_CANCEL -> finish(null)
                    }
                }

                private fun finish(value: RealPingResult?) {
                    if (finished) return
                    finished = true
                    runCatching { app.unregisterReceiver(this) }
                    continuation.resume(
                        value ?: RealPingResult(guid, -1L, sampleCount = 0, successfulSamples = 0, kind = "HTTP")
                    )
                }
            }
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags(),
            )
            continuation.invokeOnCancellation {
                finished = true
                runCatching { app.unregisterReceiver(receiver) }
            }
            MessageHelper.sendMsg2TestService(
                app,
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    serverGuids = listOf(guid),
                ),
                requestId,
            )
        }
    }

    private fun failure(stage: String, error: Exception, started: Long): OmniProbeOutcome {
        val status = if (error is SocketTimeoutException) OmniDoctorStatus.FAIL else OmniDoctorStatus.FAIL
        return OmniProbeOutcome(
            status,
            "$stage ${error.javaClass.simpleName}: ${error.message ?: "no detail"}; ${elapsed(started)} ms",
        )
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
}
