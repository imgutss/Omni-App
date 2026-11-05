package com.v2ray.ang.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.v2ray.ang.AppConfig
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
 * Owns the one QUIC-family sidecar process the daemon's selected profile needs: a sing-box client
 * for TUIC v5 / AmneziaWG, or the official shadowquic client for ShadowQUIC.
 *
 * The client opens a SOCKS5 listener on the loopback and Xray dials it as an ordinary SOCKS
 * outbound. This manager adds only what a phone needs and a desktop does not: a protected socket
 * (see [SidecarProtectServer]) so the client's own calls are not routed back into the VPN it feeds,
 * and a private session directory so its configuration and credentials never touch a command line.
 */
object QuicCoreManager {

    private const val SESSION_ENV = "OMNI_QUIC_SESSION"
    private const val IDENTITY_ENV = "OMNI_QUIC_IDENTITY"
    private const val READY_POLL_MS = 250L
    private const val EXIT_WAIT_MS = 2_000L
    private const val EXIT_POLL_MS = 50L
    internal const val PORT_CHECK_WAIT_MS = 5_000L

    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QuicLifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null

    /** True from the accepted start until that session exits or is superseded, including warm-up. */
    val isRunning: Boolean get() = session != null

    val currentPort: Int? get() = session?.core?.port

    val currentIdentity: String? get() = session?.core?.identity

    internal fun binary(context: Context, engine: QuicCore.Engine): File =
        File(context.applicationInfo.nativeLibraryDir, engine.binaryName)

    fun isSupported(context: Context, engine: QuicCore.Engine): Boolean = binary(context, engine).canExecute()

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
    fun start(context: Context, core: QuicCore, protect: ((Int) -> Boolean)?, onExit: () -> Unit) {
        stop()
        val next = Session(context.applicationContext, core, onExit)
        session = next
        lifecycle.execute { open(next, protect) }
    }

    /** Stops the session and prevents its later exit from being reported as an unexpected failure. */
    @Synchronized
    fun stop() {
        val current = session ?: return
        current.stopRequested.set(true)
        session = null
        lifecycle.execute { finish(current, notifyExit = false) }
    }

    private fun owns(target: Session): Boolean = session === target && !target.stopRequested.get()

    private fun open(target: Session, protect: ((Int) -> Boolean)?) {
        try {
            if (!owns(target)) return
            reapStale(target.context)
            val directory = sessionDirectory(target)
            target.directory = directory

            if (protect != null) {
                target.protectServer = SidecarProtectServer(File(directory, "protect.sock"), protect) { error ->
                    LogUtil.w(AppConfig.TAG, "QUIC: protect server request failed", error)
                }
            }

            val configFile = File(directory, target.core.engine.configName)
            configFile.writeText(target.core.configText(target.protectServer?.configPath))
            Os.chmod(configFile.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)

            val process = ProcessBuilder(processArguments(target, configFile)).apply {
                directory(directory)
                redirectErrorStream(true)
                environment().apply {
                    put(SESSION_ENV, "1")
                    put(IDENTITY_ENV, target.core.identity)
                    put("HOME", directory.absolutePath)
                    put("TMPDIR", target.context.cacheDir.absolutePath)
                }
            }.start()
            runCatching { process.outputStream.close() }
            target.process = process
            target.outputThread = thread(name = "QuicOutput", isDaemon = true) { forward(target, process) }
            target.watchThread = thread(name = "QuicWatch", isDaemon = true) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                }
                finish(target, notifyExit = true)
            }
            LogUtil.i(AppConfig.TAG, "QUIC: ${target.core.engine} client started on ${AppConfig.LOOPBACK}:${target.core.port}")
            if (!owns(target)) finish(target, notifyExit = false)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "QUIC: client start failed", e)
            finish(target, notifyExit = true)
        }
    }

    internal fun processArguments(target: Session, configFile: File): List<String> {
        val binary = binary(target.context, target.core.engine).absolutePath
        return when (target.core.engine) {
            QuicCore.Engine.SING_BOX -> listOf(binary, "run", "-c", configFile.absolutePath, "-D", target.directory?.absolutePath.orEmpty(), "--disable-color")
            QuicCore.Engine.SHADOW_QUIC -> listOf(binary, "-c", configFile.absolutePath)
        }
    }

    private fun sessionDirectory(target: Session): File {
        val directory = File(target.context.cacheDir, "quic/${target.id}")
        if (!directory.mkdirs() && !directory.isDirectory) error("Cannot create QUIC session directory")
        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        return directory
    }

    private fun forward(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> relay(target.core, line) }
            }
        } catch (e: Exception) {
            if (owns(target)) LogUtil.w(AppConfig.TAG, "QUIC: output reader ended unexpectedly", e)
        }
    }

    internal fun relay(core: QuicCore, line: String) {
        val text = core.redact(line).trim()
        if (text.isEmpty()) return
        when {
            text.contains("ERROR", ignoreCase = true) || text.contains("FATAL", ignoreCase = true) ->
                LogUtil.e(AppConfig.TAG, "[quic] $text")
            text.contains("WARN", ignoreCase = true) -> LogUtil.w(AppConfig.TAG, "[quic] $text")
            else -> LogUtil.i(AppConfig.TAG, "[quic] $text")
        }
    }

    /** Waits for a real SOCKS5 no-auth greeting, not merely a running process or an open TCP port. */
    suspend fun awaitListening(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (session != null) {
            val current = session ?: return@withTimeoutOrNull false
            val process = current.process
            if (process != null && !isAlive(process)) return@withTimeoutOrNull false
            if (process != null && withContext(Dispatchers.IO) { answersSocks(current.core.port) }) {
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
        val owned = session === target
        val shouldNotify = notifyExit && owned && !target.stopRequested.get()
        if (owned) session = null
        if (!target.cleaned.compareAndSet(false, true)) {
            if (shouldNotify) target.onExit()
            return
        }
        var stopped = true
        try {
            stopped = target.process?.let(::endProcess) ?: true
        } catch (e: Exception) {
            stopped = false
            LogUtil.w(AppConfig.TAG, "QUIC: process cleanup failed", e)
        }
        target.protectServer?.close()
        if (stopped) {
            target.directory?.deleteRecursively()
        } else {
            LogUtil.e(AppConfig.TAG, "QUIC: child is still alive after bounded teardown")
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

    /** Reaps only processes whose executable is one of this app's exact QUIC sidecar binaries. */
    private fun reapStale(context: Context) {
        val expected = QuicCore.Engine.entries.map { binary(context, it).absolutePath }.toSet()
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

    private fun readProcWords(file: File): List<String>? = try {
        file.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    internal class Session(
        val context: Context,
        val core: QuicCore,
        val onExit: () -> Unit,
        val id: String = UUID.randomUUID().toString(),
    ) {
        @Volatile var process: Process? = null
        @Volatile var protectServer: SidecarProtectServer? = null
        @Volatile var directory: File? = null
        @Volatile var outputThread: Thread? = null
        @Volatile var watchThread: Thread? = null
        val cleaned = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
    }
}
