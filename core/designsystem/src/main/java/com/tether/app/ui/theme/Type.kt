package com.tether.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.tether.app.core.designsystem.R

/**
 * Bundled variable fonts: the same faces the web loads from `@fontsource-variable` packages
 * (`--font-ui` / `--font-mono`, app/tokens.css:22-23; provenance in core/designsystem/licenses/README.md):
 * - Archivo[wdth,wght].ttf -> res/font/archivo_variable.ttf  (`Archivo Variable`, wght 100-900, wdth 62-125)
 * - FiraCode[wght].ttf     -> res/font/fira_code_variable.ttf (`Fira Code Variable`, wght 300-700)
 *
 * Each [Font] entry pins one named instance by setting the axes explicitly ([FontVariation.weight]
 * and, for Archivo, [FontVariation.width] at its default 100 because the web never sets
 * `font-stretch`), so every weight the web CSS asks for renders as the true instance instead of
 * Compose's nearest-weight match (Archivo's *default* instance is wght 600, Fira Code's is 300).
 *
 * No italic instance is bundled, matching the web (it never loads an italic face); the one
 * italic rule (`.diff-more`) is Compose-synthesized (FontSynthesis) here.
 */

/**
 * Every `font-weight` value in app/globals.css + app/studio.css (incl. the `font:` shorthand):
 * 400 500 550 560 600 610 620 630 640 650 660 680 700 720 740 750 770.
 */
val WebFontWeights: List<Int> = listOf(400, 500, 550, 560, 600, 610, 620, 630, 640, 650, 660, 680, 700, 720, 740, 750, 770)

/** The `wght` axis range of each bundled file (read from its `fvar` table; asserted in tests). */
val ArchivoWghtRange: IntRange = 100..900
val FiraCodeWghtRange: IntRange = 300..700

/** Archivo's `wdth` axis: range 62..125, default (and the only width used) 100. */
val ArchivoWdthRange: IntRange = 62..125
const val ArchivoDefaultWdth: Int = 100

private fun variableFamily(resId: Int, range: IntRange, width: Int? = null): FontFamily = FontFamily(
    WebFontWeights.filter { it in range }.map { w ->
        val settings = if (width == null) FontVariation.Settings(FontVariation.weight(w))
        else FontVariation.Settings(FontVariation.weight(w), FontVariation.width(width.toFloat()))
        Font(resId, weight = FontWeight(w), variationSettings = settings)
    },
)

/** The interface face (`--font-ui`, tokens.css:22). */
val Archivo: FontFamily = variableFamily(R.font.archivo_variable, ArchivoWghtRange, ArchivoDefaultWdth)

/** The mono face (`--font-mono`, tokens.css:23). */
val FiraCode: FontFamily = variableFamily(R.font.fira_code_variable, FiraCodeWghtRange)

/** Pre-Vector name of [Archivo]: the app's call sites predate the face change and keep the name. */
val Manrope: FontFamily = Archivo

/** Pre-Vector name of [FiraCode] (the mono face was JetBrains Mono); call sites keep the name. */
val JetBrainsMono: FontFamily = FiraCode

/**
 * Legacy weight aliases used by the pre-T3.2 screens. New code should take a role from
 * [TetherTypography] (via [LocalTetherTypography]) instead; these stay so existing UI keeps
 * compiling until each screen's parity task migrates it. Note: screens are NOT pixel-identical to
 * pre-T3.2 — every `Text` without an explicit style now inherits the web body role (weight 400,
 * CSS-style line height, no Material tracking) via the M3 typography mapping, a deliberate move
 * toward the web (T3.2 verifier).
 */
object TetherWeights {
    val body = FontWeight(500)
    val label = FontWeight(600)
    val name = FontWeight(650) // w640/w650 in the spec
    val strong = FontWeight(700)
    val heading = FontWeight(720) // w680/w700/w720 headings
    val glyph = FontWeight(750)
    val wordmark = FontWeight(720)
}
