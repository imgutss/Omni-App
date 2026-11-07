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
import com.v2ray.ang.ui.compose.OmniAlertDialog as AlertDialog
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.v2ray.ang.ui.compose.OmniTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.settings.SettingsActivity

class OmniPrivacyCenterActivity : BaseComponentActivity() {
    private val model: OmniPrivacyCenterViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        model.refreshNotificationState()
    }

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        var confirmRevoke by remember { mutableStateOf(false) }
        var confirmForget by remember { mutableStateOf(false) }
        OmniPrivacyCenterScreen(
            state = state,
            onBack = { finish() },
            onRefresh = model::refresh,
            onRevokeAll = { confirmRevoke = true },
            onForgetAll = { confirmForget = true },
            onRevokePanel = model::revokePanel,
            onForgetPanel = model::forgetPanelLocal,
            onBackup = { startActivity(Intent(this, BackupActivity::class.java)) },
            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            onNotificationsChanged = model::setNotificationsEnabled,
            onNotificationSettings = {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            },
        )
        if (confirmRevoke) {
            AlertDialog(
                onDismissRequest = { confirmRevoke = false },
                title = { Text(stringResource(R.string.omni_privacy_revoke_all)) },
                text = { Text(stringResource(R.string.omni_privacy_revoke_confirm)) },
                confirmButton = { TextButton(onClick = { confirmRevoke = false; model.revokeAll() }) { Text(stringResource(R.string.omni_privacy_revoke_all)) } },
                dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text(stringResource(R.string.omni_privacy_forget_all)) },
                text = { Text(stringResource(R.string.omni_privacy_forget_confirm)) },
                confirmButton = { TextButton(onClick = { confirmForget = false; model.forgetAllLocal() }) { Text(stringResource(R.string.omni_privacy_forget_all)) } },
                dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }
}

@Composable
fun OmniPrivacyCenterScreen(
    state: OmniPrivacyCenterUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRevokeAll: () -> Unit,
    onForgetAll: () -> Unit,
    onRevokePanel: (String) -> Unit,
    onForgetPanel: (String) -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
) {
    val activeCount = omniActivePairedPanelIds(state.scopes).size
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_privacy_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_privacy_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.omni_privacy_inventory), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.omni_privacy_counts, state.panels.size, activeCount))
                        Text(stringResource(R.string.omni_privacy_backup_note), style = MaterialTheme.typography.bodySmall)
                        SettingsSwitchItem(
                            title = stringResource(R.string.omni_privacy_notifications),
                            summary = stringResource(R.string.omni_privacy_notifications_note),
                            checked = state.notificationsEnabled,
                            onCheckedChange = onNotificationsChanged,
                        )
                        if (!state.notificationsSystemEnabled) {
                            Text(stringResource(R.string.omni_privacy_notifications_system_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = onNotificationSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_privacy_notification_settings)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.title_configuration_backup_restore)) }
                            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.title_settings)) }
                        }
                        Button(onClick = onRevokeAll, enabled = activeCount > 0 && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_privacy_revoke_all)) }
                        OutlinedButton(onClick = onForgetAll, enabled = state.panels.isNotEmpty() && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_privacy_forget_all)) }
                        OutlinedButton(onClick = onRefresh, enabled = !state.busy && !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_refresh)) }
                        state.message?.let { Text(privacyMessageText(it), color = if (it == OmniPrivacyMessage.REVOKED || it == OmniPrivacyMessage.LOCAL_FORGOTTEN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        if (state.busy) CircularProgressIndicator()
                    }
                }
            }
            if (state.loading && state.panels.isEmpty()) {
                item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
            } else if (state.panels.isEmpty()) {
                item { Text(stringResource(R.string.omni_privacy_empty), modifier = Modifier.padding(16.dp)) }
            }
            items(state.panels, key = { it.id }) { panel ->
                val scopes = state.scopes[panel.id].orEmpty()
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(panel.name, style = MaterialTheme.typography.titleMedium)
                        Text(panel.baseUrl, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (scopes.isEmpty()) stringResource(R.string.omni_privacy_no_active_pairing)
                            else stringResource(R.string.omni_privacy_scopes, scopes.sorted().joinToString()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRevokePanel(panel.id) }, enabled = scopes.isNotEmpty() && !state.busy) { Text(stringResource(R.string.omni_hub_revoke)) }
                            TextButton(onClick = { onForgetPanel(panel.id) }, enabled = !state.busy) { Text(stringResource(R.string.omni_privacy_forget_local)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun privacyMessageText(message: OmniPrivacyMessage): String = stringResource(
    when (message) {
        OmniPrivacyMessage.LOAD_FAILED -> R.string.omni_privacy_load_failed
        OmniPrivacyMessage.NO_PAIRINGS -> R.string.omni_privacy_no_pairings
        OmniPrivacyMessage.REVOKED -> R.string.omni_privacy_revoked
        OmniPrivacyMessage.PARTIAL -> R.string.omni_privacy_partial
        OmniPrivacyMessage.LOCAL_FORGOTTEN -> R.string.omni_privacy_local_forgotten
        OmniPrivacyMessage.FORGET_FAILED -> R.string.omni_privacy_forget_failed
        OmniPrivacyMessage.PREFERENCE_FAILED -> R.string.omni_privacy_preference_failed
    },
)
