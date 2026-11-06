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
import androidx.compose.ui.draw.drawBehind
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
import com.v2ray.ang.ui.compose.referencePalette
import com.v2ray.ang.ui.compose.referenceRaisedPanel
import com.v2ray.ang.ui.compose.referenceRaisedControl
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
    OmniPrivacy(R.drawable.ic_settings_24dp, R.string.omni_privacy_title),
    OmniDnsLab(R.drawable.ic_scan_24dp, R.string.omni_dns_title),
    OmniRoutingStudio(R.drawable.ic_routing_24dp, R.string.omni_routing_title),
    OmniSync(R.drawable.ic_check_update_24dp, R.string.omni_sync_title),
    OmniSpeed(R.drawable.ic_scan_24dp, R.string.omni_speed_title),
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
    MainDestination.OmniPrivacy,
    MainDestination.OmniDnsLab,
    MainDestination.OmniRoutingStudio,
    MainDestination.OmniSync,
    MainDestination.OmniSpeed,
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
        drawerContainerColor = appearance.skin.referencePalette.background
    ) {
        Column(
            modifier = Modifier
                .background(appearance.skin.referencePalette.background)
                .drawBehind {
                    val accent = appearance.skin.accent
                    drawRect(androidx.compose.ui.graphics.Brush.radialGradient(0f to accent.copy(alpha = .32f), .62f to androidx.compose.ui.graphics.Color.Transparent, center = androidx.compose.ui.geometry.Offset(size.width, 0f), radius = size.width * 1.2f))
                    drawRect(androidx.compose.ui.graphics.Brush.radialGradient(0f to accent.copy(alpha = .22f), .62f to androidx.compose.ui.graphics.Color.Transparent, center = androidx.compose.ui.geometry.Offset(0f, size.height), radius = size.width * 1.1f))
                }
                .verticalScroll(drawerScrollState)
                .verticalScrollbar(drawerScrollState)
                .padding(bottom = 24.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                androidx.compose.material3.IconButton(
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.size(48.dp).referenceRaisedControl(true)
                ) { Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.acc_back), tint = colors.onPrimary) }
                Column {
                    Text(if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "منو" else "Menu", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                    Text(if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "تجربهٔ خودت را شخصی کن" else "Customize your experience", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).referenceRaisedPanel(30.dp).height(156.dp)
            ) {
                com.v2ray.ang.ui.compose.OmniLogo3d(Modifier.size(176.dp).align(Alignment.CenterEnd))
                Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp).fillMaxWidth(.55f)) {
                    Text("Omni", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = colors.primary)
                    Text(if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "همهٔ گزینه‌ها یک‌جا" else "Manage all options in one place", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3
            ) {
                drawerItems.forEach { item ->
                    androidx.compose.runtime.key(item.name) {
                        Surface(
                            onClick = { onNavigate(item) },
                            modifier = Modifier.fillMaxWidth(0.31f).referenceRaisedPanel(26.dp),
                            shape = MaterialTheme.shapes.large,
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.outlineVariant),
                            shadowElevation = if (fullEffects) 5.dp else 0.dp
                        ) {
                            Column(
                                Modifier.padding(horizontal = 6.dp, vertical = 14.dp).height(94.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                com.v2ray.ang.ui.compose.OmniReferenceIcon(
                                    artwork = when (item) {
                                        MainDestination.Subscriptions -> "subscriptions"
                                        MainDestination.PerAppProxy -> "per-app"
                                        MainDestination.Routing -> "routing"
                                        MainDestination.UserAssets -> "assets"
                                        MainDestination.Settings -> "settings"
                                        MainDestination.OmniPanel -> "hub"
                                        MainDestination.OmniDoctor -> "doctor"
                                        MainDestination.OmniScanner, MainDestination.OmniDnsScanner -> "language"
                                        MainDestination.OmniPrivacy -> "logs"
                                        MainDestination.OmniDnsLab -> "update"
                                        MainDestination.OmniRoutingStudio -> "backup"
                                        MainDestination.OmniSync -> "about"
                                        MainDestination.OmniSpeed -> "effects"
                                        MainDestination.Logcat -> "theme"
                                        MainDestination.CheckUpdate -> "update"
                                        MainDestination.BackupRestore -> "backup"
                                        MainDestination.About -> "about"
                                    }
                                )
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
