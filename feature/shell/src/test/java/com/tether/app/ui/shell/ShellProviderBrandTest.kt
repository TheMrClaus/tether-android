package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.ui.inspector.Inspector
import com.tether.app.ui.inspector.InspectorBoards
import com.tether.app.ui.inspector.InspectorTags
import com.tether.app.ui.statusline.screenshots.choiceFor
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
 * ta-coik.45: the shell's provider marks take the web's brand tile (tether 90fbb9f globals.css
 * 11204-11224) — the Studio welcome's provider entries (gemini keeping its own four colours: a bare
 * tinted Icon would paint it blue) and the inspector's `.ti-mark`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ShellProviderBrandTest {
    @get:Rule val rule = createComposeRule()

    private val terracotta = Color(0xFFD97757)
    private val ink = Color(0xFF0D0D0D)
    private val paper = Color(0xFFFFFFFF)
    private val blue = Color(0xFF4D6BFE)
    private val geminiFills = listOf(0xFFFFC107, 0xFFFF3D00, 0xFF4CAF50, 0xFF1976D2).map { Color(it) }

    private fun colours(tag: String): Map<Color, Int> {
        val px = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        return out
    }

    @Test
    fun welcomeEntriesTakeTheirBrandTileAndGeminiKeepsItsColours() {
        val providers = listOf(
            ProviderAvailability("Claude Code", true, "claude"),
            ProviderAvailability("Codex", true, "codex"),
            ProviderAvailability("Gemini", true, "gemini"),
            ProviderAvailability("Reasonix", true, "reasonix"),
            ProviderAvailability("ACP", false, "acp"),
        )
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                StudioWelcome(true, providers, onNewSession = {}, onOpenWorkspace = {}, expanded = false)
            }
        }
        assertTrue("claude: terracotta", (colours(StudioWelcomeTags.provider(0))[terracotta] ?: 0) > 200)
        assertTrue("codex: ink", (colours(StudioWelcomeTags.provider(1))[ink] ?: 0) > 200)
        val gemini = colours(StudioWelcomeTags.provider(2))
        assertTrue("gemini: paper tile", (gemini[paper] ?: 0) > 200)
        for (fill in geminiFills) assertTrue("gemini keeps $fill", (gemini[fill] ?: 0) > 10)
        val whale = colours(StudioWelcomeTags.provider(3))
        assertTrue("reasonix: paper tile", (whale[paper] ?: 0) > 200)
        assertTrue("reasonix: blue mark", (whale[blue] ?: 0) > 30)
        val acp = colours(StudioWelcomeTags.provider(4))
        assertEquals("a letter takes no tile", 0, (acp[paper] ?: 0) + (acp[ink] ?: 0) + (acp[terracotta] ?: 0))
    }

    @Test
    fun theInspectorMarkIsABrandTile() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                Column(Modifier.width(380.dp)) {
                    Inspector(InspectorBoards.model(InspectorBoards.session(provider = "codex")), null, {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
                }
            }
        }
        assertTrue("codex: ink tile", (colours(InspectorTags.Identity)[ink] ?: 0) > 400)
    }
}
