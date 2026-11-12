package com.v2ray.ang.firefox

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.util.UUID

/**
 * Reads and writes this phone's Firefox accounts.
 *
 * Everything here is local. The store has no network code at all: a caller signs in through
 * [FirefoxSignIn], gets tokens back, and hands them to [save]. Nothing is mirrored to a panel, a bot or a
 * relay, and [encode] is only ever reached from the sign-in screen and from quota updates.
 */
object FirefoxAccountStore {
    private const val KEY_ACCOUNTS = "pref_firefox_accounts"
    private const val KEY_ACTIVE = "pref_firefox_active_account"

    private val gson = Gson()
    private val type = object : TypeToken<List<FirefoxAccount>>() {}.type

    fun accounts(): List<FirefoxAccount> {
        val raw = MmkvManager.decodeSettingsString(KEY_ACCOUNTS) ?: return emptyList()
        return try {
            gson.fromJson<List<FirefoxAccount>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isEmpty(): Boolean = accounts().isEmpty()

    /** The account the proxy is expected to run on, or the first one when none was chosen. */
    fun activeAccount(): FirefoxAccount? {
        val all = accounts()
        if (all.isEmpty()) return null
        val wanted = MmkvManager.decodeSettingsString(KEY_ACTIVE)
        return all.firstOrNull { it.id == wanted } ?: all.first()
    }

    fun setActive(id: String) {
        MmkvManager.encodeSettings(KEY_ACTIVE, id)
        val updated = accounts().map { it.copy(active = it.id == id) }
        encode(updated)
    }

    /**
     * Adds an account, or replaces the sign-in of the one with the same address.
     *
     * Signing in again with the same address is the normal way to repair a broken session, so it updates
     * the existing row instead of piling up duplicates a person then has to clean.
     */
    fun save(account: FirefoxAccount): FirefoxAccount {
        val all = accounts().toMutableList()
        val index = all.indexOfFirst { it.id == account.id || (account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)) }
        val stored = if (index >= 0) {
            val previous = all[index]
            account.copy(
                id = previous.id,
                quotaLimitBytes = if (account.quotaLimitBytes >= 0) account.quotaLimitBytes else previous.quotaLimitBytes,
                quotaRemainingBytes = if (account.quotaRemainingBytes >= 0) account.quotaRemainingBytes else previous.quotaRemainingBytes,
                quotaResetsAt = if (account.quotaResetsAt > 0) account.quotaResetsAt else previous.quotaResetsAt,
                quotaReadAt = if (account.quotaReadAt > 0) account.quotaReadAt else previous.quotaReadAt,
                active = previous.active,
                problem = "",
            ).also { all[index] = it }
        } else {
            account.copy(
                id = account.id.ifBlank { UUID.randomUUID().toString() },
                active = all.isEmpty(),
            ).also { all += it }
        }
        if (all.size == 1) encode(listOf(stored.copy(active = true))) else encode(all)
        if (MmkvManager.decodeSettingsString(KEY_ACTIVE) == null) {
            MmkvManager.encodeSettings(KEY_ACTIVE, stored.id)
        }
        return stored
    }

    /** Stores a fresh quota reading for one account. */
    fun saveQuota(id: String, quota: FirefoxQuota) {
        val updated = accounts().map { account ->
            if (account.id != id) {
                account
            } else {
                account.copy(
                    quotaLimitBytes = if (quota.unlimited) -1L else quota.limitBytes,
                    quotaRemainingBytes = if (quota.unlimited) -1L else quota.remainingBytes,
                    quotaResetsAt = quota.resetsAt,
                    quotaReadAt = System.currentTimeMillis(),
                    problem = "",
                )
            }
        }
        encode(updated)
    }

    /** Records why an account cannot be used right now, without throwing the sign-in away. */
    fun saveProblem(id: String, problem: String) {
        encode(accounts().map { if (it.id == id) it.copy(problem = problem) else it })
    }

    fun remove(id: String) {
        val left = accounts().filterNot { it.id == id }
        encode(left)
        if (MmkvManager.decodeSettingsString(KEY_ACTIVE) == id) {
            left.firstOrNull()?.let { MmkvManager.encodeSettings(KEY_ACTIVE, it.id) }
                ?: MmkvManager.encodeSettings(KEY_ACTIVE, "")
        }
        if (left.isEmpty()) MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_ENABLED, "false")
    }

    /** Moves an account up or down in the order the core tries them. */
    fun move(id: String, up: Boolean) {
        val all = accounts().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in all.indices) return
        val moved = all.removeAt(index)
        all.add(target, moved)
        encode(all)
    }

    private fun encode(accounts: List<FirefoxAccount>) {
        MmkvManager.encodeSettings(KEY_ACCOUNTS, gson.toJson(accounts, type))
    }
}
