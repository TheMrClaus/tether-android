package com.tether.app.ui.chat

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.chat.SessionErrorFixtures.Err
import com.tether.app.ui.chat.SessionErrorFixtures.order
import com.tether.app.ui.chat.SessionErrorFixtures.with
import kotlinx.serialization.json.JsonNull as KJsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-d3tj.10 (R1, W51 ruling 8 / (l) / (m) / (n)): the rows a REAL fold produces. The events go through the Kotlin
 * reduce (the app's own live fold, now writing `sessionErrors` and clearing `lastError` at turn start, as the web
 * does at events.ts:3544 / :2940), then through the same item builder the screen uses.
 */
@RunWith(RobolectricTestRunner::class)
class SessionErrorsFoldTest {
    private val t1 = ChatFixtures.T_IDLE
    private val t2 = ChatFixtures.T_MARKDOWN

    private fun error(message: String, seq: Long, ts: Long) =
        evNullTurn("error", seq = seq, ts = ts) { put("message", message) }

    private fun errorWithTs(message: String, seq: Long, ts: Double): AgentEvent = AgentEvent(
        buildJsonObject {
            put("type", "error")
            put("turnId", KJsonNull as JsonElement)
            put("seq", seq)
            put("ts", ts)
            put("message", message)
        },
    )

    private fun items(f: ChatFixtures.Folded) = buildChatItems(f.projection, f.tree, showThinking = false, zone = ChatFixtures.zone)
    private fun errorRows(f: ChatFixtures.Folded) = items(f).filterIsInstance<ChatItem.SessionError>()

    private fun turn(id: String, ts: Long) = ChatFixtures.turn(id, "Prompt $id", "Reply $id", ts)

    @Test fun aFoldedTurnlessErrorThenTheNextTurnRendersExactlyOneStampedRowThatSurvives() {
        // before the next turn: lastError AND the entry carry the message: ONE row, not the fallback twice.
        val live = ChatFixtures.fold(*turn("t1", t1), error("Engine exited.", seq = 7, ts = t1 + 30_000))
        assertEquals("Engine exited.", live.tree["lastError"].let { (it as com.tether.app.protocol.tree.JsStr).value })
        assertEquals(listOf("t1", "E:Engine exited."), order(items(live)))
        assertEquals(1, errorRows(live).size)
        assertEquals((t1 + 30_000).toDouble(), errorRows(live).single().ts)

        // the next turn clears lastError (events.ts:2940); the entry stays, placed by its ts, still ONE row, still stamped.
        val next = ChatFixtures.fold(*turn("t1", t1), error("Engine exited.", seq = 7, ts = t1 + 30_000), *turn("t2", t2))
        assertEquals(JsNull, next.tree["lastError"])
        assertEquals(listOf("t1", "E:Engine exited.", "t2"), order(items(next)))
        assertEquals(1, errorRows(next).size)
        assertNotNull(errorRows(next).single().ts)
    }

    @Test fun twoIdenticalTextErrorsAreTwoRowsEachAtItsOwnPosition() {
        val f = ChatFixtures.fold(
            *turn("t1", t1), error("same text", seq = 7, ts = t1 + 30_000),
            *turn("t2", t2), error("same text", seq = 19, ts = t2 + 30_000),
        )
        assertEquals(2, (f.tree["sessionErrors"] as JsArr).size)
        assertEquals(listOf("t1", "E:same text", "t2", "E:same text"), order(items(f)))
        assertEquals(listOf(t1 + 30_000.0, t2 + 30_000.0), errorRows(f).map { it.ts })
    }

    @Test fun aFractionalJournalTsSurvivesFoldTreeAndInterleave() {
        // t2 - 0.5 is BEFORE t2's start; a Long-truncated ts would tie t2 (a tie goes AFTER the turn) and misplace the row.
        val f = ChatFixtures.fold(*turn("t1", t1), errorWithTs("frac", seq = 3, ts = t2 - 0.5), *turn("t2", t2))
        val entry = (f.tree["sessionErrors"] as JsArr).single() as JsObj
        assertEquals(t2 - 0.5, (entry["ts"] as JsNum).value, 0.0)
        assertEquals(listOf("t1", "E:frac", "t2"), order(items(f)))
        assertEquals(t2 - 0.5, errorRows(f).single().ts)
    }

    @Test fun theLastErrorFallbackHidesWhenAnyEntryCarriesTheSameMessage() {
        // a replayed error leaves lastError on an entry that is not the last one: no second row (ruling 8).
        val f = with(
            SessionErrorFixtures.twoTurns,
            listOf(Err("first", t1 + 1.0, seq = 1), Err("second", t2 + 1.0, seq = 2)),
            lastError = "first",
        )
        assertEquals(listOf("t1", "E:first", "t2", "E:second"), order(items(f)))
        assertEquals(null, fallbackLastError("first", listOf(SessionErrorEntry(1, 1.0, "first"), SessionErrorEntry(2, 2.0, "second"))))
    }

    @Test fun aNullTsEntryInheritsTheEffectiveTsOfTheEntryBeforeIt() {
        // before t2: [a at t1+5, b null, c null] all sit between the turns (they inherit t1+5).
        val between = with(SessionErrorFixtures.twoTurns, listOf(Err("a", t1 + 5.0, seq = 1), Err("b", null, seq = 2), Err("c", null, seq = 3)))
        assertEquals(listOf("t1", "E:a", "E:b", "E:c", "t2"), order(items(between)))
        // behind a late entry (after t2): [a at t2+5, b null] b inherits t2+5, so it sits after t2 with a, not before it.
        val late = with(SessionErrorFixtures.twoTurns, listOf(Err("a", t2 + 5.0, seq = 1), Err("b", null, seq = 2)))
        assertEquals(listOf("t1", "t2", "E:a", "E:b"), order(items(late)))
        // the entry with no ts and no entry before it inherits 0.0 and so lands before the first turn it can: after no turn.
        val lead = with(SessionErrorFixtures.twoTurns, listOf(Err("lead", null, seq = 1)))
        assertEquals(listOf("E:lead", "t1", "t2"), order(items(lead)))
    }
}
