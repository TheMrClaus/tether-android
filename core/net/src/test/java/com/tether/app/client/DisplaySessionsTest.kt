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
        val drawn = displayStable(source, backgroundScope) { base + currentTime }
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
        val drawn = displayStable(source, backgroundScope) { base + currentTime }
        runCurrent()
        source.value = listOf(session("a", base + 40, seq = 1))
        runCurrent()
        assertEquals(base, drawn.value.single().updatedAt)
        source.value = listOf(session("a", base + 80, status = "ready", seq = 2))
        runCurrent()
        assertEquals("ready", drawn.value.single().status)
        assertEquals(base + 80, drawn.value.single().updatedAt)
    }
}
