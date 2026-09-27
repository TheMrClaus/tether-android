package com.tether.app.ui.statusline

import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** components/session-statusline.tsx, session-dial.tsx and context-gauge.tsx logic. */
class StatusSegmentsTest {
    private val now = 1_790_078_400_000.0
    private val env = ReadingEnv(now, Locale.US, ZoneOffset.UTC)

    private val fullMetrics = TelemetryMetrics(
        contextPercent = 82.4,
        contextTokens = 164_800.0,
        contextWindow = 200_000.0,
        fiveHour = TelemetryWindow(41.0, now + 125 * 60_000),
        weekly = TelemetryWindow(93.0, now + 27 * 3_600_000),
    )

    private fun busyState(grace: String?): SessionView = SessionView(
        foldTree(
            freshTree(),
            ev("turn_started", "t1", seq = 1, ts = 1_790_000_001_000),
            ev("rate_limit", "t1", seq = 2, ts = 1_790_000_002_000) {
                put("status", "allowed_warning")
                put("resetsAt", now + 95 * 60_000)
                if (grace != null) put("grace", grace)
            },
            evNullTurn("todo_updated", seq = 3, ts = 1_790_000_003_000) {
                putJsonArray("items") {
                    addJsonObject { put("content", "Fix"); put("activeForm", "Fixing the parser"); put("status", "in_progress") }
                    addJsonObject { put("content", "Test"); put("activeForm", "Testing"); put("status", "pending") }
                }
            },
        ),
    )

    // session-statusline.tsx:64-138: fixed priority order, every segment a key + printed value.
    @Test fun allFiveSegmentsInPriorityOrder() {
        val segments = buildStatusSegments(fullMetrics, busyState("wrap_up"), env)
        assertEquals(listOf("wrap-up", "context", "five-hour", "weekly", "task"), segments.map { it.id })
        assertEquals(listOf("Limit", "Ctx", "5h", "Wk", "Now"), segments.map { it.key })
        assertEquals(listOf("Wrapping up", "82%", "41%", "93%", "Fixing the parser"), segments.map { it.value })
        assertEquals("Context 82% full · 164.8K of 200K tokens", segments[1].title)
        assertEquals("Five hour usage 41% · resets in 2h 5m", segments[2].title)
        assertEquals("Weekly usage 93% · resets in 1d 3h", segments[3].title)
        assertEquals("Current task: Fixing the parser · 0 of 2 complete", segments[4].title)
        assertTrue(segments[4].elastic)
        segments.forEach { assertTrue("${it.id} prints a value", it.key.isNotEmpty() && it.value.isNotEmpty()) }
    }

    // session-statusline.tsx:162 data-tone: explicit tone, else the fill level's; windows untoned.
    @Test fun tonesOnlyReinforcePrintedValues() {
        val segments = buildStatusSegments(fullMetrics, busyState("wrap_up"), env).associateBy { it.id }
        assertEquals(UsageTone.High, segments.getValue("wrap-up").effectiveTone)
        assertEquals(UsageTone.High, segments.getValue("context").effectiveTone) // 82%
        assertEquals(UsageTone.None, segments.getValue("weekly").effectiveTone) // 93% but no fill → untoned (web)
        assertEquals(UsageTone.None, segments.getValue("task").effectiveTone)
        assertEquals(82, segments.getValue("context").percent)
        assertEquals(null, segments.getValue("weekly").percent)
        val critical = buildStatusSegments(TelemetryMetrics(contextPercent = 104.0), null, env).single()
        assertEquals("100%", critical.value)
        assertEquals(UsageTone.Critical, critical.effectiveTone)
    }

    @Test fun noUsageYetRendersNothing() {
        assertEquals(emptyList<StatusSegment>(), buildStatusSegments(null, null, env))
        assertEquals(emptyList<StatusSegment>(), buildStatusSegments(TelemetryMetrics(), SessionView(freshTree()), env))
    }

    // issue #164: unknown model window → a snapshot footprint, printed with its time, no percent.
    @Test fun unknownWindowPrintsTheSnapshotFootprint() {
        val seg = buildStatusSegments(TelemetryMetrics(contextTokens = 91_540.0, contextSnapshotAt = 1_767_258_307_000.0), null, env).single()
        assertEquals("context", seg.id)
        assertEquals("91.5K", seg.value)
        assertEquals("Context snapshot · 91.5K tokens · as of 09:05 AM", seg.title)
        assertEquals(null, seg.percent)
        assertEquals(UsageTone.None, seg.effectiveTone)
    }

    // A missing high-priority reading promotes everything behind it.
    @Test fun missingReadingsPromoteTheTail() {
        val segments = buildStatusSegments(TelemetryMetrics(weekly = TelemetryWindow(12.0)), busyState(null), env)
        assertEquals(listOf("weekly", "task"), segments.map { it.id })
        assertEquals("Weekly usage 12% · Reset time unavailable", segments[0].title)
    }

    // globals.css 1861-1883: ranks by container width; fill bar hides at ≤ 9rem.
    @Test fun containerQueryRanks() {
        assertEquals(StatuslineFit(1, false), statuslineFit(4.5f))
        assertEquals(StatuslineFit(1, false), statuslineFit(8.49f))
        assertEquals(StatuslineFit(2, false), statuslineFit(8.5f))
        assertEquals(StatuslineFit(2, false), statuslineFit(9f)) // max-width: 9rem is inclusive
        assertEquals(StatuslineFit(2, true), statuslineFit(9.01f))
        assertEquals(StatuslineFit(3, true), statuslineFit(11.5f))
        assertEquals(StatuslineFit(4, true), statuslineFit(15.5f))
        assertEquals(StatuslineFit(4, true), statuslineFit(21.75f)) // the phone popover (380 − 2·16px): no task segment
        assertEquals(StatuslineFit(5, true), statuslineFit(22f))
    }

    // session-dial.tsx:14-20.
    @Test fun dialFormatsHoursMinutesSeconds() {
        val start = 1_790_000_000_000.0
        assertEquals("00:09:00", dialReading(start, null, true, start + 540_000).label)
        assertEquals("01:02:03", dialReading(start, null, true, start + 3_723_999).label) // floors seconds
        assertEquals("100:00:00", dialReading(start, null, true, start + 360_000_000).label) // hours never wrap
        assertEquals("00:00:00", dialReading(start, null, true, start - 5_000).label) // clock skew clamps
        assertEquals("00:02:00", dialReading(start, start + 120_000, false, start + 999_999).label) // frozen at stop
        assertEquals("00:00:00", dialReading(start, null, false, start + 999_999).label) // `stoppedAt || startedAt`
        assertEquals("00:00:00", dialReading(start, 0.0, false, start + 999_999).label)
        assertEquals("Session elapsed time 00:09:00", dialReading(start, null, true, start + 540_000).contentDescription)
        assertTrue(dialReading(start, null, true, start).active)
        assertFalse(dialReading(start, null, false, start).active)
    }

    // context-gauge.tsx:39-44: needle level + tone, with the printed reading as its label.
    @Test fun gaugeCarriesTheReadingAsText() {
        val g = gaugeReading(TelemetryMetrics(contextPercent = 45.0, contextTokens = 123_000.0, contextWindow = 1_000_000.0), env)
        assertEquals(45, g.percent)
        assertEquals(UsageTone.None, g.tone)
        assertEquals("Context 45% full · 123K of 1M tokens", g.text)
        assertEquals(0.45f, g.fillFraction, 0f)
        assertTrue(g.showsNeedle)

        val none = gaugeReading(null, env)
        assertEquals(null, none.percent)
        assertEquals("Session telemetry", none.text) // no usage yet: the fallback name
        assertFalse(none.showsNeedle)
        assertEquals("Telemetry", gaugeReading(null, env, title = "Telemetry").text)

        val zero = gaugeReading(TelemetryMetrics(contextPercent = 0.2), env)
        assertEquals(0, zero.percent)
        assertFalse(zero.showsNeedle) // percent > 0 draws the needle
        assertEquals(UsageTone.Critical, gaugeReading(TelemetryMetrics(contextPercent = 250.0), env).tone)
        assertEquals(1f, gaugeReading(TelemetryMetrics(contextPercent = 250.0), env).fillFraction, 0f)
    }
}
