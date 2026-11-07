package com.v2ray.ang.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Owns the selected DNSTT, NoizDNS, VayDNS, or Slipstream process and its local layers. */
object DnsttCoreManager {
    private const val OFFICIAL_BINARY_NAME = "libdnstt.so"
    private const val NOIZDNS_BINARY_NAME = "libnoizdns.so"
    private const val VAYDNS_BINARY_NAME = "libvaydns.so"
    private const val SLIPSTREAM_BINARY_NAME = "libslipstream.so"
    private const val SOAR_BINARY_NAME = "libsoar.so"
    private const val SOAR_RESOLVERS_FILE = "soar_resolvers.txt"
    private const val SOAR_SETTINGS_FILE = "soar_settings.json"
    private const val SESSION_ENV = "OMNI_DNSTT_SESSION"
    private const val ISOLATED_ENV = "OMNI_DNSTT_ISOLATED"
    private const val KEY_ENV = "OMNI_DNSTT_KEY"
    private const val PORT_ENV = "OMNI_DNSTT_ENGINE_PORT"
    private const val SOCKS_USER_ENV = "OMNI_DNSTT_SOCKS_USER"
    private const val SOCKS_PASSWORD_ENV = "OMNI_DNSTT_SOCKS_PASSWORD"
    private const val READY_POLL_MS = 250L
    private const val EXIT_WAIT_MS = 2_000L
    private const val EXIT_POLL_MS = 50L
    internal const val PORT_CHECK_WAIT_MS = 5_000L

    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DnsttLifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null
    private val isolatedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()

    val isRunning: Boolean get() = session != null
    val currentPort: Int? get() = session?.core?.port
    val currentKey: String? get() = session?.core?.key

    /**
     * A port chosen after another application was found on the default listener. The override is
     * keyed by the profile's default core identity, so the Xray outbound and the native process
     * continue to agree on reloads and delay tests instead of each picking a different port.
     */
    private val portOverrides = ConcurrentHashMap<String, Int>()
    private const val PORT_OVERRIDE_PREFIX = "dnstt.runtime.port."

    internal fun coreOf(profile: ProfileItem): DnsttCore {
        val base = DnsttCore.of(profile)
        val override = portOverrides[base.key] ?: readSharedOverride(base.key) ?: return base
        portOverrides[base.key] = override
        return DnsttCore.of(profile, override)
    }

    /** MMKV is multi-process; a test in the UI must see the daemon's selected loopback port. */
    private fun readSharedOverride(key: String): Int? = runCatching {
        MmkvManager.decodeSettingsInt(PORT_OVERRIDE_PREFIX + key, 0)
            .takeIf { it in 1024..65534 }
    }.getOrNull()

    private fun rememberOverride(key: String, port: Int) {
        portOverrides[key] = port
        // Keep the running session usable if storage is temporarily unavailable; the next process
        // can still recover by checking ownership, and no credential or profile data is written here.
        runCatching { MmkvManager.encodeSettings(PORT_OVERRIDE_PREFIX + key, port) }
    }

    internal data class PreparedSession(val core: DnsttCore, val content: String)

    internal data class LiveProcess(val pid: Int, val port: Int, val key: String?)

    internal fun liveProcess(context: Context): LiveProcess? {
        val expected = listOf(
            binary(context, DnsttFmt.ENGINE_OFFICIAL).absolutePath,
            binary(context, DnsttFmt.ENGINE_NOIZDNS).absolutePath,
            binary(context, DnsttFmt.ENGINE_VAYDNS).absolutePath,
            binary(context, DnsttFmt.ENGINE_SLIPSTREAM).absolutePath,
            binary(context, DnsttFmt.ENGINE_SOAR).absolutePath,
        )
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val argv = readProcWords(File(entry, "cmdline")) ?: return@forEach
            if (argv.firstOrNull() !in expected) return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if ("$SESSION_ENV=1" !in environment || "$ISOLATED_ENV=1" in environment) return@forEach
            val port = environment.firstOrNull { it.startsWith("$PORT_ENV=") }
                ?.substringAfter('=')?.toIntOrNull()
                ?: argv.lastOrNull()?.substringAfterLast(':')?.toIntOrNull()
                ?: return@forEach
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

    private fun binary(context: Context, engine: String = DnsttFmt.ENGINE_OFFICIAL): File {
        val name = when (engine) {
            DnsttFmt.ENGINE_NOIZDNS, DnsttFmt.ENGINE_DNSTT -> NOIZDNS_BINARY_NAME
            DnsttFmt.ENGINE_VAYDNS -> VAYDNS_BINARY_NAME
            DnsttFmt.ENGINE_SLIPSTREAM -> SLIPSTREAM_BINARY_NAME
            DnsttFmt.ENGINE_SOAR -> SOAR_BINARY_NAME
            else -> OFFICIAL_BINARY_NAME
        }
        return File(context.applicationInfo.nativeLibraryDir, name)
    }

    fun isSupported(context: Context, engine: String = DnsttFmt.ENGINE_OFFICIAL): Boolean =
        binary(context, engine).canExecute()

    internal sealed interface PortCheck {
        data object Free : PortCheck
        data object Taken : PortCheck
        data class Unchecked(val cause: Exception? = null) : PortCheck
    }

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

    /**
     * Reclaims our orphaned process before looking at the port, then keeps the default when it is
     * usable. If another app owns it, a free alternate is chosen and the one matching Xray SOCKS
     * outbound is changed with it. Nothing is ever dialed on a listener whose ownership is unknown.
     */
    internal fun prepareSession(
        context: Context,
        core: DnsttCore,
        content: String,
        reservedPorts: Set<Int> = emptySet(),
    ): PreparedSession? {
        val appContext = context.applicationContext
        val task = FutureTask(Callable<PreparedSession?> {
            reapStale(appContext)
            val ports = core.sessionPorts
            if (ports.all { available(it, content, reservedPorts) }) {
                return@Callable PreparedSession(core, content)
            }
            val replacement = findReplacement(core, content, reservedPorts) ?: return@Callable null
            val rewritten = withDnsttPort(content, core.port, replacement) ?: return@Callable null
            val rebound = core.copy(port = replacement)
            rememberOverride(core.copy(port = DnsttCore.DEFAULT_SOCKS_PORT).key, replacement)
            LogUtil.w(AppConfig.TAG, "DNSTT: default listener unavailable; using app-owned loopback port $replacement")
            PreparedSession(rebound, rewritten)
        })
        lifecycle.execute(task)
        return try {
            task.get(PORT_CHECK_WAIT_MS + EXIT_WAIT_MS * 3, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            task.cancel(true)
            LogUtil.w(AppConfig.TAG, "DNSTT: session preflight did not complete", e)
            null
        }
    }

    private fun available(port: Int, content: String, reservedPorts: Set<Int>): Boolean =
        port !in reservedPorts && !AetherDependency.inboundListensOn(content, port) && portFree(port)

    private fun findReplacement(core: DnsttCore, content: String, reservedPorts: Set<Int>): Int? {
        repeat(64) {
            val candidate = runCatching { Utils.findRandomFreePort() }.getOrNull() ?: return@repeat
            if (candidate !in 1024..65534 || candidate == core.port || candidate == core.tunnelPort) return@repeat
            val ports = core.copy(port = candidate).sessionPorts
            if (ports.all { available(it, content, reservedPorts) }) return candidate
        }
        return null
    }

    /** Rewrites exactly one loopback DNSTT SOCKS outbound; null when the JSON is ambiguous. */
    internal fun withDnsttPort(content: String, oldPort: Int, newPort: Int): String? = try {
        val root = JsonParser.parseString(content).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val matches = outbounds.mapNotNull { element ->
            val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (outbound.get("protocol")?.asString != "socks") return@mapNotNull null
            val settings = outbound.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (settings.get("address")?.asString != AppConfig.LOOPBACK || settings.get("port")?.asInt != oldPort) {
                return@mapNotNull null
            }
            settings
        }
        if (matches.size != 1) return null
        matches.single().addProperty("port", newPort)
        JsonUtil.toJson(root)
    } catch (_: Exception) {
        null
    }

    /** Runs stale-process cleanup for a delay test before its ownership check. */
    internal fun reapStaleProcesses(context: Context): Boolean {
        val task = FutureTask(Callable {
            reapStale(context.applicationContext)
            Unit
        })
        lifecycle.execute(task)
        return try {
            task.get(PORT_CHECK_WAIT_MS + EXIT_WAIT_MS * 3, TimeUnit.MILLISECONDS)
            true
        } catch (e: Exception) {
            task.cancel(true)
            LogUtil.w(AppConfig.TAG, "DNSTT: stale-process cleanup did not complete", e)
            false
        }
    }

    @Synchronized
    fun start(
        context: Context,
        core: DnsttCore,
        protect: ((Int) -> Boolean)?,
        reapStaleProcesses: Boolean = true,
        onExit: () -> Unit,
    ) {
        stop()
        val next = Session(context.applicationContext, core, onExit)
        session = next
        lifecycle.execute { open(next, protect, reapStaleProcesses) }
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        current.stopRequested.set(true)
        session = null
        DnsttSshTunnel.stop(current.core.key)
        SlipstreamSocksBridge.stop(current.core.key)
        lifecycle.execute { finish(current, notifyExit = false) }
    }

    /** Scanner-only process. It never changes the foreground connection's selected Session. */
    @Synchronized
    internal fun startIsolated(context: Context, core: DnsttCore, protect: ((Int) -> Boolean)? = null): Session {
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
            if (target.core.engine == DnsttFmt.ENGINE_SOAR) writeSoarSessionFiles(target.core, directory)
            if (protect != null) {
                val socketName = "omni-dnstt-${android.os.Process.myPid()}-${target.id}"
                target.protectServer = MasterDnsProtectServer(socketName, protect) { error ->
                    LogUtil.w(AppConfig.TAG, "DNSTT: protect server request failed", error)
                }
            }
            val process = ProcessBuilder(processArguments(target)).apply {
                directory(directory)
                redirectErrorStream(true)
                environment().apply {
                    put(SESSION_ENV, "1")
                    if (target.isolated) put(ISOLATED_ENV, "1")
                    put(KEY_ENV, target.core.key)
                    put("HOME", directory.absolutePath)
                    put("TMPDIR", target.context.cacheDir.absolutePath)
                    put(PORT_ENV, target.core.enginePort.toString())
                    target.protectServer?.configAddress?.let { address ->
                        if (target.core.engine == DnsttFmt.ENGINE_SLIPSTREAM) {
                            put("OMNI_FD_CONTROL_UNIX_SOCKET", address)
                        }
                    }
                    if (target.core.engine != DnsttFmt.ENGINE_SLIPSTREAM &&
                        target.core.ssh == null && target.core.socksUsername.isNotEmpty()) {
                        put(SOCKS_USER_ENV, target.core.socksUsername)
                        put(SOCKS_PASSWORD_ENV, target.core.socksPassword)
                    }
                }
            }.start()
            runCatching { process.outputStream.close() }
            target.process = process
            target.outputThread = thread(name = "DnsttOutput", isDaemon = true) { forward(target, process) }
            target.watchThread = thread(name = "DnsttWatch", isDaemon = true) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    // finish still runs below.
                }
                finish(target, notifyExit = true)
            }
            if (target.core.ssh == null && target.core.engine == DnsttFmt.ENGINE_SLIPSTREAM) {
                SlipstreamSocksBridge.start(target.core) { error ->
                    LogUtil.e(AppConfig.TAG, "Slipstream SOCKS bridge failed", error)
                    lifecycle.execute {
                        if (owns(target)) finish(target, notifyExit = true)
                    }
                }
            }
            if (target.core.ssh != null) {
                DnsttSshTunnel.start(target.core) { error ->
                    LogUtil.e(AppConfig.TAG, "DNSTT+SSH: chained session failed", error)
                    lifecycle.execute {
                        if (owns(target)) finish(target, notifyExit = true)
                    }
                }
            }
            LogUtil.i(AppConfig.TAG, "DNSTT: ${target.core.engine} core started on ${AppConfig.LOOPBACK}:${target.core.enginePort}")
            if (!owns(target)) finish(target, notifyExit = false)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "DNSTT: core start failed", e)
            finish(target, notifyExit = true)
        }
    }

    internal fun processArguments(target: Session): List<String> = processArguments(
        binary = binary(target.context, target.core.engine),
        core = target.core,
        protectAddress = target.protectServer?.configAddress,
    )

    internal fun processArguments(binary: File, core: DnsttCore, protectAddress: String?): List<String> = buildList {
        add(binary.absolutePath)
        when (core.engine) {
            DnsttFmt.ENGINE_SLIPSTREAM -> {
                add("--tcp-listen-host=${AppConfig.LOOPBACK}")
                add("--tcp-listen-port=${core.enginePort}")
                add("--domain=${core.domain}")
                add("--congestion-control=${core.slipstream.congestionControl}")
                add("--keep-alive-interval=${core.slipstream.keepAliveIntervalMs}")
                if (core.slipstream.gsoEnabled) add("--gso")
                val resolvers = core.resolver.split(',')
                val flags = when {
                    core.resolverAuthorities.size == resolvers.size -> core.resolverAuthorities
                    core.authoritative -> List(resolvers.size) { true }
                    else -> List(resolvers.size) { false }
                }
                resolvers.forEachIndexed { index, resolver ->
                    add(if (flags[index]) "--authoritative=$resolver" else "--resolver=$resolver")
                }
            }
            DnsttFmt.ENGINE_VAYDNS -> {
                when (core.transport) {
                    DnsttFmt.Transport.DOH -> add("-doh=${core.resolver}")
                    DnsttFmt.Transport.DOT -> add("-dot=${core.resolver}")
                    DnsttFmt.Transport.UDP -> add("-udp=${core.resolver}")
                    DnsttFmt.Transport.TCP -> add("-tcp=${core.resolver}")
                }
                add("-pubkey=${core.publicKey}")
                if (core.utls.isNotBlank()) add("-utls=${core.utls}")
                add("-domain=${core.domain}")
                add("-listen=${AppConfig.LOOPBACK}:${core.enginePort}")
                if (core.ssh != null) add("-raw-tcp")
                if (core.ssh == null) {
                    add("-socks-listen=${AppConfig.LOOPBACK}:${core.port}")
                }
                if (core.vaydns.dnsttCompat) add("-dnstt-compat")
                add("-record-type=${core.vaydns.recordType}")
                add("-max-qname-len=${core.vaydns.maxQnameLen}")
                add("-rps=${core.vaydns.rps}")
                if (core.vaydns.idleTimeoutSeconds > 0) add("-idle-timeout=${core.vaydns.idleTimeoutSeconds}")
                if (core.vaydns.keepAliveSeconds > 0) add("-keep-alive=${core.vaydns.keepAliveSeconds}")
                if (core.vaydns.udpTimeoutMs > 0) add("-udp-timeout=${core.vaydns.udpTimeoutMs}")
                if (core.vaydns.maxNumLabels > 0) add("-max-num-labels=${core.vaydns.maxNumLabels}")
                if (core.vaydns.clientIdSize > 0) add("-clientid-size=${core.vaydns.clientIdSize}")
                if (core.maxPayload > 0) add("-max-payload=${core.maxPayload}")
                add("-resolver-mode=${core.resolverMode}")
                add("-rr-spread=${core.rrSpreadCount}")
            }
            DnsttFmt.ENGINE_SOAR -> {
                add("-domain=${core.domain}")
                add("-public-key=${core.publicKey}")
                add("-resolvers=$SOAR_RESOLVERS_FILE")
                add("-settings=$SOAR_SETTINGS_FILE")
                add("-listen=${AppConfig.LOOPBACK}:${core.tunnelPort}")
            }
            DnsttFmt.ENGINE_NOIZDNS, DnsttFmt.ENGINE_DNSTT -> {
                val engineResolver = DnsttFmt.engineResolver(core.resolver)
                if (core.resolverAuthorities.isNotEmpty()) {
                    add("-resolver-authoritative=${core.resolverAuthorities.joinToString(",") { if (it) "1" else "0" }}")
                }
                if (core.ssh != null) add("-raw-tcp")
                when (core.transport) {
                    DnsttFmt.Transport.DOH -> add("-doh=$engineResolver")
                    DnsttFmt.Transport.DOT -> add("-dot=$engineResolver")
                    DnsttFmt.Transport.UDP -> add("-udp=$engineResolver")
                    DnsttFmt.Transport.TCP -> add("-tcp=$engineResolver")
                }
                add("-pubkey=${core.publicKey}")
                if (core.utls.isNotBlank()) add("-utls=${core.utls}")
                if (core.authoritative) add("-authoritative")
                if (core.noiz) add("-noiz")
                if (core.stealth) add("-stealth")
                if (core.maxPayload > 0) add("-max-payload=${core.maxPayload}")
                add("-resolver-mode=${core.resolverMode}")
                add("-rr-spread=${core.rrSpreadCount}")
            }
            else -> {
                add("-${core.transport.type}=${core.resolver}")
                add("-pubkey=${core.publicKey}")
                if (core.utls.isNotBlank()) add("-utls=${core.utls}")
            }
        }
        if (protectAddress != null && core.engine != DnsttFmt.ENGINE_SLIPSTREAM) {
            add("-fd-control-unix-socket=$protectAddress")
        }
        if (core.engine != DnsttFmt.ENGINE_VAYDNS && core.engine != DnsttFmt.ENGINE_SLIPSTREAM && core.engine != DnsttFmt.ENGINE_SOAR) {
            add(core.domain)
            add("${AppConfig.LOOPBACK}:${core.tunnelPort}")
        }
    }

    private fun writeSoarSessionFiles(core: DnsttCore, directory: File) {
        val resolvers = core.resolver.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        writePrivate(File(directory, SOAR_RESOLVERS_FILE), resolvers.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" })
        writePrivate(File(directory, SOAR_SETTINGS_FILE), core.soarSettings + "\n")
    }

    private fun writePrivate(file: File, text: String) {
        FileOutputStream(file, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
    }

    private fun sessionDirectory(target: Session): File {
        val directory = File(target.context.cacheDir, "dnstt/${target.id}")
        if (!directory.mkdirs() && !directory.isDirectory) error("Cannot create DNSTT session directory")
        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        return directory
    }

    private fun forward(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val text = DnsttFmt.redact(line)
                    if (text.isNotEmpty()) LogUtil.i(AppConfig.TAG, "[dnstt] $text")
                }
            }
        } catch (e: Exception) {
            if (owns(target)) LogUtil.w(AppConfig.TAG, "DNSTT: output reader ended unexpectedly", e)
        }
    }

    /** Readiness requires a real no-auth SOCKS5 response through the remote tunnel end. */
    suspend fun awaitListening(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (session != null) {
            val current = session ?: return@withTimeoutOrNull false
            val process = current.process
            if (process != null && !isAlive(process)) return@withTimeoutOrNull false
            val sshReady = current.core.ssh == null || DnsttSshTunnel.isReady(current.core.key)
            if (process != null && sshReady && withContext(Dispatchers.IO) { answersSocks(current.core.port) }) {
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
                val sshReady = target.core.ssh == null || DnsttSshTunnel.isReady(target.core.key)
                if (process != null && sshReady && withContext(Dispatchers.IO) { answersSocks(target.core.port) }) {
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
        DnsttSshTunnel.stop(target.core.key)
        SlipstreamSocksBridge.stop(target.core.key)
        try {
            stopped = target.process?.let(::endProcess) ?: true
        } catch (e: Exception) {
            stopped = false
            LogUtil.w(AppConfig.TAG, "DNSTT: process cleanup failed", e)
        }
        target.protectServer?.close()
        if (stopped) {
            target.directory?.deleteRecursively()
        } else {
            LogUtil.e(AppConfig.TAG, "DNSTT: child is still alive after bounded teardown")
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

    private fun reapStale(context: Context) {
        val expected = listOf(
            binary(context, DnsttFmt.ENGINE_OFFICIAL).absolutePath,
            binary(context, DnsttFmt.ENGINE_NOIZDNS).absolutePath,
            binary(context, DnsttFmt.ENGINE_VAYDNS).absolutePath,
            binary(context, DnsttFmt.ENGINE_SLIPSTREAM).absolutePath,
            binary(context, DnsttFmt.ENGINE_SOAR).absolutePath,
        )
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
        val killed = stale.filter { File("/proc/$it").exists() }
        killed.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }
        if (killed.isNotEmpty()) {
            awaitUntil(EXIT_WAIT_MS) { killed.none { File("/proc/$it").exists() } }
        }
    }

    internal class Session(
        val context: Context,
        val core: DnsttCore,
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
