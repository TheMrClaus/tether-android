package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.InterruptResult
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.chat.ComposerFixtures.QueueSpec
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tth-4idu (strip label, E.2), tth-y1hv (peer rows in the composer), tth-2zt (the strip on a handed-off source, spec §4.1b J).
 * Each assertion names the TOKEN that decides the behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PeerAndHandedOffQueueBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val removes = mutableListOf<String>()
    private val interrupts = mutableListOf<String>()
    private val peer = QueueSpec("p", "Please also run the lint pass.", origin = "system", kind = "peer")

    private fun show(folded: ChatFixtures.Folded, session: AgentSession = ComposerFixtures.session) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = session,
                    projection = folded.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { id -> interrupts += id; InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = { removes += it },
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    tree = folded.tree,
                )
            }
        }
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
    }

    // ---- tth-4idu ----
    @Test
    fun theRegionNameCountsRowsAndHasTheSingularForm() {
        assertEquals("Message queue, 1 message", queueRegionLabel(1))
        assertEquals("Message queue, 3 messages", queueRegionLabel(3))
        show(ComposerFixtures.delivery(QueueSpec("a")))
        rule.onNodeWithContentDescription("Message queue, 1 message").assertExists()
    }

    // ---- tth-y1hv ----
    @Test
    fun aQueuedPeerRowIsListedReadOnlyAndWithdrawable() {
        show(ComposerFixtures.delivery(peer, QueueSpec("b", "Also bump the changelog.")))
        rule.onNodeWithContentDescription("Message queue, 2 messages").assertExists()
        rule.onNodeWithText("Please also run the lint pass.").assertExists()
        rule.onNodeWithText(PEER_QUEUE_WAIT_COPY).assertExists()
        rule.onNodeWithTag(QUEUE_READONLY_TEXT_TAG).assertExists()
        // one editable field only (the operator's row), never the peer's
        rule.onAllNodes(hasSetTextAction() and androidx.compose.ui.test.hasContentDescription("Edit queued message")).assertCountEquals(1)
        rule.onNodeWithContentDescription(PEER_QUEUE_REMOVE_DESCRIPTION).performClick()
        assertEquals(listOf("p"), removes)
        // the next row to deliver is a peer's: no "Interrupt and send" anywhere
        rule.onAllNodesWithContentDescription("Interrupt and send", substring = true).assertCountEquals(0)
    }

    @Test
    fun aHeldPeerRowSaysItCannotBeEdited() {
        show(ComposerFixtures.delivery(QueueSpec("p", "Please also run the lint pass.", held = true, origin = "system", kind = "peer")))
        rule.onNodeWithText("Not sent: the session failed to open. This message from another session cannot be edited; remove it.").assertExists()
        rule.onAllNodesWithContentDescription("Edit queued message").assertCountEquals(0)
        rule.onNodeWithTag(QUEUE_HELD_TAG).assertExists()
    }

    // ---- tth-2zt ----
    @Test
    fun aHandedOffSourceKeepsItsRowsHeldReadOnlyRemoveOnly() {
        val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
        show(
            ComposerFixtures.delivery(QueueSpec("a", "Mine."), peer, tools = 0),
            ComposerFixtures.session.copy(handedOffTo = target.id),
        )
        rule.onNodeWithContentDescription("Message queue, 2 messages").assertExists()
        rule.onNodeWithText("$HANDED_OFF_ROW_LABEL Send it again in the new session, then remove it here.").assertExists()
        rule.onNodeWithText("$HANDED_OFF_ROW_LABEL Remove it.").assertExists()
        rule.onAllNodesWithContentDescription("Edit queued message").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Interrupt and send", substring = true).assertCountEquals(0)
        rule.onNodeWithContentDescription(OPERATOR_READ_ONLY_ROW_DESCRIPTION).assertExists()
        rule.onNodeWithContentDescription("Remove queued message").performClick()
        assertEquals(listOf("a"), removes)
        rule.onNodeWithTag("handoff-notice-to").assertExists()
    }

    @Test
    fun aHandedOffSourceWithNoRowsDrawsNoStrip() {
        show(ComposerFixtures.idle, ComposerFixtures.session.copy(handedOffTo = "sess-0002"))
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Message queue", substring = true)).assertCountEquals(0)
    }
}
