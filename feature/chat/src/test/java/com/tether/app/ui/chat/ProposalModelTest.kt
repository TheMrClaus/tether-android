package com.tether.app.ui.chat

import androidx.compose.ui.unit.IntRect
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v175: the proposal's item model per state, the protocol gate, and where the Options panel goes. */
class ProposalModelTest {
    private fun turn(vararg after: AgentEvent): ChatFixtures.Folded = ChatFixtures.fold(
        ev("turn_started", "t1") { put("idempotencyKey", "k") },
        ev("user_message_accepted", "t1") { put("text", "plan it") },
        ev("tool_start", "t1") { put("toolId", "tool-1"); put("name", "mcp__tether__propose_session"); put("input", buildJsonObject {}) },
        ev("session_proposal", "t1") { put("proposalId", "p1"); put("title", "Split the parser"); put("prompt", "Do the work.") },
        ev("tool_end", "t1") { put("toolId", "tool-1"); put("output", "proposed") },
        *after,
    )

    private fun updated(state: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        evNullTurn("session_proposal_updated") { put("proposalId", "p1"); put("state", state); extra() }

    private fun items(f: ChatFixtures.Folded, card: Boolean) =
        buildChatItems(f.projection, f.tree, showThinking = false, zone = ChatFixtures.zone, proposalCard = card)

    private fun proposal(f: ChatFixtures.Folded) = items(f, true).filterIsInstance<ChatItem.Proposal>().single().proposal

    private fun toolRows(list: List<ChatItem>) = list.filter { it is ChatItem.Block && it.block.name == "mcp__tether__propose_session" || it is ChatItem.ToolGroup }

    @Test
    fun atProtocol175TheCardReplacesTheToolRow() {
        val list = items(turn(), true)
        assertEquals(1, list.filterIsInstance<ChatItem.Proposal>().size)
        assertTrue("the tool row gave way: $list", toolRows(list).isEmpty())
    }

    @Test
    fun atProtocol174NoCardAndTheToolRowStays() {
        val list = items(turn(), false)
        assertTrue(list.none { it is ChatItem.Proposal })
        assertTrue("the tool row stays", toolRows(list).isNotEmpty())
    }

    @Test
    fun everyStateReadsThroughToTheItem() {
        assertEquals(ProposalState.Pending, proposal(turn()).state)
        assertEquals(ProposalState.Starting, proposal(turn(updated("starting"))).state)
        val started = proposal(turn(updated("started") { put("startedSessionId", "s9"); put("startedSessionName", "Split the parser") }))
        assertEquals(ProposalState.Started, started.state)
        assertEquals("s9", started.startedSessionId)
        assertEquals("Split the parser", started.startedSessionName)
        assertEquals(ProposalState.Here, proposal(turn(updated("here"))).state)
        assertEquals(ProposalState.Dismissed, proposal(turn(updated("dismissed"))).state)
        assertEquals(ProposalState.Expired, proposal(turn(updated("expired"))).state)
        val refused = proposal(turn(updated("refused") { put("refusalCode", "profile_unavailable") }))
        assertEquals(ProposalState.Refused, refused.state)
        assertEquals("account", refused.faultRow)
    }

    @Test
    fun theResolvedStatesDropTheActionsAndRefusedStaysActionable() {
        assertTrue(ProposalState.Started.resolved && ProposalState.Here.resolved && ProposalState.Dismissed.resolved && ProposalState.Expired.resolved)
        assertTrue(!ProposalState.Refused.resolved && !ProposalState.Pending.resolved && !ProposalState.Starting.resolved)
    }

    @Test
    fun aRefusalWithNoRowMarksNone() {
        assertNull(proposal(turn(updated("refused") { put("refusalCode", "invalid_settings") })).faultRow)
    }

    // ---- placement ----
    private val bounds = IntRect(0, 100, 400, 700)

    @Test
    fun theFirstCardOpensBelowAndTheLastCardFlipsAbove() {
        val first = IntRect(10, 110, 390, 250)
        assertEquals(first.bottom + 4, ProposalPlacement.place(first, 300, bounds, 4))
        val last = IntRect(10, 560, 390, 690)
        assertEquals(last.top - 4 - 300, ProposalPlacement.place(last, 300, bounds, 4))
    }

    @Test
    fun theBoundIsTheListNotTheWindow() {
        // A 1000px window would leave room below the last card; the list ends at 700, so the panel must not go under it.
        val last = IntRect(10, 560, 390, 690)
        val y = ProposalPlacement.place(last, 300, bounds, 4)
        assertTrue("the panel stays inside the list: y=$y", y >= bounds.top && y + 300 <= bounds.bottom)
        val windowOnly = ProposalPlacement.place(last, 300, IntRect(0, 0, 400, 1000), 4)
        assertTrue("a window-only bound would run past the list", windowOnly + 300 > bounds.bottom)
    }

    @Test
    fun aPanelThatFitsNeitherSideIsCappedToTheLargerRoom() {
        val mid = IntRect(10, 300, 390, 420)
        val cap = ProposalPlacement.capHeight(mid, bounds, 4, 96)
        assertEquals(maxOf(700 - 420 - 4, 300 - 100 - 4), cap)
        val y = ProposalPlacement.place(mid, cap, bounds, 4)
        assertTrue(y >= bounds.top && y + cap <= bounds.bottom)
    }
}
