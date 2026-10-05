package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.DelegateMention
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ta-coik.45: the chat surfaces' provider marks follow the web's brand rule (tether 90fbb9f
 * globals.css 11204-11233): a container holding the mark takes the brand tile (the settings trigger,
 * the model browser chip, the @-mention logos), and the Model select's / legacy row's
 * `.chat-mode-glyph` tints the mark with no tile.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatProviderBrandTest {
    @get:Rule val rule = createComposeRule()

    private val terracotta = Color(0xFFD97757)
    private val ink = Color(0xFF0D0D0D)
    private val paper = Color(0xFFFFFFFF)

    private fun ComposeContentTestRule.colours(tag: String): Map<Color, Int> {
        val px = onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        return out
    }

    private fun show(skin: TetherSkin, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalTetherTokens provides tokensFor(skin)) { Column { content() } }
    }

    @Test
    fun modelSelectGlyphIsTintedAndTheChipAndTriggerAreTiles() {
        show(TetherSkin.StudioDark) {
            Box(Modifier.width(200.dp).testTag("inline")) { ControlPill("Opus", true, "Model: Opus", {}, glyphProvider = "claude") }
            Box(Modifier.width(200.dp).testTag("chip")) { ControlPill("Opus", true, "Choose provider and model", {}, glyphProvider = "claude", glyphTile = true) }
            Box(Modifier.width(200.dp).testTag("trigger")) {
                SessionSettingsTrigger("Opus", "claude", autoOn = false, lock = null, hasOtherSettings = true, onOpen = {})
            }
        }
        val inline = rule.colours("inline")
        assertTrue("the inline mark is terracotta", (inline[terracotta] ?: 0) > 10)
        assertEquals("no paper mark on a tile: there is no tile", 0, inline[paper] ?: 0)
        for (tag in listOf("chip", "trigger")) {
            val px = rule.colours(tag)
            assertTrue("$tag: terracotta tile", (px[terracotta] ?: 0) > 80)
            assertTrue("$tag: paper mark", (px[paper] ?: 0) > 0)
        }
    }

    @Test
    fun legacyHintGlyphTakesTheInkMarkColourNotATile() {
        show(TetherSkin.Studio) { Column(Modifier.testTag("legacy")) { LegacyCodexHintRow("codex") } }
        val px = rule.colours("legacy")
        // `.chat-mode-glyph > svg[data-brand="codex"] { color: var(--white) }`: light `--white` is #182238.
        assertTrue("codex mark in --white", (px[TetherSkin.Studio.tokens.white] ?: 0) > 10)
        assertEquals("no ink tile", 0, px[ink] ?: 0)
    }

    @Test
    fun mentionLogosAreBrandTiles() {
        show(TetherSkin.StudioDark) {
            MentionMenu(CommandFixtures.catalog) {}
            DelegateBar(DelegateMention("claude", "message"), CommandFixtures.catalog.first(), {}, {})
        }
        val menu = rule.colours(MENTION_MENU_TAG)
        assertTrue("claude tile", (menu[terracotta] ?: 0) > 150)
        assertTrue("codex / opencode ink tiles", (menu[ink] ?: 0) > 150)
        val bar = rule.colours(DELEGATE_BAR_TAG)
        assertTrue("delegate chip tile", (bar[terracotta] ?: 0) > 150)
    }
}
