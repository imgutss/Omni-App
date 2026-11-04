package com.v2ray.ang.ui.server

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.GutsCa
import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.core.GutsEdgeScanner
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for a GUTS profile: its mode, the doors it posts to, the key they check, and the Google
 * edge the client dials. Fetch mode also creates and offers the user's local CA from here.
 * Everything the client needs is here, so a profile pasted as a `guts://` link and one typed by
 * hand are the same profile.
 */
class ServerGutsActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.GUTS

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.fromProfileItem(initialConfig)
        }.apply { configType = serverConfigType }

        val scope = rememberCoroutineScope()
        var scanRunning by remember { mutableStateOf(false) }
        // The evidence of the last scan, kept on screen: which pairs this network carried and how
        // fast, or what it answered instead — so a failed scan says why, not just that it failed.
        var scanEvidence by rememberSaveable { mutableStateOf("") }
        var caRunning by remember { mutableStateOf(false) }
        var caFingerprint by remember { mutableStateOf(GutsCa.fingerprint(this)) }

        ServerEditorScaffold(
            title = stringResource(R.string.guts_title),
            onSaveClick = { saveServer(uiState) },
        ) {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = uiState.remarks,
                onValueChange = { uiState.remarks = it },
                isError = uiState.isRemarksError,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.guts_fetch_mode),
                summary = stringResource(R.string.guts_fetch_mode_support),
                checked = uiState.gutsFetchMode,
                onCheckedChange = { uiState.gutsFetchMode = it },
            )
            FormTextField(
                label = stringResource(R.string.guts_lab_doors),
                value = uiState.gutsDoors,
                onValueChange = {
                    uiState.gutsDoors = it
                    uiState.isGutsDoorsError = false
                },
                placeholder = stringResource(R.string.guts_hint_doors),
                supportingText = stringResource(
                    if (uiState.gutsFetchMode) R.string.guts_doors_support_fetch else R.string.guts_doors_support
                ),
                maxLines = 6,
                isError = uiState.isGutsDoorsError,
            )
            FormTextField(
                label = stringResource(R.string.guts_lab_key),
                value = uiState.gutsKey,
                onValueChange = {
                    uiState.gutsKey = it
                    uiState.isGutsKeyError = false
                },
                placeholder = stringResource(R.string.guts_hint_key),
                supportingText = stringResource(
                    if (uiState.gutsFetchMode) R.string.guts_key_support_fetch else R.string.guts_key_support
                ),
                isError = uiState.isGutsKeyError,
            )
            if (uiState.gutsFetchMode) {
                TextButton(
                    enabled = !caRunning,
                    onClick = {
                        caRunning = true
                        scope.launch {
                            try {
                                val material = GutsCa.ensure(this@ServerGutsActivity)
                                caFingerprint = material.fingerprint
                                val saved = withContext(Dispatchers.IO) {
                                    GutsCa.saveToDownloads(
                                        this@ServerGutsActivity,
                                        material.files.cert,
                                    )
                                }
                                val openedInstaller = runCatching {
                                    startActivity(GutsCa.installIntent(material.files.cert))
                                    true
                                }.getOrDefault(false)
                                if (!openedInstaller) {
                                    runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                }
                                this@ServerGutsActivity.toast(
                                    getString(R.string.guts_fetch_ca_saved, saved),
                                    long = true,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                this@ServerGutsActivity.toast(
                                    getString(
                                        R.string.guts_fetch_ca_failed,
                                        error.message ?: error.javaClass.simpleName,
                                    ),
                                    long = true,
                                )
                            } finally {
                                caRunning = false
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (caRunning) R.string.guts_fetch_install_running else R.string.guts_fetch_install_ca
                        )
                    )
                }
                Text(
                    text = caFingerprint?.let {
                        stringResource(R.string.guts_fetch_ca_ready, it)
                    } ?: stringResource(R.string.guts_fetch_ca_missing),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            FormTextField(
                label = stringResource(R.string.guts_lab_google_ip),
                value = uiState.gutsGoogleIp,
                onValueChange = {
                    uiState.gutsGoogleIp = it
                    uiState.isGutsGoogleIpError = false
                },
                placeholder = GutsCore.AUTO_EDGE,
                supportingText = stringResource(R.string.guts_google_ip_support),
                isError = uiState.isGutsGoogleIpError,
            )
            FormTextField(
                label = stringResource(R.string.guts_lab_sni),
                value = uiState.gutsSni,
                onValueChange = {
                    uiState.gutsSni = it
                    uiState.isGutsSniError = false
                },
                placeholder = GutsCore.DEFAULT_SNI,
                supportingText = stringResource(R.string.guts_sni_support),
                isError = uiState.isGutsSniError,
            )
            // The edge address and the Google name are properties of the network, not of the
            // profile: the client itself says which ones this network still answers, so the fields
            // above can be filled from an answer instead of from a guess.
            TextButton(
                enabled = !scanRunning,
                onClick = {
                    scanRunning = true
                    scope.launch {
                        val found = GutsEdgeScanner.scan(
                            context = this@ServerGutsActivity,
                            googleIp = uiState.gutsGoogleIp,
                            sni = uiState.gutsSni,
                        )
                        scanRunning = false
                        scanEvidence = if (found.answered) {
                            val working = found.routes.filter { it.fronted }.take(8)
                                .joinToString("\n") { "${it.ip}  •  ${it.sni}  •  ${it.rttMs} ms" }
                            getString(R.string.guts_scan_routes_found) + "\n" + working
                        } else {
                            val refused = found.routes.filter { !it.error.isNullOrEmpty() }
                                .distinctBy { it.error }.take(5)
                                .joinToString("\n") { "${it.ip}  •  ${it.sni} — ${it.error}" }
                            if (refused.isEmpty()) "" else getString(R.string.guts_scan_routes_none) + "\n" + refused
                        }
                        if (!found.answered) {
                            this@ServerGutsActivity.toast(R.string.guts_scan_none)
                            return@launch
                        }
                        found.bestEdge?.let {
                            uiState.gutsGoogleIp = it
                            uiState.isGutsGoogleIpError = false
                        }
                        found.bestSni?.let {
                            uiState.gutsSni = it
                            uiState.isGutsSniError = false
                        }
                        this@ServerGutsActivity.toast(
                            getString(
                                R.string.guts_scan_done,
                                found.bestEdge ?: getString(R.string.guts_scan_unchanged),
                                found.bestSni ?: getString(R.string.guts_scan_unchanged),
                            )
                        )
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        if (scanRunning) R.string.guts_scan_running else R.string.guts_scan_edge
                    )
                )
            }
            if (scanEvidence.isNotEmpty()) {
                Text(
                    text = scanEvidence,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                text = stringResource(
                    if (uiState.gutsFetchMode) R.string.guts_fetch_notice else R.string.guts_engine_notice
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    override fun validateBasicConfig(state: ServerUiState): Boolean {
        state.isRemarksError = state.remarks.isBlank()
        // The door's host is the profile's address, and the client keeps its own listener.
        state.isAddressError = false
        state.isPortError = false
        return !state.isRemarksError
    }

    override fun validateCommonConfig(state: ServerUiState, config: ProfileItem): Boolean {
        val problem = GutsCore.normalize(config)
        state.isGutsDoorsError = problem == GutsCore.Problem.DOOR_MISSING || problem == GutsCore.Problem.DOOR_INVALID
        state.isGutsKeyError = problem == GutsCore.Problem.KEY_MISSING || problem == GutsCore.Problem.KEY_INVALID
        state.isGutsGoogleIpError = problem == GutsCore.Problem.EDGE_INVALID
        state.isGutsSniError = problem == GutsCore.Problem.SNI_INVALID
        if (problem != null) {
            toast(
                when (problem) {
                    GutsCore.Problem.DOOR_MISSING -> R.string.guts_error_door_missing
                    GutsCore.Problem.DOOR_INVALID -> R.string.guts_error_door
                    GutsCore.Problem.KEY_MISSING -> R.string.guts_error_key_missing
                    GutsCore.Problem.KEY_INVALID -> R.string.guts_error_key
                    GutsCore.Problem.MODE_INVALID -> R.string.guts_error_mode
                    GutsCore.Problem.EDGE_INVALID -> R.string.guts_error_edge
                    GutsCore.Problem.SNI_INVALID -> R.string.guts_error_sni
                }
            )
            return false
        }
        return super.validateCommonConfig(state, config)
    }
}
