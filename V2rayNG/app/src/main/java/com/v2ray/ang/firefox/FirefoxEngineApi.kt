package com.v2ray.ang.firefox

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.FirefoxCoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * This app's calls to the account engine on this phone.
 *
 * The engine owns everything about the account: the sign-in, the tokens, the connection pass and the
 * accounting. This class is only how the screen asks it questions over loopback, and every question
 * carries the token the engine wrote into the app's own folder — so no other app on the phone can
 * add an account, read an allowance, or spend somebody's monthly data.
 *
 * Nothing here talks to an Omni panel, a bot or a relay. The only hosts involved are Mozilla's own,
 * and they are the engine's business, not this class's.
 */
object FirefoxEngineApi {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val CALL_TIMEOUT_SECONDS = 90L

    private val json = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** One account as the engine keeps it: what a person needs to see, and nothing that opens it. */
    data class Account(
        val id: String,
        val email: String,
        val active: Boolean,
        val enabled: Boolean,
        val status: String,
        val reason: String,
        val reasonCode: String,
        private val reportedUsedPercent: Double,
        val limitBytes: Long,
        val remainingBytes: Long,
        val unlimited: Boolean,
        val resetAtMillis: Long,
    ) {
        val usedBytes: Long
            get() = if (unlimited || limitBytes <= 0 || remainingBytes < 0) 0L else (limitBytes - remainingBytes).coerceAtLeast(0L)

        /** How much of the allowance is spent, worked out here when the engine does not report it. */
        val usedPercent: Double
            get() = when {
                unlimited || limitBytes <= 0 -> 0.0
                reportedUsedPercent > 0.0 -> reportedUsedPercent
                else -> (usedBytes * 100.0 / limitBytes).coerceIn(0.0, 100.0)
            }
    }

    /**
     * One interactive sign-in.
     *
     * Mozilla asks for something in the middle of a sign-in often enough — a code from the mailbox, a
     * code from an authenticator, an unblock code, a CAPTCHA — that the engine answers with the step
     * it is waiting on instead of failing. The screen shows [message] and, while [needsAnswer] is
     * true, asks for the answer and posts it back.
     */
    data class SignIn(
        val id: String,
        val step: String,
        val message: String,
        val error: String,
        val captcha: String,
    ) {
        val finished: Boolean get() = step == STEP_DONE
        val needsAnswer: Boolean get() = step.startsWith("need_")
        val failed: Boolean get() = step == STEP_FAILED || error.isNotBlank()

        companion object {
            const val STEP_DONE = "done"
            const val STEP_FAILED = "failed"
        }
    }

    /** Whether the engine is up and answering, and on which port. */
    data class Status(val phase: String, val version: String, val message: String)

    /**
     * Starts the engine when it is not up and waits for it to answer.
     *
     * The account screen requests the engine service in the VPN daemon process, so a later VPN
     * connection sees the same authenticated engine rather than a process-local false negative.
     * The wait is bounded, and a start that never answers is reported as one.
     */
    suspend fun ensureEngine(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (FirefoxCoreManager.isSupported(context) && FirefoxCoreManager.isAlive(context) &&
            token(context) != null && status(context) != null
        ) {
            return@withContext true
        }
        FirefoxCoreManager.ensureStarted(
            context,
            socksPort = FirefoxBridge.port(),
            apiPort = FirefoxBridge.DEFAULT_API_PORT,
        ) && token(context) != null && status(context) != null
    }

    suspend fun status(context: Context): Status? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/status", null) ?: return@withContext null
        val root = JSONObject(body)
        val state = root.optJSONObject("state")
        Status(
            phase = state?.optString("phase").orEmpty(),
            version = root.optString("version"),
            message = state?.optString("message").orEmpty(),
        )
    }

    suspend fun accounts(context: Context): List<Account>? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/accounts", null) ?: return@withContext null
        val list: JSONArray = JSONObject(body).optJSONArray("accounts") ?: return@withContext null
        buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val usage = item.optJSONObject("usage")
                add(
                    Account(
                        id = item.optString("id"),
                        email = item.optString("email"),
                        active = item.optBoolean("active"),
                        enabled = item.optBoolean("enabled", true),
                        status = item.optString("status"),
                        reason = item.optString("reason").ifBlank { item.optString("reason_detail") },
                        reasonCode = item.optString("reason_code"),
                        reportedUsedPercent = item.optDouble("used_percent", 0.0),
                        limitBytes = usage?.optLong("limit_bytes", -1L) ?: -1L,
                        remainingBytes = usage?.optLong("remaining_bytes", -1L) ?: -1L,
                        unlimited = usage?.optBoolean("unlimited") ?: false,
                        resetAtMillis = usage?.optString("reset_at")?.let { parseTime(it) } ?: 0L,
                    )
                )
            }
        }
    }

    /** Begins a sign-in with the address and password a person typed on this phone. */
    suspend fun startSignIn(context: Context, email: String, password: String): SignIn? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("email", email).put("password", password).toString()
            call(context, "POST", "/api/signin", body)?.let { readSignIn(it) }
        }

    /** Where a sign-in stands now, which is how the screen knows what Mozilla is asking for. */
    suspend fun signIn(context: Context, id: String): SignIn? = withContext(Dispatchers.IO) {
        call(context, "GET", "/api/signin/$id", null)?.let { readSignIn(it) }
    }

    /** Posts the answer to what Mozilla asked for: a code, or the CAPTCHA answer. */
    suspend fun answer(context: Context, id: String, answer: String): SignIn? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("value", answer).toString()
        call(context, "POST", "/api/signin/$id", body)?.let { readSignIn(it) }
    }

    /** Abandons a sign-in; the engine forgets it and no account is added. */
    suspend fun cancel(context: Context, id: String) {
        withContext(Dispatchers.IO) { call(context, "DELETE", "/api/signin/$id", null) }
    }

    /** Makes [id] the account the connection uses. */
    suspend fun activate(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/accounts/$id/activate", "")
        body?.let { readProblem(it) } ?: if (body == null) "The account engine did not answer." else null
    }

    /** Asks the engine to read [id]'s allowance from Mozilla again. */
    suspend fun refreshQuota(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/accounts/$id/refresh", "")
        body?.let { readProblem(it) } ?: if (body == null) "The account engine did not answer." else null
    }

    /** Removes local credentials; the engine does not unregister Mozilla's device remotely. */
    suspend fun remove(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val body = call(context, "DELETE", "/api/accounts/$id", null)
            ?: return@withContext "The account engine did not answer; the account may not have been removed."
        readProblem(body) ?: "The account was removed from this phone. Its Mozilla device registration was not removed; review it in your Mozilla account."
    }

    /** One exit location and its last measured latency, as Mission Control and the picker show it. */
    data class Location(
        val code: String,
        val name: String,
        val city: String,
        val recommended: Boolean,
        val pingMs: Long,
        val jitterMs: Long,
        val avgMs: Long,
        val samples: Int,
        val attempts: Int,
        val error: String,
    ) {
        val ok: Boolean get() = error.isEmpty() && pingMs > 0
    }

    /** The result of a location race: every location sorted by latency, and when it was measured. */
    data class PingResult(val locations: List<Location>, val pingedAtMillis: Long, val target: String)

    /** The engine's connection test through the tunnel, for the status bar and the Connection Doctor. */
    data class EngineTestResult(
        val ok: Boolean,
        val error: String,
        val tunnelMs: Long,
        val tlsMs: Long,
        val totalMs: Long,
        val exitIpv4: String,
        val exitCountry: String,
    )

    /** A live snapshot of the tunnel for Mission Control: speed, place, transport, quota, health. */
    data class LiveStatus(
        val phase: String,
        val selection: String,
        val selectionName: String,
        val countryCode: String,
        val countryName: String,
        val cityName: String,
        val transport: String,
        val sleeping: Boolean,
        val wakeError: String,
        val connectFailure: String,
        val upBytes: Long,
        val downBytes: Long,
        val upRate: Long,
        val downRate: Long,
        val exitIpv4: String,
        val exitCountry: String,
        val exitMismatch: Boolean,
        val latencyMs: Long,
        val quotaUnlimited: Boolean,
        val quotaLimitBytes: Long,
        val quotaRemainingBytes: Long,
        val activeAccountEmail: String,
        val noNetwork: Boolean,
    )

    /** The engine's traffic totals (up, down) since the proxy started, or null when it is not up. */
    suspend fun trafficSnapshot(context: Context): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/status", null) ?: return@withContext null
        val traffic = JSONObject(body).optJSONObject("traffic") ?: return@withContext null
        traffic.optLong("up") to traffic.optLong("down")
    }

    /** Runs the engine's connection test through the tunnel and returns the staged result. */
    suspend fun connectionTest(context: Context): EngineTestResult? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/test", "") ?: return@withContext null
        val root = JSONObject(body)
        EngineTestResult(
            ok = root.optBoolean("ok"),
            error = root.optString("error").ifBlank { root.optString("error_code") },
            tunnelMs = root.optLong("tunnel_ms"),
            tlsMs = root.optLong("tls_ms"),
            totalMs = root.optLong("total_ms"),
            exitIpv4 = root.optString("exit_ipv4"),
            exitCountry = root.optString("exit_country"),
        )
    }

    /** A live snapshot of the tunnel for Mission Control. */
    suspend fun liveStatus(context: Context): LiveStatus? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/status", null) ?: return@withContext null
        val root = JSONObject(body)
        val state = root.optJSONObject("state")
        val proxy = root.optJSONObject("proxy")
        val traffic = root.optJSONObject("traffic")
        val exit = root.optJSONObject("exit")
        val pass = root.optJSONObject("pass")
        val lastTest = root.optJSONObject("last_test")
        val active = root.optJSONObject("active_account")
        LiveStatus(
            phase = state?.optString("phase").orEmpty(),
            selection = root.optString("selection"),
            selectionName = root.optString("selection_name"),
            countryCode = proxy?.optString("country_code").orEmpty(),
            countryName = proxy?.optString("country_name").orEmpty(),
            cityName = proxy?.optString("city_name").orEmpty(),
            transport = proxy?.optString("transport").orEmpty(),
            sleeping = proxy?.optString("sleeping_since").orEmpty().isNotEmpty(),
            wakeError = proxy?.optString("wake_error").orEmpty(),
            connectFailure = root.optString("connect_failure"),
            upBytes = traffic?.optLong("up") ?: 0L,
            downBytes = traffic?.optLong("down") ?: 0L,
            upRate = traffic?.optLong("up_rate") ?: 0L,
            downRate = traffic?.optLong("down_rate") ?: 0L,
            exitIpv4 = exit?.optString("ipv4").orEmpty(),
            exitCountry = exit?.optString("country_code").orEmpty(),
            exitMismatch = root.has("exit_mismatch"),
            latencyMs = lastTest?.optLong("total_ms") ?: 0L,
            quotaUnlimited = pass?.optBoolean("quota_unlimited") ?: false,
            quotaLimitBytes = pass?.optLong("quota_limit_bytes", -1L) ?: -1L,
            quotaRemainingBytes = pass?.optLong("quota_remaining_bytes", -1L) ?: -1L,
            activeAccountEmail = active?.optString("email").orEmpty(),
            noNetwork = root.optBoolean("no_network"),
        )
    }

    /** The exit locations with their last measured latency. */
    suspend fun locations(context: Context): List<Location>? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/locations", null) ?: return@withContext null
        val list = JSONObject(body).optJSONArray("locations") ?: return@withContext null
        parseLocations(list)
    }

    /** Races the given locations (all when [codes] is empty) through the tunnel, fastest first. */
    suspend fun pingLocations(context: Context, codes: List<String> = emptyList()): PingResult? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("codes", JSONArray(codes)).toString()
            val body = call(context, "POST", "/api/locations/ping", payload) ?: return@withContext null
            val root = JSONObject(body)
            val list = root.optJSONArray("locations") ?: return@withContext null
            PingResult(
                locations = parseLocations(list),
                pingedAtMillis = parseTime(root.optString("pinged_at")),
                target = root.optString("target"),
            )
        }

    private fun parseLocations(list: JSONArray): List<Location> = buildList {
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            add(
                Location(
                    code = item.optString("code"),
                    name = item.optString("name"),
                    city = item.optString("city"),
                    recommended = item.optBoolean("recommended"),
                    pingMs = item.optLong("ping_ms"),
                    jitterMs = item.optLong("jitter_ms"),
                    avgMs = item.optLong("avg_ms"),
                    samples = item.optInt("samples"),
                    attempts = item.optInt("attempts"),
                    error = item.optString("ping_error").ifBlank { item.optString("error") },
                )
            )
        }
    }

    /** Selects a location: a country code, "fastest", or "" for the recommended one. */
    suspend fun setLocation(context: Context, country: String): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("country", country).toString()
        val body = call(context, "POST", "/api/location", payload)
            ?: return@withContext "The account engine did not answer."
        readProblem(body)
    }

    /** The engine's full settings, for the transport/DNS pickers and One-Tap Recipes. */
    suspend fun settings(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/settings", null) ?: return@withContext null
        JSONObject(body).optJSONObject("config")
    }

    /** Writes a full settings object back; returns an error message, or null on success. */
    suspend fun updateSettings(context: Context, config: JSONObject): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("config", config).toString()
        val body = call(context, "PUT", "/api/settings", payload)
            ?: return@withContext "The account engine did not answer."
        readProblem(body)
    }

    /** Tests the engine's DNS servers, for the Privacy Audit and Connection Doctor. */
    suspend fun dnsTest(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/dns/test", "") ?: return@withContext null
        JSONObject(body)
    }

    /** Restarts the engine's proxy in place (a fresh upstream session), keeping the account. */
    suspend fun restartProxy(context: Context): String? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/proxy/restart", "")
            ?: return@withContext "The account engine did not answer."
        readProblem(body)
    }

    /** Sets whether an account takes part in the failover pool. */
    suspend fun setAccountEnabled(context: Context, id: String, enabled: Boolean): String? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("enabled", enabled).toString()
            val body = call(context, "POST", "/api/accounts/$id/enabled", payload)
                ?: return@withContext "The account engine did not answer."
            readProblem(body)
        }

    /** One step of the Connection Doctor: a stable id, a status, and a technical detail. */
    data class DoctorStep(val id: String, val status: String, val detail: String, val ms: Long) {
        val ok: Boolean get() = status == "ok"
        val failed: Boolean get() = status == "fail"
        val warn: Boolean get() = status == "warn"
        val pending: Boolean get() = status == "pending"
        val skipped: Boolean get() = status == "skip"
    }

    /** The Connection Doctor's step-by-step diagnosis and its single recommendation. */
    data class DoctorReport(
        val ok: Boolean,
        val summary: String,
        val recommendation: String,
        val steps: List<DoctorStep>,
    )

    /** Runs the Connection Doctor; [live] also runs a fresh tunnel test (slower). */
    suspend fun doctor(context: Context, live: Boolean = false): DoctorReport? = withContext(Dispatchers.IO) {
        val path = if (live) "/api/doctor?test=1" else "/api/doctor"
        val body = call(context, "GET", path, null) ?: return@withContext null
        val root = JSONObject(body)
        val steps = mutableListOf<DoctorStep>()
        root.optJSONArray("steps")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                steps.add(
                    DoctorStep(
                        id = item.optString("id"),
                        status = item.optString("status"),
                        detail = item.optString("detail"),
                        ms = item.optLong("ms"),
                    )
                )
            }
        }
        DoctorReport(
            ok = root.optBoolean("ok"),
            summary = root.optString("summary"),
            recommendation = root.optString("recommendation"),
            steps = steps,
        )
    }

    /** Network DNA: which DNS paths answer on this network, and which rescue is armed. */
    data class NetworkDna(
        val filtered: Boolean,
        val bootstrapResolved: Int,
        val systemResolved: Int,
        val targets: Int,
        val sharedRelayEnabled: Boolean,
        val fallbackToSystem: Boolean,
        val recommendation: String,
        val systemServers: List<String>,
    )

    suspend fun networkDna(context: Context): NetworkDna? = withContext(Dispatchers.IO) {
        val body = call(context, "GET", "/api/network", null) ?: return@withContext null
        val root = JSONObject(body)
        val servers = mutableListOf<String>()
        root.optJSONArray("system_servers")?.let { array ->
            for (i in 0 until array.length()) servers.add(array.optString(i))
        }
        NetworkDna(
            filtered = root.optBoolean("filtered"),
            bootstrapResolved = root.optInt("bootstrap_resolved"),
            systemResolved = root.optInt("system_resolved"),
            targets = root.optInt("targets"),
            sharedRelayEnabled = root.optBoolean("shared_relay_enabled"),
            fallbackToSystem = root.optBoolean("fallback_to_system"),
            recommendation = root.optString("recommendation"),
            systemServers = servers,
        )
    }

    /** The error field the engine's plain results carry, or null when there was none. */
    private fun readProblem(body: String): String? = runCatching {
        JSONObject(body).optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readSignIn(body: String): SignIn = JSONObject(body).let { root ->
        SignIn(
            id = root.optString("id"),
            step = root.optString("step"),
            message = root.optString("message"),
            error = root.optString("error").ifBlank { root.optString("error_code") },
            captcha = root.optString("captcha"),
        )
    }

    /**
     * One call to the engine's loopback API, with this app's token.
     *
     * @return a success body, a sanitized HTTP error body, or null when the request could not reach
     *   the engine (for example, no local token or a loopback/network failure).
     */
    private fun call(context: Context, method: String, path: String, body: String?): String? {
        val token = token(context) ?: return null
        val origin = FirefoxCoreManager.baseUrl(context) ?: return null
        val request = Request.Builder()
            .url("$origin$path")
            .header("X-Local-Token", token)
            .header("Accept", "application/json")
            .apply {
                if (body == null) {
                    method(method, null)
                } else {
                    method(method, body.toRequestBody(json))
                }
            }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> text
                    else -> {
                        val detail = runCatching { JSONObject(text).optString("error") }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: "The account engine replied with HTTP ${response.code}."
                        com.v2ray.ang.util.LogUtil.w(
                            "FirefoxApi",
                            "$method $path answered HTTP ${response.code}: $detail"
                        )
                        JSONObject().put("error", detail).toString()
                    }
                }
            }
        } catch (e: Exception) {
            com.v2ray.ang.util.LogUtil.e("FirefoxApi", "$method $path did not reach the engine", e)
            null
        }
    }

    /**
     * The engine's token, read from the file inside this app's private storage, paired against the
     * token the engine is currently using so a stale file cannot send the app to an engine that would
     * only refuse it.
     */
    private fun token(context: Context): String? {
        val token = runCatching {
            File(FirefoxCoreManager.dataDir(context), AppConfig.FIREFOX_ENGINE_TOKEN).readText().trim()
        }.getOrNull()
        if (token.isNullOrEmpty() || token.length > 8_192) return null
        return token
    }

    /** "2026-01-31T00:00:00Z" and friends, as milliseconds; 0 when it is missing or unreadable. */
    private fun parseTime(raw: String): Long = try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}
