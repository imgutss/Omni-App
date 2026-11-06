package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Real ViewModel status only: the HTML's sample timers, speeds and traffic are not copied. */
@Composable
internal fun MainConnectionCard(displayText: String, isRunning: Boolean) {
    val fullEffects = com.v2ray.ang.ui.compose.LocalOmniAppearance.current.effects == com.v2ray.ang.ui.compose.OmniEffects.FULL
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = if (fullEffects) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainerLow)))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(colors.outlineVariant, style = Stroke(3.dp.toPx()))
                if (isRunning) drawCircle(colors.primary, style = Stroke(3.dp.toPx()))
                drawCircle(
                    if (isRunning) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.35f),
                    radius = 6.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
