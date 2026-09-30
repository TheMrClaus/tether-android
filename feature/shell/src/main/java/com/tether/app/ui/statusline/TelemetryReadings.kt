package com.tether.app.ui.statusline

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.isStr
import com.tether.app.protocol.helpers.jsRound
import com.tether.app.protocol.helpers.jsToNumber
import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.UsageWindow
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.time.ZoneId
import java.util.Locale

/*
 * T4.3: the state-mapping layer for every telemetry surface — a faithful port of
 * components/telemetry-readings.tsx (tether @ PARITY_BASE 7d65611; unchanged at v129/v130).
 * Pure functions from the session row's metrics and the reducer's projection (SessionView) to the
 * strings and levels the components print. Every number is formatted through the faithful
 * lib/format.ts port (com.tether.app.protocol.helpers.Format), never the divergent
 * designsystem ui/util/Format.kt.
 *
 * Contract (telemetry-readings.tsx:6-11): every reading is optional on the wire, so every helper
 * returns null for "not reported" — no caller is ever handed NaN, "undefined", or a zero standing
 * in for a missing number; surfaces omit the whole segment/row on null.
 */

/** The wall clock and host locale a reading is taken with (lib/format.ts reads the host's). */
@Immutable
data class ReadingEnv(
    val nowMs: Double,
    val locale: Locale = Locale.getDefault(),
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        fun current(): ReadingEnv = ReadingEnv(System.currentTimeMillis().toDouble())
    }
}

/**
 * The SessionMetrics fields the telemetry readings consume, as JS numbers (null = absent / not a
 * finite number is decided by the helpers, exactly as on the web).
 *
 * [contextSnapshotAt] (issue #164, lib/protocol.ts SessionMetrics): decoded since T9.1, so a
 * transcript-derived reading is labelled as a snapshot here and in the inspector.
 */
@Immutable
data class TelemetryMetrics(
    val contextPercent: Double? = null,
    val contextTokens: Double? = null,
    val contextWindow: Double? = null,
    val contextSnapshotAt: Double? = null,
    val fiveHour: TelemetryWindow? = null,
    val weekly: TelemetryWindow? = null,
    val fable: TelemetryWindow? = null,
    val gitAhead: Double? = null,
    val gitBehind: Double? = null,
) {
    companion object {
        fun from(metrics: SessionMetrics?): TelemetryMetrics? = metrics?.let {
            TelemetryMetrics(
                contextPercent = it.contextPercent,
                contextTokens = it.contextTokens?.toDouble(),
                contextWindow = it.contextWindow?.toDouble(),
                contextSnapshotAt = it.contextSnapshotAt?.toDouble(),
                fiveHour = TelemetryWindow.from(it.fiveHour),
                weekly = TelemetryWindow.from(it.weekly),
                fable = TelemetryWindow.from(it.fable),
                gitAhead = it.gitAhead?.toDouble(),
                gitBehind = it.gitBehind?.toDouble(),
            )
        }
    }
}

/** lib/protocol.ts UsageWindow: `usedPercent` is 0-100, `resetsAt` Unix ms. */
@Immutable
data class TelemetryWindow(val usedPercent: Double?, val resetsAt: Double? = null) {
    companion object {
        fun from(window: UsageWindow?): TelemetryWindow? =
            window?.let { TelemetryWindow(it.usedPercent, it.resetsAt?.toDouble()) }
    }
}

/**
 * telemetry-readings.tsx:17 `UsageTone`: "" | "is-high" | "is-critical". A tone only ever
 * reinforces a printed value; no surface renders it without the value beside it.
 */
enum class UsageTone { None, High, Critical }

/** telemetry-readings.tsx:22-25 (DESIGN.md §5): warning at 75%, danger at 90%. */
fun usageTone(percent: Int?): UsageTone = when {
    percent == null -> UsageTone.None
    percent >= 90 -> UsageTone.Critical
    percent >= 75 -> UsageTone.High
    else -> UsageTone.None
}

/** telemetry-readings.tsx:28-31: a 0-100 field → rounded (Math.round), clamped, or null. */
fun percentReading(value: Double?): Int? {
    if (value == null || !value.isFinite()) return null
    return jsRound(value).coerceIn(0.0, 100.0).toInt()
}

/** telemetry-readings.tsx:33-35. */
private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }

private fun compact(value: Double): String = Format.compactNumber(value)

/** telemetry-readings.tsx:67-74. */
@Immutable
data class ContextReading(val percent: Int, val caption: String, val detail: String)

/** telemetry-readings.tsx:80-83: the stamp of a transcript-derived reading, or null when live. */
fun contextSnapshotAt(metrics: TelemetryMetrics?): Double? = finite(metrics?.contextSnapshotAt)

/** telemetry-readings.tsx:87-90: "snapshot 09:05 AM", or null when the reading is live. */
fun contextSnapshotLabel(metrics: TelemetryMetrics?, env: ReadingEnv): String? {
    val at = contextSnapshotAt(metrics) ?: return null
    return "snapshot ${Format.clockTime(JsNum(at), env.locale, env.zone)}"
}

/** telemetry-readings.tsx:92-95. */
@Immutable
data class ContextSnapshotReading(val tokens: Double, val asOf: String)

/**
 * telemetry-readings.tsx:102-109: the footprint of a session whose meter cannot render (window
 * unknown). Null when a percent reading renders, or when there is no stamped snapshot.
 */
fun contextSnapshotReading(metrics: TelemetryMetrics?, env: ReadingEnv): ContextSnapshotReading? {
    if (metrics == null) return null
    if (percentReading(metrics.contextPercent) != null) return null
    val tokens = finite(metrics.contextTokens)
    val at = contextSnapshotAt(metrics)
    if (tokens == null || at == null) return null
    return ContextSnapshotReading(tokens, Format.clockTime(JsNum(at), env.locale, env.zone))
}

/** telemetry-readings.tsx:111-128. */
fun contextReading(metrics: TelemetryMetrics?, env: ReadingEnv): ContextReading? {
    val percent = percentReading(metrics?.contextPercent) ?: return null
    val tokens = finite(metrics?.contextTokens)
    val window = finite(metrics?.contextWindow)
    val caption = when {
        tokens != null && window != null -> "${compact(tokens)} of ${compact(window)} tokens"
        tokens != null -> "${compact(tokens)} tokens in context"
        window != null -> "${compact(window)} token window"
        else -> "Window size not reported"
    }
    val provenance = contextSnapshotLabel(metrics, env)
    val withProvenance = if (provenance != null) "$caption · $provenance" else caption
    return ContextReading(percent, withProvenance, "Context $percent% full · $withProvenance")
}

/** telemetry-readings.tsx:130-133. */
@Immutable
data class WindowReading(val percent: Int?, val caption: String)

/** telemetry-readings.tsx:136-142: a rate-limit window (5 hour / weekly / Fable). */
fun windowReading(window: TelemetryWindow?, env: ReadingEnv): WindowReading {
    if (window == null) return WindowReading(null, "Not reported")
    val reset = Format.resetTime(window.resetsAt?.let(::JsNum), env.nowMs)
    return WindowReading(percentReading(window.usedPercent), reset.ifEmpty { "Reset time unavailable" })
}

/** telemetry-readings.tsx:146-155: ahead/behind, independent of the branch name. */
fun gitDivergence(ahead: Double?, behind: Double?): String? {
    val up = finite(ahead)
    val down = finite(behind)
    if (up == null && down == null) return null
    val parts = buildList {
        // `if (up)`: a finite non-zero count.
        if (up != null && up != 0.0) add("${numberToString(up)} commit${if (up == 1.0) "" else "s"} ahead")
        if (down != null && down != 0.0) add("${numberToString(down)} commit${if (down == 1.0) "" else "s"} behind")
    }
    if (parts.isEmpty()) return "In sync with upstream"
    return "${parts.joinToString(", ")} upstream"
}

/** telemetry-readings.tsx:157-162. */
@Immutable
data class TaskReading(val text: String, val progress: String?)

/**
 * telemetry-readings.tsx:164-178: what the agent is doing now, from the projection's `todo`
 * (TodoProjection: activeForm, completed, total). A finished list is NOT a current task.
 */
fun taskReading(todo: JsObj?): TaskReading? {
    if (todo == null) return null
    val total = jsFinite(todo["total"])
    val completed = jsFinite(todo["completed"])
    val progress = if (total != null && total > 0 && completed != null) {
        "${numberToString(completed)} of ${numberToString(total)} complete"
    } else {
        null
    }
    val active = (todo["activeForm"] as? JsStr)?.value?.let(::jsTrim)
    if (!active.isNullOrEmpty()) return TaskReading(active, progress)
    if (total != null && completed != null && total > 0 && completed >= total) return null
    if (progress != null) return TaskReading("${numberToString(completed!!)} of ${numberToString(total!!)} tasks complete", null)
    return null
}

private fun jsFinite(value: JsValue?): Double? = (value as? JsNum)?.value?.takeIf { it.isFinite() }

/** telemetry-readings.tsx:185-192 (issue #195, Claude's Wrap-Up Allowance). */
@Immutable
data class WrapUpReading(val label: String, val detail: String)

/** telemetry-readings.tsx:194. */
const val WRAP_UP_LABEL: String = "Wrapping up"

/**
 * telemetry-readings.tsx:201-217: the CLI's "running" state only — the grace flag is set AND a
 * turn is in flight AND the window's reset has not passed on this clock. Null (render nothing)
 * is the normal answer.
 */
fun wrapUpReading(rateLimit: JsObj?, activeTurnId: String?, env: ReadingEnv): WrapUpReading? {
    val grace = rateLimit?.get("grace")
    if (!truthy(grace) || activeTurnId.isNullOrEmpty()) return null
    val resetsAt = rateLimit!!["resetsAt"]
    // `rateLimit.resetsAt != null && now >= rateLimit.resetsAt` (JS relational coercion; NaN → false).
    if (!isNullish(resetsAt) && env.nowMs >= jsToNumber(resetsAt)) return null
    val credits = grace.isStr("wrap_up_then_credits")
    val notice = if (credits) {
        "Usage limit reached · brief included wrap-up, then usage credits"
    } else {
        "Usage limit reached · wrapping up"
    }
    val caveat = if (credits) "usage credits cover the rest" else "a capped allowance — it may stop after the current step"
    val reset = Format.resetTime(if (isNullish(resetsAt)) null else resetsAt, env.nowMs)
    return WrapUpReading(WRAP_UP_LABEL, "$notice — $caveat${if (reset.isNotEmpty()) " · limit $reset" else ""}")
}

/** [wrapUpReading] for a projection (`Pick<SessionProjection, "rateLimit" | "activeTurnId">`). */
fun wrapUpReading(state: SessionView?, env: ReadingEnv): WrapUpReading? =
    wrapUpReading(state?.rateLimit, state?.activeTurnId, env)

/**
 * The instant a live wrap-up expires on its own (session-statusline.tsx:141-147,
 * inspector.tsx:112-118): the CLI may not push a clearing rate_limit event for an idle reset, so
 * the surfaces re-render at `resetsAt`. Null when no timer is needed.
 */
fun wrapUpExpiresAt(state: SessionView?): Double? {
    val rateLimit = state?.rateLimit ?: return null
    if (!truthy(rateLimit["grace"])) return null
    return (rateLimit["resetsAt"] as? JsNum)?.value
}
