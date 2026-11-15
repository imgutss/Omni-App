package com.v2ray.ang.omni

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted store for Hub metadata and subscription links. Android Keystore owns the key;
 * no bot token, Cloudflare token, or administrator credential is accepted by this store.
 */
internal class OmniPanelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): List<OmniPanel> = synchronized(lock) {
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return@synchronized emptyList()
        try {
            val plain = decrypt(payload)
            val array = JSONArray(plain)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        OmniPanel(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            baseUrl = item.getString("baseUrl"),
                            subscriptionUrl = item.getString("subscriptionUrl"),
                            subscriptionId = item.getString("subscriptionId"),
                            allowInsecure = item.optBoolean("allowInsecure", false),
                            lastStatus = item.optString("lastStatus", OmniPanelStatus.NOT_CHECKED.name),
                            lastStatusDetail = item.optString("lastStatusDetail", ""),
                            lastCheckedAt = item.optLong("lastCheckedAt", 0L),
                            lastSyncAt = item.optLong("lastSyncAt", 0L),
                        )
                    )
                }
            }
        } catch (error: Exception) {
            throw OmniHubException("Secure Hub storage could not be read", null).initCause(error)
        }
    }

    fun save(panels: List<OmniPanel>) = synchronized(lock) {
        val array = JSONArray()
        panels.forEach { panel ->
            array.put(
                JSONObject()
                    .put("id", panel.id)
                    .put("name", panel.name)
                    .put("baseUrl", panel.baseUrl)
                    .put("subscriptionUrl", panel.subscriptionUrl)
                    .put("subscriptionId", panel.subscriptionId)
                    .put("allowInsecure", panel.allowInsecure)
                    .put("lastStatus", panel.lastStatus)
                    .put("lastStatusDetail", panel.lastStatusDetail)
                    .put("lastCheckedAt", panel.lastCheckedAt)
                    .put("lastSyncAt", panel.lastSyncAt)
            )
        }
        try {
            val encrypted = encrypt(array.toString())
            if (!preferences.edit().putString(KEY_PAYLOAD, encrypted).commit()) {
                throw OmniHubException("Secure Hub storage refused the update")
            }
        } catch (error: OmniHubException) {
            throw error
        } catch (error: Exception) {
            throw OmniHubException("Secure Hub storage could not be written", null).initCause(error)
        }
    }

    fun replace(panel: OmniPanel) {
        val panels = load().toMutableList()
        val index = panels.indexOfFirst { it.id == panel.id }
        if (index >= 0) panels[index] = panel else panels += panel
        save(panels)
    }

    fun scopedSession(panelId: String): OmniScopedSession? = synchronized(lock) {
        loadScopedSessions().firstOrNull { it.panelId == panelId && it.validAt(System.currentTimeMillis()) }
    }

    /** Includes expired sessions so the UI can offer local Forget without exposing the token. */
    fun scopedSessionInfo(panelId: String): OmniScopedSessionInfo? = synchronized(lock) {
        loadScopedSessions().firstOrNull { it.panelId == panelId }?.publicInfo()
    }

    fun saveScopedSession(session: OmniScopedSession) = synchronized(lock) {
        val sessions = loadScopedSessions().filterNot { it.panelId == session.panelId } + session
        saveScopedSessions(sessions)
    }

    fun removeScopedSession(panelId: String) = synchronized(lock) {
        saveScopedSessions(loadScopedSessions().filterNot { it.panelId == panelId })
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
        removeScopedSession(id)
    }

    private fun loadScopedSessions(): List<OmniScopedSession> {
        val payload = preferences.getString(KEY_SCOPED_PAYLOAD, null) ?: return emptyList()
        return try {
            val array = JSONArray(decrypt(payload))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val scopes = item.optJSONArray("scopes")?.let { values ->
                        buildSet { for (scope in 0 until values.length()) add(values.getString(scope)) }
                    } ?: emptySet()
                    add(OmniScopedSession(
                        panelId = item.getString("panelId"),
                        accessToken = item.getString("accessToken"),
                        userUuid = item.getString("userUuid"),
                        scopes = scopes,
                        expiresAt = item.optLong("expiresAt", 0L),
                    ))
                }
            }
        } catch (error: Exception) {
            throw OmniHubException("Secure scoped storage could not be read", null).initCause(error)
        }
    }

    private fun saveScopedSessions(sessions: List<OmniScopedSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject()
                .put("panelId", session.panelId)
                .put("accessToken", session.accessToken)
                .put("userUuid", session.userUuid)
                .put("scopes", JSONArray(session.scopes.toList()))
                .put("expiresAt", session.expiresAt))
        }
        try {
            val encrypted = encrypt(array.toString())
            if (!preferences.edit().putString(KEY_SCOPED_PAYLOAD, encrypted).commit()) {
                throw OmniHubException("Secure scoped storage refused the update")
            }
        } catch (error: OmniHubException) {
            throw error
        } catch (error: Exception) {
            throw OmniHubException("Secure scoped storage could not be written", null).initCause(error)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "Invalid encrypted Hub payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
        )
        val plain = cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
        return String(plain, StandardCharsets.UTF_8)
    }

    private companion object {
        const val PREFS = "omni_hub_secure"
        const val KEY_PAYLOAD = "panels_v1"
        const val KEY_SCOPED_PAYLOAD = "scoped_sessions_v1"
        const val KEY_ALIAS = "omni.hub.aes.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
