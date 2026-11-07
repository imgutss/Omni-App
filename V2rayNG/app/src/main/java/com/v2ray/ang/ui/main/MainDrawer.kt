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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.referencePalette
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.referenceOrb
import com.v2ray.ang.ui.compose.referencePressTilt
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

internal val drawerItems = primaryDrawerItems + listOf(
    MainDestination.OmniPanel,
    MainDestination.OmniDoctor,
    MainDestination.OmniScanner,
    MainDestination.OmniDnsScanner,
    MainDestination.Logcat,
    MainDestination.CheckUpdate,
    MainDestination.BackupRestore,
    MainDestination.About
)

internal fun MainDestination.referenceArtwork(): String = when (this) {
    MainDestination.Subscriptions -> "subscriptions"
    MainDestination.PerAppProxy -> "per-app"
    MainDestination.Routing -> "routing"
    MainDestination.UserAssets -> "assets"
    MainDestination.Settings -> "settings"
    MainDestination.OmniPanel -> "hub"
    MainDestination.OmniDoctor -> "doctor"
    MainDestination.OmniScanner -> "scanner"
    MainDestination.OmniDnsScanner -> "language"
    MainDestination.OmniPrivacy -> "backup"
    MainDestination.OmniDnsLab -> "update"
    MainDestination.OmniRoutingStudio -> "backup"
    MainDestination.OmniSync -> "about"
    MainDestination.OmniSpeed -> "effects"
    MainDestination.Logcat -> "logs"
    MainDestination.CheckUpdate -> "update"
    MainDestination.BackupRestore -> "backup"
    MainDestination.About -> "about"
}

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
    val drawerBase=if(LocalDarkTheme.current)appearance.skin.referencePalette.background else colors.background
    val fullEffects = appearance.effects == com.v2ray.ang.ui.compose.OmniEffects.FULL
    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.fillMaxWidth(),
        drawerContainerColor = drawerBase
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(drawerBase).drawBehind {
            val accent=appearance.skin.accent
            fun glow(center: androidx.compose.ui.geometry.Offset,rx:Float,ry:Float,alpha:Float) {
                withTransform({scale(1f,ry/rx,center)}) {
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(0f to accent.copy(alpha=alpha),.62f to androidx.compose.ui.graphics.Color.Transparent,center=center,radius=rx),rx,center)
                }
            }
            glow(androidx.compose.ui.geometry.Offset(size.width,0f),size.width*1.2f,size.height*.46f,.32f)
            glow(androidx.compose.ui.geometry.Offset(0f,size.height),size.width*1.1f,size.height*.4f,.22f)
        }) {
        Column(
            modifier = Modifier
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
                    modifier = Modifier.size(48.dp).referenceOrb(appearance.skin.accent)
                ) { Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.acc_back), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)) }
                Column {
                    Text(if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "منو" else "Menu", style = MaterialTheme.typography.headlineSmall.copy(fontSize=28.sp,lineHeight=30.8.sp), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                    Text(if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "تجربهٔ خودت را شخصی کن" else "Customize your experience", style = MaterialTheme.typography.bodySmall.copy(fontSize=13.sp,lineHeight=17.sp), color = colors.onSurfaceVariant)
                }
            }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,top=10.dp,bottom=12.dp).referenceDrawerPanel(true).height(156.dp)
            ) {
                com.v2ray.ang.ui.compose.OmniLogo3d(Modifier.size(176.dp).align(Alignment.CenterEnd))
                Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp).fillMaxWidth(.5f)) {
                    Text("Omni", style = MaterialTheme.typography.headlineMedium.copy(fontSize=26.sp), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = colors.primary)
                    Text(if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "همهٔ گزینه‌ها یک‌جا" else "Manage all options in one place", style = MaterialTheme.typography.bodySmall.copy(fontSize=13.sp), color = colors.onSurfaceVariant)
                }
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3
            ) {
                drawerItems.forEach { item ->
                    androidx.compose.runtime.key(item.name) {
                        Surface(
                            onClick = { onNavigate(item) },
                            modifier = Modifier.weight(1f).referenceDrawerPanel(false),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            border = null,
                            shadowElevation = if (fullEffects) 5.dp else 0.dp
                        ) {
                            Column(
                                Modifier.padding(horizontal = 6.dp, vertical = 6.dp).heightIn(min = 100.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.v2ray.ang.ui.compose.OmniReferenceIcon(
                                    artwork = item.referenceArtwork()
                                )
                                Text(
                                    stringResource(item.labelRes),
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize=12.5.sp,lineHeight=14.5.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold),
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
}

/** Exact drawer-specific layering, distinct from the generic raised form panels. */
@Composable
private fun Modifier.referenceDrawerPanel(header:Boolean):Modifier {
    val appearance=com.v2ray.ang.ui.compose.LocalOmniAppearance.current
    val palette=appearance.skin.referencePalette
    val base=if(LocalDarkTheme.current)palette.background else MaterialTheme.colorScheme.background
    val ink=MaterialTheme.colorScheme.onSurface
    val accent=appearance.skin.accent
    val radius=if(header)30.dp else 26.dp
    return referencePressTilt().drawBehind {
        val r=androidx.compose.ui.geometry.CornerRadius(radius.toPx())
        drawRoundRect(androidx.compose.ui.graphics.Color.Black.copy(alpha=.62f),androidx.compose.ui.geometry.Offset(0f,if(header)4.dp.toPx()else 3.dp.toPx()),size,r)
        drawRoundRect(base.copy(alpha=if(header).6f else .55f),cornerRadius=r)
        val angle=(if(header)125f else 168f)*kotlin.math.PI.toFloat()/180f
        val direction=androidx.compose.ui.geometry.Offset(kotlin.math.sin(angle),-kotlin.math.cos(angle))
        val length=size.width*kotlin.math.abs(direction.x)+size.height*kotlin.math.abs(direction.y)
        val start=center-direction*(length/2f);val end=center+direction*(length/2f)
        val last=if(header)accent.copy(alpha=.06f)else ink.copy(alpha=.03f)
        drawRoundRect(androidx.compose.ui.graphics.Brush.linearGradient(0f to accent.copy(alpha=if(header).3f else .15f),(if(header).62f else .7f) to last,1f to last,start=start,end=end),cornerRadius=r)
        drawRoundRect(if(header)accent.copy(alpha=.42f)else ink.copy(alpha=.11f),cornerRadius=r,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ink.copy(alpha=.16f),androidx.compose.ui.graphics.Color.Transparent)),cornerRadius=r,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }.clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))
}
