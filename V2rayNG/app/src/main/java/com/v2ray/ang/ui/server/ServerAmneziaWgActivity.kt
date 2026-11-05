package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.FormTextField

class ServerAmneziaWgActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.AMNEZIAWG

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply { configType = EConfigType.AMNEZIAWG }

        ServerEditorScaffold(title = serverConfigType.toString(), onSaveClick = { saveServer(uiState) }) {
            CommonBasicFields(uiState)
            FormTextField(stringResource(R.string.server_lab_secret_key), uiState.secretKey, { uiState.secretKey = it })
            FormTextField(stringResource(R.string.server_lab_public_key), uiState.publicKey, { uiState.publicKey = it })
            FormTextField(stringResource(R.string.server_lab_preshared_key), uiState.preSharedKey, { uiState.preSharedKey = it })
            FormTextField(stringResource(R.string.server_lab_local_address), uiState.localAddress, { uiState.localAddress = it })
            FormTextField(stringResource(R.string.server_lab_reserved), uiState.reserved, { uiState.reserved = it })
            FormTextField(stringResource(R.string.server_lab_local_mtu), uiState.mtu, { uiState.mtu = it }, keyboardType = KeyboardType.Number)
            FormTextField(stringResource(R.string.server_lab_remote_dns), uiState.remoteDNS, { uiState.remoteDNS = it })
            FormTextField(
                stringResource(R.string.server_lab_awg_parameters),
                uiState.awgParameters,
                { uiState.awgParameters = it },
                placeholder = "{\"jc\":4,\"jmin\":40,\"jmax\":70,\"s1\":0,\"s2\":0,\"h1\":1,\"h2\":2,\"h3\":3,\"h4\":4}"
            )
            CommonTargetStrategyField(uiState)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        return !config.secretKey.isNullOrBlank() && !config.publicKey.isNullOrBlank()
    }
}
