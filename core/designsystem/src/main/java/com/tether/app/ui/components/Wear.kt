package com.tether.app.ui.components

import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import com.tether.app.ui.theme.TetherTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Key wear: "ONE irregular burnished zone where the pad of the finger lands", swipe streaks and
 * `--wear-lo` grime in the corners (globals.css 8771-8857). Each composition is the web's
 * `background:` list, layer for layer. Studio's `--wear-*` are transparent, so it draws nothing.
 */
enum class KeyWear { None, Primary, NewSession, Send, SendCompact }

private sealed interface WearLayer

/** `radial-gradient(W% H% at X% Y%, c, transparent S%)` — an explicit-size ellipse. */
private data class Radial(val w: Float, val h: Float, val x: Float, val y: Float, val hi: Boolean, val mix: Float, val stop: Float) : WearLayer

/** `linear-gradient(Adeg, transparent a%, c b%, transparent d%)` — a single scratch line. */
private data class Scratch(val deg: Float, val a: Float, val b: Float, val d: Float, val mix: Float) : WearLayer

private fun hi(w: Float, h: Float, x: Float, y: Float, mix: Float, stop: Float) = Radial(w, h, x, y, true, mix, stop)
private fun lo(w: Float, h: Float, x: Float, y: Float, mix: Float, stop: Float) = Radial(w, h, x, y, false, mix, stop)

private val layers: Map<KeyWear, List<WearLayer>> = mapOf(
    // globals.css 8816-8823
    KeyWear.Primary to listOf(
        hi(95f, 78f, 52f, 38f, 1f, 56f),
        hi(58f, 62f, 41f, 52f, 0.55f, 64f),
        hi(44f, 10f, 50f, 58f, 0.40f, 74f),
        lo(52f, 44f, 104f, 106f, 1f, 62f),
        lo(120f, 32f, 50f, -10f, 0.55f, 55f),
    ),
    // globals.css 8827-8834
    KeyWear.NewSession to listOf(
        hi(72f, 68f, 36f, 44f, 0.85f, 60f),
        hi(46f, 52f, 50f, 34f, 0.45f, 68f),
        hi(36f, 9f, 38f, 60f, 0.35f, 75f),
        lo(38f, 44f, -4f, 110f, 0.85f, 64f),
        lo(34f, 36f, 103f, 105f, 0.60f, 68f),
    ),
    // globals.css 8799-8811
    KeyWear.Send to listOf(
        hi(85f, 70f, 47f, 40f, 1f, 58f),
        hi(60f, 80f, 59f, 54f, 0.65f, 62f),
        hi(50f, 42f, 36f, 32f, 0.50f, 68f),
        hi(52f, 11f, 54f, 63f, 0.55f, 74f),
        hi(38f, 8f, 42f, 27f, 0.40f, 76f),
        Scratch(107f, 43f, 43.6f, 44.2f, 0.45f),
        Scratch(63f, 66f, 66.5f, 67f, 0.28f),
        lo(130f, 50f, 50f, 112f, 0.85f, 52f),
        lo(45f, 42f, -6f, 108f, 1f, 66f),
        lo(42f, 38f, 106f, -8f, 0.70f, 68f),
    ),
    // globals.css 8847-8857 (phone: the 44px icon-only Send square)
    KeyWear.SendCompact to listOf(
        hi(78f, 72f, 52f, 56f, 1f, 60f),
        hi(55f, 60f, 40f, 42f, 0.60f, 66f),
        hi(34f, 18f, 55f, 68f, 0.45f, 74f),
        Scratch(118f, 40f, 41.2f, 42.4f, 0.40f),
        lo(60f, 55f, -8f, -10f, 0.80f, 62f),
        lo(55f, 50f, 108f, 108f, 1f, 62f),
        lo(45f, 40f, 108f, -8f, 0.60f, 66f),
    ),
)

/**
 * Paints [wear] over the key face (the web's `::after` is positioned, so it paints above the
 * legend). `color-mix(in oklab, c p%, transparent)` is c with its alpha scaled by p (premultiplied
 * mixing with transparent keeps the hue) — exact. Stops fade to the same colour at alpha 0 so the
 * ramp matches CSS's premultiplied interpolation.
 */
fun DrawScope.drawKeyWear(t: TetherTokens, wear: KeyWear) {
    val list = layers[wear] ?: return
    if (t.wearHi.alpha == 0f && t.wearLo.alpha == 0f) return
    for (layer in list.asReversed()) {
        when (layer) {
            is Radial -> {
                val base = if (layer.hi) t.wearHi else t.wearLo
                val c = base.copy(alpha = base.alpha * layer.mix)
                drawRect(radialBrush(size, layer, c))
            }
            is Scratch -> {
                val c = t.wearHi.copy(alpha = t.wearHi.alpha * layer.mix)
                drawRect(scratchBrush(size, layer, c))
            }
        }
    }
}

private fun radialBrush(size: Size, l: Radial, c: Color): Brush {
    val rx = size.width * l.w / 100f
    val ry = size.height * l.h / 100f
    val cx = size.width * l.x / 100f
    val cy = size.height * l.y / 100f
    val shader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(c.toArgb(), c.copy(alpha = 0f).toArgb()),
        floatArrayOf(0f, l.stop / 100f),
        Shader.TileMode.CLAMP,
    )
    shader.setLocalMatrix(Matrix().apply { setScale(rx.coerceAtLeast(0.01f), ry.coerceAtLeast(0.01f)); postTranslate(cx, cy) })
    return ShaderBrush(shader)
}

private fun scratchBrush(size: Size, l: Scratch, c: Color): Brush {
    // CSS gradient line: through the centre at angle A (0deg = to top, clockwise), length
    // |w·sin A| + |h·cos A| (CSS Images 3 §3.1.1).
    val rad = l.deg * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = -cos(rad)
    val len = abs(size.width * dx) + abs(size.height * dy)
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = center - Offset(dx, dy) * (len / 2f)
    val end = center + Offset(dx, dy) * (len / 2f)
    val clear = c.copy(alpha = 0f)
    return Brush.linearGradient(
        0f to clear,
        l.a / 100f to clear,
        l.b / 100f to c,
        l.d / 100f to clear,
        1f to clear,
        start = start,
        end = end,
    )
}
