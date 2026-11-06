package com.v2ray.ang.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original always-inset 11px label and 60px field. Native selection/IME/validation remain genuine. */
@Composable
fun OmniTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null, suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1, interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val border = when { !enabled -> colors.disabledIndicatorColor; isError -> colors.errorIndicatorColor; focused -> colors.focusedIndicatorColor; else -> colors.unfocusedIndicatorColor }
    val container = when { !enabled -> colors.disabledContainerColor; isError -> colors.errorContainerColor; focused -> colors.focusedContainerColor; else -> colors.unfocusedContainerColor }
    val foreground = when { !enabled -> colors.disabledTextColor; isError -> colors.errorTextColor; focused -> colors.focusedTextColor; else -> colors.unfocusedTextColor }
    val labelColor = when { !enabled -> colors.disabledLabelColor; isError -> colors.errorLabelColor; focused -> colors.focusedLabelColor; else -> MaterialTheme.colorScheme.secondary }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val invalid = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa") "مقدار نامعتبر" else "Invalid input"
    Column(modifier) {
        CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
            BasicTextField(
                value = value, onValueChange = onValueChange, enabled = enabled, readOnly = readOnly,
                textStyle = textStyle.merge(TextStyle(color = foreground)),
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                singleLine = singleLine, maxLines = maxLines, minLines = minLines,
                interactionSource = source, visualTransformation = visualTransformation,
                cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
                onTextLayout = { onTextLayout?.invoke(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .background(container, shape).border(1.dp, border, shape)
                    .semantics { if (isError) error(invalid) },
                decorationBox = { inner ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        leadingIcon?.invoke()
                        Column(Modifier.weight(1f).heightIn(min = 44.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (label != null) CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold), LocalContentColor provides labelColor) { label() }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                prefix?.invoke()
                                Box(Modifier.weight(1f)) {
                                    if (value.isEmpty() && placeholder != null) CompositionLocalProvider(LocalContentColor provides colors.unfocusedPlaceholderColor) { placeholder() }
                                    inner()
                                }
                                suffix?.invoke()
                            }
                        }
                        trailingIcon?.invoke()
                    }
                }
            )
        }
        if (supportingText != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall, LocalContentColor provides if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) { supportingText() }
        }
    }
}
