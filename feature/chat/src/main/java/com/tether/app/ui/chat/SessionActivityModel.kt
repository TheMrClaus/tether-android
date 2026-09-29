package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import kotlin.math.floor

/**
 * T6.4: the session-level activity the composer deck and the transcript show beside the turns,
 * read off the v128 projection tree: the todo bar's progress (components/todo-bar.tsx
 * `selectProgress`) and the v54/v55 background `!` commands (chat-view.tsx
 * `backgroundCommandStatusLabel` / `backgroundCommandFailed`, the running bar, the finished chips
 * interleaved by launch time). Pure; checked against the web's own functions in
 * [SubagentRunConformanceTest].
 */

// --- Todo bar (v56) -----------------------------------------------------------------------------

internal object ProgressStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
}

@Immutable
internal data class ProgressItem(val label: String, val status: String)

@Immutable
internal data class ProgressView(val items: List<ProgressItem>, val activeLabel: String?, val completed: Int, val total: Int)

private fun s(v: JsValue?): String? = (v as? JsStr)?.value
private fun n(v: JsValue?): Double? = (v as? JsNum)?.value

/**
 * `selectProgress`: Claude's session-scoped `todo` when it holds items, else the newest Codex
 * turn with a non-empty `plan` (so the bar persists between turns); null when there is nothing.
 */
internal fun selectProgress(state: JsObj?): ProgressView? {
    if (state == null) return null
    val todo = state["todo"] as? JsObj
    val total = n(todo?.get("total"))
    if (todo != null && total != null && total > 0) {
        val items = (todo["items"] as? JsArr).orEmpty().mapNotNull { v ->
            val item = v as? JsObj ?: return@mapNotNull null
            val status = s(item["status"]) ?: return@mapNotNull null
            val content = s(item["content"]) ?: ""
            // `item.activeForm || item.content` for the item in flight.
            val label = if (status == ProgressStatus.IN_PROGRESS) s(item["activeForm"])?.ifEmpty { null } ?: content else content
            ProgressItem(label, status)
        }
        return ProgressView(items, s(todo["activeForm"]), n(todo["completed"])?.toInt() ?: 0, total.toInt())
    }
    val order = state["turnOrder"] as? JsArr ?: return null
    val turns = state["turnsById"] as? JsObj
    for (i in order.indices.reversed()) {
        val turn = s(order[i])?.let { turns?.get(it) } as? JsObj ?: continue
        val steps = ((turn["plan"] as? JsObj)?.get("steps") as? JsArr) ?: continue
        if (steps.isEmpty()) continue
        val items = steps.mapNotNull { v -> (v as? JsObj)?.let { ProgressItem(s(it["step"]) ?: "", s(it["status"]) ?: "") } }
        return ProgressView(
            items = items,
            activeLabel = items.firstOrNull { it.status == ProgressStatus.IN_PROGRESS }?.label,
            completed = items.count { it.status == ProgressStatus.COMPLETED },
            total = steps.size,
        )
    }
    return null
}

/** The collapsed bar's line: the current item, else "All tasks complete" / "N tasks". */
internal fun progressSummary(progress: ProgressView): String =
    progress.activeLabel ?: if (progress.completed >= progress.total) "All tasks complete" else "${progress.total} task${if (progress.total == 1) "" else "s"}"

/** `STATUS_WORD`: the item state in words. */
internal fun progressStatusWord(status: String): String = when (status) {
    ProgressStatus.PENDING -> "to do"
    ProgressStatus.IN_PROGRESS -> "in progress"
    ProgressStatus.COMPLETED -> "done"
    else -> status
}

// --- Background commands (v54/v55) --------------------------------------------------------------

internal const val BG_RUNNING = "running"

/** One `BackgroundCommandProjection`, with its folded output [segments] (stream + text). */
@Immutable
internal data class BackgroundCommandView(
    val commandId: String,
    val command: String,
    val cwd: String,
    val logFile: String,
    val status: String,
    val exitCode: Int?,
    val signal: String?,
    val startedAt: Double,
    val outputTruncated: Boolean,
    /** The raw `segments` array (identity-stable: the fold appends by replacing it). */
    val segments: JsArr,
) {
    val running: Boolean get() = status == BG_RUNNING
}

internal fun backgroundCommandView(value: JsValue?): BackgroundCommandView? {
    val o = value as? JsObj ?: return null
    val id = s(o["commandId"])?.takeIf { it.isNotEmpty() } ?: return null
    val exit = n(o["exitCode"])?.takeIf { it.isFinite() && it == floor(it) }?.toInt()
    return BackgroundCommandView(
        commandId = id,
        command = s(o["command"]) ?: "",
        cwd = s(o["cwd"]) ?: "",
        logFile = s(o["logFile"]) ?: "",
        status = s(o["status"]) ?: "",
        exitCode = exit,
        signal = s(o["signal"]),
        startedAt = n(o["startedAt"]) ?: 0.0,
        outputTruncated = (o["outputTruncated"] as? JsBool)?.value == true,
        segments = o["segments"] as? JsArr ?: JsArr.EMPTY,
    )
}

/** Every background command of [state], in the fold's order. */
internal fun backgroundCommands(state: JsObj?): List<BackgroundCommandView> =
    (state?.get("backgroundCommands") as? JsArr).orEmpty().mapNotNull(::backgroundCommandView)

/** The RUNNING commands (the bar above the composer), in the fold's order. */
internal fun runningBackgroundCommands(state: JsObj?): List<BackgroundCommandView> = backgroundCommands(state).filter { it.running }

/** The FINISHED commands, oldest launch first (a stable sort, as `Array.prototype.sort`). */
internal fun finishedBackgroundCommands(state: JsObj?): List<BackgroundCommandView> =
    backgroundCommands(state).filter { !it.running }.sortedBy { it.startedAt }

/** `backgroundCommandStatusLabel`: the outcome always in words. */
internal fun backgroundCommandStatusLabel(command: BackgroundCommandView): String = when (command.status) {
    "running" -> "running"
    "finished" -> "exit ${command.exitCode ?: 0}"
    "stopped" -> "stopped"
    "error" -> if (!command.signal.isNullOrEmpty()) "signal ${command.signal}" else "exit ${command.exitCode ?: 1}"
    "interrupted" -> "interrupted"
    else -> command.status
}

/** `backgroundCommandFailed`. */
internal fun backgroundCommandFailed(command: BackgroundCommandView): Boolean = command.status == "error" || command.status == "interrupted"

/** One output segment: [stderr] draws in `--warning`. */
@Immutable
internal data class OutputSegment(val stderr: Boolean, val text: String)

internal fun outputSegments(segments: JsArr): List<OutputSegment> = segments.mapNotNull { v ->
    val o = v as? JsObj ?: return@mapNotNull null
    OutputSegment(s(o["stream"]) == "stderr", s(o["text"]) ?: "")
}

/** How much of a command's captured output the sheet draws (its tail); the full stream is in the log file. */
internal const val COMMAND_SHEET_MAX_CHARS = 64_000

/**
 * The tail of [segments] the output sheet draws: at most [max] UTF-16 units, cut at a segment
 * start or inside the oldest kept one (never through a surrogate pair). [dropped] > 0 says so.
 */
internal class OutputTail(val segments: List<OutputSegment>, val dropped: Int)

internal fun outputTail(segments: List<OutputSegment>, max: Int = COMMAND_SHEET_MAX_CHARS): OutputTail {
    var budget = max
    val kept = ArrayDeque<OutputSegment>()
    var dropped = 0
    for (i in segments.indices.reversed()) {
        val seg = segments[i]
        if (budget <= 0) {
            dropped += seg.text.length
            continue
        }
        if (seg.text.length <= budget) {
            kept.addFirst(seg)
            budget -= seg.text.length
        } else {
            var cut = seg.text.length - budget
            if (cut < seg.text.length && Character.isLowSurrogate(seg.text[cut])) cut++
            kept.addFirst(OutputSegment(seg.stderr, seg.text.substring(cut)))
            dropped += cut
            budget = 0
        }
    }
    return OutputTail(kept.toList(), dropped)
}
