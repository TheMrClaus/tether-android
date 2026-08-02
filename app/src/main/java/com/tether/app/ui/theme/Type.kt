package com.tether.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tether.app.R

/**
 * Bundled variable fonts (google/fonts static builds of the wght-variable TTFs):
 * - Manrope[wght].ttf   -> res/font/manrope_variable.ttf   (UI type, 200-800)
 * - JetBrainsMono[wght].ttf -> res/font/jetbrains_mono_variable.ttf (mono, 100-800)
 *
 * Each Font() entry pins a named weight; the default variationSettings derive the
 * wght axis from the declared weight (API 26+), so intermediate weights like 650
 * render true rather than faux-bolded.
 */

private val W500 = FontWeight(500)
private val W600 = FontWeight(600)
private val W650 = FontWeight(650)
private val W700 = FontWeight(700)
private val W720 = FontWeight(720)
private val W750 = FontWeight(750)
private val W800 = FontWeight(800)

val Manrope = FontFamily(
    Font(R.font.manrope_variable, weight = FontWeight.Normal),
    Font(R.font.manrope_variable, weight = W500),
    Font(R.font.manrope_variable, weight = W600),
    Font(R.font.manrope_variable, weight = W650),
    Font(R.font.manrope_variable, weight = W700),
    Font(R.font.manrope_variable, weight = W720),
    Font(R.font.manrope_variable, weight = W750),
    Font(R.font.manrope_variable, weight = W800),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_variable, weight = FontWeight.Normal),
    Font(R.font.jetbrains_mono_variable, weight = W500),
    Font(R.font.jetbrains_mono_variable, weight = W600),
    Font(R.font.jetbrains_mono_variable, weight = W700),
)

/** Weight aliases used across the spec (610-750 map to the nearest true weight). */
object TetherWeights {
    val body = W500
    val label = W600
    val name = W650 // w640/w650 in the spec
    val strong = W700
    val heading = W720 // w680/w700/w720 headings
    val glyph = W750
    val wordmark = W720
}
