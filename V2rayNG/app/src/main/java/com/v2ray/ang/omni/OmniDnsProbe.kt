package com.v2ray.ang.omni

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val MAX_DNS_RESPONSE_BYTES = 256 * 1_024

/** Real resolver evidence. DoH is queried directly; no panel or administrator credential is involved. */
object OmniDnsProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun querySystem(host: String): OmniDnsResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val addresses = InetAddress.getAllByName(host)
            val answers = addresses.mapNotNull { address ->
                address.hostAddress?.let { ip ->
                    OmniDnsAnswer(host, if (address.address.size == 4) 1 else 28, null, ip)
                }
            }
            OmniDnsResult(OmniDnsProvider.SYSTEM, OmniDnsStatus.PASS, elapsed(started), answers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniDnsResult(OmniDnsProvider.SYSTEM, OmniDnsStatus.FAIL, elapsed(started), error = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}")
        }
    }

    suspend fun queryDoh(provider: OmniDnsProvider, endpoint: String, host: String, type: OmniDnsRecordType): OmniDnsResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val base = endpoint.trim().toHttpUrlOrNull()
        if (base == null || base.scheme != "https" || base.username.isNotEmpty() || base.password.isNotEmpty() || base.querySize > 0 || base.fragment != null) {
            return@withContext OmniDnsResult(provider, OmniDnsStatus.FAIL, elapsed(started), error = "A valid HTTPS DoH endpoint without credentials or query is required")
        }
        val url = base.newBuilder().addQueryParameter("name", host).addQueryParameter("type", type.name).build()
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").get().build()).execute().use { response ->
                val body = response.body.source().readByteArray(MAX_DNS_RESPONSE_BYTES + 1L)
                if (body.size > MAX_DNS_RESPONSE_BYTES) throw IOException("DoH response exceeds 256 KiB")
                val elapsed = elapsed(started)
                if (!response.isSuccessful) {
                    return@use OmniDnsResult(provider, OmniDnsStatus.FAIL, elapsed, httpStatus = response.code, error = "HTTP ${response.code}")
                }
                val parsed = parseOmniDnsJson(body.toString(Charsets.UTF_8))
                OmniDnsResult(
                    provider = provider,
                    status = when (parsed.responseStatus) {
                        0 -> OmniDnsStatus.PASS
                        3 -> OmniDnsStatus.NXDOMAIN
                        else -> OmniDnsStatus.FAIL
                    },
                    elapsedMs = elapsed,
                    answers = parsed.answers,
                    httpStatus = response.code,
                    responseStatus = parsed.responseStatus,
                    error = if (parsed.responseStatus in listOf(0, 3)) null else "DNS status ${parsed.responseStatus}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniDnsResult(provider, OmniDnsStatus.FAIL, elapsed(started), error = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}")
        }
    }

    private fun elapsed(started: Long): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
}

data class OmniDnsParsedResponse(val responseStatus: Int, val answers: List<OmniDnsAnswer>)

fun parseOmniDnsJson(text: String): OmniDnsParsedResponse {
    val root = JsonParser.parseString(text).asJsonObject
    val status = root.get("Status")?.asInt ?: throw IOException("DoH response has no Status")
    val answers = root.getAsJsonArray("Answer")?.mapNotNull { element ->
        val item = element.asJsonObject
        val data = item.get("data")?.asString ?: return@mapNotNull null
        OmniDnsAnswer(
            name = item.get("name")?.asString.orEmpty(),
            type = item.get("type")?.asInt ?: 0,
            ttl = item.get("TTL")?.asLong,
            data = data,
        )
    }.orEmpty()
    return OmniDnsParsedResponse(status, answers)
}
