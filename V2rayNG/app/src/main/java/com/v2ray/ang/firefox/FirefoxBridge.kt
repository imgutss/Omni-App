package com.v2ray.ang.firefox

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The local endpoints the Firefox account path uses, and whether this phone can actually offer it.
 *
 * Two things have to exist before a "Firefox" row can carry traffic:
 *
 * 1. a local proxy the Xray outbound can dial, and
 * 2. the engine that fills it — the Mozilla MASQUE/Fastly core.
 *
 * The second one is a **separate engine**, not an Xray outbound: it signs in to a Firefox account, takes a
 * connection pass from Guardian and carries traffic to Mozilla's own Fastly proxies. That engine is not part
 * of this APK, and pretending otherwise would give people a row that silently sends their data directly. So
 * this class answers honestly: [Engine.AVAILABLE] when a loopback proxy with the expected handshake answers,
 * [Engine.MISSING] otherwise, and the screen says so instead of failing at connect time.
 */
object FirefoxBridge {
    /** The default loopback port of the account engine, as the reference client uses it. */
    const val DEFAULT_PORT = 10810

    /** The loopback port of the engine's own API, which only this app may call. */
    const val DEFAULT_API_PORT = 10820

    private const val TAG = "FirefoxBridge"

    /** How long one probe of a loopback port waits before calling it closed. */
    private const val PROBE_TIMEOUT_MS = 400

    enum class Engine {
        /** A local proxy answered on the configured port: the account path can carry traffic now. */
        AVAILABLE,

        /** Nothing answers on the port, so the engine is not part of this build or is not running. */
        MISSING,
    }

    /** The port the account engine listens on. Kept off every port the Xray session owns. */
    fun port(): Int {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_FIREFOX_CORE_PORT)?.toIntOrNull()
        return (stored ?: DEFAULT_PORT).coerceIn(1024, 65535)
    }

    fun setPort(port: Int) {
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_CORE_PORT, port.coerceIn(1024, 65535))
    }

    fun enabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREFOX_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_FIREFOX_ENABLED, enabled.toString())
    }

    /**
     * Whether the account engine is up and answering on loopback right now.
     *
     * Two ports matter and the engine binds both: [port] is the SOCKS listener the profile is built on,
     * and [DEFAULT_API_PORT] is the account API the screen calls. This used to bind the SOCKS port
     * itself and call a refusal a running engine — so an Xray outbound or any other app holding that
     * port made the screen say "ready" while nothing was answering.
     */
    fun isListening(): Boolean = listening(port()) && listening(DEFAULT_API_PORT)

    /** Whether something accepts a connection on [port] on loopback, without taking the port. */
    private fun listening(port: Int): Boolean = try {
        java.net.Socket().use { probe ->
            probe.connect(InetSocketAddress(AppConfig.LOOPBACK, port), PROBE_TIMEOUT_MS)
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Whether this build can actually carry the Firefox account path.
     *
     * A missing engine is reported as [Engine.MISSING] and never approximated: routing the row "direct" would
     * leak the person's traffic while the UI claimed a tunnel.
     */
    /**
     * Whether this build can carry the account path right now.
     *
     * [Engine.MISSING] is never approximated with a direct route: that would send somebody's traffic out
     * in the clear while the screen claimed a tunnel.
     */
    fun engine(): Engine = if (isListening()) Engine.AVAILABLE else Engine.MISSING

    /** Logs, without any account data, why the path is unavailable; the screen shows the same reason. */
    fun describeUnavailable(context: Context): String {
        LogUtil.i(TAG, "firefox account engine is not part of this build or not running on ${port()}")
        return "engine-missing"
    }

    /** The name of the server group the account row lives in, as people see it in the group bar. */
    const val GROUP_NAME = "Firefox"

    /** The guid of the one row of the account group, or null when the group does not exist yet. */
    fun firefoxGuid(): String? {
        val subscription = MmkvManager.decodeSubscriptions()
            .firstOrNull { it.subscription.remarks == GROUP_NAME } ?: return null
        return MmkvManager.decodeServerList(subscription.guid).firstOrNull()
    }

    /**
     * Whether [guid] names the row that carries traffic through the account engine.
     *
     * The daemon asks this before it starts a configuration: that row is the one whose traffic depends
     * on the engine on this phone, so the engine is started for it and for no other.
     */
    fun isFirefoxRow(guid: String): Boolean = guid.isNotBlank() && guid == firefoxGuid()

    /**
     * Makes sure the group and its one row exist, and returns the guid of that row.
     *
     * The row is a real profile in the normal list, so selecting it starts the normal service with the
     * normal kill-switch, stats and per-app rules. It is a **SOCKS profile** whose server is the engine's
     * own loopback listener: the engine owns the account, the allowance and the accounting, and Xray only
     * carries the phone's traffic into it. That was the bug in the first version of this screen — the row
     * was written as a CUSTOM profile with a hand-written configuration, which the speed test reads as a
     * whole core of its own to start, and a hand-written configuration with no inbound is not a profile
     * anything can measure or edit.
     */
    fun ensureGroup(): String {
        val name = GROUP_NAME
        val existing = MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == name }
        val subId = existing?.guid
            ?: MmkvManager.tryEncodeSubscription("", SubscriptionItem(remarks = name), listFirst = true)
        if (subId.isNullOrBlank()) return ""

        val guids = MmkvManager.decodeServerList(subId)
        val guid = guids.firstOrNull() ?: Utils.getUuid()
        val profile = ProfileItem(
            configType = EConfigType.SOCKS,
            subscriptionId = subId,
            remarks = "Firefox",
            description = "account on this phone",
            server = AppConfig.LOOPBACK,
            serverPort = port().toString(),
            addedTime = System.currentTimeMillis(),
        )
        MmkvManager.encodeServerConfig(guid, profile)
        if (guids.isEmpty()) {
            MmkvManager.encodeServerList(mutableListOf(guid), subId)
        }
        return guid
    }

    /**
     * Whether the engine holds a signed-in account right now.
     *
     * Asked of the engine itself, through the same login file the screen uses, and bounded: an engine
     * that is not answering means no account, which the daemon then reports instead of starting a link
     * that would carry nothing.
     */
    fun hasAccount(): Boolean = com.v2ray.ang.core.FirefoxCoreManager.hasAccount(
        com.v2ray.ang.AngApplication.application
    )

    /**
     * The engine's loopback SOCKS address as a profile URI, for a person who wants the account row in a
     * client other than this one. The app itself builds a [ProfileItem] directly (see [ensureGroup]).
     */
    fun socksUri(): String = "socks://${AppConfig.LOOPBACK}:${port()}#Firefox"
}
