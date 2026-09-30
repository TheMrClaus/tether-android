package com.tether.app.ui.overview

import com.tether.app.client.HostMetrics
import com.tether.app.client.HostReading
import com.tether.app.client.OverviewMetricsResult
import com.tether.app.client.OverviewUsage
import com.tether.app.client.UsageCoverage
import com.tether.app.ui.overview.HostUsageFixtures.ORIGIN_A
import com.tether.app.ui.overview.HostUsageFixtures.ORIGIN_B
import com.tether.app.ui.overview.HostUsageFixtures.NOW
import com.tether.app.ui.overview.HostUsageFixtures.fresh
import com.tether.app.ui.overview.HostUsagePresentation.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15.3: components/overview/overview-host.tsx and overview-format.ts, ported case by case — the
 * reading fold (`useJsonPoll`), the meters (labels, figures, 75 / 90 % thresholds, unavailable
 * reasons, never 0 %), staleness, "Tokens today" and its coverage breakdown — plus the native
 * rules: one server's readings never outlive an answer from another, and signed out shows nothing.
 */
class HostUsageModelTest {
    private val empty = MetricsReading<HostMetrics>()
    private val noUsage = MetricsReading<OverviewUsage>()

    private fun tile(
        host: MetricsReading<HostMetrics> = fresh(HostUsageFixtures.host),
        usage: MetricsReading<OverviewUsage> = fresh(HostUsageFixtures.usageExact),
        now: Long = NOW,
        connected: Boolean = true,
    ) = HostUsagePresentation.tile(host, usage, now, connected)

    // --- the fold (useJsonPoll) --------------------------------------------------------------

    @Test fun aValueReplacesTheReadingAndAFailureKeepsTheLastValue() {
        val ok = HostUsageModel.fold(empty, OverviewMetricsResult.Ok(HostUsageFixtures.host, ORIGIN_A), 10, current = ORIGIN_A)
        assertEquals(MetricsReading(HostUsageFixtures.host, null, 10L, ORIGIN_A), ok)
        val failed = HostUsageModel.fold(ok, OverviewMetricsResult.Unavailable(500, ORIGIN_A), 20, current = ORIGIN_A)
        assertEquals(MetricsReading(HostUsageFixtures.host, MetricsFault.Unavailable(500), 10L, ORIGIN_A), failed)
        val blocked = HostUsageModel.fold(ok, OverviewMetricsResult.Blocked(302, ORIGIN_A), 20, current = ORIGIN_A)
        assertEquals(HostUsageFixtures.host, blocked.data)
        assertEquals(MetricsFault.Blocked(302), blocked.fault)
        // The next value clears the fault.
        assertEquals(MetricsReading(HostUsageFixtures.host, null, 30L, ORIGIN_A), HostUsageModel.fold(failed, OverviewMetricsResult.Ok(HostUsageFixtures.host, ORIGIN_A), 30, current = ORIGIN_A))
    }

    @Test fun onlyAnswersAboutTheShownServerCount() {
        // r2 (verifier P3): the shown server is A; an answer about B (the client's adopted server
        // moved first) changes nothing, whatever it is.
        val a = fresh(HostUsageFixtures.host, ORIGIN_A)
        val aboutB = listOf<OverviewMetricsResult<HostMetrics>>(
            OverviewMetricsResult.Ok(HostUsageFixtures.hostWarming, ORIGIN_B),
            OverviewMetricsResult.Unavailable(500, ORIGIN_B),
            OverviewMetricsResult.Unavailable(null, ORIGIN_B),
            OverviewMetricsResult.Blocked(302, ORIGIN_B),
            OverviewMetricsResult.Forbidden(ORIGIN_B),
            OverviewMetricsResult.SignedOut(ORIGIN_B),
        )
        for (answer in aboutB) assertEquals(answer.toString(), a, HostUsageModel.fold(a, answer, NOW + 1, current = ORIGIN_A))
        // The shown server is B and its reading is still empty: A's answer is not taken either.
        for (answer in aboutB.map { it.retag(ORIGIN_A) }) assertEquals(answer.toString(), empty, HostUsageModel.fold(empty, answer, NOW + 1, current = ORIGIN_B))
        // No shown server: nothing tagged is taken.
        assertEquals(empty, HostUsageModel.fold(empty, OverviewMetricsResult.Ok(HostUsageFixtures.host, ORIGIN_A), NOW, current = null))
        // An answer no server gave (no credential, local network blocked) always counts.
        assertEquals(MetricsReading<HostMetrics>(fault = MetricsFault.SignedOut), HostUsageModel.fold(a, OverviewMetricsResult.SignedOut(), NOW, current = ORIGIN_A))
        assertNull(HostUsageModel.fold(a, OverviewMetricsResult.LocalNetworkBlocked, NOW, current = ORIGIN_A).data)
    }

    @Test fun aReadingAboutAnotherServerNeverStandsBesideTheShownServersAnswer() {
        // Defence in depth: a reading that is somehow A's while B is shown is dropped by B's first answer.
        val a = fresh(HostUsageFixtures.host, ORIGIN_A)
        for (answer in listOf<OverviewMetricsResult<HostMetrics>>(
            OverviewMetricsResult.Unavailable(500, ORIGIN_B),
            OverviewMetricsResult.Blocked(302, ORIGIN_B),
        )) {
            val next = HostUsageModel.fold(a, answer, NOW + 1, current = ORIGIN_B)
            assertNull(answer.toString(), next.data)
            assertNull(answer.toString(), next.at)
            assertEquals(ORIGIN_B, next.origin)
        }
        assertEquals(
            MetricsReading(HostUsageFixtures.hostWarming, null, NOW + 1, ORIGIN_B),
            HostUsageModel.fold(a, OverviewMetricsResult.Ok(HostUsageFixtures.hostWarming, ORIGIN_B), NOW + 1, current = ORIGIN_B),
        )
    }

    private fun OverviewMetricsResult<HostMetrics>.retag(origin: String): OverviewMetricsResult<HostMetrics> = when (this) {
        is OverviewMetricsResult.Ok -> copy(origin = origin)
        is OverviewMetricsResult.Unavailable -> copy(origin = origin)
        is OverviewMetricsResult.Blocked -> copy(origin = origin)
        is OverviewMetricsResult.Forbidden -> copy(origin = origin)
        is OverviewMetricsResult.SignedOut -> copy(origin = origin)
        OverviewMetricsResult.LocalNetworkBlocked -> this
    }

    @Test fun signedOutOrRefusedShowsNothingOfTheServer() {
        val a = fresh(HostUsageFixtures.host, ORIGIN_A)
        assertEquals(MetricsReading<HostMetrics>(fault = MetricsFault.SignedOut, origin = ORIGIN_A), HostUsageModel.fold(a, OverviewMetricsResult.SignedOut(ORIGIN_A), NOW, current = ORIGIN_A))
        assertEquals(MetricsReading<HostMetrics>(fault = MetricsFault.Forbidden, origin = ORIGIN_A), HostUsageModel.fold(a, OverviewMetricsResult.Forbidden(ORIGIN_A), NOW, current = ORIGIN_A))
    }

    // --- meters -------------------------------------------------------------------------------

    @Test fun theMockupReadingPrintsAsTheWebPrintsIt() {
        val t = tile()
        assertEquals(listOf("CPU", "Memory", "Disk"), t.meters.map { it.label })
        assertEquals(listOf("24%", "12.6 / 32.0 GB", "38%"), t.meters.map { it.value })
        assertEquals(listOf(null, null, null), t.meters.map { it.unavailable })
        assertEquals(24.0, t.meters[0].percent!!, 1e-9)
        assertEquals(39.375, t.meters[1].percent!!, 1e-9)
        assertEquals("Disk: state volume, 310 GB free", t.diskNote)
        assertEquals("OS-visible (host)", t.scope)
        assertNull(t.freshness)
        assertNull(t.notice)
        assertFalse(t.busy)
        assertEquals(listOf(Tone.Normal, Tone.Normal, Tone.Normal), t.meters.map { it.tone })
        assertEquals("24%", t.meters[0].spoken)
    }

    @Test fun metersShiftToWarningAt75AndDangerAt90() {
        assertEquals(Tone.Normal, HostUsagePresentation.meterTone(74.99))
        assertEquals(Tone.Warning, HostUsagePresentation.meterTone(75.0))
        assertEquals(Tone.Warning, HostUsagePresentation.meterTone(89.99))
        assertEquals(Tone.Danger, HostUsagePresentation.meterTone(90.0))
        assertEquals(Tone.Danger, HostUsagePresentation.meterTone(100.0))
        val m = HostUsagePresentation.Meter("CPU", 140.0, "140%", null)
        assertEquals(100.0, m.clamped!!, 0.0)
        assertEquals(Tone.Danger, m.tone)
        assertEquals(0.0, HostUsagePresentation.Meter("CPU", -3.0, "", null).clamped!!, 0.0)
        assertEquals(Tone.None, HostUsagePresentation.Meter("CPU", null, "", "Measuring…").tone)
    }

    @Test fun anUnavailableReadingSaysWhyAndIsNeverZero() {
        val t = tile(host = fresh(HostUsageFixtures.hostWarming))
        assertEquals(listOf("Measuring…", null, "Timed out"), t.meters.map { it.unavailable })
        assertEquals(listOf(null, 62.5, null), t.meters.map { it.percent })
        assertEquals("Measuring…", t.meters[0].spoken)
        assertNull("no disk note without a disk reading", t.diskNote)
        for ((reason, words) in mapOf(
            "warming_up" to "Measuring…", "no_interval" to "Measuring…", "not_sampled" to "Measuring…",
            "unsupported" to "Not available here", "read_failed" to "Could not read", "no_state_dir" to "No state volume",
            "timeout" to "Timed out", "something_new" to "Unavailable", "" to "Unavailable",
        )) {
            val reading = HostUsageFixtures.host.copy(cpu = HostReading.Unavailable(reason))
            assertEquals(reason, words, tile(host = fresh(reading)).meters[0].unavailable)
        }
    }

    @Test fun beforeTheFirstReadingEverythingIsMeasuringAndBusy() {
        val t = tile(host = empty, usage = noUsage)
        assertTrue(t.busy)
        assertEquals(listOf("Measuring…", "Measuring…", "Measuring…"), t.meters.map { it.unavailable })
        assertEquals("This node", t.scope)
        assertEquals("Tokens today", t.tokensLabel)
        assertEquals("…", t.tokensValue)
        assertEquals("UTC day", t.usageNote)
        assertNull(t.notice)
        assertNull(t.freshness)
    }

    @Test fun aFailureBeforeAnyReadingIsUnavailableWithTheReason() {
        val t = tile(host = MetricsReading(fault = MetricsFault.Unavailable(500)), usage = MetricsReading(fault = MetricsFault.Unavailable(null)))
        assertFalse(t.busy)
        assertEquals(listOf("Unavailable", "Unavailable", "Unavailable"), t.meters.map { it.unavailable })
        assertEquals(HostUsagePresentation.Notice(false, "Host readings unavailable: HTTP 500"), t.notice)
        assertEquals("Unavailable", t.tokensValue)
        assertEquals("Host readings unavailable: Signed out", tile(host = MetricsReading(fault = MetricsFault.SignedOut)).notice!!.text)
        assertEquals("Host readings unavailable: HTTP 403", tile(host = MetricsReading(fault = MetricsFault.Forbidden)).notice!!.text)
        assertEquals("Host readings unavailable: Unavailable", tile(host = MetricsReading(fault = MetricsFault.Unavailable(null))).notice!!.text)
        assertEquals("Host readings unavailable: Unreadable answer", tile(host = MetricsReading(fault = MetricsFault.Unavailable(200))).notice!!.text)
        // A failure after a reading keeps the reading (marked stale) and prints no error line.
        val kept = tile(host = fresh(HostUsageFixtures.host).copy(fault = MetricsFault.Unavailable(500)))
        assertNull(kept.notice)
        assertEquals("24%", kept.meters[0].value)
        assertEquals("Stale", kept.freshness!!.label)
    }

    @Test fun aSignInGatewayIsNamedWhetherOrNotAReadingIsKept() {
        val expected = HostUsagePresentation.Notice(true, "Blocked by a sign-in page", HostUsagePresentation.BLOCKED_DETAIL)
        assertEquals(expected, tile(host = MetricsReading(fault = MetricsFault.Blocked(302))).notice)
        assertEquals(expected, tile(host = fresh(HostUsageFixtures.host).copy(fault = MetricsFault.Blocked(200))).notice)
        assertEquals(expected, tile(usage = MetricsReading(fault = MetricsFault.Blocked(401))).notice)
        assertTrue(HostUsagePresentation.BLOCKED_DETAIL.contains("/api/overview/"))
    }

    // --- staleness ------------------------------------------------------------------------------

    @Test fun aReadingIsStaleWhenTheServerSaysSoOnFailureOrAfterTwentySeconds() {
        assertFalse(HostUsagePresentation.hostStale(fresh(HostUsageFixtures.host, at = NOW - 20_000), NOW))
        assertTrue(HostUsagePresentation.hostStale(fresh(HostUsageFixtures.host, at = NOW - 20_001), NOW))
        assertTrue(HostUsagePresentation.hostStale(fresh(HostUsageFixtures.host.copy(stale = true)), NOW))
        assertTrue(HostUsagePresentation.hostStale(fresh(HostUsageFixtures.host).copy(fault = MetricsFault.Unavailable(null)), NOW))
        assertFalse("nothing to be stale", HostUsagePresentation.hostStale(MetricsReading(fault = MetricsFault.Unavailable(null)), NOW))
    }

    @Test fun staleIsANeutralPillWithTheAgeAndOfflineSaysSo() {
        assertEquals(HostUsagePresentation.Freshness(false, "Stale"), tile(host = fresh(HostUsageFixtures.host.copy(stale = true))).freshness)
        assertEquals(HostUsagePresentation.Freshness(false, "Stale · updated 3 min ago"), tile(host = fresh(HostUsageFixtures.host, at = NOW - 180_000)).freshness)
        assertEquals(HostUsagePresentation.Freshness(true, "Offline · updated 3 min ago"), tile(host = fresh(HostUsageFixtures.host, at = NOW - 180_000), connected = false).freshness)
        // A fresh reading is not marked just because the socket is down: the route answered.
        assertNull(tile(connected = false).freshness)
    }

    // --- tokens today ---------------------------------------------------------------------------

    @Test fun tokensTodayFollowsTheServersLabelAndCoverage() {
        val exact = tile()
        assertEquals("Tokens today", exact.tokensLabel)
        assertEquals("1.28M", exact.tokensValue)
        assertEquals("UTC day · just now", exact.usageNote)

        val partial = tile(usage = fresh(HostUsageFixtures.usagePartial, at = NOW - 120_000))
        assertEquals("Reported tokens today", partial.tokensLabel)
        assertEquals("450", partial.tokensValue)
        assertEquals("UTC day · partial: opencode · not reported: reasonix · 2m ago", partial.usageNote)

        // "an inactive not-reported provider is listed but does not make today partial"
        val listed = OverviewUsage(5.0, "Tokens today", false, listOf(UsageCoverage("claude", "exact"), UsageCoverage("reasonix", "not_reported")))
        assertEquals("UTC day · not reported: reasonix · just now", tile(usage = fresh(listed)).usageNote)
        // "excluded unknown attribution": nothing measurable is "Not reported", never 0.
        assertEquals("Not reported", tile(usage = fresh(OverviewUsage(null, "Reported tokens today", true, emptyList()))).tokensValue)
        // Several of each, in the server's order.
        val many = OverviewUsage(9.0, "Reported tokens today", true, listOf(UsageCoverage("opencode", "partial"), UsageCoverage("codex", "partial"), UsageCoverage("reasonix", "not_reported"), UsageCoverage("mystery", "not_reported"), UsageCoverage("x", "weird")))
        assertEquals("UTC day · partial: opencode, codex · not reported: reasonix, mystery", HostUsagePresentation.usageNote(many, null, NOW))
        // A failure after a reading keeps the figure.
        assertEquals("1.28M", tile(usage = fresh(HostUsageFixtures.usageExact).copy(fault = MetricsFault.Unavailable(502))).tokensValue)
    }

    // --- overview-format.ts ---------------------------------------------------------------------

    @Test fun formatTokensMatchesTheWeb() {
        val cases = mapOf(
            0.0 to "0", 912.0 to "912", 999.0 to "999", 999.4 to "999", 999.5 to "1000",
            1_000.0 to "1.0K", 48_200.0 to "48.2K", 999_999.0 to "1000.0K",
            1_000_000.0 to "1.00M", 1_280_000.0 to "1.28M", 3.1e9 to "3.10B", 1.5e12 to "1500.00B",
        )
        for ((value, text) in cases) assertEquals("$value", text, HostUsagePresentation.tokens(value))
        assertEquals("—", HostUsagePresentation.tokens(Double.NaN))
        assertEquals("—", HostUsagePresentation.tokens(Double.POSITIVE_INFINITY))
    }

    @Test fun formatGigabytesMatchesTheWeb() {
        val gib = HostUsageFixtures.GIB
        assertEquals("12.6", HostUsagePresentation.gigabytes(12.6 * gib))
        assertEquals("32.0", HostUsagePresentation.gigabytes(32 * gib))
        assertEquals("0.0", HostUsagePresentation.gigabytes(0.0))
        assertEquals("99.9", HostUsagePresentation.gigabytes(99.94 * gib))
        assertEquals("100", HostUsagePresentation.gigabytes(100 * gib))
        assertEquals("1954", HostUsagePresentation.gigabytes(1954.4 * gib))
    }

    @Test fun toFixedRoundsTheExactBinaryValueLikeJavaScript() {
        // (1.005).toFixed(2) === "1.00" and (0.125).toFixed(2) === "0.13" in JavaScript.
        assertEquals("1.00", HostUsagePresentation.toFixed(1.005, 2))
        assertEquals("0.13", HostUsagePresentation.toFixed(0.125, 2))
        // Checked against V8: (2.45).toFixed(1) === "2.5", (8.345).toFixed(2) === "8.35" (both stored just above).
        assertEquals("2.5", HostUsagePresentation.toFixed(2.45, 1))
        assertEquals("8.35", HostUsagePresentation.toFixed(8.345, 2))
        // Math.round: half toward +infinity.
        assertEquals(3L, HostUsagePresentation.jsRound(2.5))
        assertEquals(-2L, HostUsagePresentation.jsRound(-2.5))
    }
}
