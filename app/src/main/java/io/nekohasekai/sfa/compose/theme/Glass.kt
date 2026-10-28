package io.nekohasekai.sfa.compose.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.HazeProgressive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.math.tanh
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/**
 * Liquid Glass + Extreme Jelly + Super-smooth Scroll system
 *
 * - High transparency, double specular rim, top shine (no coloured halo behind the glass)
 * - Extreme kenyal press (strong scale + stretch/squash + bouncy spring)
 * - Scroll-linked liquid motion (scale, fade, parallax, tilt) – very smooth
 */

// ─────────────────────────────────────────────────────────────
// Extreme jelly spring specs – more kenyal, still smooth
// ─────────────────────────────────────────────────────────────

/*
 * These are the RELEASE springs: they decide how the glass puffs back up and wobbles after a
 * press. Low damping = big, round overshoot ("chubby"); low stiffness = slow, floaty, smooth
 * wobble instead of a fast buzz. The press-in motion is derived from them inside [jellyTouch] with
 * a stiffer, better damped spring, so the squish lands quickly and cleanly and only the release
 * wobbles.
 */

/**
 * Main release spring – first overshoot ≈ 37 % of the press depth, a few soft wobbles.
 * Feels like soft jelly / iOS liquid glass feedback.
 */
val JellySpring = spring<Float>(
    dampingRatio = 0.30f,
    stiffness = 250f,
)

/** Extra soft for large cards / bars */
val JellySpringSoft = spring<Float>(
    dampingRatio = 0.34f,
    stiffness = 230f,
)

/**
 * Cards / tiles: livelier than [JellySpringSoft] so the release wobble is clearly visible on
 * large surfaces (first overshoot ≈ 40 % of the press depth).
 */
val JellySpringCard = spring<Float>(
    dampingRatio = 0.28f,
    stiffness = 260f,
)

/** Snappy but still very kenyal for small controls */
val JellySpringSnappy = spring<Float>(
    dampingRatio = 0.30f,
    stiffness = 330f,
)

/** Ultra-smooth spring for scroll-driven values (almost no overshoot) */
val ScrollSpring = spring<Float>(
    dampingRatio = 0.86f,          // critically-ish damped = super smooth
    stiffness = 200f,              // gentle follow
)

/** Slightly livelier scroll spring when you want a hint of bounce */
val ScrollSpringLively = spring<Float>(
    dampingRatio = 0.72f,
    stiffness = 240f,
)

// Extreme default deformation amounts
private const val EXTREME_PRESS_SCALE = 0.86f
private const val EXTREME_STRETCH_X = 1.10f
private const val EXTREME_SQUASH_Y = 0.90f

/** A quick tap keeps the squish at least this long so it can be seen (and reaches full depth). */
private const val JELLY_MIN_PRESS_MS = 120L

// Chubby deformation model used by [jellyTouch] ---------------------------------------------------

/** Shallow presses (cards, rows) are squashed this much deeper; deep presses (buttons) are not. */
private const val JELLY_SQUASH_BOOST = 0.40f

/** Squash depth at which [JELLY_SQUASH_BOOST] has faded out completely. */
private const val JELLY_DEEP_SQUASH = 0.26f

/** Hard limit for the vertical squash so nothing ever collapses. */
private const val JELLY_MAX_SQUASH = 0.34f

/**
 * Share of the vertical squash that comes back as sideways bulge. 0 = flat squish, 1 = perfectly
 * volume preserving. Around 0.5 reads as a plump, rounded pudding instead of a shrinking card.
 */
private const val JELLY_BULGE = 0.55f

/**
 * A surface never grows beyond its layout box by more than this (dp, in total, so half of it per
 * side) in either direction. Keeps full-width cards on screen and keeps the overshoot inside the
 * small padding the lists leave around every item, so the rim is never cut by a parent.
 */
private const val JELLY_MAX_BULGE_DP = 12f

/** Press-in spring: quick and well damped so the squish is smooth, with only a hint of overshoot. */
private const val JELLY_PRESS_DAMPING = 0.58f

/** Release after a scroll / drag took over the touch: calm, no wobble while the list is moving. */
private const val JELLY_CANCEL_DAMPING = 0.80f

private const val SOFT_PRESS_SCALE = 0.93f
private const val SOFT_STRETCH_X = 1.04f
private const val SOFT_SQUASH_Y = 0.96f

private const val SNAPPY_PRESS_SCALE = 0.84f
private const val SNAPPY_STRETCH_X = 1.12f
private const val SNAPPY_SQUASH_Y = 0.88f

// ─────────────────────────────────────────────────────────────
// Jelly press modifiers
// ─────────────────────────────────────────────────────────────

fun Modifier.jellyPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = EXTREME_PRESS_SCALE,
    pressedStretchX: Float = EXTREME_STRETCH_X,
    pressedSquashY: Float = EXTREME_SQUASH_Y,
    springSpec: androidx.compose.animation.core.SpringSpec<Float> = JellySpring,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = springSpec,
        label = "jellyScale",
    )
    val stretchX by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedStretchX else 1f,
        animationSpec = springSpec,
        label = "jellyStretchX",
    )
    val squashY by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedSquashY else 1f,
        animationSpec = springSpec,
        label = "jellySquashY",
    )

    this.graphicsLayer {
        scaleX = scale * stretchX
        scaleY = scale * squashY
        transformOrigin = TransformOrigin.Center
    }
}

/**
 * Press-through jelly: the node squishes the moment a finger lands on it and springs back with a
 * wobble on release. It only *observes* touches (nothing is consumed), so a `clickable`, `Button`,
 * `Switch` or a scrolling parent keeps working exactly as before.
 *
 * Why this exists instead of reading `collectIsPressedAsState()` from a `clickable`: inside any
 * scrollable container (every screen of this app) Compose delays the press state by ~100 ms, so a
 * normal tap pressed and released in the same instant and the deformation never showed.
 * Here the squish starts on touch-down, and a quick tap is held for [minPressMillis] so it is
 * actually visible. A touch that turns into a scroll/drag springs back immediately (calmly, without
 * the wobble, so lists do not jiggle while they scroll).
 *
 * Shape of the motion: pressing squashes the surface vertically and bulges it sideways (chubby,
 * roughly volume preserving). Releasing lets [springSpec] overshoot the other way: the surface
 * puffs up taller and narrower than normal, then wobbles to rest, with X and Y slightly out of
 * phase so the wobble rolls instead of pulsing. [pressedScale] / [pressedSquashY] set how deep the
 * press is; [pressedStretchX] only nudges the sideways bulge.
 *
 * ORDER MATTERS: put it **before** `liquidGlass` / `background` / `border` (and before `padding`)
 * so the glass itself deforms, not just the content inside it, and so the touch area stays at the
 * original size while the visual is squished.
 *
 * @param skipWhenChildConsumes true for static surfaces: if a child (button, switch, clickable
 *   row) claims the touch, this surface stays still so two things never squish at once.
 */
fun Modifier.jellyTouch(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    pressedStretchX: Float = 1.02f,
    pressedSquashY: Float = 0.98f,
    springSpec: androidx.compose.animation.core.SpringSpec<Float> = JellySpringCard,
    skipWhenChildConsumes: Boolean = false,
    minPressMillis: Long = JELLY_MIN_PRESS_MS,
): Modifier = composed {
    val touching = remember { mutableStateOf(false) }
    val cancelled = remember { BooleanArray(1) }
    val startedAt = remember { LongArray(1) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(touching.value) {
        if (touching.value) {
            shown = true
        } else if (shown) {
            if (!cancelled[0]) {
                val held = SystemClock.uptimeMillis() - startedAt[0]
                if (held < minPressMillis) delay(minPressMillis - held)
            }
            shown = false
        }
    }

    // Reading `touching` here (not only `shown`) starts the squish one recomposition earlier, so
    // it follows the finger more directly. `shown` keeps it pressed for the minimum hold time
    // after a quick tap. A cancelled touch (scroll / drag) lets go at once.
    val pressed = touching.value || (shown && !cancelled[0])

    // X and Y use slightly different stiffness so the release wobble alternates between
    // "wide + flat" and "narrow + tall" instead of pulsing uniformly.
    val releaseX = springSpec
    val releaseY = remember(springSpec) {
        spring<Float>(springSpec.dampingRatio, springSpec.stiffness * 1.2f)
    }
    // Press-in: stiffer and better damped than the release spring, so the squish arrives fast and
    // smooth. The wobble is saved for the release.
    val pressX = remember(springSpec) {
        spring<Float>(JELLY_PRESS_DAMPING, (springSpec.stiffness * 2.2f).coerceIn(480f, 900f))
    }
    val pressY = remember(springSpec) {
        spring<Float>(JELLY_PRESS_DAMPING, (springSpec.stiffness * 2.4f).coerceIn(520f, 960f))
    }
    val calmX = remember(springSpec) {
        spring<Float>(JELLY_CANCEL_DAMPING, springSpec.stiffness * 1.4f)
    }
    val calmY = remember(springSpec) {
        spring<Float>(JELLY_CANCEL_DAMPING, springSpec.stiffness * 1.5f)
    }
    val progressX by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = when {
            pressed -> pressX
            cancelled[0] -> calmX
            else -> releaseX
        },
        label = "jellyTouchX",
    )
    val progressY by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = when {
            pressed -> pressY
            cancelled[0] -> calmY
            else -> releaseY
        },
        label = "jellyTouchY",
    )

    // Deformation at full press. Vertical squash first (shallow presses get a deeper one so cards
    // read clearly), then a sideways bulge that is a share of that squash.
    val depthY = (1f - pressedScale * pressedSquashY).coerceAtLeast(0f)
    val boost = 1f + JELLY_SQUASH_BOOST * (1f - depthY / JELLY_DEEP_SQUASH).coerceIn(0f, 1f)
    val squashY = (depthY * boost).coerceAtMost(JELLY_MAX_SQUASH)
    val growX = (pressedScale * pressedStretchX - 1f) * 0.5f + JELLY_BULGE * squashY

    this
        .pointerInput(enabled, skipWhenChildConsumes) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (skipWhenChildConsumes) {
                    val first = awaitPointerEvent(PointerEventPass.Final)
                    val claimed = first.changes.firstOrNull { it.id == down.id }?.isConsumed ?: true
                    if (claimed) {
                        // A child owns this touch; just wait for it to end.
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Final)
                        } while (e.changes.any { it.id == down.id && it.pressed })
                        return@awaitEachGesture
                    }
                }
                startedAt[0] = SystemClock.uptimeMillis()
                cancelled[0] = false
                touching.value = true
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val p = change.position
                        if (p.x < 0f || p.y < 0f || p.x > size.width || p.y > size.height) {
                            cancelled[0] = true
                            break
                        }
                        // A scroll / drag took over this touch: let go right away.
                        if (change.isConsumed && change.position != change.previousPosition) {
                            cancelled[0] = true
                            break
                        }
                    }
                } finally {
                    touching.value = false
                }
            }
        }
        .graphicsLayer {
            // progress is 1 while pressed and overshoots below 0 on release: that is the puff
            // (taller + narrower than normal) before the surface settles.
            // Outward growth is capped in px on BOTH axes (tall cards would otherwise puff by many
            // dp), so the overshoot stays within the slack around the item.
            val maxGrow = JELLY_MAX_BULGE_DP.dp.toPx()
            var sx = 1f + growX * progressX
            if (size.width > 0f) sx = sx.coerceAtMost(1f + maxGrow / size.width)
            var sy = (1f - squashY * progressY).coerceAtLeast(0.5f)
            if (size.height > 0f) sy = sy.coerceAtMost(1f + maxGrow / size.height)
            scaleX = sx
            scaleY = sy
            transformOrigin = TransformOrigin.Center
        }
}

/**
 * Clickable with jelly feedback. The `clickable` sits OUTSIDE the deformation so the touch area
 * never shrinks under the finger (edge taps would otherwise cancel themselves).
 *
 * Call it before `liquidGlass` / `background` so the glass deforms too, e.g.
 * `Modifier.jellyClickable(onClick = …).liquidGlass(…).padding(…)`.
 */
fun Modifier.jellyClickable(
    enabled: Boolean = true,
    pressedScale: Float = EXTREME_PRESS_SCALE,
    pressedStretchX: Float = EXTREME_STRETCH_X,
    pressedSquashY: Float = EXTREME_SQUASH_Y,
    springSpec: androidx.compose.animation.core.SpringSpec<Float> = JellySpring,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    this
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
        .jellyTouch(
            enabled = enabled,
            pressedScale = pressedScale,
            pressedStretchX = pressedStretchX,
            pressedSquashY = pressedSquashY,
            springSpec = springSpec,
        )
}

/** Softer variant for large surfaces */
fun Modifier.jellyPressSoft(
    enabled: Boolean = true,
    onClick: () -> Unit = {},
): Modifier = jellyClickable(
    enabled = enabled,
    pressedScale = SOFT_PRESS_SCALE,
    pressedStretchX = SOFT_STRETCH_X,
    pressedSquashY = SOFT_SQUASH_Y,
    springSpec = JellySpringSoft,
    onClick = onClick,
)

// ─────────────────────────────────────────────────────────────
// Scroll intensity presets (per-screen control)
// ─────────────────────────────────────────────────────────────

/**
 * Named intensity levels so each screen can pick subtle vs aggressive
 * without magic numbers.
 *
 * Usage:
 *   Modifier.liquidScroll(listState, index, LiquidScrollIntensity.Extreme)
 *   GlassScrollCard(..., intensity = LiquidScrollIntensity.Extreme)
 */
object LiquidScrollIntensity {
    /** Very gentle – settings lists, dense text */
    const val Subtle = 0.55f
    /** Default balanced look */
    const val Normal = 1.0f
    /** Stronger scale / fade / tilt – dashboards, cards */
    const val Aggressive = 1.45f
    /** Maximum liquid drama */
    const val Extreme = 1.85f
}

/** Overscroll bounce spring – extra kenyal when pulled past edges */
val OverscrollSpring = spring<Float>(
    dampingRatio = 0.32f,
    stiffness = 260f,
)

// ─────────────────────────────────────────────────────────────
// Super-smooth scroll effects
// ─────────────────────────────────────────────────────────────

/**
 * Liquid scroll effect for an item inside a LazyColumn / LazyRow.
 *
 * @param intensity use [LiquidScrollIntensity] presets or any Float
 *        (0.5 = subtle, 1.0 = normal, 1.45 = aggressive, 1.85 = extreme)
 */
fun Modifier.liquidScrollEffect(
    listState: LazyListState,
    index: Int,
    intensity: Float = LiquidScrollIntensity.Extreme,
): Modifier = composed {
    val density = LocalDensity.current

    val rawProgress by remember(listState, index) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            val item = visible.find { it.index == index }

            if (item == null) {
                1f
            } else {
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val viewportCenter = (viewportStart + viewportEnd) / 2f
                val itemCenter = item.offset + item.size / 2f
                val distance = (itemCenter - viewportCenter).absoluteValue
                val halfViewport = ((viewportEnd - viewportStart) / 2f).coerceAtLeast(1f)
                (distance / halfViewport).coerceIn(0f, 1.25f)
            }
        }
    }

    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = ScrollSpring,
        label = "scrollProgress",
    )

    val t = (progress * intensity).coerceIn(0f, 1.25f)
    val eased = t * t * (3f - 2f * t) // smoothstep

    // Scale / fade stay punchy with intensity; translation and rotation are
    // deliberately softer so card rims do not leave the layout box and get
    // clipped by LazyColumn (which caused cut / jagged edges).
    val scale = 1f - eased * 0.08f * intensity
    val alpha = 1f - eased * 0.28f * intensity
    val translationY = eased * 6f * intensity.coerceAtMost(1.5f)
    val rotationZ = sin(eased * Math.PI.toFloat()) * 1.1f * intensity.coerceAtMost(1.5f)

    this.graphicsLayer {
        // Never clip transformed content – glass borders must stay intact
        clip = false
        // The fade must NOT render the card into an offscreen buffer. With the default strategy
        // any alpha < 1 does, and that buffer is exactly the card's layout box: whatever the jelly
        // press draws outside of it (the sideways bulge, the taller puff on release) is cut off in
        // straight lines. That is why edges were clipped only on cards that were not at the centre
        // of the list (they are the ones faded below alpha 1). ModulateAlpha fades each draw
        // instruction instead, so nothing is cropped.
        compositingStrategy = CompositingStrategy.ModulateAlpha
        scaleX = scale
        scaleY = scale
        this.alpha = alpha.coerceIn(0.40f, 1f)
        this.translationY = translationY
        this.rotationZ = rotationZ
        transformOrigin = TransformOrigin.Center
        cameraDistance = 14f * density.density
    }
}

fun Modifier.liquidScrollVelocity(
    listState: LazyListState,
    intensity: Float = LiquidScrollIntensity.Extreme,
): Modifier = composed {
    val rawFactor by remember(listState) {
        derivedStateOf {
            if (listState.isScrollInProgress) 1f else 0f
        }
    }

    val scrollActive by animateFloatAsState(
        targetValue = rawFactor,
        animationSpec = ScrollSpringLively,
        label = "scrollActive",
    )

    // Slight inward pulse while scrolling – never scale above 1 so glass
    // rims stay inside the item layout box and are not clipped by LazyColumn.
    val scaleBoost = 1f - scrollActive * 0.012f * intensity

    this.graphicsLayer {
        clip = false
        scaleX = scaleBoost
        scaleY = scaleBoost
    }
}

/**
 * Combined liquid scroll (position + velocity).
 * Pass [LiquidScrollIntensity] presets per screen.
 */
fun Modifier.liquidScroll(
    listState: LazyListState,
    index: Int,
    intensity: Float = LiquidScrollIntensity.Extreme,
): Modifier = this
    .liquidScrollEffect(listState, index, intensity)
    .liquidScrollVelocity(listState, intensity)

// ─────────────────────────────────────────────────────────────
// Overscroll bounce (extra kenyal at list edges)
// ─────────────────────────────────────────────────────────────

/**
 * Holds the overscroll offset (px) so the same bounce can be applied
 * to the list container and/or individual glass items.
 */
class LiquidOverscrollState internal constructor() {
    internal val offset = Animatable(0f)
    val value: Float get() = offset.value
}

@Composable
fun rememberLiquidOverscrollState(): LiquidOverscrollState =
    remember { LiquidOverscrollState() }

/**
 * Attach to the **LazyColumn / LazyRow parent** (or a wrapping Box).
 * Intercepts overscroll at top/bottom and drives a springy bounce.
 *
 * @param listState list being scrolled
 * @param state shared overscroll state (optional – create with rememberLiquidOverscrollState)
 * @param maxPull maximum visual pull in px before resistance hard-caps
 * @param resistance higher = stiffer rubber band (1.5–3.0 typical)
 */
fun Modifier.liquidOverscroll(
    listState: LazyListState,
    state: LiquidOverscrollState? = null,
    maxPull: Float = 120f,
    resistance: Float = 2.2f,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val internal = state ?: rememberLiquidOverscrollState()
    val density = LocalDensity.current

    val connection = remember(listState, maxPull, resistance) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If we are currently bounced and user scrolls back, consume to collapse bounce
                val current = internal.offset.value
                if (current != 0f && source == NestedScrollSource.UserInput) {
                    val delta = available.y
                    // Pulling opposite to bounce → reduce bounce
                    if ((current > 0f && delta < 0f) || (current < 0f && delta > 0f)) {
                        val consumed = delta.coerceIn(
                            if (current > 0f) -current else Float.NEGATIVE_INFINITY,
                            if (current < 0f) -current else Float.POSITIVE_INFINITY,
                        )
                        scope.launch {
                            internal.offset.snapTo(current + consumed)
                        }
                        return Offset(0f, consumed)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val dy = available.y
                if (dy == 0f) return Offset.Zero

                val atTop = !listState.canScrollBackward
                val atBottom = !listState.canScrollForward

                val shouldBounce =
                    (atTop && dy > 0f) || (atBottom && dy < 0f)

                if (!shouldBounce) return Offset.Zero

                // Rubber-band: diminishing returns via tanh
                val raw = internal.offset.value + dy / resistance
                val clamped = (tanh(raw / maxPull) * maxPull)
                scope.launch {
                    internal.offset.snapTo(clamped)
                }
                return Offset(0f, dy) // consume overscroll
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Let fling pass; bounce is handled on post-fling / release
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Spring back to 0 with kenyal overshoot
                internal.offset.animateTo(0f, OverscrollSpring)
                return Velocity.Zero
            }
        }
    }

    // Also settle when scroll stops without fling (finger lift at rest)
    val isScrolling = listState.isScrollInProgress
    androidx.compose.runtime.LaunchedEffect(isScrolling) {
        if (!isScrolling && internal.offset.value != 0f) {
            internal.offset.animateTo(0f, OverscrollSpring)
        }
    }

    this
        .nestedScroll(connection)
        .graphicsLayer {
            clip = false
            // Whole list content shifts with the bounce (no scale-up – expanding
            // past layout bounds was clipping glass card edges).
            translationY = internal.offset.value
            transformOrigin = TransformOrigin.Center
            cameraDistance = 12f * density.density
        }
}

/**
 * Apply the same overscroll bounce to an individual glass item
 * (extra jelly squash while the list is pulled past the edge).
 */
fun Modifier.liquidOverscrollItem(
    overscrollState: LiquidOverscrollState,
    maxPull: Float = 120f,
    intensity: Float = LiquidScrollIntensity.Extreme,
): Modifier = composed {
    val pull = overscrollState.value
    val ratio = (pull.absoluteValue / maxPull).coerceIn(0f, 1f) * intensity

    // Jelly squash while overscrolling – scale never exceeds 1 so rims stay
    // inside the item layout box and are not clipped by the list.
    val scaleX = (1f - ratio * 0.03f).coerceAtMost(1f)
    val scaleY = (1f - ratio * 0.05f).coerceAtMost(1f)

    this.graphicsLayer {
        clip = false
        this.scaleX = scaleX
        this.scaleY = scaleY
        // Items near the pulled edge shift a bit more
        translationY = pull * 0.08f * intensity.coerceAtMost(1.5f)
        transformOrigin = TransformOrigin.Center
    }
}

// ─────────────────────────────────────────────────────────────
// Core multi-layer glass visual
// ─────────────────────────────────────────────────────────────

/**
 * True while content is drawn inside a [GlassModalBottomSheet].
 *
 * [liquidGlass] reads it to switch non-popup, dark glass to the lighter "sheet card" look: a
 * notch more transparent than on the dashboard, softer rim and a much weaker top shine. A sheet
 * has a dark body of its own and a lot of text on it, so the dashboard recipe (bright rim, 26 %
 * white shine drawn over the content) reads as haze there. Popups ([GlassAlertDialog],
 * `popup = true`) always keep their solid fill and reset this flag.
 */
val LocalGlassSheetContext = compositionLocalOf { false }

/**
 * Optional [HazeState] provided by the root content so that [GlassModalBottomSheet]
 * can apply a real backdrop blur of the page behind the sheet.
 *
 * When non-null and [GlassModalBottomSheet.enableBackdropBlur] is true, the sheet
 * body samples the marked source via Haze and the upper scrim becomes a lighter
 * tint. The root must call `Modifier.hazeSource(state = hazeState)` on the main
 * content (dashboard / NavHost container).
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Core liquid-glass modifier matched to preference (Dark Emerald style).
 *
 * - Dark frosted fill for clear depth against wallpaper
 * - Double specular rim (outer gradient + inner highlight)
 * - Strong top shine + subtle bottom shadow
 * - Optional selected state with teal glow border (preference style)
 *
 * No platform elevation shadow is drawn on purpose. The fill is translucent, and a coloured
 * `Modifier.shadow` behind it showed up as a tinted rectangle behind every card: the halo is
 * cropped to the item's rectangular bounds as soon as a parent layer is offscreen (the scroll
 * effect fades items with `alpha < 1`) and it tints whatever the glass lets through. Depth now
 * comes only from the rim light, the inner highlight and the top shine / bottom shade below.
 *
 * [glowColor] is accepted only so the many existing call sites keep compiling; it no longer
 * draws anything.
 */
@Suppress("UNUSED_PARAMETER", "UnusedParameter")
fun Modifier.liquidGlass(
    shape: Shape = GlassShapes.card,
    elevated: Boolean = false,
    strong: Boolean = false,
    selected: Boolean = false,
    popup: Boolean = false,
    glowColor: Color = GlassGlowAccent,
    backgroundColor: Color = when {
        strong -> GlassSurfaceStrong
        elevated -> GlassSurfaceElevated
        else -> GlassSurface
    },
    darkGlass: Boolean = true,          // default dark glass for preference look
): Modifier = composed {
    // Only dark, non-popup glass switches look inside a sheet.
    val inSheet = LocalGlassSheetContext.current && !popup && darkGlass

    val fill = when {
        popup -> if (elevated || strong) GlassPopupElevated else GlassPopup
        inSheet -> when {
            strong || selected -> GlassSheetCardStrong
            elevated -> GlassSheetCardElevated
            else -> GlassSheetCard
        }
        darkGlass -> when {
            strong || selected -> GlassDarkStrong
            elevated -> GlassDarkElevated
            else -> GlassDark
        }
        else -> when {
            strong || selected -> GlassSurfaceStrong
            elevated -> GlassSurfaceElevated
            else -> backgroundColor
        }
    }

    val rimBrush = when {
        selected -> Brush.linearGradient(
            colors = listOf(
                GlassBorderSelected.copy(alpha = 0.95f),
                GlassAccentCyan.copy(alpha = 0.75f),
                GlassBorderSelected.copy(alpha = 0.55f),
                GlassAccentTeal.copy(alpha = 0.80f),
            ),
        )

        inSheet -> Brush.linearGradient(
            colors = listOf(
                GlassSheetRimLight,
                GlassSheetRimSoft,
                GlassSheetRimFaint,
                GlassSheetRimSoft,
            ),
        )

        else -> Brush.linearGradient(
            colors = listOf(
                GlassRimLight,
                GlassRimSoft,
                GlassBorderSubtle,
                GlassRimSoft,
            ),
        )
    }

    val innerHighlight = when {
        selected -> GlassAccentTeal.copy(alpha = 0.35f)
        inSheet -> GlassSheetInnerHighlight
        else -> GlassInnerHighlight
    }

    // Shine / shade strengths. The shine is drawn over the content, so inside a sheet it is kept
    // faint enough that headers and chip text are not greyed out.
    val shineTop = if (inSheet) 0.10f else 0.26f
    val shineUpper = if (inSheet) 0.035f else 0.10f
    val shadeLow = if (inSheet) 0.05f else 0.08f
    val shadeBottom = if (inSheet) 0.10f else 0.14f

    this
        .clip(shape)
        .background(fill, shape)
        // Outer specular / selected rim (preference 85% strength)
        .border(
            width = if (selected) 2.0.dp else 1.4.dp,
            brush = rimBrush,
            shape = shape,
        )
        // Inner highlight rim
        .border(
            width = 0.8.dp,
            color = innerHighlight,
            shape = shape,
        )
        // Top shine + subtle bottom depth (iOS liquid glass lighting)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = shineTop),
                        0.14f to Color.White.copy(alpha = shineUpper),
                        0.40f to Color.Transparent,
                        0.85f to Color.Black.copy(alpha = shadeLow),
                        1.0f to Color.Black.copy(alpha = shadeBottom),
                    ),
                ),
                size = size,
            )
        }
}

/**
 * Body of a [GlassModalBottomSheet]: a deep, dark, translucent glass panel in the same family
 * as the app background, so the sheet reads as part of the main screen and not as a lighter
 * grey-blue overlay.
 *
 * - Fill: dark navy fading to near-black, ~80-88 % opaque (the old popup fill was ~94 % and a
 *   lighter navy). Together with [GlassSheetScrim] the dashboard behind stays a faint shape and
 *   no longer competes with the sheet's text.
 * - The soft top light is drawn BEHIND the content (the shine of [liquidGlass] is drawn over it),
 *   so titles and cards are never veiled.
 * - A hairline rim that is brightest at the top edge and fades out towards the bottom.
 */
fun Modifier.liquidSheetGlass(shape: Shape = GlassShapes.sheet): Modifier = this
    .clip(shape)
    .background(
        brush = Brush.verticalGradient(colors = listOf(GlassSheetTop, GlassSheetBottom)),
        shape = shape,
    )
    .drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.White.copy(alpha = 0.08f),
                    0.10f to Color.White.copy(alpha = 0.025f),
                    0.30f to Color.Transparent,
                ),
            ),
            size = size,
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to GlassSheetRimLight,
                0.25f to GlassSheetRimFaint,
                1.0f to Color.Transparent,
            ),
        ),
        shape = shape,
    )

fun Modifier.liquidGlassStrong(
    shape: Shape = GlassShapes.card,
    glowColor: Color = GlassGlowAccent,
    darkGlass: Boolean = true,
    selected: Boolean = false,
): Modifier = liquidGlass(
    shape = shape,
    elevated = true,
    strong = true,
    selected = selected,
    glowColor = glowColor,
    darkGlass = darkGlass,
)

fun Modifier.glassBlur(radius: Float = 28f): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        this
    }
}

fun Modifier.glassShine(shape: Shape = GlassShapes.card): Modifier =
    this.background(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.20f),
                Color.White.copy(alpha = 0.04f),
                Color.Transparent,
            ),
        ),
        shape = shape,
    )

// ─────────────────────────────────────────────────────────────
// Glass composables with extreme jelly by default
// ─────────────────────────────────────────────────────────────

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    elevated: Boolean = false,
    strong: Boolean = false,
    selected: Boolean = false,
    popup: Boolean = false,
    darkGlass: Boolean = true,
    glowColor: Color = GlassGlowAccent,
    jelly: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Order is deliberate: clickable (fixed hit area) -> jelly deformation -> glass visuals ->
    // padding. Because the deformation sits *outside* liquidGlass, the whole glass body (fill, rim,
    // shine, shadow) squishes and wobbles, not only the content inside it.
    val pressModifier: Modifier = when {
        onClick != null && jelly -> Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .jellyTouch(
                pressedScale = 0.94f,
                pressedStretchX = 1.02f,
                pressedSquashY = 0.98f,
                springSpec = JellySpringCard,
            )

        onClick != null -> Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )

        // Static cards also react to a touch (gently), but stay still when a child
        // (button, switch, clickable row) takes the touch. Dialog/popup surfaces never do.
        jelly && !popup -> Modifier.jellyTouch(
            pressedScale = 0.98f,
            pressedStretchX = 1.005f,
            pressedSquashY = 0.99f,
            springSpec = JellySpringCard,
            skipWhenChildConsumes = true,
        )

        else -> Modifier
    }

    Box(
        modifier = modifier
            .then(pressModifier)
            .liquidGlass(
                shape = shape,
                elevated = elevated,
                strong = strong,
                selected = selected,
                popup = popup,
                glowColor = glowColor,
                darkGlass = darkGlass,
            )
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    elevated: Boolean = true,
    strong: Boolean = false,
    selected: Boolean = false,
    popup: Boolean = false,
    darkGlass: Boolean = true,
    glowColor: Color = GlassGlowAccent,
    jelly: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        elevated = elevated,
        strong = strong,
        selected = selected,
        popup = popup,
        darkGlass = darkGlass,
        glowColor = glowColor,
        jelly = jelly,
        onClick = onClick,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Glass card that reacts to scroll position – use inside LazyColumn items.
 *
 * @param intensity prefer [LiquidScrollIntensity] presets per screen
 * @param overscrollState optional – pass the same state used on the list’s
 *        [liquidOverscroll] so this card also jelly-deforms on edge pull
 */
@Composable
fun GlassScrollCard(
    listState: LazyListState,
    index: Int,
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    elevated: Boolean = true,
    intensity: Float = LiquidScrollIntensity.Extreme,
    overscrollState: LiquidOverscrollState? = null,
    jelly: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val scrollMod = modifier
        .liquidScroll(listState, index, intensity)
        .then(
            if (overscrollState != null) {
                Modifier.liquidOverscrollItem(overscrollState, intensity = intensity)
            } else {
                Modifier
            },
        )

    GlassCard(
        modifier = scrollMod,
        shape = shape,
        elevated = elevated,
        jelly = jelly,
        onClick = onClick,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strong: Boolean = false,
    glowColor: Color = GlassGlowBlue,
    jelly: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    // jelly / clickable first, glass after: the glass body deforms with the press.
    val press = if (jelly) {
        modifier.jellyClickable(
            enabled = enabled,
            pressedScale = SNAPPY_PRESS_SCALE,
            pressedStretchX = SNAPPY_STRETCH_X,
            pressedSquashY = SNAPPY_SQUASH_Y,
            springSpec = JellySpringSnappy,
            onClick = onClick,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    }
    val finalModifier = press.liquidGlass(
        shape = CircleShape,
        elevated = true,
        strong = strong,
        glowColor = glowColor,
    )

    Box(
        modifier = finalModifier,
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun GlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    glowColor: Color = GlassGlowPrimary,
    jelly: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val press = if (jelly) {
        modifier.jellyClickable(
            enabled = enabled,
            pressedScale = EXTREME_PRESS_SCALE,
            pressedStretchX = EXTREME_STRETCH_X,
            pressedSquashY = EXTREME_SQUASH_Y,
            springSpec = JellySpring,
            onClick = onClick,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    }
    val finalModifier = press.liquidGlass(
        shape = GlassShapes.pill,
        elevated = true,
        strong = selected,
        glowColor = glowColor,
    )

    Box(
        modifier = finalModifier.padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassShapes.button,
    strong: Boolean = false,
    jelly: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable () -> Unit,
) {
    val glassMod = Modifier.liquidGlass(
        shape = shape,
        elevated = true,
        strong = strong,
        backgroundColor = if (strong) GlassSurfaceStrong else GlassSurfaceHigh,
    )

    // The inner Button owns the click; jellyTouch only watches the touch, so the click always
    // fires and the whole glass body (deforming first, glass after) squishes.
    val pressMod = if (jelly) {
        Modifier.jellyTouch(
            enabled = enabled,
            pressedScale = EXTREME_PRESS_SCALE,
            pressedStretchX = EXTREME_STRETCH_X,
            pressedSquashY = EXTREME_SQUASH_Y,
            springSpec = JellySpring,
        )
    } else {
        Modifier
    }

    Box(modifier = modifier.then(pressMod).then(glassMod)) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = OnGlassPrimary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = OnGlassDisabled,
            ),
            contentPadding = contentPadding,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
            content = { content() },
        )
    }
}

@Composable
fun GlassPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassShapes.buttonLarge,
    jelly: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    // No elevation shadow / pink halo behind the button (same reason as [liquidGlass]).
    val visual = Modifier
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    SingBoxPrimaryLight.copy(alpha = 0.92f),
                    SingBoxPrimary.copy(alpha = 0.88f),
                ),
            ),
        )
        .border(
            width = 1.2.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.15f),
                ),
            ),
            shape = shape,
        )

    val pressMod = if (jelly) {
        Modifier.jellyTouch(
            enabled = enabled,
            pressedScale = 0.88f,
            pressedStretchX = 1.08f,
            pressedSquashY = 0.92f,
            springSpec = JellySpring,
        )
    } else {
        Modifier
    }

    Box(modifier = modifier.then(pressMod).then(visual)) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = OnGlassDisabled,
            ),
            contentPadding = contentPadding,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
            content = { content() },
        )
    }
}

/**
 * Pengganti `TextButton` (dialog, aksi inline): pill kaca netral tanpa bayangan tebal.
 * Warna label tetap aksen (primary) seperti TextButton lama; caller boleh menimpa warna
 * teksnya sendiri di dalam [content].
 */
@Composable
fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = GlassShapes.pill,
        tint = Color.Unspecified,
        strong = false,
        contentColor = MaterialTheme.colorScheme.primary,
        contentPadding = contentPadding,
        content = content,
        elevated = false,
    )
}

@Composable
fun GlassMaterialSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    elevated: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.liquidGlass(shape = shape, elevated = elevated),
        shape = shape,
        color = Color.Transparent,
        content = content,
    )
}


/**
 * Root liquid-glass wallpaper matched to preference (Dark Emerald).
 * Multi-layer ambient field so all GlassCards gain clear depth and
 * specular response against a rich dark background.
 */
@Composable
fun LiquidGlassBackground(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = if (darkTheme) LiquidBackground else LiquidBackgroundLight
    val mid = if (darkTheme) LiquidBackgroundSecondary else LiquidBackgroundLightSecondary
    val glowTeal = if (darkTheme) Color(0x222DD4BF) else Color(0x142DD4BF)
    val glowBlue = if (darkTheme) Color(0x1860A5FA) else Color(0x1460A5FA)
    val glowPink = if (darkTheme) Color(0x18E91E63) else Color(0x12E91E63)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                // Centres and radii are derived from the real size. `Brush.radialGradient` takes its
                // centre in pixels, so the old `Offset(0.18f, 0.15f)` etc. put all three glows
                // within a pixel of the top-left corner instead of spreading them over the screen.
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@drawBehind
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowTeal, Color.Transparent),
                        center = Offset(w * 0.18f, h * 0.15f),
                        radius = w * 0.88f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowBlue, Color.Transparent),
                        center = Offset(w * 0.82f, h * 0.28f),
                        radius = w * 1.02f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowPink, Color.Transparent),
                        center = Offset(w * 0.50f, h * 0.88f),
                        radius = w * 0.93f,
                    ),
                )
            }
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        mid.copy(alpha = 0.50f),
                        Color.Transparent,
                        mid.copy(alpha = 0.30f),
                    ),
                ),
            ),
    ) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────
// Popup / list-card building blocks
// ─────────────────────────────────────────────────────────────

/**
 * Glass card that lays its children out in a [Column].
 *
 * [GlassCard] / [GlassSurface] content is a [BoxScope], so several direct
 * children are drawn on top of each other. Use this for any card that holds
 * more than one row (e.g. a list of `ListItem`s) so they can never overlap.
 * Pass `popup = true` for dialogs: that uses the solid (≈94 % opaque) fill.
 * [onClick] makes the whole card tappable (jelly press); [selected] gives the
 * teal selected rim used for chosen tiles.
 */
@Composable
fun GlassListCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    elevated: Boolean = true,
    popup: Boolean = false,
    selected: Boolean = false,
    jelly: Boolean = true,
    onClick: (() -> Unit)? = null,
    glowColor: Color = GlassGlowAccent,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier,
        shape = shape,
        elevated = elevated,
        popup = popup,
        selected = selected,
        glowColor = glowColor,
        jelly = jelly,
        onClick = onClick,
        contentPadding = contentPadding,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Drop-in replacement for Material3 `AlertDialog` that renders as a solid
 * glass card (opaque navy fill + rim light + top shine) instead of a flat
 * Material surface. Same slots as `AlertDialog`. [containerColor] is accepted
 * only so existing call sites compile unchanged: the glass fill is fixed so
 * every popup in the app looks identical. [titleContentColor] and
 * [textContentColor] do override the text colors.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = GlassShapes.dialog,
    containerColor: Color = Color.Unspecified,
    titleContentColor: Color = OnGlassPrimary,
    textContentColor: Color = OnGlassSecondary,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        // A dialog opened from a sheet inherits the sheet flag; popups keep the solid popup look.
        CompositionLocalProvider(LocalGlassSheetContext provides false) {
            Column(
                modifier = modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .liquidGlass(
                        shape = shape,
                        elevated = true,
                        popup = true,
                    )
                    .padding(24.dp),
            ) {
                if (icon != null) {
                    Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.primary,
                            content = icon,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(
                                modifier = Modifier.align(
                                    if (icon != null) Alignment.CenterHorizontally else Alignment.Start,
                                ),
                            ) { title() }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(modifier = Modifier.weight(1f, fill = false)) { text() }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}

/**
 * Drop-in replacement for Material3 `DropdownMenu` with a solid glass
 * container and a soft rim. Uses the high-opacity [GlassPopupElevated] fill so
 * cards and text behind the menu are not readable through the glass (real
 * backdrop blur is not available across the Popup window boundary). 
 * [containerColor] stays overridable so existing call sites keep compiling unchanged.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = GlassShapes.chip,
    // Solid popup glass (~94 % opaque) so background content does not show through.
    // Real hardware backdrop blur cannot be applied to a Compose Popup / DropdownMenu
    // because it lives in a separate window; opacity is the correct substitute.
    containerColor: Color = GlassPopupElevated,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 12.dp,
    border: BorderStroke? = BorderStroke(1.dp, GlassPopupBorder),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

/**
 * Small pill drawn at the top of a [GlassModalBottomSheet]. It is rendered
 * inside the glass card (not by Material), so it sits on the glass fill.
 */
@Composable
fun GlassDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 12.dp)
            .size(width = 48.dp, height = 4.dp)
            .background(OnGlassPrimary.copy(alpha = 0.40f), CircleShape),
    )
}

/**
 * Drop-in replacement for Material3 `ModalBottomSheet` that renders the sheet
 * as a deep, dark, translucent glass panel ([liquidSheetGlass]) over a heavy dark
 * scrim ([GlassSheetScrim]). Everything inside is flagged with [LocalGlassSheetContext],
 * so glass cards on the sheet use the lighter, more transparent "sheet card" look and
 * stay consistent with the cards of the main dashboard while remaining readable.
 *
 * Why the glass lives in the content and not in [modifier]: Material applies
 * `modifier` *before* the drag offset, so a background set there would stay at
 * the top of the screen while the sheet slides. Material's own surface is made
 * transparent instead, and the glass card fills the sheet's content.
 *
 * The sheet is inset below the status bar (`WindowInsets.statusBars` on the
 * sheet modifier) so it never draws under or above the system status bar.
 * Navigation-bar insets are applied inside the glass card so content clears
 * the gesture/nav bar while the glass still fills the sheet body.
 *
 * Content colour defaults to [OnGlassPrimary] because the sheet fill is always
 * dark, regardless of the light/dark theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = GlassShapes.sheet,
    contentColor: Color = OnGlassPrimary,
    scrimColor: Color = GlassSheetScrim,
    /**
     * When true and a [LocalHazeState] is provided by the root, the sheet body
     * applies a real hardware-accelerated backdrop blur (via Haze) of the page
     * content marked with [hazeSource]. The upper scrim becomes a lighter tint
     * so the blurred page remains perceptible. Falls back to the classic heavy
     * scrim when Haze is unavailable.
     */
    enableBackdropBlur: Boolean = true,
    blurRadius: Dp = 32.dp,
    dragHandle: @Composable (() -> Unit)? = { GlassDragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val hazeState = LocalHazeState.current
    val useRealBlur = enableBackdropBlur && hazeState != null

    // Lighter tint when real blur is active so the blurred dashboard stays visible
    // through the upper scrim and through the translucent glass sheet body.
    val effectiveScrim = if (useRealBlur) {
        Color(0xB305080F) // ~70 % deep navy tint
    } else {
        scrimColor
    }

    // Tuned style for the liquid-glass sheet: strong blur, subtle noise, dark
    // emerald-compatible tint, and a mild progressive falloff toward the bottom
    // of the panel so the drag handle area feels sharper.
    val sheetBlurStyle = remember(blurRadius) {
        HazeBlurStyle {
            this.blurRadius(blurRadius)
            noiseFactor(0.08f)
            backgroundColor(LiquidBackground)
            colorEffects(
                listOf(
                    HazeColorEffect.tint(Color(0xFF05080F).copy(alpha = 0.45f)),
                ),
            )
            progressive(
                HazeProgressive.verticalGradient(
                    startIntensity = 1f,
                    endIntensity = 0.65f,
                ),
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // Keep the entire sheet (including glass fill) below the status bar.
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars),
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        scrimColor = effectiveScrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // The sheet fill is always dark, so content must resolve the dark
        // colour scheme too; otherwise `onSurface` / `onSurfaceVariant` text in a
        // sheet would be dark-on-dark while the system is in light mode.
        MaterialTheme(
            colorScheme = LiquidGlassDarkColorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            // Tells liquidGlass (GlassCard, GlassIconButton, chips ...) that it is drawn on a sheet,
            // so cards use the lighter, more transparent sheet look.
            CompositionLocalProvider(LocalGlassSheetContext provides true) {
                val glassModifier = if (useRealBlur && hazeState != null) {
                    Modifier
                        .fillMaxWidth()
                        .hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = sheetBlurStyle,
                        )
                        .liquidSheetGlass(shape)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .liquidSheetGlass(shape)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                }

                Column(modifier = glassModifier) {
                    if (dragHandle != null) {
                        Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            dragHandle()
                        }
                    }
                    content()
                }
            }
        }
    }
}

/** Opaque dark fill used by text inputs that sit on a popup glass card. */
private val GlassFieldFill = Color(0xFF0E1522)

/** Text-field colors for inputs inside glass dialogs / cards. */
@Composable
fun glassTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnGlassPrimary,
    unfocusedTextColor = OnGlassPrimary,
    disabledTextColor = OnGlassDisabled,
    focusedContainerColor = GlassFieldFill,
    unfocusedContainerColor = GlassFieldFill,
    disabledContainerColor = GlassFieldFill,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = GlassRimSoft,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = OnGlassTertiary,
    focusedPlaceholderColor = OnGlassTertiary,
    unfocusedPlaceholderColor = OnGlassTertiary,
)


// ─────────────────────────────────────────────────────────────
// Drop-in glass replacements for Material buttons
//
//   IconButton                    -> GlassIconButton
//   FloatingActionButton          -> GlassFloatingActionButton
//   ExtendedFloatingActionButton  -> GlassExtendedFloatingActionButton
//   Button                        -> GlassActionButton   (aksen / primary)
//   OutlinedButton                -> GlassOutlinedButton (netral, bisa `selected`)
//   FilledTonalButton             -> GlassTonalButton
//
// Semua memakai urutan modifier yang sama dengan GlassSurface:
// clickable -> jellyTouch -> liquidGlass, jadi seluruh badan kaca ikut squish saat ditekan.
// ─────────────────────────────────────────────────────────────

private const val GLASS_DISABLED_ALPHA = 0.45f
private const val GLASS_TINT_ALPHA = 0.55f

@Composable
private fun glassPressModifier(
    enabled: Boolean,
    pressedScale: Float,
    pressedStretchX: Float,
    pressedSquashY: Float,
    springSpec: androidx.compose.animation.core.SpringSpec<Float>,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return Modifier
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
        .jellyTouch(
            enabled = enabled,
            pressedScale = pressedScale,
            pressedStretchX = pressedStretchX,
            pressedSquashY = pressedSquashY,
            springSpec = springSpec,
        )
}

/**
 * Pengganti `IconButton`: lingkaran kaca 40dp dengan area sentuh minimal 48dp
 * (jarak antar tombol di top bar sama seperti IconButton Material).
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val press = glassPressModifier(
        enabled = enabled,
        pressedScale = SNAPPY_PRESS_SCALE,
        pressedStretchX = SNAPPY_STRETCH_X,
        pressedSquashY = SNAPPY_SQUASH_Y,
        springSpec = JellySpringSnappy,
        onClick = onClick,
    )
    val base = LocalContentColor.current
    val contentColor = if (enabled) base else base.copy(alpha = 0.38f)

    Box(
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).then(press),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .liquidGlass(shape = CircleShape, elevated = false),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        }
    }
}

/** Pengganti `FloatingActionButton`: kaca bulat 56dp dengan tint [containerColor]. */
@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable () -> Unit,
) {
    val press = glassPressModifier(
        enabled = true,
        pressedScale = 0.88f,
        pressedStretchX = 1.08f,
        pressedSquashY = 0.92f,
        springSpec = JellySpring,
        onClick = onClick,
    )
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
            .then(press)
            .liquidGlass(
                shape = shape,
                elevated = true,
                strong = true,
                glowColor = containerColor,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(containerColor.copy(alpha = GLASS_TINT_ALPHA)),
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/** Pengganti `ExtendedFloatingActionButton`: pil kaca dengan ikon + teks. */
@Composable
fun GlassExtendedFloatingActionButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val press = glassPressModifier(
        enabled = true,
        pressedScale = 0.92f,
        pressedStretchX = 1.04f,
        pressedSquashY = 0.95f,
        springSpec = JellySpring,
        onClick = onClick,
    )
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .then(press)
            .liquidGlass(
                shape = shape,
                elevated = true,
                strong = true,
                glowColor = containerColor,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(containerColor.copy(alpha = GLASS_TINT_ALPHA)),
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    icon()
                    text()
                }
            }
        }
    }
}

@Composable
private fun GlassButtonBase(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    tint: Color,
    strong: Boolean,
    contentColor: Color,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
    elevated: Boolean = true,
) {
    val press = glassPressModifier(
        enabled = enabled,
        pressedScale = 0.94f,
        pressedStretchX = 1.02f,
        pressedSquashY = 0.98f,
        springSpec = JellySpringCard,
        onClick = onClick,
    )
    val tinted = tint != Color.Unspecified
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else GLASS_DISABLED_ALPHA }
            .defaultMinSize(minHeight = 40.dp)
            .then(press)
            .liquidGlass(
                shape = shape,
                elevated = elevated,
                strong = strong || tinted,
                glowColor = if (tinted) tint else GlassGlowAccent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (tinted) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(tint.copy(alpha = GLASS_TINT_ALPHA)),
            )
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    modifier = Modifier.padding(contentPadding),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

/** Pengganti `Button`: kaca dengan tint aksen ([tint], default warna primary). */
@Composable
fun GlassActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassShapes.button,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        tint = tint,
        strong = true,
        contentColor = OnGlassPrimary,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Pengganti `OutlinedButton`: kaca netral. [selected] = true memberi tint aksen
 * (dipakai untuk tombol segmented seperti pilihan Local / Remote).
 */
@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassShapes.button,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
        strong = selected,
        contentColor = OnGlassPrimary,
        contentPadding = contentPadding,
        content = content,
    )
}

/** Pengganti `FilledTonalButton`. */
@Composable
fun GlassTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = GlassShapes.button,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        contentPadding = contentPadding,
        content = content,
    )
}
