package com.v2ray.ang.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
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
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.42f),RoundedCornerShape(99.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OmniEffects.entries.forEach { effect ->
                    RaisedChoice(stringResource(effect.label), appearance.effects == effect, Modifier.weight(1f),effect) { onEffects(effect) }
                }
            }
        }
        ReferenceControlCard("language", text("Language", "زبان"), text("Select language", "زبان را انتخاب کن")) {
            val language = AppCompatDelegate.getApplicationLocales().get(0)?.language
                ?: androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.42f),RoundedCornerShape(99.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RaisedChoice("English", language == "en", Modifier.weight(1f)) { AppLocaleManager.setApplicationLanguage("en") }
                RaisedChoice("فارسی", language == "fa", Modifier.weight(1f)) { AppLocaleManager.setApplicationLanguage("fa") }
            }
        }
        ReferenceControlCard("theme", text("Theme", "تم"), text("Pick your theme", "تمت را انتخاب کن")) {
            Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OmniSkin.entries.take(7).forEach { skin ->
                    val label=stringResource(skin.label)
                    val active=appearance.skin==skin
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                        Box(Modifier.sizeIn(maxWidth=44.dp,maxHeight=44.dp).fillMaxWidth().aspectRatio(1f)
                            .graphicsLayer { if(active) { translationY=-3f*density;scaleX=1.1f;scaleY=1.1f } }
                            .referenceOrb(skin.accent,active)
                            .selectable(active,role=Role.RadioButton,onClick={onSkin(skin)})
                            .semantics { contentDescription=label })
                    }
                }
            }
            OmniSkin.entries.drop(7).chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { skin ->
                        val active=appearance.skin==skin
                        Row(Modifier.weight(1f).referenceBrand(active,skin.accent).clip(RoundedCornerShape(20.dp))
                            .selectable(active,role=Role.RadioButton,onClick={onSkin(skin)})
                            .padding(start=9.dp,end=10.dp).heightIn(min=58.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(
                                0f to skin.backdrop,.5f to skin.backdrop,.5001f to skin.accent,1f to skin.accent))
                                .border(1.dp,Color.White.copy(alpha=.4f),RoundedCornerShape(13.dp)))
                            Text(stringResource(skin.label),modifier=Modifier.weight(1f),fontSize=12.5.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            if(active) Canvas(Modifier.size(20.dp)) {
                                drawCircle(Color.White)
                                val line=Path().apply { moveTo(size.width*5/24f,size.height*12.5f/24f);lineTo(size.width*9.5f/24f,size.height*17/24f);lineTo(size.width*19/24f,size.height*7.5f/24f) }
                                drawPath(line,Color(0xFF111111),style=Stroke(2.8.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
                            }
                        }
                    }
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
                Text(subtitle, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun RaisedChoice(label: String, selected: Boolean, modifier: Modifier, effect: OmniEffects?=null, onClick: () -> Unit) {
    val skin=LocalOmniAppearance.current.skin
    val ink=if(selected)skin.referenceOnAccent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.referenceSegment(selected).clip(RoundedCornerShape(99.dp)).selectable(selected,role=Role.RadioButton,onClick=onClick)
        .heightIn(min=44.dp).padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        if(effect==OmniEffects.LIGHT || effect==OmniEffects.FULL) {
            Canvas(Modifier.padding(end=8.dp).size(20.dp)) {
                val unit=size.width/24f
                withTransform({scale(unit,unit,Offset.Zero)}) {
                    val stroke=Stroke(2f,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round)
                    if(effect==OmniEffects.LIGHT) {
                        drawCircle(ink,4f,Offset(12f,12f),style=stroke)
                        listOf(Offset(12f,2f) to Offset(12f,4f),Offset(12f,20f) to Offset(12f,22f),Offset(2f,12f) to Offset(4f,12f),Offset(20f,12f) to Offset(22f,12f),Offset(5f,5f) to Offset(6.4f,6.4f),Offset(17.6f,17.6f) to Offset(19f,19f),Offset(5f,19f) to Offset(6.4f,17.6f),Offset(17.6f,6.4f) to Offset(19f,5f)).forEach { (a,b)->drawLine(ink,a,b,2f) }
                    } else {
                        val p=Path().apply { moveTo(12f,3f);lineTo(13.8f,8.2f);lineTo(19f,10f);lineTo(13.8f,11.8f);lineTo(12f,17f);lineTo(10.2f,11.8f);lineTo(5f,10f);lineTo(10.2f,8.2f);close();moveTo(19f,3f);lineTo(19f,7f);moveTo(17f,5f);lineTo(21f,5f);moveTo(5f,17f);lineTo(5f,21f);moveTo(3f,19f);lineTo(7f,19f) }
                        drawPath(p,ink,style=stroke)
                    }
                }
            }
        }
        Text(label,fontSize=13.5.sp,fontWeight=FontWeight.Bold,color=ink)
    }
}

internal val OmniSkin.referenceOnAccent: Color get()=when(this) {
    OmniSkin.TEAL->Color(0xFF052B27);OmniSkin.ORANGE->Color(0xFF2A1100);OmniSkin.GREEN->Color(0xFF05260F);OmniSkin.YELLOW->Color(0xFF2A2100)
    OmniSkin.OMNI->Color(0xFF04231A);OmniSkin.SPOTIFY,OmniSkin.LAMBORGHINI,OmniSkin.RENAULT,OmniSkin.DELL->Color.Black
    OmniSkin.LOVABLE->Color(0xFF1C1C1C);OmniSkin.NINTENDO->Color(0xFF1A0C00);else->Color.White
}

@Composable
internal fun Modifier.referenceOrb(accent:Color,selected:Boolean=false):Modifier {
    val base=if(LocalDarkTheme.current)LocalOmniAppearance.current.skin.referencePalette.background else MaterialTheme.colorScheme.background
    return referencePressTilt().drawBehind {
        val radius=size.minDimension/2f
        drawCircle(lerp(accent,Color.Black,.55f),radius,center+Offset(0f,4.dp.toPx()))
        if(selected) { drawCircle(base,radius+3.dp.toPx(),style=Stroke(3.dp.toPx()));drawCircle(accent,radius+5.dp.toPx(),style=Stroke(2.dp.toPx())) }
        drawCircle(Brush.radialGradient(0f to lerp(accent,Color.White,.38f),.4f to accent,1f to lerp(accent,Color.Black,.44f),center=Offset(size.width*.32f,size.height*.26f),radius=size.width*.74f),radius)
        val at=Offset(size.width*.17f,size.height*.11f);val extent=Size(size.width*.44f,size.height*.26f)
        val brush=Brush.verticalGradient(listOf(Color.White.copy(alpha=.72f),Color.Transparent),at.y,at.y+extent.height)
        withTransform({rotate(-22f,at+Offset(extent.width/2f,extent.height/2f))}) { drawOval(brush,at,extent) }
    }
}

@Composable
private fun Modifier.referenceSegment(selected:Boolean):Modifier {
    val accent=LocalOmniAppearance.current.skin.accent
    return referencePressTilt().drawBehind {
        if(selected) {
            val r=CornerRadius(size.height/2f)
            drawRoundRect(lerp(accent,Color.Black,.54f),Offset(0f,3.dp.toPx()),size,r)
            drawRoundRect(Brush.verticalGradient(0f to lerp(accent,Color.White,.24f),.52f to accent,1f to lerp(accent,Color.Black,.28f)),cornerRadius=r)
            drawRoundRect(Color.White.copy(alpha=.35f),cornerRadius=r,style=Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun Modifier.referenceBrand(selected:Boolean,accent:Color):Modifier {
    val ink=MaterialTheme.colorScheme.onSurface
    return referencePressTilt().drawBehind {
        val r=CornerRadius(20.dp.toPx())
        drawRoundRect(Color.Black.copy(alpha=.55f),Offset(0f,3.dp.toPx()),size,r)
        drawRoundRect(Brush.linearGradient(listOf(if(selected)accent.copy(alpha=.26f)else ink.copy(alpha=.07f),ink.copy(alpha=.02f))),cornerRadius=r)
        drawRoundRect(if(selected)accent else ink.copy(alpha=.11f),cornerRadius=r,style=Stroke(if(selected)1.5.dp.toPx()else 1.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(ink.copy(alpha=.14f),Color.Transparent)),cornerRadius=r,style=Stroke(1.dp.toPx()))
    }
}
