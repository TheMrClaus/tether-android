package com.tether.app.ui.icons

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The brand-coloured harness tiles and inline glyphs of tether 90fbb9f app/globals.css 11204-11233. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderBrandTest {
    @get:Rule val rule = createComposeRule()

    // app/studio.css 51-54, verbatim.
    private val claude = Color(0xFFD97757)
    private val ink = Color(0xFF0D0D0D)
    private val paper = Color(0xFFFFFFFF)
    private val blue = Color(0xFF4D6BFE)

    @Test
    fun brandTokensAreTheWebsAndSharedByBothSkins() {
        for (skin in TetherSkin.entries) {
            val c = skin.tokens
            assertEquals("$skin --brand-claude", claude, c.brandClaude)
            assertEquals("$skin --brand-ink", ink, c.brandInk)
            assertEquals("$skin --brand-paper", paper, c.brandPaper)
            assertEquals("$skin --brand-blue", blue, c.brandBlue)
        }
    }

    @Test
    fun everyWebBrandMapsToItsTile() {
        val expected = mapOf(
            "claude" to ProviderBrandTile(claude, paper),
            "codex" to ProviderBrandTile(ink, paper),
            "opencode" to ProviderBrandTile(ink, paper),
            "pi" to ProviderBrandTile(ink, paper),
            "reasonix" to ProviderBrandTile(paper, blue),
            "dsh" to ProviderBrandTile(paper, blue),
            "gemini" to ProviderBrandTile(paper, blue),
        )
        // Every verified mark has a brand rule on the web.
        assertEquals(ProviderLogos.marks.keys, expected.keys)
        for (skin in TetherSkin.entries) {
            for ((id, tile) in expected) assertEquals("$skin $id", tile, ProviderBrand.tile(id, skin.tokens))
        }
    }

    @Test
    fun inlineGlyphTintsFollowTheChatModeGlyphRule() {
        for (skin in TetherSkin.entries) {
            val c = skin.tokens
            assertEquals(claude, ProviderBrand.glyphTint("claude", c))
            // `--white` follows the skin (light: the dark text colour).
            for (id in listOf("codex", "opencode", "pi")) assertEquals("$skin $id", c.white, ProviderBrand.glyphTint(id, c))
            assertEquals(blue, ProviderBrand.glyphTint("reasonix", c))
            assertEquals(blue, ProviderBrand.glyphTint("dsh", c))
            // No rule for gemini: its four fills are its own.
            assertNull(ProviderBrand.glyphTint("gemini", c))
        }
        assertEquals(Color(0xFF182238), ProviderBrand.glyphTint("codex", TetherSkin.Studio.tokens))
    }

    @Test
    fun letterFallbacksTakeNoBrand() {
        for (skin in TetherSkin.entries) {
            for (id in listOf("acp", "future-agent", "Claude", "", null)) {
                assertNull("$id tile", ProviderBrand.tile(id, skin.tokens))
                assertNull("$id glyph", ProviderBrand.glyphTint(id, skin.tokens))
            }
        }
    }

    private fun counts(tag: String): Map<Color, Int> {
        val px = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        return out
    }

    @Test
    fun tilesPaintTheBrandAndLetterFallbacksKeepTheNeutralTile() {
        val neutral = Color(0xFF00FF00) // green: in no brand
        val edge = Color(0xFFFF00FF)
        val content = Color(0xFF00FFFF)
        rule.setContent {
            CompositionLocalProvider(LocalTetherTokens provides tokensFor(TetherSkin.StudioDark)) {
                Row {
                    for (id in listOf("claude", "codex", "pi", "reasonix", "gemini", "acp")) {
                        ProviderTile(
                            id,
                            Modifier.size(48.dp).testTag(id),
                            background = neutral,
                            border = CssBorder(2.dp, edge),
                            color = content,
                            markSize = 28.dp,
                        )
                    }
                }
            }
        }
        for ((id, tile) in listOf("claude" to ProviderBrandTile(claude, paper), "codex" to ProviderBrandTile(ink, paper), "pi" to ProviderBrandTile(ink, paper), "reasonix" to ProviderBrandTile(paper, blue))) {
            val px = counts(id)
            val total = px.values.sum()
            assertEquals("$id: no neutral background", 0, px[neutral] ?: 0)
            assertEquals("$id: the border goes transparent", 0, px[edge] ?: 0)
            assertEquals("$id: the mark never takes the caller's colour", 0, px[content] ?: 0)
            assertTrue("$id: brand background", (px[tile.background] ?: 0) > total / 3)
            assertTrue("$id: brand mark", (px[tile.content] ?: 0) > total / 50)
        }
        val gemini = counts("gemini")
        assertTrue("gemini: paper tile", (gemini[paper] ?: 0) > gemini.values.sum() / 3)
        assertEquals("gemini: never tinted blue", 0, gemini[blue] ?: 0)
        for (fill in listOf(0xFFFFC107, 0xFFFF3D00, 0xFF4CAF50, 0xFF1976D2)) {
            assertTrue("gemini keeps ${fill.toString(16)}", (gemini[Color(fill)] ?: 0) > 20)
        }
        val acp = counts("acp")
        assertTrue("letter: neutral tile", (acp[neutral] ?: 0) > acp.values.sum() / 3)
        assertTrue("letter: its border", (acp[edge] ?: 0) > 0)
        rule.onNodeWithText("A").assertExists()
    }

    @Test
    fun inlineGlyphsTintTheMarkAndKeepTheCallersColourOtherwise() {
        val caller = Color(0xFF00FF00)
        rule.setContent {
            CompositionLocalProvider(LocalTetherTokens provides tokensFor(TetherSkin.Studio)) {
                Row {
                    for (id in listOf("claude", "codex", "dsh", "gemini")) {
                        ProviderInlineGlyph(id, Modifier.testTag(id), color = caller, markSize = 48.dp)
                    }
                    ProviderInlineGlyph("acp", color = caller, markSize = 48.dp)
                }
            }
        }
        val light = TetherSkin.Studio.tokens
        for ((id, tint) in listOf("claude" to claude, "codex" to light.white, "dsh" to blue)) {
            val px = counts(id)
            assertEquals("$id: not the caller's colour", 0, px[caller] ?: 0)
            assertTrue("$id: tinted", (px[tint] ?: 0) > px.values.sum() / 20)
        }
        val gemini = counts("gemini")
        assertEquals("gemini ignores the caller's colour", 0, gemini[caller] ?: 0)
        assertTrue((gemini[Color(0xFF1976D2)] ?: 0) > 20)
        rule.onNodeWithText("A").assertExists()
    }
}
