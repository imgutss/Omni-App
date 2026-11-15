package com.v2ray.ang.omni

import android.content.Context
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.omni.lab.OmniCoreRuntime
import com.v2ray.ang.util.LogUtil

/** The only Android-to-native bridge for scanner throughput; no simulated socket or WebView path exists. */
class OmniNativeCoreRuntime(context: Context) : OmniCoreRuntime {
    private val appContext = context.applicationContext
    @Volatile
    override var socksPort: Int = -1
        private set

    @Synchronized
    override fun start(exitConfiguration: String): Boolean {
        if (socksPort > 0) stop()
        return try {
            val port = CoreNativeManager.openExit(appContext, exitConfiguration)
            if (port <= 0) false else {
                socksPort = port
                true
            }
        } catch (error: Exception) {
            LogUtil.e("Omni", "Scanner native openExit failed", error)
            false
        }
    }

    @Synchronized
    override fun stop() {
        if (socksPort <= 0) return
        CoreNativeManager.closeExit()
        socksPort = -1
    }
}
