package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2.2: the host-locale branches the helper exporter deliberately skipped (corpus-UNCOVERED):
 * format.ts relativeTime ≥ 7 days, absoluteTime, clockTime's finite branch, and
 * claude-reset-grants-view claimOutcomeCopy's weekly-reset sentence. Pinned for en-US / UTC:
 * the literal expectations are what Node 22 (ICU) prints for the web's option bags with
 * `toLocale*String("en-US", { …, timeZone: "UTC" })` (ASCII space before AM/PM), and each is
 * cross-checked against java.time.
 */
class HelperLocaleBranchesTest {

    private val us = Locale.US
    private val utc = ZoneOffset.UTC
    private val now = 1790078400000.0 // Tue 2026-09-22 12:00:00Z, the corpus referenceNowMs

    private fun javaTime(pattern: String, ms: Long) = DateTimeFormatter.ofPattern(pattern, us).format(Instant.ofEpochMilli(ms).atZone(utc))

    @Test
    fun relativeTimeFallsBackToAShortDateFromSevenDays() {
        val eightDays = now - 8 * 86_400_000
        assertEquals("Sep 14", Format.relativeTime(eightDays, now, us, utc))
        assertEquals(javaTime("MMM d", eightDays.toLong()), Format.relativeTime(eightDays, now, us, utc))
        // exactly 7 days is already the date branch; 6d 23h 59m is still "6d"
        assertEquals("Sep 15", Format.relativeTime(now - 7 * 86_400_000, now, us, utc))
        assertEquals("6d", Format.relativeTime(now - 7 * 86_400_000 + 60_000, now, us, utc))
    }

    @Test
    fun absoluteTimeIsDateAndTwoDigitClock() {
        assertEquals("Jan 1, 2026, 09:05 AM", Format.absoluteTime(1767258307000.0, us, utc))
        assertEquals("Sep 22, 2026, 12:00 PM", Format.absoluteTime(now, us, utc))
        assertEquals(javaTime("MMM d, yyyy, hh:mm a", now.toLong()), Format.absoluteTime(now, us, utc))
    }

    @Test
    fun clockTimeFiniteBranchIsTwoDigitHourMinute() {
        assertEquals("09:05 AM", Format.clockTime(JsNum(1767258307000.0), us, utc))
        assertEquals("12:00 PM", Format.clockTime(JsNum(now), us, utc))
        assertEquals(javaTime("hh:mm a", now.toLong()), Format.clockTime(JsNum(now), us, utc))
        // the recorded non-finite branches stay ""
        assertEquals("", Format.clockTime(JsNum(Double.NaN), us, utc))
        assertEquals("", Format.clockTime(null, us, utc))
    }

    @Test
    fun claimOutcomeCopyStatesTheWeeklyResetDay() {
        val response = JsObj.of(
            "outcome" to JsStr("reset"),
            "cleared" to JsArr.of(JsStr("five_hour")),
            "resetsLeft" to js(1),
            "weeklyResetsAt" to js(1790241900000L), // Thu 2026-09-24 09:25Z
        )
        val copy = ClaudeResetGrantsView.claimOutcomeCopy(response, now, us, utc)
        assertEquals(
            "Reset applied — refilled your 5-hour limit. Your weekly reset day stays Thu, Sep 24, 9:25 AM. 1 reset left.",
            (copy["text"] as JsStr).value,
        )
        assertEquals("Thu, Sep 24, 9:25 AM", javaTime("EEE, MMM d, h:mm a", 1790241900000L))
    }
}
