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
import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormTextField

/**
 * Editor for a GUTS profile: the doors it posts to, the key they and the exit node check, and the
 * Google edge the client dials. Everything the client needs is here, so a profile pasted as a
 * `guts://` link and one typed by hand are the same profile.
 */
class ServerGutsActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.GUTS

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.fromProfileItem(initialConfig)
        }.apply { configType = serverConfigType }

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
            FormTextField(
                label = stringResource(R.string.guts_lab_doors),
                value = uiState.gutsDoors,
                onValueChange = {
                    uiState.gutsDoors = it
                    uiState.isGutsDoorsError = false
                },
                placeholder = stringResource(R.string.guts_hint_doors),
                supportingText = stringResource(R.string.guts_doors_support),
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
                supportingText = stringResource(R.string.guts_key_support),
                isError = uiState.isGutsKeyError,
            )
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
            Text(
                text = stringResource(R.string.guts_engine_notice),
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
                    GutsCore.Problem.EDGE_INVALID -> R.string.guts_error_edge
                    GutsCore.Problem.SNI_INVALID -> R.string.guts_error_sni
                }
            )
            return false
        }
        return super.validateCommonConfig(state, config)
    }
}
