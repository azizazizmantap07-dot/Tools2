package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import io.nekohasekai.sfa.compose.theme.GlassShapes
import io.nekohasekai.sfa.compose.theme.JellySpringSnappy
import io.nekohasekai.sfa.compose.theme.LiquidBackground
import io.nekohasekai.sfa.compose.theme.LocalHazeState
import io.nekohasekai.sfa.compose.theme.OnGlassPrimary
import io.nekohasekai.sfa.compose.theme.OnGlassTertiary
import io.nekohasekai.sfa.compose.theme.jellyClickable
import io.nekohasekai.sfa.compose.theme.liquidGlass
import kotlin.math.abs
import kotlin.math.min

/**
 * One tab of [GlassNavigationBar].
 *
 * @param icon receives `selected` so the caller can swap outlined/filled icons and add badges.
 */
class GlassNavBarItem(
    val label: String,
    val icon: @Composable (selected: Boolean) -> Unit,
    val onClick: () -> Unit,
)

private val NavItemHeight = 58.dp
private val NavBarInnerPadding = 6.dp
private val NavLensShape = RoundedCornerShape(22.dp) // GlassShapes.navBar (28dp) - inner padding (6dp)

/**
 * Floating liquid-glass bottom navigation, drop-in replacement for Material3 `NavigationBar`.
 *
 * - Rounded dark glass container with the same rim / shine / glow as every other glass surface
 *   (`Modifier.liquidGlass`), floating with margins like [ServiceStatusBar].
 * - When a [LocalHazeState] is provided by the root, the bar applies a real backdrop blur of the
 *   page content behind it (same Haze source used by [io.nekohasekai.sfa.compose.theme.GlassModalBottomSheet]).
 * - The selected tab is a lighter glass "lens" that slides between tabs with a jelly spring and
 *   stretches a little while it travels.
 * - Every tab gets the app-wide jelly press feedback.
 *
 * Window insets (gesture / 3-button navigation bar, display cutout) are handled here because
 * `Scaffold` does not pad its `bottomBar` when the app is edge-to-edge.
 *
 * @param selectedIndex index of the active tab, or -1 when the current route is not a tab
 *   (the lens fades out and stays where it was).
 * @param enableBackdropBlur when true and [LocalHazeState] is non-null, apply Haze blur under the glass.
 * @param blurRadius radius of the backdrop blur (default 22 dp, lighter than sheets).
 */
@Composable
fun GlassNavigationBar(
    items: List<GlassNavBarItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    /**
     * When true and a [LocalHazeState] is provided by the root, the bar samples the
     * marked page content and applies a real backdrop blur under the liquid-glass fill.
     */
    enableBackdropBlur: Boolean = true,
    blurRadius: Dp = 22.dp,
) {
    if (items.isEmpty()) return

    // Remember the last real tab so the lens fades out in place instead of sliding to tab 0.
    val lastTab = remember { IntArray(1) }
    val hasSelection = selectedIndex in items.indices
    if (hasSelection) lastTab[0] = selectedIndex
    val shownIndex = lastTab[0].coerceIn(0, items.lastIndex)

    val position by animateFloatAsState(
        targetValue = shownIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 380f),
        label = "glassNavLensPosition",
    )
    val lensAlpha by animateFloatAsState(
        targetValue = if (hasSelection) 1f else 0f,
        label = "glassNavLensAlpha",
    )

    val hazeState = LocalHazeState.current
    val useRealBlur = enableBackdropBlur && hazeState != null

    // Compact style suited to the floating nav bar: moderate radius, low noise,
    // dark tint that keeps the glass readable while still revealing the page behind.
    val navBlurStyle = remember(blurRadius) {
        HazeBlurStyle {
            this.blurRadius(blurRadius)
            noiseFactor(0.06f)
            backgroundColor(LiquidBackground)
            colorEffects(
                listOf(
                    HazeColorEffect.tint(Color(0xFF05080F).copy(alpha = 0.40f)),
                ),
            )
        }
    }

    val glassModifier = if (useRealBlur && hazeState != null) {
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = navBlurStyle,
            )
            .liquidGlass(
                shape = GlassShapes.navBar,
                elevated = true,
                strong = true,
            )
    } else {
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .liquidGlass(
                shape = GlassShapes.navBar,
                elevated = true,
                strong = true,
            )
    }

    Box(
        modifier = modifier.then(glassModifier),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NavBarInnerPadding),
        ) {
            val itemWidth = maxWidth / items.size

            // Sliding glass lens behind the tabs.
            Box(
                modifier = Modifier
                    .offset { IntOffset((itemWidth * position).roundToPx(), 0) }
                    .width(itemWidth)
                    .height(NavItemHeight)
                    .graphicsLayer {
                        alpha = lensAlpha
                        // Liquid stretch: widest right after a tap, relaxes as the lens arrives.
                        val travel = min(abs(position - shownIndex), 1f)
                        scaleX = 1f + travel * 0.16f
                        scaleY = 1f - travel * 0.08f
                    }
                    .liquidGlass(
                        shape = NavLensShape,
                        darkGlass = false,
                        backgroundColor = Color.White.copy(alpha = 0.16f),
                        glowColor = Color.White,
                    ),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    GlassNavItem(
                        item = item,
                        selected = hasSelection && index == selectedIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.GlassNavItem(
    item: GlassNavBarItem,
    selected: Boolean,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) OnGlassPrimary else OnGlassTertiary,
        label = "glassNavTint",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .height(NavItemHeight)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .jellyClickable(
                pressedScale = 0.92f,
                pressedStretchX = 1.04f,
                pressedSquashY = 0.96f,
                springSpec = JellySpringSnappy,
                onClick = item.onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            item.icon(selected)
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
