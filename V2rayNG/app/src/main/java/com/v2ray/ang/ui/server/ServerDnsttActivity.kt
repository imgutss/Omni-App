package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.fmt.SoarProfileFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

/** Editor for the real DNS tunnel engines and their optional SSH/SOCKS layers. */
class ServerDnsttActivity : BaseServerActivity() {
    override val serverConfigType: EConfigType = EConfigType.DNSTT

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.fromProfileItem(initialConfig)
        }.apply { configType = serverConfigType }

        ServerEditorScaffold(
            title = when (uiState.dnsttEngine) {
                DnsttFmt.ENGINE_NOIZDNS -> "NoizDNS"
                DnsttFmt.ENGINE_VAYDNS -> "VayDNS"
                DnsttFmt.ENGINE_SLIPSTREAM -> "Slipstream"
                DnsttFmt.ENGINE_SOAR -> "Soar"
                else -> stringResource(R.string.dnstt_title)
            },
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
                value = uiState.dnsttEngine,
                options = listOf(DnsttFmt.ENGINE_OFFICIAL, DnsttFmt.ENGINE_DNSTT, DnsttFmt.ENGINE_NOIZDNS, DnsttFmt.ENGINE_VAYDNS, DnsttFmt.ENGINE_SLIPSTREAM, DnsttFmt.ENGINE_SOAR),
                onValueChange = { selected ->
                    uiState.dnsttEngine = selected
                    if (selected == DnsttFmt.ENGINE_OFFICIAL) {
                        if (uiState.dnsttTransport == "tcp") uiState.dnsttTransport = "udp"
                        uiState.dnsttNoiz = false
                        uiState.dnsttStealth = false
                        uiState.dnsttSshEnabled = false
                    }
                    if (selected == DnsttFmt.ENGINE_SLIPSTREAM) {
                        uiState.dnsttTransport = "udp"
                        uiState.dnsttNoiz = false
                        uiState.dnsttStealth = false
                    }
                    if (selected == DnsttFmt.ENGINE_SOAR) {
                        if (uiState.dnsttTransport !in setOf("udp", "tcp")) uiState.dnsttTransport = "udp"
                        uiState.dnsttNoiz = false
                        uiState.dnsttStealth = false
                        uiState.dnsttSshEnabled = false
                        uiState.dnsttSocksUsername = ""
                        uiState.dnsttSocksPassword = ""
                        uiState.dnsttMaxPayload = "0"
                        uiState.dnsttResolverMode = "fanout"
                        uiState.dnsttRrSpreadCount = "3"
                        uiState.dnsttAuthoritative = false
                        uiState.dnsttUtls = ""
                    }
                },
                supportingText = stringResource(R.string.dnstt_engine_support),
            )
            if (uiState.dnsttEngine != DnsttFmt.ENGINE_SOAR) {
            FormDropdownField(
                label = stringResource(R.string.dnstt_lab_transport),
                value = uiState.dnsttTransport,
                options = when (uiState.dnsttEngine) {
                    DnsttFmt.ENGINE_SLIPSTREAM -> listOf("udp")
                    DnsttFmt.ENGINE_OFFICIAL -> listOf("doh", "dot", "udp")
                    DnsttFmt.ENGINE_SOAR -> listOf("udp", "tcp")
                    else -> listOf("doh", "dot", "udp", "tcp")
                },
                onValueChange = { uiState.dnsttTransport = it },
                supportingText = stringResource(R.string.dnstt_transport_support),
            )
            }
            FormTextField(
                label = stringResource(R.string.dnstt_lab_resolver),
                value = uiState.dnsttResolver,
                onValueChange = {
                    uiState.dnsttResolver = it
                    uiState.isDnsttResolverError = false
                },
                placeholder = stringResource(R.string.dnstt_hint_resolver),
                supportingText = stringResource(R.string.dnstt_resolver_support),
                isError = uiState.isDnsttResolverError,
            )
            FormTextField(
                label = stringResource(R.string.dnstt_lab_domain),
                value = uiState.dnsttDomain,
                onValueChange = {
                    uiState.dnsttDomain = it
                    uiState.isDnsttDomainError = false
                },
                placeholder = stringResource(R.string.dnstt_hint_domain),
                supportingText = stringResource(R.string.dnstt_domain_support),
                isError = uiState.isDnsttDomainError,
            )
            if (uiState.dnsttEngine != DnsttFmt.ENGINE_SLIPSTREAM) {
            FormTextField(
                label = stringResource(R.string.dnstt_lab_public_key),
                value = uiState.dnsttPublicKey,
                onValueChange = {
                    uiState.dnsttPublicKey = it
                    uiState.isDnsttPublicKeyError = false
                },
                placeholder = stringResource(R.string.dnstt_hint_public_key),
                supportingText = stringResource(R.string.dnstt_public_key_support),
                isError = uiState.isDnsttPublicKeyError,
            )
            }
            if (uiState.dnsttEngine !in setOf(DnsttFmt.ENGINE_SLIPSTREAM, DnsttFmt.ENGINE_SOAR)) {
            FormTextField(
                label = stringResource(R.string.dnstt_lab_utls),
                value = uiState.dnsttUtls,
                onValueChange = {
                    uiState.dnsttUtls = it
                    uiState.isDnsttUtlsError = false
                },
                supportingText = stringResource(R.string.dnstt_utls_support),
                isError = uiState.isDnsttUtlsError,
            )
            }
            if (uiState.dnsttEngine != DnsttFmt.ENGINE_OFFICIAL) {
                if (uiState.dnsttEngine == DnsttFmt.ENGINE_NOIZDNS) {
                SettingsSwitchItem(
                    title = stringResource(R.string.dnstt_lab_noiz),
                    summary = stringResource(R.string.dnstt_noiz_support),
                    checked = uiState.dnsttNoiz,
                    onCheckedChange = {
                        uiState.dnsttNoiz = it
                        if (!it) uiState.dnsttStealth = false
                    },
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.dnstt_lab_stealth),
                    summary = stringResource(R.string.dnstt_stealth_support),
                    checked = uiState.dnsttStealth,
                    onCheckedChange = { uiState.dnsttStealth = it },
                    enabled = uiState.dnsttNoiz,
                )
                }
                if (uiState.dnsttEngine !in setOf(DnsttFmt.ENGINE_VAYDNS, DnsttFmt.ENGINE_SOAR)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.dnstt_lab_authoritative),
                        summary = stringResource(R.string.dnstt_authoritative_support),
                        checked = uiState.dnsttAuthoritative,
                        onCheckedChange = { checked ->
                        uiState.dnsttAuthoritative = checked
                        if (uiState.dnsttEngine == DnsttFmt.ENGINE_SLIPSTREAM) {
                            uiState.dnsttResolverAuthorities = uiState.dnsttResolver.split(',')
                                .filter { it.isNotBlank() }
                                .joinToString(",") { if (checked) "1" else "0" }
                        }
                    },
                    )
                }
                if (uiState.dnsttEngine !in setOf(DnsttFmt.ENGINE_SLIPSTREAM, DnsttFmt.ENGINE_SOAR)) {
                FormDropdownField(
                    label = stringResource(R.string.dnstt_lab_resolver_mode),
                    value = uiState.dnsttResolverMode,
                    options = listOf("fanout", "roundrobin"),
                    onValueChange = { uiState.dnsttResolverMode = it },
                )
                FormTextField(
                    label = stringResource(R.string.dnstt_lab_rr_spread),
                    value = uiState.dnsttRrSpreadCount,
                    onValueChange = { uiState.dnsttRrSpreadCount = it },
                    keyboardType = KeyboardType.Number,
                    supportingText = stringResource(R.string.dnstt_rr_spread_support),
                )
                FormTextField(
                    label = stringResource(R.string.dnstt_lab_max_payload),
                    value = uiState.dnsttMaxPayload,
                    onValueChange = { uiState.dnsttMaxPayload = it },
                    keyboardType = KeyboardType.Number,
                    supportingText = stringResource(R.string.dnstt_max_payload_support),
                )
                }
                if (!uiState.dnsttSshEnabled && uiState.dnsttEngine != DnsttFmt.ENGINE_SOAR) {
                    FormTextField(
                        label = stringResource(R.string.dnstt_lab_socks_username),
                        value = uiState.dnsttSocksUsername,
                        onValueChange = { uiState.dnsttSocksUsername = it },
                    )
                    FormTextField(
                        label = stringResource(R.string.dnstt_lab_socks_password),
                        value = uiState.dnsttSocksPassword,
                        onValueChange = { uiState.dnsttSocksPassword = it },
                    )
                }
            }
            if (uiState.dnsttEngine == DnsttFmt.ENGINE_VAYDNS) {
                VayDnsFields(uiState)
            }
            if (uiState.dnsttEngine == DnsttFmt.ENGINE_SLIPSTREAM) {
                SlipstreamFields(uiState)
            }
            if (uiState.dnsttEngine == DnsttFmt.ENGINE_SOAR) {
                SoarFields(uiState)
            }
            SettingsSwitchItem(
                title = stringResource(R.string.dnstt_lab_ssh),
                summary = stringResource(R.string.dnstt_ssh_support),
                checked = uiState.dnsttSshEnabled,
                onCheckedChange = {
                    uiState.dnsttSshEnabled = it
                    uiState.isDnsttSshError = false
                },
                enabled = uiState.dnsttEngine !in setOf(DnsttFmt.ENGINE_OFFICIAL, DnsttFmt.ENGINE_SOAR),
            )
            if (uiState.dnsttSshEnabled) {
                FormTextField(
                    label = stringResource(R.string.dnstt_lab_ssh_username),
                    value = uiState.dnsttSshUsername,
                    onValueChange = {
                        uiState.dnsttSshUsername = it
                        uiState.isDnsttSshError = false
                    },
                    isError = uiState.isDnsttSshError,
                )
                FormDropdownField(
                    label = stringResource(R.string.dnstt_lab_ssh_auth_type),
                    value = uiState.dnsttSshAuthType,
                    options = listOf("password", "key"),
                    onValueChange = { uiState.dnsttSshAuthType = it },
                )
                if (uiState.dnsttSshAuthType == "key") {
                    FormTextField(
                        label = stringResource(R.string.dnstt_lab_ssh_private_key),
                        value = uiState.dnsttSshPrivateKey,
                        onValueChange = { uiState.dnsttSshPrivateKey = it },
                        maxLines = 10,
                        isError = uiState.isDnsttSshError,
                    )
                    FormTextField(
                        label = stringResource(R.string.dnstt_lab_ssh_key_passphrase),
                        value = uiState.dnsttSshKeyPassphrase,
                        onValueChange = { uiState.dnsttSshKeyPassphrase = it },
                    )
                } else {
                    FormTextField(
                        label = stringResource(R.string.dnstt_lab_ssh_password),
                        value = uiState.dnsttSshPassword,
                        onValueChange = { uiState.dnsttSshPassword = it },
                    )
                }
                FormTextField(
                    label = stringResource(R.string.dnstt_lab_ssh_host),
                    value = uiState.dnsttSshHost,
                    onValueChange = { uiState.dnsttSshHost = it },
                    supportingText = stringResource(R.string.dnstt_ssh_host_support),
                    isError = uiState.isDnsttSshError,
                )
                FormTextField(
                    label = stringResource(R.string.dnstt_lab_ssh_port),
                    value = uiState.dnsttSshPort,
                    onValueChange = { uiState.dnsttSshPort = it },
                    keyboardType = KeyboardType.Number,
                    isError = uiState.isDnsttSshError,
                )
            }
            Text(
                text = stringResource(
                    if (uiState.dnsttEngine == DnsttFmt.ENGINE_SLIPSTREAM) {
                        R.string.slipstream_engine_notice
                    } else if (uiState.dnsttEngine == DnsttFmt.ENGINE_SOAR) {
                        R.string.soar_engine_notice
                    } else if (uiState.dnsttEngine == DnsttFmt.ENGINE_VAYDNS) {
                        R.string.vaydns_engine_notice
                    } else if (uiState.dnsttSshEnabled) {
                        R.string.dnstt_ssh_engine_notice
                    } else if (uiState.dnsttEngine == DnsttFmt.ENGINE_NOIZDNS) {
                        R.string.dnstt_noiz_engine_notice
                    } else if (uiState.dnsttEngine == DnsttFmt.ENGINE_DNSTT) {
                        R.string.dnstt_compat_engine_notice
                    } else {
                        R.string.dnstt_engine_notice
                    }
                ),
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
        val problem = DnsttFmt.normalize(config)
        state.isDnsttResolverError = problem == DnsttFmt.Problem.RESOLVER_MISSING || problem == DnsttFmt.Problem.RESOLVER_INVALID
        state.isDnsttDomainError = problem == DnsttFmt.Problem.DOMAIN_MISSING || problem == DnsttFmt.Problem.DOMAIN_INVALID
        state.isDnsttPublicKeyError = problem == DnsttFmt.Problem.PUBLIC_KEY_MISSING || problem == DnsttFmt.Problem.PUBLIC_KEY_INVALID
        state.isDnsttUtlsError = problem == DnsttFmt.Problem.UTLS_INVALID
        state.isDnsttSshError = problem == DnsttFmt.Problem.SSH_INVALID
        if (problem != null) {
            toast(
                when (problem) {
                    DnsttFmt.Problem.DOMAIN_MISSING -> R.string.dnstt_error_domain_missing
                    DnsttFmt.Problem.DOMAIN_INVALID -> R.string.dnstt_error_domain
                    DnsttFmt.Problem.TRANSPORT_INVALID -> R.string.dnstt_error_transport
                    DnsttFmt.Problem.RESOLVER_MISSING -> R.string.dnstt_error_resolver_missing
                    DnsttFmt.Problem.RESOLVER_INVALID -> R.string.dnstt_error_resolver
                    DnsttFmt.Problem.PUBLIC_KEY_MISSING -> R.string.dnstt_error_key_missing
                    DnsttFmt.Problem.PUBLIC_KEY_INVALID -> R.string.dnstt_error_key
                    DnsttFmt.Problem.UTLS_INVALID -> R.string.dnstt_error_utls
                    DnsttFmt.Problem.SSH_INVALID -> R.string.dnstt_error_ssh
                }
            )
            return false
        }
        return super.validateCommonConfig(state, config)
    }
}


@Composable
private fun SoarFields(uiState: ServerUiState) {
    FormDropdownField(
        label = stringResource(R.string.dnstt_lab_transport),
        value = SoarProfileFmt.settingsTransport(uiState.soarSettings) ?: "auto",
        options = listOf("auto", "on", "only", "off"),
        onValueChange = { selected ->
            uiState.soarSettings = SoarProfileFmt.updateSettings(uiState.soarSettings, transport = selected)
            uiState.dnsttTransport = if (selected == "only") "tcp" else "udp"
        },
        supportingText = stringResource(R.string.soar_transport_support),
    )
    SettingsSwitchItem(
        title = stringResource(R.string.soar_lab_discovery),
        summary = stringResource(R.string.soar_discovery_support),
        checked = SoarProfileFmt.discoveryEnabled(uiState.soarSettings),
        onCheckedChange = { checked ->
            uiState.soarSettings = SoarProfileFmt.updateSettings(uiState.soarSettings, discovery = checked)
        },
    )
    FormTextField(
        label = stringResource(R.string.soar_lab_settings),
        value = uiState.soarSettings,
        onValueChange = { uiState.soarSettings = it },
        supportingText = stringResource(R.string.soar_settings_support),
        maxLines = 14,
    )
}

@Composable
private fun SlipstreamFields(uiState: ServerUiState) {
    FormTextField(
        label = stringResource(R.string.slipstream_lab_keepalive),
        value = uiState.slipstreamKeepAlive,
        onValueChange = { uiState.slipstreamKeepAlive = it },
        keyboardType = KeyboardType.Number,
    )
    FormDropdownField(
        label = stringResource(R.string.slipstream_lab_congestion_control),
        value = uiState.slipstreamCongestionControl,
        options = listOf("bbr", "dcubic"),
        onValueChange = { uiState.slipstreamCongestionControl = it },
    )
    SettingsSwitchItem(
        title = stringResource(R.string.slipstream_lab_gso),
        summary = stringResource(R.string.slipstream_gso_support),
        checked = uiState.slipstreamGsoEnabled,
        onCheckedChange = { uiState.slipstreamGsoEnabled = it },
    )
}

@Composable
private fun VayDnsFields(uiState: ServerUiState) {
    SettingsSwitchItem(
        title = stringResource(R.string.vaydns_lab_dnstt_compat),
        summary = stringResource(R.string.vaydns_dnstt_compat_support),
        checked = uiState.vaydnsDnsttCompat,
        onCheckedChange = { uiState.vaydnsDnsttCompat = it },
    )
    FormDropdownField(
        label = stringResource(R.string.vaydns_lab_record_type),
        value = uiState.vaydnsRecordType,
        options = listOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv", "null", "caa"),
        onValueChange = { uiState.vaydnsRecordType = it },
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_max_qname),
        value = uiState.vaydnsMaxQnameLen,
        onValueChange = { uiState.vaydnsMaxQnameLen = it },
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_rps),
        value = uiState.vaydnsRps,
        onValueChange = { uiState.vaydnsRps = it },
        keyboardType = KeyboardType.Decimal,
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_idle_timeout),
        value = uiState.vaydnsIdleTimeout,
        onValueChange = { uiState.vaydnsIdleTimeout = it },
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_keepalive),
        value = uiState.vaydnsKeepAlive,
        onValueChange = { uiState.vaydnsKeepAlive = it },
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_udp_timeout),
        value = uiState.vaydnsUdpTimeout,
        onValueChange = { uiState.vaydnsUdpTimeout = it },
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_max_labels),
        value = uiState.vaydnsMaxNumLabels,
        onValueChange = { uiState.vaydnsMaxNumLabels = it },
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        label = stringResource(R.string.vaydns_lab_client_id_size),
        value = uiState.vaydnsClientIdSize,
        onValueChange = { uiState.vaydnsClientIdSize = it },
        keyboardType = KeyboardType.Number,
    )
}
