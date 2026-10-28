package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import io.nekohasekai.sfa.compose.theme.GlassGlowPrimary
import io.nekohasekai.sfa.compose.theme.GlassShapes
import io.nekohasekai.sfa.compose.theme.GlassSurface
import io.nekohasekai.sfa.compose.theme.JellySpring
import io.nekohasekai.sfa.compose.theme.LiquidBackground
import io.nekohasekai.sfa.compose.theme.LocalHazeState
import io.nekohasekai.sfa.compose.theme.OnGlassPrimary
import io.nekohasekai.sfa.compose.theme.OnGlassSecondary
import io.nekohasekai.sfa.compose.theme.jellyClickable
import io.nekohasekai.sfa.compose.theme.liquidGlass
import io.nekohasekai.sfa.compose.theme.liquidGlassStrong
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.delay

/**
 * Floating service status bar shown at the bottom of the dashboard while the
 * core is running.
 *
 * When a [LocalHazeState] is provided by the root (see MainActivity), the bar
 * applies a real hardware-accelerated backdrop blur of the page content behind
 * it so cards / text do not show through clearly. Falls back to the ordinary
 * translucent glass fill when Haze is unavailable.
 *
 * @param enableBackdropBlur when true and [LocalHazeState] is non-null, apply Haze blur under the glass.
 * @param blurRadius radius of the backdrop blur (default 20 dp, slightly lighter than the nav bar).
 */
@Composable
fun ServiceStatusBar(
    visible: Boolean,
    serviceStatus: Status,
    startTime: Long?,
    groupsCount: Int,
    hasGroups: Boolean,
    onGroupsClick: () -> Unit,
    connectionsCount: Int,
    onConnectionsClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableBackdropBlur: Boolean = true,
    blurRadius: Dp = 20.dp,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val hazeState = LocalHazeState.current
        val useRealBlur = enableBackdropBlur && hazeState != null

        // Compact style suited to the floating status bar: moderate radius, low noise,
        // dark tint that keeps the glass readable while still revealing a frosted
        // impression of the page behind.
        val statusBlurStyle = remember(blurRadius) {
            HazeBlurStyle {
                this.blurRadius(blurRadius)
                noiseFactor(0.06f)
                backgroundColor(LiquidBackground)
                colorEffects(
                    listOf(
                        HazeColorEffect.tint(Color(0xFF05080F).copy(alpha = 0.42f)),
                    ),
                )
            }
        }

        val barModifier = if (useRealBlur && hazeState != null) {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .hazeBlur(
                    input = HazeInput.Sources(hazeState),
                    style = statusBlurStyle,
                )
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        }

        // Dark glass bar matching preference bottom-nav style
        GlassSurface(
            modifier = barModifier,
            shape = GlassShapes.statusBar,
            elevated = true,
            strong = true,
            darkGlass = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusItem(
                    text = when (serviceStatus) {
                        Status.Starting -> stringResource(R.string.status_starting)
                        Status.Started -> stringResource(R.string.status_started)
                        Status.Stopping -> stringResource(R.string.status_stopping)
                        else -> ""
                    },
                    modifier = Modifier.weight(1f),
                )

                if (serviceStatus == Status.Started) {
                    GlassChip(
                        onClick = onConnectionsClick,
                        text = connectionsCount.toString(),
                        selected = false,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Cable,
                                contentDescription = stringResource(R.string.title_connections),
                                modifier = Modifier.size(18.dp),
                                tint = OnGlassPrimary,
                            )
                        },
                    )
                }

                if (hasGroups) {
                    GlassChip(
                        onClick = onGroupsClick,
                        text = groupsCount.toString(),
                        selected = false,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.title_groups),
                                modifier = Modifier.size(18.dp),
                                tint = OnGlassPrimary,
                            )
                        },
                    )
                }

                // Stop = stronger selected-style glass
                GlassChip(
                    onClick = onStopClick,
                    text = null,
                    selected = true,
                    leading = {
                        if (startTime != null) {
                            UptimeText(startTime = startTime)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.stop),
                            modifier = Modifier.size(18.dp),
                            tint = OnGlassPrimary,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun GlassChip(
    onClick: () -> Unit,
    text: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
) {
    val glassModifier = if (selected) {
        Modifier.liquidGlassStrong(
            shape = GlassShapes.chip,
            glowColor = GlassGlowPrimary,
            darkGlass = false,
        )
    } else {
        Modifier.liquidGlass(
            shape = GlassShapes.chip,
            elevated = true,
            strong = false,
            glowColor = GlassGlowPrimary,
            darkGlass = false,
        )
    }

    Row(
        // jellyClickable BEFORE the glass so the chip's glass body squishes, not only its content.
        modifier = modifier
            .jellyClickable(
                pressedScale = 0.84f,
                pressedStretchX = 1.12f,
                pressedSquashY = 0.88f,
                springSpec = JellySpring,
                onClick = onClick,
            )
            .then(glassModifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading?.invoke()
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = OnGlassPrimary,
            )
            if (icon != null) Spacer(modifier = Modifier.width(4.dp))
        }
        icon?.invoke()
    }
}

@Composable
private fun StatusItem(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = OnGlassPrimary,
        modifier = modifier,
    )
}

@Composable
fun UptimeText(startTime: Long, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startTime) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val elapsedSeconds = ((currentTime - startTime) / 1000).coerceAtLeast(0)
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    val formattedTime =
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }

    Text(
        text = formattedTime,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = OnGlassSecondary,
        modifier = modifier,
    )
}
