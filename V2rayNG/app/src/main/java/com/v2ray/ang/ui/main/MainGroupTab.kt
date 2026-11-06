package com.v2ray.ang.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ServersCache
import kotlinx.coroutines.flow.StateFlow

private val TAB_BAR_PADDING = 4.dp
private val TAB_SPACING = 2.dp
private val TAB_HEIGHT = 36.dp
private val SELECTED_TAB_MARGIN = 12.dp

/**
 * Subscription group tabs: a floating rounded bar whose selected tab is a tinted pill and whose
 * server count is a small badge.
 *
 * Natural label/count widths receive equal extra space, matching the reference flex:1 0 auto.
 * Wider sets keep their natural width and scroll, with the selected tab brought into view.
 */
@Composable
fun GroupTabBar(
    groups: List<GroupMapItem>,
    selectedTabIndex: Int,
    mainViewModel: MainViewModel,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = selectedTabIndex.coerceIn(0, groups.lastIndex)
    val colors = MaterialTheme.colorScheme
    // The plain surface color equals the screen background in dark mode, so the bar needs its own tone there.
    val dark = colors.surface.luminance() < 0.5f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 0.dp),
        shape = CircleShape,
        color = if (dark) Color.White.copy(alpha=.04f) else colors.surface,
        border = BorderStroke(1.dp, if(dark)Color.White.copy(alpha=.1f) else colors.outlineVariant.copy(alpha=.35f)),
        shadowElevation = 3.dp
    ) {
        BoxWithConstraints {
            val barPaddingPx = with(LocalDensity.current) { TAB_BAR_PADDING.roundToPx() }
            val availablePx = constraints.maxWidth - 2 * barPaddingPx
            var selectedBounds by remember { mutableStateOf(Rect.Zero) }
            val motion=com.v2ray.ang.ui.compose.LocalOmniAppearance.current.effects!=com.v2ray.ang.ui.compose.OmniEffects.OFF && android.animation.ValueAnimator.areAnimatorsEnabled()
            val spec=tween<Float>(if(motion)500 else 0,easing=CubicBezierEasing(.2f,.9f,.3f,1.15f))
            val thumbX by animateFloatAsState(selectedBounds.left,spec,label="tab-thumb-x")
            val thumbW by animateFloatAsState(selectedBounds.width,spec,label="tab-thumb-width")
            val accent=com.v2ray.ang.ui.compose.LocalOmniAppearance.current.skin.accent
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(TAB_BAR_PADDING)
            ) {
                androidx.compose.foundation.layout.Box {
                Canvas(Modifier.matchParentSize()) {
                    if(thumbW>0f) {
                        val at=Offset(thumbX,0f);val extent=Size(thumbW,size.height);val radius=CornerRadius(size.height/2f)
                        drawRoundRect(lerp(accent,Color.Black,.22f),at+Offset(0f,3.dp.toPx()),extent,radius)
                        drawRoundRect(Brush.linearGradient(listOf(accent,lerp(accent,Color.Black,.28f))),at,extent,radius)
                        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha=.5f),Color.Black.copy(alpha=.2f))),at,extent,radius,style=Stroke(1.dp.toPx()))
                    }
                }
                NaturalGrowingTabsLayout(availablePx = availablePx) {
                    groups.forEachIndexed { index, group ->
                        key(group.id) {
                            val serverFlow = remember(group.id, mainViewModel) {
                                mainViewModel.serversForGroup(group.id)
                            }
                            GroupTabItem(
                                group = group,
                                selected = index == selectedIndex,
                                serverFlow = serverFlow,
                                onClick = { onTabClick(index) },
                                onSelectedBounds = { selectedBounds=it }
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * Retains natural label/count widths; distributes spare space evenly, otherwise scrolls.
 */
@Composable
private fun NaturalGrowingTabsLayout(
    availablePx: Int,
    content: @Composable () -> Unit
) {
    Layout(content = content) { measurables, _ ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}

        val spacing = TAB_SPACING.roundToPx()
        val tabHeight = TAB_HEIGHT.roundToPx()
        val count = measurables.size
        val gaps = spacing * (count - 1)
        val natural = measurables.map { it.maxIntrinsicWidth(tabHeight) }

        val widths = when {
            availablePx <= 0 -> natural
            natural.sum() + gaps <= availablePx -> {
                val extra = (availablePx - gaps - natural.sum()) / count
                natural.map { it + extra }
            }
            else -> natural
        }

        val placeables = measurables.mapIndexed { i, measurable ->
            measurable.measure(Constraints.fixed(widths[i], tabHeight))
        }
        layout(widths.sum() + gaps, tabHeight) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + spacing
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTabItem(
    group: GroupMapItem,
    selected: Boolean,
    serverFlow: StateFlow<List<ServersCache>>,
    onClick: () -> Unit,
    onSelectedBounds: (Rect) -> Unit
) {
    val servers by serverFlow.collectAsStateWithLifecycle()
    // The default group (empty id) never showed a count.
    val count = if (group.id.isEmpty()) 0 else servers.size
    val colors = MaterialTheme.colorScheme
    val bringIntoView = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val marginPx = with(LocalDensity.current) { SELECTED_TAB_MARGIN.toPx() }

    // Bring the tab in with some room around it so the pill never ends up flush against the bar's
    // rounded edge; at the first and last tab the scroll stops at the bar's own inner padding.
    LaunchedEffect(selected, size, bounds) {
        if (selected && size != IntSize.Zero) {
            onSelectedBounds(bounds)
            bringIntoView.bringIntoView(
                Rect(-marginPx, 0f, size.width + marginPx, size.height.toFloat())
            )
        }
    }

    // One selectable node: name, count and selected state are announced together.
    Row(
        modifier = Modifier
            .bringIntoViewRequester(bringIntoView)
            .onSizeChanged { size = it }
            .onGloballyPositioned { coords -> val at=coords.positionInParent();bounds=Rect(at,Size(coords.size.width.toFloat(),coords.size.height.toFloat())) }
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group.remarks,
            style = MaterialTheme.typography.bodySmall.copy(fontSize=12.5.sp),
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) Color(0xFF181818) else colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        if (count > 0) {
            Spacer(Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.onPrimary.copy(alpha = 0.12f) else colors.onSurfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = if (selected) colors.onPrimary else colors.surface,
                    maxLines = 1
                )
            }
        }
    }
}
