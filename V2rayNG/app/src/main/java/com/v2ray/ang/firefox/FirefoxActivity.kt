package com.v2ray.ang.firefox

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseComponentActivity

/**
 * The menu entry "Firefox": the account screen of this phone, and nothing of anybody else's.
 *
 * It is reached from the drawer and needs no running service, so a person can sign in and read their
 * allowance before they ever connect anything. The screen starts the engine itself when it needs it.
 */
class FirefoxActivity : BaseComponentActivity() {

    private val viewModel: FirefoxViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        FirefoxScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }
}
