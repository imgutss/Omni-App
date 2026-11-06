package com.v2ray.ang.omni

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import com.v2ray.ang.ui.compose.OmniCheckbox as Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.v2ray.ang.ui.compose.OmniTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import java.text.DateFormat
import java.util.Date

class OmniSyncCenterActivity : BaseComponentActivity() {
    private val model: OmniSyncCenterViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        OmniSyncCenterScreen(
            state = state,
            onBack = { finish() },
            onRefresh = model::refresh,
            onToggle = model::togglePanel,
            onToggleAll = model::toggleAll,
            onSyncSelected = model::syncSelected,
            onCheckSelected = model::checkSelected,
            onSyncAll = model::syncAll,
            onCheckAll = model::checkAll,
            onSyncPanel = model::syncPanel,
            onCheckPanel = model::checkPanel,
            onOpenHub = { startActivity(Intent(this, OmniHubActivity::class.java)) },
        )
    }
}

@Composable
fun OmniSyncCenterScreen(
    state: OmniSyncCenterUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onSyncSelected: () -> Unit,
    onCheckSelected: () -> Unit,
    onSyncAll: () -> Unit,
    onCheckAll: () -> Unit,
    onSyncPanel: (String) -> Unit,
    onCheckPanel: (String) -> Unit,
    onOpenHub: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_sync_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.omni_sync_subtitle), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.omni_sync_selected, state.selectedPanelIds.size, state.panels.size))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSyncSelected, enabled = !state.running && state.selectedPanelIds.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_sync_selected_action))
                        }
                        OutlinedButton(onClick = onCheckSelected, enabled = !state.running && state.selectedPanelIds.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_sync_check_selected))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSyncAll, enabled = !state.running && state.panels.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_sync_all))
                        }
                        OutlinedButton(onClick = onCheckAll, enabled = !state.running && state.panels.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_sync_check_all))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onToggleAll, enabled = !state.running && state.panels.isNotEmpty()) {
                            Text(stringResource(R.string.omni_sync_toggle_all))
                        }
                        TextButton(onClick = onRefresh, enabled = !state.running && !state.loading) {
                            Text(stringResource(R.string.omni_refresh))
                        }
                        TextButton(onClick = onOpenHub, enabled = !state.running) {
                            Text(stringResource(R.string.omni_sync_manage_panels))
                        }
                    }
                    state.message?.let { Text(syncMessageText(it), color = MaterialTheme.colorScheme.primary) }
                    if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.loading && state.panels.isEmpty()) {
                item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
            } else if (state.panels.isEmpty()) {
                item { Text(stringResource(R.string.omni_sync_empty), modifier = Modifier.padding(16.dp)) }
            }
            items(state.panels, key = { it.id }) { panel ->
                OmniSyncPanelCard(
                    panel = panel,
                    selected = panel.id in state.selectedPanelIds,
                    result = state.results[panel.id],
                    active = state.activePanelId == panel.id,
                    running = state.running,
                    onToggle = { onToggle(panel.id) },
                    onSync = { onSyncPanel(panel.id) },
                    onCheck = { onCheckPanel(panel.id) },
                )
            }
        }
    }
}

@Composable
private fun OmniSyncPanelCard(
    panel: OmniPanel,
    selected: Boolean,
    result: OmniSyncPanelResult?,
    active: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onSync: () -> Unit,
    onCheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(value = selected, enabled = !running, role = Role.Checkbox, onValueChange = { onToggle() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = !running)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(panel.name, style = MaterialTheme.typography.titleMedium)
                    Text(panel.baseUrl, style = MaterialTheme.typography.bodySmall)
                }
                if (active) CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
            }
            Text(omniSyncStatusText(panel.lastStatus), color = MaterialTheme.colorScheme.primary)
            omniSafePanelStatusDetail(panel.lastStatusDetail).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                if (panel.lastSyncAt > 0L) {
                    stringResource(R.string.omni_hub_last_sync, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(panel.lastSyncAt)))
                } else stringResource(R.string.omni_hub_not_synced),
                style = MaterialTheme.typography.bodySmall,
            )
            result?.let { Text(syncResultText(it), style = MaterialTheme.typography.bodySmall, color = if (it.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSync, enabled = !running) { Text(stringResource(R.string.omni_hub_sync)) }
                TextButton(onClick = onCheck, enabled = !running) { Text(stringResource(R.string.omni_hub_check)) }
            }
        }
    }
}

@Composable
private fun omniSyncStatusText(raw: String): String {
    val status = runCatching { OmniPanelStatus.valueOf(raw) }.getOrDefault(OmniPanelStatus.INVALID)
    return stringResource(
        when (status) {
            OmniPanelStatus.NOT_CHECKED -> R.string.omni_hub_status_not_checked
            OmniPanelStatus.READY -> R.string.omni_hub_status_ready
            OmniPanelStatus.AUTH_OR_EDGE_REFUSED -> R.string.omni_hub_status_auth
            OmniPanelStatus.NOT_FOUND -> R.string.omni_hub_status_not_found
            OmniPanelStatus.TIMEOUT -> R.string.omni_hub_status_timeout
            OmniPanelStatus.DNS_ERROR -> R.string.omni_hub_status_dns
            OmniPanelStatus.TLS_ERROR -> R.string.omni_hub_status_tls
            OmniPanelStatus.NETWORK_ERROR -> R.string.omni_hub_status_network
            OmniPanelStatus.HTTP_ERROR -> R.string.omni_hub_status_http
            OmniPanelStatus.INVALID -> R.string.omni_hub_status_invalid
        },
    )
}

@Composable
private fun syncResultText(result: OmniSyncPanelResult): String {
    if (!result.success) return stringResource(R.string.omni_sync_result_failed, result.detail.ifBlank { stringResource(R.string.omni_sync_no_detail) })
    return when (result.operation) {
        OmniSyncOperation.SYNC -> stringResource(R.string.omni_sync_result_success, result.configCount ?: 0)
        OmniSyncOperation.CHECK -> stringResource(R.string.omni_sync_check_result_success, result.detail)
    }
}

@Composable
private fun syncMessageText(message: OmniSyncMessage): String = stringResource(
    when (message) {
        OmniSyncMessage.LOAD_FAILED -> R.string.omni_sync_load_failed
        OmniSyncMessage.NO_PANEL_SELECTED -> R.string.omni_sync_no_selection
        OmniSyncMessage.PARTIAL_FAILURE -> R.string.omni_sync_partial
        OmniSyncMessage.ALL_SUCCEEDED -> R.string.omni_sync_all_succeeded
    },
)
