package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * ta-nm8u (W24): a grant row as the web's `<label><input type=checkbox>…</label>` (chat-view.tsx:1235-1281 at 29537e0): its
 * accessible name is the label text (the CDP tree: role checkbox, name = the label, source `labelwrapped`, with `disabled` and
 * `checked`), and a disabled row fades the BOX only (`input:disabled { opacity: .48 }`), drawn in Chrome's disabled palette.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GrantRowA11yTest {
    @get:Rule val rule = createComposeRule()

    private fun actions() = ConsentActions(
        sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { _, _, _, _ -> ConsentResult.Sent },
        onOpenRun = {},
    )

    private fun show(f: ChatFixtures.Folded) {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(
                    projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, consent = actions(), listState = LazyListState(), richCodex = true,
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)
    private fun name(n: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(n))
    private fun noBreaks() = SemanticsMatcher("no U+200B / U+2060 in the merged text") { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
        listOf(text, desc).none { "\u200B" in it || "\u2060" in it }
    }
    private val noText = SemanticsMatcher("no text") { it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() }

    @Test fun enabledRowsAreNamedByTheirLabelAsCheckboxes() {
        show(ApprovalFixtures.grants)
        val read = rule.onAllNodesWithTag("grant-read")
        read.assertCountEquals(2)
        read[0].assert(name("Read ${displayPath("/srv/fixtures")}")).assert(role(Role.Checkbox)).assertIsOn().assertIsEnabled().assert(noBreaks()).assert(noText)
        read[1].assert(name("Read ${displayPath("/srv/schema.sql")}")).assert(role(Role.Checkbox)).assertIsOn().assertIsEnabled().assert(noBreaks())
        rule.onNodeWithTag("grant-write").assert(name("Write ${displayPath("/w/report")}")).assert(role(Role.Checkbox)).assertIsOn().assertIsEnabled().assert(noBreaks())
        rule.onNodeWithTag("grant-network").assert(name("Network access")).assert(role(Role.Checkbox)).assertIsOn().assertIsEnabled()
        rule.onNodeWithTag("grant-confirm").assert(name(EXACT_CONFIRM_COPY)).assert(role(Role.Checkbox)).assertIsOff().assertIsEnabled()
    }

    @Test fun aLongPathIsOneNameAndItsLaterPiecesExposeNoText() {
        val deep = "/srv/" + "shared/fixtures/archive/2026/".repeat(100) + "tether"
        show(ApprovalFixtures.readPath(deep))
        val whole = "Read " + displayPath(deep) // one isolate around the whole path, as the web's one label
        assertTrue("the path is long enough to be drawn in pieces", displayPathChunks(deep).size > 1)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("grant-read"))
        rule.waitForIdle()
        rule.onAllNodesWithTag("grant-read")[0].assert(name(whole)).assert(noBreaks()).assert(noText)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("grant-path-piece"))
        rule.waitForIdle()
        val pieces = rule.onAllNodesWithTag("grant-path-piece").fetchSemanticsNodes()
        assertTrue("a later piece is drawn", pieces.isNotEmpty())
        pieces.forEach {
            assertTrue(it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
            assertTrue(it.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty())
        }
    }

    @Test fun anExactOnlyRequestHasDisabledTickedGrantRowsAndAnEnabledConfirm() {
        show(ApprovalFixtures.grantsExactOnly)
        rule.onAllNodesWithTag("grant-read")[0].assert(name("Read ${displayPath("/srv/fixtures")}")).assertIsNotEnabled().assertIsOn()
        rule.onAllNodesWithTag("grant-read")[1].assert(name("Read ${displayPath("/srv/schema.sql")}")).assertIsNotEnabled().assertIsOn()
        rule.onNodeWithTag("grant-write").assert(name("Write ${displayPath("/w/report")}")).assertIsNotEnabled().assertIsOn()
        rule.onNodeWithTag("grant-network").assert(name("Network access")).assertIsNotEnabled().assertIsOn()
        rule.onNodeWithTag("grant-confirm").assert(name(EXACT_CONFIRM_COPY)).assertIsEnabled().assertIsOff()
    }

    /** The composites the screen shows for a disabled box, at its border and at its fill (off the check's glyph). */
    private fun probe(skin: TetherSkin, checked: Boolean): Pair<Int, Int> {
        rule.setContent {
            TetherTheme(choiceFor(skin)) {
                val t = LocalTetherTokens.current
                // The card's own fill is what the box sits on, in both skins.
                Box(Modifier.testTag("host").background(t.attentionBg).padding(8.dp)) {
                    GrantCheckbox(checked = checked, enabled = false, onChange = {}, tag = "row", name = "row") { Text("label") }
                }
            }
        }
        rule.waitForIdle()
        val px = rule.onNodeWithTag("host").captureToImage().toPixelMap()
        val d = rule.density.density
        val x0 = Math.round(8 * d)
        val y = px.height / 2
        fun at(x: Int): Int = px[x, y].let { c -> (Math.round(c.red * 255) shl 16) or (Math.round(c.green * 255) shl 8) or Math.round(c.blue * 255) }
        // The border is the box's first dp, the fill starts after it.
        return at(x0 + 1) to at(x0 + Math.round(1.5f * d) + 1)
    }

    private fun near(want: Int, got: Int, what: String) {
        for (shift in listOf(16, 8, 0)) {
            val w = (want shr shift) and 0xFF
            val g = (got shr shift) and 0xFF
            assertTrue("$what: want #%06X got #%06X".format(want, got), abs(w - g) <= 3)
        }
    }

    // The composites are the web's: the served checked cell (b-412x915-card{,-dark}.json) and the standalone probe's unchecked one.
    @Test fun aDisabledTickedBoxIsGreyNotVioletInLight() = probe(TetherSkin.Studio, true).let { (border, fill) ->
        near(0xEAE6DE, border, "light checked border"); near(0xEAE6DE, fill, "light checked fill")
    }

    @Test fun aDisabledTickedBoxIsGreyNotVioletInDark() = probe(TetherSkin.StudioDark, true).let { (border, fill) ->
        near(0x514D49, border, "dark checked border"); near(0x514D49, fill, "dark checked fill")
    }

    @Test fun aDisabledUntickedBoxIsTheMeasuredPaletteInLight() = probe(TetherSkin.Studio, false).let { (border, fill) ->
        near(0xE9E5DD, border, "light unchecked border"); near(0xFCF8EF, fill, "light unchecked fill")
    }

    @Test fun aDisabledUntickedBoxIsTheMeasuredPaletteInDark() = probe(TetherSkin.StudioDark, false).let { (border, fill) ->
        near(0x484440, border, "dark unchecked border"); near(0x35322D, fill, "dark unchecked fill")
    }
}
