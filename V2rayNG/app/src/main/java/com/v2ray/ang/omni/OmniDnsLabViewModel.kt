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

class OmniDnsLabViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(OmniDnsLabUiState())
    val state: StateFlow<OmniDnsLabUiState> = mutableState.asStateFlow()
    private var queryJob: Job? = null

    fun updateHost(value: String) {
        if (!mutableState.value.running) mutableState.update { it.copy(hostInput = value, results = emptyMap(), message = null) }
    }

    fun updateCustomUrl(value: String) {
        if (!mutableState.value.running) mutableState.update { it.copy(customUrlInput = value, results = emptyMap(), message = null) }
    }

    fun selectType(type: OmniDnsRecordType) {
        if (!mutableState.value.running) mutableState.update { it.copy(recordType = type, results = emptyMap(), message = null) }
    }

    fun toggleProvider(provider: OmniDnsProvider) {
        if (mutableState.value.running) return
        mutableState.update { current ->
            current.copy(selectedProviders = current.selectedProviders.toMutableSet().apply { if (!add(provider)) remove(provider) }, message = null)
        }
    }

    fun runQueries() {
        val current = mutableState.value
        val host = normalizeOmniDnsName(current.hostInput)
        val providers = OmniDnsProvider.entries.filter { it in current.selectedProviders }
        if (host == null || (OmniDnsProvider.CUSTOM in providers && current.customUrlInput.isBlank())) {
            mutableState.update { it.copy(message = OmniDnsMessage.INVALID_INPUT) }; return
        }
        if (providers.isEmpty()) {
            mutableState.update { it.copy(message = OmniDnsMessage.NO_PROVIDER) }; return
        }
        if (current.running) return
        queryJob = viewModelScope.launch {
            mutableState.update { it.copy(running = true, activeProvider = null, results = emptyMap(), message = null) }
            try {
                providers.forEach { provider ->
                    mutableState.update { it.copy(activeProvider = provider) }
                    val result = try {
                        if (provider == OmniDnsProvider.SYSTEM) {
                            OmniDnsProbe.querySystem(host)
                        } else {
                            val endpoint = if (provider == OmniDnsProvider.CUSTOM) current.customUrlInput else provider.endpoint.orEmpty()
                            OmniDnsProbe.queryDoh(provider, endpoint, host, current.recordType)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        OmniDnsResult(provider, OmniDnsStatus.FAIL, 0L, error = error.javaClass.simpleName)
                    }
                    mutableState.update { it.copy(results = it.results + (provider to result)) }
                }
                mutableState.update {
                    val failed = it.results.values.any { result -> result.status == OmniDnsStatus.FAIL }
                    it.copy(message = if (failed) OmniDnsMessage.PARTIAL_FAILURE else OmniDnsMessage.COMPLETE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                mutableState.update { it.copy(running = false, activeProvider = null) }
            }
        }
    }

    override fun onCleared() {
        queryJob?.cancel()
        super.onCleared()
    }
}
