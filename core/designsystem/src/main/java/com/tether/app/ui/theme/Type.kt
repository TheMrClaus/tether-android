package com.tether.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.tether.app.core.designsystem.R

/**
 * Bundled variable fonts — the same faces the web loads from `@fontsource-variable` packages
 * (app/layout.tsx imports only the upright `index.css` of each package):
 * - Manrope[wght].ttf        -> res/font/manrope_variable.ttf       (`Manrope Variable`, wght 200–800)
 * - JetBrainsMono[wght].ttf  -> res/font/jetbrains_mono_variable.ttf (`JetBrains Mono Variable`, wght 100–800)
 *
 * Both files carry a single `wght` axis. Each [Font] entry pins one named instance by setting
 * that axis explicitly ([FontVariation.weight]), so every weight the web CSS asks for renders
 * as the true instance instead of Compose's nearest-weight match (Manrope's *default* instance
 * is wght 200, so an unpinned entry would render ExtraLight).
 *
 * No italic instance is bundled, matching the web (it never loads `wght-italic.css`); the one
 * italic rule (`.diff-more`, globals.css:6161) is browser-synthesized oblique there and
 * Compose-synthesized (FontSynthesis) here.
 */

/**
 * Every `font-weight` value in app/globals.css + app/studio.css (incl. the `font:` shorthand):
 * 400 500 550 560 600 610 620 630 640 650 660 680 700 720 740 750 770.
 */
val WebFontWeights: List<Int> = listOf(400, 500, 550, 560, 600, 610, 620, 630, 640, 650, 660, 680, 700, 720, 740, 750, 770)

/** The `wght` axis range of each bundled file (read from its `fvar` table; asserted in tests). */
val ManropeWghtRange: IntRange = 200..800
val JetBrainsMonoWghtRange: IntRange = 100..800

private fun variableFamily(resId: Int, range: IntRange): FontFamily = FontFamily(
    WebFontWeights.filter { it in range }.map { w ->
        Font(resId, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** The interface face (`body`, globals.css:609; Studio's `--font-ui`, studio.css:47). */
val Manrope: FontFamily = variableFamily(R.font.manrope_variable, ManropeWghtRange)

/** The mono face (`--font-mono`, globals.css:58). */
val JetBrainsMono: FontFamily = variableFamily(R.font.jetbrains_mono_variable, JetBrainsMonoWghtRange)

/**
 * Legacy weight aliases used by the pre-T3.2 screens. New code should take a role from
 * [TetherTypography] (via [LocalTetherTypography]) instead; these stay so existing UI keeps
 * compiling and looking the same until each screen's parity task migrates it.
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
