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
import com.v2ray.ang.omni.lab.OmniTunnelConnectivity
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.omni.lab.OmniBuildOptions
import com.v2ray.ang.omni.lab.OmniBuiltConfiguration
import com.v2ray.ang.omni.lab.OmniEdgeProbeResult
import com.v2ray.ang.omni.lab.OmniEndpointConfigFactory
import com.v2ray.ang.omni.lab.OmniScanOptions
import com.v2ray.ang.omni.lab.OmniProbeMode
import com.v2ray.ang.omni.lab.OmniProbeOptions
import com.v2ray.ang.omni.lab.OmniScanRunner
import com.v2ray.ang.omni.lab.OmniTargetParser
import com.v2ray.ang.omni.lab.rankByEdgeQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_SCAN_TARGETS = 100_000

enum class OmniScannerMessage { INVALID_INPUT, NO_TEMPLATE, NO_SELECTION, BUILD_EMPTY, SAVED, SAVE_FAILED, NO_SAVED, SPEED_FAILED, NO_PANEL, PUBLISH_FAILED, PUBLISHED, SYNC_FAILED, SYNCED, SCAN_FAILED, RETESTED, RETEST_FAILED }

data class OmniScannerUiState(
    val targetsInput: String = "1.1.1.1",
    val portsInput: String = "443",
    val probeMode: OmniProbeMode = OmniProbeMode.TCP,
    val probeUseTls: Boolean? = null,
    val samplesInput: String = "3",
    val sniInput: String = "",
    val hostHeaderInput: String = "",
    val httpPathInput: String = "/",
    val concurrencyInput: String = "24",
    val maxTargetsInput: String = "1000",
    val templates: List<OmniScannerTemplate> = emptyList(),
    val selectedTemplateGuid: String? = null,
    val plannedTargets: Long = 0L,
    val plannedProbes: Long = 0L,
    val invalidTargets: Int = 0,
    val results: List<OmniEdgeProbeResult> = emptyList(),
    val selectedEndpoints: Set<String> = emptySet(),
    val built: List<OmniBuiltConfiguration> = emptyList(),
    val scanning: Boolean = false,
    val building: Boolean = false,
    val saving: Boolean = false,
    val message: OmniScannerMessage? = null,
    val savedCount: Int = 0,
    val savedGuids: List<String> = emptyList(),
    val savedGuidToFingerprint: Map<String, String> = emptyMap(),
    val builtPing: Map<String, OmniBuiltPing> = emptyMap(),
    val downloadUrlInput: String = "https://speed.cloudflare.com/__down?bytes=4194304",
    val uploadUrlInput: String = "",
    val speedRunning: Boolean = false,
    val connectivity: OmniTunnelConnectivity? = null,
    val transfer: OmniTransferResult? = null,
    val pingRunning: Boolean = false,
    val pingDelayMs: Long? = null,
    val pingSamples: Int = 0,
    val pingSuccesses: Int = 0,
    val pingJitterMs: Long? = null,
    val panels: List<OmniPanel> = emptyList(),
    val selectedPanelId: String? = null,
    val publishing: Boolean = false,
    val published: OmniScannerPublishResult? = null,
    val syncing: Boolean = false,
    val syncedCount: Int = 0,
)

class OmniScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniScannerRepository()
    private val runner = OmniScanRunner()
    private val hub = OmniHubRepository(application)
    private var pingWorker: RealPingWorkerService? = null
    private val mutableState = MutableStateFlow(OmniScannerUiState())
    val state: StateFlow<OmniScannerUiState> = mutableState.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val templates = repository.templates()
            val panels = runCatching { hub.panels() }.getOrDefault(emptyList())
            mutableState.update { state ->
                val selectedGuid = templates.firstOrNull()?.guid
                val withTemplates = state.copy(
                    templates = templates,
                    selectedTemplateGuid = selectedGuid,
                    panels = panels,
                    selectedPanelId = panels.firstOrNull()?.id,
                )
                templates.firstOrNull()?.let { applyTemplateDefaults(withTemplates, it) } ?: withTemplates
            }
        }
    }

    fun updateTargets(value: String) = updateScanInput { it.copy(targetsInput = value) }
    fun updatePorts(value: String) = updateScanInput { it.copy(portsInput = value) }
    fun selectProbeMode(mode: OmniProbeMode) = updateScanInput {
        it.copy(
            probeMode = mode,
            probeUseTls = if (mode == OmniProbeMode.TLS) true else it.probeUseTls,
        )
    }
    fun updateSamples(value: String) = updateScanInput { it.copy(samplesInput = value) }
    fun updateSni(value: String) = updateScanInput {
        it.copy(sniInput = value, probeUseTls = if (value.isNotBlank()) true else it.probeUseTls)
    }
    fun updateHostHeader(value: String) = updateScanInput { it.copy(hostHeaderInput = value) }
    fun updateHttpPath(value: String) = updateScanInput { it.copy(httpPathInput = value) }
    fun updateConcurrency(value: String) = updateScanInput { it.copy(concurrencyInput = value) }
    fun updateMaxTargets(value: String) = updateScanInput { it.copy(maxTargetsInput = value) }
    fun updateDownloadUrl(value: String) = mutableState.update { it.copy(downloadUrlInput = value, connectivity = null, transfer = null, message = null) }
    fun updateUploadUrl(value: String) = mutableState.update { it.copy(uploadUrlInput = value, connectivity = null, transfer = null, message = null) }
    fun selectTemplate(guid: String) {
        val state = mutableState.value
        if (state.scanning || state.speedRunning || state.pingRunning) return
        mutableState.update { current ->
            val template = current.templates.firstOrNull { it.guid == guid } ?: return@update current
            clearBuiltEvidence(applyTemplateDefaults(current.copy(selectedTemplateGuid = guid, message = null), template))
        }
    }
    fun selectPanel(panelId: String) = mutableState.update { it.copy(selectedPanelId = panelId, published = null, message = null) }

    fun startScan() {
        val initial = mutableState.value
        if (initial.scanning || initial.speedRunning || initial.pingRunning) return
        scanJob = viewModelScope.launch {
            val current = mutableState.value
            val ports = runCatching { OmniTargetParser.parsePorts(current.portsInput) }.getOrElse {
                mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT) }; return@launch
            }
            val concurrency = OmniTargetParser.parseCount(current.concurrencyInput)
                ?.takeIf { it in 1L..128L }?.toInt()
            val maxTargets = OmniTargetParser.parseCount(current.maxTargetsInput)
                ?.takeIf { it in 1L..MAX_SCAN_TARGETS.toLong() }?.toInt()
            val samples = OmniTargetParser.parseCount(current.samplesInput)
                ?.takeIf { it in 1L..20L }?.toInt()
            if (concurrency == null || maxTargets == null || samples == null || !validProbeText(current.sniInput) || !validProbeText(current.hostHeaderInput) || !validProbeText(current.httpPathInput)) {
                mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT) }; return@launch
            }
            val plan = withContext(Dispatchers.Default) { OmniTargetParser.parse(current.targetsInput) }
            if (!plan.hasTargets) {
                mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT, invalidTargets = plan.invalid.size) }; return@launch
            }
            val plannedProbes = plan.plannedProbeCount(ports)
            mutableState.update {
                clearBuiltEvidence(it).copy(
                    plannedTargets = minOf(plan.targetCount, maxTargets.toLong()),
                    plannedProbes = minOf(plannedProbes, maxTargets.toLong()),
                    invalidTargets = plan.invalid.size,
                    results = emptyList(), selectedEndpoints = emptySet(), built = emptyList(), savedCount = 0,
                    scanning = true, message = null,
                )
            }
            var scanCompleted = false
            try {
                runner.scan(
                plan,
                OmniScanOptions(
                    ports = ports,
                    concurrency = concurrency,
                    maxTargets = maxTargets,
                    probe = OmniProbeOptions(
                        mode = current.probeMode,
                        samples = samples,
                        warmup = 1,
                        sni = current.sniInput.trim().takeIf { it.isNotEmpty() },
                        hostHeader = current.hostHeaderInput.trim().takeIf { it.isNotEmpty() },
                        httpPath = current.httpPathInput.trim().ifBlank { "/" },
                        useTls = current.probeUseTls,
                    ),
                ),
            ).collect { result ->
                    val ranked = withContext(Dispatchers.Default) { (mutableState.value.results + result).rankByEdgeQuality() }
                    mutableState.update { state -> state.copy(results = ranked) }
                }
                scanCompleted = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniScannerMessage.SCAN_FAILED) }
            } finally {
                mutableState.update { it.copy(scanning = false) }
                if (scanCompleted) {
                    val results = mutableState.value.results
                    OmniOperationNotifier.scanCompleted(getApplication(), results.size, results.count { it.success })
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        mutableState.update { it.copy(scanning = false) }
    }

    fun toggleSelection(endpointId: String) = mutableState.update { state ->
        val selected = state.selectedEndpoints.toMutableSet().apply { if (!add(endpointId)) remove(endpointId) }
        state.copy(selectedEndpoints = selected, message = null)
    }

    fun selectAllSuccessful() = mutableState.update { state ->
        state.copy(selectedEndpoints = state.results.filter { it.verified }.map { it.target.endpointId }.toSet(), message = null)
    }

    fun buildSelected() {
        val current = mutableState.value
        if (current.building || current.saving || current.speedRunning || current.pingRunning) return
        val template = current.templates.firstOrNull { it.guid == current.selectedTemplateGuid }?.profile
        if (template == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_TEMPLATE) }; return
        }
        val selected = current.results.filter { it.target.endpointId in current.selectedEndpoints && it.success }.map { it.target }
        if (selected.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_SELECTION) }; return
        }
        viewModelScope.launch {
            mutableState.update { clearBuiltEvidence(it).copy(building = true, message = null) }
            try {
                val built = withContext(Dispatchers.Default) { OmniEndpointConfigFactory.buildBatch(template, selected, OmniBuildOptions()) }
                mutableState.update { it.copy(building = false, built = built, message = if (built.isEmpty()) OmniScannerMessage.BUILD_EMPTY else null) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(building = false, built = emptyList(), message = OmniScannerMessage.BUILD_EMPTY) }
            }
        }
    }

    fun saveBuilt() {
        val current = mutableState.value
        if (current.saving || current.building || current.speedRunning || current.pingRunning) return
        val built = current.built
        if (built.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.BUILD_EMPTY) }; return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, message = null) }
            try {
                val ids = repository.saveBuilt(built)
                mutableState.update {
                    it.copy(
                        saving = false,
                        savedCount = ids.size,
                        savedGuids = ids,
                        savedGuidToFingerprint = ids.mapIndexedNotNull { index, guid -> built.getOrNull(index)?.fingerprint?.let { fingerprint -> guid to fingerprint } }.toMap(),
                        builtPing = emptyMap(),
                        message = OmniScannerMessage.SAVED,
                    )
                }
                if (ids.isNotEmpty()) retestSavedThroughCore()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(saving = false, message = OmniScannerMessage.SAVE_FAILED) }
            }
        }
    }

    fun publishBuilt() {
        val current = mutableState.value
        val panel = current.panels.firstOrNull { it.id == current.selectedPanelId }
        if (panel == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_PANEL) }; return
        }
        if (current.built.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.BUILD_EMPTY) }; return
        }
        if (current.publishing) return
        viewModelScope.launch {
            mutableState.update { it.copy(publishing = true, published = null, message = null) }
            try {
                val result = hub.publishScannerPack(panel, current.built.map { it.profile })
                mutableState.update { it.copy(publishing = false, published = result, message = OmniScannerMessage.PUBLISHED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(publishing = false, message = OmniScannerMessage.PUBLISH_FAILED) }
            }
        }
    }

    fun syncSelectedPanel() {
        val panel = mutableState.value.panels.firstOrNull { it.id == mutableState.value.selectedPanelId }
        if (panel == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_PANEL) }; return
        }
        if (mutableState.value.syncing) return
        viewModelScope.launch {
            mutableState.update { it.copy(syncing = true, message = null) }
            try {
                val count = hub.sync(panel)
                mutableState.update { it.copy(syncing = false, syncedCount = count, message = OmniScannerMessage.SYNCED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(syncing = false, message = OmniScannerMessage.SYNC_FAILED) }
            }
        }
    }

    fun runSpeedTest() {
        val current = mutableState.value
        val guid = current.savedGuids.firstOrNull()
        if (guid == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_SAVED) }; return
        }
        val downloadUrl = current.downloadUrlInput.trim()
        val uploadUrl = current.uploadUrlInput.trim().takeIf { it.isNotEmpty() }
        if (!isHttpUrl(downloadUrl) || (uploadUrl != null && !isHttpUrl(uploadUrl))) {
            mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT) }; return
        }
        if (current.speedRunning || current.pingRunning) return
        viewModelScope.launch {
            mutableState.update { it.copy(speedRunning = true, connectivity = null, transfer = null, message = null) }
            try {
                val config = withContext(Dispatchers.IO) { CoreConfigManager.getV2rayConfig4Speedtest(getApplication(), guid) }
                if (!config.status || config.content.isBlank() || config.aetherCore != null) {
                    mutableState.update { it.copy(message = OmniScannerMessage.SPEED_FAILED) }
                    return@launch
                }
                val lab = OmniTransferLab(OmniNativeCoreRuntime(getApplication()))
                val masterDns = config.masterDnsCore
                val dnstt = config.dnsttCore
                val guts = config.gutsCore
                val connection = when {
                    masterDns != null -> MasterDnsDelayTester.withCore(
                        getApplication(), masterDns, MasterDnsDelayTester.STARTUP_BUDGET_MS, guid,
                    ) { lab.connectivity(config.content, downloadUrl) }
                    dnstt != null -> DnsttDelayTester.withCore(
                        getApplication(), dnstt, DnsttDelayTester.STARTUP_BUDGET_MS, guid,
                    ) { lab.connectivity(config.content, downloadUrl) }
                    guts != null -> GutsDelayTester.withCore(
                        getApplication(), guts, GutsDelayTester.STARTUP_BUDGET_MS, guid,
                    ) { lab.connectivity(config.content, downloadUrl) }
                    else -> lab.connectivity(config.content, downloadUrl)
                }
                mutableState.update { it.copy(connectivity = connection) }
                if (connection == null || !connection.success) {
                    mutableState.update { it.copy(message = OmniScannerMessage.SPEED_FAILED) }
                    return@launch
                }
                val transfer = when {
                    masterDns != null -> MasterDnsDelayTester.withCore(
                        getApplication(), masterDns, MasterDnsDelayTester.STARTUP_BUDGET_MS, guid,
                    ) { lab.measure(config.content, downloadUrl, uploadUrl) }
                        ?: OmniTransferResult(false, error = "MasterDNS core did not start")
                    dnstt != null -> DnsttDelayTester.withCore(
                        getApplication(), dnstt, DnsttDelayTester.STARTUP_BUDGET_MS, guid,
                    ) { lab.measure(config.content, downloadUrl, uploadUrl) }
                        ?: OmniTransferResult(false, error = "DNSTT core did not start")
                    guts != null -> GutsDelayTester.withCore(
                        getApplication(), guts, GutsDelayTester.STARTUP_BUDGET_MS, guid,
                    ) { lab.measure(config.content, downloadUrl, uploadUrl) }
                        ?: OmniTransferResult(false, error = "GUTS client did not start")
                    else -> lab.measure(config.content, downloadUrl, uploadUrl)
                }
                mutableState.update { it.copy(transfer = transfer, message = if (transfer.success) null else OmniScannerMessage.SPEED_FAILED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniScannerMessage.SPEED_FAILED) }
            } finally {
                mutableState.update { it.copy(speedRunning = false) }
            }
        }
    }

    fun retestSavedThroughCore() {
        val current = mutableState.value
        val guids = current.savedGuids
        if (guids.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_SAVED) }; return
        }
        if (current.pingRunning || current.speedRunning) return
        mutableState.update {
            it.copy(
                pingRunning = true,
                builtPing = emptyMap(),
                pingDelayMs = null,
                pingSamples = 0,
                pingSuccesses = 0,
                pingJitterMs = null,
                message = null,
            )
        }
        val worker = RealPingWorkerService(getApplication(), guids) { event ->
            when (event) {
                is RealPingEvent.Result -> {
                    val ping = OmniBuiltPing(
                        delayMs = event.delayMillis.takeIf { it > 0L && event.successfulSamples > 0 },
                        sampleCount = event.sampleCount,
                        successfulSamples = event.successfulSamples,
                        jitterMs = event.jitterMillis.takeIf { it >= 0L && event.successfulSamples > 0 },
                    )
                    mutableState.update { state ->
                        val fingerprint = state.savedGuidToFingerprint[event.guid]
                        val isFirst = event.guid == state.savedGuids.firstOrNull()
                        state.copy(
                            builtPing = if (fingerprint == null) state.builtPing else state.builtPing + (fingerprint to ping),
                            pingDelayMs = if (isFirst) ping.delayMs else state.pingDelayMs,
                            pingSamples = if (isFirst) ping.sampleCount else state.pingSamples,
                            pingSuccesses = if (isFirst) ping.successfulSamples else state.pingSuccesses,
                            pingJitterMs = if (isFirst) ping.jitterMs else state.pingJitterMs,
                        )
                    }
                }
                is RealPingEvent.Finish -> {
                    pingWorker = null
                    mutableState.update { state ->
                        val complete = state.savedGuidToFingerprint.size == state.savedGuids.size && state.builtPing.size == state.savedGuids.size
                        state.copy(pingRunning = false, message = if (complete) OmniScannerMessage.RETESTED else OmniScannerMessage.RETEST_FAILED)
                    }
                }
                is RealPingEvent.Progress -> Unit
            }
        }
        pingWorker = worker
        worker.start()
    }

    private fun updateScanInput(change: (OmniScannerUiState) -> OmniScannerUiState) {
        val state = mutableState.value
        if (state.scanning || state.speedRunning || state.pingRunning) return
        mutableState.update { current ->
            clearBuiltEvidence(change(current)).copy(results = emptyList(), selectedEndpoints = emptySet(), message = null)
        }
    }

    private fun applyTemplateDefaults(state: OmniScannerUiState, template: OmniScannerTemplate): OmniScannerUiState {
        val defaults = omniScannerProbeDefaults(template.profile)
        return state.copy(
            probeMode = defaults.mode,
            probeUseTls = defaults.useTls,
            sniInput = defaults.sni,
            hostHeaderInput = defaults.hostHeader,
            httpPathInput = defaults.path,
        )
    }

    private fun clearBuiltEvidence(state: OmniScannerUiState): OmniScannerUiState = state.copy(
        built = emptyList(),
        savedCount = 0,
        savedGuids = emptyList(),
        savedGuidToFingerprint = emptyMap(),
        builtPing = emptyMap(),
        published = null,
        connectivity = null,
        transfer = null,
        pingDelayMs = null,
        pingSamples = 0,
        pingSuccesses = 0,
        pingJitterMs = null,
    )

    private fun validProbeText(value: String): Boolean = value.length <= 2_048 && value.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun isHttpUrl(value: String): Boolean = value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

    override fun onCleared() {
        scanJob?.cancel()
        pingWorker?.cancel()
        super.onCleared()
    }
}
