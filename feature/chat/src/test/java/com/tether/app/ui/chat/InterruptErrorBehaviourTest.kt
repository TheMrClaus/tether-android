package com.tether.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.Freshness
import com.tether.app.client.InterruptResult
import com.tether.app.client.SessionSync
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.7 in the chat (ChatScreen over [ChatTestClient]): the composer's Interrupt key and a queued
 * row's "Interrupt now" are armed and bound to the turn they are drawn for; a tap that lands after
 * the turn changed sends nothing; a refusal is said in words; the End session confirmation is
 * armed, in the web's words, and closes when the copy stops being live or the app stops; the
 * session's `lastError` shows, cleaned; transcript text is selectable and copyable one row at a
 * time, the composer and the consent cards are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class InterruptErrorBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val busy: AgentSession = ComposerFixtures.session

    private fun host(client: ChatTestClient, shown: AgentSession = busy, header: Boolean = false) {
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = header)
            }
        }
        rule.waitForIdle()
    }

    private fun liveClient(shown: AgentSession, fixture: ChatFixtures.Folded) = ChatTestClient().also {
        it.reports = true
        it.show(shown, fixture, live = true)
        it.sync.value = mapOf(shown.id to SessionSync(Freshness.Live, 2L))
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
    }

    /** Frames only: well inside the arming delay. */
    private fun frames() {
        rule.mainClock.advanceTimeBy(48)
        rule.waitForIdle()
    }

    private fun interruptKey() = rule.onNodeWithTag(INTERRUPT_KEY_TAG)

    // ---- Interrupt: armed, bound to its turn -------------------------------------------------------

    @Test
    fun theInterruptKeyIsArmedAndInterruptsTheTurnItIsDrawnFor() {
        val client = liveClient(busy, InterruptErrorFixtures.turnA)
        rule.mainClock.autoAdvance = false
        host(client)
        frames()
        interruptKey().assertIsNotEnabled().performClick()
        frames()
        assertTrue("a tap in the first 500 ms sends nothing: ${client.interruptCalls}", client.interruptCalls.isEmpty())
        arm()
        interruptKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t1"), client.interruptCalls)
    }

    /**
     * The late tap, at the UI: the key was drawn (and armed) for turn A; A ends and B begins under
     * the finger. The key is B's now, and not armed yet: the tap sends nothing. Armed again, it
     * interrupts B, by B's id.
     */
    @Test
    fun aLateTapAfterTurnAEndedAndTurnBStartedSendsNothing() {
        val client = liveClient(busy, InterruptErrorFixtures.turnA)
        rule.mainClock.autoAdvance = false
        host(client)
        arm()
        interruptKey().assertIsEnabled()
        rule.runOnIdle { client.show(busy, InterruptErrorFixtures.turnB) }
        frames()
        interruptKey().performClick()
        frames()
        assertTrue("a key drawn for turn A never interrupts turn B: ${client.interruptCalls}", client.interruptCalls.isEmpty())
        arm()
        interruptKey().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t2"), client.interruptCalls)
    }

    @Test
    fun aRefusedInterruptSaysSoInWords() {
        val client = liveClient(busy, InterruptErrorFixtures.turnA)
        client.interruptResult = InterruptResult.NotCurrentTurn
        host(client)
        arm()
        interruptKey().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(interruptRefusalCopy(InterruptResult.NotCurrentTurn)!!).assertIsDisplayed()
        assertEquals("That turn already ended — the turn running now was not interrupted.", interruptRefusalCopy(InterruptResult.NotCurrentTurn))
        assertNull("the client already toasts a dropped link", interruptRefusalCopy(InterruptResult.NotConnected))
        assertNull(interruptRefusalCopy(InterruptResult.Sent))
    }

    @Test
    fun interruptNowIsArmedAndBoundToItsTurnToo() {
        val client = liveClient(busy, queuedOn("t1"))
        rule.mainClock.autoAdvance = false
        host(client)
        frames()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertTrue(client.interruptCalls.isEmpty())
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t1"), client.interruptCalls)
        // The turn changes under the row: it re-arms for the new turn before it can send again.
        client.interruptCalls.clear()
        rule.runOnIdle { client.show(busy, queuedOn("t2")) }
        frames()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertTrue("Interrupt now drawn for t1 never interrupts t2: ${client.interruptCalls}", client.interruptCalls.isEmpty())
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t2"), client.interruptCalls)
    }

    @Test
    fun anInterruptRequestedSaysInterrupting() {
        val client = liveClient(busy, InterruptErrorFixtures.cancelling)
        host(client)
        rule.onNodeWithText("Interrupting", substring = true).assertExists()
    }

    // ---- End session -------------------------------------------------------------------------------

    private val running = chatSession("s1", historyId = null, name = "Deploy preview").copy(status = "active")

    private fun openEnd(client: ChatTestClient) {
        host(client, running, header = true)
        arm()
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
    }

    @Test
    fun theEndConfirmationClosesWhenTheCopyStopsBeingLive() {
        val client = liveClient(running, ChatFixtures.idle)
        openEnd(client)
        arm()
        rule.onNodeWithTag(END_SESSION_CONFIRM_TAG).assertIsEnabled()
        rule.runOnIdle { client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L)) }
        arm()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        rule.onAllNodesWithTag(END_SESSION_CONFIRM_TAG).assertCountEquals(0)
        assertTrue(client.killCalls.isEmpty())
    }

    @Test
    fun theEndConfirmationClosesWhenTheAppStops() {
        val client = liveClient(running, ChatFixtures.idle)
        openEnd(client)
        arm()
        val confirmAt = rule.screenCentreOf(END_SESSION_CONFIRM_TAG)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        arm()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        rule.tapScreenAt(confirmAt)
        arm()
        assertTrue("a tap where the key was ends nothing: ${client.killCalls}", client.killCalls.isEmpty())
    }

    @Test
    fun theEndConfirmationNamesTheSessionCleanedAndIsolated() {
        assertEquals("\u2068Deploy preview\u2069 — its running process will stop.", endSessionBody("Deploy\n\u202Epreview"))
        assertEquals("Its running process will stop.", endSessionBody("\u200B"))
    }

    // ---- error surfaces ------------------------------------------------------------------------------

    @Test
    fun aTurnErrorAndTheSessionsLastErrorShowCleaned() {
        val client = liveClient(busy, InterruptErrorFixtures.errors)
        host(client)
        rule.onNodeWithText("Engine exited before the turn finished: rate limited by the provider.").assertExists()
        rule.onNodeWithTag(SESSION_ERROR_TAG).assertExists()
        rule.onNodeWithText("The Claude CLI could not start: command not found (claude)").assertExists()
        // process_exit folds (turn.exit) but draws nothing, as on the web.
        rule.onNodeWithText("exit code", substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("signal", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun anEmptyLastErrorDrawsNoRow() {
        assertNull(sessionErrorText("\u200B \u2066\u2069"))
        assertNull(sessionErrorText(null))
        assertEquals("a b", sessionErrorText("a\n\u202Eb"))
    }

    // ---- selection & copy ------------------------------------------------------------------------------

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun copyFrom(tagOrNull: String? = null, text: String? = null) {
        val node = if (text != null) rule.onNodeWithText(text) else rule.onNodeWithTag(tagOrNull!!)
        node.performTouchInput { longClick(center) }
        rule.waitForIdle()
        node.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        rule.waitForIdle()
    }

    private fun transcript(fixture: ChatFixtures.Folded, consent: ConsentActions = ConsentActions.Unavailable) {
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                Column {
                    ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = LazyListState(),
                        showTimeline = false,
                        consent = consent,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aTranscriptRowsWordsAreSelectableAndCopyOnlyFromThatRow() {
        transcript(ChatFixtures.idle)
        assertNull(clip())
        copyFrom(text = "Summarize the README in one line.")
        val copied = clip()
        assertTrue("the operator's words copy: $copied", copied != null && "Summarize the README in one line.".contains(copied))
        assertFalse("nothing from the agent's row: $copied", copied!!.contains("fake engine"))
        copyFrom(text = "(fake engine) you said: Summarize the README in one line.")
        val reply = clip()
        assertTrue("the agent's words copy: $reply", reply != null && "(fake engine) you said: Summarize the README in one line.".contains(reply))
    }

    @Test
    fun theErrorRowsAreSelectableToo() {
        transcript(InterruptErrorFixtures.errors)
        copyFrom(tagOrNull = SESSION_ERROR_TAG)
        val copied = clip()
        assertTrue("copied: $copied", copied != null && "The Claude CLI could not start: command not found (claude)".contains(copied))
    }

    @Test
    fun theComposerAndTheConsentCardsAreNotPartOfTheSelection() {
        // Rows that are controls stay out of it; everything that is reading is in.
        assertFalse(ChatItem.LoadEarlier(2).selectableText)
        val fixture = ApprovalFixtures.write
        val approval = buildChatItems(fixture.projection, fixture.tree, showThinking = false, zone = ChatFixtures.zone)
            .filterIsInstance<ChatItem.Approval>()
        assertTrue(approval.isNotEmpty())
        approval.forEach { assertFalse(it.selectableText) }
        assertTrue(ChatItem.SessionError("s1", "x").selectableText)
        val busyClient = liveClient(busy, InterruptErrorFixtures.turnA)
        host(busyClient)
        // The composer's placeholder is chrome: a long press selects nothing, so nothing copies.
        copyFrom(tagOrNull = INTERRUPT_KEY_TAG)
        assertNull("chrome is never selected: ${clip()}", clip())
    }

    private fun queuedOn(turnId: String): ChatFixtures.Folded = ChatFixtures.fold(
        ev("turn_started", turnId, ts = ComposerFixtures.T_START) { put("idempotencyKey", "k-$turnId") },
        ev("user_message_accepted", turnId, ts = ComposerFixtures.T_START) { put("text", "Run the test suite.") },
        ev("queued_message_added", null, ts = ComposerFixtures.T_START) {
            put("queueId", "q-$turnId"); put("text", "Also bump the changelog."); put("flushMode", "next-call")
        },
    )
}
