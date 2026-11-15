package com.v2ray.ang.firefox

import android.net.VpnService
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.ui.base.HelperBaseComponentActivity

/**
 * The menu entry "Firefox": the Firefox Hub, an independent section for the person's own Mozilla
 * account path — connect, live control, the Connection Doctor, every country, accounts and quota, and
 * the tools. It needs no running service to open: a person can sign in and read their allowance before
 * they ever connect. The connect button asks Android for the VPN permission, then starts the normal
 * service on the Firefox row, so the tunnel keeps the usual kill-switch, stats and per-app rules.
 */
class FirefoxActivity : HelperBaseComponentActivity() {

    private val viewModel: FirefoxHubViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startFirefox()
        }

    override fun onResume() {
        super.onResume()
        viewModel.startPolling()
    }

    override fun onPause() {
        viewModel.stopPolling()
        super.onPause()
    }

    /** Selects the Firefox row and starts the service, asking for the VPN permission when needed. */
    private fun connect() {
        val guid = viewModel.prepareConnect() ?: return
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startFirefox()
        } else {
            requestVpnPermission.launch(intent)
        }
    }

    private fun startFirefox() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    @Composable
    override fun ScreenContent() {
        FirefoxHubScreen(
            viewModel = viewModel,
            onBack = { finish() },
            onConnect = { connect() },
        )
    }
}
