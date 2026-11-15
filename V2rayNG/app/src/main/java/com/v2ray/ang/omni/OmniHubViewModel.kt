package com.v2ray.ang.omni

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns durable Hub operation state; Compose only renders it and sends domain-ID actions. */
data class OmniHubSyncOutcome(val attempted: Int, val succeeded: Int) {
    val failed: Int get() = (attempted - succeeded).coerceAtLeast(0)
    val allSucceeded: Boolean get() = attempted > 0 && succeeded == attempted
}

data class OmniHubUiState(
    val panels: List<OmniPanel> = emptyList(),
    val scopedScopes: Map<String, Set<String>> = emptyMap(),
    val scopedSessions: Map<String, OmniScopedSessionInfo> = emptyMap(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class OmniHubViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniHubRepository(application)
    private val mutableState = MutableStateFlow(OmniHubUiState())
    val state: StateFlow<OmniHubUiState> = mutableState.asStateFlow()
    private var operationJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (mutableState.value.busy || operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null, messageIsError = false) }
            try {
                reloadInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = safeError(error), messageIsError = true) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun addSubscription(name: String, url: String, insecure: Boolean) = runOperation {
        repository.addSubscription(name, url, insecure)
        getString(R.string.omni_hub_added)
    }

    fun pairWithCode(base: String, code: String, name: String, insecure: Boolean) = runOperation {
        repository.pairWithCode(base, code, name, insecure)
        getString(R.string.omni_hub_added)
    }

    fun sync(panelId: String) = runOperation {
        val panel = requirePanel(panelId)
        val count = repository.sync(panel)
        getString(R.string.omni_hub_synced, count)
    }

    fun check(panelId: String) = runOperation {
        val health = repository.check(requirePanel(panelId))
        health.rawDetail
    }

    fun remove(panelId: String) = runOperation {
        repository.remove(requirePanel(panelId))
        getString(R.string.omni_hub_removed)
    }

    fun revokeScoped(panelId: String) = runOperation {
        repository.revokeScoped(requirePanel(panelId))
        getString(R.string.omni_hub_revoked)
    }

    fun readScopedTemplates(panelId: String) = runOperation {
        val templates = repository.readScopedTemplates(requirePanel(panelId))
        getString(R.string.omni_hub_templates_read, templates.uriCount, templates.groups.size)
    }

    fun reportDevice(panelId: String) = runOperation {
        repository.reportDevice(requirePanel(panelId), BuildConfig.VERSION_NAME, Build.MODEL, Build.VERSION.RELEASE)
        getString(R.string.omni_hub_device_reported)
    }

    /** Sequential writes protect the shared subscription/profile store; every panel gets a result. */
    fun syncAll() {
        val snapshot = mutableState.value
        if (snapshot.busy || operationJob?.isActive == true || snapshot.panels.isEmpty()) return
        operationJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null, messageIsError = false) }
            var succeeded = 0
            try {
                snapshot.panels.forEach { panel ->
                    try {
                        repository.sync(panel)
                        succeeded++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Continue with the remaining panels; the summary exposes the partial failure.
                    }
                }
                reloadInternal()
                val outcome = OmniHubSyncOutcome(snapshot.panels.size, succeeded)
                mutableState.update {
                    it.copy(
                        message = if (outcome.allSucceeded) getString(R.string.omni_hub_sync_finished) else getString(R.string.omni_hub_sync_partial, succeeded, outcome.attempted),
                        messageIsError = !outcome.allSucceeded,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = safeError(error), messageIsError = true) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun runOperation(operation: suspend () -> String) {
        if (mutableState.value.busy || operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null, messageIsError = false) }
            try {
                val message = operation()
                reloadInternal()
                mutableState.update { it.copy(message = message, messageIsError = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = safeError(error), messageIsError = true) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun reloadInternal() {
        val panels = repository.panels()
        val scopes = mutableMapOf<String, Set<String>>()
        val sessions = mutableMapOf<String, OmniScopedSessionInfo>()
        panels.forEach { panel ->
            val session = repository.scopedSessionInfo(panel)
            if (session != null) sessions[panel.id] = session
            scopes[panel.id] = session?.takeIf { it.isActive(System.currentTimeMillis()) }?.scopes.orEmpty()
        }
        mutableState.update {
            it.copy(
                panels = panels,
                scopedScopes = scopes,
                scopedSessions = sessions,
                loading = false,
            )
        }
    }

    private fun requirePanel(panelId: String): OmniPanel =
        mutableState.value.panels.firstOrNull { it.id == panelId }
            ?: throw OmniHubException("The selected panel no longer exists")

    private fun safeError(error: Exception): String {
        val generic = getString(R.string.omni_hub_operation_failed)
        return (error as? OmniHubException)?.message
            ?.let(::omniSafePanelStatusDetail)
            ?.takeIf { it.isNotBlank() }
            ?: generic
    }

    private fun getString(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
}
