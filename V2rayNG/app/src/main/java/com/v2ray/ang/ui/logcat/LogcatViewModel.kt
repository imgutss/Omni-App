package com.v2ray.ang.ui.logcat

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

class LogcatViewModel(application: Application) : BaseViewModel(application) {
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var currentFilter: String = ""

    private val _filteredLogs = MutableStateFlow<List<String>>(emptyList())
    val filteredLogs: StateFlow<List<String>> = _filteredLogs.asStateFlow()
    private val _readSucceeded = MutableStateFlow(false)
    val readSucceeded: StateFlow<Boolean> = _readSucceeded.asStateFlow()
    val logEntries: StateFlow<List<LogcatEntry>> = filteredLogs
        .map(::createLogcatEntries)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val updates = Mutex()
    private var pendingUpdates = 0
    @Volatile private var refreshJob: Job? = null

    private fun updateLogSnapshot(action: suspend () -> Unit): Job = viewModelScope.launch {
        pendingUpdates++; _isLoading.value = true
        try { updates.withLock { action() } }
        finally { pendingUpdates--; _isLoading.value = pendingUpdates > 0 }
    }

    fun pauseLogRefresh() { refreshJob?.cancel() }

    fun loadLogcat() {
        if (refreshJob?.isActive == true) return
        refreshJob = updateLogSnapshot {
            _readSucceeded.value = false
            try {
                // OS I/O stays off the UI thread. Publication/filtering stay serialized on Main.
                val allText = withContext(Dispatchers.IO) {
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-s", "GoLog", AppConfig.ANG_PACKAGE, "AndroidRuntime", "System.err", "OmniReferenceGL"))
                    try {
                        val lines=process.inputStream.bufferedReader().use { it.readLines() }.reversed()
                        val status=process.waitFor()
                        if(status!=0)throw IOException("Logcat read failed with exit status $status")
                        lines
                    }
                    finally { process.destroy() }
                }
                logsetsAll.clear(); logsetsAll.addAll(allText); applyFilter()
                _readSucceeded.value = true
            } catch (e: IOException) {
                LogUtil.e(AppConfig.TAG, "Failed to get logcat", e)
            }
        }
    }

    fun copyLogcat() {
        val all = filteredLogs.value.joinToString("\n")
        Utils.setClipboard(app, all)
        toast(R.string.toast_success)
    }

    fun clearLogcat() {
        pauseLogRefresh()
        updateLogSnapshot {
            try {
                val status = withContext(Dispatchers.IO) {
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                    try { process.waitFor() } finally { process.destroy() }
                }
                if (status == 0) { logsetsAll.clear(); applyFilter() }
                else LogUtil.w(AppConfig.TAG, "Logcat refused to clear its buffer")
            } catch (e: IOException) {
                LogUtil.e(AppConfig.TAG, "Failed to clear logcat", e)
            }
        }
    }

    fun filter(content: String?) {
        currentFilter = content?.trim() ?: ""
        applyFilter()
    }

    private fun applyFilter() {
        _filteredLogs.value = if (currentFilter.isEmpty()) {
            logsetsAll.toList()
        } else {
            logsetsAll.filter { it.contains(currentFilter, ignoreCase = true) }
        }
    }
}

data class LogcatEntry(val key: String, val text: String)

internal fun createLogcatEntries(logs: List<String>): List<LogcatEntry> {
    val occurrences = HashMap<String, Int>()
    // Identical lines are valid. Count oldest-first so filtering other lines or
    // prepending newer entries does not change the keys of existing occurrences.
    return logs.asReversed().map { line ->
        val occurrence = occurrences.getOrDefault(line, 0)
        occurrences[line] = occurrence + 1
        LogcatEntry("$occurrence:$line", line)
    }.asReversed()
}
