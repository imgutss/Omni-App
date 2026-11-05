package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real HTTP tests must launch the native client, never measure an empty SOCKS listener. */
object QuicDelayTester {
    private val clients = Mutex()
    suspend fun measureVia(context: Context, core: QuicCore, samples: Int,
                           probe: () -> Long): List<Long> = clients.withLock {
        val count = samples.coerceIn(1, 5)
        if (!QuicCoreManager.isSupported(context, core.engine)) return@withLock List(count) { 0L }
        if (withContext(Dispatchers.IO) { QuicCoreManager.matchesLiveProcess(context, core) }) {
            return@withLock withContext(Dispatchers.IO) { List(count) { ensureActive(); probe() } }
        }
        // Do not run an unprotected second transport under a VPN or steal a live core's port.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } ||
            !withContext(Dispatchers.IO) { QuicCoreManager.portFree(core.port) }) return@withLock List(count) { 0L }
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "native-test/${UUID.randomUUID()}")
            check(directory.mkdirs())
            Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
            var bridge: NaiveTcpBridge? = null
            var process: Process? = null
            try {
                if (core.engine == QuicCore.Engine.NAIVE) bridge = NaiveTcpBridge(context,
                    core.profile.server.orEmpty(), core.profile.serverPort!!.toInt(), null)
                val file = File(directory, core.engine.configName)
                file.writeText(core.configText(null, bridge?.port ?: core.port + 1))
                Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
                val target = QuicCoreManager.Session(context, core, {}).apply { this.directory = directory }
                val child = ProcessBuilder(QuicCoreManager.processArguments(target, file))
                    .directory(directory).redirectErrorStream(true).apply {
                        environment()["HOME"] = directory.absolutePath
                        environment()["TMPDIR"] = context.cacheDir.absolutePath
                    }.start()
                process = child
                child.outputStream.close()
                thread(name = "NativeTestOutput", isDaemon = true) {
                    runCatching { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { _ -> } } }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var ready = false
                while (System.nanoTime() < deadline && child.isAlive) {
                    ensureActive()
                    if (QuicCoreManager.answersSocks(core.port)) { ready = true; break }
                    delay(150)
                }
                if (!ready) List(count) { -1L }
                else List(count) { ensureActive(); probe() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                List(count) { -1L }
            } finally {
                withContext(NonCancellable) {
                    process?.let { child ->
                        child.destroy()
                        if (!child.waitFor(2, TimeUnit.SECONDS)) {
                            child.destroyForcibly()
                            child.waitFor(2, TimeUnit.SECONDS)
                        }
                    }
                    bridge?.close()
                    if (process?.isAlive != true) directory.deleteRecursively()
                }
            }
        }
    }
}
