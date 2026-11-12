package com.v2ray.ang.firefox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.ui.base.BaseComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The menu entry "Firefox": the account screen of this phone, and nothing of anybody else's.
 *
 * It is reached from the drawer and needs no running service, so a person can sign in and read their allowance
 * before they ever connect anything.
 */
class FirefoxActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        val context = LocalContext.current
        val state by produceState(
            initialValue = FirefoxBridge.Engine.MISSING to FirefoxBridge.port(),
            key1 = context,
        ) {
            value = withContext(Dispatchers.IO) {
                FirefoxBridge.engine(context) to FirefoxBridge.port()
            }
        }
        FirefoxScreen(
            engineAvailable = state.first == FirefoxBridge.Engine.AVAILABLE,
            port = state.second,
            onBack = { finish() },
        )
    }
}
