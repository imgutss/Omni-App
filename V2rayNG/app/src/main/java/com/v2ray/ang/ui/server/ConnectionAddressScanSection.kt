package com.v2ray.ang.ui.server

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.core.ConnectionAddressScanner
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.FormTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionAddressScanSection(state: ServerUiState, initial: ProfileItem) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var scanner by remember { mutableStateOf<ConnectionAddressScanner?>(null) }
    DisposableEffect(Unit) { onDispose { scanner?.cancel() } }
    Text(stringResource(R.string.address_scan_notice))
    FormTextField(stringResource(R.string.address_scan_candidates), input, { input = it })
    FormTextField(stringResource(R.string.address_scan_override), state.dialAddress, { state.dialAddress = it })
    Button(enabled = !running, onClick = {
        if (CoreServiceManager.isRunning()) {
            log = context.getString(R.string.warp_scan_stop_vpn)
            return@Button
        }
        val snapshot = state.toProfileItem(initial)
        val instance = ConnectionAddressScanner()
        scanner = instance
        running = true
        log = context.getString(R.string.aether_log_scan_started)
        job = scope.launch {
            try {
                val result = instance.scan(context, snapshot, input) { tested, total ->
                    scope.launch { log = context.getString(R.string.address_scan_progress, tested, total) }
                }
                if (result == null) log = context.getString(R.string.aether_scan_failed)
                else if (state.toProfileItem(initial) == snapshot) {
                    state.dialAddress = result.ip
                    log = context.getString(R.string.address_scan_found, result.ip, result.delay)
                } else log = context.getString(R.string.address_scan_changed)
            } catch (cancelled: CancellationException) {
                log = context.getString(R.string.aether_log_scan_cancelled)
                throw cancelled
            } catch (_: Exception) {
                log = context.getString(R.string.aether_scan_failed)
            } finally {
                instance.cancel()
                running = false
            }
        }
    }) { Text(stringResource(R.string.address_scan_button)) }
    if (running) OutlinedButton(onClick = { scanner?.cancel(); job?.cancel() }) {
        Text(stringResource(android.R.string.cancel))
    }
    if (log.isNotBlank()) Text(log)
}
