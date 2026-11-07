package com.v2ray.ang.omni

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.OmniButton
import com.v2ray.ang.ui.compose.OmniCard

/** Accepted scanner landing page, hosted by the existing activity; manifest/runtime stay pinned. */
@Composable
internal fun OmniScannerLanding(onBack: () -> Unit, onWarp: () -> Unit, onDns: () -> Unit) {
    Scaffold(topBar={ AppTopBar(stringResource(R.string.omni_scanner_title),onBackClick=onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OmniCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
                    Text(stringResource(R.string.omni_scanner_title),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.omni_scanner_home_hint),style=MaterialTheme.typography.bodyMedium)
                    OmniButton(onClick=onWarp,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_scanner_open_warp)) }
                    OmniButton(onClick=onDns,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_dns_scanner_title)) }
                }
            }
        }
    }
}
