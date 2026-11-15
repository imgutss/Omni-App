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
import androidx.compose.foundation.text.KeyboardOptions
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import com.v2ray.ang.ui.compose.OmniTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.routing.RoutingSettingActivity

class OmniRoutingStudioActivity : BaseComponentActivity() {
    private val model: OmniRoutingStudioViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        OmniRoutingStudioScreen(
            state = state,
            onBack = { finish() },
            onHost = model::updateHost,
            onPort = model::updatePort,
            onNetwork = model::updateNetwork,
            onProtocol = model::updateProtocol,
            onProcess = model::updateProcess,
            onSimulate = model::simulate,
            onRefresh = model::refresh,
            onEdit = { startActivity(Intent(this, RoutingSettingActivity::class.java)) },
        )
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

@Composable
fun OmniRoutingStudioScreen(
    state: OmniRoutingStudioUiState,
    onBack: () -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onNetwork: (String) -> Unit,
    onProtocol: (String) -> Unit,
    onProcess: (String) -> Unit,
    onSimulate: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_routing_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_routing_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.omni_routing_loaded, state.rules.size), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_routing_open_editor)) }
                        OutlinedTextField(value = state.hostInput, onValueChange = onHost, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_routing_host)) })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = state.portInput, onValueChange = onPort, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.omni_routing_port)) })
                            OutlinedTextField(value = state.networkInput, onValueChange = onNetwork, modifier = Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.omni_routing_network)) })
                        }
                        OutlinedTextField(value = state.protocolInput, onValueChange = onProtocol, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_routing_protocol)) })
                        OutlinedTextField(value = state.processInput, onValueChange = onProcess, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_routing_process)) })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSimulate, enabled = !state.simulating && !state.loading, modifier = Modifier.weight(1f)) {
                                if (state.simulating) CircularProgressIndicator() else Text(stringResource(R.string.omni_routing_simulate))
                            }
                            OutlinedButton(onClick = onRefresh, enabled = !state.simulating && !state.loading, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.omni_refresh)) }
                        }
                        state.message?.let { Text(routingMessageText(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            state.result?.let { result ->
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.omni_routing_result), style = MaterialTheme.typography.titleMedium)
                            Text(routingSimulationStatusText(result.status), color = if (result.status == OmniRoutingSimulationStatus.MATCHED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            result.outboundTag?.let { Text(stringResource(R.string.omni_routing_outbound, it)) }
                            if (result.status == OmniRoutingSimulationStatus.UNMATCHED) Text(stringResource(R.string.omni_routing_unmatched_note), style = MaterialTheme.typography.bodySmall)
                            if (result.status == OmniRoutingSimulationStatus.INDETERMINATE) Text(stringResource(R.string.omni_routing_indeterminate_note), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(result.traces) { trace ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(trace.remarks.ifBlank { trace.ruleId }, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.omni_routing_trace_outbound, trace.outboundTag.ifBlank { stringResource(R.string.omni_routing_no_outbound) }), style = MaterialTheme.typography.bodySmall)
                            Text(routingMatchText(trace.match), color = if (trace.match == OmniRouteMatch.MATCH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(routingReasonText(trace.reason), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun routingSimulationStatusText(status: OmniRoutingSimulationStatus): String = stringResource(
    when (status) {
        OmniRoutingSimulationStatus.MATCHED -> R.string.omni_routing_matched
        OmniRoutingSimulationStatus.UNMATCHED -> R.string.omni_routing_unmatched
        OmniRoutingSimulationStatus.INDETERMINATE -> R.string.omni_routing_indeterminate
        OmniRoutingSimulationStatus.INVALID -> R.string.omni_routing_invalid
    },
)

@Composable
private fun routingMatchText(match: OmniRouteMatch): String = stringResource(
    when (match) {
        OmniRouteMatch.MATCH -> R.string.omni_routing_trace_match
        OmniRouteMatch.NO_MATCH -> R.string.omni_routing_trace_no_match
        OmniRouteMatch.UNSUPPORTED -> R.string.omni_routing_trace_unsupported
    },
)

@Composable
private fun routingReasonText(reason: OmniRoutingReason): String = stringResource(
    when (reason) {
        OmniRoutingReason.MATCHED -> R.string.omni_routing_reason_matched
        OmniRoutingReason.DISABLED -> R.string.omni_routing_reason_disabled
        OmniRoutingReason.DOMAIN_MISMATCH -> R.string.omni_routing_reason_domain
        OmniRoutingReason.IP_MISMATCH -> R.string.omni_routing_reason_ip
        OmniRoutingReason.PORT_MISMATCH -> R.string.omni_routing_reason_port
        OmniRoutingReason.NETWORK_MISMATCH -> R.string.omni_routing_reason_network
        OmniRoutingReason.PROTOCOL_MISMATCH -> R.string.omni_routing_reason_protocol
        OmniRoutingReason.PROCESS_MISMATCH -> R.string.omni_routing_reason_process
        OmniRoutingReason.MISSING_PROTOCOL_CONTEXT -> R.string.omni_routing_reason_protocol_context
        OmniRoutingReason.MISSING_PROCESS_CONTEXT -> R.string.omni_routing_reason_process_context
        OmniRoutingReason.UNSUPPORTED_GEOSITE -> R.string.omni_routing_reason_geosite
        OmniRoutingReason.UNSUPPORTED_GEOIP -> R.string.omni_routing_reason_geoip
        OmniRoutingReason.UNSUPPORTED_REGEXP -> R.string.omni_routing_reason_regexp
        OmniRoutingReason.INVALID_RULE -> R.string.omni_routing_reason_invalid
        OmniRoutingReason.MISSING_OUTBOUND -> R.string.omni_routing_reason_outbound
    },
)

@Composable
private fun routingMessageText(message: OmniRoutingStudioMessage): String = stringResource(
    when (message) {
        OmniRoutingStudioMessage.LOAD_FAILED -> R.string.omni_routing_load_failed
        OmniRoutingStudioMessage.INVALID_INPUT -> R.string.omni_routing_invalid_input
    },
)
