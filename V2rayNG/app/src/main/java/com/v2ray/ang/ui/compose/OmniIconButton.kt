package com.v2ray.ang.ui.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

internal val LocalOmniHeaderChrome=staticCompositionLocalOf{false}

/** Only toolbar slots receive source chrome. Row actions, terminal refresh and
 * other compact native controls retain their own geometry and all real actions. */
@Composable
fun OmniIconButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:IconButtonColors=IconButtonDefaults.iconButtonColors(),
    interactionSource:MutableInteractionSource?=null,content:@Composable ()->Unit
) {
    val framed=if(LocalOmniHeaderChrome.current && !modifier.hasOmniIconChrome())modifier.omniIconChrome()else modifier
    MaterialIconButton(onClick=onClick,modifier=framed,enabled=enabled,colors=colors,interactionSource=interactionSource,content=content)
}
