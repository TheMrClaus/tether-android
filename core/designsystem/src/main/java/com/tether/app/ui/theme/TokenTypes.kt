package com.tether.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Structured value types GeneratedTokens.kt uses for CSS values Compose has no single type for.
 * Hand-written (the generator only references them).
 */

/**
 * One CSS `box-shadow` layer. Outer layers ([inset] false) are drop shadows (Compose
 * `Modifier.dropShadow`), inset layers are inner bevels/wells (`Modifier.innerShadow`);
 * T3.3's material primitives own that rendering. Lengths are CSS px = dp.
 */
@Immutable
data class CssShadow(
    val inset: Boolean,
    val offsetX: Dp,
    val offsetY: Dp,
    val blur: Dp,
    val spread: Dp,
    val color: Color,
)

/** A CSS `cubic-bezier(x1, y1, x2, y2)` timing function. */
@Immutable
data class CssCubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun toEasing(): Easing = CubicBezierEasing(x1, y1, x2, y2)
}

/** A responsive (`@media`) token: [value] applies while the window width is within [minWidth, maxWidth]. */
@Immutable
data class CssMediaToken(val name: String, val media: String, val minWidth: Dp?, val maxWidth: Dp?, val value: Dp)
