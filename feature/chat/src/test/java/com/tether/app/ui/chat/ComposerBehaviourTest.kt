package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.1 behaviour of the composer, against tether components/chat-view.tsx: Enter sends and
 * Shift+Enter breaks the line (:3244-3247), never mid-IME-composition; the soft keyboard's Send
 * action sends; an idle session sends and a busy one goes the queue path with the busy
 * placeholder; the phone hides an empty Queue key; a send clears the draft (and the stored copy
 * through onDraftChange). The queue rows (:1402-1489): Enter or leaving the field commits, a
 * blank row is removed, Escape restores the server text, the remove key, "Interrupt now" only on
 * a next-call row, and the row follows the server's text while it is not being edited.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ComposerBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sends = mutableListOf<String>()
    private val edits = mutableListOf<Pair<String, String>>()
    private val removes = mutableListOf<String>()
    private val drafts = mutableListOf<String>()
    /** T6.7: the turn each Interrupt tap was drawn for. */
    private val interrupts = mutableListOf<String>()
    private var projection by mutableStateOf<SessionProjection?>(null)

    private fun show(fixture: ChatFixtures.Folded, initialDraft: String = "", accept: Boolean = true) {
        projection = fixture.projection
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ ->
                        sends += text
                        accept
                    },
                    onInterrupt = { turnId -> interrupts += turnId; com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { id, text -> edits += id to text },
                    onQueueRemove = { removes += it },
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    initialDraft = initialDraft,
                    onDraftChange = { drafts += it },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun input() = rule.onNodeWithContentDescription("Message the agent")

    private fun inputText(): String =
        input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun hardwareEnterSendsTheTrimmedTextAndClearsTheDraft() {
        show(ComposerFixtures.idle)
        input().performTextInput("  hello there  ")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("hello there"), sends)
        assertEquals("", inputText())
        assertEquals("the stored draft is removed", "", drafts.last())
    }

    @Test
    fun shiftEnterBreaksTheLineAndDoesNotSend() {
        show(ComposerFixtures.idle)
        input().performTextInput("first line")
        input().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        input().performTextInput("second line")
        rule.waitForIdle()
        assertEquals(emptyList<String>(), sends)
        assertEquals("first line\nsecond line", inputText())
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("first line\nsecond line"), sends)
    }

    @Test
    fun theSoftKeyboardSendActionSends() {
        show(ComposerFixtures.idle)
        input().performTextInput("from the soft keyboard")
        input().performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("from the soft keyboard"), sends)
        assertEquals("", inputText())
    }

    @Test
    fun anEmptyOrBlankDraftSendsNothing() {
        show(ComposerFixtures.idle)
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        input().performTextInput("   ")
        input().performKeyInput { pressKey(Key.Enter) }
        input().performImeAction()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), sends)
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    /** A refused send (§5.6 rollback, e.g. attachments while disconnected) keeps the draft. */
    @Test
    fun aRefusedSendKeepsTheDraft() {
        show(ComposerFixtures.idle, accept = false)
        input().performTextInput("keep me")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("keep me"), sends)
        assertEquals("keep me", inputText())
    }

    @Test
    fun whileBusyTheComposerQueuesAndSaysSo() {
        show(ComposerFixtures.busy)
        rule.onNodeWithText(PLACEHOLDER_BUSY).assertExists()
        // Phone: an empty Queue key is hidden (globals.css:11944), Interrupt stays.
        rule.onAllNodesWithContentDescription("Queue message").assertCountEquals(0)
        // T6.7: armed like every operator control, then bound to the turn it is drawn for.
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Interrupt the current turn").performClick()
        assertEquals(listOf("t1"), interrupts)
        input().performTextInput("after this turn")
        rule.onNodeWithContentDescription("Queue message").assertIsEnabled()
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("after this turn"), sends)
        assertEquals("", inputText())
    }

    @Test
    fun theDraftIsWhatTheComposerOpensOn() {
        show(ComposerFixtures.idle, initialDraft = "half a thought")
        assertEquals("half a thought", inputText())
        input().performTextInput(" more")
        rule.waitForIdle()
        assertEquals("half a thought more", drafts.last())
    }

    /** The browser reports a keystroke that belongs to an IME composition as "Process", not Enter. */
    @Test
    fun enterNeverSubmitsMidComposition() {
        val down = KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        val up = KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        val shifted = KeyEvent(
            android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0, android.view.KeyEvent.META_SHIFT_ON),
        )
        val numpad = KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER))
        val plain = TextFieldValue("ni", TextRange(2))
        val composing = TextFieldValue("ni", TextRange(2), composition = TextRange(0, 2))
        assertTrue(isSubmitKey(down, plain))
        assertTrue(isSubmitKey(numpad, plain))
        assertFalse(isSubmitKey(up, plain))
        assertFalse(isSubmitKey(shifted, plain))
        assertFalse(isSubmitKey(down, composing))
    }

    // ── queue rows ───────────────────────────────────────────────────────────────────────────

    private fun queueRows() = rule.onAllNodesWithContentDescription("Edit queued message")

    @Test
    fun enterCommitsATrimmedEditOnce() {
        show(ComposerFixtures.queued)
        queueRows().onFirst().requestFocus()
        queueRows().onFirst().performTextReplacement("  Then fix only the snapshot test.  ")
        queueRows().onFirst().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("parity-queue-1" to "Then fix only the snapshot test."), edits)
        assertEquals(emptyList<String>(), removes)
        assertEquals("the main composer never sent it", emptyList<String>(), sends)
    }

    @Test
    fun leavingTheFieldCommitsAndAnUnchangedRowSendsNothing() {
        show(ComposerFixtures.queued)
        queueRows().onFirst().requestFocus()
        input().requestFocus() // blur without a change
        rule.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), edits)
        queueRows().onLast().requestFocus()
        queueRows().onLast().performTextReplacement("Also bump the changelog and the version.")
        input().requestFocus()
        rule.waitForIdle()
        assertEquals(listOf("parity-queue-2" to "Also bump the changelog and the version."), edits)
    }

    @Test
    fun blankingARowRemovesIt() {
        show(ComposerFixtures.queued)
        queueRows().onFirst().requestFocus()
        queueRows().onFirst().performTextReplacement("   ")
        queueRows().onFirst().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("parity-queue-1"), removes)
        assertEquals(emptyList<Pair<String, String>>(), edits)
    }

    @Test
    fun escapeRestoresTheServerTextWithoutSaving() {
        show(ComposerFixtures.queued)
        queueRows().onFirst().requestFocus()
        queueRows().onFirst().performTextReplacement("something else")
        queueRows().onFirst().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), edits)
        rule.onNodeWithText("Then fix the snapshot test and rerun only that suite.").assertExists()
    }

    @Test
    fun theRemoveKeyAndInterruptNow() {
        show(ComposerFixtures.queued)
        // Only the next-call row offers "Interrupt now"; each row says when it sends, in words.
        rule.onAllNodesWithContentDescription("Queued — sends after the current turn.").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Queued — sends at the next tool boundary.").assertCountEquals(1)
        val interruptNow = "Interrupt now — stops the current turn, its open tool call and its background tasks, then sends this"
        rule.onAllNodesWithContentDescription(interruptNow).assertCountEquals(1)
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(interruptNow).performClick()
        assertEquals(listOf("t1"), interrupts)
        rule.onAllNodesWithContentDescription("Remove queued message").onLast().performClick()
        assertEquals(listOf("parity-queue-2"), removes)
        rule.onNodeWithContentDescription("Queued messages").assertExists()
    }

    /**
     * T15.6 (v133): the composer lists only the operator's own drafts, as chat-view.tsx:1914
     * operatorQueuedMessages — an origin-less (legacy) one and an origin "user" one, in order. Tether's
     * system notices (spawn, command, continuation) are not his to edit or remove and never show.
     */
    @Test
    fun onlyTheOperatorsQueuedDraftsAreListed() {
        show(ComposerFixtures.mixedOrigins)
        assertEquals(5, projection!!.queuedMessages.size)
        queueRows().assertCountEquals(2)
        rule.onNodeWithText("Then fix the snapshot test and rerun only that suite.").assertExists()
        rule.onNodeWithText("Also bump the changelog.").assertExists()
        rule.onAllNodesWithText("SYSTEM", substring = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Remove queued message").onLast().performClick()
        assertEquals("the remove key maps to the operator's row, not a hidden notice", listOf("user"), removes)
    }

    /** T15.6: a queue of Tether notices alone draws no queue panel at all (the web's `length > 0`). */
    @Test
    fun aQueueOfSystemNoticesAloneDrawsNoPanel() {
        show(ComposerFixtures.systemOnly)
        assertEquals(3, projection!!.queuedMessages.size)
        rule.onNodeWithContentDescription("Queued messages").assertDoesNotExist()
        queueRows().assertCountEquals(0)
        rule.onAllNodesWithText("SYSTEM", substring = true).assertCountEquals(0)
    }

    /** Another device edited the message: the row follows while nobody is editing it here. */
    @Test
    fun aRowMirrorsTheServerTextWhenNotEditing() {
        show(ComposerFixtures.queued)
        val base = projection!!
        projection = base.copy(
            queuedMessages = listOf(QueuedMessage("parity-queue-1", "Edited on the laptop.")) + base.queuedMessages.drop(1),
        )
        rule.waitForIdle()
        rule.onNodeWithText("Edited on the laptop.").assertExists()
        // While editing, the operator's buffer wins until they commit.
        queueRows().onFirst().requestFocus()
        queueRows().onFirst().performTextReplacement("mine")
        projection = projection!!.copy(
            queuedMessages = listOf(QueuedMessage("parity-queue-1", "Edited again elsewhere.")) + base.queuedMessages.drop(1),
        )
        rule.waitForIdle()
        rule.onNodeWithText("mine").assertExists()
    }
}
