package com.tether.app.ui.log

import com.tether.app.client.StatsResult
import com.tether.app.protocol.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4.5: the strings and filtering of components/log-dialog.tsx, case by case. */
class LogReadingsTest {

    private fun e(json: String): LogEntry = LogFixtures.entry(json)

    @Test
    fun knownEventsReadAsProseAndOthersAsTheirSlug() {
        assertEquals("Turn started", LogReadings.label(e("""{"seq":1,"event":"turn.start"}""")))
        assertEquals("Watchdog interrupted turn", LogReadings.label(e("""{"seq":1,"event":"watchdog.fired"}""")))
        assertEquals("ws.connect", LogReadings.label(e("""{"seq":1,"event":"ws.connect"}""")))
        assertEquals(9, LogReadings.EventLabels.size)
    }

    @Test
    fun turnEndDetailIsOutcomeDurationAndContinuation() {
        assertEquals("ok · 42.3s", LogReadings.detail(e("""{"seq":1,"event":"turn.end","outcome":"ok","durationMs":42300}""")))
        assertEquals("error · 0.1s · continuation", LogReadings.detail(e("""{"seq":1,"event":"turn.end","outcome":"error","durationMs":50,"continuation":true}""")))
        // toFixed(1): 1250ms is 1.25 → "1.3" (its double is exactly 1.25, a tie rounded up).
        assertEquals("1.3s", LogReadings.detail(e("""{"seq":1,"event":"turn.end","durationMs":1250}""")))
        // A turn.end reason is not printed (the reason branch is the else of the event checks).
        assertEquals("", LogReadings.detail(e("""{"seq":1,"event":"turn.end","reason":"x"}""")))
    }

    @Test
    fun turnStartShowsOnlyContinuation() {
        assertEquals("continuation", LogReadings.detail(e("""{"seq":1,"event":"turn.start","continuation":true}""")))
        assertEquals("", LogReadings.detail(e("""{"seq":1,"event":"turn.start","continuation":false}""")))
        // Without a continuation, turn.start falls through to the reason branch, as on the web.
        assertEquals("r", LogReadings.detail(e("""{"seq":1,"event":"turn.start","continuation":false,"reason":"r"}""")))
    }

    @Test
    fun otherEventsShowReasonOutstandingAndMessage() {
        assertEquals("idle · 1 outstanding", LogReadings.detail(e("""{"seq":1,"event":"session.evict","reason":"idle","outstanding":1}""")))
        assertEquals("2.5 outstanding · boom", LogReadings.detail(e("""{"seq":1,"event":"x","outstanding":2.5,"message":"boom"}""")))
        assertEquals("", LogReadings.detail(e("""{"seq":1,"event":"x","reason":5}""")))
    }

    @Test
    fun shortIdsKeepEightCharactersAndAnEllipsis() {
        assertEquals("", LogReadings.shortId(null))
        assertEquals("abcdefgh", LogReadings.shortId("abcdefgh"))
        assertEquals("abcdefgh…", LogReadings.shortId("abcdefghi"))
    }

    @Test
    fun uptimeReadsLikeTheWeb() {
        assertEquals("0s", LogReadings.uptime(-5))
        assertEquals("59s", LogReadings.uptime(59_999))
        assertEquals("1m", LogReadings.uptime(60_000))
        assertEquals("3h 7m", LogReadings.uptime((3 * 3600 + 7 * 60 + 5) * 1000L))
        assertEquals("2d 5h", LogReadings.uptime((53 * 3600) * 1000L))
    }

    @Test
    fun mibRoundsHalfUp() {
        assertEquals("155 MB", LogReadings.mib(162_529_280))
        assertEquals("1 MB", LogReadings.mib(524_288)) // 0.5 rounds up
        assertEquals("0 MB", LogReadings.mib(524_287))
    }

    @Test
    fun clockTimeIsTheTwoDigitEnUsForm() {
        assertEquals("12:00:47 AM", LogReadings.clockTime(1_767_225_647_300, LogFixtures.locale, LogFixtures.zone))
        assertEquals("01:05:03 PM", LogReadings.clockTime(1_767_272_703_000, LogFixtures.locale, LogFixtures.zone))
    }

    @Test
    fun rowsAreNewestFirstAndTheWarningsFilterDropsOnlyInfo() {
        val all = LogReadings.filtered(LogFixtures.mixed, LogLevelFilter.All, AllSessions)
        assertEquals(listOf(7L, 6, 5, 4, 3, 2, 1), all.map { it.seq })
        val warnings = LogReadings.filtered(LogFixtures.mixed + e("""{"seq":8,"event":"odd"}"""), LogLevelFilter.Warnings, AllSessions)
        assertEquals("a missing level is not info", listOf(8L, 7, 6, 4), warnings.map { it.seq })
    }

    @Test
    fun theSessionFilterKeepsOneSidAndCombinesWithTheLevel() {
        assertEquals(listOf(6L, 5, 4), LogReadings.filtered(LogFixtures.mixed, LogLevelFilter.All, "sess-0002").map { it.seq })
        assertEquals(listOf(6L, 4), LogReadings.filtered(LogFixtures.mixed, LogLevelFilter.Warnings, "sess-0002").map { it.seq })
        assertTrue(LogReadings.filtered(LogFixtures.mixed, LogLevelFilter.All, "gone").isEmpty())
    }

    @Test
    fun loggedSessionsAreFirstSeenOrderByNameElseShortId() {
        val names = LogReadings.namesById(LogFixtures.sessions)
        assertEquals(
            listOf(
                LoggedSession("sess-0001", "Fix flaky login test"),
                LoggedSession("sess-0002", "Refactor billing export"),
                LoggedSession("sess-9f3c2a71b0", "sess-9f3…"),
            ),
            LogReadings.loggedSessions(LogFixtures.mixed, names),
        )
    }

    @Test
    fun warnCountCountsEveryNonInfoEntry() {
        assertEquals(3, LogReadings.warnCount(LogFixtures.mixed))
    }

    @Test
    fun statsLinesReadLikeTheWeb() {
        val s = LogFixtures.stats
        assertEquals("claude,codex · persistent", LogReadings.engine(s))
        assertEquals("claude", LogReadings.engine(s.copy(headlessMode = "claude", headlessPersistent = false)))
        assertEquals("terminal only", LogReadings.engine(s.copy(headlessMode = "")))
        assertEquals("terminal only", LogReadings.engine(s.copy(headlessMode = null)))
        assertEquals("7 total · 6 chat", LogReadings.sessions(s))
        assertEquals("7 total · 0 chat", LogReadings.sessions(s.copy(sessionsHeadless = null)))
        assertEquals("155 MB rss · 47 MB heap", LogReadings.memory(s))
        assertEquals("Active turns / 4", LogReadings.activeTurnsCaption(s))
        assertEquals("Active turns", LogReadings.activeTurnsCaption(s.copy(runtime = s.runtime!!.copy(maxConcurrentTurns = 0))))
        assertEquals("Active turns", LogReadings.activeTurnsCaption(s.copy(runtime = null)))
    }

    @Test
    fun aFailedRefreshKeepsTheLastSnapshotAndASuccessClearsTheError() {
        val state = LogDialogState()
        state.onStats(StatsResult.Failed("stats request failed (502)"))
        assertNull(state.stats)
        assertEquals("stats request failed (502)", state.statsError)
        state.onStats(StatsResult.Loaded(LogFixtures.stats))
        assertEquals(LogFixtures.stats, state.stats)
        assertEquals("", state.statsError)
        state.onStats(StatsResult.Failed("Could not load stats."))
        assertEquals(LogFixtures.stats, state.stats)
        assertEquals("Could not load stats.", state.statsError)
    }

    @Test
    fun theCorpusScenarioDecodesIntoRowsTheDialogCanPrint() {
        val entries = LogFixtures.corpusLog("ambient.jsonl")
        assertTrue(entries.isNotEmpty())
        val end = entries.first { it.event == "turn.end" }
        assertTrue(LogReadings.detail(end).matches(Regex("""\w+ · \d+\.\ds""")))
    }
}
