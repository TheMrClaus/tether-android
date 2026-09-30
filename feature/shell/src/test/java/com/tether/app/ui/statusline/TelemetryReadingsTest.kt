package com.tether.app.ui.statusline

import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.UsageWindow
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mapping layer against the web's logic, components/telemetry-readings.tsx (line refs per test). */
class TelemetryReadingsTest {
    private val now = 1_790_078_400_000.0 // 2026-09-22T12:00:00Z, the corpus reference clock
    private val env = ReadingEnv(now, Locale.US, ZoneOffset.UTC)

    // ── usageTone (22-25): thresholds 75 / 90, inclusive ──
    @Test fun usageToneThresholds() {
        assertEquals(UsageTone.None, usageTone(null))
        assertEquals(UsageTone.None, usageTone(0))
        assertEquals(UsageTone.None, usageTone(74))
        assertEquals(UsageTone.High, usageTone(75))
        assertEquals(UsageTone.High, usageTone(89))
        assertEquals(UsageTone.Critical, usageTone(90))
        assertEquals(UsageTone.Critical, usageTone(100))
    }

    // ── percentReading (28-31): Math.round, clamp 0..100, non-finite → null ──
    @Test fun percentReadingRoundsLikeMathRoundAndClamps() {
        assertNull(percentReading(null))
        assertNull(percentReading(Double.NaN))
        assertNull(percentReading(Double.POSITIVE_INFINITY))
        assertEquals(45, percentReading(44.5)) // ties toward +∞
        assertEquals(44, percentReading(44.49))
        assertEquals(0, percentReading(-0.5)) // Math.round(-0.5) = -0 → clamps to 0
        assertEquals(0, percentReading(-12.0))
        assertEquals(100, percentReading(137.2)) // over 100% context clamps
        assertEquals(75, percentReading(74.5)) // rounding crosses the High threshold
        assertEquals(0, percentReading(0.49999999999999994)) // JS Math.round, not floor(x + 0.5)
    }

    // ── contextReading (111-128) ──
    @Test fun contextReadingCaptionFallsBackThroughWhatWasReported() {
        val both = contextReading(TelemetryMetrics(contextPercent = 45.2, contextTokens = 123_400.0, contextWindow = 1_000_000.0), env)!!
        assertEquals(45, both.percent)
        assertEquals("123.4K of 1M tokens", both.caption)
        assertEquals("Context 45% full · 123.4K of 1M tokens", both.detail)
        assertEquals(
            "1.3K tokens in context", // compactNumber rounds half-up (the divergent ui/util floors to 1.2K)
            contextReading(TelemetryMetrics(contextPercent = 3.0, contextTokens = 1_250.0), env)!!.caption,
        )
        assertEquals("200K token window", contextReading(TelemetryMetrics(contextPercent = 3.0, contextWindow = 200_000.0), env)!!.caption)
        assertEquals("Window size not reported", contextReading(TelemetryMetrics(contextPercent = 3.0), env)!!.caption)
    }

    @Test fun contextReadingAbsentWhenNoPercent() {
        assertNull(contextReading(null, env)) // no usage yet
        assertNull(contextReading(TelemetryMetrics(contextTokens = 5_000.0, contextWindow = 200_000.0), env)) // unknown percent
        assertNull(contextReading(TelemetryMetrics(contextPercent = Double.NaN), env))
    }

    @Test fun contextReadingOverOneHundredPercentClampsAndIsCritical() {
        val over = contextReading(TelemetryMetrics(contextPercent = 112.0, contextTokens = 224_000.0, contextWindow = 200_000.0), env)!!
        assertEquals(100, over.percent)
        assertEquals("Context 100% full · 224K of 200K tokens", over.detail)
        assertEquals(UsageTone.Critical, usageTone(over.percent))
    }

    @Test fun contextReadingNamesSnapshotProvenance() {
        val m = TelemetryMetrics(contextPercent = 40.0, contextTokens = 80_000.0, contextSnapshotAt = 1_767_258_307_000.0)
        assertEquals("80K tokens in context · snapshot 09:05 AM", contextReading(m, env)!!.caption)
        assertEquals("snapshot 09:05 AM", contextSnapshotLabel(m, env))
        assertNull(contextSnapshotLabel(TelemetryMetrics(contextPercent = 40.0), env))
    }

    // ── contextSnapshotReading (102-109): unknown model window ──
    @Test fun snapshotReadingOnlyWithoutALiveMeter() {
        val snap = TelemetryMetrics(contextTokens = 91_500.0, contextSnapshotAt = 1_767_258_307_000.0)
        assertEquals(ContextSnapshotReading(91_500.0, "09:05 AM"), contextSnapshotReading(snap, env))
        assertNull(contextSnapshotReading(snap.copy(contextPercent = 12.0), env)) // live meter supersedes
        assertNull(contextSnapshotReading(snap.copy(contextSnapshotAt = null), env)) // no stamp
        assertNull(contextSnapshotReading(snap.copy(contextTokens = null), env))
        assertNull(contextSnapshotReading(null, env))
    }

    // ── windowReading (136-142) ──
    @Test fun windowReadingCaptionsTheReset() {
        assertEquals(WindowReading(null, "Not reported"), windowReading(null, env))
        assertEquals(WindowReading(42, "resets in 2h 5m"), windowReading(TelemetryWindow(41.6, now + 125 * 60_000), env))
        assertEquals(WindowReading(7, "resets in 1d 3h"), windowReading(TelemetryWindow(7.0, now + 27 * 3_600_000), env))
        assertEquals(WindowReading(7, "Reset time unavailable"), windowReading(TelemetryWindow(7.0), env))
        assertEquals(WindowReading(null, "resets in 0m"), windowReading(TelemetryWindow(Double.NaN, now - 1), env))
    }

    // ── gitDivergence (146-155) ──
    @Test fun gitDivergenceWording() {
        assertNull(gitDivergence(null, null))
        assertEquals("In sync with upstream", gitDivergence(0.0, 0.0))
        assertEquals("1 commit ahead upstream", gitDivergence(1.0, 0.0))
        assertEquals("2 commits ahead, 1 commit behind upstream", gitDivergence(2.0, 1.0))
        assertEquals("3 commits behind upstream", gitDivergence(null, 3.0))
    }

    // ── taskReading (164-178) ──
    @Test fun taskReadingFromTheProjectionTodo() {
        fun todo(active: String?, completed: Double?, total: Double?) = JsObj.of(
            "items" to com.tether.app.protocol.tree.JsArr.EMPTY,
            "activeForm" to (active?.let(::JsStr) ?: JsNull),
            "completed" to (completed?.let(::JsNum) ?: JsNull),
            "total" to (total?.let(::JsNum) ?: JsNull),
        )
        assertNull(taskReading(null))
        assertEquals(TaskReading("Fixing the parser", "2 of 5 complete"), taskReading(todo("  Fixing the parser\n", 2.0, 5.0)))
        assertEquals(TaskReading("Fixing the parser", null), taskReading(todo("Fixing the parser", 0.0, 0.0)))
        assertEquals(TaskReading("2 of 5 tasks complete", null), taskReading(todo(null, 2.0, 5.0)))
        assertEquals(TaskReading("2 of 5 tasks complete", null), taskReading(todo("   ", 2.0, 5.0)))
        assertNull(taskReading(todo(null, 5.0, 5.0))) // a finished list is not a current task
        assertNull(taskReading(todo(null, 0.0, 0.0)))
        assertEquals(TaskReading("Wrapping", "5 of 5 complete"), taskReading(todo("Wrapping", 5.0, 5.0)))
    }

    @Test fun taskReadingFromARealFold() {
        val tree = foldTree(
            freshTree(),
            ev("turn_started", "t1", seq = 1, ts = 1_790_000_001_000),
            evNullTurn("todo_updated", seq = 2, ts = 1_790_000_002_000) {
                putJsonArray("items") {
                    addJsonObject { put("content", "Read"); put("activeForm", "Reading"); put("status", "completed") }
                    addJsonObject { put("content", "Fix"); put("activeForm", "Fixing the parser"); put("status", "in_progress") }
                    addJsonObject { put("content", "Test"); put("activeForm", "Testing"); put("status", "pending") }
                }
            },
        )
        assertEquals(TaskReading("Fixing the parser", "1 of 3 complete"), taskReading(SessionView(tree).todo))
    }

    // ── wrapUpReading (201-217), v127 ──
    private fun graceTree(grace: String?, resetsAt: Double?, openTurn: Boolean = true): SessionView {
        val events = buildList {
            if (openTurn) add(ev("turn_started", "t1", seq = 1, ts = 1_790_000_001_000))
            add(
                ev("rate_limit", "t1", seq = 2, ts = 1_790_000_002_000) {
                    put("status", "allowed_warning")
                    if (resetsAt != null) put("resetsAt", resetsAt)
                    if (grace != null) put("grace", grace)
                },
            )
        }
        return SessionView(foldTree(freshTree(), *events.toTypedArray()))
    }

    @Test fun wrapUpWhileTheAllowanceCoversTheTurnInFlight() {
        val reading = wrapUpReading(graceTree("wrap_up", now + 95 * 60_000), env)!!
        assertEquals("Wrapping up", reading.label)
        assertEquals(
            "Usage limit reached · wrapping up — a capped allowance — it may stop after the current step · limit resets in 1h 35m",
            reading.detail,
        )
    }

    @Test fun wrapUpThenCreditsVariantAndUnknownReset() {
        val reading = wrapUpReading(graceTree("wrap_up_then_credits", null), env)!!
        assertEquals("Usage limit reached · brief included wrap-up, then usage credits — usage credits cover the rest", reading.detail)
    }

    @Test fun noWrapUpWithoutGraceOrTurnOrAfterReset() {
        assertNull(wrapUpReading(graceTree(null, now + 60_000), env)) // most accounts: never appears
        assertNull(wrapUpReading(graceTree("wrap_up", now + 60_000, openTurn = false), env)) // no turn in flight
        assertNull(wrapUpReading(graceTree("wrap_up", now), env)) // reset reached on this clock
        assertNull(wrapUpReading(graceTree("unknown_variant", now + 60_000), env)) // fold drops unknown grace
        assertNull(wrapUpReading(null as SessionView?, env))
    }

    @Test fun wrapUpClearsAtTheTurnBoundary() {
        val tree = foldTree(
            graceTree("wrap_up", now + 60_000).obj,
            ev("turn_end", "t1", seq = 3, ts = 1_790_000_003_000) { put("outcome", "success") },
        )
        assertNull(wrapUpReading(SessionView(tree), env))
        assertNull(wrapUpExpiresAt(SessionView(tree)))
        assertEquals(now + 60_000, wrapUpExpiresAt(graceTree("wrap_up", now + 60_000))!!, 0.0)
    }

    // ── TelemetryMetrics.from: the wire row → the reading inputs ──
    @Test fun metricsFromTheWireRow() {
        val m = TelemetryMetrics.from(
            SessionMetrics(
                contextPercent = 45.0,
                contextTokens = 90_000,
                contextWindow = 200_000,
                fiveHour = UsageWindow(12.0, 300, resetsAt = 1_790_080_000_000),
                gitAhead = 2,
            ),
        )!!
        assertEquals(90_000.0, m.contextTokens!!, 0.0)
        assertEquals(TelemetryWindow(12.0, 1_790_080_000_000.0), m.fiveHour)
        assertNull(m.weekly)
        assertNull(m.contextSnapshotAt) // absent on the row: a live reading
        // T9.1: decoded now, so a transcript-derived reading carries its stamp.
        assertEquals(1_790_000_000_000.0, TelemetryMetrics.from(SessionMetrics(contextSnapshotAt = 1_790_000_000_000))!!.contextSnapshotAt!!, 0.0)
        assertNull(TelemetryMetrics.from(null))
    }
}
