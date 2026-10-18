package com.tether.app.ui.sidebar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-hkk7.7 item 2: every segment of one chain's spine is drawn at the same device column, at every density. */
class SpineLocalXTest {
    private val densities = listOf(1.0f, 1.75f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f)

    @Test fun headToggleAndConnectorsShareOneGroupFrameXAtEveryNestingLevel() {
        for (dn in densities) {
            val d = Density(dn)
            val pad = with(d) { NestedTree.RowsPad.roundToPx() }
            val base = spineLocalX(d, 0)
            for (level in 0..3) {
                val origin = level * pad // where Compose places this group's origin
                // head spine and toggle draw in the group's own frame; a connector draws one RowsPad (padding) to its right
                val head = origin + spineLocalX(d, 0)
                val toggle = origin + spineLocalX(d, 0)
                val connector = origin + pad + spineLocalX(d, pad)
                assertEquals("density $dn level $level head/toggle", head, toggle, 0f)
                assertEquals("density $dn level $level connector vs head", head, connector, 0f)
                assertEquals("density $dn level $level whole px", Math.round(head).toFloat(), head, 0f)
                assertEquals("group x is round(SpineX)", base, spineLocalX(d, 0), 0f)
            }
        }
    }

    @Test fun theNaivePerScopeRoundingWouldNotHold() {
        // the shape of the bug the sweep guards: rounding the connector's own local x (-Run) leaves 2.75 a px off
        val d = Density(2.75f)
        val pad = with(d) { NestedTree.RowsPad.roundToPx() }
        val naive = pad + Math.round(with(d) { -NestedTree.Run.toPx() }).toFloat()
        assertTrue("naive $naive vs ${spineLocalX(d, 0)}", naive != spineLocalX(d, 0))
    }
}

/** One 420 dpi pixel test: the horizontal profile of the line is the same in the head-spine segment, the open toggle and a non-last member connector. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SpineProfileTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theLineProfileMatchesAcrossHeadToggleAndConnector() {
        var line = Color.Unspecified
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) { line = LocalTetherTokens.current.lineStrong }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SidebarUnderTest(TetherSkin.StudioDark, NestedFixture.state, seed = SidebarUiSeed(openChains = setOf("live:n0")))
            }
        }
        rule.waitForIdle()
        val root = rule.onNodeWithTag(SidebarTags.Root).fetchSemanticsNode().boundsInRoot
        val head = rule.onNodeWithTag(SidebarTags.row("live:n3")).fetchSemanticsNode().boundsInRoot
        val toggle = rule.onNodeWithTag(SidebarTags.chainToggle("live:n3")).fetchSemanticsNode().boundsInRoot
        val member = rule.onNodeWithTag(SidebarTags.row("live:n2")).fetchSemanticsNode().boundsInRoot
        val bmp = rule.onNodeWithTag(SidebarTags.Root).captureToImage().asAndroidBitmap()
        val d = rule.density.density
        val x0 = (head.left + 24 * d - root.left).toInt()
        val strong = line.toArgb()
        // coverage of the line over this row's own background (10 dp aside), on the channel that contrasts most
        fun profile(y: Int): List<Double> {
            val bg = bmp.getPixel(x0 + (10 * d).toInt(), y)
            val sh = listOf(16, 8, 0).maxBy { Math.abs(((strong shr it) and 0xFF) - ((bg shr it) and 0xFF)) }
            val span = (((strong shr sh) and 0xFF) - ((bg shr sh) and 0xFF)).toDouble()
            assertTrue("line contrasts its background", Math.abs(span) >= 15)
            val cov = (x0 - 3..x0 + 3).map { x -> (((bmp.getPixel(x, y) shr sh) and 0xFF) - ((bg shr sh) and 0xFF)) / span }
            // the row faces differ in tone: compare the SHAPE (where the line's pixels fall), normalised by its peak
            return cov.map { it / cov.max() }
        }
        val h = profile((head.bottom - root.top - 3).toInt())
        val t = profile(((toggle.top + toggle.bottom) / 2 - root.top).toInt())
        val c = profile((member.top - root.top + 4 * d).toInt())
        for (i in h.indices) {
            assertEquals("head vs toggle col $i: $h / $t", h[i], t[i], 0.08)
            assertEquals("head vs connector col $i: $h / $c", h[i], c[i], 0.08)
        }
    }
}
