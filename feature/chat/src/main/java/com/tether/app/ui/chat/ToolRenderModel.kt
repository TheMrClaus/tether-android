package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsNumberFormat
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.text.NumberFormat
import java.util.Locale

/*
 * T6.2: pure formatters over an already-projected tool block, ported line for line from the web
 * (components/chat-tool-render.tsx and the activity grouping in components/chat-view.tsx:726-838).
 * They read the v128 projection TREE (JsValue), not the legacy typed model, so every `typeof`
 * check means exactly what it means in JS. Nothing here holds state or touches the wire.
 *
 * A Kotlin `null` argument stands for JS `undefined`; [JsNull] is JS `null`.
 */

// --- JS value semantics -----------------------------------------------------------------------

/** `value == null` in JS: undefined or null. */
internal fun JsValue?.isNullish(): Boolean = this == null || this is JsNull

/** JS truthiness (`if (value)`). */
internal fun JsValue?.jsTruthy(): Boolean = when (this) {
    null, JsNull -> false
    is JsBool -> value
    is JsNum -> value != 0.0 && !value.isNaN()
    is JsStr -> value.isNotEmpty()
    is JsObj, is JsArr -> true
}

/** `asRecord` (chat-tool-render.tsx:34): a plain object, else null. */
internal fun asRecord(value: JsValue?): JsObj? = value as? JsObj

/** `asString`: the string, else null. */
internal fun asString(value: JsValue?): String? = (value as? JsStr)?.value

/**
 * `JSON.stringify(value, null, 2)`: two-space indent, `": "` between key and value, JS property
 * order, `{}` / `[]` for empty containers. Non-finite numbers print `null`, as in JS.
 *
 * Bounded (security review M2): writing stops once more than [limit] characters exist (callers
 * only ever show a prefix, and a prefix of this output is a prefix of the web's), so a huge or
 * deeply nested value costs O(limit), not O(size) or O(depth²) of indentation. A container nested
 * past [PRETTY_MAX_DEPTH] prints `…` (divergence, noted: the web prints it, then truncates).
 */
internal fun jsonStringifyPretty(value: JsValue, limit: Int = Int.MAX_VALUE): String {
    val out = StringBuilder()
    writePretty(out, value, 0, limit)
    return if (out.length > limit) out.substring(0, limit) else out.toString()
}

internal const val PRETTY_MAX_DEPTH = 64

private fun indent(out: StringBuilder, depth: Int) {
    repeat(depth) { out.append("  ") }
}

private fun writePretty(out: StringBuilder, value: JsValue, depth: Int, limit: Int) {
    if (out.length > limit) return
    when (value) {
        is JsNull -> out.append("null")
        is JsBool -> out.append(if (value.value) "true" else "false")
        is JsNum -> {
            val d = value.value
            out.append(if (d.isNaN() || d.isInfinite()) "null" else JsNumberFormat.toJsString(d))
        }
        // Only a prefix can ever show: quote no more than could fit (escapes only lengthen).
        is JsStr -> JsCodec.quote(out, if (value.value.length > limit) value.value.substring(0, limit) else value.value)
        is JsArr -> {
            if (value.isEmpty()) {
                out.append("[]")
                return
            }
            if (depth >= PRETTY_MAX_DEPTH) {
                out.append('…')
                return
            }
            out.append("[\n")
            for ((i, item) in value.withIndex()) {
                if (out.length > limit) return
                if (i > 0) out.append(",\n")
                indent(out, depth + 1)
                writePretty(out, item, depth + 1, limit)
            }
            out.append("\n")
            indent(out, depth)
            out.append(']')
        }
        is JsObj -> {
            if (value.isEmpty()) {
                out.append("{}")
                return
            }
            if (depth >= PRETTY_MAX_DEPTH) {
                out.append('…')
                return
            }
            out.append("{\n")
            for ((i, key) in JsCodec.jsPropertyOrder(value.keys).withIndex()) {
                if (out.length > limit) return
                if (i > 0) out.append(",\n")
                indent(out, depth + 1)
                JsCodec.quote(out, key)
                out.append(": ")
                writePretty(out, value.getValue(key), depth + 1, limit)
            }
            out.append("\n")
            indent(out, depth)
            out.append('}')
        }
    }
}

/** `summarize(value, max = 600)` (chat-tool-render.tsx:21): pretty JSON (or the string), capped with "…". */
internal fun summarize(value: JsValue?, max: Int = 600): String {
    if (value.isNullish()) return ""
    val text = if (value is JsStr) value.value else jsonStringifyPretty(value!!, max + 1)
    return if (text.length > max) "${text.substring(0, max)}…" else text
}

/** `toLocaleString()` of a count (the web runs in the reader's locale). */
internal fun localeCount(n: Int, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(n)

// --- Tool media (v94 media_ref, v112 attachments, legacy base64 image blocks) -----------------

/** `ToolMediaItem`: [src] is a same-origin `/api/tool-media/…` URL or a `data:` URI. */
@Immutable
data class ToolMediaItem(val kind: String, val mediaType: String, val src: String, val bytes: Double? = null) {
    val isVideo: Boolean get() = kind == KIND_VIDEO

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
    }
}

/** `toMediaItem` (chat-tool-render.tsx:58-80). */
internal fun toMediaItem(block: JsValue?): ToolMediaItem? {
    val record = asRecord(block) ?: return null
    val type = asString(record["type"])
    if (type == "media_ref") {
        val kind = asString(record["mediaKind"])
        val mediaType = asString(record["mediaType"])
        val url = asString(record["url"])
        if ((kind == ToolMediaItem.KIND_IMAGE || kind == ToolMediaItem.KIND_VIDEO) && !mediaType.isNullOrEmpty() && !url.isNullOrEmpty()) {
            return ToolMediaItem(kind, mediaType, url, (record["bytes"] as? JsNum)?.value)
        }
        return null
    }
    if (type == "image") {
        val source = asRecord(record["source"])
        val mediaType = source?.let { asString(it["media_type"]) }
        val data = source?.let { asString(it["data"]) }
        if (source != null && asString(source["type"]) == "base64" && !mediaType.isNullOrEmpty() && !data.isNullOrEmpty()) {
            return ToolMediaItem(ToolMediaItem.KIND_IMAGE, mediaType, "data:$mediaType;base64,$data")
        }
        return null
    }
    return null
}

private fun isMediaBlock(block: JsValue): Boolean = toMediaItem(block) != null

/** `extractToolMedia`: the media blocks of an array output, in order. */
internal fun extractToolMedia(value: JsValue?): List<ToolMediaItem> {
    if (value !is JsArr) return emptyList()
    return value.mapNotNull(::toMediaItem)
}

/** `stripToolMedia`: an array output without its media blocks (the same instance when none matched). */
internal fun stripToolMedia(value: JsValue?): JsValue? {
    if (value !is JsArr) return value
    val filtered = value.filter { !isMediaBlock(it) }
    return if (filtered.size == value.size) value else JsArr.of(filtered)
}

/** `remainingToolText` (v112): the text beside the media; "" for a media-only output (never "[]"). */
internal fun remainingToolText(value: JsValue?): String {
    val stripped = stripToolMedia(value)
    if (stripped is JsArr && stripped.isEmpty()) return ""
    return summarize(stripped)
}

// --- File-edit diffs (Write / Edit / MultiEdit) ------------------------------------------------

/** One `DiffRow` of a file-edit diff: `ctx`, `del` or `add`. */
@Immutable
data class EditDiffRow(val t: String, val text: String) {
    companion object {
        const val CTX = "ctx"
        const val DEL = "del"
        const val ADD = "add"
    }
}

/** `lineDiff` (chat-tool-render.tsx:381-401): trim the shared prefix/suffix, keep 2 context lines. */
internal fun lineDiff(oldStr: String, newStr: String): List<EditDiffRow> {
    val o = oldStr.split("\n")
    val n = newStr.split("\n")
    var start = 0
    while (start < o.size && start < n.size && o[start] == n[start]) start++
    var endO = o.size
    var endN = n.size
    while (endO > start && endN > start && o[endO - 1] == n[endN - 1]) {
        endO--
        endN--
    }
    val ctx = 2
    val rows = ArrayList<EditDiffRow>()
    for (i in maxOf(0, start - ctx) until start) rows.add(EditDiffRow(EditDiffRow.CTX, o[i]))
    for (i in start until endO) rows.add(EditDiffRow(EditDiffRow.DEL, o[i]))
    for (i in start until endN) rows.add(EditDiffRow(EditDiffRow.ADD, n[i]))
    for (i in endO until minOf(o.size, endO + ctx)) rows.add(EditDiffRow(EditDiffRow.CTX, o[i]))
    return rows
}

/** `MAX_DIFF_ROWS`: a 5,000-line Write renders 200 rows and a "+N more lines" tail. */
internal const val MAX_DIFF_ROWS = 200

/** The rows a `DiffBlock` draws and how many it summarised away. */
internal class CappedDiff(val shown: List<EditDiffRow>, val hidden: Int)

internal fun capDiff(rows: List<EditDiffRow>): CappedDiff {
    val shown = if (rows.size > MAX_DIFF_ROWS) rows.subList(0, MAX_DIFF_ROWS) else rows
    return CappedDiff(shown, rows.size - shown.size)
}

/**
 * [MAX_DIFF_ROWS] across every edit of one card: edits past the budget are not drawn, and the last
 * drawn block counts every row left out ("+N more lines").
 */
internal fun capEdits(diffs: List<List<EditDiffRow>>): List<CappedDiff> {
    val out = ArrayList<CappedDiff>()
    var budget = MAX_DIFF_ROWS
    val total = diffs.sumOf { it.size }
    var drawn = 0
    for (rows in diffs) {
        if (budget <= 0) break
        val shown = if (rows.size > budget) rows.subList(0, budget) else rows
        budget -= shown.size
        drawn += shown.size
        out.add(CappedDiff(shown, 0))
    }
    val hidden = total - drawn
    if (hidden > 0 && out.isNotEmpty()) out[out.lastIndex] = CappedDiff(out.last().shown, hidden)
    return out
}

/** "+1,204 more lines" / "+1 more line". */
internal fun moreLinesLabel(hidden: Int): String = "+${localeCount(hidden)} more line${if (hidden == 1) "" else "s"}"

/** What `ToolInput` (chat-tool-render.tsx:437-477) draws for a block's input. */
@Immutable
sealed interface ToolInputModel {
    /** A file edit: the path, its tag (null = none) and one diff per edit. */
    data class Edit(val filePath: String, val tag: String?, val diffs: List<List<EditDiffRow>>) : ToolInputModel

    /** Everything else: `summarize(input)` in an expandable pre. */
    data class Raw(val text: String) : ToolInputModel
}

internal fun toolInputModel(name: String?, input: JsValue?): ToolInputModel {
    val record = asRecord(input)
    val filePath = record?.let { asString(it["file_path"]) }
    if (record != null && !filePath.isNullOrEmpty()) {
        if (name == "Write") {
            val content = asString(record["content"]) ?: ""
            return ToolInputModel.Edit(filePath, "new / overwrite", listOf(content.split("\n").map { EditDiffRow(EditDiffRow.ADD, it) }))
        }
        if (name == "Edit") {
            val oldStr = asString(record["old_string"])
            val newStr = asString(record["new_string"])
            if (oldStr != null && newStr != null) {
                return ToolInputModel.Edit(filePath, if (record["replace_all"].jsTruthy()) "all matches" else null, listOf(lineDiff(oldStr, newStr)))
            }
        }
        val edits = record["edits"]
        if (name == "MultiEdit" && edits is JsArr) {
            val valid = edits.mapNotNull(::asRecord)
                .map { asString(it["old_string"]) to asString(it["new_string"]) }
                .filter { (o, n) -> o != null && n != null }
            if (valid.isNotEmpty()) {
                return ToolInputModel.Edit(
                    filePath,
                    "${valid.size} edit${if (valid.size > 1) "s" else ""}",
                    valid.map { (o, n) -> lineDiff(o!!, n!!) },
                )
            }
        }
    }
    return ToolInputModel.Raw(summarize(input))
}

// --- Tool activity grouping (chat-view.tsx:726-838) --------------------------------------------

/** `toolCategory`: the summary noun for a tool name; null = not summarised. */
internal fun toolCategory(name: String?): String? {
    if (name.isNullOrEmpty()) return null
    return when {
        name == "Bash" -> "shell command"
        name == "Read" -> "file read"
        name == "Edit" || name == "MultiEdit" -> "file edit"
        name == "Write" || name == "NotebookEdit" -> "file write"
        name == "Grep" || name == "Glob" -> "search"
        name == "Agent" || name == "Task" || name == "TaskCreate" -> "agent"
        name == "WebSearch" || name == "WebFetch" -> "web request"
        name == "AskUserQuestion" -> null
        name == "command_execution" -> "shell command"
        name == "file_change" -> "file change"
        name == "subagent_activity" -> "agent"
        name.startsWith("mcp:") -> "MCP tool call"
        name.startsWith("collaboration:") -> "collaboration"
        else -> "tool call"
    }
}

/** `pluralize`: "1 search", "2 searches", "2 shell commands". */
internal fun pluralize(count: Int, singular: String): String {
    if (count == 1) return "1 $singular"
    if (singular.endsWith("ch") || singular.endsWith("sh") || singular.endsWith("ss")) return "$count ${singular}es"
    return "$count ${singular}s"
}

private fun JsObj.str(key: String): String? = asString(this[key])
private fun JsObj.isTrue(key: String): Boolean = (this[key] as? JsBool)?.value == true

internal fun JsObj.isToolBlock(): Boolean = str("kind") == "tool"
internal fun JsObj.toolName(): String? = str("name")
internal fun JsObj.isDone(): Boolean = isTrue("done")
internal fun JsObj.isErrorBlock(): Boolean = isTrue("isError")
internal fun JsObj.isInterrupted(): Boolean = isTrue("interrupted")

/** `activitySummary`: "Ran 2 shell commands, read 1 file"-style counts, "· N interrupted". */
internal fun activitySummary(blocks: List<JsObj>): String {
    val counts = LinkedHashMap<String, Int>()
    for (block in blocks) {
        if (!block.isToolBlock()) continue
        val cat = toolCategory(block.toolName()) ?: continue
        counts[cat] = (counts[cat] ?: 0) + 1
    }
    if (counts.isEmpty()) return "Ran tools"
    val parts = counts.map { (cat, count) -> pluralize(count, cat) }
    val interrupted = blocks.count { it.isToolBlock() && it.isDone() && it.isInterrupted() }
    return if (interrupted > 0) "${parts.joinToString(", ")} · $interrupted interrupted" else parts.joinToString(", ")
}

/** `isGroupableBlock`: a tool call (not AskUserQuestion) whose output carries no media. */
internal fun isGroupableBlock(block: JsObj): Boolean =
    block.isToolBlock() && block.toolName() != "AskUserQuestion" && extractToolMedia(block["output"]).isEmpty()

/** `groupHasRunning`: the group stays open while any of its tools runs. */
internal fun groupHasRunning(blocks: List<JsObj>): Boolean = blocks.any { it.isToolBlock() && !it.isDone() }

/** An interrupted call is not an error (issue #184). */
internal fun groupHasErrors(blocks: List<JsObj>): Boolean = blocks.any { it.isErrorBlock() && !it.isInterrupted() }

/** A group with a Codex file change stays open once the turn ends (its diffs sit in place). */
internal fun groupHasFileChange(blocks: List<JsObj>): Boolean = blocks.any { it.isToolBlock() && it.toolName() == "file_change" }

/** One `ActivitySegment`: a lone block or a run of groupable tool blocks. */
internal sealed interface ActivitySegment {
    data class Single(val blockId: String) : ActivitySegment
    data class Group(val blockIds: List<String>) : ActivitySegment
}

/** `segmentBlocks`: consecutive groupable blocks become one group; anything else breaks it. */
internal fun segmentBlocks(blockIds: List<String>, blockFor: (String) -> JsObj?): List<ActivitySegment> {
    val segments = ArrayList<ActivitySegment>()
    var pending = ArrayList<String>()
    fun flush() {
        if (pending.isEmpty()) return
        segments.add(ActivitySegment.Group(pending))
        pending = ArrayList()
    }
    for (blockId in blockIds) {
        val block = blockFor(blockId) ?: continue
        if (isGroupableBlock(block)) {
            pending.add(blockId)
        } else {
            flush()
            segments.add(ActivitySegment.Single(blockId))
        }
    }
    flush()
    return segments
}

/** The default state of a group's `<details open={running || hasFileChange}>`. */
internal fun groupOpenByDefault(blocks: List<JsObj>): Boolean = groupHasRunning(blocks) || groupHasFileChange(blocks)
