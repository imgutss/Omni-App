package com.v2ray.ang.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
fun OmniAppearanceControls(
    appearance: OmniAppearance,
    onSkin: (OmniSkin) -> Unit,
    onEffects: (OmniEffects) -> Unit
) {
    PreferenceGroupHeader(stringResource(R.string.omni_appearance_title))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OmniSkin.entries.forEach { skin ->
            FilterChip(
                selected = appearance.skin == skin,
                onClick = { onSkin(skin) },
                label = { Text(stringResource(skin.label)) },
                leadingIcon = { Box(Modifier.size(12.dp).background(skin.accent, CircleShape)) }
            )
        }
    }
    PreferenceGroupHeader(stringResource(R.string.omni_effects_title))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OmniEffects.entries.forEach { effects ->
            FilterChip(
                selected = appearance.effects == effects,
                onClick = { onEffects(effects) },
                label = { Text(stringResource(effects.label)) }
            )
        }
    }
    Text(
        stringResource(R.string.omni_appearance_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
