package com.v2ray.ang.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Native shared glass/raised card. All real callbacks, explicit semantic colors and content survive. */
@Composable
fun OmniCard(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors? = null, elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.referenceRaisedPanel(), shape, colors ?: CardDefaults.cardColors(containerColor = Color.Transparent), elevation, border, content)
}

@Composable
fun OmniCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large, colors: CardColors? = null,
    elevation: CardElevation = CardDefaults.cardElevation(), border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(onClick, modifier.referenceRaisedPanel(), enabled, shape, colors ?: CardDefaults.cardColors(containerColor = Color.Transparent), elevation, border, interactionSource, content)
}
