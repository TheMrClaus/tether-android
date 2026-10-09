package com.tether.app.client

import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.SessionDisplay
import com.tether.app.ui.displayStable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-jtfq: the session list as drawn holds through the server's per-event stamps and never draws a stale age word or order. */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySessionsTest {
    private val base = 1_000_000_000_000L

    private fun session(id: String, stamp: Long, status: String = "active", seq: Long = 0) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = status, startedAt = 1, updatedAt = stamp, lastMessageAt = stamp, lastSeq = seq)

    @Test fun stampsThatKeepTheWordAndTheOrderAreTheSameDrawing() {
        val held = listOf(session("a", base + 10_000), session("b", base))
        val next = listOf(session("a", base + 10_040, seq = 1), session("b", base))
        assertTrue(SessionDisplay.equivalent(held, next, base + 20_000))
    }

    @Test fun aChangedWordIsADifferentDrawing() {
        val held = listOf(session("a", base))
        // 59 s old vs 61 s old: "now" vs "1m".
        assertFalse(SessionDisplay.equivalent(held, listOf(session("a", base + 2_000)), base + 61_000))
        assertTrue(SessionDisplay.equivalent(held, listOf(session("a", base + 2_000)), base + 30_000))
    }

    @Test fun aChangedOrderIsADifferentDrawing() {
        val held = listOf(session("a", base + 5_000), session("b", base))
        val next = listOf(session("b", base + 6_000), session("a", base + 5_000))
        assertFalse(SessionDisplay.equivalent(held, next, base + 10_000))
    }

    @Test fun anyOtherFieldIsADifferentDrawing() {
        val held = listOf(session("a", base))
        assertFalse(SessionDisplay.equivalent(held, listOf(session("a", base + 40, status = "ready")), base + 1_000))
        assertFalse(SessionDisplay.equivalent(held, listOf(session("a", base + 40).copy(name = "renamed")), base + 1_000))
        assertFalse(SessionDisplay.equivalent(held, held + session("b", base), base + 1_000))
    }

    @Test fun theNextWordComesAtTheRightInstant() {
        assertEquals(base + 60_000, SessionDisplay.nextWord(base, base))
        assertEquals(base + 60_000, SessionDisplay.nextWord(base, base + 59_999))
        assertEquals(base + 120_000, SessionDisplay.nextWord(base, base + 60_000))
        assertEquals(base + 3_600_000, SessionDisplay.nextWord(base, base + 3_599_000))
        assertEquals(base + 2 * 3_600_000, SessionDisplay.nextWord(base, base + 3_600_000))
        assertEquals(base + 86_400_000, SessionDisplay.nextWord(base, base + 86_399_000))
        assertEquals(Long.MAX_VALUE, SessionDisplay.nextWord(base, base + 8 * 86_400_000L))
    }

    @Test fun theHeldListIsRepublishedAtTheInstantAWordWouldChange() = runTest {
        val source = MutableStateFlow(listOf(session("a", base)))
        val drawn = displayStable(source, backgroundScope, clock = { base + currentTime })
        val seen = mutableListOf<List<AgentSession>>()
        backgroundScope.launch { drawn.collect { seen += it } }
        runCurrent()
        val first = drawn.value
        // 40 ms stamps for 50 s: nothing is republished.
        var t = 0L
        while (t < 50_000) {
            advanceTimeBy(40); t += 40
            source.value = listOf(session("a", base + t, seq = t))
            runCurrent()
        }
        assertSame(first, drawn.value)
        assertEquals(1, seen.size)
        // At the old stamp's 60 s the held list's word would turn "1m" while the real one says "now": the latest is published then.
        advanceTimeBy(60_000 - t - 1)
        runCurrent()
        assertSame(first, drawn.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, seen.size)
        assertEquals(source.value, drawn.value)
    }

    @Test fun anotherChangeIsPublishedAtOnceAndEndsTheWait() = runTest {
        val source = MutableStateFlow(listOf(session("a", base)))
        val drawn = displayStable(source, backgroundScope, clock = { base + currentTime })
        runCurrent()
        source.value = listOf(session("a", base + 40, seq = 1))
        runCurrent()
        assertEquals(base, drawn.value.single().updatedAt)
        source.value = listOf(session("a", base + 80, status = "ready", seq = 2))
        runCurrent()
        assertEquals("ready", drawn.value.single().status)
        assertEquals(base + 80, drawn.value.single().updatedAt)
    }

    // ---- round 3: unread, dedupe, every row's order, the hour and day boundary through the flow ----

    @Test fun aSettledRowsStampIsDrawnAsItsUnreadDot() {
        // `unread = lastSeenAt < updatedAt` for a not-open ready or exited row: a stamp-only bump can flip it.
        for (status in listOf("ready", "exited")) {
            val held = listOf(session("a", base, status = status))
            assertFalse(status, SessionDisplay.equivalent(held, listOf(session("a", base + 40, status = status)), base + 1_000))
        }
        // A running or waiting row never shows it, whatever the stamp.
        for (status in listOf("active", "waiting")) {
            val held = listOf(session("a", base, status = status))
            assertTrue(status, SessionDisplay.equivalent(held, listOf(session("a", base + 40, status = status)), base + 1_000))
        }
    }

    @Test fun aSettledRowsStampIsPublishedAtOnceThroughTheFlow() = runTest {
        val source = MutableStateFlow(listOf(session("a", base, status = "ready")))
        val drawn = displayStable(source, backgroundScope, clock = { base + currentTime })
        runCurrent()
        source.value = listOf(session("a", base + 40, status = "ready", seq = 1))
        runCurrent()
        assertEquals(base + 40, drawn.value.single().updatedAt)
    }

    @Test fun theSameChatDedupeKeepsTheSameRow() {
        // Two rows of one native chat: the more recently active one is the row drawn. Held: a (newer) wins; next: b overtakes.
        fun chat(id: String, stamp: Long) = session(id, stamp).copy(nativeSessionId = "native")
        val held = listOf(chat("a", base + 10_000), chat("b", base + 9_000))
        val flipped = listOf(chat("b", base + 10_500), chat("a", base + 10_000))
        assertFalse(SessionDisplay.equivalent(held, flipped, base + 12_000))
        val kept = listOf(chat("a", base + 10_500), chat("b", base + 9_000))
        assertTrue(SessionDisplay.equivalent(held, kept, base + 12_000))
    }

    @Test fun aLiveRowCrossingAHistoryOnlyRowIsADifferentDrawing() {
        val history = mapOf("h1" to base + 10_020)
        val held = listOf(session("a", base + 10_000))
        // Still below the history row: the same order of the three rows' stamps.
        assertTrue(SessionDisplay.equivalent(held, listOf(session("a", base + 10_010)), base + 20_000, history))
        // Above it now: the live row moved past the history row although the live ids did not move.
        assertFalse(SessionDisplay.equivalent(held, listOf(session("a", base + 10_040)), base + 20_000, history))
        // A tie with it is a different order too.
        assertFalse(SessionDisplay.equivalent(held, listOf(session("a", base + 10_020)), base + 20_000, history))
    }

    @Test fun aHistoryRowMovingPastTheHeldListIsRecheckedThroughTheFlow() = runTest {
        val source = MutableStateFlow(listOf(session("a", base + 10_000)))
        val history = MutableStateFlow(mapOf("h1" to base + 5_000))
        val drawn = displayStable(source, backgroundScope, history, clock = { base + 20_000 + currentTime })
        runCurrent()
        source.value = listOf(session("a", base + 10_040, seq = 1))
        runCurrent()
        assertEquals("held: still above the history row", base + 10_000, drawn.value.single().updatedAt)
        history.value = mapOf("h1" to base + 10_020)
        runCurrent()
        assertEquals("the history row moved between the held and the real stamp", base + 10_040, drawn.value.single().updatedAt)
    }

    /** Stamps arrive every 40 ms from a clock [ageMs] past the held stamp; the held word changes 5 s in (the minute edge of the hour or day word). */
    private fun runBoundary(ageMs: Long) = runTest {
        val source = MutableStateFlow(listOf(session("a", base)))
        val drawn = displayStable(source, backgroundScope, clock = { base + ageMs + currentTime })
        val seen = mutableListOf<List<AgentSession>>()
        backgroundScope.launch { drawn.collect { seen += it } }
        runCurrent()
        val first = drawn.value
        var t = 0L
        while (t < 4_960) {
            advanceTimeBy(40); t += 40
            source.value = listOf(session("a", base + t, seq = t))
            runCurrent()
        }
        assertSame("no word has changed yet", first, drawn.value)
        assertEquals(1, seen.size)
        advanceTimeBy(39)
        runCurrent()
        assertEquals("still before the edge", 1, seen.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("published at the edge", 2, seen.size)
        assertEquals(source.value, drawn.value)
    }

    @Test fun theHourWordFlipsAtItsBoundaryThroughTheFlow() = runBoundary(3_595_000)

    @Test fun theDayWordFlipsAtItsBoundaryThroughTheFlow() = runBoundary(86_395_000)
}
