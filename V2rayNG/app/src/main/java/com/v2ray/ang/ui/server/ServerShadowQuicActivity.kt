package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.FormTextField

class ServerShadowQuicActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.SHADOWQUIC

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply { configType = EConfigType.SHADOWQUIC }

        ServerEditorScaffold(title = serverConfigType.toString(), onSaveClick = { saveServer(uiState) }) {
            CommonBasicFields(uiState)
            FormTextField(stringResource(R.string.server_lab_username), uiState.username, { uiState.username = it })
            FormTextField(stringResource(R.string.server_lab_id3), uiState.password, { uiState.password = it })
            FormTextField(stringResource(R.string.server_lab_shadowquic_server_name), uiState.sni, { uiState.sni = it })
            FormTextField(stringResource(R.string.server_lab_alpn), uiState.alpn, { uiState.alpn = it })
            FormTextField(
                stringResource(R.string.server_lab_shadowquic_parameters),
                uiState.shadowquicParameters,
                { uiState.shadowquicParameters = it },
                placeholder = "{\"congestion_control\":\"bbr\",\"over_stream\":false,\"keep_alive_interval\":10000}"
            )
            CommonTargetStrategyField(uiState)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.username.isNullOrBlank() || config.password.isNullOrBlank()) return false
        if (config.alpn.isNullOrBlank()) config.alpn = "h3"
        return true
    }
}
