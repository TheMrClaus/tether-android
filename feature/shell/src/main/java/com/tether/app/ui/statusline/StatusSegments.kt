package com.tether.app.ui.statusline

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.model.SessionView
import kotlin.math.floor
import kotlin.math.max

/**
 * session-statusline.tsx:43-62 `StatusSegment`: a short printed [key] plus a printed [value] —
 * never a bare glyph or a colour-only state — with the unabbreviated reading in [title] (the
 * TalkBack description here).
 */
@Immutable
data class StatusSegment(
    val id: String,
    val key: String,
    /** Unabbreviated name, for the fill bar's accessible label ("Context usage"). */
    val label: String,
    val value: String,
    val title: String,
    /** 0-100 when the segment carries a fill level (context only). */
    val percent: Int? = null,
    /** Takes the leftover width and truncates instead of dropping (the task segment). */
    val elastic: Boolean = false,
    /** A tone for a segment with no fill level (the wrap-up); only reinforces [value]. */
    val tone: UsageTone? = null,
) {
    /**
     * session-statusline.tsx:162 `data-tone`: the explicit tone, else the fill level's tone, else
     * none. 5h / weekly segments carry no percent field, so they are never toned here.
     */
    val effectiveTone: UsageTone get() = tone ?: percent?.let { usageTone(percentReading(it.toDouble())) } ?: UsageTone.None
}

/**
 * session-statusline.tsx:64-138: the segments in fixed priority order —
 * wrap-up > context fullness (or its snapshot) > 5h window > weekly window > current task.
 */
fun buildStatusSegments(metrics: TelemetryMetrics?, state: SessionView?, env: ReadingEnv): List<StatusSegment> = buildList {
    wrapUpReading(state, env)?.let { wrapUp ->
        add(StatusSegment("wrap-up", "Limit", "Usage limit", wrapUp.label, wrapUp.detail, tone = UsageTone.High))
    }

    val context = contextReading(metrics, env)
    if (context != null) {
        add(StatusSegment("context", "Ctx", "Context", "${context.percent}%", context.detail, percent = context.percent))
    } else {
        // issue #164: no live meter, but the transcript's footprint is still a reading — printed
        // with the time it was taken, NOT as a live percentage.
        contextSnapshotReading(metrics, env)?.let { snapshot ->
            val tokens = Format.compactNumber(snapshot.tokens)
            add(StatusSegment("context", "Ctx", "Context", tokens, "Context snapshot · $tokens tokens · as of ${snapshot.asOf}"))
        }
    }

    for ((id, key, label, window) in listOf(
        Window("five-hour", "5h", "Five hour", metrics?.fiveHour),
        Window("weekly", "Wk", "Weekly", metrics?.weekly),
    )) {
        val reading = windowReading(window, env)
        val percent = reading.percent ?: continue
        add(StatusSegment(id, key, label, "$percent%", "$label usage $percent% · ${reading.caption}"))
    }

    taskReading(state?.todo)?.let { task ->
        val title = if (task.progress != null) "Current task: ${task.text} · ${task.progress}" else "Current task: ${task.text}"
        add(StatusSegment("task", "Now", "Current task", task.text, title, elastic = true))
    }
}

private data class Window(val id: String, val key: String, val label: String, val window: TelemetryWindow?)

/**
 * How many leading segments (by rendered RANK) the statusline shows at a container width, and
 * whether the leading fill bar shows — the container queries of globals.css 1861-1883:
 * rank 0 always; rank 1 from 8.5rem, rank 2 from 11.5rem, rank 3 from 15.5rem, rank 4 from 22rem;
 * the fill bar hides at or below 9rem. Ranks, not kinds: an absent reading promotes everything
 * behind it rather than leaving a hole.
 */
@Immutable
data class StatuslineFit(val visibleRanks: Int, val showTrack: Boolean)

val StatuslineRankThresholdsRem: List<Float> = listOf(0f, 8.5f, 11.5f, 15.5f, 22f)
const val StatuslineTrackMaxHiddenRem: Float = 9f

fun statuslineFit(containerWidthRem: Float): StatuslineFit = StatuslineFit(
    visibleRanks = StatuslineRankThresholdsRem.count { containerWidthRem >= it },
    showTrack = containerWidthRem > StatuslineTrackMaxHiddenRem,
)

/** session-dial.tsx: the header's elapsed clock ("HH:MM:SS", hours never wrap). */
@Immutable
data class DialReading(val label: String, val active: Boolean) {
    /** session-dial.tsx:20 aria-label. */
    val contentDescription: String get() = "Session elapsed time $label"
}

/**
 * session-dial.tsx:14-19. An active session reads the clock; a stopped one freezes at
 * `stoppedAt || startedAt` (an absent or zero stop time shows 00:00:00).
 */
fun dialReading(startedAt: Double, stoppedAt: Double?, active: Boolean, nowMs: Double): DialReading {
    val now = if (active) nowMs else stoppedAt?.takeIf { it != 0.0 && !it.isNaN() } ?: startedAt
    val seconds = max(0.0, floor((now - startedAt) / 1000)).let { if (it.isNaN()) 0.0 else it }.toLong()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    fun pad(v: Long) = v.toString().padStart(2, '0')
    return DialReading("${pad(hours)}:${pad(minutes)}:${pad(remaining)}", active)
}

/**
 * context-gauge.tsx:39-44: the gauge's needle level, tone and the text that travels with it
 * (title / aria-label — the reading is never carried by colour alone).
 */
@Immutable
data class GaugeReading(val percent: Int?, val tone: UsageTone, val text: String) {
    /** The needle's share of the 270° arc (context-gauge.tsx:43); drawn only when > 0. */
    val fillFraction: Float get() = (percent ?: 0) / 100f
    val showsNeedle: Boolean get() = percent != null && percent > 0
}

const val GaugeFallbackText: String = "Session telemetry"

fun gaugeReading(metrics: TelemetryMetrics?, env: ReadingEnv, title: String? = null): GaugeReading {
    val reading = contextReading(metrics, env)
    val percent = reading?.percent
    return GaugeReading(percent, usageTone(percent), reading?.detail ?: title ?: GaugeFallbackText)
}
