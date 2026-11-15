package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit,
    private val firefoxDirect: Boolean = false,
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks(): Boolean {
//        LogUtil.i(AppConfig.TAG, "Starting HevSocks5Tunnel via JNI")

        return try {
            val configContent = buildConfig()
            val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
                writeText(configContent)
            }
//            LogUtil.i(AppConfig.TAG, "Config file created: ${configFile.absolutePath}")
            LogUtil.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

//            LogUtil.i(AppConfig.TAG, "TProxyStartService...")
            // False when the native thread could not be created, or when one is still running.
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}", e)
            false
        }
    }

    /**
     * The flag the native side sets before the start call returns and clears when its loop ends,
     * whether it was asked to end or gave up while setting itself up.
     */
    override fun isTun2SocksRunning(): Boolean = try {
        TProxyIsRunning()
    } catch (e: UnsatisfiedLinkError) {
        // A native library built before this call existed cannot tell, and that must not read as a tunnel that failed.
        LogUtil.e(AppConfig.TAG, "hev-socks5-tunnel cannot report whether it runs", e)
        true
    }

    internal fun buildConfig(): String = if (firefoxDirect) buildFirefoxConfig() else buildXrayConfig()

    /**
     * The Firefox direct tunnel: hev reads the account engine's own SOCKS listener, with the login the
     * app generated, and maps DNS into the engine's FakeDNS range. There is no Xray hop, so the engine
     * does the routing and DNS itself.
     */
    private fun buildFirefoxConfig(): String {
        val (socksUser, socksPass) = FirefoxBridge.ensureCredentials()
        val escapedUser = socksUser.replace("'", "''")
        val escapedPass = socksPass.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${AppConfig.FIREFOX_TUN_IPV4}")
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, AppConfig.DEFAULT_IPV6_ENABLED)) {
                appendLine("  ipv6: '${AppConfig.FIREFOX_TUN_IPV6}'")
            }
            appendLine("socks5:")
            appendLine("  port: ${FirefoxBridge.port()}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            appendLine("  username: '$escapedUser'")
            appendLine("  password: '$escapedPass'")
            appendLine("  pipeline: true")
            appendLine("mapdns:")
            appendLine("  address: ${AppConfig.FIREFOX_TUN_DNS}")
            appendLine("  port: 53")
            appendLine("  network: ${AppConfig.FIREFOX_FAKE_NET}")
            appendLine("  netmask: ${AppConfig.FIREFOX_FAKE_MASK}")
            appendLine("  cache-size: 10000")
            appendHevTimeouts()
        }
    }

    /** The normal Xray path: hev reads Xray's SOCKS inbound. */
    private fun buildXrayConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val escapedSocksUsername = socksUsername?.replace("'", "''")
        val escapedSocksPassword = socksPassword?.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, AppConfig.DEFAULT_IPV6_ENABLED)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            // pipeline avoids the ~200ms MSG_MORE delay on small writes.
            appendLine("  pipeline: true")
            if (escapedSocksUsername != null && escapedSocksPassword != null) {
                appendLine("  username: '${escapedSocksUsername}'")
                appendLine("  password: '${escapedSocksPassword}'")
            }
            appendHevTimeouts()
        }
    }

    private fun StringBuilder.appendHevTimeouts() {
        val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
        val parts = timeoutSetting.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
        val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
        appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
        appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, AppConfig.DEFAULT_HEV_TUNNEL_LOGLEVEL)}")
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        try {
            LogUtil.i(AppConfig.TAG, "TProxyStopService...")
            TProxyStopService()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", e)
        }
    }
}
