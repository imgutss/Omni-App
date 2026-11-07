package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** Original 52x32 switch, 16->24 knob and recessed/raised gradients. Real checked callbacks only. */
@Composable
fun OmniSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(), interactionSource: MutableInteractionSource? = null) {
    val accent = LocalOmniAppearance.current.skin.accent
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(if (LocalOmniAppearance.current.effects == OmniEffects.OFF) 0 else 150), label = "reference-switch")
    val interactive = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        else Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(checked); if (!enabled) disabled() }
    Box(modifier.then(interactive).size(52.dp, 48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(52.dp, 32.dp)) {
            val opacity = if (enabled) 1f else .38f
            val track = lerp(Color(0xFF121212), lerp(accent, Color.Black, .13f), progress).copy(alpha = opacity)
            drawRoundRect(track, cornerRadius = CornerRadius(16.dp.toPx()))
            drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .85f * opacity), Color.White.copy(alpha = .12f * opacity))), cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
            val center = Offset((14f + 22f * progress).dp.toPx(), size.height / 2f)
            val radius = (8f + 4f * progress).dp.toPx()
            drawCircle(Color.Black.copy(alpha = .65f * opacity), radius, center + Offset(0f, 3.dp.toPx()))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = opacity), lerp(Color(0xFFB3B3B3), lerp(accent, Color.White, .29f), progress).copy(alpha = opacity)), center + Offset(-radius * .4f, -radius * .5f), radius * 1.8f), radius, center)
        }
        if (thumbContent != null) Box(contentAlignment = Alignment.Center) { thumbContent() }
    }
}

@Composable
fun OmniCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: CheckboxColors = CheckboxDefaults.colors(), interactionSource: MutableInteractionSource? = null) {
    val accent = LocalOmniAppearance.current.skin.accent
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val interactive = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
        else Modifier.semantics { role = Role.Checkbox; toggleableState = ToggleableState(checked); if (!enabled) disabled() }
    Box(modifier.then(interactive).size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val a = if (enabled) 1f else .38f
            val r = CornerRadius(7.dp.toPx())
            if (checked) {
                drawRoundRect(Brush.linearGradient(listOf(accent.copy(alpha = a), lerp(accent, Color.Black, .28f).copy(alpha = a))), cornerRadius = r)
                val tick = Path().apply { moveTo(size.width * .23f, size.height * .5f); lineTo(size.width * .43f, size.height * .7f); lineTo(size.width * .79f, size.height * .3f) }
                drawPath(tick, Color(0xFF181818).copy(alpha = a), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            } else drawRoundRect(neutral.copy(alpha = a), cornerRadius = r, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
fun OmniRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: RadioButtonColors = RadioButtonDefaults.colors(), interactionSource: MutableInteractionSource? = null) {
    val accent = LocalOmniAppearance.current.skin.accent
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val interactive = if (onClick != null) Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else Modifier.semantics { role = Role.RadioButton; this.selected = selected; if (!enabled) disabled() }
    Box(modifier.then(interactive).size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val a = if (enabled) 1f else .38f
            drawCircle((if (selected) accent else neutral).copy(alpha = a), radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (selected) drawCircle(Brush.linearGradient(listOf(accent.copy(alpha = a), lerp(accent, Color.Black, .28f).copy(alpha = a))), radius = 5.dp.toPx())
        }
    }
}
