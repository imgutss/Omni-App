package com.v2ray.ang.omni

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.subscription.SubEditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Hub operations over the existing subscription database; there is no central Omni panel. */
class OmniHubRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = OmniPanelStore(appContext)
    private val subscriptions = SubEditRepository()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun panels(): List<OmniPanel> = withContext(Dispatchers.IO) { store.load() }

    suspend fun addSubscription(
        displayName: String,
        subscriptionUrl: String,
        allowInsecure: Boolean,
    ): OmniPanel = withContext(Dispatchers.IO) {
        val parsed = validateUrl(subscriptionUrl, allowInsecure)
        val name = displayName.trim().ifBlank { parsed.host }
        val base = originOf(parsed)
        val existing = store.load().firstOrNull { it.subscriptionUrl.equals(parsed.toString(), ignoreCase = true) }
        if (existing != null) throw OmniHubException("This subscription is already connected")

        val subId = subscriptions.saveSubscription("") { item ->
            item.remarks = name
            item.url = parsed.toString()
            item.enabled = true
            item.autoUpdate = true
            item.allowInsecureUrl = allowInsecure
        } ?: throw OmniHubException("The subscription storage refused the new panel")
        val panel = OmniPanel(
            name = name,
            baseUrl = base,
            subscriptionUrl = parsed.toString(),
            subscriptionId = subId,
            allowInsecure = allowInsecure,
        )
        try {
            store.replace(panel)
        } catch (error: Exception) {
            // Do not leave an invisible subscription behind if encrypted Hub storage fails.
            runCatching { subscriptions.deleteSubscription(subId) }
            throw error
        }
        panel
    }

    /** Optional panel pairing. Current panels without this endpoint return an honest 404. */
    suspend fun pairWithCode(
        baseUrl: String,
        code: String,
        displayName: String,
        allowInsecure: Boolean,
    ): OmniPanel = withContext(Dispatchers.IO) {
        val rawCode = code.trim()
        val qr = rawCode.toHttpUrlOrNull()
        val base = if (qr != null && rawCode.startsWith("http", ignoreCase = true)) {
            val validatedQr = validateUrl(rawCode, allowInsecure)
            if (validatedQr.encodedPath != "/api/app/pair") throw OmniHubException("The pairing QR must point to the panel pairing endpoint")
            val qrBase = originOf(validatedQr)
            if (baseUrl.isNotBlank() && normalizeBase(baseUrl, allowInsecure) != qrBase) {
                throw OmniHubException("The pairing QR belongs to a different panel")
            }
            qrBase
        } else {
            normalizeBase(baseUrl, allowInsecure)
        }
        val pairingCode = qr?.queryParameter("code") ?: rawCode
        if (pairingCode.isEmpty()) throw OmniHubException("Enter the pairing code from your panel")
        val endpoint = "$base/api/app/pair"
        val body = JSONObject()
            .put("code", pairingCode)
            .put("client", "Omni Android")
            .put("device", "Omni Android")
            .toString()
            .toRequestBody(JSON)
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
            ).execute()
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                val hint = if (it.code == 404) {
                    "This panel has no pairing endpoint (HTTP 404); use its subscription link instead"
                } else {
                    "Pairing refused: HTTP ${it.code}"
                }
                throw OmniHubException(hint, it.code)
            }
            val pair = parsePairResponse(text)
            val parsed = validateUrl(pair.subscriptionUrl, allowInsecure)
            val panelOrigin = originOf(validateUrl(base, allowInsecure))
            if (originOf(parsed) != panelOrigin) {
                throw OmniHubException("Pairing returned a subscription for a different panel")
            }
            val name = displayName.trim().ifBlank { pair.displayName ?: parsed.host }
            val subId = subscriptions.saveSubscription("") { item ->
                item.remarks = name
                item.url = parsed.toString()
                item.enabled = true
                item.autoUpdate = true
                item.allowInsecureUrl = allowInsecure
            } ?: throw OmniHubException("The subscription storage refused the paired panel")
            val panel = OmniPanel(
                name = name,
                baseUrl = base,
                subscriptionUrl = parsed.toString(),
                subscriptionId = subId,
                allowInsecure = allowInsecure,
                lastStatus = OmniPanelStatus.READY.name,
                lastStatusDetail = "Pairing succeeded; subscription not yet synced",
                lastCheckedAt = System.currentTimeMillis(),
            )
            val scoped = pair.session?.copy(panelId = panel.id)
            try {
                store.replace(panel)
                if (scoped != null) store.saveScopedSession(scoped)
            } catch (error: Exception) {
                if (scoped != null) runCatching { store.removeScopedSession(panel.id) }
                runCatching { store.remove(panel.id) }
                runCatching { subscriptions.deleteSubscription(subId) }
                throw error
            }
            panel
        }
    }

    suspend fun remove(panel: OmniPanel): Unit = withContext(Dispatchers.IO) {
        if (!subscriptions.deleteSubscription(panel.subscriptionId)) {
            throw OmniHubException("The panel could not be removed; its subscription was left intact")
        }
        store.remove(panel.id)
    }

    suspend fun scopedScopes(panel: OmniPanel): Set<String> = withContext(Dispatchers.IO) {
        store.scopedSession(panel.id)?.scopes ?: emptySet()
    }

    suspend fun scopedSessionInfo(panel: OmniPanel): OmniScopedSessionInfo? = withContext(Dispatchers.IO) {
        store.scopedSessionInfo(panel.id)
    }

    /** Local-only erasure for expired/offline sessions; this never claims a server-side revoke. */
    suspend fun forgetScopedSession(panel: OmniPanel): Unit = withContext(Dispatchers.IO) {
        store.removeScopedSession(panel.id)
    }

    /** Publishes only through the token-bound Scanner Pack route; no administrator fallback exists. */
    suspend fun publishScannerPack(panel: OmniPanel, profiles: List<ProfileItem>): OmniScannerPublishResult = withContext(Dispatchers.IO) {
        require(profiles.size <= 2000) { "Up to 2000 configurations are allowed" }
        val session = requireScoped(panel, "scanner:publish")
        val uris = profiles.map { profile ->
            when (profile.configType) {
                EConfigType.VLESS -> VlessFmt.toUri(profile)
                EConfigType.VMESS -> VmessFmt.toUri(profile)
                EConfigType.TROJAN -> TrojanFmt.toUri(profile)
                else -> throw OmniHubException("Only VLESS, VMess and Trojan Scanner Pack entries are supported")
            }.also { uri -> if (uri.isBlank()) throw OmniHubException("A built configuration could not be serialized") }
        }
        val body = JSONObject().put("uris", JSONArray(uris)).toString().toRequestBody(JSON)
        val response = try {
            client.newCall(Request.Builder()
                .url("${panel.baseUrl}/api/app/scanner-packs")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${session.accessToken}")
                .post(body)
                .build()).execute()
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw OmniHubException("Scanner Publish refused: HTTP ${it.code}", it.code)
            val json = runCatching { JSONObject(text) }.getOrElse { throw OmniHubException("Scanner Publish returned invalid JSON") }
            if (!json.optBoolean("ok", false)) throw OmniHubException("Scanner Publish was not accepted")
            OmniScannerPublishResult(
                count = json.optInt("count", 0),
                group = json.optString("group", "scanner"),
                otherGroupsPreserved = json.optBoolean("otherGroupsPreserved", false),
                relaysUnchanged = json.optBoolean("relaysUnchanged", false),
            )
        }
    }

    suspend fun revokeScoped(panel: OmniPanel): Unit = withContext(Dispatchers.IO) {
        val session = store.scopedSession(panel.id)
            ?: throw OmniHubException("No active scoped session exists; use local Forget if the token expired")
        val response = try {
            client.newCall(Request.Builder()
                .url("${panel.baseUrl}/api/app/revoke")
                .header("Authorization", "Bearer ${session.accessToken}")
                .post("{}".toRequestBody(JSON))
                .build()).execute()
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        response.use {
            if (!it.isSuccessful) throw OmniHubException("Scoped revoke refused: HTTP ${it.code}", it.code)
        }
        store.removeScopedSession(panel.id)
    }

    /** Reads panel templates without importing them or changing the local subscription. */
    suspend fun readScopedTemplates(panel: OmniPanel): OmniScopedTemplates = withContext(Dispatchers.IO) {
        val session = requireScoped(panel, "templates:read")
        val response = try {
            client.newCall(Request.Builder()
                .url("${panel.baseUrl}/api/app/templates")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${session.accessToken}")
                .get()
                .build()).execute()
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw OmniHubException("Template read refused: HTTP ${it.code}", it.code)
            val json = runCatching { JSONObject(text) }.getOrElse { throw OmniHubException("Template read returned invalid JSON") }
            if (!json.optBoolean("ok", false)) throw OmniHubException("Template read was not accepted")
            val userUuid = json.optString("user_uuid")
            if (userUuid != session.userUuid) throw OmniHubException("Template response belongs to a different panel user")
            val subscriptionUrl = json.optString("subscription_url")
            val parsedSubscription = validateUrl(subscriptionUrl, panel.allowInsecure)
            if (originOf(parsedSubscription) != originOf(validateUrl(panel.baseUrl, panel.allowInsecure))) {
                throw OmniHubException("Template response returned a different panel subscription")
            }
            val array = json.optJSONArray("templates") ?: JSONArray()
            if (array.length() > 128) throw OmniHubException("Template response has too many groups")
            val groups = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: throw OmniHubException("Template response contains an invalid group")
                    val source = item.optString("source").trim()
                    if (source.isEmpty()) throw OmniHubException("Template response contains an unnamed group")
                    val uriArray = item.optJSONArray("uris") ?: JSONArray()
                    if (uriArray.length() > 2000) throw OmniHubException("Template group has too many configurations")
                    val uris = buildList {
                        for (uriIndex in 0 until uriArray.length()) {
                            val uri = uriArray.optString(uriIndex)
                            if (uri.isBlank() || uri.length > 32_768) throw OmniHubException("Template response contains an invalid configuration")
                            add(uri)
                        }
                    }
                    add(OmniScopedTemplateGroup(source, uris))
                }
            }
            OmniScopedTemplates(userUuid, json.optString("username"), subscriptionUrl, groups)
        }
    }

    /** Sends only explicit app/device metadata; configurations, URLs and tokens are never included. */
    suspend fun reportDevice(panel: OmniPanel, appVersion: String, device: String, androidVersion: String): Unit = withContext(Dispatchers.IO) {
        val session = requireScoped(panel, "device:report")
        if (appVersion.length > 40 || device.length > 80 || androidVersion.length > 20) {
            throw OmniHubException("Device report metadata is too long")
        }
        val body = JSONObject()
            .put("app_version", appVersion)
            .put("device", device)
            .put("android", androidVersion)
            .toString().toRequestBody(JSON)
        val response = try {
            client.newCall(Request.Builder()
                .url("${panel.baseUrl}/api/app/device-report")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${session.accessToken}")
                .post(body)
                .build()).execute()
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw OmniHubException("Device report refused: HTTP ${it.code}", it.code)
            val json = runCatching { JSONObject(text) }.getOrElse { throw OmniHubException("Device report returned invalid JSON") }
            if (!json.optBoolean("ok", false)) throw OmniHubException("Device report was not accepted")
        }
    }

    private fun requireScoped(panel: OmniPanel, scope: String): OmniScopedSession {
        val session = store.scopedSession(panel.id)
            ?: throw OmniHubException("Pair this panel with a scoped device code first")
        if (!session.hasScope(scope, System.currentTimeMillis())) {
            throw OmniHubException("This paired device has no active $scope capability")
        }
        return session
    }

    suspend fun sync(panel: OmniPanel): Int = withContext(Dispatchers.IO) {
        val subscription = MmkvManager.decodeSubscription(panel.subscriptionId)
            ?: throw OmniHubException("The panel subscription no longer exists")
        val result = AngConfigManager.updateConfigViaSub(SubscriptionCache(panel.subscriptionId, subscription))
        if (result.successCount <= 0 || result.configCount <= 0) {
            throw OmniHubException("Sync failed: no valid configurations were received")
        }
        store.replace(
            panel.copy(
                lastSyncAt = System.currentTimeMillis(),
                lastStatus = OmniPanelStatus.READY.name,
                lastStatusDetail = "Subscription sync succeeded: ${result.configCount} configurations",
            )
        )
        result.configCount
    }

    suspend fun check(panel: OmniPanel): OmniPanelHealth = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val health = try {
            val response = client.newCall(
                Request.Builder()
                    .url("${panel.baseUrl}/api/version")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            ).execute()
            response.use {
                val elapsed = elapsedMillis(started)
                val status = when (it.code) {
                    in 200..299 -> OmniPanelStatus.READY
                    401, 403 -> OmniPanelStatus.AUTH_OR_EDGE_REFUSED
                    404 -> OmniPanelStatus.NOT_FOUND
                    408, 504 -> OmniPanelStatus.TIMEOUT
                    else -> OmniPanelStatus.HTTP_ERROR
                }
                OmniPanelHealth(
                    status = status,
                    httpStatus = it.code,
                    elapsedMillis = elapsed,
                    rawDetail = "HTTP ${it.code} ${it.message}; ${elapsed} ms; endpoint=/api/version",
                )
            }
        } catch (error: Exception) {
            val status = when (error) {
                is SocketTimeoutException -> OmniPanelStatus.TIMEOUT
                is UnknownHostException -> OmniPanelStatus.DNS_ERROR
                is SSLException -> OmniPanelStatus.TLS_ERROR
                else -> OmniPanelStatus.NETWORK_ERROR
            }
            OmniPanelHealth(
                status = status,
                elapsedMillis = elapsedMillis(started),
                rawDetail = "${error.javaClass.simpleName}; ${elapsedMillis(started)} ms",
            )
        }
        runCatching {
            store.replace(
                panel.copy(
                    lastStatus = health.status.name,
                    lastStatusDetail = health.rawDetail,
                    lastCheckedAt = System.currentTimeMillis(),
                )
            )
        }
        health
    }

    private data class PairResponse(
        val subscriptionUrl: String,
        val displayName: String?,
        val session: OmniScopedSession?,
    )

    private fun parsePairResponse(text: String): PairResponse {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val subscription = json?.let { value ->
            listOf("subscription_url", "subscriptionUrl", "url", "subscription")
                .firstOrNull { key -> value.optString(key).startsWith("http") }
                ?.let { value.optString(it) }
        } ?: parseSubscriptionResponse(text) ?: throw OmniHubException("Pairing returned no subscription URL")
        val token = json?.optString("access_token")?.takeIf { it.isNotBlank() }
        val session = if (token == null) {
            null
        } else {
            val scopedJson = json ?: throw OmniHubException("Scoped pairing returned no JSON session")
            val userUuid = scopedJson.optString("user_uuid").takeIf { it.isNotBlank() }
                ?: throw OmniHubException("Scoped pairing returned no bound panel user")
            val expiresAt = scopedJson.optLong("expires_at", 0L)
            if (expiresAt <= System.currentTimeMillis()) throw OmniHubException("Scoped pairing returned an expired token")
            val scopes = buildSet {
                scopedJson.optJSONArray("scopes")?.let { values ->
                    for (index in 0 until values.length()) add(values.getString(index))
                }
            }
            OmniScopedSession("", token, userUuid, scopes, expiresAt)
        }
        val responseName = json?.optString("name")?.takeIf { it.isNotBlank() }
            ?: json?.optString("username")?.takeIf { it.isNotBlank() }
        return PairResponse(subscription, responseName, session)
    }

    private fun parseSubscriptionResponse(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return listOf("subscription_url", "subscriptionUrl", "url", "subscription")
            .firstOrNull { key -> json.optString(key).startsWith("http") }
            ?.let { json.optString(it) }
    }

    private fun validateUrl(raw: String, allowInsecure: Boolean): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw OmniHubException("Enter a valid HTTP(S) URL")
        if (url.scheme !in setOf("http", "https")) throw OmniHubException("Only HTTP(S) panel URLs are supported")
        if (!allowInsecure && url.scheme == "http") {
            throw OmniHubException("HTTPS is required; enable insecure HTTP only for a trusted local panel")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw OmniHubException("Put credentials in the panel pairing flow, not in the URL")
        }
        return url
    }

    private fun normalizeBase(raw: String, allowInsecure: Boolean): String {
        val url = validateUrl(raw, allowInsecure)
        if (url.encodedQuery != null || url.encodedFragment != null) {
            throw OmniHubException("The panel base URL must not contain a query or fragment")
        }
        return url.toString().trimEnd('/')
    }

    private fun originOf(url: HttpUrl): String =
        url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')

    private fun networkFailure(error: Exception): OmniHubException = when (error) {
        // Exception messages may contain the complete subscription URL; keep them out of UI and persisted status.
        is SocketTimeoutException -> OmniHubException("Panel timeout: no response")
        is UnknownHostException -> OmniHubException("DNS failed: host not found")
        is SSLException -> OmniHubException("TLS failed: handshake failed")
        is IOException -> OmniHubException("Network failed: I/O error")
        else -> OmniHubException("Panel request failed: ${error.javaClass.simpleName}")
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
