package com.v2ray.ang.omni

import com.v2ray.ang.ui.compose.OmniDoctorHero
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

class OmniDoctorActivity : BaseComponentActivity() {
    companion object {
        const val EXTRA_PANEL_ID = "omni_panel_id"
    }

    @Composable
    override fun ScreenContent() {
        OmniDoctorScreen(
            initialPanelId = intent.getStringExtra(EXTRA_PANEL_ID),
            onBack = { finish() },
        )
    }
}

@Composable
fun OmniDoctorScreen(initialPanelId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = remember { OmniHubRepository(context) }
    val scope = rememberCoroutineScope()
    var panels by remember { mutableStateOf<List<OmniPanel>>(emptyList()) }
    var selectedPanelId by remember { mutableStateOf(initialPanelId) }
    var stages by remember { mutableStateOf<List<OmniDoctorStage>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var panelMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            panels = hub.panels()
            if (selectedPanelId == null) selectedPanelId = panels.firstOrNull()?.id
        } catch (error: Exception) {
            message = error.message
        }
    }

    val selectedPanel = panels.firstOrNull { it.id == selectedPanelId }

    fun localizedTitle(id: String): String = context.getString(
        when (id) {
            "config" -> R.string.omni_doctor_config
            "dns" -> R.string.omni_doctor_dns
            "tcp" -> R.string.omni_doctor_tcp
            "tls" -> R.string.omni_doctor_tls
            "http" -> R.string.omni_doctor_http
            "websocket" -> R.string.omni_doctor_websocket
            "core" -> R.string.omni_doctor_core
            "tun" -> R.string.omni_doctor_tun
            else -> R.string.omni_doctor_exit
        }
    )

    fun append(id: String, status: OmniDoctorStatus, summary: String, raw: String = "") {
        stages = stages + OmniDoctorStage(id, localizedTitle(id), status, summary, raw)
    }

    fun appendProbe(id: String, outcome: OmniProbeOutcome) {
        val summary = when {
            outcome.status == OmniDoctorStatus.FAIL -> context.getString(R.string.omni_doctor_network_failed)
            outcome.status == OmniDoctorStatus.SKIPPED -> outcome.raw
            outcome.status == OmniDoctorStatus.WARN && id != "http" -> outcome.raw
            id == "http" && outcome.status == OmniDoctorStatus.WARN -> context.getString(R.string.omni_doctor_http_warn)
            id == "dns" -> context.getString(R.string.omni_doctor_dns_pass, outcome.value)
            id == "tcp" -> context.getString(R.string.omni_doctor_tcp_pass, outcome.value)
            id == "tls" -> context.getString(R.string.omni_doctor_tls_pass, outcome.value)
            id == "http" -> context.getString(R.string.omni_doctor_http_pass, outcome.value)
            else -> outcome.raw
        }
        append(id, outcome.status, summary, outcome.raw)
    }

    fun runDoctor() {
        if (running) return
        running = true
        stages = emptyList()
        message = null
        scope.launch {
            try {
                val guid = MmkvManager.getSelectServer()
                val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
                if (guid.isNullOrBlank() || profile == null) {
                    append("config", OmniDoctorStatus.FAIL, context.getString(R.string.omni_doctor_no_config), "selectedGuid=${guid ?: "null"}")
                } else {
                    append(
                        "config",
                        OmniDoctorStatus.PASS,
                        context.getString(R.string.omni_doctor_config_found, profile.configType.name),
                        "type=${profile.configType.name}; network=${profile.network ?: "default"}; security=${profile.security ?: "default"}",
                    )
                }

                if (selectedPanel == null) {
                    listOf("dns", "tcp", "tls", "http").forEach { id ->
                        append(id, OmniDoctorStatus.SKIPPED, context.getString(R.string.omni_doctor_no_panel), "panel=null")
                    }
                } else {
                    appendProbe("dns", OmniDoctorProbe.dns(selectedPanel.baseUrl))
                    appendProbe("tcp", OmniDoctorProbe.tcp(selectedPanel.baseUrl))
                    appendProbe("tls", OmniDoctorProbe.tls(selectedPanel.baseUrl))
                    appendProbe("http", OmniDoctorProbe.http(selectedPanel.baseUrl))
                }

                append(
                    "websocket",
                    OmniDoctorStatus.SKIPPED,
                    context.getString(R.string.omni_doctor_websocket_skipped),
                    "No panel WebSocket URL was advertised; no synthetic WebSocket success is reported",
                )

                if (guid.isNullOrBlank() || profile == null) {
                    append("core", OmniDoctorStatus.SKIPPED, context.getString(R.string.omni_doctor_no_config), "core test not started")
                } else {
                    try {
                        val result = OmniDoctorProbe.realHttp(context, guid)
                        when {
                            result.delayMillis > 0L -> append(
                                "core",
                                OmniDoctorStatus.PASS,
                                context.getString(
                                    R.string.omni_doctor_core_success,
                                    result.delayMillis,
                                    result.successfulSamples,
                                    result.sampleCount,
                                    result.jitterMillis,
                                ),
                                "kind=${result.kind}; median=${result.delayMillis} ms; success=${result.successfulSamples}/${result.sampleCount}; jitter=${result.jitterMillis} ms",
                            )
                            result.delayMillis == 0L -> append("core", OmniDoctorStatus.WARN, context.getString(R.string.omni_doctor_core_untested), "delay=0 (untested)")
                            else -> append(
                                "core",
                                OmniDoctorStatus.FAIL,
                                context.getString(R.string.omni_doctor_core_failed),
                                "kind=${result.kind}; delay=${result.delayMillis}; success=${result.successfulSamples}/${result.sampleCount}",
                            )
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        append("core", OmniDoctorStatus.FAIL, context.getString(R.string.omni_doctor_core_failed), "CoreTestService timeout after 90 s")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        append("core", OmniDoctorStatus.FAIL, context.getString(R.string.omni_doctor_core_failed), "${error.javaClass.simpleName}: ${error.message ?: "no detail"}")
                    }
                }

                append("tun", OmniDoctorStatus.SKIPPED, context.getString(R.string.omni_doctor_tun_skipped), "TUN ownership is not inferred from a probe")

                val exit = OmniDoctorProbe.exitIp()
                if (exit.status == OmniDoctorStatus.PASS) {
                    val parts = exit.value.split("|", limit = 2)
                    append(
                        "exit",
                        OmniDoctorStatus.PASS,
                        context.getString(R.string.omni_doctor_exit_success, parts[0], parts.getOrElse(1) { "unknown" }),
                        exit.raw,
                    )
                } else {
                    append(
                        "exit",
                        if (exit.status == OmniDoctorStatus.SKIPPED) OmniDoctorStatus.SKIPPED else OmniDoctorStatus.FAIL,
                        context.getString(if (exit.status == OmniDoctorStatus.SKIPPED) R.string.omni_doctor_exit_not_run else R.string.omni_doctor_exit_failed),
                        exit.raw,
                    )
                }
                message = context.getString(R.string.omni_doctor_done)
            } finally {
                running = false
            }
        }
    }

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.title_omni_doctor), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "reference_diagnostic_ring") { OmniDoctorHero(stages, running) }
            item {
                Text(stringResource(R.string.omni_doctor_subtitle), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.omni_doctor_http_explanation), style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text(stringResource(R.string.omni_doctor_selected_panel), fontWeight = FontWeight.Bold)
                Box {
                    OutlinedButton(onClick = { panelMenu = true }, enabled = !running && panels.isNotEmpty()) {
                        Text(selectedPanel?.name ?: stringResource(R.string.omni_doctor_no_panel))
                    }
                    DropdownMenu(expanded = panelMenu, onDismissRequest = { panelMenu = false }) {
                        panels.forEach { panel ->
                            DropdownMenuItem(
                                text = { Text(panel.name) },
                                onClick = { selectedPanelId = panel.id; panelMenu = false },
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { runDoctor() }, enabled = !running) {
                        Text(stringResource(if (running) R.string.omni_doctor_running else R.string.omni_doctor_run))
                    }
                    if (running) CircularProgressIndicator()
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.omni_doctor_actions), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, OmniHubActivity::class.java)) },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.title_omni_panel)) }
                }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            items(stages, key = { it.id }) { stage -> OmniDoctorStageCard(stage) }
        }
    }
}

@Composable
private fun OmniDoctorStageCard(stage: OmniDoctorStage) {
    val color = when (stage.status) {
        OmniDoctorStatus.PASS -> Color(0xFF16855B)
        OmniDoctorStatus.FAIL -> MaterialTheme.colorScheme.error
        OmniDoctorStatus.WARN -> Color(0xFF9A6700)
        OmniDoctorStatus.SKIPPED, OmniDoctorStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = stringResource(
        when (stage.status) {
            OmniDoctorStatus.PASS -> R.string.omni_doctor_pass
            OmniDoctorStatus.FAIL -> R.string.omni_doctor_fail
            OmniDoctorStatus.WARN -> R.string.omni_doctor_warn
            OmniDoctorStatus.SKIPPED, OmniDoctorStatus.RUNNING -> R.string.omni_doctor_skip
        }
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stage.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(statusText, color = color, fontWeight = FontWeight.Bold)
            }
            Text(stage.summary)
            if (stage.raw.isNotBlank()) {
                Text(stringResource(R.string.omni_doctor_raw), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stage.raw, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
