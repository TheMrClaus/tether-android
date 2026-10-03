package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.Freshness
import com.tether.app.client.InterruptResult
import com.tether.app.client.SessionSync
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-ceo (tether 887c222, issue #229, chat-view.tsx:1410-1548 and 4543-4572): a deferred
 * ("next-call") queued message says what it waits for and for how long — measured from its
 * journal-stamped `queuedAt` to the event-anchored server now, ticking — and after 60 s with work
 * still live it offers the choice (keep waiting / interrupt). With live work "Interrupt now" and the
 * composer's Interrupt key need a second, informed tap that names the cost, and that second tap is
 * bound to its turn and locked on a copy that is not live, as T6.7 / T13.2 made the first. ta-coik.13:
 * each acts on its first tap, as on the web (no arm delay): a double tap passes through, as there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DeferredQueueBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val interrupts = mutableListOf<String>()
    private var fixture by mutableStateOf(ComposerFixtures.deferred())
    private var liveness by mutableStateOf(ComposerLiveness.Live)
    private var now = ComposerFixtures.BUSY_NOW

    private fun show(folded: ChatFixtures.Folded, at: Long = ComposerFixtures.BUSY_NOW) {
        fixture = folded
        now = at
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = fixture.projection,
                    controls = null,
                    serverNow = { now },
                    onSend = { _, _ -> true },
                    onInterrupt = { turnId -> interrupts += turnId; InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = liveness,
                    tree = fixture.tree,
                )
            }
        }
        frames()
    }

    /** A few frames: enough for a tap's state to recompose. */
    private fun frames() {
        // A tap's write lands in the global snapshot; with the clock paused nothing else would tell
        // the recomposer about it before the next ticker second.
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private val sevenLive = "7 background tasks live"
    private val cost = "Stop ends the open tool call and will kill 7 background tasks"
    private val choiceCopy = "Delivers at the next safe boundary or when the turn ends. Interrupting stops the open tool call and will kill 7 background tasks."

    @Test
    fun aDeferredRowSaysWhatItWaitsForAndHowLong() {
        show(ComposerFixtures.deferred(), at = ComposerFixtures.QUEUED_AT + 5_000)
        rule.onNodeWithText("Queued — waiting for a safe boundary (1 tool running · $sevenLive · waiting 5s)").assertExists()
        // Under the threshold: a passive wait, no choice.
        rule.onNodeWithText(choiceCopy).assertDoesNotExist()
        rule.onNodeWithTag(QUEUE_KEEP_WAITING_TAG).assertDoesNotExist()
        // It ticks off the server clock, once a second.
        now = ComposerFixtures.QUEUED_AT + 14 * 60_000 + 43_000
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("Queued — waiting for a safe boundary (1 tool running · $sevenLive · waiting 14m 43s)").assertExists()
    }

    @Test
    fun afterSixtySecondsWithLiveWorkTheRowAsksForAChoiceAndKeepWaitingAcknowledgesIt() {
        show(ComposerFixtures.deferred(), at = ComposerFixtures.QUEUED_AT + 59_000)
        rule.onNodeWithText(choiceCopy).assertDoesNotExist()
        now = ComposerFixtures.QUEUED_AT + 60_000
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText(choiceCopy).assertExists()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertExists()
        rule.onNodeWithTag(QUEUE_KEEP_WAITING_TAG).performClick()
        frames()
        rule.onNodeWithText(choiceCopy).assertDoesNotExist()
        assertTrue("keep waiting sends nothing: $interrupts", interrupts.isEmpty())
    }

    @Test
    fun withNothingLiveTheRowNeverAsksAndInterruptNowIsOneTap() {
        show(ComposerFixtures.deferred(tools = 0, background = 0))
        rule.onNodeWithText("Queued — sends at the next tool call or when the turn ends (waiting 7m 00s)").assertExists()
        rule.onNodeWithTag(QUEUE_KEEP_WAITING_TAG).assertDoesNotExist()
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
    }

    @Test
    fun anUnstampedRowStillSaysWhatItWaitsForButNeverAsks() {
        show(ComposerFixtures.deferred(queuedAt = null))
        rule.onNodeWithText("Queued — waiting for a safe boundary (1 tool running · $sevenLive)").assertExists()
        rule.onNodeWithText(choiceCopy).assertDoesNotExist()
    }

    @Test
    fun interruptNowWithLiveWorkAsksForASecondTapThatNamesTheCost() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertTrue("the first tap only asks: $interrupts", interrupts.isEmpty())
        rule.onNodeWithText("$cost — it cannot be undone.").assertExists()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertDoesNotExist()
        // ta-coik.13: chat-view.tsx 90fbb9f :1518-1529, the web's confirmation acts on its first
        // click (a double tap passes through there too): no arm delay.
        rule.onNodeWithTag(QUEUE_STOP_ANYWAY_TAG).assertIsEnabled().performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertExists()
    }

    @Test
    fun keepWaitingClosesTheConfirmationWithoutSending() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithTag(QUEUE_KEEP_WAITING_TAG).performClick()
        frames()
        rule.onNodeWithText("$cost — it cannot be undone.").assertDoesNotExist()
        rule.onNodeWithTag(QUEUE_STOP_ANYWAY_TAG).assertDoesNotExist()
        // The 7-minute wait still asks for the choice afterwards (the web's two states are separate).
        rule.onNodeWithText(choiceCopy).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aConfirmationAskedForOneTurnNeverStopsTheNext() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithTag(QUEUE_STOP_ANYWAY_TAG).assertExists()
        rule.runOnIdle { fixture = ComposerFixtures.deferred(turnId = "t2") }
        frames()
        rule.onNodeWithTag(QUEUE_STOP_ANYWAY_TAG).assertDoesNotExist()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aLockedCopyDropsTheConfirmationAndCannotInterrupt() {
        show(ComposerFixtures.deferred(), at = ComposerFixtures.QUEUED_AT + 5_000)
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithTag(QUEUE_STOP_ANYWAY_TAG).assertExists()
        rule.runOnIdle { liveness = ComposerLiveness(interruptLock = "Catching up", stale = null) }
        frames()
        rule.onNodeWithTag(QUEUE_STOP_ANYWAY_TAG).assertDoesNotExist()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsNotEnabled().performClick()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsNotEnabled().performClick()
        arm()
        assertTrue(interrupts.isEmpty())
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertDoesNotExist()
    }

    /** T13.2: a copy that is not live claims nothing about now — its wait stops counting where it stood. */
    @Test
    fun aStaleCopysWaitStopsCounting() {
        liveness = ComposerLiveness(interruptLock = "Not live", stale = SessionSync(Freshness.CatchingUp, null))
        show(ComposerFixtures.deferred(), at = ComposerFixtures.QUEUED_AT + 5_000)
        rule.onNodeWithText("Queued — waiting for a safe boundary (1 tool running · $sevenLive · waiting 5s)").assertExists()
        now = ComposerFixtures.QUEUED_AT + 5 * 60_000
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        rule.onNodeWithText("Queued — waiting for a safe boundary (1 tool running · $sevenLive · waiting 5s)").assertExists()
        rule.onNodeWithText(choiceCopy).assertDoesNotExist()
    }

    // ---- the composer's Stop (chat-view.tsx:4543-4572) ----------------------------------------------

    @Test
    fun interruptWithLiveWorkShowsTheCostAndKeepRunningThenTheSecondTapStops() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        assertTrue("the first press only asks: $interrupts", interrupts.isEmpty())
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertDoesNotExist()
        rule.onNodeWithText("$cost — Stop anyway").assertExists()
        rule.onNodeWithContentDescription("$cost — stop anyway").assertExists()
        rule.onNodeWithContentDescription("Keep the turn running").assertExists()
        // ta-coik.13: chat-view.tsx 90fbb9f :4552-4558, "Stop anyway" acts on its first click.
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertIsEnabled().performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertExists()
    }

    @Test
    fun keepRunningClosesTheStopConfirmation() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).performClick()
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertDoesNotExist()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun theStopConfirmationLapsesWithItsTurnOrItsPrice() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertExists()
        // A new turn: the confirmation was asked for t1, never t2.
        rule.runOnIdle { fixture = ComposerFixtures.deferred(turnId = "t2") }
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertDoesNotExist()
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertExists()
        // The work ends: nothing left to confirm, and it does not come back on its own.
        rule.runOnIdle { fixture = ComposerFixtures.deferred(tools = 0, background = 0, turnId = "t2") }
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertDoesNotExist()
        rule.runOnIdle { fixture = ComposerFixtures.deferred(turnId = "t2") }
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertDoesNotExist()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun withoutLiveWorkInterruptIsStillOneTap() {
        show(ComposerFixtures.busy)
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
    }
}
