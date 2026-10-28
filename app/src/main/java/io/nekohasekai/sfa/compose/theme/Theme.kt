package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Strengthened Liquid Glass color schemes.
 * Surfaces intentionally very translucent so liquidGlass modifiers
 * create clear depth and the preference-style frosted look.
 */
internal val LiquidGlassDarkColorScheme =
    darkColorScheme(
        primary = SingBoxPrimary,
        onPrimary = Color.White,
        primaryContainer = SingBoxPrimaryContainer,
        onPrimaryContainer = SingBoxPrimaryLight,
        secondary = SingBoxPrimaryLight,
        onSecondary = Color.White,
        tertiary = LogBlue,
        onTertiary = Color.White,
        background = LiquidBackground,
        onBackground = OnGlassPrimary,
        surface = LiquidBackgroundSecondary,
        onSurface = OnGlassPrimary,
        surfaceVariant = LiquidBackgroundTertiary,
        onSurfaceVariant = OnGlassSecondary,
        // Very translucent containers – critical for glass visibility
        surfaceContainer = GlassSurfaceElevated,
        surfaceContainerHigh = GlassSurfaceHigh,
        surfaceContainerHighest = GlassSurfaceStrong,
        surfaceContainerLow = GlassSurface,
        surfaceContainerLowest = Color.Transparent,
        outline = GlassBorder,
        outlineVariant = GlassBorderSubtle,
        error = ErrorRed,
        onError = Color.White,
        errorContainer = ErrorRed.copy(alpha = 0.18f),
        onErrorContainer = ErrorRed,
        inverseSurface = Color.White.copy(alpha = 0.12f),
        inverseOnSurface = Color.White,
        inversePrimary = SingBoxPrimaryLight,
        scrim = Color.Black.copy(alpha = 0.60f),
    )

private val LiquidGlassLightColorScheme =
    lightColorScheme(
        primary = SingBoxPrimary,
        onPrimary = Color.White,
        primaryContainer = SingBoxPrimaryContainer,
        onPrimaryContainer = SingBoxPrimaryDark,
        secondary = SingBoxPrimaryDark,
        onSecondary = Color.White,
        tertiary = LogBlue,
        onTertiary = Color.White,
        background = LiquidBackgroundLight,
        onBackground = Color(0xFF0F172A),
        surface = LiquidBackgroundLightSecondary,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFD0DAE8),
        onSurfaceVariant = Color(0xFF475569),
        surfaceContainer = Color.White.copy(alpha = 0.45f),
        surfaceContainerHigh = Color.White.copy(alpha = 0.60f),
        surfaceContainerHighest = Color.White.copy(alpha = 0.75f),
        surfaceContainerLow = Color.White.copy(alpha = 0.30f),
        surfaceContainerLowest = Color.Transparent,
        outline = Color(0xFF94A3B8),
        outlineVariant = Color(0xFFCBD5E1),
        error = ErrorRed,
        onError = Color.White,
        errorContainer = ErrorRed.copy(alpha = 0.12f),
        onErrorContainer = ErrorRed,
        inverseSurface = Color(0xFF1E293B),
        inverseOnSurface = Color.White,
        inversePrimary = SingBoxPrimaryLight,
        scrim = Color.Black.copy(alpha = 0.40f),
    )

@Composable
fun Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) LiquidGlassDarkColorScheme else LiquidGlassLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = {
            LiquidGlassBackground(darkTheme = darkTheme) {
                content()
            }
        },
    )
}
