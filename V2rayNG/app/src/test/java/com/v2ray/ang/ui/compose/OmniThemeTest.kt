package com.v2ray.ang.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniThemeTest {
    @Test
    fun darkThemeKeepsNormalTextReadableOnCardsAndFields() = checkContrast(DarkColor)

    @Test
    fun lightThemeKeepsNormalTextReadableOnCardsAndFields() = checkContrast(LightColor)

    private fun checkContrast(colors: ColorScheme) {
        for (surface in listOf(colors.surface, colors.surfaceContainerLow, colors.surfaceContainerHigh)) {
            for (text in listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.tertiary, colors.error)) {
                assertTrue(contrast(text, surface) >= 4.5, "Text must meet WCAG AA on the Omni surface")
            }
        }
        assertTrue(contrast(colors.onPrimary, colors.primary) >= 4.5)
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}

class OmniAppearanceTest {
    @org.junit.jupiter.api.Test
    fun storedAppearanceIdsRoundTripWithoutProfileChanges() {
        OmniSkin.entries.forEach { org.junit.jupiter.api.Assertions.assertEquals(it, OmniSkin.fromStored(it.id)) }
        OmniEffects.entries.forEach { org.junit.jupiter.api.Assertions.assertEquals(it, OmniEffects.fromStored(it.id)) }
    }

    @org.junit.jupiter.api.Test
    fun unknownEmptyAndMissingPreferencesUseSafeDefaults() {
        listOf(null, "", "removed-theme").forEach {
            org.junit.jupiter.api.Assertions.assertEquals(OmniSkin.VIOLET, OmniSkin.fromStored(it))
            org.junit.jupiter.api.Assertions.assertEquals(OmniEffects.FULL, OmniEffects.fromStored(it))
        }
    }

    @org.junit.jupiter.api.Test
    fun everySkinHasReadableAccentsInBothModes() {
        OmniSkin.entries.forEach { skin ->
            listOf(false, true).forEach { dark ->
                val colors = omniColorScheme(if (dark) DarkColor else LightColor, skin, dark)
                org.junit.jupiter.api.Assertions.assertTrue(omniContrast(colors.primary, colors.background) >= 4.5, skin.id)
                org.junit.jupiter.api.Assertions.assertTrue(omniContrast(colors.primary, colors.surfaceContainerLow) >= 4.5, skin.id)
                org.junit.jupiter.api.Assertions.assertTrue(omniContrast(colors.onPrimary, colors.primary) >= 4.5, skin.id)
            }
        }
    }
}
