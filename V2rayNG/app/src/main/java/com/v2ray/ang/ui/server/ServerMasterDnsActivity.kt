package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.MasterDnsFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

/** Editor for the official MasterDnsVPN client document and its resolver file. */
class ServerMasterDnsActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.MASTERDNS

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.fromProfileItem(initialConfig)
        }.apply { configType = serverConfigType }

        ServerEditorScaffold(
            title = if (uiState.masterDnsEngine == MasterDnsFmt.ENGINE_COTTENDNS) "CottenDNS" else stringResource(R.string.masterdns_title),
            onSaveClick = { saveServer(uiState) },
        ) {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = uiState.remarks,
                onValueChange = { uiState.remarks = it },
                isError = uiState.isRemarksError,
            )
            FormDropdownField(
                label = stringResource(R.string.dnstt_lab_engine),
                value = uiState.masterDnsEngine,
                options = listOf(MasterDnsFmt.ENGINE_MASTERDNS, MasterDnsFmt.ENGINE_COTTENDNS),
                onValueChange = { uiState.masterDnsEngine = it },
            )
            FormTextField(
                label = stringResource(R.string.masterdns_lab_config),
                value = uiState.masterDnsConfig,
                onValueChange = {
                    uiState.masterDnsConfig = it
                    uiState.isMasterDnsConfigError = false
                },
                placeholder = stringResource(R.string.masterdns_hint_config),
                supportingText = stringResource(R.string.masterdns_config_support),
                maxLines = 14,
                isError = uiState.isMasterDnsConfigError,
            )
            FormTextField(
                label = stringResource(R.string.masterdns_lab_resolvers),
                value = uiState.masterDnsResolvers,
                onValueChange = {
                    uiState.masterDnsResolvers = it
                    uiState.isMasterDnsResolversError = false
                },
                placeholder = stringResource(R.string.masterdns_hint_resolvers),
                supportingText = stringResource(R.string.masterdns_resolvers_support),
                maxLines = 8,
                isError = uiState.isMasterDnsResolversError,
            )
            Text(
                text = stringResource(if (uiState.masterDnsEngine == MasterDnsFmt.ENGINE_COTTENDNS) R.string.cottendns_engine_notice else R.string.masterdns_engine_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    override fun validateBasicConfig(state: ServerUiState): Boolean {
        state.isRemarksError = state.remarks.isBlank()
        state.isAddressError = false
        state.isPortError = false
        return !state.isRemarksError
    }

    override fun validateCommonConfig(state: ServerUiState, config: ProfileItem): Boolean {
        val problem = MasterDnsFmt.normalize(config)
        state.isMasterDnsConfigError = problem != null && problem != MasterDnsFmt.Problem.RESOLVERS_INVALID
        state.isMasterDnsResolversError = !MasterDnsFmt.validResolvers(state.masterDnsResolvers)
        if (problem != null) {
            toast(
                when (problem) {
                    MasterDnsFmt.Problem.CONFIG_EMPTY -> R.string.masterdns_error_config_empty
                    MasterDnsFmt.Problem.CONFIG_TOO_LARGE -> R.string.masterdns_error_config_large
                    MasterDnsFmt.Problem.CONFIG_MALFORMED -> R.string.masterdns_error_config
                    MasterDnsFmt.Problem.DOMAINS_MISSING -> R.string.masterdns_error_domains
                    MasterDnsFmt.Problem.KEY_MISSING -> R.string.masterdns_error_key
                    MasterDnsFmt.Problem.RESOLVERS_INVALID -> R.string.masterdns_error_resolvers
                }
            )
            return false
        }
        return super.validateCommonConfig(state, config)
    }
}
