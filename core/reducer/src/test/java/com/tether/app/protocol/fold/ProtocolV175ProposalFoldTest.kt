package com.tether.app.protocol.fold

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v175: `session_proposal` folds into the open turn, `session_proposal_updated` patches it in place; a terminal state is never left. */
class ProtocolV175ProposalFoldTest {
    private fun t(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): AgentEvent = ev(type, "t1", build = build)

    private val open = arrayOf(t("turn_started") { put("idempotencyKey", "k") }, t("user_message_accepted") { put("text", "go") })

    private fun proposed(id: String = "p1", extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = t("session_proposal") {
        put("proposalId", id)
        put("title", "Split the parser")
        put("prompt", "Do the work.")
        put("suggestions", buildJsonObject { put("provider", "codex"); put("cwd", "/w"); put("toolApproval", "bogus"); put("sandboxPolicy", "read-only") })
        extra()
    }

    private fun updated(id: String, state: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        evNullTurn("session_proposal_updated") { put("proposalId", id); put("state", state); extra() }

    private fun proposal(tree: JsObj, id: String = "p1"): JsObj? =
        tree["turnsById"].obj!!.values.firstNotNullOfOrNull { turn -> turn.obj!!["proposals"].arr?.firstOrNull { it.obj!!["proposalId"].str == id }?.obj }

    @Test
    fun aProposalFoldsIntoTheOpenTurnPendingWithOnlyKnownSuggestions() {
        val p = proposal(foldTree(freshTree(), *open, proposed()))!!
        assertEquals("pending", p["state"].str)
        assertEquals("Split the parser", p["title"].str)
        val s = p["suggestions"].obj!!
        assertEquals("codex", s["provider"].str)
        assertEquals("read-only", s["sandboxPolicy"].str)
        assertNull("an unknown approvals value is dropped", s["toolApproval"])
    }

    @Test
    fun aRepeatedProposalIsIgnored() {
        val tree = foldTree(freshTree(), *open, proposed(), proposed())
        assertEquals(1, tree["turnsById"].obj!!["t1"].obj!!["proposals"].arr!!.size)
    }

    @Test
    fun anUpdatePatchesInPlaceAndStartedKeepsTheNewSession() {
        val tree = foldTree(freshTree(), *open, proposed(), updated("p1", "starting"), updated("p1", "started") { put("startedSessionId", "s9"); put("startedSessionName", "Split the parser") })
        val p = proposal(tree)!!
        assertEquals("started", p["state"].str)
        assertEquals("s9", p["startedSessionId"].str)
        assertEquals("Split the parser", p["startedSessionName"].str)
    }

    @Test
    fun refusedKeepsItsCodeAndALaterStateClearsIt() {
        val refused = foldTree(freshTree(), *open, proposed(), updated("p1", "refused") { put("refusalCode", "folder_unavailable") })
        assertEquals("folder_unavailable", proposal(refused)!!["refusalCode"].str)
        val again = foldTree(refused, updated("p1", "starting"))
        assertNull(proposal(again)!!["refusalCode"])
    }

    @Test
    fun aTerminalStateIsNeverLeft() {
        val tree = foldTree(freshTree(), *open, proposed(), updated("p1", "dismissed"), updated("p1", "pending"), updated("p1", "started"))
        assertEquals("dismissed", proposal(tree)!!["state"].str)
    }

    @Test
    fun anUnknownIdOrStateChangesNothing() {
        val base = foldTree(freshTree(), *open, proposed())
        assertEquals(base, foldTree(base, updated("nope", "started")))
        assertEquals(base, foldTree(base, updated("p1", "weird")))
    }

    @Test
    fun aReplacedMarkerKeepsItsFieldCodeAndTheAgentsValue() {
        val tree = foldTree(freshTree(), *open, proposed {
            put("replaced", buildJsonArray {
                add(buildJsonObject { put("field", "profileId"); put("code", "profile_unavailable"); put("suggested", "side") })
                add(buildJsonObject { put("field", "bogus"); put("code", "profile_unavailable"); put("suggested", "x") })
            })
        })
        val replaced = proposal(tree)!!["replaced"].arr!!
        assertEquals(1, replaced.size)
        assertEquals("side", replaced[0].obj!!["suggested"].str)
        assertTrue(replaced[0].obj!!["field"].str == "profileId")
    }

    @Test
    fun aCompactedProposalReEmittedAlreadyResolvedKeepsItsState() {
        val tree = foldTree(freshTree(), *open, proposed { put("state", "started"); put("startedSessionId", "s9"); put("startedSessionName", "N") })
        assertEquals("started", proposal(tree)!!["state"].str)
    }
}
