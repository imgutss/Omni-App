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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.v2ray.ang.ui.compose.OmniTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class OmniDnsLabActivity : BaseComponentActivity() {
    private val model: OmniDnsLabViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        OmniDnsLabScreen(
            state = state,
            onBack = { finish() },
            onHost = model::updateHost,
            onCustomUrl = model::updateCustomUrl,
            onType = model::selectType,
            onProvider = model::toggleProvider,
            onRun = model::runQueries,
        )
    }
}

@Composable
fun OmniDnsLabScreen(
    state: OmniDnsLabUiState,
    onBack: () -> Unit,
    onHost: (String) -> Unit,
    onCustomUrl: (String) -> Unit,
    onType: (OmniDnsRecordType) -> Unit,
    onProvider: (OmniDnsProvider) -> Unit,
    onRun: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_dns_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_dns_subtitle), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = state.hostInput, onValueChange = onHost, enabled = !state.running, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_dns_host)) })
                        Text(stringResource(R.string.omni_dns_record_type), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OmniDnsRecordType.entries.forEach { type ->
                                FilterChip(selected = state.recordType == type, onClick = { onType(type) }, enabled = !state.running, label = { Text(type.name) })
                            }
                        }
                        Text(stringResource(R.string.omni_dns_providers), style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(OmniDnsProvider.entries) { provider ->
                                FilterChip(selected = provider in state.selectedProviders, onClick = { onProvider(provider) }, enabled = !state.running, label = { Text(dnsProviderText(provider)) })
                            }
                        }
                        if (OmniDnsProvider.CUSTOM in state.selectedProviders) {
                            OutlinedTextField(value = state.customUrlInput, onValueChange = onCustomUrl, enabled = !state.running, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_dns_custom_url)) })
                        }
                        Text(stringResource(R.string.omni_dns_privacy_note), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onRun, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                            if (state.running) CircularProgressIndicator() else Text(stringResource(R.string.omni_dns_run))
                        }
                        state.activeProvider?.let { Text(stringResource(R.string.omni_dns_active, dnsProviderText(it)), style = MaterialTheme.typography.bodySmall) }
                        state.message?.let { Text(dnsMessageText(it), color = if (it == OmniDnsMessage.COMPLETE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    }
                }
            }
            items(OmniDnsProvider.entries.filter { it in state.results }, key = { it.name }) { provider ->
                state.results[provider]?.let { result -> OmniDnsResultCard(result) }
            }
        }
    }
}

@Composable
private fun OmniDnsResultCard(result: OmniDnsResult) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(dnsProviderText(result.provider), style = MaterialTheme.typography.titleMedium)
            Text(dnsStatusText(result.status), color = if (result.status == OmniDnsStatus.PASS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.omni_dns_elapsed, result.elapsedMs), style = MaterialTheme.typography.bodySmall)
            result.httpStatus?.let { Text(stringResource(R.string.omni_dns_http_status, it), style = MaterialTheme.typography.bodySmall) }
            result.responseStatus?.let { Text(stringResource(R.string.omni_dns_response_status, it), style = MaterialTheme.typography.bodySmall) }
            result.error?.let { Text(stringResource(R.string.omni_dns_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (result.answers.isEmpty() && result.status == OmniDnsStatus.PASS) Text(stringResource(R.string.omni_dns_no_answers), style = MaterialTheme.typography.bodySmall)
            result.answers.forEach { answer ->
                Text(stringResource(R.string.omni_dns_answer, answer.name.ifBlank { stringResource(R.string.omni_dns_no_name) }, answer.type, answer.ttl?.toString() ?: stringResource(R.string.omni_dns_no_ttl), answer.data), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun dnsProviderText(provider: OmniDnsProvider): String = stringResource(
    when (provider) {
        OmniDnsProvider.SYSTEM -> R.string.omni_dns_system
        OmniDnsProvider.GOOGLE -> R.string.omni_dns_google
        OmniDnsProvider.CLOUDFLARE -> R.string.omni_dns_cloudflare
        OmniDnsProvider.CUSTOM -> R.string.omni_dns_custom
    },
)

@Composable
private fun dnsStatusText(status: OmniDnsStatus): String = stringResource(
    when (status) {
        OmniDnsStatus.PASS -> R.string.omni_dns_pass
        OmniDnsStatus.NXDOMAIN -> R.string.omni_dns_nxdomain
        OmniDnsStatus.FAIL -> R.string.omni_dns_fail
    },
)

@Composable
private fun dnsMessageText(message: OmniDnsMessage): String = stringResource(
    when (message) {
        OmniDnsMessage.INVALID_INPUT -> R.string.omni_dns_invalid_input
        OmniDnsMessage.NO_PROVIDER -> R.string.omni_dns_no_provider
        OmniDnsMessage.COMPLETE -> R.string.omni_dns_complete
        OmniDnsMessage.PARTIAL_FAILURE -> R.string.omni_dns_partial
    },
)
