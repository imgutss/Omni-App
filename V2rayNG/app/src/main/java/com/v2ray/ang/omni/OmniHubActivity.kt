package com.v2ray.ang.omni

import android.content.Intent
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import com.v2ray.ang.ui.compose.OmniCheckbox as Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import com.v2ray.ang.ui.compose.OmniTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.omni.dns.OmniDnsScannerActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

class OmniHubActivity : HelperBaseComponentActivity() {
    private val model: OmniHubViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    @Composable
    override fun ScreenContent() {
        var scannedPair by rememberSaveable { mutableStateOf<String?>(null) }
        OmniHubScreen(
            model = model,
            onBack = { finish() },
            scannedPair = scannedPair,
            onScanPair = {
                launchQRCodeScanner { result ->
                    if (result != null) scannedPair = result
                }
            },
            onScannedPairConsumed = { scannedPair = null },
        )
    }
}

@Composable
fun OmniHubScreen(
    model: OmniHubViewModel,
    onBack: () -> Unit,
    scannedPair: String? = null,
    onScanPair: () -> Unit = {},
    onScannedPairConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val panels = state.panels
    val busy = state.busy || state.loading
    val message = state.message
    val scopedScopes = state.scopedScopes
    val scopedSessions = state.scopedSessions
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showPair by rememberSaveable { mutableStateOf(false) }
    var pairInitialCode by rememberSaveable { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<OmniPanel?>(null) }

    LaunchedEffect(scannedPair) {
        if (scannedPair != null) {
            pairInitialCode = scannedPair
            showPair = true
            onScannedPairConsumed()
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.title_omni_panel), onBackClick = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.omni_hub_subtitle), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.omni_hub_secure_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAdd = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.omni_hub_add_subscription))
                    }
                    OutlinedButton(onClick = { pairInitialCode = ""; showPair = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.omni_hub_pair_code))
                    }
                }
            }
            item {
                OutlinedButton(onClick = onScanPair, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.omni_hub_pair_qr))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = model::syncAll,
                        enabled = !busy && panels.isNotEmpty(),
                    ) { Text(stringResource(R.string.omni_hub_sync_all)) }
                    Spacer(Modifier.width(12.dp))
                    if (busy) CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_hub_tools_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.omni_hub_tools_subtitle), style = MaterialTheme.typography.bodySmall)
                        OmniHubToolButton(R.string.omni_sync_title) {
                            context.startActivity(Intent(context, OmniSyncCenterActivity::class.java))
                        }
                        OmniHubToolButton(R.string.omni_speed_title) {
                            context.startActivity(Intent(context, OmniSpeedLabActivity::class.java))
                        }
                        OmniHubToolButton(R.string.omni_dns_scanner_title) {
                            context.startActivity(Intent(context, OmniDnsScannerActivity::class.java))
                        }
                        OmniHubToolButton(R.string.omni_routing_title) {
                            context.startActivity(Intent(context, OmniRoutingStudioActivity::class.java))
                        }
                        OmniHubToolButton(R.string.omni_dns_title) {
                            context.startActivity(Intent(context, OmniDnsLabActivity::class.java))
                        }
                        OmniHubToolButton(R.string.omni_privacy_title) {
                            context.startActivity(Intent(context, OmniPrivacyCenterActivity::class.java))
                        }
                    }
                }
            }
            message?.let { item { Text(it, color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } }
            if (panels.isEmpty() && !busy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.omni_hub_empty), Modifier.padding(16.dp))
                    }
                }
            }
            items(panels, key = { it.id }) { panel ->
                OmniPanelCard(
                    panel = panel,
                    busy = busy,
                    scopes = scopedScopes[panel.id].orEmpty(),
                    session = scopedSessions[panel.id],
                    onSync = { model.sync(panel.id) },
                    onCheck = { model.check(panel.id) },
                    onRemove = { removeTarget = panel },
                    onRevoke = { model.revokeScoped(panel.id) },
                    onReadTemplates = { model.readScopedTemplates(panel.id) },
                    onReportDevice = { model.reportDevice(panel.id) },
                    onDoctor = {
                        context.startActivity(
                            Intent(context, OmniDoctorActivity::class.java)
                                .putExtra(OmniDoctorActivity.EXTRA_PANEL_ID, panel.id)
                        )
                    },
                )
            }
        }
    }

    if (showAdd) {
        OmniAddSubscriptionDialog(
            busy = busy,
            onDismiss = { showAdd = false },
            onConfirm = { name, url, insecure ->
                showAdd = false
                model.addSubscription(name, url, insecure)
            },
        )
    }
    if (showPair) {
        OmniPairDialog(
            busy = busy,
            initialCode = pairInitialCode,
            onDismiss = { showPair = false },
            onConfirm = { base, code, name, insecure ->
                showPair = false
                model.pairWithCode(base, code, name, insecure)
            },
        )
    }
    removeTarget?.let { panel ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.omni_hub_remove)) },
            text = {
                Column {
                    Text(stringResource(R.string.omni_hub_remove_confirm))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.omni_hub_remove_warning))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    model.remove(panel.id)
                }) { Text(stringResource(R.string.omni_hub_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun OmniHubToolButton(@StringRes labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun OmniPanelCard(
    panel: OmniPanel,
    busy: Boolean,
    scopes: Set<String>,
    session: OmniScopedSessionInfo?,
    onSync: () -> Unit,
    onCheck: () -> Unit,
    onRemove: () -> Unit,
    onRevoke: () -> Unit,
    onReadTemplates: () -> Unit,
    onReportDevice: () -> Unit,
    onDoctor: () -> Unit,
) {
    val status = runCatching { OmniPanelStatus.valueOf(panel.lastStatus) }.getOrDefault(OmniPanelStatus.INVALID)
    val statusDetail = omniSafePanelStatusDetail(panel.lastStatusDetail)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(panel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(panel.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(omniPanelStatusText(status), color = MaterialTheme.colorScheme.primary)
            if (statusDetail.isNotBlank()) {
                Text(statusDetail, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (panel.lastSyncAt > 0L) {
                    stringResource(
                        R.string.omni_hub_last_sync,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(panel.lastSyncAt)),
                    )
                } else stringResource(R.string.omni_hub_not_synced),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.omni_hub_scoped_title), style = MaterialTheme.typography.labelLarge)
            if (session != null) {
                Text(
                    stringResource(
                        if (session.isActive(System.currentTimeMillis())) R.string.omni_hub_pairing_expiry else R.string.omni_hub_pairing_expired,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.expiresAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (scopes.isEmpty()) {
                Text(stringResource(R.string.omni_hub_scoped_none), style = MaterialTheme.typography.bodySmall)
            } else {
                val capabilities = buildList {
                    if ("templates:read" in scopes) add(stringResource(R.string.omni_hub_scope_templates))
                    if ("device:report" in scopes) add(stringResource(R.string.omni_hub_scope_device))
                    if ("scanner:publish" in scopes) add(stringResource(R.string.omni_hub_scope_scanner))
                }
                Text(stringResource(R.string.omni_hub_scoped_capabilities, capabilities.joinToString()), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRevoke, enabled = !busy) { Text(stringResource(R.string.omni_hub_revoke)) }
                if ("templates:read" in scopes) {
                    TextButton(onClick = onReadTemplates, enabled = !busy) { Text(stringResource(R.string.omni_hub_read_templates)) }
                }
                if ("device:report" in scopes) {
                    Text(stringResource(R.string.omni_hub_device_report_note), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onReportDevice, enabled = !busy) { Text(stringResource(R.string.omni_hub_report_device)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSync, enabled = !busy) { Text(stringResource(R.string.omni_hub_sync)) }
                TextButton(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.omni_hub_check)) }
                TextButton(onClick = onDoctor) { Text(stringResource(R.string.title_omni_doctor)) }
                TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.omni_hub_remove)) }
            }
        }
    }
}

@Composable
private fun omniPanelStatusText(status: OmniPanelStatus): String = stringResource(
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
    }
)

@Composable
private fun OmniAddSubscriptionDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var insecure by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.omni_hub_add_subscription)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.omni_hub_name)) }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.omni_hub_subscription_url)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                    Text(stringResource(R.string.omni_hub_allow_insecure), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, insecure) }, enabled = !busy && url.isNotBlank()) {
                Text(stringResource(R.string.omni_hub_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun OmniPairDialog(
    busy: Boolean,
    initialCode: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, Boolean) -> Unit,
) {
    var base by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable(initialCode) { mutableStateOf(initialCode) }
    var name by rememberSaveable { mutableStateOf("") }
    var insecure by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.omni_hub_pair_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.omni_hub_pair_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.omni_hub_pair_qr_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text(stringResource(R.string.omni_hub_base_url)) }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text(stringResource(R.string.omni_hub_pair_payload)) }, singleLine = true)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.omni_hub_name)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                    Text(stringResource(R.string.omni_hub_allow_insecure), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(base, code, name, insecure) }, enabled = !busy && code.isNotBlank() && (base.isNotBlank() || code.trim().startsWith("http", ignoreCase = true))) {
                Text(stringResource(R.string.omni_hub_pair))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
