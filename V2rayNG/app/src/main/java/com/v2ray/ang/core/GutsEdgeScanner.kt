package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds the Google edge address and the Google name this network still lets through.
 *
 * A restricted network answers a few of Google's published frontend addresses and drops the rest,
 * and filters the name in the clear in the ClientHello, so both are properties of the network at
 * that moment rather than of the relay. The scanner therefore asks the client itself — the same
 * fronting code the tunnel uses — and prints what it found, so the numbers here can never disagree
 * with what a connection would do.
 *
 * Nothing here touches Apps Script: every probe is a TCP connection, a TLS handshake, and one
 * fronted request, so a scan costs no quota.
 */
object GutsEdgeScanner {
    /** A scan may take a while on a network that drops most addresses; the client is bounded too. */
    internal const val BUDGET_MS = 40_000L

    /** One address the network answered, as the client reports it. */
    data class Edge(val ip: String, val rttMs: Long, val fronted: Boolean, val error: String?)

    /** One Google name tried on the best address. */
    data class Name(val sni: String, val rttMs: Long, val fronted: Boolean, val error: String?)

    /** What one round of scanning found; empty [bestEdge] or [bestSni] means "nothing answered". */
    data class Result(
        val edges: List<Edge> = emptyList(),
        val names: List<Name> = emptyList(),
        val bestEdge: String? = null,
        val bestSni: String? = null,
    ) {
        val answered: Boolean get() = bestEdge != null || bestSni != null
    }

    /**
     * Scans the addresses, then tries the Google names on the best one.
     *
     * A failure to scan at all is reported as an empty result with a log line, never as a profile
     * quietly rewritten to something that did not answer.
     */
    suspend fun scan(
        context: Context,
        googleIp: String = "",
        sni: String = GutsCore.DEFAULT_SNI,
        frontHost: String = GutsCore.DEFAULT_FRONT_HOST,
    ): Result = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.canExecute()) {
            LogUtil.w(AppConfig.TAG, "GutsEdge: no GUTS client to scan with")
            return@withContext Result()
        }
        runCatching {
            val edges = parseEdges(run(context, binary, googleIp, sni, frontHost, "-scan-json"))
            val bestEdge = edges.firstOrNull { it.fronted }?.ip
                ?: return@runCatching Result(edges = edges)
            val names = parseNames(run(context, binary, bestEdge, sni, frontHost, "-scan-snis-json"))
            Result(
                edges = edges,
                names = names,
                bestEdge = bestEdge,
                bestSni = names.firstOrNull { it.fronted }?.sni,
            )
        }.getOrElse { error ->
            LogUtil.w(AppConfig.TAG, "GutsEdge: the scan failed: ${error.message}")
            Result()
        }
    }

    /**
     * Runs one scan mode of the client and returns its whole answer.
     *
     * A scan needs neither a door nor the key: it never calls Apps Script, only the edge in front of
     * it. That is also why a half-typed profile can be scanned without being valid first.
     */
    private fun run(
        context: Context,
        binary: File,
        googleIp: String,
        sni: String,
        frontHost: String,
        mode: String,
    ): String {
        val command = buildList {
            add(binary.absolutePath)
            add(mode)
            add("-timeout=3s")
            add("-google-ip=${googleIp.ifBlank { GutsCore.AUTO_EDGE }}")
            add("-sni=${sni.ifBlank { GutsCore.DEFAULT_SNI }}")
            add("-front-host=${frontHost.ifBlank { GutsCore.DEFAULT_FRONT_HOST }}")
            // A scan runs while no tunnel of ours is up, so there is no VPN to escape.
            add("-fd-control-unix-socket=")
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
        val answer = StringBuilder()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (answer.length < MAX_ANSWER) answer.appendLine(line)
                }
            }
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "GutsEdge: could not read the scan: ${error.message}")
        }
        if (!process.waitFor(BUDGET_MS, TimeUnit.MILLISECONDS)) {
            process.destroy()
            LogUtil.w(AppConfig.TAG, "GutsEdge: the scan did not finish in time")
            return ""
        }
        return answer.toString()
    }

    /**
     * Reads `gutsclient -scan-json`: an array of `{ip, rtt_ms, fronted, status, e}`.
     *
     * The client also prints log lines on the same stream, so everything before the JSON array is
     * skipped rather than failing the parse: a scan that worked is not made worthless by a log line.
     */
    internal fun parseEdges(answer: String): List<Edge> = runCatching {
        val array = JSONArray(jsonPart(answer))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val ip = item.optString("ip")
            if (ip.isBlank()) return@mapNotNull null
            Edge(
                ip = ip,
                rttMs = item.optLong("rtt_ms", 0L),
                fronted = item.optBoolean("fronted", false),
                error = item.optString("e").ifBlank { null },
            )
        }
    }.getOrElse { emptyList() }

    /** Reads `gutsclient -scan-snis-json`: the same shape, with `sni` instead of `ip`. */
    internal fun parseNames(answer: String): List<Name> = runCatching {
        val array = JSONArray(jsonPart(answer))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val sni = item.optString("sni")
            if (sni.isBlank()) return@mapNotNull null
            Name(
                sni = sni,
                rttMs = item.optLong("rtt_ms", 0L),
                fronted = item.optBoolean("fronted", false),
                error = item.optString("e").ifBlank { null },
            )
        }
    }.getOrElse { emptyList() }

    /** The JSON part of an answer, ignoring the log lines the client prints around it. */
    private fun jsonPart(answer: String): String {
        val start = answer.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) return "[]"
        val end = answer.lastIndexOfFirst { it == ']' || it == '}' }
        if (end <= start) return "[]"
        return answer.substring(start, end + 1)
    }

    private inline fun String.lastIndexOfFirst(predicate: (Char) -> Boolean): Int {
        for (index in indices.reversed()) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private const val BINARY_NAME = "libguts.so"

    /** A scan answer is a short array; anything longer is not one. */
    private const val MAX_ANSWER = 64 * 1024
}
