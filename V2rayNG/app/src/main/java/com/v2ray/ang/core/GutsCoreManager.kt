package com.v2ray.ang.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Owns the one guts client process the daemon's selected profile needs.
 *
 * The client is the same program the desktop runs: it opens a SOCKS5 listener on the loopback and
 * carries every connection through a door to the exit node. Xray dials that listener, so the only
 * thing this manager adds to the client is what a phone needs and a desktop does not — a protected
 * socket, so the client's own calls to Google are not routed back into the VPN it feeds, and a
 * private environment, so its key is never on a command line or in a log.
 */
object GutsCoreManager {

    private const val BINARY_NAME = "libguts.so"
    private const val SESSION_ENV = "OMNI_GUTS_SESSION"
    private const val ISOLATED_ENV = "OMNI_GUTS_ISOLATED"
    private const val IDENTITY_ENV = "OMNI_GUTS_IDENTITY"
    private const val PORT_ENV = "OMNI_GUTS_PORT"

    /** The client reads its shared key from here, which keeps it off the command line. */
    private const val KEY_ENV = "GUTS_KEY"
    private const val QUOTA_STATE_ENV = "GUTS_QUOTA_STATE"
    private const val QUOTA_BUDGET_ENV = "GUTS_DOOR_BUDGET"
    private const val QUOTA_STATE_FILE = "guts-quota.json"

    private const val READY_POLL_MS = 250L
    private const val EXIT_WAIT_MS = 2_000L
    private const val EXIT_POLL_MS = 50L
    internal const val PORT_CHECK_WAIT_MS = 5_000L

    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GutsLifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null
    private val isolatedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()

    /** True from the accepted start until that session exits or is superseded, including warm-up. */
    val isRunning: Boolean get() = session != null

    val currentPort: Int? get() = session?.core?.port

    val currentIdentity: String? get() = session?.core?.identity

    internal data class LiveProcess(val pid: Int, val port: Int, val identity: String?)

    /** Finds this app's client without mistaking an unrelated loopback listener for it. */
    internal fun liveProcess(context: Context): LiveProcess? {
        val expected = binary(context).absolutePath
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val argv = readProcWords(File(entry, "cmdline")) ?: return@forEach
            if (argv.firstOrNull() != expected) return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if ("$SESSION_ENV=1" !in environment || "$ISOLATED_ENV=1" in environment) return@forEach
            val port = environment.firstOrNull { it.startsWith("$PORT_ENV=") }
                ?.substringAfter('=')?.toIntOrNull()
                ?: argv.firstOrNull { it.startsWith("-listen") }
                    ?.substringAfter('=')?.substringAfterLast(':')?.toIntOrNull()
                ?: return@forEach
            val identity = environment.firstOrNull { it.startsWith("$IDENTITY_ENV=") }?.substringAfter('=')
            return LiveProcess(pid, port, identity)
        }
        return null
    }

    private fun readProcWords(file: File): List<String>? = try {
        file.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Waits for queued process/protect cleanup, bounded so a failed teardown cannot hang a test. */
    internal fun awaitStopped(timeoutMs: Long): Boolean {
        val barrier = FutureTask(Callable { Unit })
        lifecycle.execute(barrier)
        return try {
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            barrier.cancel(true)
            false
        }
    }

    private fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isSupported(context: Context): Boolean = binary(context).canExecute()

    internal sealed interface PortCheck {
        data object Free : PortCheck
        data object Taken : PortCheck
        data class Unchecked(val cause: Exception? = null) : PortCheck
    }

    /**
     * Checks the loopback port before Xray starts and can send traffic to it. The bounded wait is
     * leak prevention: an unrelated listener must never receive the profile's traffic.
     */
    internal fun checkSessionPort(port: Int, timeoutMs: Long = PORT_CHECK_WAIT_MS): PortCheck {
        val check = FutureTask(Callable { if (portFree(port)) PortCheck.Free else PortCheck.Taken })
        lifecycle.execute(check)
        return try {
            check.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            check.cancel(true)
            PortCheck.Unchecked(e)
        } catch (e: Exception) {
            PortCheck.Unchecked(e)
        }
    }

    internal fun portFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(AppConfig.LOOPBACK, port))
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Starts [core] after Xray is up. In VPN mode [protect] is CoreVpnService.vpnProtect. */
    @Synchronized
    fun start(
        context: Context,
        core: GutsCore,
        protect: ((Int) -> Boolean)?,
        reapStaleProcesses: Boolean = true,
        onExit: () -> Unit,
    ) {
        stop()
        val next = Session(context.applicationContext, core, onExit)
        session = next
        lifecycle.execute { open(next, protect, reapStaleProcesses) }
    }

    /** Stops the session and prevents its later exit from being reported as an unexpected failure. */
    @Synchronized
    fun stop() {
        val current = session ?: return
        current.stopRequested.set(true)
        session = null
        lifecycle.execute { finish(current, notifyExit = false) }
    }

    /** Starts a scanner-owned client without replacing the foreground session. */
    @Synchronized
    internal fun startIsolated(context: Context, core: GutsCore, protect: ((Int) -> Boolean)? = null): Session {
        val next = Session(context.applicationContext, core, onExit = {}, isolated = true)
        isolatedSessions += next
        lifecycle.execute { open(next, protect, reapStaleProcesses = false) }
        return next
    }

    @Synchronized
    internal fun stopIsolated(target: Session) {
        if (!isolatedSessions.remove(target)) return
        target.stopRequested.set(true)
        lifecycle.execute { finish(target, notifyExit = false) }
    }

    private fun owns(target: Session): Boolean = ownsCoreSession(
        isolated = target.isolated,
        selected = session === target,
        registered = isolatedSessions.contains(target),
        stopRequested = target.stopRequested.get(),
    )

    private fun open(target: Session, protect: ((Int) -> Boolean)?, reapStaleProcesses: Boolean) {
        try {
            if (!owns(target)) return
            if (reapStaleProcesses) reapStale(target.context)
            val directory = sessionDirectory(target)
            target.directory = directory

            if (protect != null) {
                val socketName = "omni-guts-${android.os.Process.myPid()}-${target.id}"
                target.protectServer = MasterDnsProtectServer(socketName, protect) { error ->
                    LogUtil.w(AppConfig.TAG, "GUTS: protect server request failed", error)
                }
            }

            val process = ProcessBuilder(processArguments(target)).apply {
                directory(directory)
                redirectErrorStream(true)
                environment().apply {
                    put(SESSION_ENV, "1")
                    if (target.isolated) put(ISOLATED_ENV, "1")
                    put(IDENTITY_ENV, target.core.identity)
                    // The door and the exit node check this; the command line does not carry it.
                    put(KEY_ENV, target.core.key)
                    put(PORT_ENV, target.core.port.toString())
                    put("HOME", directory.absolutePath)
                    put("TMPDIR", target.context.cacheDir.absolutePath)
                    // Where the client remembers today's calls per door, and how many each door
                    // may take: passed through the environment so an older client ignores them
                    // instead of refusing an argv it does not know.
                    put(QUOTA_STATE_ENV, File(target.context.filesDir, QUOTA_STATE_FILE).absolutePath)
                    val budget = SettingsManager.gutsDoorBudget()
                    if (budget > 0) put(QUOTA_BUDGET_ENV, budget.toString())
                }
            }.start()
            // A client that cannot start prints why and exits; an open stdin would only make it wait.
            runCatching { process.outputStream.close() }
            target.process = process
            target.outputThread = thread(name = "GutsOutput", isDaemon = true) { forward(target, process) }
            target.watchThread = thread(name = "GutsWatch", isDaemon = true) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    // finish still runs: the session no longer owns a live process to report.
                }
                finish(target, notifyExit = true)
            }
            LogUtil.i(AppConfig.TAG, "GUTS: client started on ${AppConfig.LOOPBACK}:${target.core.port}")
            if (!owns(target)) finish(target, notifyExit = false)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "GUTS: client start failed", e)
            finish(target, notifyExit = true)
        }
    }

    /**
     * Deterministic command line. The key is absent on purpose: it reaches the client through
     * GUTS_KEY in the environment, which only this user can read, and never through a log line.
     */
    internal fun processArguments(target: Session): List<String> = processArguments(
        binary = binary(target.context),
        core = target.core,
        protectAddress = target.protectServer?.configAddress,
    )

    internal fun processArguments(binary: File, core: GutsCore, protectAddress: String?): List<String> = buildList {
        add(binary.absolutePath)
        add("-listen=${AppConfig.LOOPBACK}:${core.port}")
        core.doors.forEach { door -> add("-door=$door") }
        add("-google-ip=${core.googleIp}")
        add("-sni=${core.sni}")
        add("-front-host=${core.frontHost}")
        add("-fd-control-unix-socket=${protectAddress.orEmpty()}")
    }

    private fun sessionDirectory(target: Session): File {
        val directory = File(target.context.cacheDir, "guts/${target.id}")
        if (!directory.mkdirs() && !directory.isDirectory) error("Cannot create GUTS session directory")
        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        return directory
    }

    private fun forward(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> relay(target.core, line) }
            }
        } catch (e: Exception) {
            if (owns(target)) LogUtil.w(AppConfig.TAG, "GUTS: output reader ended unexpectedly", e)
        }
    }

    internal fun relay(core: GutsCore, line: String) {
        val text = core.redact(line).trim()
        if (text.isEmpty()) return
        when {
            text.contains("ERROR", ignoreCase = true) || text.contains("FATAL", ignoreCase = true) ||
                text.startsWith("gutsclient: ") -> LogUtil.e(AppConfig.TAG, "[guts] $text")
            text.contains("WARN", ignoreCase = true) -> LogUtil.w(AppConfig.TAG, "[guts] $text")
            else -> LogUtil.i(AppConfig.TAG, "[guts] $text")
        }
    }

    /** Waits for a real SOCKS5 no-auth greeting, not merely a running process or an open TCP port. */
    suspend fun awaitListening(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (session != null) {
            val current = session ?: return@withTimeoutOrNull false
            val process = current.process
            if (process != null && !isAlive(process)) return@withTimeoutOrNull false
            // Before ProcessBuilder returns there is no owned listener to accept a readiness probe.
            if (process != null && withContext(Dispatchers.IO) { answersSocks(current.core.port) }) {
                return@withTimeoutOrNull true
            }
            delay(READY_POLL_MS)
        }
        false
    } ?: false

    internal suspend fun awaitIsolatedListening(target: Session, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (owns(target)) {
                val process = target.process
                if (process != null && !isAlive(process)) return@withTimeoutOrNull false
                if (process != null && withContext(Dispatchers.IO) { answersSocks(target.core.port) }) {
                    return@withTimeoutOrNull true
                }
                delay(READY_POLL_MS)
            }
            false
        } ?: false

    internal fun answersSocks(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 500)
            socket.soTimeout = 1_000
            socket.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                flush()
            }
            val response = ByteArray(2)
            val read = socket.getInputStream().read(response)
            read == 2 && response.contentEquals(byteArrayOf(0x05, 0x00))
        }
    } catch (_: Exception) {
        false
    }

    private fun finish(target: Session, notifyExit: Boolean) {
        val current = session === target
        val owned = if (target.isolated) isolatedSessions.contains(target) else current
        val shouldNotify = notifyExit && owned && !target.stopRequested.get()
        if (target.isolated) isolatedSessions.remove(target) else if (current) session = null
        if (!target.cleaned.compareAndSet(false, true)) {
            if (shouldNotify) target.onExit()
            return
        }
        var stopped = true
        try {
            stopped = target.process?.let(::endProcess) ?: true
        } catch (e: Exception) {
            stopped = false
            LogUtil.w(AppConfig.TAG, "GUTS: process cleanup failed", e)
        }
        target.protectServer?.close()
        if (stopped) {
            target.directory?.deleteRecursively()
        } else {
            // Do not erase the only diagnostic state while a child may still be alive.
            LogUtil.e(AppConfig.TAG, "GUTS: child is still alive after bounded teardown")
        }
        if (shouldNotify) target.onExit()
    }

    private fun endProcess(process: Process): Boolean {
        process.destroy()
        if (awaitUntil(EXIT_WAIT_MS) { !isAlive(process) }) return true
        process.destroyForcibly()
        awaitUntil(EXIT_WAIT_MS) { !isAlive(process) }
        return !isAlive(process)
    }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(EXIT_POLL_MS)
        }
        return condition()
    }

    /** Reaps only processes whose executable is this app's exact GUTS binary. */
    private fun reapStale(context: Context) {
        val expected = binary(context).absolutePath
        val stale = mutableListOf<Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val command = runCatching {
                String(File(entry, "cmdline").readBytes(), Charsets.UTF_8).substringBefore('\u0000')
            }.getOrNull() ?: return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if (command == expected && "$SESSION_ENV=1" in environment) stale += pid
        }
        stale.forEach { pid -> runCatching { android.os.Process.sendSignal(pid, OsConstants.SIGTERM) } }
        if (stale.isEmpty()) return
        if (awaitUntil(EXIT_WAIT_MS) { stale.none { File("/proc/$it").exists() } }) return
        stale.filter { File("/proc/$it").exists() }.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }
    }

    internal class Session(
        val context: Context,
        val core: GutsCore,
        val onExit: () -> Unit,
        val id: String = UUID.randomUUID().toString(),
        val isolated: Boolean = false,
    ) {
        @Volatile var process: Process? = null
        @Volatile var protectServer: MasterDnsProtectServer? = null
        @Volatile var directory: File? = null
        @Volatile var outputThread: Thread? = null
        @Volatile var watchThread: Thread? = null
        val cleaned = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
    }
}
