package com.tether.app.ui.usage

import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import com.tether.app.ui.usage.UsageFixtures.NOW
import com.tether.app.ui.usage.UsageFixtures.env
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.2: the Usage surfaces' words and numbers, each against the web's own (file:line in each test). */
class UsageModelTest {
    // usage-dashboard.tsx:52-58
    @Test fun numbersFormatAsTheWebsIntlFormatters() {
        assertEquals("28.4M", UsageFormat.compact(28_400_000.0))
        assertEquals("0", UsageFormat.compact(0.0))
        assertEquals("1M", UsageFormat.compact(999_950.0))
        assertEquals("1,260", UsageFormat.whole(1260.0))
        assertEquals("0.5", UsageFormat.whole(0.5))
        assertEquals("$214", UsageFormat.money(214.37))
        assertEquals("$1,235", UsageFormat.money(1234.5))
        assertEquals("$0.42", UsageFormat.money2(0.42))
        assertEquals("$61.40", UsageFormat.money2(61.4))
        assertEquals("96.2", UsageFormat.js(96.2))
        assertEquals("100", UsageFormat.js(100.0))
    }

    // usage-dashboard.tsx:438-442
    @Test fun shortPathKeepsTheLastTwoSegments() {
        assertEquals("—", UsageFormat.shortPath(null))
        assertEquals("/tmp", UsageFormat.shortPath("/tmp"))
        assertEquals("/a/b", UsageFormat.shortPath("/a/b"))
        assertEquals("…/git/tether", UsageFormat.shortPath("/home/op/git/tether"))
    }

    // usage-dashboard.tsx:66-70 (toISOString().slice(0, 10), UTC)
    @Test fun sinceIsTheUtcDateDaysAgo() {
        assertEquals("2026-09-12", UsageDashboardModel.sinceParam(30, NOW))
        assertEquals("2026-10-05", UsageDashboardModel.sinceParam(7, NOW))
        assertEquals("", UsageDashboardModel.sinceParam(null, NOW))
    }

    // usage-dashboard.tsx:73-79, 140, 150-154
    @Test fun dashboardStateFollowsTheWebs() {
        val s = UsageDashboardState()
        assertEquals(UsageRange.Month, s.range)
        assertTrue(s.loading)
        assertEquals("Scanning transcripts…", UsageDashboardModel.syncState(s, env))
        assertEquals("Your agents, measured.", UsageDashboardModel.subtitle(s.data))
        s.onResult(UsageRange.Month, UsageCall.Ok(UsageFixtures.analytics(), "o"))
        assertFalse(s.loading)
        assertEquals("Updated 07:00 AM · refreshes automatically", UsageDashboardModel.syncState(s, env))
        assertEquals("48 sessions · 1,260 turns", UsageDashboardModel.subtitle(s.data))
        s.selectRange(UsageRange.Week)
        assertTrue("another range's data is loading", s.loading)
        s.onResult(UsageRange.Week, UsageCall.Failed(UsageFailure.Http(500, null)))
        assertEquals("HTTP 500", s.error)
        assertEquals("Update unavailable", UsageDashboardModel.syncState(s, env))
        assertEquals("Could not update usage. Showing the last available readings. HTTP 500", UsageDashboardModel.errorBanner(s.error!!, s.data != null))
        s.refreshNow()
        assertNull(s.error)
        assertEquals(1, s.refresh)
        // Another server's answer never lands.
        s.onResult(UsageRange.Week, UsageCall.Failed(UsageFailure.OtherServer))
        assertNull(s.error)
        assertEquals("Failed to fetch", UsageDashboardModel.errorText(UsageFailure.Unreachable))
    }

    // usage-accounts-dialog.tsx:92-100, 118-128
    @Test fun accountsReadingAgesAndLabels() {
        assertEquals("0m ago", UsageAccountsModel.lastReadingAge(NOW.toDouble(), NOW.toDouble(), env))
        assertEquals("59m ago", UsageAccountsModel.lastReadingAge(NOW - 59 * 60_000.0, NOW.toDouble(), env))
        assertEquals("2h ago", UsageAccountsModel.lastReadingAge(NOW - 2.5 * 3_600_000, NOW.toDouble(), env))
        assertEquals("6d ago", UsageAccountsModel.lastReadingAge(NOW - 6.5 * 86_400_000, NOW.toDouble(), env))
        assertEquals("Oct 1", UsageAccountsModel.lastReadingAge(NOW - 11 * 86_400_000.0, NOW.toDouble(), env))
        assertEquals("1 day", UsageAccountsModel.durationLabel(1440.0))
        assertEquals("12 hour", UsageAccountsModel.durationLabel(720.0))
        assertEquals("90 min", UsageAccountsModel.durationLabel(90.0))
        assertEquals("live session", UsageAccountsModel.sourceLabel("session"))
        assertEquals("polled", UsageAccountsModel.sourceLabel("http"))
        assertNull(UsageAccountsModel.sourceLabel("live"))
    }

    // usage-accounts-dialog.tsx:212-299
    @Test fun accountCardNotesInTheWebsPriorityOrder() {
        val a = UsageFixtures.accounts()
        val gen = a.generatedAt
        val default = a.claude[0]
        assertEquals(listOf("5 hour", "Weekly"), UsageAccountsModel.rows(default).map { it.first })
        assertEquals("Updated 3m ago · polled" to UsageAccountsModel.NoteTone.Fresh, UsageAccountsModel.note(default, gen, env))
        val work = a.claude[1]
        assertEquals("Rate-limited on refresh — showing last reading as of 25m ago" to UsageAccountsModel.NoteTone.Stale, UsageAccountsModel.note(work, gen, env))
        val old = a.claude[2]
        assertTrue(UsageAccountsModel.rows(old).isEmpty())
        assertEquals("Token expired — no reading until this account's CLI next runs" to UsageAccountsModel.NoteTone.Expired, UsageAccountsModel.note(old, gen, env))
        assertEquals("Token expired and no reading is cached yet — this account will report once its CLI next runs.", UsageAccountsModel.emptyText(old, gen, "x"))
        val throttled = work.copy(throttledByGuard = true, retryAfter = gen + 4_200)
        assertEquals("Asked Anthropic moments ago — try again in 5s; last reading 25m ago" to UsageAccountsModel.NoteTone.Throttled, UsageAccountsModel.note(throttled, gen, env))
        val codex = a.codex.single()
        assertEquals("No 5-hour limit on this plan — weekly only.", UsageAccountsModel.noFiveHour(codex))
        assertNull(UsageAccountsModel.noFiveHour(default))
        assertEquals("No rate-limit data reported yet.", UsageAccountsModel.codexFallback(codex))
        assertEquals("No Codex usage available right now — confirm Codex is logged in, then retry.", UsageAccountsModel.codexFallback(codex.copy(source = "unavailable")))
    }

    // usage-accounts-dialog.tsx:144-155
    @Test fun grantNotes() {
        val r = UsageFixtures.accounts().claude[0].resetGrants!!
        assertNull(UsageAccountsModel.grantsNote(r, NOW.toDouble(), env))
        assertEquals("Token expired — resets as of 3m ago; this account needs a CLI call to read again", UsageAccountsModel.grantsNote(r.copy(tokenExpired = true), NOW.toDouble(), env))
        assertEquals("Rate-limited — resets not read yet", UsageAccountsModel.grantsNote(r.copy(rateLimited = true, at = 0.0), NOW.toDouble(), env))
        assertEquals("Resets not read — no Claude Code CLI version could be resolved to identify as", UsageAccountsModel.grantsNote(r.copy(unavailable = true), NOW.toDouble(), env))
        assertEquals("Resets not read yet — try again in a moment", UsageAccountsModel.grantsNote(r.copy(throttledByGuard = true, at = 0.0), NOW.toDouble(), env))
    }

    // usage-accounts-dialog.tsx:533-558
    @Test fun loadErrorsAreTheWebs() {
        assertEquals("Could not reach Tether right now.", UsageAccountsModel.loadError(UsageFailure.Unreachable))
        assertEquals("Tether's server answered with an error (HTTP 502).", UsageAccountsModel.loadError(UsageFailure.Http(502, "x")))
        assertNull(UsageAccountsModel.loadError(UsageFailure.OtherServer))
        assertEquals("server env, dsh, Pi, OpenCode, Reasonix, other", UsageAccountsModel.deepSeekSources(listOf("env", "dsh", "pi", "opencode", "reasonix", "other")))
    }

    // codex-reset-credit-dialog.tsx:40-46, 131-132; claude-reset-grant-dialog.tsx:82-93
    @Test fun resetCopy() {
        assertEquals("Reset applied. Your 5-hour and weekly windows have been restored.", CodexResetCopy.outcome("reset", weeklyOnly = false))
        assertEquals("Reset applied. Your weekly window has been restored.", CodexResetCopy.outcome("reset", weeklyOnly = true))
        assertEquals("That reset was already redeemed.", CodexResetCopy.outcome("alreadyRedeemed", false))
        assertEquals(
            "Request completed, but the response wasn't recognized. Reopen this dialog to see the current state.",
            CodexResetCopy.outcome("somethingNew", false),
        )
        assertEquals("Redeem failed (500).", CodexResetCopy.error(UsageFailure.Http(500, null)))
        assertEquals("Nope.", CodexResetCopy.error(UsageFailure.Http(403, "Nope.")))
        assertEquals("Claim failed (HTTP 400).", ClaudeResetCopy.error(UsageFailure.Http(400, null)))
        assertEquals(
            "Could not reach Tether — it is unknown whether the claim was sent. Reopen Usage to check before retrying.",
            ClaudeResetCopy.error(UsageFailure.Unreachable),
        )
    }

    // deepseek-peak.tsx:82-86, 118-134; lib/deepseek-peak.mjs copy
    @Test fun deepSeekWords() {
        assertEquals("$0.6", usd(0.6))
        assertEquals("$0.003", usd(0.003))
        assertEquals("$3.96", usd(3.96))
        val copy = peakCopy(NOW)!!
        assertTrue(copy.peak)
        assertEquals(listOf("Peak", "2× rate", "Off-peak in 3h 0m"), copy.full)
        assertEquals("01:00 AM–04:00 AM, 06:00 AM–10:00 AM UTC", localWindows(NOW, env))
        val title = peakTitle("flash", "deepseek-v4-flash", copy, NOW, env)
        assertEquals(
            listOf(
                "deepseek-v4-flash · DeepSeek API · peak rate in force",
                "Off-peak in 3h 0m (10:00 AM UTC)",
                "Now, per 1M tokens: $0.3 input (cache miss), $0.006 input (cache hit), $1.2 output",
                "Off-peak: $0.15 input (cache miss), $0.003 input (cache hit), $0.6 output",
                "Peak hours: 01:00–04:00 and 06:00–10:00 UTC (01:00 AM–04:00 AM, 06:00 AM–10:00 AM UTC), Monday–Friday, except Chinese public holidays.",
            ).joinToString("\n"),
            title,
        )
        // A holiday weekday is off-peak in full, and says why.
        val holiday = peakCopy(java.time.Instant.parse("2026-10-06T07:00:00Z").toEpochMilli())!!
        assertFalse(holiday.peak)
        assertEquals("National Day", holiday.holidayName)
    }
}
