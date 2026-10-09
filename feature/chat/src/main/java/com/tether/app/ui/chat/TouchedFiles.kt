package com.tether.app.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/*
 * ta-8hcc: a relative file mention in the agent's prose (`digests/cat.md`) names a file the SAME session touched, usually in
 * another folder than the session's working directory. The mention is resolved at TAP time against the absolute paths the
 * session's tool calls touched; the index of those paths is built off the main thread with the transcript's rows and handed
 * to a stable holder ([TouchedFilesHolder]) only when the touched paths change, so a streaming delta rebuilds no prose.
 *
 * Same as the web? No: the web has no prose file links (markdown.tsx:36-62; tether#264 is the open request). This is the
 * owner-directed app feature (ta-9jnm, ta-8hcc); it adds no restriction and opens through the same /api/files route.
 */

/** One absolute, normalized path a tool call touched, at the walk position [ord] of the block that held the call. */
internal data class Touch(val path: String, val ord: Int) {
    /** The path's segments (no leading empty one), for the whole-segment suffix match. */
    val segments: List<String> = path.split('/').filter { it.isNotEmpty() }
}

/** Which block of the main transcript a piece of prose sits in (a sub-agent's message: its launcher block). */
internal data class FileMentionAnchor(val turnId: String, val blockId: String)

/** The block that holds the prose being read; null where unknown (every touch counts then). Set per transcript row. */
internal val LocalFileMentionAnchor = staticCompositionLocalOf<FileMentionAnchor?> { null }

/** The anchor an activity sheet [key] (`<turnId>/<blockId>`) names, by the longest known turn id (as [activityTarget] reads it). */
internal fun activityKeyAnchor(projection: SessionProjection, key: String): FileMentionAnchor? {
    val turnId = projection.turnOrder.filter { key.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
    return FileMentionAnchor(turnId, key.substring(turnId.length + 1))
}

/**
 * The files one session's tool calls touched, in walk order (turns in `turnOrder`, blocks in order; a sub-agent's own calls
 * take their launcher block's position), and the position of every block.
 */
internal class TouchedFiles(val touches: List<Touch>, private val order: Map<String, Int>) {
    /** True when [other] holds the same touched paths at the same positions (the only thing a holder publishes on). */
    fun sameTouchesAs(other: TouchedFiles): Boolean = touches == other.touches

    /**
     * The absolute path a relative [mention] (normalized segments joined by `/`, no `..`) names: the NEWEST touch at or before
     * [anchor]'s block whose path ends with all of the mention's whole segments (case-sensitive), else null. An anchor this
     * index has not seen is newer than everything in it (a touch after it would have changed the index), so every touch counts.
     */
    fun resolve(mention: String, anchor: FileMentionAnchor?): String? {
        if (touches.isEmpty() || mention.isEmpty()) return null
        val want = mention.split('/').filter { it.isNotEmpty() }
        if (want.isEmpty()) return null
        val upTo = anchor?.let { order[blockKey(it.turnId, it.blockId)] } ?: Int.MAX_VALUE
        for (i in touches.indices.reversed()) {
            val touch = touches[i]
            if (touch.ord > upTo) continue
            val have = touch.segments
            if (have.size < want.size) continue
            var same = true
            val skip = have.size - want.size
            for (n in want.indices) {
                if (have[skip + n] != want[n]) {
                    same = false
                    break
                }
            }
            if (same) return touch.path
        }
        return null
    }

    companion object {
        val EMPTY = TouchedFiles(emptyList(), emptyMap())

        internal fun blockKey(turnId: String, blockId: String): String = "$turnId\u0000$blockId"
    }
}

/**
 * The stable holder a [WorkspaceFileLinks] carries: the latest [TouchedFiles] of the transcript on screen. Read at tap time,
 * never by drawing. [publish] replaces it only when the touched paths (or the session) change; [publishCount] counts those.
 */
internal class TouchedFilesHolder {
    @Volatile private var current: TouchedFiles = TouchedFiles.EMPTY
    private var sessionKey: Any? = null

    /** How many times the holder took a new index (main thread; tests read it). */
    @Volatile var publishCount: Int = 0
        private set

    val latest: TouchedFiles get() = current

    fun publish(sessionKey: Any?, next: TouchedFiles) {
        val now = current
        if (next === now) return
        if (sessionKey == this.sessionKey && now.sameTouchesAs(next)) return
        this.sessionKey = sessionKey
        current = next
        publishCount++
    }
}

// --- The walk (pure; runs off the main thread with the rows) -------------------------------------------------------------

/** The tools whose input names one file (the server's classification, lib/session-brief.mjs, with every provider's names). */
private val FILE_TOOLS: Set<String> = setOf(
    // claude
    "Edit", "MultiEdit", "Write", "NotebookEdit", "Read",
    // opencode
    "edit", "write", "read", "patch",
    // codex: its changes are read first, this is the shape that carries one path instead
    "file_change",
)

private fun str(v: JsValue?): String? = (v as? JsStr)?.value

private fun firstString(vararg candidates: JsValue?): String? {
    for (candidate in candidates) {
        val s = str(candidate)
        if (!s.isNullOrEmpty()) return s
    }
    return null
}

/** `pathFromInput` of the server's ledger: `file_path`, `filePath`, `path`, `notebook_path`, the first non-empty string. */
private fun pathFromInput(input: JsValue?): String? {
    val record = input as? JsObj ?: return null
    return firstString(record["file_path"], record["filePath"], record["path"], record["notebook_path"])
}

/**
 * The raw paths one tool call touched, from its RAW input strings (never the row's folded, 200-character argument): a
 * file tool's one path; every path of a `file_change` (the output's changes when it has them, as the card reads them).
 * Grep, Glob and every other tool name directories or patterns, never files: they touch nothing.
 */
internal fun touchedPathsOf(name: String?, input: JsValue?, output: JsValue?): List<String> {
    if (name == null || name !in FILE_TOOLS) return emptyList()
    if (name == "file_change") {
        val changes = (output as? JsObj)?.get("changes") as? JsArr ?: (input as? JsObj)?.get("changes") as? JsArr
        if (changes != null) return changes.mapNotNull { (it as? JsObj)?.get("path")?.let(::str)?.takeIf { p -> p.isNotEmpty() } }
    }
    return listOfNotNull(pathFromInput(input))
}

/** Only an absolute path that normalizes (never a relative one: it has no base) and names something below the root. */
private fun absoluteOrNull(raw: String): String? {
    if (!raw.startsWith("/") || raw.length > MAX_TOUCHED_PATH) return null
    val normal = FileLinks.normalize(raw) ?: return null
    return normal.takeIf { it.length > 1 }
}

private const val MAX_TOUCHED_PATH = 4096

/** Every file the session's tool calls touched (main transcript and every sub-agent thread of it), in walk order. */
internal fun touchedFilesOf(tree: JsObj): TouchedFiles {
    val turnOrder = tree["turnOrder"] as? JsArr ?: return TouchedFiles.EMPTY
    val turnsById = tree["turnsById"] as? JsObj ?: return TouchedFiles.EMPTY
    val touches = ArrayList<Touch>()
    val order = HashMap<String, Int>()
    var ord = 0
    for (turnValue in turnOrder) {
        val turnId = str(turnValue) ?: continue
        val turn = turnsById[turnId] as? JsObj ?: continue
        val blocks = turn["blocks"] as? JsArr ?: continue
        val byId = turn["blocksById"] as? JsObj ?: continue
        for (idValue in blocks) {
            val blockId = str(idValue) ?: continue
            val block = byId[blockId] as? JsObj ?: continue
            val at = ord++
            order[TouchedFiles.blockKey(turnId, blockId)] = at
            if (str(block["kind"]) != "tool") continue
            for (raw in touchedPathsOf(str(block["name"]), block["input"], block["output"])) {
                absoluteOrNull(raw)?.let { touches.add(Touch(it, at)) }
            }
            // The calls of a sub-agent run live in its own thread; they take the launcher's position.
            val thread = block["subagent"] as? JsObj ?: continue
            val threadOrder = thread["order"] as? JsArr ?: continue
            val entries = thread["entries"] as? JsObj ?: continue
            for (keyValue in threadOrder) {
                val entry = str(keyValue)?.let { entries[it] } as? JsObj ?: continue
                if (str(entry["kind"]) != "tool") continue
                for (raw in touchedPathsOf(str(entry["name"]), entry["input"], entry["output"])) {
                    absoluteOrNull(raw)?.let { touches.add(Touch(it, at)) }
                }
            }
        }
    }
    return TouchedFiles(touches, order)
}

/** The mention of a relative inline-code path as the matcher wants it: `./` and empty segments dropped; null when it climbs (`..`). */
internal fun mentionOf(relativePath: String): String? {
    val parts = ArrayList<String>()
    for (seg in relativePath.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> return null
            else -> parts.add(seg)
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
}
