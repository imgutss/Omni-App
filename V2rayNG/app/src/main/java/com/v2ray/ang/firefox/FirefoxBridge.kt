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

    /** True when a socket on the loopback port of the account engine accepts a connection. */
    fun isListening(): Boolean = try {
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            probe.bind(InetSocketAddress("127.0.0.1", port()))
        }
        false
    } catch (e: Exception) {
        // The port is taken, which is what a listening engine looks like; anything else is not our business.
        e is java.io.IOException
    }

    /**
     * Whether this build can actually carry the Firefox account path.
     *
     * A missing engine is reported as [Engine.MISSING] and never approximated: routing the row "direct" would
     * leak the person's traffic while the UI claimed a tunnel.
     */
    fun engine(context: Context): Engine = if (isListening()) Engine.AVAILABLE else Engine.MISSING

    /**
     * The Xray outbound that sends a profile through this phone's account engine.
     *
     * It is a plain SOCKS outbound to the loopback port: the engine owns the Mozilla session, the account's
     * allowance and its accounting, so nothing about the account crosses into the Xray config. There is no
     * username or password to put in the config, because the engine authenticates with the pass it minted
     * from the person's own tokens.
     */
    fun socksOutboundJson(port: Int = port()): String = """
        {
          "protocol": "socks",
          "settings": { "servers": [ { "address": "127.0.0.1", "port": $port } ] },
          "tag": "firefox-account"
        }
    """.trimIndent()

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
     * The row is a real profile in the normal list, so selecting it starts the normal service with the normal
     * kill-switch, stats and per-app rules. It is a **custom** profile whose whole configuration is one SOCKS
     * outbound to the loopback port of the account engine: the engine owns the account, the allowance and the
     * accounting, and Xray only carries the phone's traffic into it. Nothing about the person's tokens enters
     * an Xray config, and no direct outbound is ever offered as a fallback.
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
            configType = EConfigType.CUSTOM,
            subscriptionId = subId,
            remarks = "Firefox",
            description = "account on this phone",
            addedTime = System.currentTimeMillis(),
        )
        if (guids.isEmpty()) {
            MmkvManager.encodeServerConfigWithRaw(guid, profile, socksConfig(port()))
            MmkvManager.encodeServerList(mutableListOf(guid), subId)
        } else {
            // Keep the row's configuration in step with the port the engine actually listens on.
            MmkvManager.encodeServerConfigWithRaw(guid, profile, socksConfig(port()))
        }
        return guid
    }

    /**
     * The smallest Xray configuration that is honest about this path: one SOCKS outbound to the loopback port
     * and a routing rule that sends everything to it — no `freedom` outbound, so a missing engine cannot
     * quietly turn into a direct connection.
     */
    fun socksConfig(port: Int = port()): String = """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [],
          "outbounds": [
            { "protocol": "socks", "tag": "firefox-account",
              "settings": { "servers": [ { "address": "127.0.0.1", "port": $port } ] } }
          ],
          "routing": { "domainStrategy": "AsIs", "rules": [] }
        }
    """.trimIndent()
}
