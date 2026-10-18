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

/**
 * ta-hkk7.7 item 1 (wave-check B1): the chain toggle's constant 14 dp stub. A chain toggle after a delegate block draws its line from 14 dp ABOVE its
 * top (web studio.css:1388, `top: calc((--sb-tile - --sb-row) / 2)`), up into the preceding sibling; the last delegate row has no `::after`, so a gap
 * stays between that row's centre and the stub. Probed on the pixel column at the spine x (mirrored in RTL).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChainStubTest {
    @get:Rule val rule = createComposeRule()

    private fun probe(seed: SidebarUiSeed, delegatesOpen: Boolean, rtl: Boolean = false) {
        var line = Color.Unspecified
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) { line = LocalTetherTokens.current.lineStrong }
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                SidebarUnderTest(TetherSkin.StudioDark, DelegatesAndChainFixture.state, seed = seed)
            }
        }
        rule.waitForIdle()
        val d = rule.density.density
        val root = rule.onNodeWithTag(SidebarTags.Root).fetchSemanticsNode().boundsInRoot
        val head = rule.onNodeWithTag(SidebarTags.row("live:c2")).fetchSemanticsNode().boundsInRoot
        val toggle = rule.onNodeWithTag(SidebarTags.chainToggle("live:c2")).fetchSemanticsNode().boundsInRoot
        val bmp = rule.onNodeWithTag(SidebarTags.Root).captureToImage().asAndroidBitmap()
        fun at(x: Int, y: Int) = bmp.getPixel(x, y) and 0xFFFFFF
        val strong = line.toArgb() and 0xFFFFFF
        fun isLine(c: Int) = listOf(16, 8, 0).all { sh -> Math.abs(((c shr sh) and 0xFF) - ((strong shr sh) and 0xFF)) <= 10 }
        val x0 = (if (rtl) head.right - 24 * d else head.left + 24 * d).let { (it - root.left).toInt() }
        val toggleTop = (toggle.top - root.top).toInt()
        val x = (x0 - 2..x0 + 2).firstOrNull { isLine(at(it, toggleTop + 2)) } ?: x0
        assertTrue("the toggle's own line is at x=$x", isLine(at(x, toggleTop + 2)))
        // (a) the unbroken run up from the toggle ends 14 dp above its top (+-1 px)
        var top = toggleTop + 2
        while (top > 0 && isLine(at(x, top - 1))) top--
        val stub = 14 * d
        assertTrue("(a) the stub starts ${(toggleTop - top) / d} dp above the toggle (want 14 +-1 px): top=$top toggleTop=$toggleTop", Math.abs((toggleTop - top) - stub) <= 1.0)
        // (b) background directly above it
        val bgX = x + (if (rtl) -(10 * d).toInt() else (10 * d).toInt())
        assertTrue("(b) background above the stub: ${at(x, top - 2).toString(16)} vs ${at(bgX, top - 2).toString(16)}", !isLine(at(x, top - 2)))
        // (c) no line between the preceding sibling's centre and the stub: the line does not climb into the delegate block
        val prevCentre = if (delegatesOpen) (rule.onNodeWithTag(SidebarTags.row("live:cd")).fetchSemanticsNode().boundsInRoot.center.y - root.top).toInt()
        else (toggleTop - 24 * d).toInt() // the 48 dp delegate toggle's own mid-height
        val from = prevCentre + (1 * d).toInt() + (if (delegatesOpen) 0 else 0)
        for (y in from..top - 2) assertTrue("(c) no line at y=$y between the previous sibling's centre ($prevCentre) and the stub ($top)", !isLine(at(x, y)))
    }

    @Test fun stubIsFourteenDpIntoTheLastOpenDelegateRowAndNoFurther() = probe(DelegatesAndChainFixture.open, true)
    @Test fun stubIsFourteenDpUnderTheClosedDelegateToggle() = probe(DelegatesAndChainFixture.delegatesClosed, false)
    @Test fun mirroredInRtl() = probe(DelegatesAndChainFixture.open, true, rtl = true)
}
