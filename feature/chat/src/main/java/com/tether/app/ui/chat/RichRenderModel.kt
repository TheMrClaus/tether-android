package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/*
 * T6.2: the pure view models behind the engine-specific rich cards, ported line for line from
 * components/codex-rich-render-model.mjs and components/opencode-rich-render-model.mjs, plus the
 * small formatters of codex-rich-renderers.tsx. Reads the v128 projection tree.
 */

private fun record(value: JsValue?): JsObj = value as? JsObj ?: JsObj.EMPTY
private fun text(value: JsValue?): String = (value as? JsStr)?.value ?: ""
private fun optionalText(value: JsValue?): String? = text(value).ifEmpty { null }
private fun finiteNumber(value: JsValue?): Double? = (value as? JsNum)?.value?.takeIf { it.isFinite() }

/**
 * `displayValue`: "" for nullish, the string itself, else `JSON.stringify(value, null, 2)` —
 * bounded at [max] characters then "…" (security review M2; the web has no cap).
 */
internal fun displayValue(value: JsValue?, max: Int = DISPLAY_MAX): String {
    val text = when {
        value.isNullish() -> return ""
        value is JsStr -> value.value
        else -> jsonStringifyPretty(value!!, max + 1)
    }
    return if (text.length > max) "${text.substring(0, max)}…" else text
}

/** The most text any one rich-card section lays out (64K characters). */
internal const val DISPLAY_MAX = 64 * 1024

/** A cut index that never splits a surrogate pair. */
private fun safeCut(text: String, index: Int): Int =
    if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index - 1 else index

/**
 * Security review M3 (divergence, noted: the web lays out any length): a finished payload shows
 * its first [max] characters and says how many more there are.
 */
internal fun capHead(text: String, max: Int = DISPLAY_MAX): String {
    if (text.length <= max) return text
    val cut = safeCut(text, max)
    return "${text.substring(0, cut)}\n… ${localeCount(text.length - cut)} more characters"
}

/** While a payload streams, only its newest [max] characters are laid out (each delta re-lays it out). */
internal fun capTail(text: String, max: Int = DISPLAY_MAX): String {
    if (text.length <= max) return text
    val cut = safeCut(text, text.length - max)
    return "… ${localeCount(cut)} earlier characters\n${text.substring(cut)}"
}

/** One diff line cut at [max] characters (a minified bundle is one 2 MB line). */
internal const val DIFF_LINE_MAX = 2_000

internal fun cutLine(text: String, max: Int = DIFF_LINE_MAX): String =
    if (text.length <= max) text else "${text.substring(0, safeCut(text, max))}…"

/** Rows a unified diff file draws before "+N more lines" (the web draws them all). */
internal const val DIFF_FILE_MAX_ROWS = 2_000

// --- Codex (codex-app-server-v2) ----------------------------------------------------------------

private val RICH_TOOL_NAMES = setOf("command_execution", "file_change", "subagent_activity")

/** `codexRichToolKind`: which rich card a Codex tool block gets, or null (the generic card). */
internal fun codexRichToolKind(block: JsObj?): String? {
    if (block == null || !block.isToolBlock()) return null
    val name = block["name"] as? JsStr ?: return null
    return when {
        name.value in RICH_TOOL_NAMES -> name.value
        name.value.startsWith("mcp:") -> "mcp"
        name.value.startsWith("collaboration:") -> "collaboration"
        else -> null
    }
}

@Immutable
data class CommandView(
    val command: String,
    val cwd: String?,
    val source: String?,
    val output: String,
    val status: String?,
    val exitCode: Long?,
    val durationMs: Double?,
    val running: Boolean,
    val failed: Boolean,
)

/** `Number.isInteger`. */
private fun jsInteger(value: JsValue?): Long? {
    val d = (value as? JsNum)?.value ?: return null
    return if (d.isFinite() && floor(d) == d) d.toLong() else null
}

internal fun commandView(block: JsObj): CommandView {
    val input = record(block["input"])
    val output = record(block["output"])
    val streaming = block["output"] as? JsStr
    return CommandView(
        command = text(input["command"]).ifEmpty { "Command" },
        cwd = optionalText(input["cwd"]),
        source = optionalText(input["source"]),
        output = streaming?.value ?: text(output["text"]),
        status = optionalText(output["status"]),
        exitCode = jsInteger(output["exitCode"]),
        durationMs = finiteNumber(output["durationMs"]),
        running = !block.isDone(),
        failed = block.isErrorBlock(),
    )
}

@Immutable
data class FileChangeEntry(val key: String, val path: String, val kind: String, val diff: String)

@Immutable
data class FileChangeView(
    val changes: List<FileChangeEntry>,
    val streamingOutput: String,
    val status: String?,
    val running: Boolean,
    val failed: Boolean,
)

private fun normalizedChange(value: JsValue, index: Int): FileChangeEntry? {
    val change = record(value)
    val path = text(change["path"])
    if (path.isEmpty()) return null
    return FileChangeEntry("$path:$index", path, text(change["kind"]).ifEmpty { "change" }, text(change["diff"]))
}

internal fun fileChangeView(block: JsObj): FileChangeView {
    val input = record(block["input"])
    val output = record(block["output"])
    val source = output["changes"] as? JsArr ?: input["changes"] as? JsArr ?: JsArr.EMPTY
    return FileChangeView(
        changes = source.mapIndexedNotNull { i, v -> normalizedChange(v, i) },
        streamingOutput = (block["output"] as? JsStr)?.value ?: "",
        status = optionalText(output["status"]),
        running = !block.isDone(),
        failed = block.isErrorBlock(),
    )
}

/**
 * `hasInlineFileChangeDiffs`: a turn whose own `file_change` blocks already show a diff, so the
 * trailing aggregate "Turn changes" card would duplicate them.
 */
internal fun hasInlineFileChangeDiffs(turn: JsObj?): Boolean {
    val blocks = turn?.get("blocks") as? JsArr ?: return false
    val byId = turn["blocksById"] as? JsObj ?: return false
    return blocks.any { id ->
        val block = (id as? JsStr)?.let { byId[it.value] } as? JsObj ?: return@any false
        block.isToolBlock() && block.toolName() == "file_change" && fileChangeView(block).changes.any { it.diff.isNotEmpty() }
    }
}

/** `String.prototype.trim`: JS WhiteSpace + LineTerminator (U+FEFF yes, U+0085 no). */
internal fun jsTrim(s: String): String {
    fun ws(c: Char) = c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == ' ' || c == '\u00A0' ||
        c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F' ||
        c == '\u3000' || c == '\uFEFF'
    var a = 0
    var b = s.length
    while (a < b && ws(s[a])) a++
    while (b > a && ws(s[b - 1])) b--
    return s.substring(a, b)
}

/** One unified-diff row: `meta` / `file` / `hunk` / `add` / `delete` / `context`. */
@Immutable
data class UnifiedDiffRow(val kind: String, val marker: String, val text: String)

@Immutable
data class DiffFileView(val key: String, val oldPath: String?, val newPath: String?, val rows: List<UnifiedDiffRow>)

/**
 * `/^diff --git a\/(.+) b\/(.+)$/` with JS semantics: JS `.` excludes only \n \r U+2028 U+2029
 * (Java's also excludes U+0085), and JS `$` is the end of input (Java's also matches before a
 * final line terminator, so a CRLF line would match here and not on the web).
 */
private val DIFF_GIT_HEADER = Regex("^diff --git a/([^\\n\\r\\u2028\\u2029]+) b/([^\\n\\r\\u2028\\u2029]+)\\z")

private fun diffPath(line: String): String? {
    val path = jsTrim(line.substring(4).split("\t", limit = 2)[0])
    return if (path == "/dev/null") null else path.replaceFirst(Regex("^[ab]/"), "")
}

/** `parseUnifiedDiff`: files → rows, as the web draws them (no highlighting, markers only). */
internal fun parseUnifiedDiff(unifiedDiff: String?): List<DiffFileView> {
    if (unifiedDiff.isNullOrEmpty()) return emptyList()
    class Builder(val key: String, var oldPath: String?, var newPath: String?, val rows: MutableList<UnifiedDiffRow>)
    val files = ArrayList<Builder>()
    var current: Builder? = null
    fun openFile(): Builder = current ?: Builder("diff:${files.size}", null, null, ArrayList()).also {
        current = it
        files.add(it)
    }
    for (line in unifiedDiff.split("\n")) {
        if (line.startsWith("diff --git ")) {
            val match = DIFF_GIT_HEADER.find(line)
            val file = Builder(
                "diff:${files.size}",
                match?.groupValues?.get(1),
                match?.groupValues?.get(2),
                mutableListOf(UnifiedDiffRow("meta", "·", line)),
            )
            current = file
            files.add(file)
            continue
        }
        val file = openFile()
        when {
            line.startsWith("--- ") -> {
                file.oldPath = diffPath(line)
                file.rows.add(UnifiedDiffRow("file", "−", line))
            }
            line.startsWith("+++ ") -> {
                file.newPath = diffPath(line)
                file.rows.add(UnifiedDiffRow("file", "+", line))
            }
            line.startsWith("@@") -> file.rows.add(UnifiedDiffRow("hunk", "·", line))
            line.startsWith("+") -> file.rows.add(UnifiedDiffRow("add", "+", line.substring(1)))
            line.startsWith("-") -> file.rows.add(UnifiedDiffRow("delete", "−", line.substring(1)))
            line.startsWith("\\") -> file.rows.add(UnifiedDiffRow("meta", "·", line))
            else -> file.rows.add(UnifiedDiffRow("context", " ", if (line.startsWith(" ")) line.substring(1) else line))
        }
    }
    return files.map { DiffFileView(it.key, it.oldPath, it.newPath, it.rows.toList()) }
}

@Immutable
data class McpView(
    val server: String,
    val tool: String,
    val arguments: String,
    val progress: String,
    val result: String,
    val status: String?,
    val error: String?,
    val durationMs: Double?,
    val appName: String?,
    val actionName: String?,
    val pluginId: String?,
    val running: Boolean,
    val failed: Boolean,
)

internal fun mcpView(block: JsObj): McpView {
    val input = record(block["input"])
    val output = record(block["output"])
    val attribution = record(input["attribution"])
    val result = record(output["result"])
    val error = record(output["error"])
    val name = text(block["name"])
    val fallback = if (name.startsWith("mcp:")) name.substring(4).split("/") else emptyList()
    val content = result["content"]
    return McpView(
        server = text(input["server"]).ifEmpty { fallback.getOrNull(0).orEmpty() }.ifEmpty { "MCP" },
        tool = text(input["tool"]).ifEmpty { fallback.drop(1).joinToString("/") }.ifEmpty { "tool" },
        arguments = displayValue(input["arguments"]),
        progress = (block["output"] as? JsStr)?.value ?: "",
        result = displayValue(if (content.isNullish()) result["structuredContent"] else content),
        status = optionalText(output["status"]),
        error = optionalText(error["message"]),
        durationMs = finiteNumber(output["durationMs"]),
        appName = optionalText(attribution["appName"]),
        actionName = optionalText(attribution["actionName"]),
        pluginId = optionalText(attribution["pluginId"]),
        running = !block.isDone(),
        failed = block.isErrorBlock(),
    )
}

@Immutable
data class AgentState(val id: String, val state: String)

@Immutable
data class CollaborationView(
    val action: String,
    val receivers: List<String>,
    val prompt: String?,
    val model: String?,
    val reasoningEffort: String?,
    val agents: List<AgentState>,
    val status: String?,
    val running: Boolean,
    val failed: Boolean,
)

private fun agentStates(value: JsValue?): List<AgentState> {
    val states = record(value)
    return com.tether.app.protocol.tree.JsCodec.jsPropertyOrder(states.keys).map { id ->
        val state = states.getValue(id)
        AgentState(id, (state as? JsStr)?.value ?: displayValue(state))
    }
}

internal fun collaborationView(block: JsObj): CollaborationView {
    val input = record(block["input"])
    val output = record(block["output"])
    val name = text(block["name"])
    val states = output["agentsStates"]
    return CollaborationView(
        action = if (name.startsWith("collaboration:")) name.substring("collaboration:".length) else "collaboration",
        receivers = (input["receiverThreadIds"] as? JsArr)?.mapNotNull { (it as? JsStr)?.value } ?: emptyList(),
        prompt = optionalText(input["prompt"]),
        model = optionalText(input["model"]),
        reasoningEffort = optionalText(input["reasoningEffort"]),
        agents = agentStates(if (states.isNullish()) input["agentsStates"] else states),
        status = optionalText(output["status"]),
        running = !block.isDone(),
        failed = block.isErrorBlock(),
    )
}

@Immutable
data class SubagentActivityView(val kind: String, val path: String?, val running: Boolean, val failed: Boolean)

internal fun subagentView(block: JsObj): SubagentActivityView {
    val done = block.isDone()
    val source = if (done) record(block["output"]) else record(block["input"])
    return SubagentActivityView(
        kind = text(source["kind"]).ifEmpty { if (done) "completed" else "started" },
        path = optionalText(source["agentPath"]),
        running = !done,
        failed = block.isErrorBlock(),
    )
}

/** `statusText` (codex-rich-renderers.tsx:43). */
internal fun richStatusText(running: Boolean, failed: Boolean, status: String? = null): String = when {
    running -> "running"
    failed -> status?.ifEmpty { null } ?: "failed"
    else -> status?.ifEmpty { null } ?: "completed"
}

/** `formatDuration`: "420 ms", "4.2 s", "12 s" (`toFixed` rounds half away from zero). */
internal fun formatDuration(durationMs: Double?): String? {
    if (durationMs == null) return null
    if (durationMs < 1_000) return "${jsRound(durationMs).let { com.tether.app.protocol.tree.JsNumberFormat.toJsString(it) }} ms"
    val seconds = durationMs / 1_000
    val digits = if (durationMs < 10_000) 1 else 0
    return "${toFixed(seconds, digits)} s"
}

/** `Math.round`: half toward +∞. */
private fun jsRound(value: Double): Double = floor(value + 0.5)

/** `Number.prototype.toFixed` for a small, positive value: the exact decimal, rounded half up. */
private fun toFixed(value: Double, digits: Int): String = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

// --- Plan / diff / review (turn-level rich details) ----------------------------------------------

@Immutable
data class PlanStep(val step: String, val status: String)

@Immutable
data class PlanView(val explanation: String?, val steps: List<PlanStep>) {
    val completed: Int get() = steps.count { it.status == "completed" }
}

/** `turn.initialPlan ?? turn.plan` (new turns keep the first plan that entered the transcript). */
internal fun transcriptPlan(turn: JsObj): PlanView? {
    val initial = turn["initialPlan"]
    val plan = (if (initial.isNullish()) turn["plan"] else initial) as? JsObj ?: return null
    val steps = (plan["steps"] as? JsArr)?.mapNotNull { v ->
        val s = v as? JsObj ?: return@mapNotNull null
        PlanStep(text(s["step"]), text(s["status"]))
    } ?: emptyList()
    return PlanView(plan["explanation"].let { (it as? JsStr)?.value?.ifEmpty { null } }, steps)
}

/** `PLAN_LABELS`. */
internal fun planLabel(status: String): String = when (status) {
    "pending" -> "Pending"
    "in_progress" -> "In progress"
    "completed" -> "Completed"
    else -> ""
}

/** "2 of 3 complete" / "No steps". */
internal fun planCount(plan: PlanView): String =
    if (plan.steps.isEmpty()) "No steps" else "${plan.completed} of ${plan.steps.size} complete"

/** `CodexPlanCard` renders nothing for an empty plan with no explanation. */
internal fun planShows(plan: PlanView?): Boolean = plan != null && (plan.steps.isNotEmpty() || plan.explanation != null)

@Immutable
data class ReviewView(val reviewId: String, val status: String, val target: String?, val result: String?) {
    val running: Boolean get() = status == "started"
    val failed: Boolean get() = status == "failed"
    val cancelled: Boolean get() = status == "cancelled"

    /** `reviewStatus`: "in progress" while started, else the status verbatim. */
    val statusLabel: String get() = if (status == "started") "in progress" else status
}

internal fun reviews(turn: JsObj): List<ReviewView> = (turn["reviews"] as? JsArr)?.mapNotNull { v ->
    val r = v as? JsObj ?: return@mapNotNull null
    ReviewView(text(r["reviewId"]), text(r["status"]), (r["target"] as? JsStr)?.value?.ifEmpty { null }, (r["result"] as? JsStr)?.value?.ifEmpty { null })
} ?: emptyList()

/** `turn.diff.unifiedDiff`, when the turn has one. */
internal fun turnUnifiedDiff(turn: JsObj): String? = ((turn["diff"] as? JsObj)?.get("unifiedDiff") as? JsStr)?.value

// --- opencode (opencode-serve-v2) --------------------------------------------------------------

/**
 * `taskResultText` (opencode-rich-render-model.mjs): the `<task_result>` payload, else the output
 * verbatim — `/<task_result>\r?\n?([\s\S]*?)\r?\n?<\/task_result>/` without a regex (L1: a lazy
 * group over a long unterminated output is quadratic). The first open tag, one optional CR and
 * one optional LF after it, the first close tag after that, and the lazy capture's end: the
 * earliest point where only "\r\n", "\r", "\n" or nothing stands before the close tag.
 */
internal fun taskResultText(output: String): String {
    if (output.isEmpty()) return output
    val open = output.indexOf(TASK_OPEN)
    if (open < 0) return output
    var start = open + TASK_OPEN.length
    if (start < output.length && output[start] == '\r') start++
    if (start < output.length && output[start] == '\n') start++
    val close = output.indexOf(TASK_CLOSE, start)
    if (close < 0) return output
    val end = when {
        close - 2 >= start && output[close - 2] == '\r' && output[close - 1] == '\n' -> close - 2
        close - 1 >= start && (output[close - 1] == '\r' || output[close - 1] == '\n') -> close - 1
        else -> close
    }
    return output.substring(start, end)
}

private const val TASK_OPEN = "<task_result>"
private const val TASK_CLOSE = "</task_result>"

/** `opencodeRichToolKind`: only opencode's `task` tool gets its own card. */
internal fun opencodeRichToolKind(block: JsObj?): String? {
    if (block == null || !block.isToolBlock()) return null
    return if ((block["name"] as? JsStr)?.value == "task") "task" else null
}

@Immutable
data class TaskView(
    val description: String?,
    val prompt: String?,
    val subagentType: String?,
    val output: String,
    val running: Boolean,
    val failed: Boolean,
)

internal fun taskView(block: JsObj): TaskView {
    val input = record(block["input"])
    val output = (block["output"] as? JsStr)?.value ?: ""
    return TaskView(
        description = optionalText(input["description"]),
        prompt = optionalText(input["prompt"]),
        subagentType = optionalText(input["subagent_type"]),
        output = taskResultText(output),
        running = !block.isDone(),
        failed = block.isErrorBlock(),
    )
}
