package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Asks the phone's own network the one question that decides the «national ⇄ normal» switch: does
 * the Google edge carry a fronted call from here?
 *
 * The question is answered by the very client that would carry the tunnel (`-probe`), so there is
 * no second implementation of "can I reach Google today" to drift away from the first. The probe
 * makes no Apps Script call: it is one TLS handshake with a Google SNI plus one fronted request, so
 * it costs no quota and does not wake the door.
 *
 * A probe is only meaningful while the tunnel is down. With the tunnel up, the app's own sockets go
 * through it and the answer would be about the tunnel instead of the network, so [shouldAsk] is
 * false in that state and the caller keeps its last answer.
 */
object EdgeProbe {
    /** A probe is bounded by itself; this is only the outer guarantee that it ends. */
    internal const val BUDGET_MS = 25_000L

    /**
     * Passed to the client so it gives up before the outer budget does. Twelve seconds because
     * the client now races addresses and names together and returns at the first fronted pair: a
     * healthy line answers in a second, and a line whose one working name is slow is still a
     * working line — five seconds used to call it blocked.
     */
    internal const val CLIENT_BUDGET = "20s"

    /** What resolving this line's own edge addresses may cost the probe. */
    private const val EDGE_RESOLVE_BUDGET_MS = 2_500L

    /** Thin wrapper so the spawn is not attempted twice for a phone that has no client at all. */
    suspend fun probe(context: Context, core: GutsCore): EdgeVerdict = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.canExecute()) {
            LogUtil.w(AppConfig.TAG, "EdgeProbe: no GUTS client to ask with")
            return@withContext EdgeVerdict.INCONCLUSIVE
        }
        runCatching { ask(context, binary, core) }
            .getOrElse { error ->
                LogUtil.w(AppConfig.TAG, "EdgeProbe: the probe itself failed: ${error.message}")
                EdgeVerdict.INCONCLUSIVE
            }
    }

    private fun ask(context: Context, binary: File, core: GutsCore): EdgeVerdict {
        // While the edge is automatic the probe races the addresses this line's resolver just
        // answered, resolved here for the same reason the scanner resolves them: the client's own
        // resolver has nothing to read on Android.
        val edgeCandidates = if (core.googleIp == GutsCore.AUTO_EDGE) {
            GutsEdgeScanner.resolveOperatorEdges(EDGE_RESOLVE_BUDGET_MS)
        } else {
            emptyList()
        }
        val command = buildList {
            add(binary.absolutePath)
            add("-probe")
            add("-probe-budget=$CLIENT_BUDGET")
            core.doors.forEach { door -> add("-door=$door") }
            add("-google-ip=${core.googleIp}")
            add("-sni=${core.sni}")
            if (edgeCandidates.isNotEmpty()) {
                add("-edge-candidates=${edgeCandidates.joinToString(",")}")
            }
            add("-front-host=${core.frontHost}")
            // No control socket: this runs while the tunnel is down, so there is no VPN of ours to
            // escape. Passing one would only make the probe depend on a session that is not there.
            add("-fd-control-unix-socket=")
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                // The key stays out of the argv of every client, probe or not.
                environment()[KEY_ENV] = core.key
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()

        // Read on its own thread, like the scanner: reading to the end first would leave the
        // outer budget below with nothing left to enforce.
        val answer = StringBuilder()
        val reader = thread(name = "EdgeProbeRead", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(answer) {
                            if (answer.length < MAX_ANSWER) answer.appendLine(line)
                        }
                    }
                }
            } catch (error: Exception) {
                LogUtil.w(AppConfig.TAG, "EdgeProbe: could not read the answer: ${error.message}")
            }
        }

        val finished = process.waitFor(BUDGET_MS, TimeUnit.MILLISECONDS)
        reader.join(2_000L)
        if (!finished) {
            process.destroy()
            LogUtil.w(AppConfig.TAG, "EdgeProbe: the probe did not answer in time")
            return EdgeVerdict.classify(-1, timedOut = true)
        }

        val verdict = EdgeVerdict.classify(process.exitValue(), timedOut = false)
        // The answer is one JSON line with the address and its latency, or the reason it failed; it
        // is logged with the key taken out like every other line of this client.
        val text = synchronized(answer) { answer.toString() }
        LogUtil.i(AppConfig.TAG, "EdgeProbe: ${verdict.name.lowercase()} ${core.redact(text.trim())}")
        return verdict
    }

    /** Matches the client's file name in the native library directory. */
    private const val BINARY_NAME = "libguts.so"

    /** The environment name the client reads its key from. */
    private const val KEY_ENV = "GUTS_KEY"

    /** A probe answer is one short line; anything longer is not an answer. */
    private const val MAX_ANSWER = 4_096
}
