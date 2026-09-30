package com.tether.app.client

/**
 * T15.3: server answers for the two Overview routes, in the server's own shapes (lib/protocol.ts
 * `HostMetricsSnapshot`, `OverviewUsageSummary`). The host reading carries the approved mockup's
 * numbers (design/mockups/tether-overview: CPU 24%, memory 12.6 / 32 GB, disk 38%); the usage one
 * is tests/usage-daily.test.mjs "partial providers" (450 tokens, OpenCode partial, Reasonix not
 * reported) as lib/usage-daily.mjs `summarizeDaily` returns it.
 */
object OverviewMetricsFixtures {
    const val GIB = 1024.0 * 1024.0 * 1024.0

    val HOST_JSON = """
        {"sampledAt":1790000000000,"scope":"host","scopeLabel":"OS-visible (host)",
         "cpu":{"percent":24,"cores":8,"sampledAt":1790000000000},
         "memory":{"usedBytes":13529146982,"totalBytes":34359738368,"source":"meminfo","sampledAt":1790000000000},
         "disk":{"label":"State volume","usedBytes":204010946560,"totalBytes":536870912000,"availableBytes":332859965440,"sampledAt":1790000000000},
         "stale":false}
    """.trimIndent()

    val HOST = HostMetrics(
        scopeLabel = "OS-visible (host)",
        cpu = HostReading.Value(HostCpu(24.0, 8)),
        memory = HostReading.Value(HostMemory(13529146982.0, 34359738368.0)),
        disk = HostReading.Value(HostDisk(204010946560.0, 536870912000.0, 332859965440.0)),
        stale = false,
    )

    val USAGE_PARTIAL_JSON = """
        {"asOf":1790000000000,"period":{"start":1789948800000,"end":1790035200000,"boundary":"UTC"},
         "tokensToday":{"value":450,"label":"Reported tokens today","partial":true},
         "coverage":[
           {"provider":"opencode","status":"partial","reason":"OpenCode stores per-session totals only; sessions that started before today cannot be split by day."},
           {"provider":"pi","status":"exact"},
           {"provider":"reasonix","status":"not_reported","reason":"Reasonix does not report token usage."}],
         "unavailableReasons":["OpenCode stores per-session totals only; sessions that started before today cannot be split by day.","Reasonix does not report token usage."]}
    """.trimIndent()

    val USAGE_PARTIAL = OverviewUsage(
        tokensToday = 450.0,
        label = "Reported tokens today",
        partial = true,
        coverage = listOf(
            UsageCoverage("opencode", "partial"),
            UsageCoverage("pi", "exact"),
            UsageCoverage("reasonix", "not_reported"),
        ),
    )
}
