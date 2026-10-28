package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.compose.theme.GlassPopup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import io.nekohasekai.sfa.compose.theme.GlassOutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import io.nekohasekai.sfa.compose.theme.GlassShapes
import io.nekohasekai.sfa.compose.theme.OnGlassPrimary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.GlassDropdownMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClashModeCard(modes: List<String>, selectedMode: String, onModeSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        elevated = true,
        glowColor = GlassGlowAccent,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val textMeasurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.labelLarge
                val density = LocalDensity.current

                val totalTextWidth = remember(modes, textStyle, density) {
                    modes.sumOf { mode ->
                        textMeasurer.measure(mode, textStyle).size.width
                    }
                }
                val buttonPadding = with(density) { (24.dp * modes.size + 8.dp * (modes.size - 1).coerceAtLeast(0)).roundToPx() }
                val estimatedWidth = totalTextWidth + buttonPadding
                val availableWidth = with(density) { maxWidth.roundToPx() }

                val useDropdown = estimatedWidth > availableWidth

                if (useDropdown) {
                    ModeDropdown(
                        modes = modes,
                        selectedMode = selectedMode,
                        onModeSelected = onModeSelected,
                    )
                } else {
                    // Pilihan mode: deretan pill kaca; yang terpilih bertint aksen.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        modes.forEach { mode ->
                            GlassOutlinedButton(
                                onClick = { onModeSelected(mode) },
                                modifier = Modifier.weight(1f),
                                shape = GlassShapes.pill,
                                selected = mode == selectedMode,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = mode,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeDropdown(modes: List<String>, selectedMode: String, onModeSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        GlassCard(
            onClick = { expanded = true },
            shape = GlassShapes.card,
            elevated = false,
            glowColor = GlassGlowAccent,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedMode,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        if (mode == selectedMode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}
