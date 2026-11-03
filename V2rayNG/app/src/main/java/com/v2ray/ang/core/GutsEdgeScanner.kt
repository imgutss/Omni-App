package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Finds the Google edge address and the Google name this network still lets through.
 *
 * A restricted network answers a few of Google's published frontend addresses and drops the rest,
 * and filters the name in the clear in the ClientHello, so both are properties of the network at
 * that moment rather than of the relay. The scanner therefore asks the client itself — the same
 * fronting code the tunnel uses — and prints what it found, so the numbers here can never disagree
 * with what a connection would do.
 *
 * The question is asked as one matrix, never as "an address under www.google.com first, then the
 * other names on it": on the lines that need this most, www.google.com is the slow or filtered
 * name (three measured seconds for its handshake alone), so a scan gated on it reports a working
 * network as dead. The client's `-scan-matrix-json` tries addresses and names together and
 * reports every pair, fastest fronted pair first — that first pair is what the profile pins.
 *
 * The candidate addresses also begin with this line itself. Whatever the operator's resolver
 * answers for Google's hosts is the frontend pool that operator routes and, in a national
 * network, whitelists, and it differs between one SIM and the next. The app resolves those names
 * with the platform resolver ([resolveOperatorEdges]) and hands the addresses to the client,
 * because the client is a pure-Go binary with no /etc/resolv.conf to read on Android.
 *
 * Nothing here touches Apps Script: every probe is a TCP connection, a TLS handshake, and one
 * fronted request, so a scan costs no quota.
 */
object GutsEdgeScanner {
    /** A matrix scan is bounded by the client itself; this only guarantees the call returns. */
    internal const val BUDGET_MS = 95_000L

    /** Per-stage budget inside one probe: connect, handshake and read each get this in full. */
    internal const val CLIENT_TIMEOUT = "5s"

    /** What the client may spend on the whole matrix. */
    internal const val CLIENT_BUDGET = "80s"

    /** One address the network answered, as the client reports it. */
    data class Edge(val ip: String, val rttMs: Long, val fronted: Boolean, val error: String?)

    /** One Google name tried on the best address. */
    data class Name(val sni: String, val rttMs: Long, val fronted: Boolean, val error: String?)

    /** One (address, name) pair of the matrix, as the client reports it. */
    data class Route(
        val ip: String,
        val sni: String,
        val rttMs: Long,
        val fronted: Boolean,
        val error: String?,
    )

    /** What one round of scanning found; empty [bestEdge] or [bestSni] means "nothing answered". */
    data class Result(
        val edges: List<Edge> = emptyList(),
        val names: List<Name> = emptyList(),
        val bestEdge: String? = null,
        val bestSni: String? = null,
        val routes: List<Route> = emptyList(),
    ) {
        val answered: Boolean get() = bestEdge != null || bestSni != null
    }

    /**
     * Scans addresses and names as one matrix and keeps the fastest pair that fronted.
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
            val pinned = googleIp.isNotBlank() && googleIp != GutsCore.AUTO_EDGE
            val candidates = if (pinned) emptyList() else resolveOperatorEdges()
            val routes = parseRoutes(run(context, binary, googleIp, sni, frontHost, candidates))
            val best = routes.firstOrNull { it.fronted }
            Result(
                edges = edgesOf(routes),
                names = namesOf(routes),
                bestEdge = best?.ip,
                bestSni = best?.sni,
                routes = routes,
            )
        }.getOrElse { error ->
            LogUtil.w(AppConfig.TAG, "GutsEdge: the scan failed: ${error.message}")
            Result()
        }
    }

    /** The addresses of a scan, one row per address, fronted ones first. */
    private fun edgesOf(routes: List<Route>): List<Edge> =
        routes.groupBy { it.ip }.values.map { same ->
            val fronted = same.filter { it.fronted }.minByOrNull { it.rttMs }
            val any = fronted ?: same.minByOrNull { it.rttMs } ?: same.first()
            Edge(any.ip, any.rttMs, fronted != null, if (fronted != null) null else any.error)
        }.sortedWith(compareByDescending<Edge> { it.fronted }.thenBy { it.rttMs })

    /** The names of a scan, one row per name, fronted ones first. */
    private fun namesOf(routes: List<Route>): List<Name> =
        routes.filter { it.sni.isNotEmpty() }.groupBy { it.sni }.values.map { same ->
            val fronted = same.filter { it.fronted }.minByOrNull { it.rttMs }
            val any = fronted ?: same.minByOrNull { it.rttMs } ?: same.first()
            Name(any.sni, any.rttMs, fronted != null, if (fronted != null) null else any.error)
        }.sortedWith(compareByDescending<Name> { it.fronted }.thenBy { it.rttMs })

    /**
     * The frontend addresses of the line the phone is on right now: whatever the platform
     * resolver answers for Google's hosts. Best effort, bounded as a whole, IPv4 only because the
     * client's edge pool is — a resolver that stalls costs the scan its head start, not its
     * answer.
     */
    internal fun resolveOperatorEdges(timeoutMs: Long = OPERATOR_RESOLVE_MS): List<String> {
        val executor = Executors.newFixedThreadPool(RESOLVER_HOSTS.size) { runnable ->
            Thread(runnable, "GutsEdgeResolve").apply { isDaemon = true }
        }
        return try {
            val futures = RESOLVER_HOSTS.map { host ->
                executor.submit(
                    Callable {
                        runCatching {
                            InetAddress.getAllByName(host)
                                .filterIsInstance<Inet4Address>()
                                .mapNotNull { it.hostAddress }
                        }.getOrDefault(emptyList())
                    }
                )
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            futures.flatMap { future ->
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) {
                    future.cancel(true)
                    emptyList()
                } else {
                    runCatching { future.get(remaining, TimeUnit.MILLISECONDS) }
                        .getOrDefault(emptyList())
                }
            }.distinct().take(MAX_OPERATOR_EDGES)
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "GutsEdge: operator resolve failed: ${error.message}")
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Runs the client's matrix scan and returns its whole answer.
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
        edgeCandidates: List<String>,
    ): String {
        val command = buildList {
            add(binary.absolutePath)
            add("-scan-matrix-json")
            add("-timeout=$CLIENT_TIMEOUT")
            add("-scan-budget=$CLIENT_BUDGET")
            add("-google-ip=${googleIp.ifBlank { GutsCore.AUTO_EDGE }}")
            add("-sni=${sni.ifBlank { GutsCore.DEFAULT_SNI }}")
            add("-front-host=${frontHost.ifBlank { GutsCore.DEFAULT_FRONT_HOST }}")
            if (edgeCandidates.isNotEmpty()) {
                add("-edge-candidates=${edgeCandidates.joinToString(",")}")
            }
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
        // The answer is read on its own thread: reading it to the end before waiting would make
        // the budget unenforceable, because the read only ends when the client does.
        val answer = StringBuilder()
        val reader = thread(name = "GutsEdgeScanRead", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(answer) {
                            if (answer.length < MAX_ANSWER) answer.appendLine(line)
                        }
                    }
                }
            } catch (error: Exception) {
                LogUtil.w(AppConfig.TAG, "GutsEdge: could not read the scan: ${error.message}")
            }
        }
        if (!process.waitFor(BUDGET_MS, TimeUnit.MILLISECONDS)) {
            process.destroy()
            LogUtil.w(AppConfig.TAG, "GutsEdge: the scan did not finish in time")
        }
        reader.join(READER_JOIN_MS)
        return synchronized(answer) { answer.toString() }
    }

    /**
     * Reads `gutsclient -scan-matrix-json`: an array of `{ip, sni, rtt_ms, fronted, status, e}`,
     * fastest fronted pair first.
     *
     * The client also prints log lines on the same stream, so everything around the JSON array is
     * skipped rather than failing the parse: a scan that worked is not made worthless by a log
     * line. Gson reads it because it is what this app already parses JSON with — and because
     * `org.json` is a stub in JVM unit tests, where these parsers are the thing under test.
     */
    internal fun parseRoutes(answer: String): List<Route> = parsed(answer).mapNotNull { item ->
        val ip = item.ip.ifEmpty { return@mapNotNull null }
        Route(ip, item.sni, item.rttMs, item.fronted, item.error)
    }

    /** Reads `gutsclient -scan-json`: an array of `{ip, rtt_ms, fronted, status, e}`. */
    internal fun parseEdges(answer: String): List<Edge> =
        parsed(answer).mapNotNull { item ->
            val ip = item.ip.ifEmpty { return@mapNotNull null }
            Edge(ip, item.rttMs, item.fronted, item.error)
        }

    /** Reads `gutsclient -scan-snis-json`: the same shape, named by `sni`. */
    internal fun parseNames(answer: String): List<Name> =
        parsed(answer).mapNotNull { item ->
            val name = item.sni.ifEmpty { return@mapNotNull null }
            Name(name, item.rttMs, item.fronted, item.error)
        }

    /** One element of any scan answer. */
    private data class Parsed(
        val ip: String,
        val sni: String,
        val rttMs: Long,
        val fronted: Boolean,
        val error: String?,
    )

    private fun parsed(answer: String): List<Parsed> = runCatching {
        JsonParser.parseString(jsonPart(answer)).asJsonArray.mapNotNull { element ->
            val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            Parsed(
                ip = runCatching { item.get("ip")?.asString }.getOrNull()?.trim().orEmpty(),
                sni = runCatching { item.get("sni")?.asString }.getOrNull()?.trim().orEmpty(),
                rttMs = runCatching { item.get("rtt_ms")?.asLong }.getOrNull() ?: 0L,
                fronted = runCatching { item.get("fronted")?.asBoolean }.getOrNull() ?: false,
                error = runCatching { item.get("e")?.asString }.getOrNull()?.takeIf { it.isNotBlank() },
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

    /** A whole matrix is a few hundred short rows; anything longer is not one. */
    private const val MAX_ANSWER = 512 * 1024

    private const val READER_JOIN_MS = 2_000L
    private const val OPERATOR_RESOLVE_MS = 6_000L
    private const val MAX_OPERATOR_EDGES = 24

    /** The Google hosts whose answers on this line seed the candidate addresses. */
    private val RESOLVER_HOSTS = listOf(
        "www.google.com",
        "mail.google.com",
        "drive.google.com",
        "script.google.com",
        "www.gstatic.com",
        "fonts.gstatic.com",
        "connectivitycheck.gstatic.com",
    )
}
