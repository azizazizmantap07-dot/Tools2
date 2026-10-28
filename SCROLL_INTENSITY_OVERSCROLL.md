# Scroll Intensity Presets + Overscroll Bounce

## 1. Intensity per layar

```kotlin
object LiquidScrollIntensity {
    const val Subtle     = 0.55f   // settings, list padat
    const val Normal     = 1.0f    // default
    const val Aggressive = 1.45f   // dashboard, kartu besar
    const val Extreme    = 1.85f   // efek maksimal
}
```

Contoh per layar:

```kotlin
// Settings – halus
GlassScrollCard(listState, index, intensity = LiquidScrollIntensity.Subtle)

// Dashboard – agresif
GlassScrollCard(listState, index, intensity = LiquidScrollIntensity.Aggressive)

// Atau angka custom
Modifier.liquidScroll(listState, index, intensity = 1.2f)
```

## 2. Overscroll bounce (kenyal di ujung list)

Pasang di **parent** LazyColumn, lalu (opsional) bagikan state ke item:

```kotlin
val listState = rememberLazyListState()
val overscroll = rememberLiquidOverscrollState()

Box(
    Modifier.liquidOverscroll(
        listState = listState,
        state = overscroll,
        maxPull = 120f,      // jarak tarik maksimal (px)
        resistance = 2.2f,   // 1.5 = lebih mudah ditarik, 3.0 = lebih kaku
    )
) {
    LazyColumn(state = listState) {
        itemsIndexed(items) { index, item ->
            GlassScrollCard(
                listState = listState,
                index = index,
                intensity = LiquidScrollIntensity.Aggressive,
                overscrollState = overscroll,  // item ikut jelly saat overscroll
            ) { /* content */ }
        }
    }
}
```

Perilaku:
- Tarik melewati ujung atas/bawah → rubber-band (tanh resistance)
- Lepas / fling → spring kembali dengan bounce kenyal (`OverscrollSpring`)
- Seluruh konten sedikit scale + translation
- Item glass (jika `overscrollState` di-pass) ikut squash/stretch

## Rekomendasi per layar

| Layar            | Intensity   | Overscroll |
|------------------|-------------|------------|
| Settings         | Subtle      | ya, soft   |
| Connections list | Normal      | ya         |
| Dashboard cards  | Aggressive  | ya         |
| Profile / hero   | Extreme     | ya         |
