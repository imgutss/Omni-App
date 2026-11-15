package com.v2ray.ang.ui.compose

import androidx.compose.ui.graphics.Color

/** Literal SK tokens from the accepted final HTML, not a generic Material approximation. */
internal data class OmniPalette(val background: Color, val surface: Color, val low: Color, val high: Color, val highest: Color, val text: Color, val muted: Color)
internal val OmniSkin.referencePalette: OmniPalette get() = when (this) {
    OmniSkin.VIOLET -> OmniPalette(Color(0xFF05030F), Color(0xFF0D0A20), Color(0xFF120E2B), Color(0xFF1A1440), Color(0xFF231A55), Color(0xFFF4F1FF), Color(0xFFB4A9E6))
    OmniSkin.BLUE -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.TEAL -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.PINK -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.ORANGE -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.GREEN -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.YELLOW -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.OMNI -> OmniPalette(Color(0xFF0B0C0F), Color(0xFF14161A), Color(0xFF1A1D22), Color(0xFF20242A), Color(0xFF272B32), Color(0xFFF2F4F7), Color(0xFFA3ABB7))
    OmniSkin.SPOTIFY -> OmniPalette(Color(0xFF121212), Color(0xFF181818), Color(0xFF1F1F1F), Color(0xFF252525), Color(0xFF2C2C2C), Color(0xFFFFFFFF), Color(0xFFB3B3B3))
    OmniSkin.APPLE -> OmniPalette(Color(0xFF000000), Color(0xFF1D1D1F), Color(0xFF272729), Color(0xFF2A2A2C), Color(0xFF333333), Color(0xFFFFFFFF), Color(0xFFCCCCCC))
    OmniSkin.CLAUDE -> OmniPalette(Color(0xFF181715), Color(0xFF1F1E1B), Color(0xFF252320), Color(0xFF2C2A26), Color(0xFF35322D), Color(0xFFFAF9F5), Color(0xFFA39E93))
    OmniSkin.FERRARI -> OmniPalette(Color(0xFF000000), Color(0xFF0D0D0D), Color(0xFF181818), Color(0xFF202020), Color(0xFF303030), Color(0xFFFFFFFF), Color(0xFF969696))
    OmniSkin.LAMBORGHINI -> OmniPalette(Color(0xFF000000), Color(0xFF181818), Color(0xFF202020), Color(0xFF2A2A2A), Color(0xFF333333), Color(0xFFFFFFFF), Color(0xFFA0A0A0))
    OmniSkin.RENAULT -> OmniPalette(Color(0xFF000000), Color(0xFF111111), Color(0xFF1A1A1A), Color(0xFF222222), Color(0xFF2B2B2B), Color(0xFFFFFFFF), Color(0xFFB5B5B5))
    OmniSkin.PINTEREST -> OmniPalette(Color(0xFF130E14), Color(0xFF211922), Color(0xFF2A212B), Color(0xFF332A34), Color(0xFF3D323E), Color(0xFFFFFFFF), Color(0xFFB9B0BA))
    OmniSkin.WEBFLOW -> OmniPalette(Color(0xFF080808), Color(0xFF121212), Color(0xFF1A1A1A), Color(0xFF222222), Color(0xFF2B2B2B), Color(0xFFFFFFFF), Color(0xFFABABAB))
    OmniSkin.LOVABLE -> OmniPalette(Color(0xFF141414), Color(0xFF1C1C1C), Color(0xFF232321), Color(0xFF2B2A27), Color(0xFF34322E), Color(0xFFFCFBF8), Color(0xFFA8A69F))
    OmniSkin.NINTENDO -> OmniPalette(Color(0xFF131833), Color(0xFF1A2142), Color(0xFF222B55), Color(0xFF2B3668), Color(0xFF34417A), Color(0xFFEEF2FF), Color(0xFFA9B6E6))
    OmniSkin.DELL -> OmniPalette(Color(0xFF0A0A0A), Color(0xFF151515), Color(0xFF1D1D1D), Color(0xFF262626), Color(0xFF303030), Color(0xFFFFFFFF), Color(0xFFBDBDBD))
}
