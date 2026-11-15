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

class OmniPrivacyCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniHubRepository(application)
    private val mutableState = MutableStateFlow(OmniPrivacyCenterUiState())
    val state: StateFlow<OmniPrivacyCenterUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        mutableState.update {
            it.copy(
                notificationsEnabled = OmniOperationNotifier.isEnabled(),
                notificationsSystemEnabled = OmniOperationNotifier.systemEnabled(application),
            )
        }
        refresh()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        val saved = OmniOperationNotifier.setEnabled(enabled)
        mutableState.update {
            it.copy(
                notificationsEnabled = if (saved) enabled else OmniOperationNotifier.isEnabled(),
                message = if (saved) null else OmniPrivacyMessage.PREFERENCE_FAILED,
            )
        }
    }

    fun refreshNotificationState() {
        mutableState.update {
            it.copy(
                notificationsEnabled = OmniOperationNotifier.isEnabled(),
                notificationsSystemEnabled = OmniOperationNotifier.systemEnabled(getApplication()),
            )
        }
    }

    fun refresh() {
        if (mutableState.value.busy || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val panels = repository.panels()
                val scopes = panels.associate { panel -> panel.id to repository.scopedScopes(panel) }
                mutableState.update {
                    it.copy(
                        panels = panels,
                        scopes = scopes,
                        loading = false,
                        notificationsEnabled = OmniOperationNotifier.isEnabled(),
                        notificationsSystemEnabled = OmniOperationNotifier.systemEnabled(getApplication()),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = OmniPrivacyMessage.LOAD_FAILED) }
            }
        }
    }

    fun revokeAll() = revokePanels(omniActivePairedPanelIds(mutableState.value.scopes))

    fun revokePanel(panelId: String) = revokePanels(setOf(panelId))

    private fun revokePanels(panelIds: Set<String>) {
        val snapshot = mutableState.value
        if (snapshot.busy) return
        val panels = snapshot.panels.filter { it.id in panelIds && snapshot.scopes[it.id]?.isNotEmpty() == true }
        if (panels.isEmpty()) {
            mutableState.update { it.copy(message = OmniPrivacyMessage.NO_PAIRINGS) }; return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            var failures = 0
            try {
                panels.forEach { panel ->
                    try {
                        repository.revokeScoped(panel)
                        mutableState.update { it.copy(scopes = it.scopes + (panel.id to emptySet())) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures++
                    }
                }
                mutableState.update { it.copy(message = omniPrivacyRunMessage(panels.size, failures)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun forgetAllLocal() {
        val panels = mutableState.value.panels
        if (mutableState.value.busy) return
        if (panels.isEmpty()) {
            mutableState.update { it.copy(message = OmniPrivacyMessage.NO_PAIRINGS) }; return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            var failures = 0
            try {
                panels.forEach { panel ->
                    try {
                        repository.forgetScopedSession(panel)
                        mutableState.update { it.copy(scopes = it.scopes + (panel.id to emptySet())) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures++
                    }
                }
                mutableState.update { it.copy(message = if (failures == 0) OmniPrivacyMessage.LOCAL_FORGOTTEN else OmniPrivacyMessage.FORGET_FAILED) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun forgetPanelLocal(panelId: String) {
        val panel = mutableState.value.panels.firstOrNull { it.id == panelId } ?: return
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            try {
                repository.forgetScopedSession(panel)
                mutableState.update { it.copy(scopes = it.scopes + (panel.id to emptySet()), message = OmniPrivacyMessage.LOCAL_FORGOTTEN) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniPrivacyMessage.FORGET_FAILED) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
