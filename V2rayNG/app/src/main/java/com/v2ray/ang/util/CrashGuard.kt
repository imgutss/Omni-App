package com.v2ray.ang.util

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a failure on the phone instead of letting it empty the screen.
 *
 * A person who taps the status bar to check the connection is asking a question about the link they have.
 * Whatever goes wrong while that question is answered has to come back as an answer — a line in the logs,
 * a message on the status bar — and not as the app disappearing and the tunnel with it. So this guard
 * records every exception no other layer handled, in the process it happened in, and then:
 *
 * - on the main thread it keeps the loop alive, so the screen stays and the tunnel keeps running;
 * - anywhere else it simply records, because that thread was the only thing the failure could take;
 * - when the same process keeps failing in the same moment, it stops pretending and hands the failure to
 *   the platform, which is the honest thing to do for a process that cannot do its work at all.
 *
 * The traces it writes are plain text in the app's own private storage and are readable from the logs
 * screen, so a person can send the real reason instead of a description of it.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    /** How many traces of one process are kept; older ones are dropped. */
    private const val KEPT_TRACES = 8

    /** Above this many failures inside [RESET_WINDOW_MS], the guard stops keeping the loop alive. */
    private const val FAILURE_BUDGET = 5

    private const val RESET_WINDOW_MS = 3_000L

    private val failures = AtomicInteger(0)
    private var windowStartedAt = 0L

    @Volatile
    private var installed = false

    @Volatile
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Installs the guard for this process.
     *
     * Both the UI process and the daemon run [Application.onCreate] in their own process, so calling this
     * from there covers both: each one records its own failures under its own name.
     */
    @Synchronized
    fun install(app: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        defaultHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(app.applicationContext, thread, throwable, previous)
        }
    }

    private fun handle(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        val trace = render(thread, throwable)
        LogUtil.e(TAG, "Unhandled failure on ${thread.name}", throwable)
        runCatching { writeTrace(context, trace) }

        if (thread == Looper.getMainLooper()?.thread && !budgetSpent()) {
            // The failure came from a message the main loop was handling. The loop has already left its
            // dispatch, so the only way the screen comes back is to enter it again; every message after
            // this one is then handled as usual, with the tunnel untouched.
            while (true) {
                try {
                    Looper.loop()
                    return
                } catch (looped: Throwable) {
                    LogUtil.e(TAG, "Unhandled failure on the main loop", looped)
                    runCatching { writeTrace(context, render(thread, looped)) }
                    if (budgetSpent()) break
                }
            }
        }

        // Either the thread was not the main one — nothing else to keep — or this process has failed
        // too often to be kept alive honestly.
        previous?.uncaughtException(thread, throwable)
    }

    /** True when this process has failed more than [FAILURE_BUDGET] times inside [RESET_WINDOW_MS]. */
    private fun budgetSpent(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - windowStartedAt > RESET_WINDOW_MS) {
            windowStartedAt = now
            failures.set(0)
        }
        return failures.incrementAndGet() > FAILURE_BUDGET
    }

    private fun render(thread: Thread, throwable: Throwable): String {
        val body = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("at: $stamp")
            appendLine("process: ${processName()}")
            appendLine("thread: ${thread.name}")
            appendLine(throwable.toString())
            append(body)
        }
    }

    private fun processName(): String {
        val pid = Process.myPid()
        val manager = runCatching {
            File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault("")
        return manager.ifBlank { "pid-$pid" }
    }

    private fun writeTrace(context: Context, trace: String) {
        val directory = File(context.filesDir, "crash-traces")
        if (!directory.isDirectory && !directory.mkdirs()) return
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" +
            Process.myPid() + ".txt"
        File(directory, name).writeText(trace)
        val kept = directory.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.name }
            .orEmpty()
        kept.drop(KEPT_TRACES).forEach { it.delete() }
    }

    /**
     * The recorded traces of this process, newest first, each as plain text.
     *
     * A trace is a developer artifact and is never sent anywhere by itself: the logs screen shows it so the
     * person can read it, copy it, or attach it to a report of their own.
     */
    fun traces(context: Context): List<String> {
        val directory = File(context.filesDir, "crash-traces")
        val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: return emptyList()
        return files.mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
    }

    /** Removes every recorded trace; what happened stays gone, as the person asked. */
    fun clear(context: Context) {
        File(context.filesDir, "crash-traces").listFiles()?.forEach { it.delete() }
    }
}
