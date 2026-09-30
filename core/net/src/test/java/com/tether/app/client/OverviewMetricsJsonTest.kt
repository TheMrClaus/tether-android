package com.tether.app.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15.3: the lenient, bounded read of `GET /api/overview/host` and `/api/overview/usage`. The
 * shapes are the ones the web's server tests pin (tests/host-metrics.test.mjs,
 * tests/usage-daily.test.mjs, tests/integration/overview-feed-wire.test.mjs); a malformed or
 * hostile body degrades to "unavailable", never to a false number, and never keeps an unbounded
 * string or array.
 */
class OverviewMetricsJsonTest {
    private fun hostOrNull(text: String) = OverviewMetricsJson.host(OverviewMetricsJson.parseObject(text)!!)
    private fun host(text: String) = hostOrNull(text)!!

    /** [json] with one reading object added, so a test about another field reads as the server's answer. */
    private fun withReading(json: String) = "{\"memory\":{\"unavailable\":\"not_sampled\"}" + (if (json.trim() == "{}") "}" else "," + json.trim().removePrefix("{"))
    private fun usage(text: String) = OverviewMetricsJson.usage(OverviewMetricsJson.parseObject(text)!!)

    // --- ported: tests/host-metrics.test.mjs --------------------------------------------------

    @Test fun theMockupReadingParses() {
        assertEquals(OverviewMetricsFixtures.HOST, host(OverviewMetricsFixtures.HOST_JSON))
    }

    @Test fun theFirstSampleIsUnavailableNeverZero() {
        // "the first sample is unavailable, never 0%; the second is real"
        val first = host("""{"sampledAt":1,"scope":"host","scopeLabel":"OS-visible (host)","cpu":{"unavailable":"warming_up"},"memory":{"usedBytes":10240000000,"totalBytes":16384000000,"source":"meminfo","sampledAt":1},"disk":{"label":"State volume","unavailable":"not_sampled"},"stale":false}""")
        assertEquals(HostReading.Unavailable("warming_up"), first.cpu)
        assertEquals(HostReading.Value(HostMemory(10240000000.0, 16384000000.0)), first.memory)
        assertEquals(HostReading.Unavailable("not_sampled"), first.disk)
        val second = host("""{"cpu":{"percent":75,"cores":2,"sampledAt":5001}}""")
        assertEquals(HostReading.Value(HostCpu(75.0, 2)), second.cpu)
    }

    @Test fun diskFailureAndTimeoutAreUnavailableWithTheirReasons() {
        // "disk: the state volume via statfs; failure and timeout are unavailable"
        assertEquals(HostReading.Unavailable("read_failed"), host("""{"disk":{"label":"State volume","unavailable":"read_failed"}}""").disk)
        assertEquals(HostReading.Unavailable("timeout"), host("""{"disk":{"label":"State volume","unavailable":"timeout"}}""").disk)
        assertEquals(
            HostReading.Value(HostDisk(4096.0 * 750, 4096.0 * 1000, 4096.0 * 200)),
            host("""{"disk":{"label":"State volume","usedBytes":3072000,"totalBytes":4096000,"availableBytes":819200,"sampledAt":1}}""").disk,
        )
        assertEquals(HostReading.Unavailable("unsupported"), host("""{"memory":{"unavailable":"unsupported"}}""").memory)
    }

    @Test fun aNeverSampledSnapshotIsStaleAndAllUnavailable() {
        // "lifecycle: ... staleness is reported": snapshot() before any touch.
        val idle = host("""{"sampledAt":null,"scope":"container","scopeLabel":"OS-visible (container)","cpu":{"unavailable":"warming_up"},"memory":{"unavailable":"not_sampled"},"disk":{"label":"State volume","unavailable":"not_sampled"},"stale":true}""")
        assertEquals(true, idle.stale)
        assertEquals("OS-visible (container)", idle.scopeLabel)
        assertTrue(listOf(idle.cpu, idle.memory, idle.disk).all { it is HostReading.Unavailable })
    }

    // --- ported: tests/usage-daily.test.mjs ---------------------------------------------------

    @Test fun partialProvidersAreListedWithTheirStatus() {
        assertEquals(OverviewMetricsFixtures.USAGE_PARTIAL, usage(OverviewMetricsFixtures.USAGE_PARTIAL_JSON))
    }

    @Test fun anInactiveNotReportedProviderIsListedButTodayIsExact() {
        val out = usage("""{"tokensToday":{"value":5,"label":"Tokens today","partial":false},"coverage":[{"provider":"claude","status":"exact"},{"provider":"reasonix","status":"not_reported","reason":"Reasonix does not report token usage."}]}""")!!
        assertEquals(5.0, out.tokensToday)
        assertEquals("Tokens today", out.label)
        assertEquals(false, out.partial)
        assertEquals(listOf(UsageCoverage("claude", "exact"), UsageCoverage("reasonix", "not_reported")), out.coverage)
    }

    @Test fun nothingAttributableIsNotReportedNotZero() {
        // "excluded unknown attribution: a rollup with no dated data never contributes"
        val out = usage("""{"tokensToday":{"value":null,"label":"Reported tokens today","partial":true},"coverage":[{"provider":"mystery","status":"not_reported"}]}""")!!
        assertNull(out.tokensToday)
        assertEquals(true, out.partial)
        // An explicit null (the server's own "nothing measured") is a reading; an absent one is not (r2).
        assertEquals("Reported tokens today", usage("""{"tokensToday":{"value":null,"label":"Reported tokens today","partial":true}}""")!!.label)
    }

    // --- lenient -------------------------------------------------------------------------------

    @Test fun readingsThatBreakTheServersInvariantsAreUnavailable() {
        val bad = listOf(
            """{"percent":"24"}""", // a numeric string is not a number
            """{"percent":-1}""",
            """{"percent":100.5}""",
            """{"percent":1e300}""",
            """{"percent":null}""",
            """{}""",
        )
        for (cpu in bad) assertEquals(cpu, HostReading.Unavailable(""), host("""{"cpu":$cpu}""").cpu)
        val badBytes = listOf(
            """{"usedBytes":5,"totalBytes":0}""",
            """{"usedBytes":-5,"totalBytes":10}""",
            """{"usedBytes":11,"totalBytes":10}""",
            """{"usedBytes":5,"totalBytes":1e300}""",
            """{"usedBytes":true,"totalBytes":10}""",
            """{"totalBytes":10}""",
        )
        for (memory in badBytes) assertEquals(memory, HostReading.Unavailable(""), host("""{"memory":$memory}""").memory)
        assertEquals(HostReading.Unavailable(""), host("""{"disk":{"usedBytes":5,"totalBytes":10,"availableBytes":11}}""").disk)
        assertEquals(HostReading.Unavailable(""), host("""{"disk":{"usedBytes":5,"totalBytes":10}}""").disk)
        // Missing, or not an object at all (beside a reading that is).
        val bare = host(withReading("{}"))
        assertEquals(HostReading.Unavailable(""), bare.cpu)
        assertEquals(HostReading.Unavailable(""), host(withReading("""{"cpu":[1,2]}""")).cpu)
        assertEquals(HostReading.Unavailable(""), host(withReading("""{"cpu":"75%"}""")).cpu)
        assertNull(bare.scopeLabel)
        assertNull(host(withReading("""{"scopeLabel":42}""")).scopeLabel)
        // An `unavailable` key wins, whatever else the object holds (overview-host.tsx `"unavailable" in value`).
        assertEquals(HostReading.Unavailable(""), host("""{"cpu":{"unavailable":7,"percent":50}}""").cpu)
    }

    @Test fun staleIsReadAsTheWebReadsIt() {
        assertEquals(false, host(withReading("{}")).stale)
        assertEquals(false, host(withReading("""{"stale":false}""")).stale)
        assertEquals(false, host(withReading("""{"stale":null}""")).stale)
        assertEquals(false, host(withReading("""{"stale":0}""")).stale)
        assertEquals(false, host(withReading("""{"stale":""}""")).stale)
        assertEquals(true, host(withReading("""{"stale":true}""")).stale)
        assertEquals(true, host(withReading("""{"stale":1}""")).stale)
        assertEquals(true, host(withReading("""{"stale":"yes"}""")).stale)
        assertEquals(true, host(withReading("""{"stale":{}}""")).stale)
    }

    @Test fun anUnusableTokenCountMakesTheWholeUsageUnavailable() {
        for (value in listOf("\"450\"", "-1", "1e300", "true", "{}", "[]")) {
            assertNull(value, usage("""{"tokensToday":{"value":$value,"label":"Tokens today","partial":false}}"""))
        }
        assertNull(usage("""{"tokensToday":"450"}"""))
        assertNull(usage("""{"coverage":[]}"""))
        // A missing label falls back to the web's default; a non-boolean `partial` is not partial.
        val out = usage("""{"tokensToday":{"value":1,"partial":"true"}}""")!!
        assertEquals("Tokens today", out.label)
        assertEquals(false, out.partial)
    }

    /**
     * r2 (security Low): a 200 JSON answer that is not Tether's (a gateway's `{"error":"login
     * required"}`) is no reading at all, so it cannot replace a good one with blanks.
     */
    @Test fun aForeignShapeIsNotAReading() {
        for (body in listOf("{}", """{"error":"login required"}""", """{"cpu":[1,2],"memory":"x","disk":null}""", """{"stale":false,"scopeLabel":"OS-visible (host)"}""")) {
            assertNull(body, hostOrNull(body))
        }
        // One reading object is enough (the others may be missing or unusable, as the server may send).
        assertEquals(HostReading.Unavailable("warming_up"), host("""{"cpu":{"unavailable":"warming_up"}}""").cpu)
        for (body in listOf("""{"error":"login required"}""", """{"tokensToday":{}}""", """{"tokensToday":{"label":"Tokens today","partial":false}}""", """{"tokensToday":null}""")) {
            assertNull(body, usage(body))
        }
    }

    // --- bounds ----------------------------------------------------------------------------------

    @Test fun everyStringAndArrayIsBounded() {
        val long = "x".repeat(10_000)
        val h = host("""{"scopeLabel":"$long","cpu":{"unavailable":"$long"}}""")
        assertEquals(OverviewMetricsJson.MAX_TEXT, h.scopeLabel!!.length)
        assertEquals(OverviewMetricsJson.MAX_REASON, (h.cpu as HostReading.Unavailable).reason.length)

        val entries = (1..1_000).joinToString(",") { """{"provider":"p$it$long","status":"partial$long"}""" }
        val u = usage("""{"tokensToday":{"value":1,"label":"$long","partial":true},"coverage":[$entries]}""")!!
        assertEquals(OverviewMetricsJson.MAX_TEXT, u.label.length)
        assertEquals(OverviewMetricsJson.MAX_COVERAGE, u.coverage.size)
        assertTrue(u.coverage.all { it.provider.length <= OverviewMetricsJson.MAX_TEXT && it.status.length <= OverviewMetricsJson.MAX_REASON })
    }

    @Test fun malformedCoverageEntriesAreSkippedNotFatal() {
        val u = usage("""{"tokensToday":{"value":1},"coverage":[1,"x",null,{"provider":7,"status":"exact"},{"provider":"pi"},{"provider":"pi","status":"exact"}]}""")!!
        assertEquals(listOf(UsageCoverage("pi", "exact")), u.coverage)
        assertEquals(emptyList<UsageCoverage>(), usage("""{"tokensToday":{"value":1},"coverage":"all"}""")!!.coverage)
    }

    @Test fun aCutNeverSplitsASurrogatePair() {
        val emoji = "😀"
        val text = "a".repeat(OverviewMetricsJson.MAX_TEXT - 1) + emoji
        val label = host(withReading("""{"scopeLabel":"$text"}""")).scopeLabel!!
        assertEquals(OverviewMetricsJson.MAX_TEXT - 1, label.length)
        assertTrue(!Character.isHighSurrogate(label.last()))
    }

    @Test fun hostileTextIsKeptRawForTheScreensRulesButNeverParsedDeep() {
        // Bidi controls and invisibles are kept as data here (the screen draws them by the label rule).
        val hostile = "\u202Eevil\u200B\u0000\n"
        assertEquals(hostile, host(withReading("{\"scopeLabel\":\"\\u202Eevil\\u200B\\u0000\\n\"}")).scopeLabel)
        assertNull(OverviewMetricsJson.parseObject("{\"a\":" + "[".repeat(20) + "]".repeat(20) + "}"))
        assertNull(OverviewMetricsJson.parseObject("[".repeat(200_000)))
        assertNull(OverviewMetricsJson.parseObject("{"))
    }
}
