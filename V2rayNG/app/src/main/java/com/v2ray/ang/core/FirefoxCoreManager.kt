package com.v2ray.ang.core

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Keeps the Firefox account engine alive for as long as this process is.
 *
 * The engine is a plain ELF the installer drops beside the app's other native libraries. Unlike the
 * library-backed cores, nothing in the app can `System.load` it, so it is executed as a child process
 * bound to loopback on the ports [FirefoxBridge] hands to the rest of the app.
 *
 * The engine holds what an account is made of — the refresh token, the device keys, the session — in
 * this app's private storage, and answers over loopback for a caller that proves it is this app by
 * presenting the token the engine itself wrote into a file no other app can read. Two properties
 * follow from that, and they are the reason this class exists at all:
 *
 * - **One copy of a secret.** The app never sees the tokens it would otherwise have to hold to call
 *   Mozilla itself; it asks the engine, and the engine answers or does not.
 * - **One lifetime.** The engine is started when something needs it, kept while the service lives,
 *   and stopped with it — a child left behind would be a process holding live credentials that no
 *   one is any longer watching.
 */
object FirefoxCoreManager {

    /** The engine is built for 64-bit Android only; that is what its library directory holds. */
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    /** Android 10 is the oldest release the engine's loopback server and TLS stack are built for. */
    private const val MIN_SDK = 29

    /** How long the engine is given to bind its listeners and write its token. */
    private const val STARTUP_TIMEOUT_MS = 15_000L

    /** How long one probe of a loopback port waits before calling it closed. */
    private const val PROBE_TIMEOUT_MS = 400

    /** The prefix the app's own ELF libraries carry, which the installer keeps for the engine too. */
    private const val LIBRARY_PREFIX = "lib"

    /** Owns the log pump alone, so a failing reader cannot take anything else down with it. */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null

    @Volatile private var childJob: kotlinx.coroutines.Job? = null

    @Volatile private var apiPort: Int = 0

    /** Whether this app was installed with an engine for this device, and can start it. */
    fun isSupported(context: Context): Boolean = nativeLibrary(context) != null

    /** The port the engine's account API answers on once it is up; 0 when it is not. */
    fun currentApiPort(context: Context): Int = if (process != null) apiPort else 0

    /** Whether a child of ours is running at all, without touching the network. */
    val isRunning: Boolean get() = process?.isAlive == true && apiPort != 0

    /** Where the engine keeps its store and its token, inside this app's private files. */
    fun dataDir(context: Context): File =
        File(context.filesDir, AppConfig.FIREFOX_ENGINE_DIRECTORY)

    /**
     * The engine's loopback origin, for callers that want to name it rather than have it named for
     * them, e.g. to install it as the system proxy: `http://127.0.0.1:<port>`.
     */
    fun baseUrl(context: Context): String? {
        val port = currentApiPort(context)
        return if (port == 0) null else "http://${AppConfig.LOOPBACK}:$port"
    }

    /**
     * Starts the engine unless it is already answering, and waits for it to answer.
     *
     * Blocking by design: the caller is a service thread, and a start that has not finished is a
     * start that has not happened. Callers on the main thread want [ensureStarted] instead.
     *
     * @return whether the engine is listening on [apiPort] when this returns.
     */
    @Suppress("ReturnCount")
    fun start(context: Context, socksPort: Int, apiPort: Int): Boolean {
        if (isAlive(context) && this.apiPort == apiPort) return true
        stop()

        val library = nativeLibrary(context) ?: run {
            LogUtil.w(AppConfig.TAG, "The Firefox account engine is not installed for this device")
            return false
        }
        if (!library.canExecute() && !library.setExecutable(true)) {
            LogUtil.e(AppConfig.TAG, "The Firefox account engine cannot be made executable")
            return false
        }

        val dataDir = dataDir(context).apply {
            if (!exists() && !mkdirs()) {
                LogUtil.e(AppConfig.TAG, "The account engine's data folder cannot be created")
                return false
            }
        }

        val launched = runCatching {
            // A child is given nothing of this app but a directory of its own and the loopback it binds
            // to. HOME points inside that directory so a TLS stack that wants a place to cache roots
            // writes where it is permitted to rather than anywhere else.
            ProcessBuilder(library.absolutePath)
                .apply { environment()["HOME"] = dataDir.absolutePath }
                .directory(dataDir)
                .redirectErrorStream(true)
                .start()
        }.onFailure { error ->
            LogUtil.e(AppConfig.TAG, "The Firefox account engine did not launch", error)
        }.getOrNull() ?: return false

        synchronized(this) {
            process = launched
            this.apiPort = apiPort
            childJob = logScope.launch { pumpLogs(launched, context) }
        }

        val listening = awaitListeningBlocking(apiPort, STARTUP_TIMEOUT_MS)
        if (!listening) {
            LogUtil.e(AppConfig.TAG, "The Firefox account engine started but never answered on loopback")
            stop()
            return false
        }
        LogUtil.i(AppConfig.TAG, "The Firefox account engine is listening on $socksPort (api $apiPort)")
        return true
    }

    /** [start] for callers on the main thread: the same work, off it. */
    suspend fun ensureStarted(context: Context, socksPort: Int, apiPort: Int): Boolean =
        withContext(Dispatchers.IO) { start(context, socksPort, apiPort) }

    /**
     * Stops the engine if it is running, and forgets it.
     *
     * Called when the service stops and whenever the app wants no live credential-bearing process
     * that nothing is watching.
     */
    fun stop() {
        val (child, pump) = synchronized(this) {
            val taken = Pair(process, childJob)
            process = null
            childJob = null
            apiPort = 0
            taken
        }
        pump?.cancel()
        val running = child ?: return
        LogUtil.d(AppConfig.TAG, "Stopping the Firefox account engine")
        runCatching { running.destroy() }
        // The engine stops on its own when told, but a process that ignores the signal would keep the
        // loopback socket open and the credentials it holds live, so it is given a moment and then
        // killed outright.
        runCatching {
            if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
        }.onFailure { runCatching { running.destroyForcibly() } }
    }

    /**
     * Whether the engine is answering on its loopback port right now.
     *
     * Touches the network, so it is never called from the main thread.
     */
    fun isAlive(context: Context): Boolean {
        val port = currentApiPort(context)
        return port != 0 && listeningOn(port)
    }

    /** Whether something is accepting connections on [port] on loopback. */
    fun awaitListeningBlocking(port: Int, timeoutMs: Long): Boolean {
        var waited = 0L
        var pause = 100L
        while (waited < timeoutMs) {
            if (listeningOn(port)) return true
            Thread.sleep(pause)
            waited += pause
            // A fresh process needs a moment before its socket exists, and the waiting grows so a
            // slow start is given time without a fast one being slowed down by a fixed pause.
            pause = min(pause * 2, 1_000L)
        }
        return false
    }

    /**
     * The shared secret the engine wrote for this app alone.
     *
     * Read straight from the file each call, so a rotated token is picked up without a restart; null
     * while the engine has not started yet.
     */
    fun token(context: Context): String? {
        val file = File(dataDir(context), AppConfig.FIREFOX_ENGINE_TOKEN)
        val value = runCatching { file.readText().trim() }.getOrNull()
        if (value.isNullOrEmpty() || value.length > 8_192) return null
        return value
    }

    /**
     * Deletes the engine's store in this app's private storage on this phone: the accounts, the
     * device registrations and the tokens. The connection pass itself lives with Mozilla and cannot
     * be revoked from here, so this does not claim to be account deletion — only the local half of
     * signing out.
     */
    fun clearStoredSession(context: Context) {
        val dir = dataDir(context)
        val children = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
        var failures = 0
        for (child in children) {
            if (child.isDirectory) {
                if (!child.deleteRecursively()) failures++
            } else if (!child.delete() && child.exists()) {
                failures++
            }
        }
        if (failures > 0) {
            LogUtil.w(AppConfig.TAG, "The account engine's data folder kept $failures of ${children.size} entries")
        } else if (children.isNotEmpty()) {
            LogUtil.i(AppConfig.TAG, "The account engine's data folder is clear")
        }
    }

    /** Finds the engine for this device, or null where there is none. */
    private fun nativeLibrary(context: Context): File? {
        if (Build.VERSION.SDK_INT < MIN_SDK) return null
        if (SUPPORTED_ABIS.none { Build.SUPPORTED_ABIS.contains(it) }) return null
        val directory = File(context.applicationInfo.nativeLibraryDir)
        val plain = File(directory, "${LIBRARY_PREFIX}firefox.so")
        return plain.takeIf { it.exists() && it.length() > 0 }
    }

    private fun listeningOn(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(AppConfig.LOOPBACK, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * Consumes the child's output for as long as it lives.
     *
     * A child's stdout, were it not consumed, would fill its pipe buffer and stop the process dead
     * mid-session — a failure that looks like a hang and is only a full buffer. The lines go to the
     * log with the token and any address redacted out of them.
     */
    private fun pumpLogs(child: Process, context: Context) {
        val token = token(context)
        runCatching {
            child.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!logScope.isActive) break
                    LogUtil.d(AppConfig.TAG, "firefox core: ${redact(line, token)}")
                }
            }
        }.onFailure { error ->
            LogUtil.d(AppConfig.TAG, "The account engine's log stopped: ${error.message}")
        }
    }

    /** Keeps a secret and an address out of a child's log line. */
    private fun redact(line: String, token: String?): String {
        var safe = line
        if (!token.isNullOrEmpty() && token.length >= 8) safe = safe.replace(token, "****")
        return EMAIL.replace(safe, "****@****")
    }

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
}
