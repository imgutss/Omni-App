package com.v2ray.ang.ui.server

import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherScanner
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** WARP-only endpoint discovery. Never changes the user's keys, addresses or AWG parameters. */
@Composable
internal fun WarpEndpointScanSection(state: ServerUiState) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val warpPeer = state.publicKey.trim() == "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    Text(stringResource(R.string.warp_scan_notice))
    Button(enabled = !running && warpPeer, onClick = {
        if (CoreServiceManager.isRunning()) {
            log = context.getString(R.string.warp_scan_stop_vpn)
            return@Button
        }
        running = true
        log = context.getString(R.string.aether_log_scan_started)
        job = scope.launch {
            try {
                // Aether uses its own registered scan identity. Results establish Cloudflare UDP
                // reachability, not authentication of the user's separate WG/AWG identity.
                val profile = ProfileItem.create(EConfigType.AETHER).apply {
                    aetherProtocol = "wg"
                    aetherScanMode = "balanced"
                    aetherIpVersion = "both"
                }
                val result = AetherScanner.scan(context, profile)
                if (result == null) {
                    log = context.getString(R.string.aether_scan_failed)
                } else {
                    state.address = result.endpoint.host
                    state.port = result.endpoint.port.toString()
                    log = context.getString(R.string.aether_log_scan_found, result.endpoint.toString())
                }
            } catch (cancelled: CancellationException) {
                log = context.getString(R.string.aether_log_scan_cancelled)
                throw cancelled
            } catch (error: Exception) {
                // Do not expose native output or profile credentials in the editor.
                log = context.getString(R.string.aether_scan_failed)
            } finally {
                running = false
            }
        }
    }) { Text(stringResource(R.string.warp_scan_button)) }
    if (running) {
        OutlinedButton(onClick = { job?.cancel() }) {
            Text(stringResource(android.R.string.cancel))
        }
    }
    if (log.isNotBlank()) Text(log)
}
