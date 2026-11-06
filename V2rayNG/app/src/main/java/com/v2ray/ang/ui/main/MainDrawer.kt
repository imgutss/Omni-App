package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.verticalScrollbar

enum class MainDestination(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Subscriptions(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting),
    PerAppProxy(R.drawable.ic_per_apps_24dp, R.string.per_app_proxy_settings),
    Routing(R.drawable.ic_routing_24dp, R.string.routing_settings_title),
    UserAssets(R.drawable.ic_file_24dp, R.string.title_user_asset_setting),
    Settings(R.drawable.ic_settings_24dp, R.string.title_settings),
    OmniPanel(R.drawable.ic_cloud_download_24dp, R.string.title_omni_panel),
    OmniDoctor(R.drawable.ic_check_update_24dp, R.string.title_omni_doctor),
    OmniScanner(R.drawable.ic_scan_24dp, R.string.omni_scanner_title),
    OmniDnsScanner(R.drawable.ic_scan_24dp, R.string.omni_dns_scanner_title),
    Logcat(R.drawable.ic_logcat_24dp, R.string.title_logcat),
    CheckUpdate(R.drawable.ic_check_update_24dp, R.string.update_check_for_update),
    BackupRestore(R.drawable.ic_restore_24dp, R.string.title_configuration_backup_restore),
    About(R.drawable.ic_about_24dp, R.string.title_about)
}

private val primaryDrawerItems = listOf(
    MainDestination.Subscriptions,
    MainDestination.PerAppProxy,
    MainDestination.Routing,
    MainDestination.UserAssets,
    MainDestination.Settings
)

private val drawerItems = primaryDrawerItems + listOf(
    MainDestination.OmniPanel,
    MainDestination.OmniDoctor,
    MainDestination.OmniScanner,
    MainDestination.OmniDnsScanner,
    MainDestination.Logcat,
    MainDestination.CheckUpdate,
    MainDestination.BackupRestore,
    MainDestination.About
)

@Composable
fun MainDrawerContent(
    drawerState: DrawerState,
    appearance: com.v2ray.ang.ui.compose.OmniAppearance,
    onSkin: (com.v2ray.ang.ui.compose.OmniSkin) -> Unit,
    onEffects: (com.v2ray.ang.ui.compose.OmniEffects) -> Unit,
    onNavigate: (MainDestination) -> Unit
) {
    val drawerScrollState = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val fullEffects = appearance.effects == com.v2ray.ang.ui.compose.OmniEffects.FULL
    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.fillMaxWidth(),
        drawerContainerColor = colors.background
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(drawerScrollState)
                .verticalScrollbar(drawerScrollState)
                .padding(bottom = 24.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.IconButton(onClick = { scope.launch { drawerState.close() } }) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.acc_back))
                }
                Image(painterResource(R.drawable.ic_omni_logo), null, Modifier.size(48.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3
            ) {
                drawerItems.forEach { item ->
                    androidx.compose.runtime.key(item.name) {
                        Surface(
                            onClick = { onNavigate(item) },
                            modifier = Modifier.fillMaxWidth(0.31f),
                            shape = MaterialTheme.shapes.large,
                            color = colors.surfaceContainerLow,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.outlineVariant),
                            shadowElevation = if (fullEffects) 5.dp else 0.dp
                        ) {
                            Column(
                                Modifier.padding(horizontal = 6.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(48.dp)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.3f), colors.surfaceContainerHigh)),
                                            MaterialTheme.shapes.medium
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(item.iconRes),
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Text(
                                    stringResource(item.labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 2,
                                    minLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            com.v2ray.ang.ui.compose.OmniAppearanceControls(appearance, onSkin, onEffects)
        }
    }
}
