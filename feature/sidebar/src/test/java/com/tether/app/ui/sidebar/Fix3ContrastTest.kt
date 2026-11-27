package com.tether.app.ui.sidebar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8A-FIX3 B2 + B3: the workspace badge (idle and current) and the New session key keep 4.5:1 between the glyph/label the
 * widget really drew (the pixel farthest in luminance from the face) and the face it really drew, in Light and Dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class Fix3ContrastTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double = lum(a).let { x -> lum(b).let { y -> (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) } }

    private class Crop(val m: androidx.compose.ui.graphics.PixelMap, val x: Int, val y: Int, val width: Int, val height: Int) {
        operator fun get(px: Int, py: Int): Color = m[x + px, y + py]
    }
    private fun root() = rule.onRoot().captureToImage().toPixelMap()

    /** [tag]'s own pixels (a plain node). */
    private fun node(tag: String): Crop {
        val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Crop(root(), b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
    }

    /** The workspace badge: the tile (its face is --bg-panel, 32 dp less its 1 dp border) found in the header corner of [workspace]'s block. */
    private fun badge(workspace: String, face: Color, dp: Float): Crop {
        val b = rule.onNodeWithTag(SidebarTags.block(workspace), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val m = root()
        val span = (56 * dp).toInt()
        val spanY = (44 * dp).toInt() // above the first row (its face is --bg-panel too when current)
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in b.top.toInt() until b.top.toInt() + spanY) for (x in b.left.toInt() until b.left.toInt() + span) {
            if (m[x, y].toArgb() == face.toArgb()) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
        }
        val side = (x1 - x0 + 1) / dp
        assertTrue("badge tile of $workspace found: ${side}dp wide, ${(y1 - y0 + 1) / dp}dp tall", side in 28f..34f && (y1 - y0 + 1) / dp in 28f..34f)
        return Crop(m, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** The contrast of the pixel of [c] (inner 60%) farthest in luminance from [face]: the glyph's solid core. */
    private fun glyph(c: Crop, face: Color, inner: Boolean): Double {
        val x0 = if (inner) c.width / 5 else 0
        val x1 = if (inner) c.width - c.width / 5 else c.width
        val y0 = if (inner) c.height / 5 else 0
        val y1 = if (inner) c.height - c.height / 5 else c.height
        var best = 1.0
        for (y in y0 until y1) for (x in x0 until x1) best = maxOf(best, ratio(c[x, y], face))
        return best
    }

    private fun check(skin: TetherSkin, current: Boolean, layout: TetherLayoutClass = TetherLayoutClass.Phone) {
        var t: TetherTokens? = null
        rule.setContent {
            TetherTheme(choiceFor(skin)) { t = LocalTetherTokens.current }
            // Sessions group by repo root (/srv/ws): one block, current when it holds the active session.
            SidebarUnderTest(
                skin,
                F.state(listOf(F.live("c1", "One", cwd = F.DOCS, ago = 0), F.live("c2", "Other", cwd = F.APP, ago = 5)), activeId = if (current) "c1" else null),
                layout,
            )
        }
        rule.waitForIdle()
        val tk = t!!
        val phone = layout == TetherLayoutClass.Phone
        val dp = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        val face = tk.css.bgPanel
        if (phone) {
            val tile = badge(F.ROOT, face, dp)
            val g = glyph(tile, face, inner = true)
            assertTrue("${if (current) "current" else "idle"} badge glyph vs face $g ($skin)", g >= 4.5)
        }
        // B3 (P8C, non-vacuous): the labelled "+ New session" key is the accent with the accent ink. The key's face is found as the
        // most frequent pixel that is not the rail behind it; the measure is taken INSIDE the face's own box (never the rail around it),
        // as the contrast of the glyph pixel FARTHEST IN LUMINANCE from that face (Light ink is lighter than its face: never "darkest"),
        // and the glyph must exist (pixels that are not the face > 0).
        val keyBmp = node(SidebarTags.NewSession)
        val counts = HashMap<Int, Int>()
        for (y in 0 until keyBmp.height) for (x in 0 until keyBmp.width) keyBmp[x, y].toArgb().let { if (it != tk.css.bgRail.toArgb()) counts.merge(it, 1, Int::plus) }
        val keyFace = Color(counts.maxByOrNull { it.value }!!.key)
        assertEquals("New session key face is --accent ($skin, $layout)", tk.accent.toArgb(), keyFace.toArgb())
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until keyBmp.height) for (x in 0 until keyBmp.width) if (keyBmp[x, y].toArgb() == keyFace.toArgb()) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        // the inner box: the face's box less 15% on every side (the corner radius and the border are not the glyph)
        val ix0 = x0 + bw * 15 / 100; val ix1 = x1 - bw * 15 / 100; val iy0 = y0 + bh * 15 / 100; val iy1 = y1 - bh * 15 / 100
        var glyphPixels = 0
        var farthest = keyFace
        for (y in iy0..iy1) for (x in ix0..ix1) {
            val c = keyBmp[x, y]
            if (c.toArgb() != keyFace.toArgb()) glyphPixels++
            if (ratio(c, keyFace) > ratio(farthest, keyFace)) farthest = c
        }
        val key = ratio(farthest, keyFace)
        println("P8C-FIX3 key-ink $skin $layout ${"%.2f".format(key)}:1 glyphPixels=$glyphPixels")
        assertTrue("New session label/icon vs face ${"%.2f".format(key)}:1 ($skin, $layout)", key >= 4.5)
        assertTrue("the key draws a glyph inside its face: $glyphPixels pixels ($skin, $layout)", glyphPixels > 0)
    }

    @Test fun lightIdleBadgeAndKeyKeepContrast() = check(TetherSkin.Studio, current = false)
    @Test fun lightCurrentBadgeAndKeyKeepContrast() = check(TetherSkin.Studio, current = true)
    @Test fun darkIdleBadgeAndKeyKeepContrast() = check(TetherSkin.StudioDark, current = false)
    @Test fun darkCurrentBadgeAndKeyKeepContrast() = check(TetherSkin.StudioDark, current = true)
    /** The tablet rail's key (RailChrome.kt) is the accent with the accent ink too. */
    @Test fun lightTabletKeyKeepsContrast() = check(TetherSkin.Studio, current = false, layout = TetherLayoutClass.Expanded)
    @Test fun darkTabletKeyKeepsContrast() = check(TetherSkin.StudioDark, current = false, layout = TetherLayoutClass.Expanded)
}
