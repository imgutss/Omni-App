package com.v2ray.ang.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The original .btn primary/outlined dimensions, raised gradient and text treatment. */
@Composable
fun OmniButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp), colors: ButtonColors? = null,
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    val accent = colors?.containerColor ?: LocalOmniAppearance.current.skin.accent
    Button(onClick, modifier.referenceRaisedControl(enabled, accent).heightIn(min = 46.dp), enabled, shape,
        colors?.copy(containerColor = Color.Transparent) ?: ButtonDefaults.buttonColors(containerColor = Color.Transparent), elevation, border,
        contentPadding, interactionSource) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontWeight = FontWeight.ExtraBold)) { content() }
    }
}

@Composable
fun OmniOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp), colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.referenceRaisedControl(false).heightIn(min = 46.dp), enabled, shape,
        colors, elevation, border ?: BorderStroke(1.dp, LocalOmniAppearance.current.skin.accent.copy(alpha = .6f)), contentPadding, interactionSource) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontWeight = FontWeight.ExtraBold)) { content() }
    }
}
