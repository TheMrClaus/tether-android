package com.tether.app.ui.settings

import android.icu.text.BreakIterator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import java.util.Locale
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-c3ie (W24): the account card's head keeps Rename and Check beside the title at every width. The web's
 * `.engine-card-head` is one nowrap, centre-aligned row with a 12 gap (studio.css:643 over globals.css:3128-3129):
 * the keys keep their intrinsic width, the title shrinks and wraps, and the plan and Pre-existing tags are
 * `display: none` (studio.css:344). Through SettingsFrame with the production defaults, as SettingsRowsBandTest.
 */
abstract class AccountCardHeadBase(private val width: Int) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val narrow = width <= 640

    private class R(val l: Float, val t: Float, val w: Float, val h: Float) {
        val r get() = l + w
        val b get() = t + h
        val cy get() = t + h / 2f
        override fun toString() = "[$l,$t ${w}x$h]"
    }

    private val d get() = compose.density.density

    private fun SemanticsNodeInteraction.rect(): R {
        val n = fetchSemanticsNode()
        val p = n.positionInRoot
        return R(p.x / d, p.y / d, n.size.width / d, n.size.height / d)
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true).rect()

    private fun inCard(id: String, text: String) =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(ClaudeAccountsTags.card(id))), useUnmergedTree = true).onFirst().rect()

    private fun show(claudeAccounts: ClaudeAccountsBinding = AccountsShot.Loaded.binding()) {
        val stored = runBlocking { store.prefs.preferences.first() }
        val state = SettingsDialogState(SettingsTab.Engines, GeneralDraft.of(stored))
        compose.setContent {
            TetherTheme(TetherSkin.Studio.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SettingsFrame(
                        prefs = store.prefs,
                        state = state,
                        restartRequired = false,
                        currentWorkspace = CURRENT,
                        onClose = {},
                        layout = if (narrow) TetherLayoutClass.Phone else TetherLayoutClass.Expanded,
                        initialPreferences = stored,
                        claudeAccounts = claudeAccounts,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun head(id: String, title: String, keys: List<String>) {
        val card = tag(ClaudeAccountsTags.card(id))
        val titleRect = inCard(id, title)
        val glyph = inCard(id, "C")
        val pad = if (narrow) 16f else 20f
        val rects = keys.map { tag(it) }
        for ((i, key) in rects.withIndex()) {
            assertTrue("$width $id key $i $key is beside the title $titleRect", key.l >= titleRect.r - 0.5f && key.t < titleRect.b)
            assertEquals("$width $id key $i is centred on the head (glyph $glyph)", glyph.cy, key.cy, 1f)
        }
        // Check ends at the card's content edge; Rename sits 12 before it.
        assertEquals("$width $id: Check ends at the card's content edge (card $card)", card.r - pad, rects.last().r, 1f)
        if (rects.size == 2) assertEquals("$width $id: Rename to Check is 12", 12f, rects[1].l - rects[0].r, 0.5f)
        // Every key keeps its intrinsic width (the same in dp at every width).
        for ((i, key) in rects.withIndex()) {
            val expected = if (keys.size == 2 && i == 0) RENAME_WIDTH else CHECK_WIDTH
            // The same in dp at every width (text rounds to whole pixels per density: 104.8 dp at 420 dpi, 106 at mdpi).
            assertEquals("$width $id key $i keeps its intrinsic width", expected, key.w, 1.5f)
        }
    }

    @Test fun theKeysSitBesideTheTitleAt12() {
        show()
        head("claude-work", "Claude Code (work)", listOf(ClaudeAccountsTags.rename("claude-work"), ClaudeAccountsTags.check("claude-work")))
        head("claude-default", "Claude Code (default)", listOf(ClaudeAccountsTags.check("claude-default")))
    }

    /** The text layouts drawn in [id]'s head title column, one per Text node (a node may hold several lines). */
    private fun titleLayouts(id: String): List<TextLayoutResult> =
        compose.onAllNodes(
            hasAnyAncestor(hasTestTag(ClaudeAccountsTags.head(id))) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { node ->
            val out = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            out[0]
        }

    private fun breaks(text: String): Set<Int> {
        val it = BreakIterator.getLineInstance(Locale.ROOT)
        it.setText(text)
        return generateSequence(it.first().takeIf { b -> b != BreakIterator.DONE }) { _ -> it.next().takeIf { b -> b != BreakIterator.DONE } }.toSet()
    }

    @Test fun theGlyphAndTitleShrinkLikeTheWebsFlexRow() {
        show()
        val glyph = tag(ClaudeAccountsTags.glyph("claude-work"))
        val title = tag(ClaudeAccountsTags.head("claude-work"))
        assertEquals("$width: the glyph is 32 tall at every width ($glyph)", 32f, glyph.h, 0.5f)
        if (width == 412) {
            // The web's own measures (c3ie-premerge-probe, Rename-104 variant): glyph 12.1, title 66.9. The app
            // reaches 12.5 and 67.7 from its own key widths (Rename 104.8, Check 94: 1.2 dp more room) and text
            // metrics (title max-content 173 against the web's 177); the same flex-shrink arithmetic.
            assertEquals("412: the glyph shrinks to the web's 12.1", 12.1f, glyph.w, 1f)
            assertEquals("412: the title column is the web's 66.9", 66.9f, title.w, 1f)
        }
        if (width >= 641) assertEquals("$width: the glyph keeps its 32 basis when the head has room", 32f, glyph.w, 0.5f)
        assertTrue("$width: the glyph never grows past 32 ($glyph)", glyph.w <= 32.5f)
        // The title takes what is left between the glyph and the keys, 12 either side.
        assertEquals("$width: glyph to title is 12", 12f, title.l - glyph.r, 0.5f)
        assertEquals("$width: title to Rename is 12", 12f, tag(ClaudeAccountsTags.rename("claude-work")).l - title.r, 0.5f)
    }

    @Test fun noTitleOrSmallLineEndsInsideAWord() {
        show()
        for (id in listOf("claude-default", "claude-work")) {
            val layouts = titleLayouts(id)
            assertTrue("$width $id: the head draws text", layouts.isNotEmpty())
            for (r in layouts) {
                val text = r.layoutInput.text.text
                val ok = breaks(text)
                for (line in 0 until r.lineCount - 1) {
                    assertTrue("$width $id: \"$text\" breaks inside a word at ${r.getLineEnd(line)}", r.getLineEnd(line) in ok)
                }
            }
        }
    }

    @Test fun anOverlongWordIsDrawnWholeAndUnclipped() {
        show()
        val title = tag(ClaudeAccountsTags.head("claude-work"))
        val email = titleLayouts("claude-work").first { "work@example.com" in it.layoutInput.text.text }
        val text = email.layoutInput.text.text
        val line = (0 until email.lineCount).first { "work@example.com" in text.substring(email.getLineStart(it), email.getLineEnd(it, visibleEnd = true)) }
        val drawn = (email.getLineRight(line) - email.getLineLeft(line)) / d
        assertTrue("$width: the email is on one line, drawn whole and not ellipsized", !email.isLineEllipsized(line))
        assertTrue("$width: drawn $drawn dp is at least the unwrapped ${email.multiParagraph.maxIntrinsicWidth / d} of its own line", drawn > 90f)
        if (width == 412) {
            assertEquals("412: the email line is its own", "work@example.com", text.trim())
            assertTrue("412: it overflows the $title column (drawn $drawn dp)", drawn >= title.w + 20f)
            assertTrue("412: and is drawn at its unwrapped width", drawn >= email.multiParagraph.maxIntrinsicWidth / d - 1f)
        }
    }

    /**
     * A word wider than its column is drawn whole, never split (the web's `overflow-wrap: normal` on the title).
     * The fixture is this test's own: a "work" account whose label holds one over-long word with no hyphen or slash.
     */
    @Test fun aTitleWordWiderThanItsColumnIsDrawnWholeNotSplit() {
        val word = "(supercalifragilistic)"
        val shot = AccountsShot.Loaded
        val seed = shot.seed()
        val accounts = seed.accounts!!.map { if (it.id == "claude-work") it.copy(label = "Claude Code $word") else it }
        show(ClaudeAccountsBinding(FakeAccounts(), AccountsFixtures.ORIGIN, AccountsFixtures.TIME, initial = seed.copy(accounts = accounts)))
        val column = tag(ClaudeAccountsTags.head("claude-work"))
        val layouts = titleLayouts("claude-work")
        for (r in layouts) {
            val text = r.layoutInput.text.text
            val ok = breaks(text)
            for (line in 0 until r.lineCount - 1) {
                assertTrue("$width: \"$text\" breaks inside a word at ${r.getLineEnd(line)}", r.getLineEnd(line) in ok)
            }
        }
        val holder = layouts.first { word in it.layoutInput.text.text }
        val text = holder.layoutInput.text.text
        val line = (0 until holder.lineCount).first { word in text.substring(holder.getLineStart(it), holder.getLineEnd(it, visibleEnd = true)) }
        assertTrue("$width: the word is not ellipsized", !holder.isLineEllipsized(line))
        val drawn = (holder.getLineRight(line) - holder.getLineLeft(line)) / d
        if (width == 412) {
            // In this squeeze the word has a line of its own (one Text per line), so its intrinsic width is the word's.
            val whole = holder.multiParagraph.maxIntrinsicWidth / d
            assertEquals("412: the word's line is the word alone", word, text.trim())
            assertTrue("412: the word is drawn at $drawn dp, its unwrapped width is $whole", drawn >= whole - 1f)
            assertTrue("412: the word ($drawn dp) is wider than its ${column.w} dp column, so it overflows it whole", drawn > column.w + 20f)
        }
    }

    @Test fun noPlanOrPreExistingTagIsInTheHead() {
        show()
        for (id in listOf("claude-default", "claude-work", "claude-fresh")) {
            compose.onNodeWithTag(ClaudeAccountsTags.plan(id), useUnmergedTree = true).assertDoesNotExist()
        }
        for (text in listOf("Team Premium 5x", "Team Standard", "Pre-existing")) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private companion object {
        const val RENAME_WIDTH = 106f
        const val CHECK_WIDTH = 94f
    }
}

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w412dp-h915dp-420dpi") class AccountCardHead412Test : AccountCardHeadBase(412)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w560dp-h900dp-mdpi") class AccountCardHead560Test : AccountCardHeadBase(560)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w561dp-h900dp-mdpi") class AccountCardHead561Test : AccountCardHeadBase(561)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w640dp-h900dp-mdpi") class AccountCardHead640Test : AccountCardHeadBase(640)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w641dp-h900dp-mdpi") class AccountCardHead641Test : AccountCardHeadBase(641)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w1280dp-h800dp-mdpi") class AccountCardHead1280Test : AccountCardHeadBase(1280)
