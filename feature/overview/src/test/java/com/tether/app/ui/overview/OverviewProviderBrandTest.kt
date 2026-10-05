package com.tether.app.ui.overview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-coik.45: overview's `.providerMark` holding a verified mark takes its brand tile (tether 90fbb9f globals.css 11204-11224). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class OverviewProviderBrandTest {
    @get:Rule val rule = createComposeRule()

    private fun colours(tag: String): Map<Color, Int> {
        val px = rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        return out
    }

    @Test
    fun cardMarksAreBrandTiles() {
        rule.setContent { OverviewUnderTest(TetherSkin.StudioDark, OverviewFixtures.populated) }
        rule.waitForIdle()
        // A 1.4rem tile at 420dpi is about 59x59px.
        assertTrue("claude card", (colours(OverviewTags.card("s-sync"))[Color(0xFFD97757)] ?: 0) > 1_500)
        assertTrue("codex card", (colours(OverviewTags.card("s-proto"))[Color(0xFF0D0D0D)] ?: 0) > 1_500)
        val gemini = colours(OverviewTags.card("s-game"))
        assertTrue("gemini card: paper tile", (gemini[Color(0xFFFFFFFF)] ?: 0) > 1_500)
        assertTrue("gemini keeps its blue", (gemini[Color(0xFF1976D2)] ?: 0) > 20)
    }
}
