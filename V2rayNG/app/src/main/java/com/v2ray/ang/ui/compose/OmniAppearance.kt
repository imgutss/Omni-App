package com.v2ray.ang.ui.compose

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.v2ray.ang.R

/** IDs belong only to appearance settings, never profile or VPN configuration. */
enum class OmniSkin(val id: String, @param:StringRes val label: Int, val accent: Color, val backdrop: Color, val panel: Color) {
    VIOLET("violet", R.string.omni_skin_violet, Color(0xFF8B5CF6), Color(0xFF05030F), Color(0xFF0D0A20)),
    BLUE("blue", R.string.omni_skin_blue, Color(0xFF4D8DFF), Color(0xFF0B0C0F), Color(0xFF14161C)),
    TEAL("teal", R.string.omni_skin_teal, Color(0xFF2DD4BF), Color(0xFF0B0C0F), Color(0xFF14161C)),
    PINK("pink", R.string.omni_skin_pink, Color(0xFFF43F9E), Color(0xFF0B0C0F), Color(0xFF14161C)),
    ORANGE("orange", R.string.omni_skin_orange, Color(0xFFFB923C), Color(0xFF0B0C0F), Color(0xFF14161C)),
    GREEN("green", R.string.omni_skin_green, Color(0xFF4ADE80), Color(0xFF0B0C0F), Color(0xFF14161C)),
    YELLOW("yellow", R.string.omni_skin_yellow, Color(0xFFFACC15), Color(0xFF0B0C0F), Color(0xFF14161C)),
    OMNI("omni", R.string.omni_skin_omni, Color(0xFF3DDC97), Color(0xFF071510), Color(0xFF11261D)),
    SPOTIFY("spotify", R.string.omni_skin_spotify, Color(0xFF1ED760), Color(0xFF121212), Color(0xFF181818)),
    APPLE("apple", R.string.omni_skin_apple, Color(0xFF2997FF), Color(0xFF000000), Color(0xFF1D1D1F)),
    CLAUDE("claude", R.string.omni_skin_claude, Color(0xFFCC785C), Color(0xFF181715), Color(0xFF1F1E1B)),
    FERRARI("ferrari", R.string.omni_skin_ferrari, Color(0xFFDA291C), Color(0xFF000000), Color(0xFF0D0D0D)),
    LAMBORGHINI("lamborghini", R.string.omni_skin_lamborghini, Color(0xFFFFC000), Color(0xFF000000), Color(0xFF181818)),
    RENAULT("renault", R.string.omni_skin_renault, Color(0xFFFFED00), Color(0xFF000000), Color(0xFF111111)),
    PINTEREST("pinterest", R.string.omni_skin_pinterest, Color(0xFFE60023), Color(0xFF130E14), Color(0xFF211922)),
    WEBFLOW("webflow", R.string.omni_skin_webflow, Color(0xFF4D8DFF), Color(0xFF080808), Color(0xFF121212)),
    LOVABLE("lovable", R.string.omni_skin_lovable, Color(0xFFF7F4ED), Color(0xFF141414), Color(0xFF1C1C1C)),
    NINTENDO("nintendo", R.string.omni_skin_nintendo, Color(0xFFF68D1F), Color(0xFF131833), Color(0xFF1A2142)),
    DELL("dell", R.string.omni_skin_dell, Color(0xFFFCC20F), Color(0xFF0A0A0A), Color(0xFF151515));

    companion object {
        fun fromStored(value: String?): OmniSkin = entries.firstOrNull { it.id == value } ?: VIOLET
    }
}

enum class OmniEffects(val id: String, @param:StringRes val label: Int) {
    OFF("off", R.string.omni_effects_off),
    LIGHT("light", R.string.omni_effects_light),
    FULL("full", R.string.omni_effects_full);

    companion object {
        fun fromStored(value: String?): OmniEffects = entries.firstOrNull { it.id == value } ?: FULL
    }
}

data class OmniAppearance(val skin: OmniSkin = OmniSkin.VIOLET, val effects: OmniEffects = OmniEffects.FULL)
val LocalOmniAppearance = staticCompositionLocalOf { OmniAppearance() }

/** Keep semantic error/success colors independent from the decorative brand accent. */
internal fun omniColorScheme(base: ColorScheme, skin: OmniSkin, dark: Boolean): ColorScheme {
    val palette = skin.referencePalette
    val background = if (dark) palette.background else base.background
    // HTML brand swatches sometimes fail contrast. Lift/darken the accent only as far as AA needs.
    var accent = skin.accent
    val target = if (dark) Color.White else Color.Black
    repeat(20) {
        if (omniContrast(accent, background) < 4.5 || omniContrast(accent, if (dark) palette.low else base.surfaceContainerLow) < 4.5) {
            accent = Color(
                accent.red * 0.9f + target.red * 0.1f,
                accent.green * 0.9f + target.green * 0.1f,
                accent.blue * 0.9f + target.blue * 0.1f
            )
        }
    }
    val onAccent = if (omniContrast(Color.Black, accent) >= omniContrast(Color.White, accent)) Color.Black else Color.White
    return base.copy(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        primaryContainer = if (dark) palette.surface else base.primaryContainer,
        background = background, surface = if (dark) palette.surface else base.surface,
        onSurface = if (dark) palette.text else base.onSurface,
        onSurfaceVariant = if (dark) palette.muted else base.onSurfaceVariant,
        surfaceContainerLowest = background,
        surfaceContainerLow = if (dark) palette.low else base.surfaceContainerLow,
        surfaceContainer = if (dark) palette.low else base.surfaceContainer,
        surfaceContainerHigh = if (dark) palette.high else base.surfaceContainerHigh,
        surfaceContainerHighest = if (dark) palette.highest else base.surfaceContainerHighest,
        surfaceTint = accent
    )
}

internal fun omniContrast(a: Color, b: Color): Double {
    val x = a.luminance().toDouble()
    val y = b.luminance().toDouble()
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}
