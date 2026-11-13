package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Starts and reconciles the Firefox account engine owned by the VPN daemon process.
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
 * - **One lifetime.** A small service in `:daemon` owns the child for the account screen and the
 *   VpnService alike, so the UI and the VPN never start competing copies or disagree about whether
 *   an account exists.
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

    /** How long the account question may take; it is answered from memory by the engine. */
    private const val ACCOUNT_QUERY_TIMEOUT_MS = 1_500

    /** The prefix the app's own ELF libraries carry, which the installer keeps for the engine too. */
    private const val LIBRARY_PREFIX = "lib"

    /** The engine's own folder inside [AppConfig.FIREFOX_ENGINE_DIRECTORY], which holds its store. */
    private const val DATA_SUBDIRECTORY = "data"

    /** The abstract socket name a session's protect server listens on; random per start. */
    private const val PROTECT_SOCKET_PREFIX = "omni-firefox-"

    /** The engine's flag naming that socket; see firefox-core/cmd/firefoxclient. */
    private const val PROTECT_SOCKET_FLAG = "-protect-socket"

    /** Where the engine keeps its store, and the login its loopback API answers this app with. */
    private const val DATA_DIR_FLAG = "-data-dir"
    private const val TOKEN_FLAG = "-local-token"

    /** Owns the log pump alone, so a failing reader cannot take anything else down with it. */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null

    @Volatile private var childJob: kotlinx.coroutines.Job? = null

    @Volatile private var apiPort: Int = 0

    /**
     * The protect server this engine's session was started with, when the app runs as a VPN.
     *
     * The engine carries the phone's traffic; the sockets it opens to Mozilla must therefore come out
     * of the phone's own network and not back into the tunnel it is carrying. Only the app that owns the
     * VpnService can take a socket out of it, so the app serves this handshake and the engine hands it
     * every socket before connecting.
     */
    @Volatile private var protectServer: MasterDnsProtectServer? = null

    /** Whether the last owned child was started with the active VPN's socket-protect bridge. */
    @Volatile private var protectedInLastStart: Boolean = false

    /** How many sockets the engine asked the app to protect, and how many of those were refused. */
    private val protectRequests = AtomicInteger()
    private val protectRefusals = AtomicInteger()

    /** The last line the engine printed, which is how a start that failed says why. */
    @Volatile private var lastEngineLine: String = ""

    private fun engineSaid(): String = lastEngineLine.ifBlank { "no output" }

    /** Why the last start did not come up, as the screen should say it; empty after a start that did. */
    @Volatile private var lastFailure: String? = null

    /**
     * The reason the last start failed, in the app's language.
     *
     * A start that failed for a reason the engine itself printed (no account yet, a port taken, a
     * certificate store it could not read) used to reach the screen as "did not start": true, and of no
     * use to anybody. The engine's own last line is kept and returned instead.
     */
    fun lastFailure(context: Context): String =
        lastFailure ?: context.getString(com.v2ray.ang.R.string.firefox_engine_reason_unknown)

    /** Whether this app was installed with an engine for this device, and can start it. */
    fun isSupported(context: Context): Boolean = nativeLibrary(context) != null

    /** The loopback API port, visible from both the UI and the isolated VPN daemon process. */
    fun currentApiPort(context: Context): Int =
        apiPort.takeIf { it != 0 } ?: FirefoxBridge.DEFAULT_API_PORT

    /** Whether a child of ours is running at all, without touching the network. */
    val isRunning: Boolean get() = process?.isAlive == true && apiPort != 0

    /** Where the engine keeps its store and its token, inside this app's private files. */
    fun dataDir(context: Context): File =
        File(File(context.filesDir, AppConfig.FIREFOX_ENGINE_DIRECTORY), DATA_SUBDIRECTORY)

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
    @Synchronized
    fun start(
        context: Context,
        socksPort: Int,
        apiPort: Int,
        protect: ((Int) -> Boolean)? = null,
    ): Boolean {
        val wantsSocketProtection = protect != null
        if (isAlive(context)) {
            if (this.apiPort == apiPort && process?.isAlive == true &&
                protectedInLastStart == wantsSocketProtection
            ) return true
            // The account screen and the VpnService are in different Android processes. If this
            // process has no child but the authenticated API answers, the other process already owns
            // the one shared loopback listener; never start a competing engine on its port.
            if (process == null) {
                this.apiPort = apiPort
                if (!wantsSocketProtection) return true
                lastFailure = "Firefox engine is already running in another process without VPN socket protection"
                return false
            }
        }
        stop()

        lastFailure = null
        val library = nativeLibrary(context) ?: run {
            LogUtil.w(AppConfig.TAG, "The Firefox account engine is not installed for this device")
            lastFailure = context.getString(com.v2ray.ang.R.string.firefox_engine_reason_missing_file)
            return false
        }
        if (!library.canExecute() && !library.setExecutable(true)) {
            LogUtil.e(AppConfig.TAG, "The Firefox account engine cannot be made executable")
            lastFailure = context.getString(com.v2ray.ang.R.string.firefox_engine_reason_not_executable)
            return false
        }

        val dataDir = dataDir(context).apply {
            if (!exists() && !mkdirs()) {
                LogUtil.e(AppConfig.TAG, "The account engine's data folder cannot be created")
                lastFailure = context.getString(com.v2ray.ang.R.string.firefox_engine_reason_no_folder)
                return false
            }
        }

        // The protect server lives exactly as long as this start: a server left behind would be a socket
        // name another app could knock on, and a session without one would send the engine's own traffic
        // into the tunnel it carries.
        val server = protect?.let { callback ->
            runCatching {
                MasterDnsProtectServer(
                    name = PROTECT_SOCKET_PREFIX + android.os.Process.myPid() + "-" + System.nanoTime(),
                    protect = { descriptor ->
                        protectRequests.incrementAndGet()
                        val allowed = callback(descriptor)
                        if (!allowed) protectRefusals.incrementAndGet()
                        allowed
                    },
                    onError = { error -> LogUtil.w(AppConfig.TAG, "Firefox: protect request failed", error) },
                )
            }.onFailure { error ->
                LogUtil.e(AppConfig.TAG, "Firefox: the protect server could not be opened", error)
            }.getOrNull()
        }
        protectServer = server

        // The login the engine will require, written before it starts: a start without one answers nothing.
        if (writeEngineToken(context, dataDir).not()) {
            LogUtil.e(AppConfig.TAG, "The account engine's login could not be written")
            closeProtectServer()
            lastFailure = context.getString(com.v2ray.ang.R.string.firefox_engine_reason_no_folder)
            return false
        }

        val launched = runCatching {
            // A child is given nothing of this app but a directory of its own and the loopback it binds
            // to. HOME points inside that directory so a TLS stack that wants a place to cache roots
            // writes where it is permitted to rather than anywhere else.
            ProcessBuilder(
                buildList {
                    add(library.absolutePath)
                    // The engine's data folder, and the login that opens its loopback API: without it the
                    // engine answers nothing, and this app used to start it with neither.
                    add(DATA_DIR_FLAG)
                    add(dataDir.absolutePath)
                    add(TOKEN_FLAG)
                    add(token(context).orEmpty())
                    // Without the engine cannot run: it refuses the start rather than carrying the phone's
                    // traffic into its own tunnel.
                    server?.let { add(PROTECT_SOCKET_FLAG); add(it.configAddress) }
                }
            )
                .apply { environment()["HOME"] = dataDir.absolutePath }
                .directory(dataDir)
                .redirectErrorStream(true)
                .start()
        }.onFailure { error ->
            LogUtil.e(AppConfig.TAG, "The Firefox account engine did not launch", error)
        }.getOrNull() ?: run {
            closeProtectServer()
            return false
        }

        synchronized(this) {
            process = launched
            this.apiPort = apiPort
            protectedInLastStart = wantsSocketProtection
            childJob = logScope.launch { pumpLogs(launched, context) }
        }

        val listening = awaitListeningBlocking(apiPort, STARTUP_TIMEOUT_MS)
        if (!listening) {
            LogUtil.e(AppConfig.TAG, "The Firefox account engine started but never answered on loopback")
            lastFailure = "engine started, no loopback answer; ${engineSaid()}"
            stop()
            return false
        }
        LogUtil.i(AppConfig.TAG, "The Firefox account engine is listening on $socksPort (api $apiPort)")
        return true
    }

    /** [start] for callers on the main thread: the same work, off it. */
    suspend fun ensureStarted(
        context: Context,
        socksPort: Int,
        apiPort: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        if (isAlive(context)) return@withContext true
        val started = runCatching {
            context.startService(Intent(context, com.v2ray.ang.service.FirefoxAccountEngineService::class.java))
        }.getOrNull() != null
        if (!started) return@withContext false

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            if (isAlive(context) && listeningOn(apiPort)) return@withContext true
            Thread.sleep(100)
        }
        false
    }

    /**
     * Stops the engine if it is running, and forgets it.
     *
     * Called when the service stops and whenever the app wants no live credential-bearing process
     * that nothing is watching.
     */
    @Synchronized
    fun stop() {
        val (child, pump) = synchronized(this) {
            val taken = Pair(process, childJob)
            process = null
            childJob = null
            apiPort = 0
            protectedInLastStart = false
            taken
        }
        closeProtectServer()
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
        val token = token(context) ?: return false
        val port = currentApiPort(context)
        return runCatching {
            val connection = (java.net.URL("http://${AppConfig.LOOPBACK}:$port/api/status")
                .openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = ACCOUNT_QUERY_TIMEOUT_MS
                readTimeout = ACCOUNT_QUERY_TIMEOUT_MS
                setRequestProperty("X-Local-Token", token)
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Writes the login the engine's loopback API answers this app with, once per install.
     *
     * It is a random value in the app's private folder: any other app on the phone can reach loopback,
     * and none of them may add an account or spend somebody's monthly allowance. A token left over from
     * an earlier install is replaced, so a copied folder cannot open a fresh engine.
     */
    private fun writeEngineToken(context: Context, dataDir: File): Boolean {
        val file = File(dataDir, AppConfig.FIREFOX_ENGINE_TOKEN)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrEmpty() && existing.all { it.isLetterOrDigit() } && existing.length in 32..256) {
            return true
        }
        return runCatching {
            if (!dataDir.isDirectory && !dataDir.mkdirs()) return false
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it) }
            file.writeText(token)
            runCatching { file.setReadable(false, false); file.setReadable(true, true) }
            true
        }.getOrDefault(false)
    }

    /** Closes the protect server of the last start, if one is still open. */
    private fun closeProtectServer() {
        val server = synchronized(this) {
            val taken = protectServer
            protectServer = null
            taken
        }
        runCatching { server?.close() }
    }

    /**
     * Whether the engine holds at least one account, asked over its loopback API.
     *
     * The engine answers "no account" as a phase once it is up, so the question is how many it keeps:
     * a row whose engine has none answers every request with nothing, and the daemon says so instead
     * of starting the link.
     */
    fun hasAccount(context: Context): Boolean {
        val token = token(context) ?: return false
        val port = currentApiPort(context)
        if (port == 0) return false
        val body = runCatching {
            val url = java.net.URL("http://${AppConfig.LOOPBACK}:$port/api/accounts")
            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = ACCOUNT_QUERY_TIMEOUT_MS
                readTimeout = ACCOUNT_QUERY_TIMEOUT_MS
                setRequestProperty("X-Local-Token", token)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode != 200) return false
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return false
        // `{"accounts":[...]}` — one entry means there is an account to carry traffic with.
        val accounts = org.json.JSONObject(body).optJSONArray("accounts") ?: return false
        return accounts.length() > 0
    }

    /** How the engine was protected in this start, for the log and for a person reading it. */
    fun protectionReport(): String =
        "protect requests=${protectRequests.get()} refused=${protectRefusals.get()} " +
            "server=${if (protectServer != null) "up" else "none"}"

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
                    val clean = redact(line, token)
                    LogUtil.d(AppConfig.TAG, "firefox core: $clean")
                    if (clean.isNotBlank()) lastEngineLine = clean
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
