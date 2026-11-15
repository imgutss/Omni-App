package com.v2ray.ang.ui.compose

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Original layered SVG geometry, gradients and drop-shadow extracted from the HTML's final IC array.
 * The artwork is pre-rasterized at 4x, not substituted with a flat Material icon.
 * Canvas adds the original theme-colored halo; asset decoding never blocks the UI thread.
 */
@Composable
internal fun OmniReferenceIcon(artwork: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val appearance = LocalOmniAppearance.current
    val skin = appearance.skin
    val bitmap by produceState<ImageBitmap?>(null, artwork, skin.id) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("omni-reference/icons/${skin.id}/$artwork.png").use {
                requireNotNull(BitmapFactory.decodeStream(it)) { "Invalid reference icon: $artwork" }.asImageBitmap()
            }
        }
    }
    Canvas(modifier.size(64.dp)) {
        if (appearance.effects != OmniEffects.OFF) {
            val radius = size.minDimension * .5625f
            drawCircle(Brush.radialGradient(listOf(skin.accent.copy(alpha = .34f), Color.Transparent), center, radius), radius)
        }
        bitmap?.let { image ->
            val paddingX = size.width / 8f
            val paddingY = size.height / 8f
            drawImage(image, dstOffset = IntOffset(-paddingX.roundToInt(), -paddingY.roundToInt()), dstSize = IntSize((size.width + paddingX * 2).roundToInt(), (size.height + paddingY * 2).roundToInt()))
        }
    }
}
