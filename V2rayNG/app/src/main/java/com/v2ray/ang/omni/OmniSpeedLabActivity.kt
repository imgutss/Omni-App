package com.v2ray.ang.omni

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import com.v2ray.ang.ui.compose.OmniTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class OmniSpeedLabActivity : BaseComponentActivity() {
    private val model: OmniSpeedLabViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        OmniSpeedLabScreen(
            state = state,
            onBack = { finish() },
            onSubscription = model::selectSubscription,
            onDownloadUrl = model::updateDownloadUrl,
            onUploadUrl = model::updateUploadUrl,
            onDownloadSize = model::updateDownloadSize,
            onUploadSize = model::updateUploadSize,
            onSpeed = model::runSpeedTest,
            onCancelSpeed = model::cancelSpeedTest,
            onPing = model::runRealPing,
            onRefresh = model::refresh,
        )
    }
}

@Composable
fun OmniSpeedLabScreen(
    state: OmniSpeedLabUiState,
    onBack: () -> Unit,
    onSubscription: (String) -> Unit,
    onDownloadUrl: (String) -> Unit,
    onUploadUrl: (String) -> Unit,
    onDownloadSize: (String) -> Unit,
    onUploadSize: (String) -> Unit,
    onSpeed: () -> Unit,
    onCancelSpeed: () -> Unit,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
) {
    val busy = state.speedRunning || state.pingRunning
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_speed_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_speed_subtitle), style = MaterialTheme.typography.bodyMedium)
                        OmniSpeedSubscriptionPicker(state, onSubscription)
                        OutlinedTextField(
                            value = state.downloadUrlInput,
                            onValueChange = onDownloadUrl,
                            enabled = !state.speedRunning,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.omni_scanner_download_url)) },
                        )
                        OutlinedTextField(
                            value = state.uploadUrlInput,
                            onValueChange = onUploadUrl,
                            enabled = !state.speedRunning,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.omni_scanner_upload_url)) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.downloadSizeInput,
                                onValueChange = onDownloadSize,
                                enabled = !state.speedRunning,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.omni_speed_download_size)) },
                            )
                            OutlinedTextField(
                                value = state.uploadSizeInput,
                                onValueChange = onUploadSize,
                                enabled = !state.speedRunning,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.omni_speed_upload_size)) },
                            )
                        }
                        Text(stringResource(R.string.omni_speed_size_note), style = MaterialTheme.typography.bodySmall)
                        state.plannedDataBytes?.let { bytes ->
                            Text(
                                stringResource(R.string.omni_speed_planned_data, state.totalCount, bytes / 1_048_576.0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.speedRunning) {
                                OutlinedButton(onClick = onCancelSpeed, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.omni_speed_cancel))
                                }
                            } else {
                                Button(
                                    onClick = onSpeed,
                                    enabled = state.results.isNotEmpty() && !state.pingRunning,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.omni_speed_run_all)) }
                            }
                            OutlinedButton(
                                onClick = onPing,
                                enabled = state.results.isNotEmpty() && !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.pingRunning) {
                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(20.dp))
                                    Text(stringResource(R.string.omni_speed_ping_running))
                                } else {
                                    Text(stringResource(R.string.omni_speed_ping_all))
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            enabled = !busy && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.omni_refresh)) }
                        if (state.speedRunning) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.omni_speed_progress, state.completedCount, state.totalCount))
                        }
                        state.message?.let {
                            Text(
                                speedMessageText(it),
                                color = if (it in setOf(OmniSpeedLabMessage.BATCH_FINISHED, OmniSpeedLabMessage.BATCH_CANCELLED)) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.omni_speed_ranked_results), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.omni_speed_separate_metrics), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.results.isEmpty() && !state.loading) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.omni_speed_empty), modifier = Modifier.padding(16.dp))
                    }
                }
            } else {
                itemsIndexed(state.results, key = { _, result -> result.profile.guid }) { index, result ->
                    OmniSpeedResultRow(
                        index = index + 1,
                        result = result,
                        ping = state.pingResults[result.profile.guid],
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OmniSpeedSubscriptionPicker(state: OmniSpeedLabUiState, onSubscription: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.subscriptions.firstOrNull { it.id == state.selectedSubscriptionId }
    OutlinedButton(
        onClick = { expanded = true },
        enabled = state.subscriptions.isNotEmpty() && !state.speedRunning && !state.pingRunning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            selected?.let { stringResource(R.string.omni_speed_subscription_count, omniSpeedSubscriptionName(it), it.profiles.size) }
                ?: stringResource(R.string.omni_speed_choose_subscription),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        state.subscriptions.forEach { subscription ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.omni_speed_subscription_count, omniSpeedSubscriptionName(subscription), subscription.profiles.size)) },
                onClick = { onSubscription(subscription.id); expanded = false },
            )
        }
    }
}

@Composable
private fun omniSpeedSubscriptionName(subscription: OmniSpeedSubscription): String =
    subscription.name.ifBlank { stringResource(R.string.omni_speed_default_subscription) }

@Composable
private fun OmniSpeedResultRow(
    index: Int,
    result: OmniSpeedProfileResult,
    ping: OmniSpeedPing?,
    modifier: Modifier = Modifier,
) {
    val profile = result.profile
    val noResponse = stringResource(R.string.omni_scanner_no_response)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.omni_speed_result_rank, index, profile.profile.remarks.ifBlank { profile.guid }),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(profile.profile.configType.name, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.omni_speed_endpoint, profile.profile.server.orEmpty(), profile.profile.serverPort.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
            )
            when (result.status) {
                OmniSpeedStatus.UNTESTED -> Text(stringResource(R.string.omni_speed_no_transfer), style = MaterialTheme.typography.bodySmall)
                OmniSpeedStatus.RUNNING -> Text(stringResource(R.string.omni_speed_status_running), color = MaterialTheme.colorScheme.primary)
                OmniSpeedStatus.FAILED -> Text(stringResource(R.string.omni_speed_status_failed), color = MaterialTheme.colorScheme.error)
                OmniSpeedStatus.SUCCESS -> Unit
            }
            result.transfer?.let { transfer ->
                if (transfer.downloadTtfbMs != null || transfer.downloadHttpStatus != null) {
                    Text(
                        stringResource(
                            R.string.omni_scanner_tunnel_metrics,
                            transfer.downloadTtfbMs?.toString() ?: noResponse,
                            transfer.downloadHttpStatus?.toString() ?: noResponse,
                        ),
                    )
                }
                if (transfer.success) {
                    Text(
                        stringResource(
                            R.string.omni_scanner_download_metrics,
                            transfer.downloadBytes,
                            transfer.downloadDurationMs,
                            transfer.downloadMBps,
                            transfer.downloadMbps,
                        ),
                    )
                }
                if (transfer.success && transfer.requestedUploadBytes > 0) {
                    Text(
                        stringResource(
                            R.string.omni_scanner_upload_metrics,
                            transfer.uploadBytes,
                            transfer.uploadDurationMs,
                            transfer.uploadMBps,
                            transfer.uploadMbps,
                        ),
                    )
                }
                Text(
                    stringResource(
                        R.string.omni_scanner_data_use,
                        transfer.downloadLimitBytes,
                        transfer.requestedUploadBytes,
                        transfer.dataUsedBytes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.omni_speed_elapsed, transfer.durationMs), style = MaterialTheme.typography.bodySmall)
                transfer.error?.let {
                    Text(stringResource(R.string.omni_speed_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            result.downloadHost?.let { Text(stringResource(R.string.omni_speed_download_endpoint, it), style = MaterialTheme.typography.bodySmall) }
            if (result.usedFallback) {
                Text(stringResource(R.string.omni_speed_fallback), style = MaterialTheme.typography.bodySmall)
            }
            ping?.let {
                Text(
                    stringResource(
                        R.string.omni_scanner_ping_metrics,
                        it.successfulSamples,
                        it.sampleCount,
                        it.delayMs?.toString() ?: noResponse,
                        it.jitterMs?.toString() ?: noResponse,
                    ),
                )
            }
        }
    }
}

@Composable
private fun speedMessageText(message: OmniSpeedLabMessage): String = stringResource(
    when (message) {
        OmniSpeedLabMessage.LOAD_FAILED -> R.string.omni_speed_load_failed
        OmniSpeedLabMessage.NO_PROFILE -> R.string.omni_speed_no_profile
        OmniSpeedLabMessage.INVALID_INPUT -> R.string.omni_speed_invalid_input
        OmniSpeedLabMessage.SPEED_FAILED -> R.string.omni_speed_failed
        OmniSpeedLabMessage.PING_FAILED -> R.string.omni_speed_ping_failed
        OmniSpeedLabMessage.BATCH_FINISHED -> R.string.omni_speed_batch_finished
        OmniSpeedLabMessage.BATCH_PARTIAL -> R.string.omni_speed_batch_partial
        OmniSpeedLabMessage.BATCH_CANCELLED -> R.string.omni_speed_batch_cancelled
    },
)
