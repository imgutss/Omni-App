package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.FormTextField

class ServerNaiveActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.NAIVE
    @Composable
    override fun EditorContent() {
        val state = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply { configType = EConfigType.NAIVE }
        ServerEditorScaffold(title = "NaiveProxy", onSaveClick = { saveServer(state) }) {
            CommonBasicFields(state)
            FormTextField(stringResource(R.string.server_lab_username), state.username, { state.username = it })
            FormTextField(stringResource(R.string.server_lab_id3), state.password, { state.password = it })
            FormTextField(stringResource(R.string.server_lab_sni), state.sni, { state.sni = it })
            Text(stringResource(R.string.naive_protocol_notice))
        }
    }
    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        config.security = AppConfig.TLS
        config.insecure = false
        return !config.username.isNullOrBlank() && !config.password.isNullOrBlank()
    }
}
