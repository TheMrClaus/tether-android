package com.tether.app.ui.overview

import com.tether.app.client.HostMetrics
import com.tether.app.client.HostReading
import com.tether.app.client.LabelText
import com.tether.app.client.OverviewMetricsResult
import com.tether.app.client.OverviewUsage
import com.tether.app.ui.components.FreshnessCopy
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

/** Why the last fetch of one reading gave no value. */
sealed interface MetricsFault {
    data object SignedOut : MetricsFault
    data object Forbidden : MetricsFault
    data object LocalNetworkBlocked : MetricsFault

    /** T6.8: a sign-in gateway answered instead of Tether. */
    data class Blocked(val code: Int) : MetricsFault

    /** [code]: the HTTP status, or null when the server could not be reached. */
    data class Unavailable(val code: Int?) : MetricsFault
}

/**
 * overview-host.tsx `Reading<T>`: the last value ([data], fetched at [at]), and why the latest
 * fetch failed ([fault]; the value stays, marked stale, as on the web). [origin] is the server the
 * reading is about: see [HostUsageModel.fold].
 */
data class MetricsReading<T>(
    val data: T? = null,
    val fault: MetricsFault? = null,
    val at: Long? = null,
    val origin: String? = null,
)

/**
 * T15.3: components/overview/overview-host.tsx's state and cadence, pure (callers pass `now`).
 */
object HostUsageModel {
    /** overview-host.tsx HOST_POLL_MS: the server samples only while a viewer asked within ~20 s. */
    const val HOST_POLL_MS = 5_000L

    /** overview-host.tsx USAGE_POLL_MS: the usage store's 15 s cache. */
    const val USAGE_POLL_MS = 15_000L

    /** overview-host.tsx STALE_AFTER_MS: a reading older than this is stale whatever the server said. */
    const val STALE_AFTER_MS = 20_000L

    /**
     * overview-host.tsx `useJsonPoll`'s two `setReading`s: a value replaces the reading; a failure
     * keeps the last value and records why. Native additions:
     * - an answer about ANOTHER server (its [OverviewMetricsResult.origin]) starts from nothing, so
     *   one server's numbers never stand beside another's answer;
     * - signed out or refused (401/403): the value is dropped, not kept as stale (a credential the
     *   server no longer accepts shows none of its numbers).
     */
    fun <T> fold(reading: MetricsReading<T>, result: OverviewMetricsResult<T>, at: Long): MetricsReading<T> {
        val kept = if (result.origin != reading.origin) MetricsReading() else reading
        return when (result) {
            is OverviewMetricsResult.Ok -> MetricsReading(result.value, null, at, result.origin)
            is OverviewMetricsResult.SignedOut -> MetricsReading(fault = MetricsFault.SignedOut, origin = result.origin)
            is OverviewMetricsResult.Forbidden -> MetricsReading(fault = MetricsFault.Forbidden, origin = result.origin)
            OverviewMetricsResult.LocalNetworkBlocked -> kept.copy(fault = MetricsFault.LocalNetworkBlocked, origin = null)
            is OverviewMetricsResult.Blocked -> kept.copy(fault = MetricsFault.Blocked(result.code), origin = result.origin)
            is OverviewMetricsResult.Unavailable -> kept.copy(fault = MetricsFault.Unavailable(result.code), origin = result.origin)
        }
    }
}

/**
 * T15.3: what the "Host & usage" tile shows, derived from the two readings exactly as
 * overview-host.tsx `HostUsageTile` derives it (labels, formatting, thresholds), plus the native
 * states the web has no words for (blocked by a sign-in page, offline). All server text goes
 * through the label rule here; the reasons are looked up, never drawn.
 */
object HostUsagePresentation {
    const val TITLE = "Host & usage"
    const val MEASURING = "Measuring…"
    const val UNAVAILABLE = "Unavailable"
    const val THIS_NODE = "This node"
    const val TOKENS_TODAY = "Tokens today"
    const val BLOCKED = "Blocked by a sign-in page"
    const val BLOCKED_DETAIL = "A sign-in gateway (SSO or a proxy) answered instead of Tether. Exempt /api/overview/ for paired devices."

    /** overview-host.tsx UNAVAILABLE_TEXT. */
    val UNAVAILABLE_TEXT: Map<String, String> = mapOf(
        "warming_up" to MEASURING,
        "no_interval" to MEASURING,
        "not_sampled" to MEASURING,
        "unsupported" to "Not available here",
        "read_failed" to "Could not read",
        "no_state_dir" to "No state volume",
        "timeout" to "Timed out",
    )

    enum class Tone { None, Normal, Warning, Danger }

    /** overview-host.tsx `meterTone` (DESIGN.md: warning at 75 %, danger at 90 %; the figure always shows). */
    fun meterTone(percent: Double): Tone = when {
        percent >= 90 -> Tone.Danger
        percent >= 75 -> Tone.Warning
        else -> Tone.Normal
    }

    /** One `Meter`: [percent] null = no bar (unavailable or loading). */
    data class Meter(val label: String, val percent: Double?, val value: String, val unavailable: String?) {
        /** `Math.min(100, Math.max(0, percent))`. */
        val clamped: Double? get() = percent?.coerceIn(0.0, 100.0)
        val tone: Tone get() = clamped?.let(::meterTone) ?: Tone.None

        /** `aria-valuetext`. */
        val spoken: String get() = unavailable ?: value
    }

    /** The neutral freshness pill (T13.2's idiom): offline when the link is down, else stale. */
    data class Freshness(val offline: Boolean, val label: String)

    /** The line above the meters: the web's error line, or the native blocked notice. */
    data class Notice(val blocked: Boolean, val text: String, val detail: String? = null)

    data class Tile(
        val scope: String,
        val freshness: Freshness?,
        val notice: Notice?,
        val busy: Boolean,
        val meters: List<Meter>,
        val diskNote: String?,
        val tokensLabel: String,
        val tokensValue: String,
        val usageNote: String,
    )

    /** overview-host.tsx `hostStale`. */
    fun hostStale(host: MetricsReading<HostMetrics>, now: Long): Boolean {
        val data = host.data ?: return false
        return data.stale || host.fault != null || (host.at != null && now - host.at > HostUsageModel.STALE_AFTER_MS)
    }

    fun tile(host: MetricsReading<HostMetrics>, usage: MetricsReading<OverviewUsage>, now: Long, connected: Boolean): Tile {
        val data = host.data
        val stale = hostStale(host, now)
        val cpu = (data?.cpu as? HostReading.Value)?.value
        val memory = (data?.memory as? HostReading.Value)?.value
        val disk = (data?.disk as? HostReading.Value)?.value
        val loadingText = if (host.fault != null) UNAVAILABLE else MEASURING
        fun reason(reading: HostReading<*>?): String = when {
            data == null -> loadingText
            reading is HostReading.Unavailable -> UNAVAILABLE_TEXT[reading.reason] ?: UNAVAILABLE
            else -> UNAVAILABLE
        }

        val meters = listOf(
            Meter("CPU", cpu?.percent, cpu?.let { "${jsRound(it.percent)}%" }.orEmpty(), if (cpu != null) null else reason(data?.cpu)),
            Meter(
                "Memory",
                memory?.let { it.usedBytes / it.totalBytes * 100 },
                memory?.let { "${gigabytes(it.usedBytes)} / ${gigabytes(it.totalBytes)} GB" }.orEmpty(),
                if (memory != null) null else reason(data?.memory),
            ),
            Meter(
                "Disk",
                disk?.let { it.usedBytes / it.totalBytes * 100 },
                disk?.let { "${jsRound(it.usedBytes / it.totalBytes * 100)}%" }.orEmpty(),
                if (disk != null) null else reason(data?.disk),
            ),
        )

        val blocked = host.fault is MetricsFault.Blocked || usage.fault is MetricsFault.Blocked
        val notice = when {
            blocked -> Notice(blocked = true, text = BLOCKED, detail = BLOCKED_DETAIL)
            host.fault != null && data == null -> Notice(blocked = false, text = "Host readings unavailable: ${faultText(host.fault)}")
            else -> null
        }

        val freshness = if (!stale) null else {
            val age = FreshnessCopy.age(host.at, now)?.takeIf { it != "just now" }
            val words = if (connected) "Stale" else "Offline"
            Freshness(offline = !connected, label = age?.let { "$words · updated $it" } ?: words)
        }

        val summary = usage.data
        val today = summary?.tokensToday
        val tokensValue = when {
            usage.fault != null && summary == null -> UNAVAILABLE
            summary == null -> "…"
            today == null -> "Not reported"
            else -> tokens(today)
        }
        return Tile(
            scope = data?.scopeLabel?.let { LabelText.title(it, LabelText.MAX_LABEL) }?.takeIf { it.isNotEmpty() } ?: THIS_NODE,
            freshness = freshness,
            notice = notice,
            busy = data == null && host.fault == null,
            meters = meters,
            diskNote = disk?.let { "Disk: state volume, ${gigabytes(it.availableBytes)} GB free" },
            tokensLabel = summary?.label?.let { LabelText.title(it, LabelText.MAX_LABEL) }?.takeIf { it.isNotEmpty() } ?: TOKENS_TODAY,
            tokensValue = tokensValue,
            usageNote = usageNote(summary, usage.at, now),
        )
    }

    /** overview-host.tsx's usage footnote: "UTC day · partial: … · not reported: … · just now". */
    fun usageNote(summary: OverviewUsage?, at: Long?, now: Long): String = buildString {
        append("UTC day")
        val coverage = summary?.coverage.orEmpty()
        fun names(status: String) = coverage.filter { it.status == status }.map { LabelText.title(it.provider, LabelText.MAX_LABEL) }
        names("partial").takeIf { it.isNotEmpty() }?.let { append(" · partial: ").append(it.joinToString(", ")) }
        names("not_reported").takeIf { it.isNotEmpty() }?.let { append(" · not reported: ").append(it.joinToString(", ")) }
        if (at != null && at != 0L) append(" · ").append(OverviewFormat.ago(at, now))
    }

    /** What a failed fetch says (overview-host.tsx: "Signed out" for a 401, else `HTTP <status>`). */
    fun faultText(fault: MetricsFault): String = when (fault) {
        MetricsFault.SignedOut -> "Signed out"
        MetricsFault.Forbidden -> "HTTP 403"
        MetricsFault.LocalNetworkBlocked -> "Local network access is blocked"
        is MetricsFault.Blocked -> BLOCKED
        is MetricsFault.Unavailable -> when (fault.code) {
            null -> UNAVAILABLE
            in 200..299 -> "Unreadable answer"
            else -> "HTTP ${fault.code}"
        }
    }

    private const val GB = 1024.0 * 1024.0 * 1024.0

    /** overview-format.ts `formatGigabytes`: one decimal under 100 GB ("12.6"), whole above. */
    fun gigabytes(bytes: Double): String {
        val value = bytes / GB
        return if (value >= 100) toFixed(value, 0) else toFixed(value, 1)
    }

    /** overview-format.ts `formatTokens`: "912", "48.2K", "1.28M", "3.10B". */
    fun tokens(value: Double): String {
        if (!value.isFinite()) return "—"
        val abs = kotlin.math.abs(value)
        return when {
            abs >= 1e9 -> "${toFixed(value / 1e9, 2)}B"
            abs >= 1e6 -> "${toFixed(value / 1e6, 2)}M"
            abs >= 1e3 -> "${toFixed(value / 1e3, 1)}K"
            else -> jsRound(value).toString()
        }
    }

    /**
     * JavaScript `Number.prototype.toFixed`: the exact binary value, rounded half up, and never
     * locale digits (`String.format` rounds the shortest decimal instead: 1.005 → "1.01" where JS
     * gives "1.00").
     */
    internal fun toFixed(value: Double, digits: Int): String =
        BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    /** JavaScript `Math.round`: half toward +∞. */
    internal fun jsRound(value: Double): Long = kotlin.math.floor(value + 0.5).roundToLong()
}
