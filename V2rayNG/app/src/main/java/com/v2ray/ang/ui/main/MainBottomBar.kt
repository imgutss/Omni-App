package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.v2ray.ang.ui.compose.LocalOmniStrike
import com.v2ray.ang.ui.compose.OmniStrike
import com.v2ray.ang.ui.compose.omniRaisedPower
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalOmniAppearance
import com.v2ray.ang.ui.compose.OmniEffects
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight
import kotlinx.coroutines.launch

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isDarkTheme: Boolean,
    isConnecting: Boolean = false,
    onAction: (MainAction) -> Unit
) {
    val effects = LocalOmniAppearance.current.effects
    val connected=isRunning && !isConnecting
    val strike = LocalOmniStrike.current
    var powerCenter by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent,MaterialTheme.colorScheme.surface.copy(alpha=.94f))))
                .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(start = 22.dp, end = 100.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val accent=LocalOmniAppearance.current.skin.accent
                Canvas(Modifier.size(10.dp)) {
                    val color=if(connected)accent else Color(0xFF7C7C7C)
                    drawCircle(if(connected)accent.copy(alpha=.17f)else Color.White.copy(alpha=.063f),size.width/2f+4.dp.toPx())
                    if(connected && effects!=OmniEffects.OFF)drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.5f),Color.Transparent),center,16.dp.toPx()),16.dp.toPx())
                    drawCircle(color)
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,fontWeight=FontWeight.ExtraBold),
                    color=if(connected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = displayText
                    }
                )
            }
        }
        FloatingActionButton(
            onClick = {
                strike(OmniStrike(powerCenter, !isRunning)) { onAction(MainAction.ToggleService) }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 22.dp)
                .offset(y = (-14).dp)
                .navigationBarsPadding()
                .size(46.dp)
                .omniRaisedPower(isRunning)
                .onGloballyPositioned { powerCenter = it.boundsInRoot().center },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            containerColor = Color.Transparent,
            elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)

        ) {
            Icon(
                painter = painterResource(R.drawable.ic_omni_power),
                contentDescription = stringResource(
                    if (isRunning) R.string.acc_stop else R.string.acc_start
                ),
                tint = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(22.dp)
            )
        }
    }
}
