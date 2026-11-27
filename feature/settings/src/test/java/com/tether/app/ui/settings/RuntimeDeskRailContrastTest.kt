package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WCAG relative luminance and contrast, shared by the P8E contrast tests. */
internal fun lum(c: Color): Double {
    fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

internal fun ratio(a: Color, b: Color): Double = lum(a).let { x -> lum(b).let { y -> (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) } }

/** The pixel of [img] inside the box (x0..x1, y0..y1) farthest in luminance from [face]: the glyph's own colour, never a corner. */
internal fun farthest(img: ImageBitmap, face: Color, x0: Int, y0: Int, x1: Int, y1: Int): Pair<Color, Int> {
    val px = img.toPixelMap()
    var best = face
    var bestD = 0.0
    var glyphs = 0
    for (y in y0 until y1.coerceAtMost(px.height)) for (x in x0 until x1.coerceAtMost(px.width)) {
        val c = px[x, y]
        val d = Math.abs(lum(c) - lum(face))
        if (d > 0.05) glyphs++
        if (d > bestD) { bestD = d; best = c }
    }
    return best to glyphs
}

/**
 * P8A-FIX3 B1 (made non-vacuous, P8E 13): the REAL RuntimeDeskScaffold at the expanded width (w800dp-h1000dp-mdpi) with a
 * selected route; the selected row's face and its title / supporting pixels are sampled from what the rail drew, so the
 * call site (`RuntimeDeskList(..., dark = false, ...)`) is what is tested: flipping it to `dark = true` fails here.
 * Q8: the title is the scope accent in both skins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1000dp-mdpi")
class RuntimeDeskRailContrastTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun check(skin: TetherSkin) {
        var t: TetherTokens? = null
        val items = listOf(
            RuntimeDeskNavItem("p", "Preferences", "Choose startup, session behavior"),
            RuntimeDeskNavItem("e", "Engines", "Models and accounts"),
        )
        rule.setContent {
            TetherTheme(skin.mode) {
                t = LocalTetherTokens.current
                RuntimeDeskScaffold(
                    items = items,
                    selected = "p",
                    onSelect = {},
                    entities = emptyList(),
                    selectedEntity = null,
                    onEntity = {},
                    level = RuntimeDeskLevel.Destinations,
                    onBack = {},
                    onReturn = {},
                ) { Box(Modifier.fillMaxSize().background(Color.Transparent).testTag("detail")) }
            }
        }
        rule.waitForIdle()
        val tk = t!!
        val selectedRow = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)
        val row = rule.onNode(selectedRow)
        val img = row.captureToImage()
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val face = img.toPixelMap().let { it[2, it.height / 2] }
        assertEquals("selected face is the accent-soft wash ($skin)", tk.css.accentSoft.toArgbInt(), face.toArgbInt())

        fun region(text: String): Pair<Color, Int> {
            val b = rule.onNode(hasText(text) and hasAnyAncestor(selectedRow), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return farthest(img, face, (b.left - rowBounds.left).toInt(), (b.top - rowBounds.top).toInt(), (b.right - rowBounds.left).toInt(), (b.bottom - rowBounds.top).toInt())
        }
        val (title, titlePx) = region("Preferences")
        val (sub, subPx) = region("Choose startup, session behavior")
        assertTrue("title has glyph pixels ($skin)", titlePx > 0)
        assertTrue("supporting has glyph pixels ($skin)", subPx > 0)
        val rt = ratio(title, face)
        val rs = ratio(sub, face)
        println("RAIL $skin face=${face.hex()} title=${title.hex()} ${"%.2f".format(rt)}:1 supporting=${sub.hex()} ${"%.2f".format(rs)}:1")
        assertTrue("selected title vs face $rt ($skin)", rt >= 4.5)
        assertTrue("selected supporting vs face $rs ($skin)", rs >= 4.5)
        // Q8: the title colour is the scope accent (the glyph core reaches it within antialiasing).
        assertTrue("selected title is the scope accent ($skin): ${title.hex()} vs ${tk.css.accent.hex()}", near(title, tk.css.accent))
    }

    @Test fun lightSelectedRowKeepsContrast() = check(TetherSkin.Studio)
    @Test fun darkSelectedRowKeepsContrast() = check(TetherSkin.StudioDark)
}

internal fun Color.toArgbInt(): Int = this.toArgb()
internal fun Color.hex(): String = "#%06x".format(toArgbInt() and 0xFFFFFF)
internal fun near(a: Color, b: Color, tol: Float = 0.06f) =
    Math.abs(a.red - b.red) <= tol && Math.abs(a.green - b.green) <= tol && Math.abs(a.blue - b.blue) <= tol
