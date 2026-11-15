package com.v2ray.ang.omni

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.ui.routing.RoutingEditRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OmniRoutingStudioUiState(
    val rules: List<RulesetItem> = emptyList(),
    val hostInput: String = "example.com",
    val portInput: String = "443",
    val networkInput: String = "tcp",
    val protocolInput: String = "",
    val processInput: String = "",
    val loading: Boolean = false,
    val simulating: Boolean = false,
    val result: OmniRoutingSimulation? = null,
    val message: OmniRoutingStudioMessage? = null,
)

enum class OmniRoutingStudioMessage { LOAD_FAILED, INVALID_INPUT }

class OmniRoutingStudioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RoutingEditRepository()
    private val mutableState = MutableStateFlow(OmniRoutingStudioUiState())
    val state: StateFlow<OmniRoutingStudioUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true || mutableState.value.simulating) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val rules = repository.loadRules()
                mutableState.update { it.copy(rules = rules, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = OmniRoutingStudioMessage.LOAD_FAILED) }
            }
        }
    }

    fun updateHost(value: String) = mutableState.update { it.copy(hostInput = value, result = null, message = null) }
    fun updatePort(value: String) = mutableState.update { it.copy(portInput = value, result = null, message = null) }
    fun updateNetwork(value: String) = mutableState.update { it.copy(networkInput = value, result = null, message = null) }
    fun updateProtocol(value: String) = mutableState.update { it.copy(protocolInput = value, result = null, message = null) }
    fun updateProcess(value: String) = mutableState.update { it.copy(processInput = value, result = null, message = null) }

    fun simulate() {
        val current = mutableState.value
        val port = current.portInput.trim().toIntOrNull()
        if (current.hostInput.isBlank() || port == null || port !in 1..65_535 || current.networkInput.isBlank() || current.simulating) {
            mutableState.update { it.copy(message = OmniRoutingStudioMessage.INVALID_INPUT) }; return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(simulating = true, message = null) }
            try {
                val result = withContext(Dispatchers.Default) {
                    simulateOmniRouting(
                        current.rules,
                        OmniRoutingRequest(current.hostInput, port, current.networkInput, current.protocolInput, current.processInput),
                    )
                }
                mutableState.update { it.copy(result = result, simulating = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(simulating = false, message = OmniRoutingStudioMessage.INVALID_INPUT) }
            }
        }
    }
}
