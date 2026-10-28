package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass shapes – larger, softer, more fluid radii
 * matched to preference (pill / highly rounded glass objects).
 */
val Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(26.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )

/** Dedicated glass shapes from preference images */
object GlassShapes {
    val pill = RoundedCornerShape(50)
    val card = RoundedCornerShape(24.dp)
    val cardLarge = RoundedCornerShape(30.dp)
    val button = RoundedCornerShape(18.dp)
    val buttonLarge = RoundedCornerShape(22.dp)
    val sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val dialog = RoundedCornerShape(30.dp)
    val chip = RoundedCornerShape(16.dp)
    val statusBar = RoundedCornerShape(22.dp)
    val navBar = RoundedCornerShape(28.dp)
    val circle = RoundedCornerShape(50)
}
