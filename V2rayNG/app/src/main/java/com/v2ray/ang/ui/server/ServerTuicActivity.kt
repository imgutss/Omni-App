package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

class ServerTuicActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.TUIC

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply { configType = EConfigType.TUIC }

        ServerEditorScaffold(title = serverConfigType.toString(), onSaveClick = { saveServer(uiState) }) {
            CommonBasicFields(uiState)
            FormTextField(stringResource(R.string.server_lab_tuic_uuid), uiState.username, { uiState.username = it })
            FormTextField(stringResource(R.string.server_lab_id3), uiState.password, { uiState.password = it })
            FormTextField(stringResource(R.string.server_lab_sni), uiState.sni, { uiState.sni = it })
            FormTextField(stringResource(R.string.server_lab_alpn), uiState.alpn, { uiState.alpn = it })
            SettingsSwitchItem(
                title = stringResource(R.string.server_lab_allow_insecure),
                checked = uiState.allowInsecure,
                onCheckedChange = { uiState.allowInsecure = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_tuic_parameters),
                uiState.tuicParameters,
                { uiState.tuicParameters = it },
                placeholder = "{\"congestion_control\":\"bbr\",\"udp_relay_mode\":\"native\"}"
            )
            CommonTargetStrategyField(uiState)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.username.isNullOrBlank() || config.password.isNullOrBlank()) return false
        config.security = AppConfig.TLS
        if (config.alpn.isNullOrBlank()) config.alpn = "h3"
        return true
    }
}
