package com.v2ray.ang.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.ui.compose.OmniEffects
import com.v2ray.ang.ui.compose.OmniSkin
import com.v2ray.ang.ui.compose.ThemeManager
import kotlinx.coroutines.launch

class AppearanceViewModel : ViewModel() {
    val appearance = ThemeManager.appearance

    init { viewModelScope.launch { ThemeManager.refreshAppearance() } }

    fun selectSkin(skin: OmniSkin) { viewModelScope.launch { ThemeManager.setSkin(skin) } }
    fun selectEffects(effects: OmniEffects) { viewModelScope.launch { ThemeManager.setEffects(effects) } }
}
