package com.v2ray.ang.firefox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.FirefoxCoreManager
import com.v2ray.ang.firefox.hub.FirefoxRecipes
import com.v2ray.ang.firefox.hub.QuotaMath
import com.v2ray.ang.firefox.shield.Blackbox
import com.v2ray.ang.firefox.shield.FirefoxAutoPilot
import com.v2ray.ang.firefox.shield.FirefoxQuotaGuard
import com.v2ray.ang.firefox.shield.HybridShield
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/**
 * The Firefox Hub's work: bring the engine up, keep its live picture current, and carry out what the
 * person asks for — connect, a country, a recipe, a doctor run, an account.
 *
 * Nothing an account contains is kept here. The engine holds the tokens in the app's private folder
 * and answers over loopback; this holds only what the screen shows: a phase, a share of an allowance,
 * a latency, the step a sign-in waits on.
 */
class FirefoxHubViewModel(application: Application) : AndroidViewModel(application) {

    enum class Engine { STARTING, READY, MISSING }

    data class UiState(
        val engine: Engine = Engine.STARTING,
        val port: Int = FirefoxBridge.DEFAULT_PORT,
        val accounts: List<FirefoxEngineApi.Account> = emptyList(),
        val signIn: FirefoxEngineApi.SignIn? = null,
        val busy: Boolean = false,
        val problem: String = "",
        val message: String = "",
        val live: FirefoxEngineApi.LiveStatus? = null,
        val serviceRunning: Boolean = false,
        val todayUsageBytes: Long = 0L,
        val locations: List<FirefoxEngineApi.Location> = emptyList(),
        val pinging: Boolean = false,
        val pingedAtMillis: Long = 0L,
        val doctor: FirefoxEngineApi.DoctorReport? = null,
        val doctorRunning: Boolean = false,
        val dna: FirefoxEngineApi.NetworkDna? = null,
        val autoBest: Boolean = false,
        val countryLock: String = "",
        val favorites: Set<String> = emptySet(),
        val quotaAutopilot: Boolean = true,
        val sessionGuardian: Boolean = true,
        val hybridShield: Boolean = true,
        val recipe: String = "",
        val events: List<String> = emptyList(),
    )

    private val mutableState = MutableStateFlow(UiState(port = FirefoxBridge.port()))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var pollJob: Job? = null
    private var signInJob: Job? = null

    init {
        refresh()
    }

    /** Starts the engine if this build has one, then reads the first picture. */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val app = getApplication<Application>()
            val supported = FirefoxCoreManager.isSupported(app)
            val up = supported && FirefoxEngineApi.ensureEngine(app)
            if (up) {
                runCatching { FirefoxBridge.ensureGroup() }
                    .onFailure { LogUtil.w(TAG, "the Firefox group could not be created", it) }
            }
            mutableState.update {
                it.copy(
                    engine = if (up) Engine.READY else Engine.MISSING,
                    busy = false,
                    problem = if (up) "" else FirefoxCoreManager.lastFailure(app),
                )
            }
            readPreferences()
            if (up) {
                readAccounts()
                readLocations()
                tick()
            }
        }
    }

    /** Polls while the screen is visible: quickly while a tunnel is up, slowly when it is not. */
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                tick()
                val running = mutableState.value.live?.phase == PHASE_RUNNING
                delay(if (running) POLL_RUNNING_MS else POLL_IDLE_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One refresh of the live picture, the allowance spent today, and the recorder's tail. */
    private suspend fun tick() {
        val app = getApplication<Application>()
        if (mutableState.value.engine != Engine.READY) return
        val live = FirefoxEngineApi.liveStatus(app)
        val running = CoreServiceManager.isServiceRunning()
        mutableState.update {
            it.copy(
                live = live ?: it.live,
                serviceRunning = running,
                events = Blackbox.entries().takeLast(EVENT_TAIL),
            )
        }
        if (live != null) accumulateToday(live.upBytes + live.downBytes)
    }

    /**
     * Today's usage, kept across engine restarts.
     *
     * The engine's counters belong to one process run, so a restart would make "today" jump back to
     * zero. The last total is remembered: a smaller total means a new run, and the difference is added
     * to the day's accumulator; a new day starts the accumulator over.
     */
    private fun accumulateToday(total: Long) {
        if (total < 0) return
        val today = todayKey()
        val storedDay = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_USAGE_DAY).orEmpty()
        var counted = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_USAGE_BYTES)?.toLongOrNull() ?: 0L
        var last = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_USAGE_LAST_TOTAL)?.toLongOrNull() ?: 0L
        if (storedDay != today) {
            counted = 0L
            last = total
            MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_USAGE_DAY, today)
        } else if (total < last) {
            // A new engine run: everything it counts from now belongs to today as well.
            counted += total
            last = total
        } else {
            counted += total - last
            last = total
        }
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_USAGE_BYTES, counted)
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_USAGE_LAST_TOTAL, last)
        mutableState.update { it.copy(todayUsageBytes = counted) }
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"
    }

    // ---- connect ----

    /**
     * Selects the Firefox row so the service starts on it, and returns its guid. The Activity then
     * asks Android for the VPN permission and starts the ordinary service.
     */
    fun prepareConnect(): String? {
        val app = getApplication<Application>()
        if (mutableState.value.engine != Engine.READY) {
            mutableState.update { it.copy(problem = "موتور فایرفاکس روی این گوشی بالا نیامده است.") }
            return null
        }
        if (!FirefoxCoreManager.hasAccount(app)) {
            mutableState.update { it.copy(problem = "اول با حساب Firefox خودت وارد شو.") }
            return null
        }
        val guid = runCatching { FirefoxBridge.ensureGroup() }.getOrNull()
        if (guid.isNullOrBlank()) {
            mutableState.update { it.copy(problem = "ردیف فایرفاکس ساخته نشد؛ دوباره تلاش کن.") }
            return null
        }
        MmkvManager.setSelectServer(guid)
        Blackbox.record("connect_requested", "guid=$guid")
        return guid
    }

    /** Restarts the engine's proxy in place, without dropping the phone's tunnel. */
    fun restartProxy() = act("پراکسی از نو راه‌اندازی شد.") {
        FirefoxEngineApi.restartProxy(getApplication())
    }

    /** Runs the engine's own end-to-end test and shows what it found. */
    fun runConnectionTest() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = "", problem = "") }
            val result = FirefoxEngineApi.connectionTest(getApplication())
            mutableState.update {
                it.copy(
                    busy = false,
                    message = when {
                        result == null -> ""
                        result.ok -> "تونل سالم است؛ خروجی ${result.exitIpv4} (${result.exitCountry}) در ${result.totalMs} میلی‌ثانیه."
                        else -> ""
                    },
                    problem = when {
                        result == null -> "موتور به تست پاسخ نداد."
                        !result.ok -> com.v2ray.ang.firefox.hub.FirefoxLabels.failure(result.error)
                            .ifBlank { result.error }
                        else -> ""
                    },
                )
            }
        }
    }

    // ---- doctor / network ----

    fun runDoctor(live: Boolean) {
        viewModelScope.launch {
            mutableState.update { it.copy(doctorRunning = true, problem = "") }
            val app = getApplication<Application>()
            val report = FirefoxEngineApi.doctor(app, live)
            val dna = FirefoxEngineApi.networkDna(app)
            if (dna != null) {
                MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_NETWORK_DNA, dna.recommendation)
            }
            report?.let { Blackbox.record("doctor", "ok=${it.ok} rec=${it.recommendation}") }
            mutableState.update {
                it.copy(
                    doctorRunning = false,
                    doctor = report ?: it.doctor,
                    dna = dna ?: it.dna,
                    problem = if (report == null) "پزشک اتصال پاسخ نداد؛ موتور را بررسی کن." else "",
                )
            }
        }
    }

    fun refreshNetworkDna() {
        viewModelScope.launch {
            val dna = FirefoxEngineApi.networkDna(getApplication())
            mutableState.update {
                it.copy(dna = dna ?: it.dna, problem = if (dna == null) "موتور وضعیت شبکه را برنگرداند." else "")
            }
        }
    }

    // ---- locations ----

    private suspend fun readLocations() {
        val locations = FirefoxEngineApi.locations(getApplication()) ?: return
        mutableState.update { it.copy(locations = locations) }
    }

    fun pingAll() {
        viewModelScope.launch {
            mutableState.update { it.copy(pinging = true, problem = "") }
            val result = FirefoxEngineApi.pingLocations(getApplication())
            val merged = mergeLocations(result?.locations.orEmpty())
            mutableState.update {
                it.copy(
                    pinging = false,
                    locations = merged,
                    pingedAtMillis = result?.pingedAtMillis ?: it.pingedAtMillis,
                    problem = if (result == null) "سنجش تأخیر پاسخ نداد." else "",
                )
            }
        }
    }

    private fun mergeLocations(measured: List<FirefoxEngineApi.Location>): List<FirefoxEngineApi.Location> {
        if (measured.isEmpty()) return mutableState.value.locations
        val byCode = measured.associateBy { it.code.uppercase() }
        val existing = mutableState.value.locations
        if (existing.isEmpty()) return measured
        return existing.map { byCode[it.code.uppercase()] ?: it } +
            measured.filter { m -> existing.none { it.code.equals(m.code, true) } }
    }

    fun selectLocation(code: String) = act("کشور خروجی تغییر کرد.") {
        FirefoxEngineApi.setLocation(getApplication(), code)
    }

    fun toggleFavorite(code: String) {
        FirefoxAutoPilot.toggleFavorite(code)
        readPreferences()
    }

    fun setCountryLock(code: String) {
        FirefoxAutoPilot.setCountryLock(code)
        readPreferences()
    }

    fun setAutoBest(value: Boolean) {
        FirefoxAutoPilot.setAutoBestEnabled(value)
        readPreferences()
    }

    // ---- accounts ----

    private suspend fun readAccounts() {
        val accounts = FirefoxEngineApi.accounts(getApplication()) ?: return
        mutableState.update { it.copy(accounts = accounts) }
    }

    fun signIn(email: String, password: String) {
        val address = email.trim()
        if (address.isBlank() || password.isBlank()) return
        signInJob?.cancel()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val started = FirefoxEngineApi.startSignIn(getApplication(), address, password)
            if (started == null) {
                mutableState.update { it.copy(busy = false, problem = "موتور حساب پاسخ نداد.") }
                return@launch
            }
            mutableState.update { it.copy(busy = false, signIn = started) }
            follow(started.id)
        }
    }

    fun answer(value: String) {
        val id = mutableState.value.signIn?.id ?: return
        if (value.isBlank()) return
        signInJob?.cancel()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val next = FirefoxEngineApi.answer(getApplication(), id, value.trim())
            if (next == null) {
                mutableState.update { it.copy(busy = false, problem = "موتور حساب پاسخ نداد.") }
                return@launch
            }
            mutableState.update { it.copy(busy = false, signIn = next, problem = next.error) }
            if (!next.finished) follow(id)
        }
    }

    fun cancelSignIn() {
        val id = mutableState.value.signIn?.id ?: return
        signInJob?.cancel()
        viewModelScope.launch {
            FirefoxEngineApi.cancel(getApplication(), id)
            mutableState.update { it.copy(signIn = null, problem = "", busy = false) }
        }
    }

    private fun follow(id: String) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + SIGN_IN_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                delay(SIGN_IN_POLL_MS)
                val next = FirefoxEngineApi.signIn(getApplication(), id) ?: continue
                mutableState.update { it.copy(signIn = next, problem = next.error) }
                if (next.finished || next.failed || next.needsAnswer) break
            }
            if (mutableState.value.signIn?.finished == true) {
                readAccounts()
                Blackbox.record("account_signed_in")
                mutableState.update { it.copy(signIn = null, problem = "", message = "حساب اضافه شد.") }
            }
        }
    }

    fun activate(id: String) = accountAct { FirefoxEngineApi.activate(getApplication(), id) }
    fun refreshQuota(id: String) = accountAct { FirefoxEngineApi.refreshQuota(getApplication(), id) }
    fun remove(id: String) = accountAct { FirefoxEngineApi.remove(getApplication(), id) }
    fun setAccountEnabled(id: String, enabled: Boolean) = accountAct {
        FirefoxEngineApi.setAccountEnabled(getApplication(), id, enabled)
    }

    private fun accountAct(call: suspend () -> String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "") }
            val problem = call()
            readAccounts()
            mutableState.update { it.copy(busy = false, problem = problem.orEmpty()) }
        }
    }

    // ---- recipes and shields ----

    fun applyRecipe(recipe: FirefoxRecipes.Recipe) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "", message = "") }
            val app = getApplication<Application>()
            val settings = FirefoxEngineApi.settings(app)
            if (settings == null) {
                mutableState.update { it.copy(busy = false, problem = "تنظیمات موتور خوانده نشد.") }
                return@launch
            }
            // Merge the recipe's patch into the engine's nested config sections. The patch is
            // built by FirefoxRecipes.patch (unit-tested) so a field can never land at the top
            // level, which the engine rejects as an unknown field.
            for ((section, fields) in FirefoxRecipes.patch(recipe)) {
                val target = settings.optJSONObject(section) ?: JSONObject()
                for ((key, value) in fields) {
                    if (value is List<*>) {
                        target.put(key, org.json.JSONArray().apply { value.forEach { put(it) } })
                    } else {
                        target.put(key, value)
                    }
                }
                settings.put(section, target)
            }
            val problem = FirefoxEngineApi.updateSettings(app, settings)
            if (problem == null) {
                MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_RECIPE, recipe.id)
                Blackbox.record("recipe_applied", recipe.id)
            }
            mutableState.update {
                it.copy(
                    busy = false,
                    recipe = if (problem == null) recipe.id else it.recipe,
                    message = if (problem == null) "نسخهٔ «${recipeTitle(recipe)}» اعمال شد." else "",
                    problem = problem.orEmpty(),
                )
            }
            tick()
        }
    }

    fun setQuotaAutopilot(value: Boolean) {
        FirefoxQuotaGuard.setEnabled(value)
        readPreferences()
    }

    fun setSessionGuardian(value: Boolean) {
        FirefoxAutoPilot.setSessionGuardianEnabled(value)
        readPreferences()
    }

    fun setHybridShield(value: Boolean) {
        HybridShield.setEnabled(value)
        readPreferences()
    }

    fun clearEvents() {
        Blackbox.clear()
        mutableState.update { it.copy(events = emptyList(), message = "ثبت رویدادها پاک شد.") }
    }

    /** The recorder's text, for sharing when something went wrong. */
    fun exportEvents(): String = Blackbox.export()

    fun clearMessage() = mutableState.update { it.copy(message = "", problem = "") }

    private fun readPreferences() {
        mutableState.update {
            it.copy(
                autoBest = FirefoxAutoPilot.autoBestEnabled(),
                countryLock = FirefoxAutoPilot.countryLock(),
                favorites = FirefoxAutoPilot.favorites(),
                quotaAutopilot = FirefoxQuotaGuard.enabled(),
                sessionGuardian = FirefoxAutoPilot.sessionGuardianEnabled(),
                hybridShield = HybridShield.enabled(),
                recipe = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_RECIPE).orEmpty(),
                todayUsageBytes = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_USAGE_BYTES)
                    ?.toLongOrNull() ?: it.todayUsageBytes,
                events = Blackbox.entries().takeLast(EVENT_TAIL),
            )
        }
    }

    private fun act(success: String, call: suspend () -> String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, problem = "", message = "") }
            val problem = call()
            mutableState.update {
                it.copy(busy = false, problem = problem.orEmpty(), message = if (problem == null) success else "")
            }
            tick()
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        signInJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "FirefoxHub"
        private const val PHASE_RUNNING = "running"
        private const val POLL_RUNNING_MS = 2_000L
        private const val POLL_IDLE_MS = 5_000L
        private const val SIGN_IN_POLL_MS = 1_500L
        private const val SIGN_IN_BUDGET_MS = 6 * 60 * 1_000L
        private const val EVENT_TAIL = 8

        fun recipeTitle(recipe: FirefoxRecipes.Recipe): String = when (recipe) {
            FirefoxRecipes.Recipe.STREAMING -> "تماشای فیلم"
            FirefoxRecipes.Recipe.GAMING -> "بازی"
            FirefoxRecipes.Recipe.PRIVACY -> "حریم خصوصی"
            FirefoxRecipes.Recipe.SAVE_QUOTA -> "صرفه‌جویی سهمیه"
            FirefoxRecipes.Recipe.BYPASS -> "عبور از فیلترینگ"
        }

        fun recipeSubtitle(recipe: FirefoxRecipes.Recipe): String = when (recipe) {
            FirefoxRecipes.Recipe.STREAMING -> "تونل پایدار HTTP/2 با DNS خودترمیم"
            FirefoxRecipes.Recipe.GAMING -> "سریع‌ترین کشور به‌صورت خودکار"
            FirefoxRecipes.Recipe.PRIVACY -> "فقط DNS رمزنگاری‌شده، بدون مسیر ساده"
            FirefoxRecipes.Recipe.SAVE_QUOTA -> "تا ۹۵٪ سهمیه روی همین حساب می‌ماند"
            FirefoxRecipes.Recipe.BYPASS -> "همهٔ مسیرهای DNS، شکستن Hello و رلهٔ پشتیبان"
        }

        /** Today's usage as the ring's tiles show it. */
        fun todayLabel(bytes: Long): String = QuotaMath.formatBytes(bytes)
    }
}
