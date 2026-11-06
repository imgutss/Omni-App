package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val LightColor = lightColorScheme(
    primary = Color(0xFF6740C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEE7FF),
    onPrimaryContainer = Color(0xFF261145),
    secondary = Color(0xFF6740C4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEE7FF),
    onSecondaryContainer = Color(0xFF261145),
    tertiary = Color(0xFF2863B3),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD8E5FF),
    onTertiaryContainer = Color(0xFF102C57),
    error = Color(0xFFB32644),
    errorContainer = Color(0xFFFFD9DF),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF410013),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF17151C),
    surface = Color(0xFFF7F7FA),
    onSurface = Color(0xFF17151C),
    surfaceVariant = Color(0xFFEAE7F0),
    onSurfaceVariant = Color(0xFF575260),
    outline = Color(0xFF77717F),
    outlineVariant = Color(0xFFD3CEDC),
    inverseSurface = Color(0xFF24222B),
    inverseOnSurface = Color(0xFFF4EFFA),
    inversePrimary = Color(0xFFC8AFFF),
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF6740C4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F0F6),
    surfaceContainer = Color(0xFFEEEAF3),
    surfaceContainerHigh = Color(0xFFE8E3EE),
    surfaceContainerHighest = Color(0xFFE1DCE9),
)

internal val DarkColor = darkColorScheme(
    primary = Color(0xFFA781FF),
    onPrimary = Color(0xFF1B0A38),
    primaryContainer = Color(0xFF32204D),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFA781FF),
    onSecondary = Color(0xFF1B0A38),
    secondaryContainer = Color(0xFF32204D),
    onSecondaryContainer = Color(0xFFEBDDFF),
    tertiary = Color(0xFF71AAFF),
    onTertiary = Color(0xFF09224D),
    tertiaryContainer = Color(0xFF153663),
    onTertiaryContainer = Color(0xFFD8E5FF),
    error = Color(0xFFFF8499),
    errorContainer = Color(0xFF581E2B),
    onError = Color(0xFF380614),
    onErrorContainer = Color(0xFFFFD9DF),
    background = Color(0xFF0B0C0F),
    onBackground = Color(0xFFF4F1FF),
    surface = Color(0xFF0B0C0F),
    onSurface = Color(0xFFF4F1FF),
    surfaceVariant = Color(0xFF24212B),
    onSurfaceVariant = Color(0xFFC0B6D4),
    outline = Color(0xFF8F879C),
    outlineVariant = Color(0xFF39333F),
    inverseSurface = Color(0xFFE9E2F0),
    inverseOnSurface = Color(0xFF24212B),
    inversePrimary = Color(0xFF6740C4),
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFFA781FF),
    surfaceContainerLowest = Color(0xFF07080B),
    surfaceContainerLow = Color(0xFF121216),
    surfaceContainer = Color(0xFF19181E),
    surfaceContainerHigh = Color(0xFF211F27),
    surfaceContainerHighest = Color(0xFF2A2632),
)

// Semantic Colors
val colorPing: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary
val colorPingRed: Color
    @Composable get() = MaterialTheme.colorScheme.error
val colorConfigType: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val colorFabActive = Color(0xFF8B5CF6)
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xD94A4F54) // Darker Gray
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD93F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White


// Presentation tokens translated from Omni-0.13.1.html. Existing theme preferences still win.
internal val OmniShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val OmniLatinFont = FontFamily(
    Font(R.font.omni_inter, FontWeight.Normal),
    Font(R.font.omni_inter_bold, FontWeight.Bold)
)
private val OmniPersianFont = FontFamily(
    Font(R.font.omni_vazirmatn, FontWeight.Normal),
    Font(R.font.omni_vazirmatn_bold, FontWeight.Bold)
)

private val OmniFontFigtree = FontFamily(Font(R.font.omni_figtree, FontWeight.Normal), Font(R.font.omni_figtree_bold, FontWeight.Bold))
private val OmniFontLora = FontFamily(Font(R.font.omni_lora, FontWeight.Normal), Font(R.font.omni_lora_bold, FontWeight.Bold))
private val OmniFontBarlowcondensed = FontFamily(Font(R.font.omni_barlowcondensed, FontWeight.Normal), Font(R.font.omni_barlowcondensed_bold, FontWeight.Bold))
private val OmniFontRajdhani = FontFamily(Font(R.font.omni_rajdhani, FontWeight.Normal), Font(R.font.omni_rajdhani_bold, FontWeight.Bold))
private val OmniFontPlusjakartasans = FontFamily(Font(R.font.omni_plusjakartasans, FontWeight.Normal), Font(R.font.omni_plusjakartasans_bold, FontWeight.Bold))
private val OmniFontNunito = FontFamily(Font(R.font.omni_nunito, FontWeight.Normal), Font(R.font.omni_nunito_bold, FontWeight.Bold))
private val OmniFontTinos = FontFamily(Font(R.font.omni_tinos, FontWeight.Normal), Font(R.font.omni_tinos_bold, FontWeight.Bold))
private val OmniFontArimo = FontFamily(Font(R.font.omni_arimo, FontWeight.Normal), Font(R.font.omni_arimo_bold, FontWeight.Bold))

@Composable
private fun omniTypography(skin: OmniSkin): Typography {
    val language = LocalConfiguration.current.locales[0].language
    val body = when (skin) {
        OmniSkin.SPOTIFY, OmniSkin.LOVABLE -> OmniFontFigtree
        OmniSkin.RENAULT -> OmniFontPlusjakartasans
        OmniSkin.PINTEREST, OmniSkin.NINTENDO -> OmniFontNunito
        OmniSkin.DELL -> OmniFontArimo
        else -> OmniLatinFont
    }
    val family = if (language == "fa" || language == "ar") OmniPersianFont else body
    val heading = if (language == "fa" || language == "ar") OmniPersianFont else when (skin) {
        OmniSkin.CLAUDE -> OmniFontLora
        OmniSkin.FERRARI -> OmniFontBarlowcondensed
        OmniSkin.LAMBORGHINI -> OmniFontRajdhani
        OmniSkin.DELL -> OmniFontTinos
        else -> family
    }
    val defaults = Typography()
    return Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = heading),
        displayMedium = defaults.displayMedium.copy(fontFamily = heading),
        displaySmall = defaults.displaySmall.copy(fontFamily = heading),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = heading, fontWeight = FontWeight.Bold),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = heading, fontWeight = FontWeight.Bold),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = heading, fontWeight = FontWeight.Bold),
        titleLarge = defaults.titleLarge.copy(fontFamily = heading, fontWeight = FontWeight.Bold),
        titleMedium = defaults.titleMedium.copy(fontFamily = heading, fontWeight = FontWeight.Bold),
        titleSmall = defaults.titleSmall.copy(fontFamily = heading, fontWeight = FontWeight.Bold),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = family),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = family),
        bodySmall = defaults.bodySmall.copy(fontFamily = family),
        labelLarge = defaults.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        labelMedium = defaults.labelMedium.copy(fontFamily = family),
        labelSmall = defaults.labelSmall.copy(fontFamily = family)
    )
}

object ThemeManager {
    private const val SKIN_KEY = "pref_omni_ui_skin"
    private const val EFFECTS_KEY = "pref_omni_ui_effects"
    private val appearanceMutex = Mutex()
    private val _appearance = MutableStateFlow(OmniAppearance())
    val appearance: StateFlow<OmniAppearance> = _appearance.asStateFlow()

    suspend fun refreshAppearance() = appearanceMutex.withLock {
        val stored = withContext(Dispatchers.IO) {
            OmniAppearance(
                OmniSkin.fromStored(MmkvManager.decodeSettingsString(SKIN_KEY)),
                OmniEffects.fromStored(MmkvManager.decodeSettingsString(EFFECTS_KEY))
            )
        }
        _appearance.value = stored
    }

    suspend fun setSkin(skin: OmniSkin) = appearanceMutex.withLock {
        // An explicit skin choice must be visible even when the previous version used
        // Android dynamic colors. The existing dynamic-color switch remains available.
        withContext(Dispatchers.IO) {
            MmkvManager.encodeSettings(SKIN_KEY, skin.id)
            MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)
        }
        _dynamicColorEnabled.value = false
        _appearance.value = _appearance.value.copy(skin = skin)
    }

    suspend fun setEffects(effects: OmniEffects) = appearanceMutex.withLock {
        withContext(Dispatchers.IO) { MmkvManager.encodeSettings(EFFECTS_KEY, effects.id) }
        _appearance.value = _appearance.value.copy(effects = effects)
    }

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val appearance by ThemeManager.appearance.collectAsStateWithLifecycle()
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> omniColorScheme(DarkColor, appearance.skin, true)
        else -> omniColorScheme(LightColor, appearance.skin, false)
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalOmniAppearance provides appearance,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) colorScheme.copy(background = Color.Transparent) else colorScheme,
            shapes = OmniShapes,
            typography = omniTypography(appearance.skin)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                OmniBackdrop()
                AppSnackbarBridge(controller = snackbarController)
                OmniLightningHost { content() }
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
