package com.tether.app.ui.chat

import com.tether.app.protocol.helpers.Elapsed
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.text.SafeText

/*
 * ta-a5jl: the pure model of a compact activity row (one transcript line per tool call or thinking
 * block): the verb, the kind glyph, the one-line argument and the state, read from the same
 * projection-tree block the full renderers read. Nothing here draws or holds state.
 */

/** The kind glyph of a row; mapped to a TetherIcons vector where it is drawn (this model stays pure). */
internal enum class ActivityGlyph {
    SquareTerminal, Eye, Pencil, FilePlus, FileDiff, Search, FolderOpen, Network, Bot, ListTodo, Server, Boxes, Wrench, Brain,
}

/** What one row says: [verb] (fixed vocabulary, at most 8 characters), [glyph], [arg] (one line, at most [ACTIVITY_ARG_MAX]) and [state]. */
internal data class ActivityRowModel(
    val verb: String,
    val glyph: ActivityGlyph,
    val arg: String,
    val state: ToolState,
    /** "12s" while a tool runs and reports its elapsed time, else empty. */
    val elapsed: String = "",
) {
    /** The status words beside the state glyph (`toolStatusText`): empty for a done row. */
    val words: String
        get() = when (state) {
            ToolState.Running -> if (elapsed.isNotEmpty()) "running · $elapsed" else "running"
            ToolState.Interrupted -> "interrupted"
            ToolState.Error -> "error"
            ToolState.Done -> ""
        }

    /** The row's one accessible name: "<verb> <arg>, <state>" ("Thinking, done"; "Shell git status, running, 12s"). */
    val label: String
        get() {
            // The argument is the agent's / a file's own text: spoken as the code rule shows it (a hidden control is a token).
            val head = if (arg.isEmpty()) verb else "$verb ${SafeText.code(arg)}"
            val stateWords = when (state) {
                ToolState.Running -> if (elapsed.isNotEmpty()) "running, $elapsed" else "running"
                ToolState.Interrupted -> "interrupted"
                ToolState.Error -> "error"
                ToolState.Done -> "done"
            }
            return "$head, $stateWords"
        }
}

/** The longest argument a row carries (`toolInputSummary`'s cap). */
internal const val ACTIVITY_ARG_MAX = 200

/** How much of a source field is looked at before its whitespace is folded (a huge command costs a bounded scan). */
private const val ARG_SCAN_MAX = 1000

private val WHITESPACE_RUN = Regex("\\s+")

/** Whitespace runs become one space, the ends are trimmed; the scan is bounded. */
internal fun activityArgText(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    return WHITESPACE_RUN.replace(value.take(ARG_SCAN_MAX), " ").trim()
}

private fun JsObj.field(key: String): String? = (this[key] as? JsStr)?.value
private fun record(value: JsValue?): JsObj = value as? JsObj ?: JsObj.EMPTY

/** The first non-empty (after folding) of [keys] in [input]. */
private fun first(input: JsObj, vararg keys: String): String {
    for (key in keys) {
        val folded = activityArgText(input.field(key))
        if (folded.isNotEmpty()) return folded
    }
    return ""
}

/** The compact input of a tool the table does not know: `key: value` pairs (each folded to one line) two spaces apart. */
private fun compactInput(input: JsValue?): String {
    if (input == null) return ""
    val record = input as? JsObj ?: return activityArgText(jsonStringifyPretty(input, ARG_SCAN_MAX))
    val pairs = ArrayList<String>()
    var scanned = 0
    for (key in record.keys) {
        if (scanned >= ARG_SCAN_MAX) break
        val v = record[key] ?: continue
        val shown = when (v) {
            is JsStr -> v.value.take(ARG_SCAN_MAX)
            is JsNum -> jsonStringifyPretty(v, 64)
            else -> jsonStringifyPretty(v, ARG_SCAN_MAX)
        }
        val pair = activityArgText("$key: $shown")
        scanned += pair.length
        pairs += pair
    }
    return pairs.joinToString("  ")
}

private fun joinArgs(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("  ")

private fun capped(arg: String): String = if (arg.length > ACTIVITY_ARG_MAX) arg.substring(0, ACTIVITY_ARG_MAX) else arg

/** The row verb of the generic kind (a tool with no kind of its own, mcp__ names included). */
internal const val ActivityGenericVerb = "Tool"

/** The row for a tool block: its verb, glyph, argument (the same fields as `toolInputSummary`) and state. */
internal fun activityRowModel(block: JsObj): ActivityRowModel {
    val name = block.toolName().orEmpty()
    val input = record(block["input"])
    val state = toolStateOf(block)
    val elapsed = if (state == ToolState.Running) Elapsed.elapsedLabel(block["elapsedSeconds"]) else ""
    fun row(verb: String, glyph: ActivityGlyph, arg: String) = ActivityRowModel(verb, glyph, capped(arg), state, elapsed)
    return when {
        name == "Bash" || name == "command_execution" -> row("Shell", ActivityGlyph.SquareTerminal, first(input, "command"))
        name == "BashOutput" -> row("Shell", ActivityGlyph.SquareTerminal, first(input, "bash_id"))
        name == "KillShell" -> row("Shell", ActivityGlyph.SquareTerminal, first(input, "shell_id"))
        name == "Read" -> row("Read", ActivityGlyph.Eye, first(input, "file_path"))
        name == "Edit" || name == "MultiEdit" -> row("Edit", ActivityGlyph.Pencil, first(input, "file_path"))
        name == "NotebookEdit" -> row("Edit", ActivityGlyph.Pencil, first(input, "notebook_path", "file_path"))
        name == "Write" -> row("Write", ActivityGlyph.FilePlus, first(input, "file_path"))
        name == "file_change" -> {
            val changes = fileChangeView(block).changes
            val head = activityArgText(changes.firstOrNull()?.path)
            row("Edit", ActivityGlyph.FileDiff, if (changes.size > 1) "$head +${changes.size - 1}" else head)
        }
        name == "Grep" -> row("Search", ActivityGlyph.Search, joinArgs(first(input, "pattern"), first(input, "path")))
        name == "Glob" -> row("Find", ActivityGlyph.FolderOpen, first(input, "pattern"))
        name == "WebSearch" -> row("Search", ActivityGlyph.Search, first(input, "query"))
        name == "WebFetch" -> row("Fetch", ActivityGlyph.Network, first(input, "url"))
        name == "subagent_activity" -> {
            val view = subagentView(block)
            row("Agent", ActivityGlyph.Bot, activityArgText(view.path))
        }
        name == "Task" || name == "Agent" || name == "TaskCreate" || name == "task" ->
            row("Agent", ActivityGlyph.Bot, first(input, "description", "prompt"))
        name == "TodoWrite" || name == "ExitPlanMode" -> row("Plan", ActivityGlyph.ListTodo, "")
        name.startsWith("mcp:") -> {
            val view = mcpView(block)
            row("MCP", ActivityGlyph.Server, "${activityArgText(view.server)} · ${activityArgText(view.tool)}")
        }
        name.startsWith("collaboration:") -> row("Agents", ActivityGlyph.Boxes, activityArgText(collaborationView(block).action))
        else -> row(ActivityGenericVerb, ActivityGlyph.Wrench, joinArgs(activityArgText(name), compactInput(block["input"])))
    }
}

/** The row for a thinking block: "Thinking", Running while the block streams, else Done. */
internal fun thinkingRowModel(block: TurnBlock): ActivityRowModel =
    ActivityRowModel("Thinking", ActivityGlyph.Brain, "", if (block.done == false) ToolState.Running else ToolState.Done)

/** What an open sheet shows: a tool block's tree object or a thinking block. */
internal sealed interface ActivityTarget {
    class Tool(val raw: JsObj) : ActivityTarget
    class Thinking(val block: TurnBlock) : ActivityTarget
}

/** The sheet key of a row: `<turnId>/<blockId>`. */
internal fun activityKey(turnId: String, blockId: String): String = "$turnId/$blockId"

/**
 * The block a sheet [key] names, read from the projection TREE (never from the row list, so an activity group that
 * closes as its run ends does not close the sheet), with the typed projection as the fallback the rows use. Null when
 * the block has left the projection, and for a thinking block while the showThinking gate is off (its row is gone, so its
 * open sheet closes: ta-rzgv). A turn id is matched as the longest known id the key starts with, so an id with a
 * slash in it still resolves.
 */
internal fun activityTarget(projection: SessionProjection, tree: JsObj?, key: String, showThinking: Boolean): ActivityTarget? {
    val turnId = projection.turnOrder.filter { key.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
    val blockId = key.substring(turnId.length + 1)
    val turn = projection.turnsById[turnId] ?: return null
    val typed = turn.blocksById[blockId]
    val treeBlock = ((tree?.get("turnsById") as? JsObj)?.get(turnId) as? JsObj)?.let { it["blocksById"] as? JsObj }?.get(blockId) as? JsObj
    val kind = typed?.kind ?: treeBlock?.field("kind") ?: return null
    return when (kind) {
        Vocab.BLOCK_TOOL -> ActivityTarget.Tool(treeBlock ?: typed?.asTree() ?: return null)
        Vocab.BLOCK_THINKING -> if (showThinking) ActivityTarget.Thinking(typed ?: return null) else null
        else -> null
    }
}
