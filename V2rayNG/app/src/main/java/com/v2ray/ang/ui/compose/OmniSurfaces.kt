package com.v2ray.ang.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Decorative only: callers retain their original click, focus and accessibility contracts. */
@Composable
fun Modifier.omniIconChrome(): Modifier = this
    .padding(3.dp)
    .clip(MaterialTheme.shapes.small)
    .background(Brush.verticalGradient(listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.surfaceContainerLow
    )))
    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
