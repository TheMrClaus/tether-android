package com.tether.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-28i: the LIVE question card draws the agent's question, header, option labels and
 * descriptions by the prose rule (every explicit bidi control a token, text in its content's
 * direction), as the answered card already did; the approval card's tool name is code. The answer
 * sent back carries the agent's label exactly (display only is encoded), and a 4,000-character cut
 * never splits a surrogate pair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class QuestionCardSafeTextTest {
    @get:Rule val rule = createComposeRule()

    private companion object {
        const val RLO = "\u202E"
        const val PDF = "\u202C"
        const val LRI = "\u2066"
        const val PDI = "\u2069"
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
        const val QUESTION = "Should I ${RLO}eteled$PDF the backups?"
        const val OPTION = "${LRI}No$PDI ${LRI}Yes$PDI"
        val BIDI = ('\u202A'..'\u202E') + ('\u2066'..'\u2069')
        fun tok(cp: Int) = "\u2060\u27E8U+%04X\u27E9".format(cp)
    }

    private val sent = mutableListOf<String>()

    private fun fixture(question: String = QUESTION, toolName: String = "Bash${RLO}hsab") = ChatFixtures.fold(
        ev("turn_started", "t1", ts = ApprovalFixtures.T) { put("idempotencyKey", "k-t1") },
        ev("user_message_accepted", "t1", ts = ApprovalFixtures.T) { put("text", "Clean up.") },
        ev("tool_start", "t1", ts = ApprovalFixtures.T) { put("toolId", "ask-1"); put("name", "AskUserQuestion"); putJsonObject("input") {} },
        ev("question_request", "t1", ts = ApprovalFixtures.T) {
            put("requestId", "q-1"); put("toolId", "ask-1")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", question); put("header", "Dan${RLO}ger"); put("multiSelect", false)
                    putJsonArray("options") {
                        addJsonObject { put("label", OPTION); put("description", "Keeps ${RLO}lla$PDF of them") }
                        addJsonObject { put("label", HEBREW); put("description", "") }
                    }
                }
            }
        },
        ev("tool_start", "t1", ts = ApprovalFixtures.T) { put("toolId", "tool-x"); put("name", toolName) },
        ev("approval_request", "t1", ts = ApprovalFixtures.T) {
            put("requestId", "req-x"); put("toolId", "tool-x"); put("name", toolName)
            putJsonObject("input") { put("command", "ls") }
        },
    )

    private fun show(f: ChatFixtures.Folded) {
        val consent = ConsentActions(
            sessionId = "s1",
            origin = TEST_ORIGIN,
            lock = null,
            decided = emptySet(),
            questionUnavailable = null,
            onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
            onAnswer = { requestId, _, picks, skipped ->
                val request = com.tether.app.client.ConsentGuard.pendingQuestion(f.tree, requestId)
                sent += request?.let { com.tether.app.client.ConsentGuard.buildAnswers(it, picks, skipped) }?.answers.toString()
                ConsentResult.Sent
            },
            onOpenRun = {},
        )
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                CompositionLocalProvider(LocalCardStates provides CardStateStore()) {
                    ChatTranscript(
                        projection = f.projection,
                        tree = f.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        consent = consent,
                        listState = androidx.compose.foundation.lazy.LazyListState(),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
    }

    private fun layoutOf(text: String): Pair<String, TextLayoutResult> {
        val node = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().let { it.layoutInput.text.text to it }
    }

    @Test fun theQuestionItsOptionsAndTheToolNameShowEveryBidiControl() {
        show(fixture())
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-card"))
        rule.waitForIdle()
        val shown = spoken()
        assertTrue(shown.contains("Should I ${tok(0x202E)}eteled${tok(0x202C)} the backups?"))
        assertTrue(shown.contains("DAN${tok(0x202E)}GER"))
        assertTrue(shown.contains("${tok(0x2066)}No${tok(0x2069)} ${tok(0x2066)}Yes${tok(0x2069)}"))
        assertTrue(shown.contains("Keeps ${tok(0x202E)}lla${tok(0x202C)} of them"))
        // A real Hebrew option stays whole.
        assertEquals(HEBREW, layoutOf(HEBREW).first)
        for (s in shown) for (c in BIDI) assertFalse("raw U+%04X in \"$s\"".format(c.code), s.contains(c))
        // The words read in their stored order.
        val (text, layout) = layoutOf("eteled")
        val at = text.indexOf("eteled")
        assertTrue(layout.getBoundingBox(at).left < layout.getBoundingBox(at + 5).left)
    }

    @Test fun theApprovalCardsToolNameIsCode() {
        show(fixture())
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-card"))
        rule.waitForIdle()
        assertTrue(spoken().toString(), spoken().any { it == "The agent wants to run  Bash${tok(0x202E)}hsab ." })
        for (s in spoken()) assertFalse("raw RLO in \"$s\"", s.startsWith("The agent wants to run") && s.contains(RLO))
    }

    @Test fun theApprovalCardsToolNameEscapesStackedCombiningMarksFromTheThird() {
        // ta-d2cx: four marks on one base: 1 and 2 stay, 3 and 4 are written out, as in a path.
        show(fixture(toolName = "Ba\u0301\u0302\u0303\u0304sh"))
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-card"))
        rule.waitForIdle()
        assertTrue(spoken().toString(), spoken().any { it == "The agent wants to run  Ba\u0301\u0302\\u0303\\u0304sh ." })
    }

    @Test fun theApprovalCardsAccentedToolNameIsUnchanged() {
        show(fixture(toolName = "Cafe\u0301_to\u0302ol"))
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-card"))
        rule.waitForIdle()
        assertTrue(spoken().toString(), spoken().any { it == "The agent wants to run  Cafe\u0301_to\u0302ol ." })
    }

    @Test fun theAnswerCarriesTheAgentsLabelExactly() {
        show(fixture())
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-submit"))
        rule.waitForIdle()
        rule.onNodeWithText("${tok(0x2066)}No", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("question-submit").performClick()
        rule.waitForIdle()
        assertEquals(listOf("{$QUESTION=$OPTION}"), sent)
    }

    @Test fun aLongQuestionIsCutWithoutSplittingAPair() {
        val long = "q".repeat(CARD_TEXT_MAX - 1) + "\uD83D\uDE00" + "tail"
        val cut = cut4k(long)
        assertEquals("q".repeat(CARD_TEXT_MAX - 1) + "\u2026", cut)
        val accent = cut4k("q".repeat(CARD_TEXT_MAX - 1) + "e\u0301" + "tail")
        assertEquals("q".repeat(CARD_TEXT_MAX - 1) + "\u2026", accent)
        // r2: one cluster longer than the bound is cut at a code point, not reduced to a lone "\u2026".
        val flood = cut4k("q" + "\u0301".repeat(CARD_TEXT_MAX * 2))
        assertEquals(CARD_TEXT_MAX + 1, flood.length)
        assertTrue(flood.startsWith("q\u0301"))
    }
}
