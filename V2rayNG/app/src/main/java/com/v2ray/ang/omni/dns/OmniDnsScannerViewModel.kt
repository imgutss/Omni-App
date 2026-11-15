package com.v2ray.ang.omni.dns

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.DnsttCore
import com.v2ray.ang.core.DnsttCoreManager
import com.v2ray.ang.core.DnsttDelayTester
import com.v2ray.ang.core.MasterDnsCore
import com.v2ray.ang.core.MasterDnsCoreManager
import com.v2ray.ang.core.MasterDnsDelayTester
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.omni.OmniScannerTemplate
import com.v2ray.ang.omni.lab.OmniLatencyStats
import com.v2ray.ang.omni.lab.OmniTargetParser
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class OmniDnsScannerMessage { INVALID, NO_TEMPLATE, NO_WORKING, SCAN_FAILED, SCAN_TIMEOUT, E2E_COMPLETE, E2E_PARTIAL, E2E_TIMEOUT, APPLIED, APPLY_FAILED }

data class OmniDnsScannerUiState(
    val targetsInput: String = "1.1.1.1\n8.8.8.8",
    val candidateSource: OmniDnsCandidateSource = OmniDnsCandidateSource.MANUAL,
    val rangeGroups: List<OmniDnsRangeGroup> = emptyList(),
    val selectedRangeGroups: Set<String> = emptySet(),
    val generatedTargetsInput: String? = null,
    val generatedCount: Int = 0,
    val generating: Boolean = false,
    val autoScanAfterGenerate: Boolean = false,
    val portsInput: String = "53",
    val samplesInput: String = "3",
    val timeoutInput: String = "1500",
    val concurrencyInput: String = "24",
    val maxTargetsInput: String = "256",
    val e2eUrlInput: String = "http://www.gstatic.com/generate_204",
    val e2eSamplesInput: String = "3",
    val e2eTimeoutInput: String = "30000",
    val e2eMaxTargetsInput: String = "6",
    val e2eConcurrencyInput: String = "2",
    val e2eBudgetInput: String = "600000",
    val templates: List<OmniScannerTemplate> = emptyList(),
    val selectedTemplateGuid: String? = null,
    val totalCandidates: Long = 0L,
    val sampledTargets: Int = 0,
    val invalidInputs: Int = 0,
    val results: List<OmniDnsResolverResult> = emptyList(),
    val e2eResults: Map<String, OmniDnsE2eResult> = emptyMap(),
    val scanning: Boolean = false,
    val scanCompleted: Int = 0,
    val e2eRunning: Boolean = false,
    val e2eCompleted: Int = 0,
    val e2eTotal: Int = 0,
    val applying: Boolean = false,
    val bestEndpoint: String? = null,
    val appliedEndpoint: String? = null,
    val message: OmniDnsScannerMessage? = null,
)

class OmniDnsScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniDnsScannerRepository()
    private val probe = OmniDnsResolverProbe()
    private val mutableState = MutableStateFlow(OmniDnsScannerUiState())
    val state: StateFlow<OmniDnsScannerUiState> = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var e2eJob: Job? = null
    private val reservedPorts = mutableSetOf<Int>()

    init {
        viewModelScope.launch {
            val templates = runCatching { repository.templates() }.getOrDefault(emptyList())
            mutableState.update { current ->
                val selected = templates.firstOrNull()
                current.copy(templates = templates, selectedTemplateGuid = selected?.guid)
                    .let { if (selected == null) it else applyTemplateDefaults(it, selected) }
            }
        }
    }

    fun selectTemplate(guid: String) {
        if (busy()) return
        mutableState.update { current ->
            val template = current.templates.firstOrNull { it.guid == guid } ?: return@update current
            clearEvidence(applyTemplateDefaults(current.copy(selectedTemplateGuid = guid, message = null), template))
        }
    }

    fun updateTargets(value: String) = updateScanInput { it.copy(targetsInput = value) }
    fun updatePorts(value: String) = updateScanInput { it.copy(portsInput = value) }
    fun updateSamples(value: String) = updateScanInput { it.copy(samplesInput = value) }
    fun updateTimeout(value: String) = updateScanInput { it.copy(timeoutInput = value) }
    fun updateConcurrency(value: String) = updateScanInput { it.copy(concurrencyInput = value) }
    fun updateMaxTargets(value: String) = updateScanInput {
        it.copy(maxTargetsInput = value, generatedTargetsInput = null, generatedCount = 0)
    }
    fun updateE2eUrl(value: String) = updateE2eInput { it.copy(e2eUrlInput = value) }
    fun updateE2eSamples(value: String) = updateE2eInput { it.copy(e2eSamplesInput = value) }
    fun updateE2eTimeout(value: String) = updateE2eInput { it.copy(e2eTimeoutInput = value) }
    fun updateE2eMaxTargets(value: String) = updateE2eInput { it.copy(e2eMaxTargetsInput = value) }
    fun updateE2eConcurrency(value: String) = updateE2eInput { it.copy(e2eConcurrencyInput = value) }
    fun updateE2eBudget(value: String) = updateE2eInput { it.copy(e2eBudgetInput = value) }

    fun selectCandidateSource(source: OmniDnsCandidateSource) {
        if (busy()) return
        mutableState.update {
            clearEvidence(it).copy(
                candidateSource = source, rangeGroups = emptyList(), selectedRangeGroups = emptySet(),
                generatedTargetsInput = null, generatedCount = 0,
                totalCandidates = 0L, sampledTargets = 0, invalidInputs = 0,
            )
        }
        if (source == OmniDnsCandidateSource.MANUAL) return
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                OmniDnsCandidatePacks.groups(OmniDnsCandidatePacks.load(getApplication(), source), source)
            }
            mutableState.update { current ->
                if (current.candidateSource != source) current else current.copy(
                    rangeGroups = groups,
                    selectedRangeGroups = groups.mapTo(linkedSetOf(), OmniDnsRangeGroup::key),
                )
            }
        }
    }

    fun toggleRangeGroup(key: String) {
        if (busy()) return
        updateScanInput { current ->
            val selected = current.selectedRangeGroups.toMutableSet()
            if (!selected.add(key)) selected.remove(key)
            current.copy(selectedRangeGroups = selected, generatedTargetsInput = null, generatedCount = 0)
        }
    }

    fun selectAllRangeGroups(selected: Boolean) {
        if (busy()) return
        updateScanInput { current ->
            current.copy(
                selectedRangeGroups = if (selected) current.rangeGroups.mapTo(linkedSetOf(), OmniDnsRangeGroup::key) else emptySet(),
                generatedTargetsInput = null, generatedCount = 0,
            )
        }
    }

    fun updateAutoScanAfterGenerate(enabled: Boolean) {
        if (!busy()) mutableState.update { it.copy(autoScanAfterGenerate = enabled) }
    }

    fun generateCandidates() {
        if (busy()) return
        val current = mutableState.value
        if (current.candidateSource == OmniDnsCandidateSource.MANUAL) return
        val count = parseCount(current.maxTargetsInput, 1..OmniDnsCandidatePacks.MAX_CUSTOM_COUNT)
        val ranges = current.rangeGroups.filter { it.key in current.selectedRangeGroups }.flatMap(OmniDnsRangeGroup::ranges)
        if (count == null || ranges.isEmpty()) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.INVALID) }
            return
        }
        val autoScan = current.autoScanAfterGenerate
        val source = current.candidateSource
        viewModelScope.launch {
            mutableState.update { it.copy(generating = true, message = null) }
            val generated = runCatching {
                withContext(Dispatchers.Default) { OmniDnsCandidatePacks.generate(ranges, count) }
            }.getOrDefault(emptyList())
            mutableState.update {
                clearEvidence(it).copy(
                    generatedTargetsInput = generated.joinToString("\n"),
                    generatedCount = generated.size,
                    totalCandidates = generated.size.toLong(),
                    sampledTargets = generated.size,
                    invalidInputs = 0,
                    generating = false,
                    message = if (generated.isEmpty()) OmniDnsScannerMessage.INVALID else null,
                )
            }
            if (autoScan && generated.isNotEmpty() && mutableState.value.candidateSource == source) startScan()
        }
    }

    fun startScan() {
        if (busy()) return
        val current = mutableState.value
        val template = selectedTemplate(current)
        if (template == null) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.NO_TEMPLATE) }
            return
        }
        if (dnsProfileProbeDomain(template.profile).isBlank()) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.INVALID) }
            return
        }
        scanJob = viewModelScope.launch {
            val ports = runCatching { OmniTargetParser.parsePorts(mutableState.value.portsInput) }.getOrNull()
            val samples = parseCount(mutableState.value.samplesInput, 1..20)
            val timeout = parseCount(mutableState.value.timeoutInput, 100..15_000)
            val concurrency = parseCount(mutableState.value.concurrencyInput, 1..64)
            val limit = parseCount(mutableState.value.maxTargetsInput, 1..OmniDnsCandidatePacks.MAX_CUSTOM_COUNT)
            if (ports == null || samples == null || timeout == null || concurrency == null || limit == null) {
                mutableState.update { it.copy(message = OmniDnsScannerMessage.INVALID) }
                return@launch
            }
            val plan = withContext(Dispatchers.Default) {
                val input = if (mutableState.value.candidateSource == OmniDnsCandidateSource.MANUAL) {
                    mutableState.value.targetsInput
                } else {
                    mutableState.value.generatedTargetsInput.orEmpty()
                }
                OmniDnsResolverTargets.parseAndSample(input, ports, limit)
            }
            if (plan.targets.isEmpty()) {
                mutableState.update { it.copy(message = OmniDnsScannerMessage.INVALID, invalidInputs = plan.invalidInputs) }
                return@launch
            }
            mutableState.update {
                clearEvidence(it).copy(
                    totalCandidates = plan.totalCandidates,
                    sampledTargets = plan.targets.size,
                    invalidInputs = plan.invalidInputs,
                    scanning = true,
                    message = null,
                )
            }
            try {
                withTimeout(dnsScanBudgetMs(plan.targets.size, samples, timeout, concurrency)) {
                    probe.scan(plan.targets, OmniDnsResolverProbeOptions(
                        domain = dnsProfileProbeDomain(template.profile),
                        samples = samples,
                        timeoutMs = timeout,
                        concurrency = concurrency,
                    )).collect { result ->
                        mutableState.update { state ->
                            val results = (state.results.filterNot { it.target.endpointId == result.target.endpointId } + result)
                            state.copy(results = rankDnsResolverResults(results, state.e2eResults), scanCompleted = state.scanCompleted + 1)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(message = OmniDnsScannerMessage.SCAN_TIMEOUT) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniDnsScannerMessage.SCAN_FAILED) }
            } finally {
                mutableState.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun startE2e() {
        if (busy()) return
        val current = mutableState.value
        val template = selectedTemplate(current)
        val working = current.results.filter { it.working }
        if (template == null) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.NO_TEMPLATE) }
            return
        }
        if (working.isEmpty()) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.NO_WORKING) }
            return
        }
        val url = current.e2eUrlInput.trim().toHttpUrlOrNull()
        val samples = parseCount(current.e2eSamplesInput, 1..20)
        val timeout = parseCount(current.e2eTimeoutInput, 5_000..180_000)?.toLong()
        val maxTargets = parseCount(current.e2eMaxTargetsInput, 1..32)
        val concurrency = parseCount(current.e2eConcurrencyInput, 1..3)
        val budget = parseCount(current.e2eBudgetInput, 30_000..1_800_000)?.toLong()
        if (url == null || url.scheme !in setOf("http", "https") || samples == null || timeout == null ||
            maxTargets == null || concurrency == null || budget == null) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.INVALID) }
            return
        }
        val candidates = dnsE2eShortlist(working, maxTargets)
        e2eJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    e2eRunning = true,
                    e2eResults = emptyMap(),
                    e2eCompleted = 0,
                    e2eTotal = candidates.size,
                    bestEndpoint = null,
                    appliedEndpoint = null,
                    message = null,
                )
            }
            var completed = false
            try {
                withTimeout(budget) {
                    supervisorScope {
                        val semaphore = Semaphore(concurrency)
                        candidates.map { targetResult ->
                            launch {
                                semaphore.withPermit {
                                    val result = runE2e(template, targetResult, url.toString(), samples, timeout)
                                    mutableState.update { state ->
                                        val e2e = state.e2eResults + (targetResult.target.endpointId to result)
                                        state.copy(
                                            results = rankDnsResolverResults(state.results, e2e),
                                            e2eResults = e2e,
                                            e2eCompleted = state.e2eCompleted + 1,
                                            bestEndpoint = bestDnsResolver(state.results, e2e)?.target?.endpointId,
                                        )
                                    }
                                }
                            }
                        }.joinAll()
                    }
                }
                completed = true
                mutableState.update { state ->
                    val passed = state.e2eResults.values.count { it.success }
                    state.copy(message = if (passed == candidates.size) OmniDnsScannerMessage.E2E_COMPLETE else OmniDnsScannerMessage.E2E_PARTIAL)
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(message = OmniDnsScannerMessage.E2E_TIMEOUT) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniDnsScannerMessage.E2E_PARTIAL) }
            } finally {
                mutableState.update { state ->
                    state.copy(
                        e2eRunning = false,
                        message = if (completed) state.message else state.message ?: OmniDnsScannerMessage.E2E_PARTIAL,
                    )
                }
            }
        }
    }

    fun stopE2e() {
        e2eJob?.cancel()
        e2eJob = null
    }

    fun applyBest() {
        if (busy()) return
        val current = mutableState.value
        val template = selectedTemplate(current)
        val best = current.results.firstOrNull { it.target.endpointId == current.bestEndpoint }
        if (template == null || best == null || current.e2eResults[best.target.endpointId]?.success != true) {
            mutableState.update { it.copy(message = OmniDnsScannerMessage.NO_WORKING) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(applying = true, message = null) }
            try {
                val candidate = dnsCandidateProfile(template.profile, best.target)
                repository.applyResolver(template.guid, candidate)
                mutableState.update { state ->
                    state.copy(
                        applying = false,
                        templates = state.templates.map { if (it.guid == template.guid) it.copy(profile = candidate) else it },
                        appliedEndpoint = best.target.endpointId,
                        message = OmniDnsScannerMessage.APPLIED,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(applying = false, message = OmniDnsScannerMessage.APPLY_FAILED) }
            }
        }
    }

    private suspend fun runE2e(
        template: OmniScannerTemplate,
        target: OmniDnsResolverResult,
        url: String,
        samples: Int,
        timeoutMs: Long,
    ): OmniDnsE2eResult = withContext(Dispatchers.IO) {
        val candidate = runCatching { dnsCandidateProfile(template.profile, target.target) }.getOrElse {
            return@withContext failedE2e(itMessage(it))
        }
        when (candidate.configType) {
            EConfigType.DNSTT -> {
                val allocated = reserveCore(
                    build = { port -> DnsttCore.of(candidate, port) },
                    ports = { core -> core.sessionPorts },
                    free = DnsttCoreManager::portFree,
                ) ?: return@withContext failedE2e("No free loopback port for isolated test")
                val core = allocated.first
                try {
                    if (!DnsttCoreManager.isSupported(getApplication(), core.engine)) {
                        return@withContext failedE2e("Native DNS core unavailable")
                    }
                    val startup = if (core.ssh == null) DnsttDelayTester.STARTUP_BUDGET_MS else DnsttDelayTester.SSH_STARTUP_BUDGET_MS
                    val ping = DnsttDelayTester.withIsolatedCore(getApplication(), core, startup) {
                        OmniDirectSocksLab().ping(core.port, url, samples, warmup = 1, timeoutMs = timeoutMs)
                    }
                    if (ping == null) failedE2e("Tunnel did not become ready")
                    else OmniDnsE2eResult(ping.stats, ping.lastHttpStatus, ping.error)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failedE2e(itMessage(error))
                } finally {
                    releasePorts(allocated.second)
                }
            }
            EConfigType.MASTERDNS -> {
                val allocated = reserveCore(
                    build = { port -> MasterDnsCore.of(candidate, port) },
                    ports = { core -> listOf(core.port) },
                    free = MasterDnsCoreManager::portFree,
                ) ?: return@withContext failedE2e("No free loopback port for isolated test")
                val core = allocated.first
                try {
                    if (!MasterDnsCoreManager.isSupported(getApplication(), core.engine)) {
                        return@withContext failedE2e("Native DNS core unavailable")
                    }
                    val ping = MasterDnsDelayTester.withIsolatedCore(getApplication(), core) {
                        OmniDirectSocksLab().ping(core.port, url, samples, warmup = 1, timeoutMs = timeoutMs)
                    }
                    if (ping == null) failedE2e("Tunnel did not become ready")
                    else OmniDnsE2eResult(ping.stats, ping.lastHttpStatus, ping.error)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failedE2e(itMessage(error))
                } finally {
                    releasePorts(allocated.second)
                }
            }
            else -> failedE2e("Unsupported DNS tunnel profile")
        }
    }

    private fun <T> reserveCore(
        build: (Int) -> T,
        ports: (T) -> List<Int>,
        free: (Int) -> Boolean,
    ): Pair<T, List<Int>>? = synchronized(reservedPorts) {
        repeat(64) {
            val port = runCatching { Utils.findRandomFreePort() }.getOrNull() ?: return@repeat
            val core = runCatching { build(port) }.getOrNull() ?: return@repeat
            val required = ports(core)
            if (required.isNotEmpty() && required.all { it !in reservedPorts && free(it) }) {
                reservedPorts.addAll(required)
                return core to required
            }
        }
        null
    }

    private fun releasePorts(ports: List<Int>) = synchronized(reservedPorts) {
        reservedPorts.removeAll(ports.toSet())
    }

    private fun selectedTemplate(state: OmniDnsScannerUiState) = state.templates.firstOrNull { it.guid == state.selectedTemplateGuid }
    private fun busy() = mutableState.value.let { it.scanning || it.e2eRunning || it.applying || it.generating } ||
        scanJob?.isActive == true || e2eJob?.isActive == true
    private fun parseCount(value: String, range: IntRange) = OmniTargetParser.parseCount(value)
        ?.takeIf { it >= range.first.toLong() && it <= range.last.toLong() }?.toInt()

    private fun updateScanInput(change: (OmniDnsScannerUiState) -> OmniDnsScannerUiState) {
        if (busy()) return
        mutableState.update { clearEvidence(change(it)).copy(totalCandidates = 0L, sampledTargets = 0, invalidInputs = 0) }
    }

    private fun updateE2eInput(change: (OmniDnsScannerUiState) -> OmniDnsScannerUiState) {
        if (busy()) return
        mutableState.update {
            change(it).copy(e2eResults = emptyMap(), e2eCompleted = 0, e2eTotal = 0, bestEndpoint = null, appliedEndpoint = null, message = null)
        }
    }

    private fun applyTemplateDefaults(state: OmniDnsScannerUiState, template: OmniScannerTemplate): OmniDnsScannerUiState = state.copy(
        targetsInput = if (template.profile.configType == EConfigType.MASTERDNS) {
            template.profile.masterDnsResolvers?.takeIf { it.isNotBlank() } ?: state.targetsInput
        } else {
            template.profile.dnsttResolver?.takeIf { it.isNotBlank() } ?: state.targetsInput
        },
        e2eTimeoutInput = if (template.profile.dnsttSshEnabled == true) "120000" else "30000",
    )

    override fun onCleared() {
        scanJob?.cancel()
        e2eJob?.cancel()
        super.onCleared()
    }
}

internal fun dnsScanBudgetMs(targetCount: Int, samples: Int, timeoutMs: Int, concurrency: Int): Long {
    require(targetCount > 0 && samples > 0 && timeoutMs > 0 && concurrency > 0)
    val waves = (targetCount.toLong() + concurrency - 1L) / concurrency
    val estimated = waves * (samples + 1L) * timeoutMs + 5_000L
    return estimated.coerceIn(30_000L, 180_000L)
}

private fun clearEvidence(state: OmniDnsScannerUiState) = state.copy(
    results = emptyList(), e2eResults = emptyMap(), scanCompleted = 0,
    e2eCompleted = 0, e2eTotal = 0, bestEndpoint = null, appliedEndpoint = null, message = null,
)

private fun failedE2e(error: String?) = OmniDnsE2eResult(OmniLatencyStats(emptyList(), 0, 0), error = error)
private fun itMessage(error: Throwable) = error.message ?: error.javaClass.simpleName
