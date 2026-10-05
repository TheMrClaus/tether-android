package com.tether.app.ui.sidebar

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.45: [ProviderCap] — every sidebar `.provider-glyph` (session rows, the harness menu, archived
 * rows, global search's chips and hits) — takes the brand tile of a verified mark (tether 90fbb9f
 * globals.css 11204-11224); a letter keeps the raised neutral tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProviderCapBrandTest {
    @get:Rule val rule = createComposeRule()

    private fun colours(tag: String): Map<Color, Int> {
        val px = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        return out
    }

    @Test
    fun capsTakeTheBrandTile() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                Row {
                    for (id in listOf("claude", "pi", "dsh", "acp")) ProviderCap(id, 28.dp, Modifier.testTag(id))
                }
            }
        }
        val raised = TetherSkin.StudioDark.tokens.graphiteRaised
        for ((id, bg) in listOf("claude" to Color(0xFFD97757), "pi" to Color(0xFF0D0D0D), "dsh" to Color(0xFFFFFFFF))) {
            val px = colours(id)
            assertTrue("$id: brand background", (px[bg] ?: 0) > px.values.sum() / 3)
            assertEquals("$id: no neutral tile", 0, px[raised] ?: 0)
        }
        val acp = colours("acp")
        assertTrue("a letter keeps the raised tile", (acp[raised] ?: 0) > acp.values.sum() / 3)
        rule.onNodeWithText("A").assertExists()
    }
}
