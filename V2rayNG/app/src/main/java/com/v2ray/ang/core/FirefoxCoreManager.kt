package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Owns the one account engine process the "Firefox" row needs.
 *
 * The engine is the Mozilla account path itself: it signs in to Mozilla's own endpoints with the
 * account a person typed on this phone, keeps that account in the data folder this app gives it, and
 * opens a SOCKS5 listener on loopback which Xray dials like any other outbound. Nothing here is an
 * Xray protocol, and nothing the account contains is put into an Xray config: the engine owns the
 * session, the allowance and the accounting, and this class only owns the process.
 *
 * It is started from the app's native library directory, the way the other engines here are, and it
 * is told to stop by closing the pipe it reads — no signals, no process group, and nothing left
 * behind if the app itself is killed.
 *
 * Its logs are read and logged by this app, with the pieces that could name a person or open their
 * account taken out: an email address, a token, or an authorization header never reaches the log.
 */
object FirefoxCoreManager {

    private const val BINARY_NAME = "libfirefox.so"
    private const val DATA_DIR_NAME = "firefox-engine"
    private const val TOKEN_FILE_NAME = "local-api.token"
    private const val READY_POLL_MS = 250L
    private const val EXIT_WAIT_MS = 3_000L
    private const val TAG = "Firefox"

    /** Long enough for the engine to bind both listeners on a phone that is busy waking up. */
    private const val DEFAULT_START_BUDGET_MS = 15_000L

    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FirefoxLifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null

    /** True from an accepted start until the process is gone again, warm-up included. */
    val isRunning: Boolean get() = session != null

    /** The loopback port Xray dials, as the running session opened it. */
    val currentSocksPort: Int? get() = session?.socksPort

    /** The loopback port the app's own calls to the engine use. */
    val currentApiPort: Int? get() = session?.apiPort

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /** Whether this build carries the engine at all; a phone without it is told so, not worked around. */
    fun isSupported(context: Context): Boolean = binary(context).canExecute()

    /** The folder the engine keeps the account in: this app's private storage, and nothing else's. */
    fun dataDir(context: Context): File = File(context.filesDir, DATA_DIR_NAME).apply {
        if (!isDirectory) mkdirs()
    }

    /**
     * The token that opens the engine's loopback API, or null before the engine has written one.
     *
     * It is a fresh random value each run, readable by this app alone, and is what keeps every other
     * app on the phone out of an API that can add an account.
     */
    fun token(context: Context): String? = runCatching {
        File(dataDir(context), TOKEN_FILE_NAME).readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Starts the engine, or keeps the one that is already up.
     *
     * The wait for its listeners is bounded: a start that never answers is a failure the caller can
     * report, not a tunnel that hangs.
     */
    fun start(
        context: Context,
        socksPort: Int = FirefoxBridge.DEFAULT_PORT,
        apiPort: Int = FirefoxBridge.DEFAULT_API_PORT,
    ): Boolean {
        val running = session
        if (running != null && running.socksPort == socksPort && running.apiPort == apiPort && running.alive()) {
            return true
        }
        if (running != null) stop()
        if (!isSupported(context)) {
            LogUtil.w(AppConfig.TAG, "$TAG: this build carries no account engine")
            return false
        }
        val program = binary(context)
        val data = dataDir(context)
        val started = try {
            ProcessBuilder(
                program.absolutePath,
                "-data-dir", data.absolutePath,
                "-socks", "${AppConfig.LOOPBACK}:$socksPort",
                "-api", "${AppConfig.LOOPBACK}:$apiPort",
            ).apply {
                redirectErrorStream(false)
                // The engine reads its standard input until it closes, which is how it is stopped;
                // redirecting it from a file would stop it at once.
                redirectInput(ProcessBuilder.Redirect.PIPE)
            }.start()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: the account engine did not start", e)
            null
        } ?: return false

        val next = Session(started, socksPort, apiPort)
        next.reader = thread(name = "FirefoxLog", isDaemon = true) { pumpLogs(started) }
        session = next
        LogUtil.i(AppConfig.TAG, "$TAG: account engine started, socks=$socksPort api=$apiPort")
        return true
    }

    /** Stops the engine and waits, briefly, for the process to be gone. */
    fun stop() {
        val current = session ?: return
        session = null
        lifecycle.execute {
            try {
                runCatching { current.process.outputStream.close() }
                current.process.destroy()
                val exited = runCatching { current.process.waitFor(EXIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
                if (exited == false) current.process.destroyForcibly()
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "$TAG: stopping the account engine met: ${e.message.orEmpty()}")
            }
        }
    }

    /** Waits for the engine's SOCKS listener to answer, bounded by [timeoutMs]. Blocking, for callers outside a coroutine. */
    fun awaitListeningBlocking(port: Int, timeoutMs: Long = DEFAULT_START_BUDGET_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (session == null) return false
            if (portAnswers(port)) return true
            Thread.sleep(READY_POLL_MS)
        }
        return portAnswers(port)
    }

    /** Whether something accepts a connection on the loopback port, which is what a listening engine looks like. */
    fun portAnswers(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 1_000)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Copies the engine's log into this app's, without the parts that name a person or open an account.
     *
     * The engine logs what happened to a session, which can include the address it belongs to; a log
     * this app keeps is read by the person and shown on screen, so it never becomes the way an account
     * leaks out of the phone.
     */
    private fun pumpLogs(process: Process) {
        try {
            process.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    LogUtil.i(AppConfig.TAG, "[$TAG] ${redact(line)}")
                }
            }
        } catch (_: Exception) {
            // A closed stream is the normal end of the pump.
        }
    }

    /**
     * Takes the parts of an engine log line that could name a person or open their account out of it:
     * an email address, a bearer token, and the headers and query strings that carry one.
     */
    internal fun redact(line: String): String = line
        .replace(EMAIL_PATTERN, "<address>")
        .replace(BEARER_PATTERN, "Bearer <token>")
        .replace(TOKEN_QUERY_PATTERN, "token=<token>")

    private val EMAIL_PATTERN = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val BEARER_PATTERN = Regex("""(?i)bearer\s+[\w.-]+""")
    private val TOKEN_QUERY_PATTERN = Regex("""(?i)([?&](?:token|code|authpw|authPW)=)[^&\s]+""")

    private class Session(
        val process: Process,
        val socksPort: Int,
        val apiPort: Int,
    ) {
        @Volatile
        var reader: Thread? = null

        private val stopped = AtomicBoolean(false)

        fun alive(): Boolean = !stopped.get() && runCatching { process.isAlive }.getOrDefault(false)
    }
}
