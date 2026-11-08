package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tth-5u90 / D-GH-8 (spec §4 SA, A1-A3): the queue strip is one compact line per row and caps its height.
 * The RED on 38fbe783 (before the change) is recorded in receipts/w4-QA.md; the thresholds here are the spec's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class QueueStripCompactTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val longBody = "Please look at the parser rewrite and report back what fails. ".repeat(20)
    private fun peer(id: String) = QueuedMessage(
        queueId = id,
        text = "Message from session disaster52 (s-1): $longBody",
        origin = JsonPrimitive("system"),
        noticeKind = JsonPrimitive("peer"),
        fromSessionId = "s-1",
        fromSessionName = "disaster52",
    )
    private fun operator(id: String, text: String = "Message $id") = QueuedMessage(queueId = id, text = text)

    private val saves = mutableListOf<Pair<String, String>>()
    private val removed = mutableListOf<String>()
    private var rows by androidx.compose.runtime.mutableStateOf(emptyList<QueuedMessage>())
    private var interruptSend: String? = null

    private fun show(initial: List<QueuedMessage>, interruptSendId: String? = null) {
        rows = initial
        interruptSend = interruptSendId
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                QueuedMessages(
                    queued = rows,
                    onSave = { id, text -> saves += id to text },
                    onRemove = { id -> removed += id; rows = rows.filter { it.queueId != id } },
                    interruptSendId = interruptSend,
                    sessionId = "sess-1",
                    interruptTurnId = "t1",
                    onInterruptNow = {},
                    interruptLock = null,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun stripHeight(n: Int): Dp = rule.onNodeWithContentDescription(queueRegionLabel(n)).getBoundsInRoot().let { it.bottom - it.top }

    /** The read-only / preview `Text` nodes of the strip (base: the full-text peer `Text`; after: the one-line preview). */
    private val previews = SemanticsMatcher("queue preview") { n ->
        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
        tag == QUEUE_PREVIEW_TAG || tag == QUEUE_READONLY_TEXT_TAG
    }

    private fun layoutOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
        return out.first()
    }

    @Test
    fun a1ThreeRowsStayInACompactStrip() {
        show(listOf(peer("p1"), peer("p2"), operator("o1")))
        val h = stripHeight(3)
        println("SA A1 strip height = $h")
        assertTrue("A1: strip $h must be <= 160dp", h.value <= 160f)
    }

    @Test
    fun a2ManyRowsCapAndScroll() {
        show(listOf(peer("p1"), peer("p2")) + (1..5).map { operator("o$it") })
        val h = stripHeight(7)
        val node = rule.onNodeWithContentDescription(queueRegionLabel(7)).fetchSemanticsNode()
        val scrolls = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        println("SA A2 strip height = $h, scrolls = $scrolls")
        assertTrue("A2: strip $h must be <= 176dp (cap min(176, 34% of 915))", h.value <= 176f)
        assertTrue("A2: the strip must scroll", scrolls)
    }

    @Test
    fun a3PreviewIsOneLineAndCut() {
        show(listOf(peer("p1")))
        val layout = layoutOf(rule.onAllNodes(previews, useUnmergedTree = true)[0])
        println("SA A3 lineCount = ${layout.lineCount}, hasVisualOverflow = ${layout.hasVisualOverflow}")
        assertEquals("A3: one line", 1, layout.lineCount)
        assertTrue("A3: cut with an ellipsis", layout.hasVisualOverflow)
    }

    // ---- A4 ----
    @Test
    fun a4APeerRowReadsFromTheSenderAndOffersOpenMessage() {
        show(listOf(peer("p1")))
        rule.onNode(hasText("From disaster52")).assertExists()
        rule.onNodeWithContentDescription(PEER_QUEUE_REMOVE_DESCRIPTION).assertExists()
        val open = rule.openArea(0).fetchSemanticsNode().config
        assertTrue(open.getOrNull(SemanticsProperties.ContentDescription)!!.any { it.contains("another agent, not you") })
        assertEquals(PEER_QUEUE_WAIT_COPY, open.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("Open message", open.getOrNull(SemanticsActions.OnClick)!!.label)
    }

    // ---- A5 ----
    @Test
    fun a5TapOpensTheWholeMessageAndWithdrawClosesIt() {
        val p = peer("p1")
        show(listOf(p, operator("o1")))
        rule.openRowSheet(0)
        rule.onNode(isDialog()).assertExists()
        val shown = rule.onNodeWithTag(QUEUE_SHEET_TEXT_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
        assertEquals(p.text, shown)
        rule.onNodeWithTag(QUEUE_SHEET_WAIT_TAG).assertExists()
        rule.onNodeWithContentDescription(QUEUE_SHEET_WITHDRAW_LABEL).performClick()
        rule.waitForIdle()
        assertEquals(listOf("p1"), removed)
        rule.onAllNodesWithTag(QUEUE_SHEET_TEXT_TAG).assertCountEquals(0)
    }

    // ---- A6 ----
    @Test
    fun a6TheEditFieldIs16spImeDoneIsQueueCommitAndCloseCommits() {
        show(listOf(operator("o1", "Original text")))
        rule.openRowSheet(0)
        val field = rule.onNodeWithTag(QUEUE_SHEET_EDIT_TAG)
        val layout = layoutOf(field)
        assertEquals(16.sp, layout.layoutInput.style.fontSize)
        field.performTextReplacement("Changed")
        field.performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("o1" to "Changed"), saves)
        // close with a pending change commits once more (server text still "Original text" in this static list)
        rule.onNodeWithTag(QUEUE_SHEET_EDIT_TAG).performTextReplacement("Changed again")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals(listOf("o1" to "Changed", "o1" to "Changed again"), saves)
    }

    @Test
    fun a6HeldChangedCloseRequeuesAndUnchangedCloseSendsNothing() {
        show(listOf(QueuedMessage(queueId = "h1", text = "Held text", held = true)))
        rule.openRowSheet(0)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals("unchanged close sends nothing", emptyList<Pair<String, String>>(), saves)
        rule.openRowSheet(0)
        rule.onNodeWithTag(QUEUE_SHEET_EDIT_TAG).performTextReplacement("Held text, edited")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals(listOf("h1" to "Held text, edited"), saves)
    }

    // ---- A7 ----
    @Test
    fun a7OnAPhoneInterruptAndSendIsSheetOnly() {
        show(listOf(operator("o1"), peer("p1")), interruptSendId = "o1")
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.openRowSheet(0)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(1)
    }

    // ---- A8 ----
    @Test
    fun a8UnconfirmedLeadAndSendAgainSendsTheUnchangedTextOnce() {
        show(listOf(QueuedMessage(queueId = "u1", text = "Maybe sent", held = true, heldReason = "steer_uncertain"), QueuedMessage(queueId = "f1", text = "Failed open", held = true)))
        val leads = rule.onAllNodes(hasTestTag(QUEUE_LEAD_TAG), useUnmergedTree = true)
        assertEquals("Unconfirmed", leads[0].fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text })
        assertEquals("Not sent", leads[1].fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text })
        rule.openRowSheet(0)
        assertEquals("Maybe not delivered", rule.onNodeWithTag(QUEUE_SHEET_META_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text })
        rule.onNodeWithContentDescription(QUEUE_SHEET_SEND_AGAIN_LABEL).performClick()
        rule.waitForIdle()
        assertEquals(listOf("u1" to "Maybe sent"), saves)
    }

    // ---- A9a (GUARD) ----
    @Test
    fun a9aTheRegionLabelCountsEveryRenderedRow() {
        show(listOf(operator("a"), operator("b"), peer("p")))
        rule.onNodeWithContentDescription("Message queue, 3 messages").assertExists()
    }
}

/** A7, the Medium class: the button is on the row AND in the sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w840dp-h915dp-420dpi")
class QueueStripMediumTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun a7FromTheMediumClassInterruptAndSendIsOnTheRowAndInTheSheet() {
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                QueuedMessages(
                    queued = listOf(QueuedMessage(queueId = "o1", text = "Mine")),
                    onSave = { _, _ -> }, onRemove = {}, sessionId = "s", interruptTurnId = "t1", onInterruptNow = {}, interruptLock = null,
                    interruptSendId = "o1",
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(1)
        rule.openRowSheet(0)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(2)
    }
}
