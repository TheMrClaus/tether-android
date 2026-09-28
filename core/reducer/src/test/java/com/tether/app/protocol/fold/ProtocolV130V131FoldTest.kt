package com.tether.app.protocol.fold

import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * ta-koy: the v130 (S13.1-C `removedQueueIds`) and v131 (pending `createdAt`) fold paths the
 * vendored corpus does not reach (it never removes more than 2 ids, folds no pre-v130 state and
 * stamps every event). Every expectation below was produced by the real engines/events.mjs at
 * tether 79c3d37 folding the same events.
 */
class ProtocolV130V131FoldTest {

    private fun added(queueId: String) = evNullTurn("queued_message_added") {
        put("queueId", queueId)
        put("text", "t")
    }

    private fun removed(queueId: String) = evNullTurn("queued_message_removed") { put("queueId", queueId) }

    private fun ids(state: JsObj): List<String> = state["removedQueueIds"].arr!!.map { (it as JsStr).value }

    @Test
    fun initialStateCarriesAnEmptyList() {
        assertSame(JsArr.EMPTY, freshTree()["removedQueueIds"])
    }

    @Test
    fun theListIsBoundedAtFiftyOldestDropped() {
        var state = freshTree()
        for (i in 1..55) state = foldTree(state, added("q$i"))
        for (i in 1..55) state = foldTree(state, removed("q$i"))
        assertEquals(Limits.MAX_REMOVED_QUEUE_IDS, ids(state).size)
        assertEquals((6..55).map { "q$it" }, ids(state))
        assertEquals(0, state["queuedMessages"].arr!!.size)
    }

    @Test
    fun aReRemovedIdMovesToTheEndOnce() {
        var state = freshTree()
        fun cycle(q: String) {
            state = foldTree(state, added(q), removed(q))
        }
        cycle("q1")
        cycle("q1")
        assertEquals(listOf("q1"), ids(state))
        cycle("q2")
        cycle("q1")
        assertEquals(listOf("q2", "q1"), ids(state))
    }

    @Test
    fun removingAnUnknownIdIsTheSameState() {
        val state = foldTree(freshTree(), added("q1"), removed("q1"))
        assertSame(state, foldTree(state, removed("nope")))
    }

    @Test
    fun aPreV130StateGainsTheListOnItsFirstRemoval() {
        for (legacyValue in listOf(null, JsNull)) {
            var state = freshTree().with("removedQueueIds" to legacyValue)
            state = foldTree(state, added("q1"))
            assertEquals(legacyValue == null, !state.containsKey("removedQueueIds"))
            state = foldTree(state, removed("q1"))
            assertEquals(listOf("q1"), ids(state))
        }
    }

    @Test
    fun pendingRequestsCarryTheJournalStampedCreatedAtOnlyWhenStamped() {
        // ts -> createdAt (JS): undefined -> absent, 1790000005000 -> same, -1 -> absent, 0 -> 0.
        val cases = listOf<Long?>(null, 1_790_000_005_000, -1, 0)
        for (ts in cases) {
            val state = foldTree(
                freshTree(),
                ev("turn_started", turnId = "t1", seq = 1, ts = 1_790_000_001_000),
                ev("approval_request", turnId = "t1", ts = ts) {
                    put("requestId", "r1")
                    put("toolId", "u1")
                    put("name", "Bash")
                    put("input", JsonObject(emptyMap()))
                },
                ev("question_request", turnId = "t1", ts = ts) {
                    put("requestId", "r2")
                    put("toolId", "u2")
                    put("questions", JsonArray(emptyList()))
                },
            )
            val turn = state["turnsById"].obj!!["t1"].obj!!
            val approval = turn["pendingApprovals"].obj!!["r1"].obj!!
            val question = turn["pendingQuestions"].obj!!["r2"].obj!!
            val expected = ts?.takeIf { it >= 0 }?.let { js(it.toDouble()) }
            assertEquals("approval ts=$ts", expected, approval["createdAt"])
            assertEquals("question ts=$ts", expected, question["createdAt"])
            if (expected == null) {
                assertFalse(approval.containsKey("createdAt"))
                assertFalse(question.containsKey("createdAt"))
            }
        }
    }
}
