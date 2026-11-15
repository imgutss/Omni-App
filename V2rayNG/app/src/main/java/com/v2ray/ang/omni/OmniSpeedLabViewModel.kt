package com.v2ray.ang.omni

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.DnsttDelayTester
import com.v2ray.ang.core.GutsDelayTester
import com.v2ray.ang.core.MasterDnsDelayTester
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.omni.lab.OmniTransferLab
import com.v2ray.ang.omni.lab.OmniTransferResult
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class OmniSpeedLabViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniSpeedLabRepository()
    private val mutableState = MutableStateFlow(OmniSpeedLabUiState())
    val state: StateFlow<OmniSpeedLabUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var speedJob: Job? = null
    @Volatile private var pingWorker: RealPingWorkerService? = null
    @Volatile private var activeRuntime: OmniNativeCoreRuntime? = null

    init { refresh() }

    fun refresh() {
        if (mutableState.value.speedRunning || mutableState.value.pingRunning || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val subscriptions = repository.loadSubscriptions()
                mutableState.update { current ->
                    val selectedId = current.selectedSubscriptionId
                        ?.takeIf { id -> subscriptions.any { it.id == id } }
                        ?: subscriptions.firstOrNull()?.id
                    val selected = subscriptions.firstOrNull { it.id == selectedId }
                    current.copy(
                        subscriptions = subscriptions,
                        selectedSubscriptionId = selectedId,
                        results = selected?.profiles.orEmpty().map { OmniSpeedProfileResult(it) },
                        pingResults = emptyMap(),
                        loading = false,
                        completedCount = 0,
                        plannedDataBytes = plannedData(current, selected?.profiles?.size ?: 0),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = OmniSpeedLabMessage.LOAD_FAILED) }
            }
        }
    }

    fun selectSubscription(id: String) {
        val current = mutableState.value
        if (current.speedRunning || current.pingRunning) return
        val selected = current.subscriptions.firstOrNull { it.id == id } ?: return
        mutableState.update { state ->
            state.copy(
                selectedSubscriptionId = id,
                results = selected.profiles.map { OmniSpeedProfileResult(it) },
                pingResults = emptyMap(),
                completedCount = 0,
                plannedDataBytes = plannedData(state, selected.profiles.size),
                message = null,
            )
        }
    }

    fun updateDownloadUrl(value: String) = updateTransferInput { it.copy(downloadUrlInput = value) }
    fun updateUploadUrl(value: String) = updateTransferInput { it.copy(uploadUrlInput = value) }
    fun updateDownloadSize(value: String) = updateTransferInput { it.copy(downloadSizeInput = value) }
    fun updateUploadSize(value: String) = updateTransferInput { it.copy(uploadSizeInput = value) }

    private fun updateTransferInput(change: (OmniSpeedLabUiState) -> OmniSpeedLabUiState) {
        if (mutableState.value.speedRunning) return
        mutableState.update { current ->
            val changed = change(current)
            changed.copy(
                results = changed.results.map { OmniSpeedProfileResult(it.profile) },
                completedCount = 0,
                plannedDataBytes = plannedData(changed, changed.results.size),
                message = null,
            )
        }
    }

    fun runSpeedTest() {
        val current = mutableState.value
        if (current.speedRunning || current.pingRunning) return
        if (current.selectedSubscriptionId == null || current.results.isEmpty()) {
            mutableState.update { it.copy(message = OmniSpeedLabMessage.NO_PROFILE) }
            return
        }
        val downloadUrl = current.downloadUrlInput.trim()
        val uploadUrl = current.uploadUrlInput.trim().takeIf { it.isNotEmpty() }
        val downloadSize = parseOmniTransferSize(current.downloadSizeInput)
        val uploadSize = parseOmniTransferSize(current.uploadSizeInput)
        if (!validHttpUrl(downloadUrl) || (uploadUrl != null && !validHttpUrl(uploadUrl)) ||
            downloadSize == null || (uploadUrl != null && uploadSize == null)
        ) {
            mutableState.update { it.copy(message = OmniSpeedLabMessage.INVALID_INPUT) }
            return
        }
        val profiles = current.results.map { it.profile }
        speedJob = viewModelScope.launch {
            var completedNormally = false
            mutableState.update { state ->
                state.copy(
                    speedRunning = true,
                    results = profiles.map { OmniSpeedProfileResult(it) },
                    completedCount = 0,
                    message = null,
                )
            }
            try {
                var activeDownloadUrl = downloadUrl
                for (profile in profiles) {
                    ensureActive()
                    updateResult(OmniSpeedProfileResult(profile, OmniSpeedStatus.RUNNING))
                    var runtime: OmniNativeCoreRuntime? = null
                    var attemptedUrl = activeDownloadUrl
                    var usedFallback = false
                    val outcome = try {
                        val config = withContext(Dispatchers.IO) {
                            CoreConfigManager.getV2rayConfig4Speedtest(getApplication(), profile.guid)
                        }
                        if (!config.status || config.content.isBlank() || config.aetherCore != null) {
                            failedResult(profile, attemptedUrl, usedFallback, uploadUrl, uploadSize, downloadSize, "Native exit unavailable")
                        } else {
                            runtime = OmniNativeCoreRuntime(getApplication())
                            activeRuntime = runtime
                            val lab = OmniTransferLab(runtime)
                            val masterDns = config.masterDnsCore
                            val dnstt = config.dnsttCore
                            val guts = config.gutsCore
                            var coreUnavailable = false
                            suspend fun measure(url: String): OmniTransferResult {
                                val operation: suspend () -> OmniTransferResult = {
                                    lab.measure(config.content, url, uploadUrl, uploadSize ?: 1_048_576, downloadSize)
                                }
                                return when {
                                    masterDns != null -> MasterDnsDelayTester.withCore(
                                        getApplication(), masterDns, MasterDnsDelayTester.STARTUP_BUDGET_MS, profile.guid, operation,
                                    ) ?: OmniTransferResult(false, error = "MasterDNS core did not start").also { coreUnavailable = true }
                                    dnstt != null -> DnsttDelayTester.withCore(
                                        getApplication(), dnstt, DnsttDelayTester.STARTUP_BUDGET_MS, profile.guid, operation,
                                    ) ?: OmniTransferResult(false, error = "DNSTT core did not start").also { coreUnavailable = true }
                                    guts != null -> GutsDelayTester.withCore(
                                        getApplication(), guts, GutsDelayTester.STARTUP_BUDGET_MS, profile.guid, operation,
                                    ) ?: OmniTransferResult(false, error = "GUTS client did not start").also { coreUnavailable = true }
                                    else -> operation()
                                }
                            }
                            var transfer = measure(attemptedUrl)
                            ensureActive()
                            if (!transfer.success && !coreUnavailable && attemptedUrl == OMNI_SPEED_CLOUDFLARE_DOWNLOAD) {
                                val cloudflareAttempt = transfer
                                attemptedUrl = OMNI_SPEED_OVH_DOWNLOAD
                                usedFallback = true
                                ensureActive()
                                transfer = measure(attemptedUrl).copy(
                                    priorAttemptDataBytes = cloudflareAttempt.dataUsedBytes,
                                    priorAttemptDurationMs = cloudflareAttempt.durationMs,
                                )
                            }
                            ensureActive()
                            if (transfer.success && attemptedUrl == OMNI_SPEED_OVH_DOWNLOAD) {
                                activeDownloadUrl = OMNI_SPEED_OVH_DOWNLOAD
                            }
                            OmniSpeedProfileResult(
                                profile = profile,
                                status = if (transfer.success) OmniSpeedStatus.SUCCESS else OmniSpeedStatus.FAILED,
                                transfer = transfer,
                                downloadHost = attemptedUrl.toHttpUrlOrNull()?.host,
                                usedFallback = usedFallback,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failedResult(
                            profile,
                            attemptedUrl,
                            usedFallback,
                            uploadUrl,
                            uploadSize,
                            downloadSize,
                            error.javaClass.simpleName ?: "Native test failed",
                        )
                    } finally {
                        runCatching { runtime?.stop() }
                        activeRuntime = null
                    }
                    updateResult(outcome)
                }
                completedNormally = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniSpeedLabMessage.SPEED_FAILED) }
            } finally {
                runCatching { activeRuntime?.stop() }
                activeRuntime = null
                speedJob = null
                mutableState.update { state ->
                    val settled = state.results.map { result ->
                        if (!completedNormally && result.status == OmniSpeedStatus.RUNNING) OmniSpeedProfileResult(result.profile) else result
                    }
                    state.copy(
                        speedRunning = false,
                        results = rankOmniSpeedResults(settled),
                        completedCount = settled.count { it.status == OmniSpeedStatus.SUCCESS || it.status == OmniSpeedStatus.FAILED },
                        message = if (completedNormally) {
                            if (settled.all { it.status == OmniSpeedStatus.SUCCESS }) {
                                OmniSpeedLabMessage.BATCH_FINISHED
                            } else {
                                OmniSpeedLabMessage.BATCH_PARTIAL
                            }
                        } else {
                            state.message
                        },
                    )
                }
            }
        }
    }

    fun cancelSpeedTest() {
        val job = speedJob
        if (!mutableState.value.speedRunning || job == null) return
        job.cancel()
        runCatching { activeRuntime?.stop() }
        // Keep start disabled until the cancelled coroutine has stopped its runtime and settled state.
        mutableState.update { it.copy(message = OmniSpeedLabMessage.BATCH_CANCELLED) }
    }

    private suspend fun updateResult(outcome: OmniSpeedProfileResult) {
        val ranked = withContext(Dispatchers.Default) {
            rankOmniSpeedResults(
                mutableState.value.results.map { current ->
                    if (current.profile.guid == outcome.profile.guid) outcome else current
                },
            )
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        mutableState.update { state ->
            state.copy(
                results = ranked,
                completedCount = ranked.count { it.status == OmniSpeedStatus.SUCCESS || it.status == OmniSpeedStatus.FAILED },
            )
        }
    }

    private fun failedResult(
        profile: OmniSpeedProfile,
        attemptedUrl: String,
        usedFallback: Boolean,
        uploadUrl: String?,
        uploadSize: Int?,
        downloadSize: Int,
        error: String,
    ): OmniSpeedProfileResult = OmniSpeedProfileResult(
        profile = profile,
        status = OmniSpeedStatus.FAILED,
        transfer = OmniTransferResult(
            success = false,
            requestedUploadBytes = if (uploadUrl == null) 0 else uploadSize ?: 0,
            downloadLimitBytes = downloadSize,
            error = error,
        ),
        downloadHost = attemptedUrl.toHttpUrlOrNull()?.host,
        usedFallback = usedFallback,
    )

    fun runRealPing() {
        val current = mutableState.value
        if (current.speedRunning || current.pingRunning || pingWorker != null) return
        val guids = current.results.map { it.profile.guid }
        if (guids.isEmpty()) {
            mutableState.update { it.copy(message = OmniSpeedLabMessage.NO_PROFILE) }
            return
        }
        mutableState.update { it.copy(pingRunning = true, pingResults = emptyMap(), message = null) }
        val worker = RealPingWorkerService(getApplication(), guids) { event ->
            when (event) {
                is RealPingEvent.Result -> {
                    val ping = OmniSpeedPing(
                        delayMs = event.delayMillis.takeIf { it > 0L && event.successfulSamples > 0 },
                        sampleCount = event.sampleCount,
                        successfulSamples = event.successfulSamples,
                        jitterMs = event.jitterMillis.takeIf { it >= 0L && event.successfulSamples > 0 },
                    )
                    mutableState.update { state -> state.copy(pingResults = state.pingResults + (event.guid to ping)) }
                    viewModelScope.launch { runCatching { repository.recordPing(event) } }
                }
                is RealPingEvent.Finish -> {
                    pingWorker = null
                    mutableState.update { state ->
                        state.copy(
                            pingRunning = false,
                            message = if (state.pingResults.isEmpty()) OmniSpeedLabMessage.PING_FAILED else state.message,
                        )
                    }
                }
                is RealPingEvent.Progress -> Unit
            }
        }
        pingWorker = worker
        worker.start()
    }

    private fun plannedData(state: OmniSpeedLabUiState, count: Int): Long? = omniSpeedPlannedDataBytes(
        count,
        state.downloadSizeInput,
        state.uploadSizeInput,
        state.uploadUrlInput,
        state.downloadUrlInput,
    )

    private fun validHttpUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme in setOf("http", "https") && url.username.isEmpty() && url.password.isEmpty()
    }

    override fun onCleared() {
        refreshJob?.cancel()
        speedJob?.cancel()
        pingWorker?.cancel()
        runCatching { activeRuntime?.stop() }
        activeRuntime = null
        super.onCleared()
    }
}
