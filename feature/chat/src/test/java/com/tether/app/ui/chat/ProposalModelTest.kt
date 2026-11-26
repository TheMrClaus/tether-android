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

    @Test
    fun theLastCardStaysInsideTheListWhenTheWindowIsTallerThanTheList() {
        // The list ends at 700 inside a 1000px window; the keyboard takes 0. The bound is the list, so the panel flips above the last card.
        val list = IntRect(0, 100, 400, 700)
        val b = ProposalPlacement.panelBounds(list, 400, 1000, 0)
        val last = IntRect(10, 560, 390, 690)
        val y = ProposalPlacement.place(last, 300, b, 4)
        assertTrue("the last item's panel is inside the list: y=$y", y >= list.top && y + 300 <= list.bottom)
    }

    @Test
    fun theKeyboardTakesTheBottomOfTheBound() {
        val b = ProposalPlacement.panelBounds(IntRect(0, 100, 400, 700), 400, 1000, 400)
        assertEquals(600, b.bottom)
        assertEquals(IntRect(0, 0, 400, 1000), ProposalPlacement.panelBounds(null, 400, 1000, 0))
    }

    @Test
    fun scrollToFitMakesRoomBelowWhenTheCardCanMoveUp() {
        // The panel needs 300; below the card there is 100: the list scrolls 200 further down, the action row staying in view.
        val card = IntRect(10, 400, 390, 560)
        val d = ProposalPlacement.scrollToFit(card, 300, IntRect(0, 100, 400, 664), 4, 56)
        assertEquals(300f - (664 - 560 - 4), d!!, 0.5f)
    }

    @Test
    fun scrollToFitGoesAboveWhenBelowCannotWorkAndNullWhenNothingFits() {
        val bounds = IntRect(0, 100, 400, 700)
        // A card at the very top that fills the list: nothing makes 560 of room on a side.
        assertNull(ProposalPlacement.scrollToFit(IntRect(10, 100, 390, 690), 560, bounds, 4, 56))
        // The panel already fits below: nothing to scroll.
        assertNull(ProposalPlacement.scrollToFit(IntRect(10, 110, 390, 250), 300, bounds, 4, 56))
        // The card hangs below the list: it is brought into view first.
        assertEquals(60f, ProposalPlacement.scrollToFit(IntRect(10, 600, 390, 760), 40, bounds, 4, 56)!!, 0.5f)
    }

    // ---- placement in the transcript ----
    private fun twoRuns(): ChatFixtures.Folded = ChatFixtures.fold(
        ev("turn_started", "t1") { put("idempotencyKey", "k") },
        ev("user_message_accepted", "t1") { put("text", "plan it") },
        ev("message_started", "t1") { put("blockId", "t1:m0") },
        ev("message_completed", "t1") { put("blockId", "t1:m0"); put("text", "Before the proposal.") },
        ev("tool_start", "t1") { put("toolId", "tool-1"); put("name", "mcp__tether__propose_session"); put("input", buildJsonObject {}) },
        ev("session_proposal", "t1") { put("proposalId", "p1"); put("title", "Split the parser"); put("prompt", "Do the work.") },
        ev("tool_end", "t1") { put("toolId", "tool-1"); put("output", "proposed") },
        ev("message_started", "t1") { put("blockId", "t1:m1") },
        ev("message_completed", "t1") { put("blockId", "t1:m1"); put("text", "After the proposal.") },
    )

    @Test
    fun theCardSitsWhereTheToolRowWasBetweenTheTextBeforeAndAfter() {
        val list = items(twoRuns(), true)
        val at = list.indexOfFirst { it is ChatItem.Proposal }
        assertTrue("a card is present", at >= 0)
        assertTrue("text before it", list.subList(0, at).any { it is ChatItem.Block })
        assertTrue("text after it, not the card at the tail: $list", list.subList(at + 1, list.size).any { it is ChatItem.Block })
    }

    @Test
    fun aProposalWithNoToolRowFollowsTheLastRun() {
        val p1 = proposal(turn())
        val p2 = p1.copy(proposalId = "p2")
        val chunks = proposalChunks(listOf("a", "b"), listOf(p1, p2)) { false }
        assertEquals(2, chunks.size)
        assertEquals(listOf("a", "b"), chunks[0].blockIds)
        assertEquals("p1", chunks[0].card?.proposalId)
        assertEquals("p2", chunks[1].card?.proposalId)
    }

    // ---- what the card draws ----
    @Test
    fun aPressInFlightReadsAsStartingAndARefusalAsRefusedWithItsText() {
        val base = proposal(turn())
        val starting = proposalFace(base, com.tether.app.client.ProposalLocalState("start", "r1"))
        assertEquals(ProposalState.Starting, starting.state)
        assertTrue(starting.busy)
        val refused = proposalFace(base, com.tether.app.client.ProposalLocalState("start", "r1", com.tether.app.client.Proposals.parseRefusal("folder_unavailable: x")))
        assertEquals(ProposalState.Refused, refused.view.state)
        assertEquals("The chosen folder is not available for a new session.", refused.refusalText)
        assertEquals("folder", refused.view.faultRow)
        assertTrue(!refused.busy)
        // A resolved state is never changed by a press of this device.
        val done = proposal(turn(updated("started")))
        assertEquals(ProposalState.Started, proposalFace(done, com.tether.app.client.ProposalLocalState("start", "r1")).state)
    }

    @Test
    fun theOptionsRowCopyIsTheSpecsWords() {
        assertEquals("Suggested account “side” is not available here; using “work”.", ProposalCopy.replacedLine(ProposalRow.Account, "side", "work"))
        assertEquals("Not available for a new session. Choose another folder.", ProposalCopy.refusedRowLine(ProposalRow.Folder))
        assertEquals(ProposalRow.Mode, ProposalCopy.rowOfReplaced("sandboxPolicy"))
        assertEquals(setOf(ProposalRow.Harness, ProposalRow.Model), ProposalCopy.suggestedRows(com.tether.app.protocol.tree.JsObj.of("provider" to com.tether.app.protocol.tree.JsStr("codex"), "model" to com.tether.app.protocol.tree.JsStr("m"))))
    }

    private fun twoCalls(firstFailed: Boolean): ChatFixtures.Folded = ChatFixtures.fold(
        ev("turn_started", "t1") { put("idempotencyKey", "k") },
        ev("user_message_accepted", "t1") { put("text", "plan it") },
        ev("tool_start", "t1") { put("toolId", "tool-1"); put("name", "mcp__tether__propose_session"); put("input", buildJsonObject {}) },
        ev("tool_end", "t1") { put("toolId", "tool-1"); put("output", if (firstFailed) "refused" else "proposed"); put("isError", firstFailed) },
        ev("tool_start", "t1") { put("toolId", "tool-2"); put("name", "mcp__tether__propose_session"); put("input", buildJsonObject {}) },
        ev("session_proposal", "t1") { put("proposalId", "p1"); put("title", "Split the parser"); put("prompt", "Do the work.") },
        ev("tool_end", "t1") { put("toolId", "tool-2"); put("output", "proposed"); put("isError", false) },
    )

    @Test
    fun aFailedProposeCallKeepsItsOwnToolRowAndOnlyTheCallThatProposedGivesWayToTheCard() {
        val list = items(twoCalls(firstFailed = true), true)
        assertEquals(1, list.filterIsInstance<ChatItem.Proposal>().size)
        val rows = list.filter { it is ChatItem.Block && it.block.name == "mcp__tether__propose_session" || it is ChatItem.ToolGroup }
        assertEquals("the failed call keeps one tool row: $list", 1, rows.size)
    }

    @Test
    fun aProposeCallWithNoProposalLeftStaysAToolRow() {
        val f = ChatFixtures.fold(
            ev("turn_started", "t1") { put("idempotencyKey", "k") },
            ev("user_message_accepted", "t1") { put("text", "plan it") },
            ev("tool_start", "t1") { put("toolId", "tool-1"); put("name", "mcp__tether__propose_session"); put("input", buildJsonObject {}) },
            ev("tool_end", "t1") { put("toolId", "tool-1"); put("output", "x"); put("isError", false) },
        )
        val list = items(f, true)
        assertEquals(0, list.filterIsInstance<ChatItem.Proposal>().size)
        assertEquals(1, list.count { it is ChatItem.Block && it.block.name == "mcp__tether__propose_session" || it is ChatItem.ToolGroup })
    }
}
