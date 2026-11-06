package com.v2ray.ang.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.appcompat.app.AppCompatDelegate
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLocaleManager

/** Native equivalents of the final HTML dw-card/seg/sw.brands, retaining every existing skin. */
@Composable
fun OmniAppearanceControls(appearance: OmniAppearance, onSkin: (OmniSkin) -> Unit, onEffects: (OmniEffects) -> Unit) {
    val persian = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa"
    fun text(en: String, fa: String) = if (persian) fa else en
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ReferenceControlCard("effects", stringResource(R.string.omni_effects_title), text("Choose your preferred style", "سبک دلخواهت را انتخاب کن")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OmniEffects.entries.forEach { effect ->
                    RaisedChoice(stringResource(effect.label), appearance.effects == effect, Modifier.weight(1f)) { onEffects(effect) }
                }
            }
        }
        ReferenceControlCard("language", text("Language", "زبان"), text("Select language", "زبان را انتخاب کن")) {
            val language = AppCompatDelegate.getApplicationLocales().get(0)?.language
                ?: androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RaisedChoice("English", language == "en", Modifier.weight(1f)) { AppLocaleManager.setApplicationLanguage("en") }
                RaisedChoice("فارسی", language == "fa", Modifier.weight(1f)) { AppLocaleManager.setApplicationLanguage("fa") }
            }
        }
        ReferenceControlCard("theme", text("Theme", "تم"), text("Pick your theme", "تمت را انتخاب کن")) {
            // The HTML's final UI uses two-column, raised brand tiles, not flat Material chips.
            // Keep the earlier seven accent themes as well: an update must not remove saved choices.
            OmniSkin.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { skin ->
                        val active = appearance.skin == skin
                        Row(
                            Modifier.weight(1f).referenceRaisedControl(active, skin.accent)
                                .selectable(active, role = Role.RadioButton, onClick = { onSkin(skin) })
                                .padding(horizontal = 10.dp, vertical = 8.dp).heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                                .background(Brush.linearGradient(listOf(skin.backdrop, skin.backdrop, skin.accent, skin.accent), start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(80f, 80f)))
                                .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(13.dp)))
                            Text(stringResource(skin.label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (active) Text("✓", color = skin.accent, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReferenceControlCard(icon: String, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalOmniAppearance.current.skin.accent
    Column(Modifier.fillMaxWidth().referenceRaisedPanel(30.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OmniReferenceIcon(icon, Modifier.size(54.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun RaisedChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.referenceRaisedControl(selected).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 44.dp).padding(8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
