package com.v2ray.ang.ui.compose

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the actual --art image from the reference, never a substitute procedural background. */
@Composable
internal fun OmniBackdrop() {
    val appearance = LocalOmniAppearance.current
    val dark = LocalDarkTheme.current
    val context = LocalContext.current.applicationContext
    val image by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("omni-reference/backdrop.webp").use { requireNotNull(BitmapFactory.decodeStream(it)).asImageBitmap() }
        }
    }
    Box(Modifier.fillMaxSize().background(if (dark) appearance.skin.referencePalette.background else Color.White)) {
        if (dark) {
            image?.let { art ->
                val blur = if (appearance.effects == OmniEffects.FULL) Modifier.blur(10.dp) else Modifier
                Image(art, contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = androidx.compose.ui.Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.11f; scaleY = 1.11f }.then(blur))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(appearance.skin.referencePalette.background.copy(alpha = .78f), appearance.skin.referencePalette.background.copy(alpha = .95f), appearance.skin.referencePalette.background.copy(alpha = .98f)))))
        }
    }
}
