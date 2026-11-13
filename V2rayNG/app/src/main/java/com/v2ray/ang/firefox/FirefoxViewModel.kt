package com.v2ray.ang.firefox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.FirefoxCoreManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The account screen's work: start the engine, keep its account list, and carry a sign-in through
 * whatever Mozilla asks for in the middle of it.
 *
 * Nothing the account contains is kept in this app: the engine holds the tokens in the app's private
 * folder and answers questions over loopback, so the screen only ever holds what it is going to show
 * — an address, a share of an allowance, and the step a sign-in is waiting on.
 */
class FirefoxViewModel(application: Application) : AndroidViewModel(application) {

    /** Whether this phone can carry the account path at all. */
    enum class Engine {
        /** The engine is being started, or the last start has not answered yet. */
        STARTING,

        /** The engine is up and answered on loopback. */
        READY,

        /** This build has no engine, so the row cannot carry traffic; signing in would be a dead end. */
        MISSING,
    }

    data class UiState(
        val engine: Engine = Engine.STARTING,
        val port: Int = FirefoxBridge.DEFAULT_PORT,
        val accounts: List<FirefoxEngineApi.Account> = emptyList(),
        val signIn: FirefoxEngineApi.SignIn? = null,
        val busy: Boolean = false,
        val problem: String = "",
    )

    private val mutableState = MutableStateFlow(UiState(port = FirefoxBridge.port()))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
    }

    /**
     * Brings the engine up when this build has one, and reads the accounts it keeps.
     *
     * The screen works with nothing connected, so it starts the engine itself: a person can sign in
     * and read their allowance before they ever connect the row.
     */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val supported = FirefoxCoreManager.isSupported(getApplication())
            val up = supported && FirefoxEngineApi.ensureEngine(getApplication())
            val accounts = if (up) FirefoxEngineApi.accounts(getApplication()).orEmpty() else emptyList()
            if (up) {
                // The row the connection list offers is created as soon as the engine can carry it, so a
                // person who opened this screen finds "Firefox" in their groups instead of having to
                // wonder where it went.
                runCatching { FirefoxBridge.ensureGroup() }.onFailure { error ->
                    LogUtil.w("Firefox", "the account group could not be created", error)
                }
            }
            mutableState.update {
                it.copy(
                    engine = when {
                        !supported -> Engine.MISSING
                        up -> Engine.READY
                        else -> Engine.MISSING
                    },
                    accounts = accounts,
                    busy = false,
                    // A start that failed says why, in words, instead of only that it failed.
                    problem = if (up) "" else FirefoxCoreManager.lastFailure(getApplication()),
                )
            }
        }
    }

    /**
     * Starts a sign-in with the address and password a person typed, then follows it: Mozilla asks for
     * a code or a CAPTCHA in the middle of a sign-in often enough that waiting on one is the normal
     * path, not the exceptional one.
     */
    fun signIn(email: String, password: String) {
        val address = email.trim()
        if (address.isBlank() || password.isBlank()) return
        pollJob?.cancel()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val started = FirefoxEngineApi.startSignIn(getApplication(), address, password)
            if (started == null) {
                mutableState.update {
                    it.copy(busy = false, problem = "the account engine did not answer")
                }
                return@launch
            }
            mutableState.update { it.copy(busy = false, signIn = started) }
            follow(started.id)
        }
    }

    /** Posts what Mozilla asked for — a code from the mailbox, from an authenticator, a CAPTCHA. */
    fun answer(value: String) {
        val id = mutableState.value.signIn?.id ?: return
        if (value.isBlank()) return
        pollJob?.cancel()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val next = FirefoxEngineApi.answer(getApplication(), id, value.trim())
            if (next == null) {
                mutableState.update { it.copy(busy = false, problem = "the account engine did not answer") }
                return@launch
            }
            mutableState.update { it.copy(busy = false, signIn = next, problem = next.error) }
            if (!next.finished) follow(id)
        }
    }

    /** Leaves a sign-in half-done; nothing is stored and no account is added. */
    fun cancelSignIn() {
        val id = mutableState.value.signIn?.id ?: return
        pollJob?.cancel()
        viewModelScope.launch {
            FirefoxEngineApi.cancel(getApplication(), id)
            mutableState.update { it.copy(signIn = null, problem = "", busy = false) }
        }
    }

    /** Makes [id] the account the connection spends. */
    fun activate(id: String) = act { FirefoxEngineApi.activate(getApplication(), id) }

    /** Reads [id]'s allowance from Mozilla again. */
    fun refreshQuota(id: String) = act { FirefoxEngineApi.refreshQuota(getApplication(), id) }

    /** Forgets [id] on this phone. */
    fun remove(id: String) = act { FirefoxEngineApi.remove(getApplication(), id) }

    private fun act(call: suspend () -> String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val problem = call()
            val accounts = FirefoxEngineApi.accounts(getApplication()).orEmpty()
            mutableState.update { it.copy(busy = false, accounts = accounts, problem = problem.orEmpty()) }
        }
    }

    /** Asks a running sign-in where it stands until it finishes, fails, or the person gives up. */
    private fun follow(id: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + SIGN_IN_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
                val next = FirefoxEngineApi.signIn(getApplication(), id) ?: continue
                mutableState.update { it.copy(signIn = next, problem = next.error) }
                if (next.finished || next.failed) break
                // A step that asks something of the person is where the following stops: they type,
                // and the next step begins when the answer is posted.
                if (next.needsAnswer) break
            }
            if (mutableState.value.signIn?.finished == true) {
                val accounts = FirefoxEngineApi.accounts(getApplication()).orEmpty()
                mutableState.update { it.copy(accounts = accounts, signIn = null, problem = "") }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** How often a sign-in is asked where it stands while a person goes to the mailbox for a code. */
        private const val POLL_INTERVAL_MS = 1_500L

        /** How long a sign-in may wait for the person at all. */
        private const val SIGN_IN_BUDGET_MS = 6 * 60 * 1_000L
    }
}
