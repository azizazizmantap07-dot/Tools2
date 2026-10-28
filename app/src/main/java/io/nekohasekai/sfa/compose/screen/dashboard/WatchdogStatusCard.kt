package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.compose.theme.GlassTonalButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.bg.RootState
import io.nekohasekai.sfa.bg.ServiceWatchdog
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Same green as the root indicator in the Service settings. */
private val OnColor = Color(0xFF4CAF50)

private enum class FeatureStatus { Active, Standby, NeedsRoot, Off }

private enum class Overall(val title: String, val subtitle: String) {
    Protected("Protected", "Watching the service in the background"),
    Standby("Standby", "Ready, waiting for the service to start"),
    Attention("Needs attention", "Airplane recovery cannot work without root"),
    Off("Off", "No watchdog is enabled"),
}

private enum class WatchItem { AutoRestart, Airplane, Root }

private enum class IconMotion { Spin, Float, Pulse }

/**
 * Dashboard indicator for the two watchdogs and for root access.
 *
 * - A header with a live shield (a soft pulse while everything is protected) and one summary
 *   chip that rolls the three states up into **Protected / Standby / Needs attention / Off**.
 * - Three tiles, one per feature. The icon of an active tile moves (restart spins, airplane
 *   floats, root pulses) so a glance tells what is alive; the tile color follows its state.
 * - Tap a tile to open its detail panel: what it does, and the control for it (switch for the
 *   two watchdogs, "Check again" for root). Tap it again to close.
 *
 * The two switches live in settings (not in flows), so they are read again whenever the
 * dashboard comes back to the foreground. Root follows [RootClient.rootState], which is
 * refreshed by itself every time the app is opened.
 */
@Composable
fun WatchdogStatusCard(serviceRunning: Boolean, modifier: Modifier = Modifier) {
    var autoRestartEnabled by remember { mutableStateOf(ServiceWatchdog.autoRestartEnabled) }
    var airplaneRecoveryEnabled by remember { mutableStateOf(Settings.airplaneRecoveryEnabled) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        autoRestartEnabled = ServiceWatchdog.autoRestartEnabled
        airplaneRecoveryEnabled = Settings.airplaneRecoveryEnabled
    }
    val rootState by RootClient.rootState.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var selected by remember { mutableStateOf<WatchItem?>(null) }
    var lastSelected by remember { mutableStateOf(WatchItem.AutoRestart) }
    LaunchedEffect(selected) { selected?.let { lastSelected = it } }

    val autoRestartStatus = when {
        !autoRestartEnabled -> FeatureStatus.Off
        serviceRunning -> FeatureStatus.Active
        else -> FeatureStatus.Standby
    }
    val airplaneStatus = when {
        !airplaneRecoveryEnabled -> FeatureStatus.Off
        rootState == RootState.Denied -> FeatureStatus.NeedsRoot
        serviceRunning -> FeatureStatus.Active
        else -> FeatureStatus.Standby
    }
    val overall = when {
        airplaneStatus == FeatureStatus.NeedsRoot -> Overall.Attention
        autoRestartStatus == FeatureStatus.Active || airplaneStatus == FeatureStatus.Active -> Overall.Protected
        autoRestartStatus == FeatureStatus.Standby || airplaneStatus == FeatureStatus.Standby -> Overall.Standby
        else -> Overall.Off
    }
    val overallColor by animateColorAsState(
        targetValue = overallColor(overall),
        animationSpec = tween(400),
        label = "overallColor",
    )

    fun toggle(item: WatchItem) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        selected = if (selected == item) null else item
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        elevated = true,
        glowColor = GlassGlowAccent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(overallColor.copy(alpha = 0.14f), Color.Transparent),
                    ),
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Header(overall = overall, color = overallColor)

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val autoColor = featureColor(autoRestartStatus)
                    StatusTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.RestartAlt,
                        label = "Auto restart",
                        status = featureText(autoRestartStatus),
                        color = autoColor,
                        motion = IconMotion.Spin,
                        live = autoRestartStatus == FeatureStatus.Active,
                        selected = selected == WatchItem.AutoRestart,
                        onClick = { toggle(WatchItem.AutoRestart) },
                    )
                    StatusTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.AirplanemodeActive,
                        label = "Airplane",
                        status = featureText(airplaneStatus),
                        color = featureColor(airplaneStatus),
                        motion = IconMotion.Float,
                        live = airplaneStatus == FeatureStatus.Active,
                        selected = selected == WatchItem.Airplane,
                        onClick = { toggle(WatchItem.Airplane) },
                    )
                    StatusTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.AdminPanelSettings,
                        label = "Root",
                        status = rootText(rootState),
                        color = rootColor(rootState),
                        motion = IconMotion.Pulse,
                        live = rootState == RootState.Granted || rootState == RootState.Checking,
                        selected = selected == WatchItem.Root,
                        onClick = { toggle(WatchItem.Root) },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        DetailPanel(
                            item = selected ?: lastSelected,
                            autoRestartEnabled = autoRestartEnabled,
                            airplaneEnabled = airplaneRecoveryEnabled,
                            rootState = rootState,
                            onAutoRestartChange = { checked ->
                                autoRestartEnabled = checked
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch(Dispatchers.IO) { ServiceWatchdog.autoRestartEnabled = checked }
                            },
                            onAirplaneChange = { checked ->
                                airplaneRecoveryEnabled = checked
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch(Dispatchers.IO) {
                                    Settings.airplaneRecoveryEnabled = checked
                                    // Turning it on is the moment root is needed: ask right away,
                                    // like the Service settings screen does.
                                    if (checked && RootClient.rootState.value != RootState.Granted) {
                                        runCatching { RootClient.checkRootAvailable() }
                                    }
                                }
                            },
                            onCheckRoot = {
                                scope.launch(Dispatchers.IO) { runCatching { RootClient.checkRootAvailable() } }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(overall: Overall, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShieldBadge(color = color, pulsing = overall == Overall.Protected)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Watchdog",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = overall.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = overall.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

/** Shield in a tinted circle; while [pulsing] a soft ring expands from it and fades out. */
@Composable
private fun ShieldBadge(color: Color, pulsing: Boolean) {
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "shieldPulse")
            val scale by transition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.35f,
                animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)),
                label = "scale",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)),
                label = "alpha",
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = color,
            )
        }
    }
}

@Composable
private fun StatusTile(
    icon: ImageVector,
    label: String,
    status: String,
    color: Color,
    motion: IconMotion,
    live: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(color, tween(300), label = "tileColor")
    val background by animateColorAsState(
        targetValue = tint.copy(alpha = if (selected) 0.22f else 0.12f),
        animationSpec = tween(300),
        label = "tileBackground",
    )
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) tint else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MotionIcon(icon = icon, tint = tint, motion = motion, live = live)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Icon that moves only while [live]; a static icon costs nothing, so no transition otherwise. */
@Composable
private fun MotionIcon(icon: ImageVector, tint: Color, motion: IconMotion, live: Boolean) {
    if (!live) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = tint)
        return
    }
    val transition = rememberInfiniteTransition(label = "tileMotion")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motion == IconMotion.Spin) -360f else 0f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)),
        label = "spin",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave",
    )
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .size(26.dp)
            .graphicsLayer {
                when (motion) {
                    IconMotion.Spin -> rotationZ = spin
                    IconMotion.Float -> translationY = (wave - 0.5f) * 6.dp.toPx()
                    IconMotion.Pulse -> {
                        val s = 1f + 0.14f * wave
                        scaleX = s
                        scaleY = s
                    }
                }
            },
        tint = tint,
    )
}

@Composable
private fun DetailPanel(
    item: WatchItem,
    autoRestartEnabled: Boolean,
    airplaneEnabled: Boolean,
    rootState: RootState,
    onAutoRestartChange: (Boolean) -> Unit,
    onAirplaneChange: (Boolean) -> Unit,
    onCheckRoot: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp),
    ) {
        val (title, description) = when (item) {
            WatchItem.AutoRestart ->
                "Auto restart" to
                    "Starts sing-box again if Android kills it or it stops by itself. " +
                    "Pauses after repeated crashes so it never loops forever."

            WatchItem.Airplane ->
                "Airplane recovery" to
                    "Resets the mobile radio through root when every URL test keeps failing. " +
                    "Needs root and a urltest group in the config."

            WatchItem.Root ->
                "Root access" to
                    "Needed by airplane recovery. It is checked each time the app opens."
        }
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            when (item) {
                WatchItem.AutoRestart -> {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = autoRestartEnabled, onCheckedChange = onAutoRestartChange)
                }

                WatchItem.Airplane -> {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = airplaneEnabled, onCheckedChange = onAirplaneChange)
                }

                WatchItem.Root -> {
                    Text(rootText(rootState), style = MaterialTheme.typography.bodyMedium)
                    if (rootState == RootState.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        GlassTonalButton(onClick = onCheckRoot) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun overallColor(overall: Overall): Color = when (overall) {
    Overall.Protected -> OnColor
    Overall.Standby -> MaterialTheme.colorScheme.primary
    Overall.Attention -> MaterialTheme.colorScheme.error
    Overall.Off -> MaterialTheme.colorScheme.outline
}

@Composable
private fun featureColor(status: FeatureStatus): Color = when (status) {
    FeatureStatus.Active -> OnColor
    FeatureStatus.Standby -> MaterialTheme.colorScheme.primary
    FeatureStatus.NeedsRoot -> MaterialTheme.colorScheme.error
    FeatureStatus.Off -> MaterialTheme.colorScheme.outline
}

private fun featureText(status: FeatureStatus): String = when (status) {
    FeatureStatus.Active -> "On"
    FeatureStatus.Standby -> "Standby"
    FeatureStatus.NeedsRoot -> "Needs root"
    FeatureStatus.Off -> "Off"
}

@Composable
private fun rootColor(state: RootState): Color = when (state) {
    RootState.Granted -> OnColor
    RootState.Checking -> MaterialTheme.colorScheme.primary
    RootState.Denied -> MaterialTheme.colorScheme.error
    RootState.Unknown -> MaterialTheme.colorScheme.outline
}

private fun rootText(state: RootState): String = when (state) {
    RootState.Granted -> "Granted"
    RootState.Checking -> "Checking…"
    RootState.Denied -> "Not granted"
    RootState.Unknown -> "Not checked"
}
