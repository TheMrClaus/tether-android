package com.tether.app.ui.overview

import com.tether.app.client.HostCpu
import com.tether.app.client.HostDisk
import com.tether.app.client.HostMemory
import com.tether.app.client.HostMetrics
import com.tether.app.client.HostReading
import com.tether.app.client.OverviewUsage
import com.tether.app.client.UsageCoverage

/**
 * T15.3: the tile's readings. [host] carries the approved mockup's numbers
 * (design/mockups/tether-overview: CPU 24 %, memory 12.6 of 32 GB, disk 38 %); [usagePartial] is
 * tests/usage-daily.test.mjs "partial providers" as lib/usage-daily.mjs returns it; [usageExact]
 * the mockup's "Tokens today 1.28M".
 */
object HostUsageFixtures {
    const val GIB = 1024.0 * 1024.0 * 1024.0
    const val ORIGIN_A = "https://a.test"
    const val ORIGIN_B = "https://b.test"
    const val NOW = OverviewFixtures.NOW

    val host = HostMetrics(
        scopeLabel = "OS-visible (host)",
        cpu = HostReading.Value(HostCpu(24.0, 8)),
        memory = HostReading.Value(HostMemory(12.6 * GIB, 32 * GIB)),
        disk = HostReading.Value(HostDisk(190 * GIB, 500 * GIB, 310 * GIB)),
        stale = false,
    )

    /** lib/host-metrics.mjs `snapshot()` before its first CPU delta: warming up, disk not yet read. */
    val hostWarming = HostMetrics(
        scopeLabel = "OS-visible (container)",
        cpu = HostReading.Unavailable("warming_up"),
        memory = HostReading.Value(HostMemory(10.0 * GIB, 16.0 * GIB)),
        disk = HostReading.Unavailable("timeout"),
        stale = false,
    )

    val usageExact = OverviewUsage(
        tokensToday = 1_280_000.0,
        label = "Tokens today",
        partial = false,
        coverage = listOf(UsageCoverage("claude", "exact"), UsageCoverage("codex", "exact")),
    )

    val usagePartial = OverviewUsage(
        tokensToday = 450.0,
        label = "Reported tokens today",
        partial = true,
        coverage = listOf(UsageCoverage("opencode", "partial"), UsageCoverage("pi", "exact"), UsageCoverage("reasonix", "not_reported")),
    )

    fun <T> fresh(value: T, origin: String = ORIGIN_A, at: Long = NOW) = MetricsReading(value, null, at, origin)
}
