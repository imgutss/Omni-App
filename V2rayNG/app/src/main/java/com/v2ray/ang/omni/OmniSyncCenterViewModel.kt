package com.v2ray.ang.omni

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs real panel operations sequentially because each sync writes the shared subscription/profile store. */
class OmniSyncCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniHubRepository(application)
    private val mutableState = MutableStateFlow(OmniSyncCenterUiState())
    val state: StateFlow<OmniSyncCenterUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (mutableState.value.running || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val panels = repository.panels()
                val ids = panels.mapTo(mutableSetOf()) { it.id }
                mutableState.update {
                    it.copy(
                        panels = panels,
                        selectedPanelIds = reconcileOmniPanelSelection(panels, it.selectedPanelIds),
                        results = it.results.filterKeys { id -> id in ids },
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = OmniSyncMessage.LOAD_FAILED) }
            }
        }
    }

    fun togglePanel(panelId: String) {
        if (mutableState.value.running) return
        mutableState.update { current ->
            if (current.panels.none { it.id == panelId }) current else current.copy(
                selectedPanelIds = current.selectedPanelIds.toMutableSet().apply {
                    if (!add(panelId)) remove(panelId)
                },
                message = null,
            )
        }
    }

    fun toggleAll() {
        if (mutableState.value.running) return
        mutableState.update { current ->
            val allIds = current.panels.mapTo(mutableSetOf()) { it.id }
            current.copy(selectedPanelIds = if (current.selectedPanelIds == allIds) emptySet() else allIds, message = null)
        }
    }

    fun syncSelected() = runSelected(OmniSyncOperation.SYNC, all = false)
    fun checkSelected() = runSelected(OmniSyncOperation.CHECK, all = false)
    fun syncAll() = runSelected(OmniSyncOperation.SYNC, all = true)
    fun checkAll() = runSelected(OmniSyncOperation.CHECK, all = true)
    fun syncPanel(panelId: String) = runSelected(OmniSyncOperation.SYNC, all = false, singleId = panelId)
    fun checkPanel(panelId: String) = runSelected(OmniSyncOperation.CHECK, all = false, singleId = panelId)

    private fun runSelected(operation: OmniSyncOperation, all: Boolean, singleId: String? = null) {
        val snapshot = mutableState.value
        if (snapshot.running) return
        val ids = when {
            singleId != null -> listOf(singleId)
            all -> snapshot.panels.map { it.id }
            else -> snapshot.selectedPanelIds.toList()
        }
        val panels = snapshot.panels.filter { it.id in ids }
        if (panels.isEmpty()) {
            mutableState.update { it.copy(message = OmniSyncMessage.NO_PANEL_SELECTED) }
            return
        }
        viewModelScope.launch {
            val completed = mutableListOf<OmniSyncPanelResult>()
            mutableState.update {
                it.copy(
                    running = true,
                    activePanelId = null,
                    selectedPanelIds = if (all) panels.mapTo(mutableSetOf()) { panel -> panel.id } else it.selectedPanelIds,
                    results = it.results - panels.map { panel -> panel.id }.toSet(),
                    message = null,
                )
            }
            try {
                panels.forEach { panel ->
                    mutableState.update { it.copy(activePanelId = panel.id) }
                    val result = try {
                        when (operation) {
                            OmniSyncOperation.SYNC -> {
                                val count = repository.sync(panel)
                                OmniSyncPanelResult(panel.id, operation, true, count, "Synchronized $count configurations")
                            }
                            OmniSyncOperation.CHECK -> {
                                val health = repository.check(panel)
                                OmniSyncPanelResult(panel.id, operation, health.status == OmniPanelStatus.READY, detail = health.rawDetail)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        OmniSyncPanelResult(panel.id, operation, false, detail = safeError(error))
                    }
                    completed += result
                    mutableState.update { it.copy(results = mergeOmniSyncResult(it.results, result)) }
                }
                if (operation == OmniSyncOperation.SYNC) {
                    OmniOperationNotifier.syncCompleted(getApplication(), completed.size, completed.count { it.success })
                }
                val refreshed = repository.panels()
                val idsNow = refreshed.mapTo(mutableSetOf()) { it.id }
                mutableState.update {
                    it.copy(
                        panels = refreshed,
                        selectedPanelIds = reconcileOmniPanelSelection(refreshed, it.selectedPanelIds),
                        results = it.results.filterKeys { id -> id in idsNow },
                        message = omniSyncRunSummary(completed),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniSyncMessage.LOAD_FAILED) }
            } finally {
                mutableState.update { it.copy(running = false, activePanelId = null) }
            }
        }
    }

    private fun safeError(error: Exception): String = when (error) {
        is OmniHubException -> error.message ?: error.javaClass.simpleName
        else -> error.javaClass.simpleName
    }
}
