package com.tether.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-z4c1 Z2: CssFlexRow follows CSS Flexbox's shrink (flex 0 1 auto, min-width auto). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CssFlexRowTest {
    @get:Rule val rule = createComposeRule()

    private class Box4(var x: Float = -1f, var w: Float = -1f, var h: Float = -1f)

    private val dotDp = 6.4f

    /** The status line at [width]: dot (floor 0), "Needs you", "· 12h"; returns dot, text1, text2 boxes and layouts. */
    private fun statusLine(width: Dp, density: Float = 1f): Triple<List<Box4>, List<TextLayoutResult?>, Unit> {
        val boxes = List(3) { Box4() }
        val layouts = arrayOfNulls<TextLayoutResult>(3)
        rule.setContent {
            TetherTheme {
                val style = TextStyle(fontFamily = LocalTetherTypography.current.ui, fontSize = (10.88f * density).sp, fontWeight = FontWeight(500))
                val d = LocalDensity.current
                Box(Modifier.width(width)) {
                    CssFlexRow(gap = 5.6.dp) {
                        StatusDot(Color.Red, dotDp.dp, Modifier.testTag("dot").flexFloor(0.dp).onGloballyPositioned { boxes[0].x = it.positionInRoot().x / d.density; boxes[0].w = it.size.width / d.density; boxes[0].h = it.size.height / d.density })
                        Text("Needs you", style = style, onTextLayout = { layouts[1] = it }, modifier = Modifier.onGloballyPositioned { boxes[1].x = it.positionInRoot().x / d.density; boxes[1].w = it.size.width / d.density })
                        Text("· 12h", style = style, onTextLayout = { layouts[2] = it }, modifier = Modifier.onGloballyPositioned { boxes[2].x = it.positionInRoot().x / d.density; boxes[2].w = it.size.width / d.density })
                    }
                }
            }
        }
        rule.waitForIdle()
        return Triple(boxes, layouts.toList(), Unit)
    }

    private fun assertWholeWords(layout: TextLayoutResult?) {
        val l = layout!!
        val boundaries = lineBoundaries(l.layoutInput.text.text)
        (0 until l.lineCount - 1).forEach { assertTrue("line $it ends at an opportunity", l.getLineEnd(it) in boundaries) }
    }

    @Test fun aFittingRowIsRowSpacedByGap() {
        val (b, _, _) = statusLine(300.dp)
        assertEquals(0f, b[0].x, 0.5f)
        assertEquals(dotDp, b[0].w, 0.5f)
        assertEquals("dot, gap", dotDp + 5.6f, b[1].x, 0.5f)
        assertEquals("text, gap", b[1].x + b[1].w + 5.6f, b[2].x, 0.5f)
    }

    @Test fun aShrunkRowWrapsAtSpacesAndTheDotNarrowsButKeepsItsHeight() {
        val (b, l, _) = statusLine(118.dp, density = 1.97f)
        assertTrue("the dot narrows", b[0].w < dotDp && b[0].w > 0f)
        assertEquals("a shrunk dot keeps its full height", dotDp, b[0].h, 0.5f)
        // Drawn as an oval at its full height (border-radius 50% on a narrowed box), not as a circle of the narrow
        // diameter: the dot's painted rows run the whole laid-out height (a circle's would stop short by
        // (height - width) / 2 at each end), and the centre column is painted top to bottom.
        val img = rule.onNodeWithTag("dot").captureToImage().toPixelMap()
        val cx = img.width / 2
        // The capture has an opaque backdrop: the dot is the red pixels.
        fun red(x: Int, y: Int) = img[x, y].let { it.red > 0.6f && it.green < 0.4f && it.blue < 0.4f }
        val painted = (0 until img.height).filter { red(cx, it) }
        assertTrue("the oval's centre column runs the full height: ${painted.firstOrNull()}..${painted.lastOrNull()} of ${img.height}", painted.first() <= 1 && painted.last() >= img.height - 2)
        val rows = (0 until img.height).filter { y -> (0 until img.width).any { red(it, y) } }
        assertEquals("painted height = laid-out height", img.height.toFloat(), (rows.last() - rows.first() + 1).toFloat(), 2f)
        assertTrue("narrower than tall: ${img.width} x ${img.height}", img.width < img.height - 4)
        assertWholeWords(l[1]); assertWholeWords(l[2])
        assertTrue("the last item ends inside the row", b[2].x + b[2].w <= 118.5f)
    }

    @Test fun anUnbreakableWordOverflowsAtItsFloorAndTheDotGoesToZero() {
        val boxes = List(2) { Box4() }
        var layout: TextLayoutResult? = null
        rule.setContent {
            TetherTheme {
                val style = TextStyle(fontFamily = LocalTetherTypography.current.ui, fontSize = 21.76.sp, fontWeight = FontWeight(500))
                val d = LocalDensity.current
                Box(Modifier.width(184.81.dp)) {
                    CssFlexRow(gap = 5.6.dp) {
                        Box(Modifier.flexFloor(0.dp).size(dotDp.dp).onGloballyPositioned { boxes[0].w = it.size.width / d.density })
                        Text("Needsyouverymuchrightnow", style = style, onTextLayout = { layout = it }, modifier = Modifier.onGloballyPositioned { boxes[1].x = it.positionInRoot().x / d.density; boxes[1].w = it.size.width / d.density })
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("the dot shrinks to 0", 0f, boxes[0].w, 0.5f)
        assertEquals("one word, one line, never split", 1, layout!!.lineCount)
        assertTrue("overflows the row", boxes[1].x + boxes[1].w > 184.81f)
    }

    /**
     * (d) The sidebar footer's geometry at the 1.3x tablet: an actions item at its floor, then the
     * status span (dot + "Private runtime"). With negative free space the span starts at the actions'
     * right edge and fills the leftover, exactly as `Row` + `Spacer` did: the dot's left edge stays put
     * while it narrows, and the text starts left of where it would with a full dot.
     */
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    @Test fun theFooterSpanStartsAtTheActionsEdgeAndFillsTheLeftover() {
        val actionsW = 140f
        val total = 237f
        val span = Box4(); val dot = Box4(); val text = Box4()
        rule.setContent {
            TetherTheme {
                val style = TextStyle(fontFamily = LocalTetherTypography.current.ui, fontSize = 13.7.sp, fontWeight = FontWeight(500))
                val d = LocalDensity.current
                Box(Modifier.width(total.dp)) {
                    CssFlexRow(justify = FlexJustify.SpaceBetween, modifier = Modifier.width(total.dp)) {
                        Box(Modifier.size(actionsW.dp, 44.dp))
                        CssFlexRow(gap = 8.dp, modifier = Modifier.onGloballyPositioned { span.x = it.positionInRoot().x / d.density; span.w = it.size.width / d.density }) {
                            Box(Modifier.flexFloor(0.dp).size(dotDp.dp).onGloballyPositioned { dot.x = it.positionInRoot().x / d.density; dot.w = it.size.width / d.density })
                            Text("Private runtime", style = style, modifier = Modifier.onGloballyPositioned { text.x = it.positionInRoot().x / d.density; text.w = it.size.width / d.density })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("span left = actions right", actionsW, span.x, 0.5f)
        assertEquals("the span fills the leftover", total - actionsW, span.w, 1f)
        assertEquals("dot left stays at the span's left", actionsW, dot.x, 0.5f)
        assertTrue("the dot narrowed", dot.w < dotDp)
        assertEquals("text left = dot + gap", dot.x + dot.w + 8f, text.x, 0.5f)
    }

    @Test fun flexTargetsFollowsTheCssResolution() {
        // Fits: the bases. Deficit 20 over bases 60/40 -> 48/32; a floor of 40 on the second freezes it, the first takes the rest.
        assertEquals(listOf(60f, 40f), flexTargets(intArrayOf(60, 40), intArrayOf(0, 0), 100f).toList())
        assertEquals(listOf(48f, 32f), flexTargets(intArrayOf(60, 40), intArrayOf(0, 0), 80f).toList())
        assertEquals(listOf(40f, 40f), flexTargets(intArrayOf(60, 40), intArrayOf(0, 40), 80f).toList())
        // The floors alone do not fit: every item sits at its floor.
        assertEquals(listOf(50f, 40f), flexTargets(intArrayOf(60, 40), intArrayOf(50, 40), 60f).toList())
    }
}
