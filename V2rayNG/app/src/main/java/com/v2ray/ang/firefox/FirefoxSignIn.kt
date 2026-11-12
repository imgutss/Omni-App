package com.v2ray.ang.firefox

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Signs a person in to **their own** Mozilla account and reads **their own** VPN allowance.
 *
 * Only two hosts are ever contacted, both Mozilla's own:
 *
 * - `api.accounts.firefox.com` for the sign-in of the address and password the person typed on this phone;
 * - `vpn.mozilla.org/api/v1/fpn/token` (Guardian) for the account's allowance, whose `X-Quota-*` response
 *   headers are the numbers the screen shows.
 *
 * Nothing here talks to an Omni server, a bot or a relay, and nothing it returns is sent anywhere else: the
 * tokens go straight into [FirefoxAccountStore], which is this phone's storage.
 */
object FirefoxSignIn {
    private const val ACCOUNTS_HOST = "https://api.accounts.firefox.com"
    private const val GUARDIAN_HOST = "https://vpn.mozilla.org"

    /** Mozilla's OAuth client id for its own Android browser, so the grant is the one Mozilla expects. */
    private const val CLIENT_ID = "5882386c6d801776"
    private const val SCOPE = "https://identity.mozilla.com/apps/oldsync"
    private const val USER_AGENT = "Mozilla/5.0 (Android 13; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

    private const val TIMEOUT_SECONDS = 30L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Signs in and returns the account with its tokens filled in.
     *
     * @throws FirefoxError with [FirefoxError.Kind.CHALLENGE] when Mozilla's anti-bot page answered, which is
     *   the signal for the screen to explain what to try next instead of blaming the password.
     */
    fun signIn(email: String, password: String): FirefoxAccount {
        val address = email.trim()
        if (address.isEmpty()) throw FirefoxError(FirefoxError.Kind.CREDENTIALS, "the address is empty")

        val body = FormBody.Builder()
            .add("email", address)
            .add("authPW", password)
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .add("access_type", "offline")
            .add("ttl", "0")
            .build()

        val request = Request.Builder()
            .url("$ACCOUNTS_HOST/v1/account/login?keys=true")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()

        val payload = execute(request, "the sign-in")
        val session = payload.optJSONObject("session_token")
            ?: throw FirefoxError(FirefoxError.Kind.OTHER, "Mozilla did not return a session")
        val accessToken = session.optString("token")
        val sessionToken = session.optString("sessionToken")
        if (accessToken.isBlank() || sessionToken.isBlank()) {
            throw FirefoxError(FirefoxError.Kind.OTHER, "Mozilla returned an incomplete session")
        }
        val expiresIn = session.optLong("expiresIn", 0L)
        return FirefoxAccount(
            id = "",
            email = address,
            accessToken = accessToken,
            refreshToken = sessionToken,
            expiresAt = if (expiresIn > 0L) System.currentTimeMillis() + expiresIn * 1000L else 0L,
        )
    }

    /**
     * Reads the account's allowance without keeping the connection pass itself.
     *
     * A HEAD answers with the `X-Quota-*` headers and no body; when the endpoint refuses HEAD, a GET is used
     * and whatever pass it returns is discarded here. A spent allowance still reports valid headers, which is
     * exactly what lets the screen say "50 of 50 used" instead of an error.
     */
    fun readQuota(account: FirefoxAccount): FirefoxQuota {
        val head = Request.Builder()
            .url("$GUARDIAN_HOST/api/v1/fpn/token")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("Accept", "application/json")
            .head()
            .build()

        val first = call(head, "the allowance request")
        first.use { response ->
            when (response.code) {
                200 -> return response.headers.toQuota()
                401 -> throw FirefoxError(FirefoxError.Kind.CREDENTIALS, "the session is no longer valid")
                403 -> throw FirefoxError(FirefoxError.Kind.NOT_ENTITLED, "this account has no free VPN allowance")
                429 -> throw FirefoxError(FirefoxError.Kind.QUOTA_EXCEEDED, "the monthly allowance is spent")
                451 -> throw FirefoxError(FirefoxError.Kind.REGION, "Mozilla is unavailable from this network")
            }
            if (response.code in 200..299) return response.headers.toQuota()
        }

        val get = Request.Builder()
            .url("$GUARDIAN_HOST/api/v1/fpn/token")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()
        call(get, "the allowance request").use { response ->
            return when (response.code) {
                200 -> response.headers.toQuota()
                401 -> throw FirefoxError(FirefoxError.Kind.CREDENTIALS, "the session is no longer valid")
                403 -> throw FirefoxError(FirefoxError.Kind.NOT_ENTITLED, "this account has no free VPN allowance")
                429 -> throw FirefoxError(FirefoxError.Kind.QUOTA_EXCEEDED, "the monthly allowance is spent")
                451 -> throw FirefoxError(FirefoxError.Kind.REGION, "Mozilla is unavailable from this network")
                else -> throw FirefoxError(FirefoxError.Kind.OTHER, "Guardian answered HTTP ${response.code}")
            }
        }
    }

    private fun call(request: Request, what: String): okhttp3.Response = try {
        client.newCall(request).execute()
    } catch (e: Exception) {
        throw FirefoxError(FirefoxError.Kind.NETWORK, "$what did not leave the phone: ${e.message.orEmpty()}")
    }

    private fun execute(request: Request, what: String): JSONObject {
        val response = call(request, what)
        response.use {
            val body = it.body?.string().orEmpty()
            when (it.code) {
                in 200..299 -> return try {
                    JSONObject(body)
                } catch (e: Exception) {
                    throw FirefoxError(FirefoxError.Kind.OTHER, "Mozilla answered something this app cannot read")
                }
                406 -> throw FirefoxError(FirefoxError.Kind.CHALLENGE, "Mozilla asked for a browser check")
                429 -> throw FirefoxError(FirefoxError.Kind.CREDENTIALS, "too many attempts; wait a few minutes")
                401, 403 -> {
                    val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                    throw FirefoxError(
                        FirefoxError.Kind.CREDENTIALS,
                        message.ifBlank { "the address or the password did not match" },
                    )
                }
                451 -> throw FirefoxError(FirefoxError.Kind.REGION, "Mozilla is unavailable from this network")
                else -> throw FirefoxError(FirefoxError.Kind.OTHER, "Mozilla answered HTTP ${it.code}")
            }
        }
    }

    /**
     * The allowance as Guardian's headers describe it.
     *
     * A missing header stays -1/0, so the screen can say "not read yet" instead of inventing a number.
     */
    private fun okhttp3.Headers.toQuota(): FirefoxQuota {
        if (this["X-Quota-Unlimited"]?.equals("true", ignoreCase = true) == true) {
            return FirefoxQuota(limitBytes = -1L, remainingBytes = -1L, resetsAt = 0L, unlimited = true)
        }
        val limit = this["X-Quota-Limit"]?.toLongOrNull() ?: -1L
        val remaining = this["X-Quota-Remaining"]?.toLongOrNull() ?: -1L
        val resetsAt = this["X-Quota-Reset"]?.let { raw ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        } ?: 0L
        return FirefoxQuota(limitBytes = limit, remainingBytes = remaining, resetsAt = resetsAt, unlimited = false)
    }
}
