package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreVpnProtect
import com.v2ray.ang.core.FirefoxCoreManager
import com.v2ray.ang.firefox.FirefoxBridge
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the Firefox account engine in :daemon, beside CoreVpnService.
 *
 * The account screen requests this service while it is visible, then talks to the engine over its
 * authenticated loopback API. When VPN connection starts, CoreServiceManager reconciles the same
 * child with the VpnService socket-protect bridge before any phone traffic is routed through it.
 */
class FirefoxAccountEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val started = FirefoxCoreManager.start(
                applicationContext,
                socksPort = FirefoxBridge.port(),
                apiPort = FirefoxBridge.DEFAULT_API_PORT,
                protect = CoreVpnProtect.current(),
            )
            if (!started) {
                LogUtil.e(
                    AppConfig.TAG,
                    "Firefox account engine service could not start: ${FirefoxCoreManager.lastFailure(this@FirefoxAccountEngineService)}",
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
