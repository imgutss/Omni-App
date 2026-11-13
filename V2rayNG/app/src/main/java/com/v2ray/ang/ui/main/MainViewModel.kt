package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, RealPingResult>,
): List<ServersCache> = servers.map { server ->
    val result = updates[server.guid] ?: return@map server
    server.copy(
        testDelayMillis = result.delayMillis,
        testSampleCount = result.sampleCount,
        testSuccessfulSamples = result.successfulSamples,
        testJitterMillis = result.jitterMillis,
        testKind = result.kind,
        testMedianMillis = result.medianMillis,
        testMaxMillis = result.maxMillis,
        testPartial = result.partial,
    )
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, RealPingResult>,
): List<ServerRowUiModel> = rows.map { row ->
    val result = updates[row.guid] ?: return@map row
    row.copy(
        testDelayMillis = result.delayMillis,
        testSampleCount = result.sampleCount,
        testSuccessfulSamples = result.successfulSamples,
        testJitterMillis = result.jitterMillis,
        testKind = result.kind,
        testMedianMillis = result.medianMillis,
        testMaxMillis = result.maxMillis,
        testPartial = result.partial,
    )
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    /**
     * PattNG: per group, the moves asked for and not stored yet, and the groups one of whose moves the storage refused,
     * see [moveServer]. Concurrent, as a move's job may end on the thread its store ended on, as under an unconfined
     * dispatcher.
     */
    private val unstoredMoves = ConcurrentHashMap<String, Int>()
    private val refusedMoveGroups: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** PattNG: the number the last refusal of a move to be told got, see [MainUiState.moveRefusal]. */
    private val moveRefusals = AtomicInteger()

    /**
     * PattNG: per group, the moves shown, and those settled, stored or refused, see [storeMove]. A reading of a group
     * taken before every move shown was settled is held back, see [updateGroupUi], and the group is shown anew once its
     * moves are, see [moveServer]; [groupsReadEarly] names the groups waiting for that. A move is shown, and a reading
     * shown or held back, under [groupShowLock], one at a time.
     */
    private val shownMoves = ConcurrentHashMap<String, Int>()
    private val settledMoves = ConcurrentHashMap<String, Int>()
    private val groupsReadEarly: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val groupShowLock = Any()

    /**
     * PattNG: per group, the number the last reading got, in the order the readings were taken, which the cached list,
     * written with it, is too, and the number of the reading shown last: a reading older than the one shown is not
     * shown over it, see [updateGroupUi].
     */
    private val readingNumbers = ConcurrentHashMap<String, Int>()
    private val shownReadings = ConcurrentHashMap<String, Int>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null

    /** Bound on how long one measurement may keep the status bar waiting, see its schedule. */
    private var currentTestTimeoutJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, RealPingResult>()

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    /**
     * PattNG: the names the Aether editor gives the WARP protocols, which the rows of Aether profiles show. Declared
     * before init, whose work off the main thread reads it: a delegate declared after it may not be there yet.
     */
    private val aetherProtocolLabel by lazy {
        aetherProtocolLabels(
            dataSource.getStringArray(R.array.aether_protocol_entries),
            dataSource.getStringArray(R.array.aether_protocol_values),
        )
    }

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                // The daemon attaches a reason only when it is a localized resource string, e.g.
                // the Aether core stopping or missing on this ABI; the generic text is the fallback.
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            is MainServiceEvent.StateConnecting -> {
                if (uiState.value.isRunning) {
                    _uiState.update { it.copy(status = MainStatus.Connecting(event.message)) }
                }
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                if (!uiState.value.isRunning || !testRequests.completeCurrent(event.requestId)) return
                cancelCurrentTestTimeout()
                _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (testRequests.completeCurrent(event.requestId)) {
                    cancelCurrentTestTimeout()
                    resetTestStatus()
                }
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }

            MainServiceEvent.ServersChanged -> setupGroupTab(forceRefresh = true)
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        mutableServerGroupState(request.groupId).update { current ->
            current.copy(
                servers = applyTestDelayResults(current.servers, updates),
                rows = applyTestDelayResultsToRows(current.rows, updates),
            )
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        is MainStatus.Connecting -> status.message
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    /**
     * What the status bar says about the measurement it was asked for.
     *
     * It describes the same figure the rows do — the fastest real response, next to the typical and the
     * slowest one — through the same renderer, so tapping the bar and reading a row cannot show two
     * different pings for one link. Before this, the bar showed the median and the rows showed the
     * minimum, which is precisely what made the two disagree.
     */
    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val readout = PingReadout(
            delayMillis = result.delayMillis,
            medianMillis = result.medianMillis,
            maxMillis = result.maxMillis,
            sampleCount = result.sampleCount,
            successfulSamples = result.successfulSamples,
            kind = result.kind,
            partial = result.partial,
        )
        val status = when {
            readout.fastest != null -> PingReadout.describeForStatusBar(localizedContext, readout)
            result.delayMillis == 0L -> dataSource.getString(R.string.connection_test_untested)
            else -> {
                val detail = result.errorMessage.ifBlank {
                    dataSource.getString(R.string.connection_test_empty_message)
                }
                dataSource.getString(R.string.connection_test_error, detail)
            }
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshServiceState -> dataSource.queryServiceState()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub(uiState.value.selectedGroupId)
            is MainAction.UpdateGroupSubscription -> importConfigViaSub(action.groupId)
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.MoveServer -> moveServer(action.groupId, action.fromGuid, action.toGuid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.MoveRefusalShown -> _uiState.update {
                if (it.moveRefusal == action.refusal) it.copy(moveRefusal = null) else it
            }
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent,
            is MainAction.OpenSubscriptionLink -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L,
                testSampleCount = affiliation?.testSampleCount ?: 0,
                testSuccessfulSamples = affiliation?.testSuccessfulSamples ?: 0,
                testJitterMillis = affiliation?.testJitterMillis ?: 0L,
                testKind = affiliation?.testKind.orEmpty(),
                testMedianMillis = affiliation?.testMedianMillis ?: 0L,
                testMaxMillis = affiliation?.testMaxMillis ?: 0L,
                testPartial = affiliation?.testPartial ?: false,
            )
        }

    /**
     * PattNG: the servers of a group as read, with how many of its moves were settled then, and the number of the reading,
     * see [updateGroupUi].
     */
    private class GroupReading(val servers: List<ServersCache>, val settledMoves: Int, val number: Int)

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): GroupReading {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            // PattNG: under the group's load lock, which a move is stored under too, see [storeMove], and numbered in turn.
            cacheMutex.withLock {
                groupDataCache[groupId] = servers
                val number = readingNumbers.merge(groupId, 1, Int::plus)!!
                GroupReading(servers, settledMoves.getOrDefault(groupId, 0), number)
            }
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>, filter: String): List<ServersCache> {
        val keyword = filter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    /**
     * Shows [reading] of the group [groupId]. PattNG: as [readingFate] decides: not over a newer reading shown, and not
     * when a move of the group was shown that was not settled when the group was read, which the reading would undo on
     * the screen; the group is then shown anew once its moves are settled, see [moveServer], or at once when they are
     * by now. Nor when the search changed while it was filtered: the group is then read and filtered anew.
     */
    private fun updateGroupUi(groupId: String, reading: GroupReading) {
        val filter = keywordFilter
        val filteredServers = applyKeywordFilter(reading.servers, filter)
        val state = ServerGroupUiState(
            servers = filteredServers,
            rows = buildServerRows(groupId, filteredServers)
        )
        val readAgain = synchronized(groupShowLock) {
            val fate = readingFate(
                number = reading.number,
                lastShown = shownReadings.getOrDefault(groupId, 0),
                settledWhenRead = reading.settledMoves,
                shownMoves = shownMoves.getOrDefault(groupId, 0),
                unsettledMoves = unstoredMoves.getOrDefault(groupId, 0),
                filteredAsSearched = filter == keywordFilter,
            )
            when (fate) {
                ReadingFate.SHOW -> {
                    shownReadings[groupId] = reading.number
                    mutableServerGroupState(groupId).value = state
                }
                ReadingFate.HOLD_UNTIL_SETTLED -> groupsReadEarly += groupId
                ReadingFate.OVERTAKEN, ReadingFate.READ_AGAIN -> Unit
            }
            fate == ReadingFate.READ_AGAIN
        }
        if (readAgain) viewModelScope.launch { showGroupAnew(groupId) }
    }

    /** PattNG: reads the group [groupId] anew and shows it, see [updateGroupUi]. */
    private suspend fun showGroupAnew(groupId: String) {
        val reading = withContext(ioDispatcher) { loadGroup(groupId) }
        withContext(defaultDispatcher) { updateGroupUi(groupId, reading) }
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty(),
                aetherProtocolLabel = aetherProtocolLabel,
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(
                        id = it.guid,
                        remarks = it.subscription.remarks,
                        usageInfo = it.subscription.usageInfo?.let { info ->
                            SubscriptionInfoParser.withWebPageFallback(info, it.subscription.url)
                        },
                    )
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub(subId: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            // PattNG: what the storage removed, a profile it refused to remove staying.
                            currentServers().count { dataSource.removeServer(it.guid) }
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    // PattNG: what the storage removed, a profile it refused to remove staying.
                    val removed = duplicates.count { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, removed))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            // PattNG: with how many of each group's moves were settled when it was cached, and the number of the reading it
            // is, see [updateGroupUi].
            val snapshot = cacheMutex.withLock {
                groupDataCache.mapValues { (groupId, servers) ->
                    GroupReading(servers, settledMoves.getOrDefault(groupId, 0), readingNumbers.getOrDefault(groupId, 0))
                }
            }
            ensureActive()
            snapshot.forEach { (groupId, reading) ->
                ensureActive()
                updateGroupUi(groupId, reading)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            // PattNG: a removal the storage refused is told; the list, shown anew, keeps the profile.
            if (!dataSource.removeServer(guid)) toastError(R.string.toast_failure)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    /**
     * Shows the profile [fromGuid] names where the one [toGuid] names stands, and stores it there; the job storing it,
     * null when nothing moved. PattNG: by the two guids, see [storeMove], even when the screen closes right after. A
     * move the storage refused is told, see [MainUiState.moveRefusal], once, when the group's last move asked for is
     * stored, and the groups are shown anew, as stored, so that the reading comes after every move queued behind the
     * refused one. Not in the list of every group, [groupId] empty, which is no stored list.
     */
    fun moveServer(groupId: String, fromGuid: String, toGuid: String): Job? {
        if (groupId.isEmpty()) return null
        synchronized(groupShowLock) {
            val groupState = mutableServerGroupState(groupId).value
            val servers = groupState.servers.toMutableList()
            val fromPosition = servers.indexOfFirst { it.guid == fromGuid }
            val toPosition = servers.indexOfFirst { it.guid == toGuid }
            if (!servers.moveItem(fromPosition, toPosition)) return null
            val rows = groupState.rows.toMutableList()
            rows.moveItem(fromPosition, toPosition)
            mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
            shownMoves.merge(groupId, 1, Int::plus)
            unstoredMoves.merge(groupId, 1, Int::plus)
        }
        // A drag emits several moves; each is stored once the one before it is, in the order they were made.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        // PattNG: begun at once, on the main thread the move comes on, and the store kept from cancellation, so that a
        // screen closing right after neither stops it nor keeps it from starting.
        return viewModelScope.launch {
            val moved = withContext(NonCancellable + ioDispatcher) {
                previousPersistenceJob?.join()
                storeMove(groupId, fromGuid, toGuid)
            }
            if (!moved) refusedMoveGroups += groupId
            // PattNG: once the group's last move asked for is settled: a refusal among them told, and the groups read anew;
            // or else a reading held back for them taken anew, see [updateGroupUi].
            val (refused, readEarly) = synchronized(groupShowLock) {
                if (unstoredMoves.merge(groupId, -1, Int::plus) != 0) {
                    false to false
                } else {
                    refusedMoveGroups.remove(groupId) to groupsReadEarly.remove(groupId)
                }
            }
            if (refused) {
                val refusal = moveRefusals.incrementAndGet()
                _uiState.update { it.copy(moveRefusal = refusal) }
                setupGroupTab(forceRefresh = true)
            } else if (readEarly) {
                showGroupAnew(groupId)
            }
        }.also { serverOrderPersistenceJobs[groupId] = it }
    }

    /**
     * PattNG: stores the move of the profile [fromGuid] names to where the one [toGuid] names stands, in the list of
     * [groupId] as stored then, see [MainDataSource.moveServer], so that a profile an update stored, or a removal took
     * away, meanwhile is not undone, and makes it in the group's cached list too, under the group's load lock, see
     * [loadGroup]: a reading of the group comes before the move is stored or after it is cached, so the cached list stays
     * the stored one; and counts it settled, see [settledMoves]. Whether the storage took it.
     */
    private suspend fun storeMove(groupId: String, fromGuid: String, toGuid: String): Boolean =
        groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }.withLock {
            val moved = dataSource.moveServer(groupId, fromGuid, toGuid)
            cacheMutex.withLock {
                if (moved) {
                    groupDataCache[groupId]?.toMutableList()?.let { cached ->
                        if (cached.moveItem(cached.indexOfFirst { it.guid == fromGuid }, cached.indexOfFirst { it.guid == toGuid })) {
                            groupDataCache[groupId] = cached
                        }
                    }
                }
                // PattNG: settled, stored or refused: a reading from now on has it as stored, see [updateGroupUi].
                settledMoves.merge(groupId, 1, Int::plus)
            }
            moved
        }

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        cancelCurrentTestTimeout()
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false) {
        cancelAllPing()
        val groupId = uiState.value.selectedGroupId
        val servers = currentServers()
        if (servers.isEmpty()) {
            return
        }
        val serverGuids = servers.map { it.guid }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.testDelayMillis == 0L) server else server.copy(
                        testDelayMillis = 0L,
                        testSampleCount = 0,
                        testSuccessfulSamples = 0,
                        testJitterMillis = 0L,
                        testMedianMillis = 0L,
                        testMaxMillis = 0L,
                        testPartial = false,
                    )
                },
                rows = current.rows.map { row ->
                    if (row.testDelayMillis == 0L) row else row.copy(
                        testDelayMillis = 0L,
                        testSampleCount = 0,
                        testSuccessfulSamples = 0,
                        testJitterMillis = 0L,
                        testMedianMillis = 0L,
                        testMaxMillis = 0L,
                        testPartial = false,
                    )
                }
            )
        }
        val request = testRequests.beginBulk(groupId)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = if (keywordFilter.isNotEmpty()) serverGuids else emptyList(),
            onlyTcp = onlyTcp
        )
        _uiState.update {
            it.copy(
                isTesting = true,
                status = MainStatus.Testing
            )
        }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(
                                testDelayMillis = 0L,
                                testSampleCount = 0,
                                testSuccessfulSamples = 0,
                                testJitterMillis = 0L,
                                testMedianMillis = 0L,
                                testMaxMillis = 0L,
                                testPartial = false,
                            )
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        // A tap while a measurement is already on its way asks for the same number twice; the second one
        // is dropped so the bar cannot be left waiting for an answer to a question it stopped owning.
        if (uiState.value.isTesting) return
        // Everything this tap touches — the request registry, the state the bar draws from, the ordered
        // broadcast to the daemon — runs inside one guard: a throw here used to travel out of the click
        // handler on the main thread, which is the app gone and the tunnel with it. A tap that cannot be
        // answered now says so on the bar and leaves the app running.
        try {
            val requestId = testRequests.beginCurrent()
            _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
            dataSource.testCurrentServerRealPing(requestId)
            scheduleCurrentTestTimeout(requestId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            LogUtil.e(AppConfig.TAG, "The status bar's test could not be started", error)
            testRequests.invalidateCurrent()
            cancelCurrentTestTimeout()
            val unanswered = ConnectionTestResult(
                delayMillis = -1L,
                errorMessage = dataSource.getString(R.string.connection_test_no_answer_from_service),
            )
            _uiState.update {
                it.copy(isTesting = false, status = MainStatus.ConnectionTest(unanswered))
            }
        }
    }

    /**
     * Puts a bound on how long the status bar may say "Testing…".
     *
     * The daemon answers every request it takes, but a request can also reach no daemon at all — the
     * process died, or the ordered broadcast found nobody — and then nothing would ever come back. This
     * gives that silence an end and a reason a person can read, instead of a bar that waits forever.
     */
    private fun scheduleCurrentTestTimeout(requestId: String) {
        cancelCurrentTestTimeout()
        currentTestTimeoutJob = viewModelScope.launch {
            delay(CURRENT_TEST_TIMEOUT_MS)
            if (!testRequests.completeCurrent(requestId)) return@launch
            val unanswered = ConnectionTestResult(
                delayMillis = -1L,
                errorMessage = dataSource.getString(R.string.connection_test_no_answer_from_service),
            )
            // A daemon that went away while the bar waited: the bar says the service is not there any
            // more, rather than leaving "connected" over a number that never came.
            val stillRunning = askServiceIfRunning()
            _uiState.update {
                it.copy(
                    isTesting = testRequests.isTesting,
                    isRunning = it.isRunning && stillRunning,
                    status = if (stillRunning) {
                        MainStatus.ConnectionTest(unanswered)
                    } else {
                        MainStatus.Disconnected
                    },
                )
            }
        }
    }

    /**
     * Whether the daemon is still running, as the newest state it reported says.
     *
     * The query is fire-and-forget: a daemon that died meanwhile cannot answer it, and the event it
     * would have sent is what changes [uiState]. So this asks, waits a moment for the answer to arrive,
     * and believes the state it then has — rather than running a second clock of its own.
     */
    private suspend fun askServiceIfRunning(): Boolean {
        dataSource.queryServiceState()
        delay(SERVICE_QUERY_SETTLE_MS)
        return uiState.value.isRunning
    }

    private fun cancelCurrentTestTimeout() {
        currentTestTimeoutJob?.cancel()
        currentTestTimeoutJob = null
    }

    private fun onTestsFinished(requestId: String) {
        val request = testRequests.completeBulk(requestId) ?: return
        resetTestStatus()
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
            putFastestFirst(request.groupId)
        }
    }

    /**
     * Uses what a round of measurements just found, instead of leaving it for a menu to be pressed.
     *
     * Every row now carries a real response, so the row with the lowest delay is the link that answered
     * fastest: it is put first, and it becomes the selection. When the person was connected, the daemon
     * is started again so they are on that link and not merely looking at it. A server that never
     * answered has no delay to compare and is never chosen, wherever the sort leaves it.
     */
    private suspend fun putFastestFirst(groupId: String) {
        if (groupId.isBlank() || !dataSource.autoPickFastestAfterTest()) return
        runCatching {
            dataSource.sortByTestResultsForSub(groupId)
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            val fastest = fastestAnsweringServer(mutableServerGroupState(groupId).value.servers) ?: return
            if (fastest.guid == dataSource.getSelectServer()) return
            dataSource.setSelectServer(fastest.guid)
            _uiState.update { it.copy(selectedGuid = fastest.guid) }
            if (uiState.value.isRunning) dataSource.restartService()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            LogUtil.e(AppConfig.TAG, "Failed to put the fastest server first", error)
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = runningStatus(state.status, state.isRunning, running, clearTestingText)
            )
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    /** PattNG: what becomes of a reading of a group, see [readingFate]. */
    internal enum class ReadingFate { SHOW, OVERTAKEN, HOLD_UNTIL_SETTLED, READ_AGAIN }

    companion object {
        private const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L

        /** How long the status bar waits for a state answer before believing the state it already has. */
        private const val SERVICE_QUERY_SETTLE_MS = 400L

        /**
         * How long the status bar waits for the daemon to answer one measurement.
         *
         * Three real samples over a tunnel that is up take a handful of seconds; a link that takes longer
         * than this is not going to be described by a number anyway, and the person is owed the reason
         * instead of a bar that keeps saying "Testing…".
         */
        private const val CURRENT_TEST_TIMEOUT_MS = 20_000L

        /**
         * The server a finished round of measurements puts first: the one whose real response was fastest.
         *
         * A server that got no answer has a delay of zero or less, which is not a measurement but the
         * absence of one, so it is never the fastest however the rows are ordered.
         */
        internal fun fastestAnsweringServer(servers: List<ServersCache>): ServersCache? =
            servers.filter { it.testDelayMillis > 0L }.minByOrNull { it.testDelayMillis }

        /**
         * PattNG: what becomes of a reading of a group numbered [number] when the one shown last is [lastShown], taken
         * when [settledWhenRead] of the group's moves were settled, now that [shownMoves] were shown and [unsettledMoves]
         * of them are not settled yet, and filtered as the search stands now or not, [filteredAsSearched]: one older than
         * the one shown is not shown over it; one that a move shown since it was taken overtook waits until the group's
         * moves are settled, or, when they are by now, is taken anew, as one filtered for a search changed since is;
         * else it is shown.
         */
        internal fun readingFate(
            number: Int,
            lastShown: Int,
            settledWhenRead: Int,
            shownMoves: Int,
            unsettledMoves: Int,
            filteredAsSearched: Boolean,
        ): ReadingFate =
            when {
                number < lastShown -> ReadingFate.OVERTAKEN
                settledWhenRead < shownMoves && unsettledMoves > 0 -> ReadingFate.HOLD_UNTIL_SETTLED
                settledWhenRead < shownMoves || !filteredAsSearched -> ReadingFate.READ_AGAIN
                else -> ReadingFate.SHOW
            }

        /**
         * The status after a running or stopped signal. A test text survives a repeated signal
         * that changes nothing, but a connecting status does not: the daemon repeats the
         * connecting signal right after a running one whenever the tunnel is still on its way.
         */
        internal fun runningStatus(
            current: MainStatus,
            wasRunning: Boolean,
            running: Boolean,
            clearTestingText: Boolean,
        ): MainStatus =
            if (!clearTestingText && wasRunning == running && current !is MainStatus.Connecting) current
            else if (running) MainStatus.Connected else MainStatus.Disconnected
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
