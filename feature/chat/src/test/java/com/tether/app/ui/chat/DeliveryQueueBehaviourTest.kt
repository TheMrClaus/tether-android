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
@Config(qualifiers = "w412dp-h915dp-420dpi")
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
        rule.onAllNodesWithText(v4).assertCountEquals(2)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG).assertCountEquals(2)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG).assertCountEquals(0)
        for (retired in listOf("Queued — sends after the current turn", "Interrupt now", "Stop anyway", "Keep waiting")) {
            rule.onAllNodesWithText(retired, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun aLegacyNextCallRowReadsV4() {
        show(ComposerFixtures.deferred())
        rule.onNodeWithText(v4).assertExists()
        rule.onAllNodesWithText("waiting for a safe boundary", substring = true).assertCountEquals(0)
    }

    @Test
    fun v5OnlyWhenTheEngineCanSteer() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onNodeWithText(v5).assertExists()
        rule.onAllNodesWithText(v4).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG).assertCountEquals(1)
        rule.onNodeWithText(PLACEHOLDER_BUSY_STEER).assertExists()
    }

    @Test
    fun steerEngineRowWithAttachmentsReadsV4GlyphAndWords() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a", attachmentIds = listOf("att-1"))))
        rule.onNodeWithText(v4).assertExists()
        rule.onAllNodesWithText(v5).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG).assertCountEquals(0)
    }

    @Test
    fun steerEngineLegacyNextCallRowReadsV4GlyphAndWords() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a", flushMode = "next-call")))
        rule.onNodeWithText(v4).assertExists()
        rule.onAllNodesWithText(v5).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG).assertCountEquals(0)
    }

    @Test
    fun steerEngineOrdinaryRowReadsV5GlyphAndWords() {
        steer = true
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onNodeWithText(v5).assertExists()
        rule.onAllNodesWithTag(QUEUE_STATE_STEER_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(QUEUE_STATE_TURN_END_TAG).assertCountEquals(0)
    }

    @Test
    fun withoutTheCapabilityTheV5LabelNeverShows() {
        steer = false
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onAllNodesWithText(v5).assertCountEquals(0)
        rule.onNodeWithText(PLACEHOLDER_BUSY).assertExists()
    }

    // ---- held rows (A8) -------------------------------------------------------------------------------------

    @Test
    fun aHeldRowShowsItsReasonAndNeverSendsWhenThisTurnEnds() {
        show(ComposerFixtures.delivery(QueueSpec("h", held = true, reason = "open_failed")))
        rule.onNodeWithText("Not sent: the session failed to open. Edit to send it again, or remove it.").assertExists()
        rule.onAllNodesWithText("sends when this turn ends", substring = true).assertCountEquals(0)
        rule.onAllNodesWithTag(QUEUE_HELD_TAG + "-glyph").assertCountEquals(1)
    }

    @Test
    fun steerUncertainIsVerbatimAndAbsentReadsOpenFailed() {
        show(ComposerFixtures.delivery(QueueSpec("h", held = true, reason = "steer_uncertain"), QueueSpec("g", held = true)))
        rule.onNodeWithText("May not have reached the agent; it was not sent again. Edit to resend it, or remove it.").assertExists()
        rule.onNodeWithText("Not sent: the session failed to open. Edit to send it again, or remove it.").assertExists()
    }

    @Test
    fun attachmentMissingAndAnUnknownReasonHaveTheirOwnWords() {
        show(ComposerFixtures.delivery(QueueSpec("m", held = true, reason = "attachment_missing"), QueueSpec("z", held = true, reason = "zzz")))
        rule.onNodeWithText(
            "Not sent: an attachment was missing when it was time to send. Edit to send the text without it, or remove this message.",
        ).assertExists()
        rule.onNodeWithText("Not sent. Edit to send it again, or remove it.").assertExists()
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
        rule.onNodeWithText("Not sent: the session failed to open. Stop handover in the Session links menu (⋯) cancels it.").assertExists()
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

    // ---- the composer's Stop (unchanged, CLI parity) ----------------------------------------------------------

    @Test
    fun interruptWithLiveWorkShowsTheCostAndKeepRunningThenTheSecondTapStops() {
        // Spec §4.1 interruptCostCopy (Claude: the tool call is certain, the tasks are visible), never the old stopCostCopy.
        val cost = "Stops the current response and 1 running tool call, and may stop 7 background tasks."
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        assertTrue("the first press only asks: $interrupts", interrupts.isEmpty())
        rule.onNodeWithText("$cost — Stop").assertExists()
        rule.onNodeWithContentDescription("Keep the turn running").assertExists()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
    }

    private fun interruptCost(p: String) = QueueWait.interruptCostCopy(
        LiveWork(openTools = 1, outstandingBackground = 0), QueueWait.foregroundCertain(p), QueueWait.backgroundVisible(p),
    )

    private fun restingDescription(): String? =
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** Spec §4.1a A: `{cost} {line}` in the confirm's commit and in the resting key's description, per engine and n. */
    @Test
    fun theStopConfirmationAndTheKeyCarryTheSteeredLinePerEngine() {
        val lines = mapOf(
            "claude" to "may still run after Stop.",
            "opencode" to "may wait and go with your next message.",
            "codex" to "may go back to the queue, after any newer messages.",
            "pi" to "may go back to the queue, after any newer messages.",
        )
        show(ComposerFixtures.delivery(tools = 1))
        for ((p, tail) in lines) for (n in 0..2) {
            interrupts.clear()
            val line = when (n) {
                0 -> ""
                1 -> " The message you sent into this turn $tail"
                else -> " The 2 messages you sent into this turn $tail"
            }
            val expected = interruptCost(p) + line
            rule.runOnIdle {
                provider = p
                fixture = ComposerFixtures.delivery(tools = 1, turnId = "t-$p-$n", steered = List(n) { "go in $it" })
            }
            frames()
            assertEquals("resting key description [$p n=$n]", expected, restingDescription())
            arm()
            rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
            frames()
            rule.onNodeWithText("$expected — Stop").assertExists()
        }
    }

    @Test
    fun keepRunningClosesTheStopConfirmation() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).performClick()
        frames()
        rule.onAllNodesWithTag(STOP_ANYWAY_KEY_TAG).assertCountEquals(0)
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun theStopConfirmationLapsesWithItsTurnOrItsPrice() {
        show(ComposerFixtures.deferred())
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertExists()
        rule.runOnIdle { fixture = ComposerFixtures.deferred(turnId = "t2") }
        frames()
        rule.onAllNodesWithTag(STOP_ANYWAY_KEY_TAG).assertCountEquals(0)
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        rule.runOnIdle { fixture = ComposerFixtures.deferred(tools = 0, background = 0, turnId = "t2") }
        frames()
        rule.onAllNodesWithTag(STOP_ANYWAY_KEY_TAG).assertCountEquals(0)
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun stopWithNoVisibleLiveWorkStopsAtOnce() {
        show(ComposerFixtures.busy)
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
        assertEquals(listOf("t1"), interrupts)
    }
}
