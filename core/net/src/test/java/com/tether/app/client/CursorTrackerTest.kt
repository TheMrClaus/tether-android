package com.tether.app.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorTrackerTest {

    @Test
    fun eventsForNeverAttachedSessionsAreIgnored() {
        val tracker = CursorTracker()
        assertEquals(CursorTracker.Decision.Ignore, tracker.onEvent("s1", 1, canSend = true))
        assertNull(tracker.cursorFor("s1"))
    }

    @Test
    fun contiguousSeqAdvancesAndFolds() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        assertEquals(CursorTracker.Decision.Fold, tracker.onEvent("s1", 11, canSend = true))
        assertEquals(CursorTracker.Decision.Fold, tracker.onEvent("s1", 12, canSend = true))
        assertEquals(12L, tracker.cursorFor("s1"))
    }

    @Test
    fun duplicatesAreDropped() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        assertEquals(CursorTracker.Decision.Drop, tracker.onEvent("s1", 10, canSend = true))
        assertEquals(CursorTracker.Decision.Drop, tracker.onEvent("s1", 3, canSend = true))
        assertEquals(10L, tracker.cursorFor("s1"))
    }

    @Test
    fun gapTriggersExactlyOneResyncAndFreezesCursor() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        val first = tracker.onEvent("s1", 13, canSend = true)
        assertEquals(CursorTracker.Decision.Resync(afterSeq = 10), first)
        // Every later gapped event in the same burst awaits the snapshot silently
        // (the cursor stays frozen, so they all still read as a gap).
        assertEquals(CursorTracker.Decision.AwaitSnapshot, tracker.onEvent("s1", 14, canSend = true))
        assertEquals(CursorTracker.Decision.AwaitSnapshot, tracker.onEvent("s1", 15, canSend = true))
        assertEquals(10L, tracker.cursorFor("s1"))
        // Reference-client semantics: a CONTIGUOUS seq still folds and advances
        // even while the resync is pending (the snapshot will override anyway).
        assertEquals(CursorTracker.Decision.Fold, tracker.onEvent("s1", 11, canSend = true))
        assertEquals(11L, tracker.cursorFor("s1"))
    }

    @Test
    fun snapshotResetsCursorAndClearsResyncFlag() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        tracker.onEvent("s1", 13, canSend = true) // opens the gap
        tracker.onSnapshot("s1", 15)
        assertEquals(15L, tracker.cursorFor("s1"))
        // Gap resolved: a NEW gap may request a new resync.
        assertEquals(CursorTracker.Decision.Resync(afterSeq = 15), tracker.onEvent("s1", 18, canSend = true))
    }

    @Test
    fun gapWithClosedSocketDoesNotLatchTheFlag() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        assertEquals(CursorTracker.Decision.AwaitSnapshot, tracker.onEvent("s1", 13, canSend = false))
        // Socket back: the next gap event still gets its one resync.
        assertEquals(CursorTracker.Decision.Resync(afterSeq = 10), tracker.onEvent("s1", 14, canSend = true))
    }

    @Test
    fun seqlessEventsFoldWithoutMovingTheCursor() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        assertEquals(CursorTracker.Decision.Fold, tracker.onEvent("s1", null, canSend = true))
        assertEquals(10L, tracker.cursorFor("s1"))
    }

    @Test
    fun sessionsAreIndependent() {
        val tracker = CursorTracker()
        tracker.onSnapshot("s1", 10)
        tracker.onSnapshot("s2", 5)
        tracker.onEvent("s1", 20, canSend = true) // s1 gapped
        assertEquals(CursorTracker.Decision.Fold, tracker.onEvent("s2", 6, canSend = true))
        assertTrue(tracker.attachedSessions().containsAll(listOf("s1", "s2")))
    }
}
