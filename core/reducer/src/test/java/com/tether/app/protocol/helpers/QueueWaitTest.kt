package com.tether.app.protocol.helpers

import com.tether.app.protocol.helpers.QueueWait.DEFERRAL_CHOICE_AFTER_MS
import com.tether.app.protocol.helpers.QueueWait.DeferredWait
import com.tether.app.protocol.helpers.QueueWait.LiveWork
import com.tether.app.protocol.helpers.QueueWait.deferredWaitCopy
import com.tether.app.protocol.helpers.QueueWait.formatWaited
import com.tether.app.protocol.helpers.QueueWait.interruptStopsCopy
import com.tether.app.protocol.helpers.QueueWait.liveWorkCopy
import com.tether.app.protocol.helpers.QueueWait.stopCostCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-ceo: lib/queue-wait.mjs at tether 887c222. The first block of each test is
 * tests/queue-wait.test.mjs hand-ported case for case; the rest ("edges") were produced by running
 * the real web module (Node 22) on the same inputs.
 */
class QueueWaitTest {

    @Test
    fun theThresholdIsSixtySeconds() = assertEquals(60_000L, DEFERRAL_CHOICE_AFTER_MS)

    @Test
    fun formatWaitedRoundsDownToTheSecondAndScalesUnits() {
        // tests/queue-wait.test.mjs:8
        assertEquals("0s", formatWaited(0.0))
        assertEquals("45s", formatWaited(45_900.0))
        assertEquals("14m 43s", formatWaited(14 * 60_000.0 + 43_000))
        assertEquals("1h 02m", formatWaited(62 * 60_000.0))
        assertEquals("", formatWaited(-1.0))
        assertEquals("", formatWaited(Double.NaN))
        // edges
        assertEquals("0s", formatWaited(999.0))
        assertEquals("1s", formatWaited(1_000.0))
        assertEquals("59s", formatWaited(59_999.0))
        assertEquals("1m 00s", formatWaited(60_000.0))
        assertEquals("1m 01s", formatWaited(61_000.0))
        assertEquals("59m 59s", formatWaited(3_599_999.0))
        assertEquals("1h 00m", formatWaited(3_600_000.0))
        assertEquals("1h 01m", formatWaited(3_660_000.0))
        assertEquals("72h 00m", formatWaited(259_200_000.0))
        assertEquals("416666666h 40m", formatWaited(1.5e15))
        assertEquals("", formatWaited(Double.POSITIVE_INFINITY))
        assertEquals("", formatWaited(null))
    }

    @Test
    fun liveWorkCopyNamesToolsAndBackgroundTasksEmptyWhenIdle() {
        // tests/queue-wait.test.mjs:17
        assertEquals("", liveWorkCopy(LiveWork(0, 0)))
        assertEquals("1 tool running · 7 background tasks live", liveWorkCopy(LiveWork(1, 7)))
        assertEquals("1 background task live", liveWorkCopy(LiveWork(outstandingBackground = 1)))
        // edges
        assertEquals("", liveWorkCopy(LiveWork.None))
        assertEquals("2 tools running", liveWorkCopy(LiveWork(openTools = 2)))
        assertEquals("2 tools running · 3 background tasks live", liveWorkCopy(LiveWork(2, 3)))
        assertEquals("1 background task live", liveWorkCopy(LiveWork(-1, 1)))
    }

    @Test
    fun deferredWaitCopySaysWhatItWaitsForAndHowLongAndAsksAfterTheThreshold() {
        // tests/queue-wait.test.mjs:23
        val early = deferredWaitCopy(LiveWork(outstandingBackground = 7), 5_000.0)
        assertEquals("Queued — waiting for a safe boundary (7 background tasks live · waiting 5s)", early.label)
        assertFalse(early.needsChoice)
        assertTrue(deferredWaitCopy(LiveWork(outstandingBackground = 7), DEFERRAL_CHOICE_AFTER_MS.toDouble()).needsChoice)
        // Nothing live any more: no wait reason, and never a choice to make.
        val clear = deferredWaitCopy(LiveWork.None, 10.0 * DEFERRAL_CHOICE_AFTER_MS)
        assertTrue(clear.label.contains("next tool call or when the turn ends"))
        assertFalse(clear.needsChoice)
        // No stamp (older journal): still renders, never asks for a choice.
        assertFalse(deferredWaitCopy(LiveWork(outstandingBackground = 2), null).needsChoice)
        // edges
        assertEquals(DeferredWait("Queued — waiting for a safe boundary (1 tool running · waiting 59s)", false), deferredWaitCopy(LiveWork(openTools = 1), 59_999.0))
        assertEquals(DeferredWait("Queued — waiting for a safe boundary (1 tool running · waiting 1m 00s)", true), deferredWaitCopy(LiveWork(openTools = 1), 60_000.0))
        assertEquals(DeferredWait("Queued — sends at the next tool call or when the turn ends (waiting 5s)", false), deferredWaitCopy(LiveWork.None, 5_000.0))
        assertEquals(DeferredWait("Queued — sends at the next tool call or when the turn ends", false), deferredWaitCopy(LiveWork.None, null))
        assertEquals(
            DeferredWait("Queued — waiting for a safe boundary (2 tools running · 3 background tasks live · waiting 1h 02m)", true),
            deferredWaitCopy(LiveWork(2, 3), 3_725_000.0),
        )
        assertEquals(DeferredWait("Queued — waiting for a safe boundary (1 background task live)", false), deferredWaitCopy(LiveWork(outstandingBackground = 1), -5.0))
    }

    @Test
    fun stopCostCopyIsEmptyWhenStopDestroysNothingButTheTurn() {
        // tests/queue-wait.test.mjs:38
        assertEquals("", stopCostCopy(LiveWork.None))
        assertEquals("Stop will kill 7 background tasks", stopCostCopy(LiveWork(outstandingBackground = 7)))
        assertEquals("Stop ends the open tool call", stopCostCopy(LiveWork(openTools = 1)))
        assertEquals("Stop ends the open tool call and will kill 1 background task", stopCostCopy(LiveWork(1, 1)))
        // edges
        assertEquals("Stop ends the 2 open tool calls", stopCostCopy(LiveWork(openTools = 2)))
        assertEquals("Stop ends the 2 open tool calls and will kill 3 background tasks", stopCostCopy(LiveWork(2, 3)))
        assertEquals("Stop will kill 1 background task", stopCostCopy(LiveWork(-1, 1)))
    }

    /** chat-view.tsx:1537 `cost.replace(/^Stop (will |ends )?/, "")` — the choice line's object. */
    @Test
    fun theChoiceLineDropsTheLeadingVerb() {
        assertEquals("kill 7 background tasks", interruptStopsCopy("Stop will kill 7 background tasks"))
        assertEquals("the open tool call", interruptStopsCopy("Stop ends the open tool call"))
        assertEquals("the open tool call and will kill 1 background task", interruptStopsCopy("Stop ends the open tool call and will kill 1 background task"))
        assertEquals("", interruptStopsCopy(""))
    }
}
