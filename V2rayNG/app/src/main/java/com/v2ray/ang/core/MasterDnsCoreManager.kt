package com.v2ray.ang.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.v2ray.ang.AppConfig
import com.v2ray.ang.fmt.MasterDnsFmt
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
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

/** Owns the one official MasterDnsVPN process used by the daemon's selected profile. */
object MasterDnsCoreManager {

    private const val BINARY_NAME = "libmasterdns.so"
    private const val COTTENDNS_BINARY_NAME = "libcottendns.so"
    private const val OWNER_ENV = "OMNI_MASTERDNS_OWNER"
    private const val SESSION_ENV = "OMNI_MASTERDNS_SESSION"
    private const val ISOLATED_ENV = "OMNI_MASTERDNS_ISOLATED"
    private const val KEY_ENV = "OMNI_MASTERDNS_KEY"
    private const val READY_POLL_MS = 250L
    private const val EXIT_WAIT_MS = 2_000L
    private const val EXIT_POLL_MS = 50L
    internal const val PORT_CHECK_WAIT_MS = 5_000L

    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MasterDnsLifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null
    private val isolatedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()

    /** True from the accepted start until that session exits or is superseded, including warm-up. */
    val isRunning: Boolean get() = session != null

    val currentPort: Int? get() = session?.core?.port

    val currentKey: String? get() = session?.core?.key

    internal data class LiveProcess(val pid: Int, val port: Int, val key: String?)

    /** Finds the app's official client without mistaking an unrelated loopback listener for it. */
    internal fun liveProcess(context: Context): LiveProcess? {
        val expected = listOf(binary(context).absolutePath, binary(context, MasterDnsFmt.ENGINE_COTTENDNS).absolutePath)
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val argv = readProcWords(File(entry, "cmdline")) ?: return@forEach
            if (argv.firstOrNull() !in expected) return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if ("$SESSION_ENV=1" !in environment || "$ISOLATED_ENV=1" in environment) return@forEach
            val port = argv.firstOrNull { it.startsWith("-listen-port=") }
                ?.substringAfter('=')?.toIntOrNull() ?: return@forEach
            val key = environment.firstOrNull { it.startsWith("$KEY_ENV=") }?.substringAfter('=')
            return LiveProcess(pid, port, key)
        }
        return null
    }

    private fun readProcWords(file: File): List<String>? = try {
        file.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Waits for queued process/protect cleanup, bounded so a failed native teardown cannot hang a test. */
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

    private fun binary(context: Context, engine: String = MasterDnsFmt.ENGINE_MASTERDNS): File = File(
        context.applicationInfo.nativeLibraryDir,
        if (engine == MasterDnsFmt.ENGINE_COTTENDNS) COTTENDNS_BINARY_NAME else BINARY_NAME,
    )

    fun isSupported(context: Context, engine: String = MasterDnsFmt.ENGINE_MASTERDNS): Boolean = binary(context, engine).canExecute()

    internal sealed interface PortCheck {
        data object Free : PortCheck
        data object Taken : PortCheck
        data class Unchecked(val cause: Exception? = null) : PortCheck
    }

    /**
     * Checks the loopback port before Xray starts and can send traffic to it. The bounded wait is
     * intentional leak prevention: an unrelated listener must never receive the profile's traffic.
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
        core: MasterDnsCore,
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

    /** Starts a scanner-owned child without replacing the foreground MasterDNS/CottenDNS session. */
    @Synchronized
    internal fun startIsolated(context: Context, core: MasterDnsCore, protect: ((Int) -> Boolean)? = null): Session {
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
            writeSessionFiles(target, directory)

            if (protect != null) {
                val socketName = "omni-masterdns-${android.os.Process.myPid()}-${target.id}"
                target.protectServer = MasterDnsProtectServer(socketName, protect) { error ->
                    LogUtil.w(AppConfig.TAG, "MasterDNS: protect server request failed", error)
                }
            }

            val process = ProcessBuilder(processArguments(target)).apply {
                directory(directory)
                redirectErrorStream(true)
                environment().apply {
                    put(OWNER_ENV, android.os.Process.myPid().toString())
                    put(SESSION_ENV, "1")
                    if (target.isolated) put(ISOLATED_ENV, "1")
                    put(KEY_ENV, target.core.key)
                    put("HOME", directory.absolutePath)
                    put("TMPDIR", target.context.cacheDir.absolutePath)
                }
            }.start()
            // Upstream asks for Enter after a startup error. Closing stdin turns that wait into
            // EOF, so the process exit reaches the service instead of leaving a false warm-up.
            runCatching { process.outputStream.close() }
            target.process = process
            target.outputThread = thread(name = "MasterDnsOutput", isDaemon = true) { forward(target, process) }
            target.watchThread = thread(name = "MasterDnsWatch", isDaemon = true) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    // finish still runs: the session no longer owns a live process to report.
                }
                finish(target, notifyExit = true)
            }
            LogUtil.i(AppConfig.TAG, "MasterDNS: official core started on ${AppConfig.LOOPBACK}:${target.core.port}")
            if (!owns(target)) finish(target, notifyExit = false)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "MasterDNS: core start failed", e)
            finish(target, notifyExit = true)
        }
    }

    /** Deterministic command line; the encryption key remains only in the private config file. */
    internal fun processArguments(target: Session): List<String> = processArguments(
        binary = binary(target.context, target.core.engine),
        config = File(target.directory, configFileName(target.core.config)),
        resolvers = File(target.directory, RESOLVERS_FILE),
        core = target.core,
        protectAddress = target.protectServer?.configAddress,
    )

    internal fun processArguments(
        binary: File,
        config: File,
        resolvers: File,
        core: MasterDnsCore,
        protectAddress: String?,
    ): List<String> = buildList {
        add(binary.absolutePath)
        add("-config=${config.absolutePath}")
        add("-resolvers=${resolvers.absolutePath}")
        add("-protocol-type=SOCKS5")
        add("-listen-ip=${AppConfig.LOOPBACK}")
        add("-listen-port=${core.port}")
        add("-socks5-auth=false")
        add("-local-dns-enabled=false")
        add("-local-dns-cache-persist-to-file=false")
        if (core.engine == MasterDnsFmt.ENGINE_COTTENDNS) {
            add("-startup-mode=resolvers")
            add("-terminal-ui=plain")
            add("-log-to-file=false")
        } else {
            add("-save-mtu-servers-to-file=false")
        }
        add("-fd-control-unix-socket=${protectAddress.orEmpty()}")
    }

    private const val RESOLVERS_FILE = "client_resolvers.txt"

    internal fun configFileName(raw: String): String =
        if (MasterDnsFmt.document(raw)?.format == MasterDnsFmt.Format.TOML) "client_config.toml" else "client_config.json"

    private fun sessionDirectory(target: Session): File {
        val directory = File(target.context.cacheDir, "masterdns/${target.id}")
        if (!directory.mkdirs() && !directory.isDirectory) error("Cannot create MasterDNS session directory")
        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        return directory
    }

    private fun writeSessionFiles(target: Session, directory: File) {
        writePrivate(File(directory, configFileName(target.core.config)), target.core.config + "\n")
        writePrivate(File(directory, RESOLVERS_FILE), MasterDnsFmt.normalizeResolvers(target.core.resolvers))
    }

    private fun writePrivate(file: File, text: String) {
        FileOutputStream(file, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
    }

    private fun forward(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> relay(target.core, line) }
            }
        } catch (e: Exception) {
            if (owns(target)) LogUtil.w(AppConfig.TAG, "MasterDNS: output reader ended unexpectedly", e)
        }
    }

    internal fun relay(core: MasterDnsCore, line: String) {
        val text = core.redact(line).trim()
        if (text.isEmpty()) return
        when {
            text.contains("ERROR", ignoreCase = true) || text.contains("FATAL", ignoreCase = true) ->
                LogUtil.e(AppConfig.TAG, "[masterdns] $text")
            text.contains("WARN", ignoreCase = true) -> LogUtil.w(AppConfig.TAG, "[masterdns] $text")
            else -> LogUtil.i(AppConfig.TAG, "[masterdns] $text")
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
            LogUtil.w(AppConfig.TAG, "MasterDNS: process cleanup failed", e)
        }
        target.protectServer?.close()
        if (stopped) {
            target.directory?.deleteRecursively()
        } else {
            // Do not erase the only diagnostic/config state while a child may still be alive.
            LogUtil.e(AppConfig.TAG, "MasterDNS: child is still alive after bounded teardown")
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

    /** Reaps only processes whose executable is this app's exact MasterDnsVPN binary. */
    private fun reapStale(context: Context) {
        val expected = listOf(binary(context).absolutePath, binary(context, MasterDnsFmt.ENGINE_COTTENDNS).absolutePath)
        val stale = mutableListOf<Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val command = runCatching {
                String(File(entry, "cmdline").readBytes(), Charsets.UTF_8).substringBefore('\u0000')
            }.getOrNull() ?: return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if (command in expected && "$SESSION_ENV=1" in environment) stale += pid
        }
        stale.forEach { pid -> runCatching { android.os.Process.sendSignal(pid, OsConstants.SIGTERM) } }
        if (stale.isEmpty()) return
        if (awaitUntil(EXIT_WAIT_MS) { stale.none { File("/proc/$it").exists() } }) return
        stale.filter { File("/proc/$it").exists() }.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }
    }

    internal class Session(
        val context: Context,
        val core: MasterDnsCore,
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
