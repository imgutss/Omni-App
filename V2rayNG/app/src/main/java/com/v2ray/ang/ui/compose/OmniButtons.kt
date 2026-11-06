package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun OmniButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=RoundedCornerShape(16.dp),
    colors:ButtonColors?=null,
    elevation:ButtonElevation?=null,border:BorderStroke?=null,
    contentPadding:PaddingValues=PaddingValues(horizontal=20.dp),
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) {
    val actual=colors?:ButtonDefaults.buttonColors(
        containerColor=LocalOmniAppearance.current.skin.accent,
        disabledContainerColor=LocalOmniAppearance.current.skin.accent.copy(alpha=.38f),
        contentColor=omniSourceOnAccent(LocalOmniAppearance.current.skin),
        disabledContentColor=omniSourceOnAccent(LocalOmniAppearance.current.skin).copy(alpha=.38f))
    SourceButton(onClick,modifier,enabled,shape,actual,elevation,border,contentPadding,interactionSource,filled=true,content=content)
}

@Composable
fun OmniOutlinedButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=RoundedCornerShape(16.dp),
    colors:ButtonColors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),
    elevation:ButtonElevation?=null,
    border:BorderStroke?=null,
    contentPadding:PaddingValues=PaddingValues(horizontal=20.dp),
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = SourceButton(onClick,modifier,enabled,shape,colors,elevation,border?:BorderStroke(1.dp,LocalOmniAppearance.current.skin.accent.copy(alpha=if(enabled)1f else .38f)),contentPadding,interactionSource,filled=false,content=content)

@Composable
fun OmniTextButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=RoundedCornerShape(16.dp),
    colors:ButtonColors=ButtonDefaults.textButtonColors(contentColor=LocalOmniAppearance.current.skin.accent,disabledContentColor=LocalOmniAppearance.current.skin.accent.copy(alpha=.38f)),
    elevation:ButtonElevation?=null,border:BorderStroke?=null,
    contentPadding:PaddingValues=PaddingValues(horizontal=12.dp),
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = SourceButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,filled=null,content=content)

@Composable
fun OmniFilledTonalButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=RoundedCornerShape(16.dp),colors:ButtonColors=ButtonDefaults.filledTonalButtonColors(),
    elevation:ButtonElevation?=ButtonDefaults.filledTonalButtonElevation(),border:BorderStroke?=null,
    contentPadding:PaddingValues=PaddingValues(horizontal=20.dp),
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = SourceButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,filled=true,content=content)

/** Native Material still dispatches every real click/disabled/focus/keyboard/accessibility action.
 * Real interaction state only drives source 2dp/.99 pressed pose and its beveled paint. */
@Composable
private fun SourceButton(
    onClick:()->Unit,modifier:Modifier,enabled:Boolean,shape:Shape,colors:ButtonColors,
    elevation:ButtonElevation?,border:BorderStroke?,contentPadding:PaddingValues,
    interactionSource:MutableInteractionSource?,filled:Boolean?,content:@Composable RowScope.()->Unit
) {
    val interaction=interactionSource?:remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val motion=LocalOmniAppearance.current.effects!=OmniEffects.OFF && android.animation.ValueAnimator.areAnimatorsEnabled()
    val progress by animateFloatAsState(if(pressed && enabled)1f else 0f,tween(if(motion)150 else 0),label="source-button-press")
    val base=if(enabled)colors.containerColor else colors.disabledContainerColor
    val low=MaterialTheme.colorScheme.surfaceContainerLow
    MaterialButton(
        onClick=onClick,enabled=enabled,shape=shape,elevation=elevation,border=border,
        interactionSource=interaction,contentPadding=contentPadding,
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent,contentColor=colors.contentColor,disabledContentColor=colors.disabledContentColor),
        modifier=modifier.defaultMinSize(minHeight=46.dp).graphicsLayer {
            translationY=progress*2.dp.toPx();scaleX=1f-progress*.01f;scaleY=scaleX
        }.drawBehind {
            val r=CornerRadius(16.dp.toPx())
            if(filled!=null) {
                drawRoundRect(Color.Black.copy(alpha=if(enabled).5f else .15f),Offset(0f,3.dp.toPx()),size,r)
                drawRoundRect(if(filled || base.alpha>0f)base else low.copy(alpha=if(enabled)1f else .38f),cornerRadius=r)
            }else if(base.alpha>0f)drawRoundRect(base,cornerRadius=r)
            if(filled==true) {
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(enabled).46f else .17f),Color.Transparent,Color.Black.copy(alpha=if(enabled).2f else .07f))),cornerRadius=r,style=Stroke(1.dp.toPx()))
                drawLine(Color.Black.copy(alpha=if(enabled).2f else .07f),Offset(16.dp.toPx(),size.height-2.dp.toPx()),Offset(size.width-16.dp.toPx(),size.height-2.dp.toPx()),strokeWidth=3.dp.toPx())
            }
        },content=content
    )
}

/** Exact original skin on-accent inks, distinct from AA-adjusted body text accents. */
internal fun omniSourceOnAccent(skin:OmniSkin):Color=when(skin) {
    OmniSkin.TEAL->Color(0xFF052B27)
    OmniSkin.ORANGE->Color(0xFF2A1100)
    OmniSkin.GREEN->Color(0xFF05260F)
    OmniSkin.YELLOW->Color(0xFF2A2100)
    OmniSkin.OMNI->Color(0xFF04231A)
    OmniSkin.LOVABLE->Color(0xFF1C1C1C)
    OmniSkin.NINTENDO->Color(0xFF1A0C00)
    OmniSkin.SPOTIFY,OmniSkin.LAMBORGHINI,OmniSkin.RENAULT,OmniSkin.DELL->Color.Black
    else->Color.White
}
