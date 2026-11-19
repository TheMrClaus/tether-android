package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.InterruptResult
import com.tether.app.client.QueueRowSteer
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
 * v165, Steer on a waiting row, at the phone size (the action is on the row at every width): the label follows the ROW's outcome,
 * a Steer is one tap with no confirmation, a refused Steer never interrupts and offers Interrupt and send, the hold setting
 * changes the wait words, and a server below 165 shows today's strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class QueueSteerBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val steered = mutableListOf<String>()
    private val interrupts = mutableListOf<Pair<String, String>>()
    private val plainInterrupts = mutableListOf<String>()
    private var protocol by mutableStateOf<Int?>(165)
    private var mode by mutableStateOf<String?>(null)
    private var states by mutableStateOf<Map<String, QueueRowSteer>>(emptyMap())
    private var engineSteers = true

    private fun show(folded: ChatFixtures.Folded) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = folded.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { turnId -> plainInterrupts += turnId; InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    steerCapableEngine = engineSteers,
                    serverProtocol = protocol,
                    queueSteerMode = mode,
                    queueSteerStates = states,
                    onQueueSteer = { steered += it },
                    onInterruptRow = { queueId, turnId -> interrupts += queueId to turnId; InterruptResult.Sent },
                    tree = folded.tree,
                )
            }
        }
        frames()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun frames() {
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    @Test fun everyWaitingRowHasItsOwnSteerAndOneTapSendsItWithNoConfirm() {
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b"), QueueSpec("c")))
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(3)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STEER_TAG)[1].performClick()
        frames()
        assertEquals("the pressed row, not the first", listOf("b"), steered)
        assertTrue(interrupts.isEmpty() && plainInterrupts.isEmpty())
        rule.onAllNodesWithText(QUEUE_INTERRUPT_HEADING).assertCountEquals(0)
    }

    @Test fun aRowWithAttachmentsReadsInterruptAndSendAndTheCommitNamesThatRow() {
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b", attachmentIds = listOf("att-1"))))
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(1)
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertTrue("the first tap only asks", interrupts.isEmpty() && steered.isEmpty())
        rule.onNodeWithText(QUEUE_INTERRUPT_HEADING).assertExists()
        rule.onNodeWithTag(QUEUE_INTERRUPT_COMMIT_TAG).performClick()
        frames()
        assertEquals(listOf("b" to "t1"), interrupts)
        assertTrue(plainInterrupts.isEmpty())
    }

    @Test fun anEngineThatCannotSteerOffersInterruptAndSendOnEveryRow() {
        engineSteers = false
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b")))
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(2)
    }

    @Test fun aRefusedSteerKeepsTheRowSaysSoAndOffersInterruptAndSendWithoutInterrupting() {
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b")))
        rule.runOnIdle { states = mapOf("a" to QueueRowSteer.Refused) }
        frames()
        rule.onNodeWithText("Not steered").assertExists()
        rule.onNode(hasWait("Queued on the server · the agent could not take it into this turn; it sends when this turn ends")).assertExists()
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_TAG).assertCountEquals(1)
        assertTrue("a refused steer never interrupts by itself", interrupts.isEmpty() && plainInterrupts.isEmpty())
    }

    @Test fun aSteerWaitingForItsAnswerCannotBePressedAgain() {
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.runOnIdle { states = mapOf("a" to QueueRowSteer.Pending) }
        frames()
        rule.onNodeWithTag(QUEUE_STEER_TAG).assertIsNotEnabled().performClick()
        frames()
        assertTrue(steered.isEmpty())
    }

    @Test fun holdModeChangesTheWaitWordsButKeepsTheButton() {
        mode = "hold"
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b", attachmentIds = listOf("att-1"))))
        rule.onNode(hasWait("Queued on the server · waits for Steer or the end of this turn")).assertExists()
        rule.onNode(hasWait("Queued on the server · sends when this turn ends")).assertExists()
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(1)
        rule.onNodeWithText(PLACEHOLDER_BUSY_HOLD).assertExists()
    }

    @Test fun autoModeKeepsTodaysWordsAndTheButton() {
        mode = "auto"
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onNode(hasWait("Queued on the server · goes into this turn at the agent’s next step")).assertExists()
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(1)
    }

    @Test fun aServerBelow165ShowsNoSteerAndIgnoresTheHoldSetting() {
        protocol = 164
        mode = "hold"
        show(ComposerFixtures.delivery(QueueSpec("a"), QueueSpec("b")))
        rule.onAllNodesWithTag(QUEUE_STEER_TAG).assertCountEquals(0)
        rule.onAllNodes(hasWait("Queued on the server · goes into this turn at the agent’s next step")).assertCountEquals(2)
        rule.onNodeWithText(PLACEHOLDER_BUSY_STEER).assertExists()
    }
}
