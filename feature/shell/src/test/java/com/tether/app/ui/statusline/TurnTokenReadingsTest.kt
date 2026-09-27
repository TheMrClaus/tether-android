package com.tether.app.ui.statusline

import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.model.TurnView
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `token_progress` and `usage` → the values shown (components/turn-activity.tsx), read from real
 * v128 folds of the events.
 */
class TurnTokenReadingsTest {
    private var seq = 0L
    private fun e(type: String, turnId: String?, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        (++seq).let { ev(type, turnId, seq = it, ts = 1_790_000_000_000 + it * 1000, build = build) }

    private fun fold(state: JsObj, vararg events: com.tether.app.protocol.AgentEvent) = foldTree(state, *events)

    @Test fun liveEstimateCountsTheOpenRunOnly() {
        var tree = fold(freshTree(), e("turn_started", "t1"), e("token_progress", "t1") { put("tokens", 420) })
        val session = SessionView(tree)
        val turn = session.activeTurn!!
        assertEquals(420.0, runTokens(turn)!!, 0.0)
        assertNull(settledTurnTokens(turn)) // still open: not accounted
        assertEquals(SessionTokenTotal(0.0, 0), sessionTokenTotal(session))

        tree = fold(tree, e("token_progress", "t1") { put("tokens", 960) })
        assertEquals("960 tokens", tokenLabel(runTokens(SessionView(tree).activeTurn)!!))
    }

    @Test fun settledTurnTakesTheMaxOfUsageAndLiveEstimate() {
        val tree = fold(
            freshTree(),
            e("turn_started", "t1"),
            e("token_progress", "t1") { put("tokens", 1_200) },
            e("usage", "t1") { put("perTurnTokens", 1_250) },
            e("turn_end", "t1") { put("outcome", "success") },
            e("turn_started", "t2"),
            e("token_progress", "t2") { put("tokens", 5_000) },
            e("usage", "t2") { put("perTurnTokens", 4_000) }, // authoritative lands under the estimate
            e("turn_end", "t2") { put("outcome", "success") },
        )
        val session = SessionView(tree)
        assertEquals(1_250.0, settledTurnTokens(session.turn("t1")!!)!!, 0.0)
        assertEquals(5_000.0, settledTurnTokens(session.turn("t2")!!)!!, 0.0) // never falls
        val total = sessionTokenTotal(session)
        assertEquals(SessionTokenTotal(6_250.0, 2), total)
        assertEquals("6.3K tokens", tokenLabel(total.tokens)) // lib/format compact: half-up, not floor
        assertNull(runTokens(session.turn("t2"))) // no run once the turn is over
    }

    @Test fun finishedTurnWithoutFiguresCountsZero() {
        val tree = fold(freshTree(), e("turn_started", "t1"), e("turn_end", "t1") { put("outcome", "success") })
        assertEquals(0.0, settledTurnTokens(SessionView(tree).turn("t1")!!)!!, 0.0)
        assertEquals(SessionTokenTotal(0.0, 1), sessionTokenTotal(SessionView(tree)))
    }

    // turn-activity.tsx:171-173 with a malformed run: JS `live - undefined` is NaN, Math.max keeps
    // it, and the web renders tokenLabel(NaN) = "— tokens"; a JSON-null start coerces to 0.
    @Test fun runTokensFollowJsSubtraction() {
        fun turn(start: JsValue?) = TurnView(
            JsObj.of(
                "turnId" to JsStr("t1"),
                "liveTokens" to JsNum(420.0),
                "run" to JsObj.of("index" to JsNum(0.0), "startedAt" to JsNum(1.0), "tokensStart" to start),
            ),
        )
        assertEquals(320.0, runTokens(turn(JsNum(100.0)))!!, 0.0)
        assertEquals(0.0, runTokens(turn(JsNum(900.0)))!!, 0.0) // never below 0
        assertEquals(420.0, runTokens(turn(JsNull))!!, 0.0) // null → 0
        val missing = runTokens(turn(null))!! // absent → NaN, still a reading
        assertTrue(missing.isNaN())
        assertEquals("— tokens", tokenLabel(missing))
        assertTrue(runTokens(turn(JsStr("abc")))!!.isNaN())
    }

    @Test fun tokenLabelSingularAndCompact() {
        assertEquals("1 token", tokenLabel(1.0))
        assertEquals("0 tokens", tokenLabel(0.0))
        assertEquals("1M tokens", tokenLabel(999_950.0)) // divergent ui/util printed "999.9K"
        assertEquals("1.2T tokens", tokenLabel(1.2e12)) // the T unit the divergent helper lacked
    }
}
