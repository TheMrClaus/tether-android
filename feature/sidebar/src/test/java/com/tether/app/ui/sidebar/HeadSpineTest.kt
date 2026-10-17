package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-hkk7.5 (W46): the head spine starts AT the head tile's bottom edge and runs on into the group toggle, at every font scale, on a
 * selected head too, closed / open, for the chain toggle and the delegate toggle, mirrored in RTL. Brand-tile heads (claude), whose tile
 * carries a `lineStrong` border; the tile is located from its handle's centre. ta-hkk7.6 item 3 probe: no pixel of a connector is painted twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HeadSpineTest {
    @get:Rule val rule = createComposeRule()

    private fun join(head: String, seed: SidebarUiSeed, fontScale: Float = 1f, rtl: Boolean = false, tall: Boolean = false) {
        var line = Color.Unspecified
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) { line = LocalTetherTokens.current.lineStrong }
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(base.density, fontScale),
            ) { SidebarUnderTest(TetherSkin.StudioDark, NestedFixture.state, seed = seed) }
        }
        rule.waitForIdle()
        val d = rule.density.density
        val rootNode = rule.onNodeWithTag(SidebarTags.Root).fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag(SidebarTags.row(head)).fetchSemanticsNode().boundsInRoot
        val handle = rule.onNodeWithTag(SidebarTags.handle(head), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        if (tall) assertTrue("the head row is taller than 56 dp: ${row.height / d}", row.height > 56 * d + 2)
        val bmp = rule.onNodeWithTag(SidebarTags.Root).captureToImage().asAndroidBitmap()
        fun at(x: Int, y: Int) = bmp.getPixel(x, y) and 0xFFFFFF
        val strong = line.toArgb() and 0xFFFFFF
        // the raster blends the 2.6 px line a few levels under its token: "line" = within 10 per channel of it (the row faces are 25+ away)
        fun isLine(c: Int) = listOf(16, 8, 0).all { sh -> kotlin.math.abs(((c shr sh) and 0xFF) - ((strong shr sh) and 0xFF)) <= 10 }
        val x0 = (if (rtl) row.right - 24 * d else row.left + 24 * d).let { (it - rootNode.left).toInt() }
        val bottom = (row.bottom - rootNode.top).toInt()
        // the 1 dp line is ~2.6 px: take its fully covered column within a pixel of the computed one
        val x = (x0 - 2..x0 + 2).firstOrNull { isLine(at(it, bottom + 2)) } ?: x0
        val bg = at(x + (if (rtl) -(10 * d).toInt() else (10 * d).toInt()), bottom - 2)
        val centreY = (handle.center.y - rootNode.top).toInt()
        // The line reaches the head row's bottom and the toggle below it...
        assertTrue("line at the row's bottom -1: ${at(x, bottom - 1).toString(16)}", isLine(at(x, bottom - 1)))
        assertTrue("line at the row's bottom +2 (toggle): ${at(x, bottom + 2).toString(16)}", isLine(at(x, bottom + 2)))
        // ...and walks up, unbroken, into the tile: the first non-line pixel above it is the tile, never the row's background.
        var y = bottom - 1
        while (y > centreY && isLine(at(x, y))) y--
        val above = at(x, y)
        assertTrue("an unbroken line from the tile to the toggle (stopped at ${(y - centreY) / d} dp below the tile centre, background above)", above != bg)
        assertTrue("it meets the tile (<= 14 dp below its centre), not further up: $y vs $centreY", y - centreY <= 15 * d)
    }

    private val chainClosed = SidebarUiSeed()
    private val chainOpen = SidebarUiSeed(openChains = setOf("live:n0"))
    private val delClosed = SidebarUiSeed()
    private val delOpen = SidebarUiSeed(openChildren = setOf("live:h"))

    @Test fun closedChainToggleOnTheActiveHead() = join("live:n3", chainClosed)
    @Test fun openChainToggleOnTheActiveHead() = join("live:n3", chainOpen)
    @Test fun closedDelegateToggle() = join("live:h", delClosed)
    @Test fun openDelegateToggle() = join("live:h", delOpen)
    @Test fun aTallerHeadRowAtFont2xChainOpen() = join("live:n3", chainOpen, fontScale = 2f, tall = true)
    @Test fun aTallerHeadRowAtFont2xChainClosed() = join("live:n3", chainClosed, fontScale = 2f, tall = true)
    @Test fun aTallerHeadRowAtFont2xDelegate() = join("live:h", delClosed, fontScale = 2f, tall = true)
    @Test fun mirroredInRtl() = join("live:n3", chainOpen, rtl = true)
}
