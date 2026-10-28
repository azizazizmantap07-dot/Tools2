package io.nekohasekai.sfa.compose.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
// Liquid Glass Color System – matched to preference screenshots
// Dark Emerald / iOS-style frosted glass with strong specular rims
// ─────────────────────────────────────────────────────────────

// Primary accent (kept brand pink, used sparingly)
val SingBoxPrimary = Color(0xFFE91E63)
val SingBoxPrimaryDark = Color(0xFFAD1457)
val SingBoxPrimaryLight = Color(0xFFF48FB1)
val SingBoxPrimaryContainer = Color(0x40E91E63)

// Preference accent (Dark Emerald / teal used for selection glow)
val GlassAccentTeal = Color(0xFF2DD4BF)
val GlassAccentTealSoft = Color(0x662DD4BF)
val GlassAccentCyan = Color(0xFF22D3EE)

// ── Glass fills (tuned for visible frost like preference cards) ──
// Slightly higher opacity than pure glass so content remains readable
// while still showing the wallpaper underneath
val GlassSurface = Color(0x1AFFFFFF)              // ~10%
val GlassSurfaceElevated = Color(0x28FFFFFF)      // ~16%
val GlassSurfaceHigh = Color(0x36FFFFFF)          // ~21%
val GlassSurfaceStrong = Color(0x48FFFFFF)        // ~28% – selected / important cards

// Dark glass variants (preferred for most dashboard cards)
val GlassDark = Color(0x33101828)                 // deep navy glass
val GlassDarkElevated = Color(0x40182030)         // elevated
val GlassDarkStrong = Color(0x52202838)           // strong / selected

// Popup / dialog glass – much less transparent for readability
val GlassPopup = Color(0xE6121A28)                 // ~90% opaque navy
val GlassPopupElevated = Color(0xF0182030)         // ~94%
val GlassPopupBorder = Color(0x66FFFFFF)

// ── Bottom sheet glass ──
// Sheets sit on top of the busy dashboard, so they use a deep, dark body (same family as
// LiquidBackground instead of the lighter popup navy) and a heavy scrim that pushes the page
// behind them back. Cards inside a sheet are a notch more transparent than on the dashboard and
// have a softer rim / shine, so text stays readable and the sheet matches the main screen.
val GlassSheetTop = Color(0xCC0A1120)             // ~80% deep navy, top edge
val GlassSheetBottom = Color(0xE005080F)          // ~88% near-black, bottom edge
val GlassSheetScrim = Color(0xCC05080F)           // ~80% LiquidBackground behind the sheet

val GlassSheetCard = Color(0x26101828)            // ~15% (dashboard GlassDark: 20%)
val GlassSheetCardElevated = Color(0x33182030)    // ~20% (dashboard GlassDarkElevated: 25%)
val GlassSheetCardStrong = Color(0x4D202838)      // ~30% (dashboard GlassDarkStrong: 32%)

val GlassSheetRimLight = Color(0xA6FFFFFF)        // ~65% (dashboard rim: 80%)
val GlassSheetRimSoft = Color(0x4DFFFFFF)         // ~30%
val GlassSheetRimFaint = Color(0x2EFFFFFF)        // ~18%
val GlassSheetInnerHighlight = Color(0x33FFFFFF)  // ~20%

// ── Specular borders & highlights (85% rim strength from preference) ──
val GlassRimLight = Color(0xCCFFFFFF)             // bright top/left edge
val GlassRimSoft = Color(0x66FFFFFF)
val GlassInnerHighlight = Color(0x59FFFFFF)       // top inner shine
val GlassBorder = Color(0x80FFFFFF)
val GlassBorderSubtle = Color(0x40FFFFFF)
val GlassBorderSelected = Color(0xFF2DD4BF)       // teal glow border (Dark Emerald)

// Colored ambient glow
val GlassGlowPrimary = Color(0x55E91E63)
val GlassGlowAccent = Color(0x552DD4BF)           // teal preference glow
val GlassGlowBlue = Color(0x5560A5FA)
val GlassGlowGreen = Color(0x554ADE80)
val GlassShadow = Color(0x88000000)

// Backgrounds – deep so glass can pop (Dark Emerald base)
val LiquidBackground = Color(0xFF05080F)
val LiquidBackgroundSecondary = Color(0xFF0A101C)
val LiquidBackgroundTertiary = Color(0xFF121A2A)
val LiquidBackgroundLight = Color(0xFFE6EDF7)
val LiquidBackgroundLightSecondary = Color(0xFFD8E2F0)

// Status
val ServiceRunning = Color(0xFF4ADE80)
val ServiceStopped = Color(0xFF94A3B8)
val ServiceError = Color(0xFFF87171)
val ServiceStarting = Color(0xFFFBBF24)

// Logs
val LogRed = Color(0xFFF43F5E)
val LogGreen = Color(0xFF34D399)
val LogYellow = Color(0xFFFACC15)
val LogBlue = Color(0xFF60A5FA)
val LogPurple = Color(0xFFC084FC)
val LogRedLight = Color(0xFFFB7185)
val LogBlueLight = Color(0xFF22D3EE)
val LogWhite = Color(0xFFE2E8F0)

// Semantic
val SuccessGreen = Color(0xFF4ADE80)
val WarningOrange = Color(0xFFFB923C)
val ErrorRed = Color(0xFFF87171)
val InfoBlue = Color(0xFF60A5FA)

val SeedColor = Color(0xFFE91E63)

// On-glass text
val OnGlassPrimary = Color(0xFFFFFFFF)
val OnGlassSecondary = Color(0xCCFFFFFF)          // 80%
val OnGlassTertiary = Color(0x99FFFFFF)           // 60%
val OnGlassDisabled = Color(0x4DFFFFFF)
