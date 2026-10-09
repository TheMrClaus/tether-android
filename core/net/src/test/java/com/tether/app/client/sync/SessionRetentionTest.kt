package com.tether.app.client.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-2vm7: which sessions keep their projection in memory. The policy alone, with no socket. */
class SessionRetentionTest {
    private val mb = 1L shl 20

    @Test
    fun theOpenSessionAndTheThreeMostRecentlyOpenedStay() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 1_000 * mb)
        val released = (1..20).flatMap { r.open("s$it") }
        assertEquals(setOf("s17", "s18", "s19", "s20"), r.retained)
        assertEquals("s20", r.open)
        // Every other one was released exactly once, oldest first.
        assertEquals((1..16).map { "s$it" }, released)
    }

    @Test
    fun reopeningARetainedSessionMovesItToTheFrontWithoutReleasingAnything() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 1_000 * mb)
        listOf("a", "b", "c", "d").forEach { r.open(it) }
        assertEquals(emptyList<String>(), r.open("a"))
        // a is the freshest now, so b is the oldest and goes when a fifth one opens.
        assertEquals(listOf("b"), r.open("e"))
        assertEquals(setOf("a", "c", "d", "e"), r.retained)
    }

    @Test
    fun theSizeBudgetBoundsWhatIsKeptEvenUnderTheCount() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 10 * mb)
        r.open("a"); r.resize("a", 4 * mb)
        r.open("b"); r.resize("b", 4 * mb)
        r.open("c")
        // c grows past the budget: the oldest goes first, then the next, never the open one.
        assertEquals(listOf("a", "b"), r.resize("c", 9 * mb))
        assertEquals(setOf("c"), r.retained)
        assertTrue(r.totalBytes() <= 10 * mb)
    }

    @Test
    fun oneHugeOpenSessionEvictsTheOthersButNeverItself() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 10 * mb)
        r.open("a"); r.resize("a", 1 * mb)
        r.open("huge")
        assertEquals(listOf("a"), r.resize("huge", 50 * mb))
        assertEquals(setOf("huge"), r.retained)
        assertEquals("huge", r.open)
        // Events keep growing it: still never released while open.
        assertEquals(emptyList<String>(), r.grew("huge", 5 * mb))
        assertTrue(r.isRetained("huge"))
    }

    @Test
    fun aSessionThatIsNotRetainedIsNeverCounted() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 10 * mb)
        r.open("a")
        assertEquals(emptyList<String>(), r.resize("ghost", 100 * mb))
        assertEquals(emptyList<String>(), r.grew("ghost", 100 * mb))
        assertFalse(r.isRetained("ghost"))
        assertEquals(0L, r.totalBytes())
    }

    @Test
    fun growthByEventsCountsAgainstTheBudget() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 10 * mb)
        r.open("a"); r.resize("a", 3 * mb)
        r.open("b"); r.resize("b", 3 * mb)
        assertEquals(emptyList<String>(), r.grew("b", 3 * mb))
        assertEquals(listOf("a"), r.grew("b", 3 * mb))
        assertEquals(9 * mb, r.totalBytes())
    }

    @Test
    fun aTrimKeepsOnlyTheOpenSession() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 1_000 * mb)
        listOf("a", "b", "c").forEach { r.open(it) }
        assertEquals(listOf("a", "b"), r.trim())
        assertEquals(setOf("c"), r.retained)
        assertEquals(emptyList<String>(), r.trim())
    }

    @Test
    fun forgetAndClearEmptyIt() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 1_000 * mb)
        r.open("a"); r.open("b")
        r.forget("a")
        assertEquals(setOf("b"), r.retained)
        r.clear()
        assertEquals(emptySet<String>(), r.retained)
        assertEquals(null, r.open)
    }

    @Test
    fun adoptedSessionsJoinTheOldEndAndNeverBecomeTheOpenOne() {
        val r = SessionRetention(maxOthers = 3, budgetBytes = 1_000 * mb)
        assertEquals(4, r.room())
        assertEquals(emptyList<String>(), r.adopt(listOf("a", "b")))
        assertEquals(2, r.room())
        r.open("x")
        assertEquals("x", r.open)
        // Adopted ones are older than a, b: a fifth is too many and the oldest of them goes.
        assertEquals(listOf("c"), r.adopt(listOf("c", "d")))
        assertEquals(setOf("a", "b", "d", "x"), r.retained)
        assertEquals("x", r.open)
    }
}
