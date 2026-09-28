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

/** The first [max] lines of [text] (a `split("\n")` prefix, walked with indexOf) and how many lines it has. */
internal fun boundedLines(text: String, max: Int): Pair<List<String>, Int> {
    val out = ArrayList<String>(minOf(max, 64))
    var total = 0
    var start = 0
    while (true) {
        val nl = text.indexOf('\n', start)
        total++
        if (out.size < max) out.add(text.substring(start, if (nl < 0) text.length else nl))
        if (nl < 0) break
        start = nl + 1
    }
    return out to total
}

/** A path or label is cut at this many characters before it is drawn (R4-L1). */
internal const val PATH_MAX = 4_096

/** One diff line cut at [max] characters (a minified bundle is one 2 MB line). */
internal const val DIFF_LINE_MAX = 2_000

internal fun cutLine(text: String, max: Int = DIFF_LINE_MAX): String =
    if (text.length <= max) text else "${text.substring(0, safeCut(text, max))}…"

/**
 * Rows one diff CARD draws in total — every file of a file-change card or of a turn diff shares it
 * (R3-M2: a per-file budget let 100 files × 2,000 rows build one list item) — then
 * "+N more lines · +M more files" (the web draws them all).
 */
internal const val DIFF_CARD_MAX_ROWS = 2_000

/** A unified-diff line (never wrapped: it scrolls sideways) is cut at this many characters. */
internal const val UNIFIED_LINE_MAX = 500

/** One file of a diff card and how many of its rows it draws. */
internal class PlannedFile(val file: DiffFileView, val label: String, val rows: Int)

/**
 * A card's diff: per change (a turn diff is one group), the files it draws; [groupsDrawn] leading
 * groups are drawn at all; what the budget left out ([hiddenRows] of [hiddenFiles] undrawn files);
 * [totalFiles] every file the card's diffs hold.
 */
internal class DiffCardPlan(val files: List<List<PlannedFile>>, val groupsDrawn: Int, val hiddenRows: Int, val hiddenFiles: Int) {
    val totalFiles: Int get() = files.sumOf { it.size } + hiddenFiles
    val more: String? get() = when {
        hiddenFiles <= 0 -> null
        else -> "${moreLinesLabel(hiddenRows)} · +${localeCount(hiddenFiles)} more file${if (hiddenFiles == 1) "" else "s"}"
    }
}

/**
 * [DIFF_CARD_MAX_ROWS] handed out in order over [diffs] (one per change of a file-change card,
 * whose path row costs [headerCost]; a turn diff is one diff with no header), parsed with
 * [parseUnifiedDiffBounded] so rows past the budget are never built: a file cut short shows its
 * own "+N more lines"; a file or a whole change past the budget is only counted (a change with no
 * diff counts as one file).
 */
internal fun planDiffCard(diffs: List<String>, budget: Int = DIFF_CARD_MAX_ROWS, headerCost: Int = 0): DiffCardPlan {
    var left = budget
    var hiddenRows = 0
    var hiddenFiles = 0
    var groupsDrawn = 0
    val out = diffs.map { diff ->
        if (left <= 0) {
            val counted = parseUnifiedDiffBounded(diff, 0)
            hiddenFiles += maxOf(1, counted.hiddenFiles)
            hiddenRows += counted.hiddenRows
            return@map emptyList()
        }
        groupsDrawn++
        left -= headerCost
        val parse = parseUnifiedDiffBounded(diff, maxOf(left, 0))
        hiddenRows += parse.hiddenRows
        hiddenFiles += parse.hiddenFiles
        left -= parse.files.sumOf { it.rows.size }
        parse.files.mapIndexed { index, file -> PlannedFile(file, file.newPath ?: file.oldPath ?: "Patch ${index + 1}", file.rows.size) }
    }
    return DiffCardPlan(out, groupsDrawn, hiddenRows, hiddenFiles)
}

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

/**
 * One diff file. [totalRows]: every row the file has, [rows] only those built (a bounded parse stops
 * building at the card's budget and only counts the rest).
 */
@Immutable
data class DiffFileView(val key: String, val oldPath: String?, val newPath: String?, val rows: List<UnifiedDiffRow>, val totalRows: Int = rows.size)

private const val DIFF_GIT_PREFIX = "diff --git a/"

/**
 * `/^diff --git a\/(.+) b\/(.+)$/` with JS semantics (JS `.` excludes only \n \r U+2028 U+2029;
 * JS `$` is the end of input), matched in LINEAR time (R4-M1: the regex backtracked quadratically
 * on `diff --git a/` + N × ` b/` + `\r`, an ANR on the main thread). Equivalent: the greedy first
 * group ends at the LAST ` b/` that still leaves a non-empty second group, and neither group may
 * hold a JS line terminator.
 */
internal fun diffGitPaths(line: String): Pair<String, String>? {
    if (!line.startsWith(DIFF_GIT_PREFIX)) return null
    for (i in DIFF_GIT_PREFIX.length until line.length) {
        val c = line[i]
        if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') return null
    }
    val idx = line.lastIndexOf(" b/", line.length - 4)
    if (idx < DIFF_GIT_PREFIX.length + 1) return null
    return line.substring(DIFF_GIT_PREFIX.length, idx) to line.substring(idx + 3)
}

private fun diffPath(line: String): String? {
    val path = jsTrim(line.substring(4).split("\t", limit = 2)[0])
    return if (path == "/dev/null") null else path.replaceFirst(Regex("^[ab]/"), "")
}

/** A bounded parse: the files begun within the budget, and what was only counted past it. */
internal class DiffParse(val files: List<DiffFileView>, val hiddenRows: Int, val hiddenFiles: Int)

/** `parseUnifiedDiff`: files → rows, as the web draws them (no highlighting, markers only). */
internal fun parseUnifiedDiff(unifiedDiff: String?): List<DiffFileView> = parseUnifiedDiffBounded(unifiedDiff, Int.MAX_VALUE).files

/**
 * [parseUnifiedDiff] building at most [rowBudget] rows (R4-M2: 10 MB of `a\n` built five million
 * row objects on the main thread before any cap applied). Lines are walked with indexOf — never a
 * split of the whole text. Past the budget, a file already begun keeps counting into its
 * [DiffFileView.totalRows] (its own "+N more lines"); a file begun past the budget is not built at
 * all: its rows go to [DiffParse.hiddenRows] and itself to [DiffParse.hiddenFiles].
 */
internal fun parseUnifiedDiffBounded(unifiedDiff: String?, rowBudget: Int): DiffParse {
    if (unifiedDiff.isNullOrEmpty()) return DiffParse(emptyList(), 0, 0)
    class Builder(val key: String, var oldPath: String?, var newPath: String?, val rows: MutableList<UnifiedDiffRow>, var total: Int)
    val files = ArrayList<Builder>()
    var current: Builder? = null
    var skipping = false // the current file was begun past the budget: counted, never built
    var left = rowBudget
    var hiddenRows = 0
    var hiddenFiles = 0
    val text = unifiedDiff
    var start = 0
    while (true) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        val header = text.startsWith("diff --git ", start)
        if (header) {
            if (left <= 0) {
                hiddenFiles++
                hiddenRows++
                skipping = true
                current = null
            } else {
                val line = text.substring(start, end)
                val paths = diffGitPaths(line)
                val file = Builder("diff:${files.size}", paths?.first, paths?.second, mutableListOf(UnifiedDiffRow("meta", "·", line)), 1)
                left--
                current = file
                skipping = false
                files.add(file)
            }
        } else {
            if (current == null && !skipping) {
                if (left <= 0) {
                    hiddenFiles++
                    skipping = true
                } else {
                    current = Builder("diff:${files.size}", null, null, ArrayList(), 0).also { files.add(it) }
                }
            }
            val file = current
            if (skipping || file == null) {
                hiddenRows++
            } else {
                file.total++
                val build = left > 0
                if (build) left--
                // Paths still follow a file's own `---` / `+++` lines, built or not.
                if (build || text.startsWith("--- ", start) || text.startsWith("+++ ", start)) {
                    val line = text.substring(start, end)
                    when {
                        line.startsWith("--- ") -> {
                            file.oldPath = diffPath(line)
                            if (build) file.rows.add(UnifiedDiffRow("file", "−", line))
                        }
                        line.startsWith("+++ ") -> {
                            file.newPath = diffPath(line)
                            if (build) file.rows.add(UnifiedDiffRow("file", "+", line))
                        }
                        line.startsWith("@@") -> file.rows.add(UnifiedDiffRow("hunk", "·", line))
                        line.startsWith("+") -> file.rows.add(UnifiedDiffRow("add", "+", line.substring(1)))
                        line.startsWith("-") -> file.rows.add(UnifiedDiffRow("delete", "−", line.substring(1)))
                        line.startsWith("\\") -> file.rows.add(UnifiedDiffRow("meta", "·", line))
                        else -> file.rows.add(UnifiedDiffRow("context", " ", if (line.startsWith(" ")) line.substring(1) else line))
                    }
                }
            }
        }
        if (nl < 0) break
        start = nl + 1
    }
    return DiffParse(files.map { DiffFileView(it.key, it.oldPath, it.newPath, it.rows.toList(), it.total) }, hiddenRows, hiddenFiles)
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
