package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal data class OmniDialogPose(val alpha:Float,val scale:Float,val rotationX:Float,val translateY:Float)
internal fun omniDialogPose(progress:Float):OmniDialogPose {
    val p=if(progress.isFinite())progress else 1f
    return OmniDialogPose(p.coerceIn(0f,1f),.94f+.06f*p,14f*(1f-p),30f*(1f-p))
}

/** Source glass/chrome and 400ms perspective entry; Material owns real modality,
 * IME, focus, back/outside dismissal and all untouched action/content slots. */
@Composable
fun OmniAlertDialog(
    onDismissRequest:()->Unit,
    confirmButton:@Composable ()->Unit,
    modifier:Modifier=Modifier,
    dismissButton:(@Composable ()->Unit)?=null,
    icon:(@Composable ()->Unit)?=null,
    title:(@Composable ()->Unit)?=null,
    text:(@Composable ()->Unit)?=null,
    shape:Shape=RoundedCornerShape(30.dp),
    containerColor:Color=MaterialTheme.colorScheme.surfaceContainer,
    iconContentColor:Color=MaterialTheme.colorScheme.secondary,
    titleContentColor:Color=MaterialTheme.colorScheme.secondary,
    textContentColor:Color=MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation:Dp=0.dp,
    properties:DialogProperties=DialogProperties()
) {
    val motion=LocalOmniAppearance.current.effects!=OmniEffects.OFF && android.animation.ValueAnimator.areAnimatorsEnabled()
    val dark=LocalDarkTheme.current
    val accent=LocalOmniAppearance.current.skin.accent
    val edge=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f)
    var entered by remember(motion){mutableStateOf(!motion)}
    LaunchedEffect(motion){entered=true}
    val progress by animateFloatAsState(if(entered)1f else 0f,tween(if(motion)400 else 0,easing=CubicBezierEasing(.2f,.9f,.3f,1.2f)),label="reference-dialog-entry")
    val pose=omniDialogPose(progress)
    MaterialAlertDialog(
        onDismissRequest=onDismissRequest,
        confirmButton=confirmButton,
        modifier=modifier.graphicsLayer {
            alpha=pose.alpha;scaleX=pose.scale;scaleY=pose.scale
            rotationX=pose.rotationX;translationY=pose.translateY*density;cameraDistance=900f*density
            this.shape=shape;shadowElevation=24.dp.toPx();clip=false
            ambientShadowColor=Color.Black.copy(alpha=.8f);spotShadowColor=Color.Black
        }.drawBehind {
            val r=CornerRadius(30.dp.toPx())
            drawRoundRect(containerColor,cornerRadius=r)
            drawRoundRect(cssLinearGradient(size,150f,0f to Color.White.copy(alpha=if(dark).078f else .85f),(if(dark).62f else 1f) to Color.White.copy(alpha=if(dark).02f else .55f),1f to Color.White.copy(alpha=if(dark).02f else .55f)),cornerRadius=r)
            drawRoundRect(Brush.radialGradient(listOf(accent.copy(alpha=.08f),Color.Transparent),radius=size.maxDimension),cornerRadius=r)
            drawRoundRect(edge,cornerRadius=r,style=Stroke(1.dp.toPx()))
        },
        dismissButton=dismissButton,icon=icon,title=title,text=text,shape=shape,
        containerColor=Color.Transparent,iconContentColor=iconContentColor,
        titleContentColor=titleContentColor,textContentColor=textContentColor,
        tonalElevation=tonalElevation,properties=properties
    )
}
