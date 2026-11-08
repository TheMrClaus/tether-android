package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import com.tether.app.client.InterruptResult
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.helpers.QueueWait.LiveWork
import com.tether.app.ui.chat.ComposerFixtures.QueueSpec
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Delivery UI (delivery-composer-spec §1 V4/V5, §3.2, §4.2, §4.1a C): a queued row says when it sends (V4/V5) with a glyph and
 * words and no spinner; a held row says why it was not sent and never offers "Interrupt and send"; the ONE next operator row
 * offers "Interrupt and send", which always confirms with the cost sentence and then sends exactly one `interrupt`. The old
 * "Interrupt now" / "Stop anyway" / "Keep waiting" / ticking-wait rows are retired. Each assertion names the TOKEN that decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w840dp-h915dp-420dpi") // tth-5u90: the row button is on the row from the Medium class
class DeliveryQueueBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val interrupts = mutableListOf<String>()
    private var fixture by mutableStateOf(ComposerFixtures.deferred())
    private var liveness by mutableStateOf(ComposerLiveness.Live)
    private var steer = false
    private var provider by mutableStateOf("claude")

    private fun show(folded: ChatFixtures.Folded) {
        fixture = folded
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session.copy(provider = provider),
                    projection = fixture.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { turnId -> interrupts += turnId; InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = liveness,
                    steerCapableEngine = steer,
                    tree = fixture.tree,
                )
            }
        }
        frames()
    }

    private fun frames() {
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private val v4 = "Queued on the server · sends when this turn ends"
    private val v5 = "Queued on the server · goes into this turn at the agent’s next step"
    private fun inHeld(tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(QUEUE_HELD_TAG))

    // ---- V4 / V5 ------------------------------------------------------------------------------------------

    @Test
    fun aQueuedRowSaysV4WithAClockAndNoRetiredCopy() {
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b")))
        rule.onAllNodes(hasWait(v4)).assertCountEquals(2)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG, useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG, useUnmergedTree = true).assertCountEquals(0)
        for (retired in listOf("Queued — sends after the current turn", "Interrupt now", "Stop anyway", "Keep waiting")) {
            rule.onAllNodes(hasWaitContaining(retired)).assertCountEquals(0)
        }
    }

    @Test
    fun aLegacyNextCallRowReadsV4() {
        show(ComposerFixtures.deferred())
        rule.onNode(hasWait(v4)).assertExists()
        rule.onAllNodesWithText("waiting for a safe boundary", substring = true).assertCountEquals(0)
    }

    @Test
    fun v5OnlyWhenTheEngineCanSteer() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onNode(hasWait(v5)).assertExists()
        rule.onAllNodes(hasWait(v4)).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText(PLACEHOLDER_BUSY_STEER).assertExists()
    }

    @Test
    fun steerEngineRowWithAttachmentsReadsV4GlyphAndWords() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a", attachmentIds = listOf("att-1"))))
        rule.onNode(hasWait(v4)).assertExists()
        rule.onAllNodes(hasWait(v5)).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun steerEngineLegacyNextCallRowReadsV4GlyphAndWords() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a", flushMode = "next-call")))
        rule.onNode(hasWait(v4)).assertExists()
        rule.onAllNodes(hasWait(v5)).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun steerEngineOrdinaryRowReadsV5GlyphAndWords() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onNode(hasWait(v5)).assertExists()
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun withoutTheCapabilityTheV5LabelNeverShows() {
        steer = false
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onAllNodes(hasWait(v5)).assertCountEquals(0)
        rule.onNodeWithText(PLACEHOLDER_BUSY).assertExists()
    }

    // ---- held rows (A8) -------------------------------------------------------------------------------------

    @Test
    fun aHeldRowShowsItsReasonAndNeverSendsWhenThisTurnEnds() {
        show(ComposerFixtures.delivery(QueueSpec("h", held = true, reason = "open_failed")))
        rule.onNode(hasWait("Not sent: the session failed to open. Edit to send it again, or remove it.")).assertExists()
        rule.onAllNodes(hasWaitContaining("sends when this turn ends")).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_HELD_TAG + "-glyph", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun steerUncertainIsVerbatimAndAbsentReadsOpenFailed() {
        show(ComposerFixtures.delivery(QueueSpec("h", held = true, reason = "steer_uncertain"), QueueSpec("g", held = true)))
        rule.onNode(hasWait("May not have reached the agent; it was not sent again. Edit to resend it, or remove it.")).assertExists()
        rule.onNode(hasWait("Not sent: the session failed to open. Edit to send it again, or remove it.")).assertExists()
    }

    @Test
    fun attachmentMissingAndAnUnknownReasonHaveTheirOwnWords() {
        show(ComposerFixtures.delivery(QueueSpec("m", held = true, reason = "attachment_missing"), QueueSpec("z", held = true, reason = "zzz")))
        rule.onNode(hasWait("Not sent: an attachment was missing when it was time to send. Edit to send the text without it, or remove this message.")).assertExists()
        rule.onNode(hasWait("Not sent. Edit to send it again, or remove it.")).assertExists()
    }

    @Test
    fun aHeldRowNeverOffersInterruptAndSendButKeepsRemove() {
        show(ComposerFixtures.delivery(QueueSpec("h", held = true, reason = "steer_uncertain"), QueueSpec("b")))
        rule.onAllNodes(inHeld(QUEUE_INTERRUPT_TAG)).assertCountEquals(0)
        // The live row behind it is the next one to deliver: it carries the one button.
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Remove queued message").assertCountEquals(2)
    }

    @Test
    fun theHeldHandoverRowReadsItsOwnHint() {
        show(
            ComposerFixtures.delivery(
                QueueSpec("h", text = "Handover instruction", held = true, origin = "system", kind = "handover-request"),
            ),
        )
        rule.onNode(hasWait("Not sent: the session failed to open. Stop handover in the Session links menu (⋯) cancels it.")).assertExists()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
    }

    // ---- Interrupt and send (A5) ----------------------------------------------------------------------------

    @Test
    fun interruptAndSendIsOnTheFirstOperatorRowOnly() {
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b"), QueueSpec("c")))
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(1)
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertExists()
    }

    @Test
    fun aPeerRowFirstMeansNoRowOffersIt() {
        show(
            ComposerFixtures.delivery(
                QueueSpec("p", origin = "system", kind = "peer"),
                QueueSpec("a"),
                QueueSpec("b"),
            ),
        )
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.onNodeWithText("Message a").assertExists() // listed, just without the action
    }

    @Test
    fun interruptAndSendAlwaysAsksAndThenSendsExactlyOneInterrupt() {
        show(ComposerFixtures.delivery(QueueSpec("a"), tools = 0, background = 0))
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertTrue("the first tap only asks (even with no live work): $interrupts", interrupts.isEmpty())
        rule.onNodeWithText(QUEUE_INTERRUPT_HEADING).assertExists()
        rule.onNodeWithText(
            "Stops the current response. Then this message is sent.",
        ).assertExists()
        rule.onNodeWithTag(QUEUE_INTERRUPT_COMMIT_TAG).performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
        rule.onAllNodesWithText(QUEUE_INTERRUPT_HEADING).assertCountEquals(0)
    }

    @Test
    fun theConfirmationNamesTheCostAndASteeredMessagesLine() {
        show(ComposerFixtures.delivery(QueueSpec("a"), tools = 1, background = 3, steered = listOf("go in")))
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithText(
            "Stops the current response and 1 running tool call, and may stop 3 background tasks. " +
                "The message you sent into this turn may still run after Stop. Then this message is sent.",
        ).assertExists()
    }

    @Test
    fun keepQueuedClosesTheConfirmationWithoutSending() {
        show(ComposerFixtures.delivery(QueueSpec("a")))
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithTag(QUEUE_KEEP_QUEUED_TAG).performClick()
        frames()
        rule.onAllNodesWithText(QUEUE_INTERRUPT_HEADING).assertCountEquals(0)
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aConfirmationAskedForOneTurnNeverStopsTheNext() {
        show(ComposerFixtures.delivery(QueueSpec("a")))
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithTag(QUEUE_INTERRUPT_COMMIT_TAG).assertExists()
        rule.runOnIdle { fixture = ComposerFixtures.delivery(QueueSpec("a"), turnId = "t2") }
        frames()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_COMMIT_TAG).assertCountEquals(0)
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertExists()
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun aLockedCopyDropsTheConfirmationAndCannotInterrupt() {
        show(ComposerFixtures.delivery(QueueSpec("a")))
        arm()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        rule.onNodeWithTag(QUEUE_INTERRUPT_COMMIT_TAG).assertExists()
        rule.runOnIdle { liveness = ComposerLiveness(interruptLock = "Catching up", stale = null) }
        frames()
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_COMMIT_TAG).assertCountEquals(0)
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsNotEnabled().performClick()
        arm()
        assertTrue(interrupts.isEmpty())
    }

    // The composer's Stop (now the Stop panel, AW.3) is pinned in StopPanelBehaviourTest.
}
