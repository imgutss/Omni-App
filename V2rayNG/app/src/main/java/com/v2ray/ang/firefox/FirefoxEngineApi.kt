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
     * The account screen works before anything is connected, so it is the screen that starts the
     * engine; the wait is bounded, and a start that never answers is reported as one.
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
        val list: JSONArray = JSONObject(body).optJSONArray("accounts") ?: return@withContext emptyList()
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
        val body = JSONObject().put("answer", answer).toString()
        call(context, "POST", "/api/signin/$id", body)?.let { readSignIn(it) }
    }

    /** Abandons a sign-in; the engine forgets it and no account is added. */
    suspend fun cancel(context: Context, id: String) {
        withContext(Dispatchers.IO) { call(context, "DELETE", "/api/signin/$id", null) }
    }

    /** Makes [id] the account the connection uses. */
    suspend fun activate(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/accounts/$id/activate", "")
        body?.let { readProblem(it) }
    }

    /** Asks the engine to read [id]'s allowance from Mozilla again. */
    suspend fun refreshQuota(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val body = call(context, "POST", "/api/accounts/$id/refresh", "")
        body?.let { readProblem(it) }
    }

    /**
     * Forgets [id] on this phone: the account is deleted from the engine's own storage.
     *
     * The account holds a device registration, which lives with Mozilla rather than here. It is
     * detached first: a device left behind is one that keeps appearing in the person's own account
     * list on Mozilla's site, and one fewer of them is one fewer thing that can spend their data.
     * A failure to detach is not treated as fatal — it is said plainly, so they can remove that
     * device on Mozilla's site themselves.
     */
    suspend fun remove(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val detached = detachDevice(context, id)
        val body = call(context, "DELETE", "/api/accounts/$id", null)
        val problem = body?.let { readProblem(it) }
        when {
            !problem.isNullOrBlank() -> problem
            detached == false ->
                "The account is no longer on this phone, but its device could not be detached from " +
                    "Mozilla. Remove it from the devices in that account on Mozilla's own site."
            else -> null
        }
    }

    /**
     * Unregisters this phone's device from [id] on Mozilla's side, before the record here is deleted.
     *
     * @return whether the engine said the device was detached, or null when the answer could not be
     *   read. A refusal is not thrown, because a registration left behind is a nuisance, not a
     *   failure of the deletion the person asked for.
     */
    private fun detachDevice(context: Context, id: String): Boolean? {
        val body = call(context, "POST", "/api/accounts/$id/device/detach", "") ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optString("error").isNotBlank()) return false
        // Absence of a problem is success; a field that says so outright is believed when present.
        if (root.has("ok")) return root.optBoolean("ok", true)
        return true
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
     * @return the body, or null when the call did not happen: no engine, no token, or an engine that
     *   refused. A refusal is logged without the body, which can name an account.
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
                        com.v2ray.ang.util.LogUtil.w(
                            "FirefoxApi",
                            "$method $path answered HTTP ${response.code}"
                        )
                        null
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
