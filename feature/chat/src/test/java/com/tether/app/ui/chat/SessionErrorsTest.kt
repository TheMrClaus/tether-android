package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.js
import com.tether.app.ui.chat.SessionErrorFixtures.Err
import com.tether.app.ui.chat.SessionErrorFixtures.order
import com.tether.app.ui.chat.SessionErrorFixtures.with
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import java.util.Locale

/** W50 (ta-d3tj.8): the session-error rows, their fallback, the history gate and the outcome stamp. */
@RunWith(RobolectricTestRunner::class)
class SessionErrorsTest {
    private val t1 = ChatFixtures.T_IDLE.toDouble()
    private val t2 = ChatFixtures.T_MARKDOWN.toDouble()

    private fun items(f: ChatFixtures.Folded) = buildChatItems(f.projection, f.tree, showThinking = false, zone = ChatFixtures.zone)
    private fun orderOf(f: ChatFixtures.Folded) = order(items(f))

    // ---- interleave ----------------------------------------------------------------------------

    @Test fun errorsMergeByTsNotSeq() {
        // seq order is the reverse of the ts order: the rows follow ts (a later turn's seq can be smaller).
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("late", t2 + 1, seq = 1), Err("early", t1 + 1, seq = 2)))
        // journal order is kept (early listed second): the clamp lifts it to the previous effective ts.
        assertEquals(listOf("t1", "t2", "E:late", "E:early"), orderOf(f))
        val g = with(SessionErrorFixtures.twoTurns, listOf(Err("early", t1 + 1, seq = 9), Err("late", t2 + 1, seq = 2)))
        assertEquals(listOf("t1", "E:early", "t2", "E:late"), orderOf(g))
    }

    @Test fun aTieGoesAfterTheTurn() {
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("tie", t2, seq = 1)))
        assertEquals(listOf("t1", "t2", "E:tie"), orderOf(f))
        val before = with(SessionErrorFixtures.twoTurns, listOf(Err("older", t2 - 1, seq = 1)))
        assertEquals(listOf("t1", "E:older", "t2"), orderOf(before))
    }

    @Test fun aNullTsInheritsThePreviousEffectiveTs() {
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("a", t1 + 5, seq = 1), Err("b", null, seq = 2), Err("c", t1 - 1_000, seq = 3)))
        // b inherits t1+5; c (out of order) never jumps back before it: all three sit between the turns.
        assertEquals(listOf("t1", "E:a", "E:b", "E:c", "t2"), orderOf(f))
    }

    @Test fun aFractionalTsIsComparedAsADouble() {
        // t2 - 0.5 is BEFORE t2's start; a Long-truncated ts (= t2 - 1... or t2 itself) would tie or misplace it.
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("frac", t2 - 0.5, seq = 1)))
        assertEquals(listOf("t1", "E:frac", "t2"), orderOf(f))
        val g = with(SessionErrorFixtures.twoTurns, listOf(Err("frac", t2 + 0.5, seq = 1)))
        assertEquals(listOf("t1", "t2", "E:frac"), orderOf(g))
    }

    @Test fun errorsFlushBeforeABackgroundCommandAtTheSamePoint() {
        val cmd = JsObj.of("commandId" to js("c1"), "command" to js("ls"), "status" to js("finished"), "startedAt" to js(t2 + 100))
        val base = with(SessionErrorFixtures.twoTurns, listOf(Err("e", t2 + 50, seq = 1)))
        val tree = base.tree.put("backgroundCommands", JsArr.of(cmd))
        val f = ChatFixtures.Folded(base.projection, tree)
        assertEquals(listOf("t1", "t2", "E:e", "BG:c1"), orderOf(f))
        // the same point: the command's launch is the same ts as the error -> the error comes first.
        val same = ChatFixtures.Folded(base.projection, base.tree.put("backgroundCommands", JsArr.of(cmd.put("startedAt", js(t2 + 50)))))
        assertEquals(listOf("t1", "t2", "E:e", "BG:c1"), orderOf(same))
    }

    @Test fun errorsOlderThanTheFirstLoadedTurnWaitWithTheUnloadedOnes() {
        val bounded = ChatFixtures.boundedOf(5, trimmed = 3)
        val firstLoaded = ChatFixtures.T_IDLE + 4 * 60_000.0
        val f = with(bounded, listOf(Err("ancient", ChatFixtures.T_IDLE + 60_000.0, seq = 1), Err("mid", firstLoaded + 1_000, seq = 2)))
        // "ancient" predates the first loaded turn (t4): dropped, not shown above Load earlier. "mid" follows t4.
        assertEquals(listOf("LE", "t4", "E:mid", "t5"), orderOf(f))
    }

    @Test fun whenEveryTurnIsTrimmedTheErrorsGoAfterLoadEarlier() {
        val bounded = ChatFixtures.boundedOf(3, trimmed = 3)
        val f = with(bounded, listOf(Err("only", ChatFixtures.T_IDLE + 1.0, seq = 1)))
        assertEquals(listOf("LE", "E:only"), orderOf(f))
    }

    @Test fun anEmptyMessageStillRendersARowAndKeysAreStable() {
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("", t1 + 1, seq = 4), Err("x", null, seq = null)))
        val rows = items(f).filterIsInstance<ChatItem.SessionError>()
        assertEquals(listOf("", "x"), rows.map { it.text })
        assertTrue(rows[0].key.endsWith("/session-error/seq-4"))
        assertTrue(rows[1].key.endsWith("/session-error/idx-2")) // the web's idx is 1-based
    }

    // ---- the lastError fallback ----------------------------------------------------------------

    @Test fun lastErrorFallbackNeedsNoSessionErrors() {
        val f = with(SessionErrorFixtures.twoTurns, emptyList(), lastError = "boom")
        assertEquals(listOf("t1", "t2", "E:boom"), orderOf(f))
        assertTrue(items(f).filterIsInstance<ChatItem.SessionError>().single().key.endsWith("/last-error"))
    }

    @Test fun lastErrorEqualToTheLastSessionErrorDrawsOnce() {
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("boom", t1 + 1, seq = 1)), lastError = "boom")
        assertEquals(listOf("t1", "E:boom", "t2"), orderOf(f))
    }

    @Test fun aLiveLastErrorDifferingFromTheSnapshotsLastErrorDrawsAsFallbackToo() {
        val f = with(SessionErrorFixtures.twoTurns, listOf(Err("e1", t1 + 1, seq = 1)), lastError = "e2")
        assertEquals(listOf("t1", "E:e1", "t2", "E:e2"), orderOf(f))
    }

    @Test fun noLastErrorNoFallbackRow() {
        assertNull(fallbackLastError(null, emptyList()))
        assertNull(fallbackLastError("\u200B", emptyList()))
    }

    // ---- hasHistory ----------------------------------------------------------------------------

    @Test fun historyCountsTurnsErrorsAndTheFallbackRow() {
        assertFalse(chatHasHistory(SessionErrorFixtures.noTurns.projection, SessionErrorFixtures.noTurns.tree))
        assertTrue(chatHasHistory(SessionErrorFixtures.twoTurns.projection, SessionErrorFixtures.twoTurns.tree))
        val e = SessionErrorFixtures.errorOnly
        assertTrue(chatHasHistory(e.projection, e.tree))
        val last = with(SessionErrorFixtures.noTurns, emptyList(), lastError = "boom")
        assertTrue(chatHasHistory(last.projection, last.tree))
        val blank = with(SessionErrorFixtures.noTurns, emptyList(), lastError = "\u200B")
        assertFalse(chatHasHistory(blank.projection, blank.tree))
    }

    // ---- the outcome stamp ---------------------------------------------------------------------

    @Test fun outcomeAtIsErrorAtElseEndedAtElseNone() {
        val f = SessionErrorFixtures.twoTurns
        val turn = f.projection.turnsById.getValue("t1")
        val obj = (f.tree["turnsById"] as JsObj)["t1"] as JsObj
        assertNull(outcomeAt(obj, turn))
        assertEquals(5.5, outcomeAt(obj.put("endedAt", js(5.5)), turn)!!, 0.0)
        assertEquals(7.25, outcomeAt(obj.put("endedAt", js(5.5)).put("errorAt", js(7.25)), turn)!!, 0.0)
        assertEquals(5.5, outcomeAt(obj.put("errorAt", com.tether.app.protocol.tree.JsNull).put("endedAt", js(5.5)), turn)!!, 0.0)
    }

    @Test fun theOutcomeItemCarriesTheStamp() {
        val base = ChatFixtures.fold(
            *ChatFixtures.turn("t1", "p", "r", ChatFixtures.T_IDLE).dropLast(1).toTypedArray(),
            com.tether.app.protocol.reduce.ev("turn_end", "t1", ts = ChatFixtures.T_IDLE) { put("outcome", "error") },
        )
        val turnObj = ((base.tree["turnsById"] as JsObj)["t1"] as JsObj).put("errorAt", js(ChatFixtures.T_IDLE + 120_000.0))
        val tree = base.tree.put("turnsById", (base.tree["turnsById"] as JsObj).put("t1", turnObj))
        val outcome = buildChatItems(base.projection, tree, false, ChatFixtures.zone).filterIsInstance<ChatItem.Outcome>().single()
        assertEquals(ChatFixtures.T_IDLE + 120_000.0, outcome.at!!, 0.0)
    }

    @Test fun messageStampMatchesTheWebsOwnFunction() {
        // cases.json: generated by the web's lib/message-time.ts (see gen.mjs beside it), TZ=UTC LC_ALL=en_US.UTF-8.
        val text = javaClass.getResourceAsStream("/message-stamp/cases.json")!!.bufferedReader().readText()
        val cases = Json.parseToJsonElement(text) as JsonArray
        assertTrue(cases.size >= 19)
        for (c in cases) {
            val o = c as JsonObject
            val ts = (o["ts"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
            val now = o["now"]!!.jsonPrimitive.doubleOrNull!!.toLong()
            assertEquals("ts=$ts now=$now", o["stamp"]!!.jsonPrimitive.content, messageStamp(ts, now, ZoneOffset.UTC, Locale.US))
        }
    }

    @Test fun messageStampFollowsTheLocalDayInTheZone() {
        val zone = java.time.ZoneId.of("Pacific/Auckland") // UTC+13 in October 2026
        val now = java.time.Instant.parse("2026-10-18T12:00:00Z").toEpochMilli() // 01:00 on the 19th local
        val sameLocalDay = java.time.Instant.parse("2026-10-18T11:30:00Z").toEpochMilli().toDouble() // 00:30 on the 19th local
        assertEquals("00:30", messageStamp(sameLocalDay, now, zone, Locale.US))
        val prevLocalDay = java.time.Instant.parse("2026-10-18T10:30:00Z").toEpochMilli().toDouble() // 23:30 on the 18th local
        assertEquals("Oct 18, 23:30", messageStamp(prevLocalDay, now, zone, Locale.US))
    }
}
