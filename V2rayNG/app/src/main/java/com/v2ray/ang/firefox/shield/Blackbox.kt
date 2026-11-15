package com.v2ray.ang.firefox.shield

import com.v2ray.ang.AngApplication
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Blackbox Recorder — a small flight recorder for the Firefox path.
 *
 * It keeps the last few hundred events (connects, failovers, quota warnings, location switches,
 * errors) in a ring buffer and on disk, so a problem that happened in the pocket can be read back
 * afterwards. It records what happened, never what an account contains: no tokens, no passwords, no
 * browsing — only the events and their reasons.
 */
object Blackbox {

    private const val TAG = "Blackbox"
    private const val MAX_ENTRIES = 500
    private const val FILE_NAME = "firefox-blackbox.log"

    private val ring = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(): File = File(AngApplication.application.filesDir, FILE_NAME)

    /** Records one event with a short, non-secret detail. */
    @Synchronized
    fun record(event: String, detail: String = "") {
        val line = "${stamp.format(Date())}  $event  $detail".trim()
        ring.addLast(line)
        while (ring.size > MAX_ENTRIES) ring.removeFirst()
        runCatching { file().appendText(line + "\n") }
            .onFailure { LogUtil.d(TAG, "could not persist event: ${it.message}") }
    }

    /** The most recent events, newest last. */
    @Synchronized
    fun entries(): List<String> = ring.toList()

    /** Everything on disk, for sharing when something went wrong. */
    fun export(): String = runCatching { file().readText() }.getOrDefault(entries().joinToString("\n"))

    /** Forgets the recording, in memory and on disk. */
    @Synchronized
    fun clear() {
        ring.clear()
        runCatching { file().delete() }
    }
}
