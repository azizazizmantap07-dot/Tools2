# Liquid Glass + Blur Glass UI Redesign – Sing-box for Android

## Overview

This redesign introduces a full **Liquid Glass / Glassmorphism** design system to the Sing-box for Android (SFA) application. The changes focus on a cohesive, modern, translucent aesthetic with soft borders, fluid shapes, and depth while preserving the original brand accent color (pink/magenta).

## What was changed

### 1. Color System (`compose/theme/Color.kt`)
- New translucent glass surfaces (`GlassSurface`, `GlassSurfaceElevated`, `GlassSurfaceHigh`)
- Soft glass borders and highlights
- Deep navy-black backgrounds optimized for glass (`LiquidBackground*`)
- Refined status and log colors that harmonize with glass
- On-glass text colors with proper opacity hierarchy

### 2. Shapes (`compose/theme/Shape.kt`)
- Larger, more fluid corner radii across the Material shapes scale
- Dedicated `GlassShapes` object (pill, card, button, sheet, chip, statusBar, etc.)

### 3. Theme (`compose/theme/Theme.kt`)
- Complete `LiquidGlassDarkColorScheme` and `LiquidGlassLightColorScheme`
- Dynamic color disabled by default so the glass palette remains consistent
- Transparent system bars for an immersive glass experience
- Surfaces and containers use translucent-leaning values so glass modifiers create real depth

### 4. New Glass Design System (`compose/theme/Glass.kt`)
Reusable building blocks:

| Component / Modifier      | Purpose                                      |
|---------------------------|----------------------------------------------|
| `Modifier.liquidGlass()`  | Core glass treatment (bg + border + shadow)  |
| `Modifier.glassBlur()`    | Content blur (Android 12+)                   |
| `Modifier.glassHighlight()` | Soft vertical specular highlight           |
| `GlassSurface`            | Translucent surface container                |
| `GlassCard`               | Elevated glass card                          |
| `GlassButton`             | Glass-style button                           |
| `GlassPrimaryButton`      | Accent primary button with glass edge        |
| `GlassTextButton`         | Text button tuned for glass                  |
| `GlassMaterialSurface`    | Drop-in replacement for Material Surface     |
| `GlassListCard`           | Glass card that stacks rows in a `Column` (use for multi-row cards; plain `GlassCard` is a `Box` and overlaps children). `popup = true` for solid dialog fill, `onClick` for tappable tiles, `selected = true` for the teal selected rim |
| `GlassAlertDialog`        | Drop-in for `AlertDialog` – solid glass card popup |
| `GlassDropdownMenu`       | Drop-in for `DropdownMenu` – solid glass menu |
| `GlassModalBottomSheet`   | Drop-in for `ModalBottomSheet` – dark translucent glass body (`liquidSheetGlass`) over a heavy dark scrim, always dark so content stays readable in light mode. Cards inside use the lighter "sheet card" look (see *Bottom sheet glass*). Pass only `onDismissRequest` / `sheetState`; do not set a container colour |
| `GlassDragHandle`         | Drag pill used by `GlassModalBottomSheet`    |
| `glassTextFieldColors()`  | Solid dark text-field colors for inputs inside glass popups |

### 5. Component example (`ServiceStatusBar.kt`)
Fully rewritten to use `GlassSurface` + `liquidGlass` chips as a reference implementation.

## How to adopt across the rest of the app

1. **Replace plain Surfaces / Cards**
   ```kotlin
   // Before
   Surface(color = MaterialTheme.colorScheme.surfaceContainer) { ... }

   // After
   GlassCard { ... }
   // or
   GlassSurface(elevated = true) { ... }
   ```

2. **Buttons**
   ```kotlin
   GlassPrimaryButton(onClick = { ... }) {
       Text("Start")
   }
   // or
   GlassButton(onClick = { ... }) {
       Text("Secondary")
   }
   ```

3. **Quick modifier usage**
   ```kotlin
   Box(
       modifier = Modifier
           .liquidGlass(shape = GlassShapes.card, elevated = true)
           .padding(16.dp)
   ) { ... }
   ```

4. **NavigationBar / TopAppBar / FAB**
   Because the color scheme now uses translucent container colors, most Material3 components already pick up the glass feel. For stronger effect, wrap them or apply `liquidGlass` to their containers.

5. **Recommended background**
   Prefer dark theme (`LiquidBackground`) – glass looks significantly better on deep dark bases.

## Technical notes

- True *backdrop* blur (blurring content behind a translucent layer) is limited on Android without additional libraries (e.g. Haze). The provided `glassBlur` applies content blur and the translucent surfaces create the classic glassmorphism look.
- All new APIs are pure Compose + Android 12+ RenderEffect. No extra dependencies required.
- The original primary accent (`#E91E63`) is preserved for brand continuity.

## Files in this package

```
app/src/main/java/io/nekohasekai/sfa/compose/theme/
├── Color.kt      (updated)
├── Shape.kt      (updated)
├── Theme.kt      (updated)
└── Glass.kt      (new)

app/src/main/java/io/nekohasekai/sfa/compose/component/
└── ServiceStatusBar.kt  (updated – reference implementation)
```

Copy these files into your project (overwrite existing ones). Rebuild and run. The entire Material3 surface hierarchy will shift toward the liquid-glass aesthetic; progressively replace remaining custom Surfaces and buttons with the new glass components for a complete look.

## Bottom navigation (liquid glass)

`compose/component/GlassNavigationBar.kt` replaces the Material3 `NavigationBar` in `MainActivity`:

- Floating dark-glass container (`Modifier.liquidGlass`, `GlassShapes.navBar`) with the same 12dp side margin as `ServiceStatusBar`.
- Selected tab = lighter glass "lens" that slides between tabs with a jelly spring and stretches while it moves.
- Selected tab uses the filled icon, other tabs the outlined one (`Screen.outlinedIcon` in `NavigationDestinations.kt`).
- Every tab uses `jellyClickable`; Tools badge (taildrop / reports) is unchanged.
- Handles system-bar insets itself, so the status bar above it still lines up through `Scaffold`'s bottom padding.

The wide-screen `NavigationRail` (tablet / unfolded) is not changed.

## Jelly press (fixed)

Why the jelly was barely visible on glass cards, and what changed in `theme/Glass.kt`:

1. **Order bug.** The deformation (`graphicsLayer`) was applied *after* `liquidGlass`, so only the
   content inside the card shrank while the glass itself (fill, rim, shine, shadow) stayed still.
   The deformation now sits *before* `liquidGlass`, so the whole glass body squishes and wobbles.
   Rule: `Modifier.jellyClickable(...) / jellyTouch(...)` first, then `.liquidGlass(...)`, then `.padding(...)`.
2. **Delayed press.** Inside scrollable containers `clickable` delays its pressed state by ~100 ms, so a
   normal tap pressed and released in the same instant. New `Modifier.jellyTouch` reacts on
   touch-down, holds a quick tap for 90 ms so it is visible, and springs back immediately when the
   touch becomes a scroll.
3. **Only clickable cards reacted.** Static `GlassCard`s (most of them) now squish gently on touch too,
   and stay still when a child (button, switch, clickable row) takes the touch. Turn off per card with
   `jelly = false`. Popup/dialog surfaces never squish.
4. **Livelier spring.** Cards use the new `JellySpringCard` (damping 0.34, stiffness 340) and deeper
   press values (clickable ≈ 4 % × 8 %, static ≈ 1.5 % × 3 %), X and Y with slightly different
   stiffness so the release wobble alternates wide/flat and narrow/tall.
5. `jellyClickable` now keeps the `clickable` outside the deformation (hit area does not shrink under
   the finger). `GlassButton` / `GlassPrimaryButton` no longer swallow their click behind a dummy
   `onClick = {}`. `GlassChip` in `ServiceStatusBar` got the same order fix.

Tuning: press depth = the three `pressed*` values passed to `jellyTouch` in `GlassSurface`;
bounce = `JellySpringCard.dampingRatio` (lower = more wobble); minimum visible tap = `JELLY_MIN_PRESS_MS`.

Scroll-linked jelly is now active on primary lists:

- **DashboardScreen** – `liquidOverscroll` on the LazyColumn + `liquidScroll` / `liquidOverscrollItem` (Aggressive) on card rows
- **ConnectionsScreen** – `liquidOverscroll` on both sheet and full-page lists + `liquidScroll` / `liquidOverscrollItem` (Normal) on each `ConnectionItem`
- **LogScreen** – `liquidOverscroll` on the log LazyColumn (edge bounce only; log lines stay dense)

Use `GlassScrollCard`, `Modifier.liquidScroll`, or `Modifier.liquidOverscroll` the same way on any remaining lists.

## Tombol glass (drop-in pengganti Material)

Semua tombol Material di screen sudah diganti dengan versi glass, urutan modifier sama dengan `GlassSurface`
(clickable -> jellyTouch -> liquidGlass) jadi seluruh badan kaca ikut squish saat ditekan:

| Material                       | Glass                              | Catatan |
|--------------------------------|------------------------------------|---------|
| `IconButton`                   | `GlassIconButton`                  | lingkaran kaca 40dp, area sentuh 48dp |
| `FloatingActionButton`         | `GlassFloatingActionButton`        | kaca bulat 56dp + tint `containerColor` |
| `ExtendedFloatingActionButton` | `GlassExtendedFloatingActionButton`| pil kaca ikon + teks |
| `Button`                       | `GlassActionButton`                | tint aksen; `tint = ...` untuk warna lain (mis. error) |
| `OutlinedButton`               | `GlassOutlinedButton`              | netral; `selected = true` untuk tombol segmented |
| `FilledTonalButton`            | `GlassTonalButton`                 | netral |

| `TextButton`                   | `GlassTextButton`                  | pill kaca netral (dialog & aksi inline), label tetap warna primary |
| `SegmentedButton`              | `GlassOutlinedButton(selected = …)` | pill kaca di `ClashModeCard`; yang terpilih bertint aksen |

## Bottom sheet glass (consistency + readability)

Sheets (Groups, Connections, Profiles, Dashboard settings, Add profile, QR scan, Share) all go through
`GlassModalBottomSheet`, so one change in `theme/Glass.kt` + `theme/Color.kt` covers every sheet.

What was wrong: the sheet body used the dialog fill (`GlassPopup`, ~94 % opaque, a *lighter* grey-blue navy)
with the 26 % white shine drawn **over** the content, the default 32 % scrim let the dashboard text show
through, and the cards inside used the dashboard recipe on top of that grey panel (flat, loud white rims,
visible seams between stacked group rows).

What changed:

| Part | Before | After |
|------|--------|-------|
| Sheet body | `GlassPopup(Elevated)` ~94 % navy | `liquidSheetGlass`: `GlassSheetTop` ~80 % → `GlassSheetBottom` ~88 %, deep navy / near-black like `LiquidBackground` |
| Sheet top light | white 26 % drawn over content | white 8 % drawn **behind** content |
| Scrim | default black 32 % | `GlassSheetScrim` (~80 % `LiquidBackground`) – parameter `scrimColor` |
| Cards in sheet | dashboard fill 20 / 25 / 32 % | `GlassSheetCard*` 15 / 20 / 30 % |
| Card rim in sheet | 80 % white | `GlassSheetRim*` 65 % → 18 % |
| Card shine in sheet | 26 % → 10 % white over content | 10 % → 3.5 % |

How it works: `GlassModalBottomSheet` provides `LocalGlassSheetContext = true` around its content and
`Modifier.liquidGlass` (now a `composed` modifier) reads it. Only dark, non-popup glass switches look, so
`popup = true` surfaces and `GlassAlertDialog` (which resets the flag) are unchanged, and nothing outside a
sheet is affected. No call site had to change.

Tuning: sheet transparency = alpha of `GlassSheetTop` / `GlassSheetBottom`; how much of the dashboard is
visible behind = `GlassSheetScrim` (or pass `scrimColor` per sheet); card transparency = `GlassSheetCard*`;
rim strength = `GlassSheetRim*`.

Note: this is still tint + scrim, not a real backdrop blur (see *Technical notes*). A blur of the page behind
the sheet would need an extra library such as Haze.
