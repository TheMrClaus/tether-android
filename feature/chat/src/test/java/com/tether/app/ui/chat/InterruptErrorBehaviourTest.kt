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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
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
        rule.onNodeWithContentDescription("Session error: The Claude CLI could not start: command not found (claude)").assertExists()
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

    /**
     * The text toolbar a selection opens (Compose's context menu), caught so a test can press its
     * "Select all" and "Copy" as the operator would.
     */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private class MenuSpy : androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider {
        @Volatile var shown: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun press(key: Any) {
            val menu = checkNotNull(shown) { "no text toolbar is open" }
            val item = menu.data().components.filterIsInstance<androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem>().first { it.key == key }
            item.onClick(object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession { override fun close() = Unit })
        }
    }

    private fun transcript(
        fixture: ChatFixtures.Folded,
        consent: ConsentActions = ConsentActions.Unavailable,
        notices: NoticeActions = NoticeActions.Unavailable,
        richCodex: Boolean = false,
        menu: MenuSpy? = null,
    ) {
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides (menu ?: androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider.current),
            ) {
            ChatHost(TetherSkin.StudioDark) {
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
                        notices = notices,
                        richCodex = richCodex,
                    )
                }
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

    private fun queuedOn(turnId: String, queueId: String = "q-$turnId", cancelling: Boolean = false): ChatFixtures.Folded = ChatFixtures.fold(
        *listOfNotNull(
            ev("turn_started", turnId, ts = ComposerFixtures.T_START) { put("idempotencyKey", "k-$turnId") },
            ev("user_message_accepted", turnId, ts = ComposerFixtures.T_START) { put("text", "Run the test suite.") },
            ev("queued_message_added", null, ts = ComposerFixtures.T_START) {
                put("queueId", queueId); put("text", "Also bump the changelog."); put("flushMode", "next-call")
            },
            if (cancelling) ev("cancel_requested", turnId, ts = ComposerFixtures.T_START) else null,
        ).toTypedArray(),
    )

    private fun queuedTwoOn(turnId: String, cancelling: Boolean): ChatFixtures.Folded = ChatFixtures.fold(
        *listOfNotNull(
            ev("turn_started", turnId, ts = ComposerFixtures.T_START) { put("idempotencyKey", "k-$turnId") },
            ev("user_message_accepted", turnId, ts = ComposerFixtures.T_START) { put("text", "Run the test suite.") },
            ev("queued_message_added", null, ts = ComposerFixtures.T_START) {
                put("queueId", "q-1"); put("text", "Then fix the snapshot test."); put("flushMode", "next-call")
            },
            ev("queued_message_added", null, ts = ComposerFixtures.T_START) {
                put("queueId", "q-2"); put("text", "Also bump the changelog."); put("flushMode", "next-call")
            },
            if (cancelling) ev("cancel_requested", turnId, ts = ComposerFixtures.T_START) else null,
        ).toTypedArray(),
    )

    // ---- r2 ------------------------------------------------------------------------------------------

    /**
     * r2/r3 (security, until ta-yw0): while the turn is already being interrupted, the queue's head
     * message flushes into a new turn the moment it stops, which this client may not have seen when
     * a second tap lands. Every interrupt control of the session is locked (the composer's key and
     * each row's "Interrupt now"), says why, and sends nothing, until the server reports that
     * interrupt failed: then they unlock for a retry. A failure of some other turn unlocks nothing.
     */
    @Test
    fun everyInterruptControlIsLockedWhileTheTurnIsBeingInterruptedUntilItFails() {
        val client = liveClient(busy, queuedTwoOn("t1", cancelling = true))
        rule.mainClock.autoAdvance = false
        host(client)
        arm()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(2)
        interruptKey().assertIsNotEnabled().performClick()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG)[0].assertIsNotEnabled().performClick()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG)[1].assertIsNotEnabled().performClick()
        arm()
        assertTrue("an interrupt after the turn was already being interrupted: ${client.interruptCalls}", client.interruptCalls.isEmpty())
        rule.onNodeWithContentDescription("Interrupt the current turn, unavailable: $INTERRUPTING_LOCK_COPY").assertExists()
        rule.onAllNodesWithContentDescription(
            "Interrupt now — stops the current turn, its open tool call and its background tasks, then sends this, unavailable: $INTERRUPTING_LOCK_COPY",
        ).assertCountEquals(2)

        // Another turn's failure is not this one's.
        rule.runOnIdle { client.failed.value = mapOf(busy.id to "t0") }
        arm()
        interruptKey().assertIsNotEnabled()

        // The server reports this turn's interrupt failed: the controls unlock (armed again) for a retry.
        rule.runOnIdle { client.failed.value = mapOf(busy.id to "t1") }
        arm()
        interruptKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG)[1].assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t1", "${busy.id}@$TEST_ORIGIN#t1"), client.interruptCalls)
    }

    /**
     * r2 (verifier): the same queued message across a turn change. Its "Interrupt now" re-arms for
     * the new turn (the arming identity carries the turn, not only the queueId): a tap before it
     * re-arms sends nothing; once armed it interrupts the new turn.
     */
    @Test
    fun theSameQueuedRowReArmsWhenTheTurnChanges() {
        val client = liveClient(busy, queuedOn("t1", queueId = "q-same"))
        rule.mainClock.autoAdvance = false
        host(client)
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsEnabled()
        rule.runOnIdle { client.show(busy, queuedOn("t2", queueId = "q-same")) }
        frames()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsNotEnabled().performClick()
        frames()
        assertTrue("the row armed for t1 sent for t2 before re-arming: ${client.interruptCalls}", client.interruptCalls.isEmpty())
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t2"), client.interruptCalls)
    }

    /** r2: the notices (each with its armed X) are not selectable; a selection elsewhere never swallows or doubles the X's tap. */
    @Test
    fun theNoticeXStaysOneTapWhileASelectionIsActive() {
        val recorder = NoticeFixtures.Recorder()
        val fixture = NoticeFixtures.sessionNotices
        buildChatItems(fixture.projection, fixture.tree, showThinking = false, zone = ChatFixtures.zone).forEach { item ->
            if (item is ChatItem.SessionNotice || item is ChatItem.ProviderNotice || item is ChatItem.Compaction) assertFalse("$item is selectable", item.selectableText)
        }
        transcript(fixture, notices = recorder.actions())
        arm()
        // A word of the prompt is selected ...
        copyFrom(text = "Summarize the README in one line.")
        assertTrue(clip() != null && "Summarize the README in one line.".contains(clip()!!))
        // ... and the notice's X, tapped once, dismisses once.
        rule.onAllNodesWithTag("notice-dismiss").onFirst().performClick()
        rule.waitForIdle()
        assertEquals(1, recorder.dismissed.size)
        // A long press on the X selects nothing and dismisses nothing more: the copy keeps the prompt's word.
        val before = clip()
        rule.onAllNodesWithTag("notice-dismiss").onFirst().performTouchInput { longClick(center) }
        rule.waitForIdle()
        assertEquals(before, clip())
        assertEquals(1, recorder.dismissed.size)
    }

    /**
     * r2 (verifier): a Codex turn diff's +/- column is `user-select: none` (codex-rich-renderers.module.css
     * `.diffMarker`). The operator selects a word of the diff, then "Select all" and "Copy" from the
     * toolbar: the copy is the diff's lines, never a lone marker, and never another row's words
     * (the selection stays in its row).
     */
    @Test
    fun aCodexDiffsMarkersAreNeverSelected() {
        val menu = MenuSpy()
        transcript(ToolFixtures.codexDetails, richCodex = true, menu = menu)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Turn changes"))
        rule.onAllNodesWithTag(DIFF_MARKER_TAG, useUnmergedTree = true).onFirst().assertExists()
        rule.onNodeWithText("export const config = { retries: 5 };", substring = true, useUnmergedTree = true).performTouchInput { longClick(center) }
        // ta-9dpl: wait for the menu itself (as NoCopyProbe.longPressForMenu does): an idle frame after
        // the long press is not always enough on a loaded machine, and the press then finds no menu.
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
        val copied = checkNotNull(clip()) { "nothing was copied" }
        assertTrue("the diff's lines are copied: $copied", copied.contains("export const config = { retries: 5 };"))
        val lines = copied.lines().map { it.trim() }
        assertFalse("a diff marker was part of the copy: $copied", lines.any { it == "+" || it == "-" })
        // The rows around it are on screen (composed): a selection that ran across rows would take them.
        rule.onNodeWithText("LGTM: the retry bump is covered by the tests.", substring = true).assertExists()
        assertFalse("the selection ran into another row: $copied", copied.contains("LGTM") || copied.contains("Apply the patch and review it."))
    }

    @Test
    fun theSessionErrorRowSaysWhatItIs() {
        transcript(InterruptErrorFixtures.errors)
        rule.onNodeWithContentDescription("Session error: The Claude CLI could not start: command not found (claude)").assertExists()
        // r3: said once: the words are not a second node TalkBack would read after the description.
        rule.onNodeWithText("The Claude CLI could not start: command not found (claude)").assertDoesNotExist()
    }
}
