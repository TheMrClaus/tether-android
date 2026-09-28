package com.tether.app.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * One-line text helpers over the legacy typed model, still used by the approval card (T6.3) and
 * the sub-agent run panel (T6.4). The transcript's tool cards moved to ToolCards.kt (T6.2), which
 * ports the web's renderers over the projection tree.
 */

// --- JsonElement display helpers -------------------------------------------------

internal fun JsonElement?.str(field: String): String? =
    ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.content

/** One-line input summary per tool, mirroring chat-tool-render.tsx. */
internal fun toolInputSummary(name: String?, input: JsonElement?): String? {
    if (input == null) return null
    val summary = when (name) {
        "Bash" -> input.str("command")
        "Read", "Edit", "Write", "NotebookEdit" -> input.str("file_path")
        "Grep" -> listOfNotNull(input.str("pattern"), input.str("path")).joinToString("  ")
        "Glob" -> input.str("pattern")
        "Task" -> input.str("description") ?: input.str("prompt")
        "WebFetch" -> input.str("url")
        "WebSearch" -> input.str("query")
        else -> null
    }
    if (summary != null) return summary.take(200)
    // Fallback: compact JSON.
    val compact = (input as? JsonObject)?.entries?.joinToString("  ") { (k, v) ->
        "$k: ${(v as? JsonPrimitive)?.content ?: v.toString()}"
    } ?: input.toString()
    return compact.take(200).ifBlank { null }
}

/** Best-effort text extraction from a tool output payload; truncated to [maxChars]. */
internal fun toolOutputText(output: JsonElement?, maxChars: Int = 600): String? {
    val text = when (output) {
        null -> return null
        is JsonPrimitive -> output.content
        is JsonObject -> output.str("text")
            ?: output.str("output")
            ?: output.str("stdout")
            ?: output.str("content")
            ?: (output["content"] as? JsonArray)?.joinToString("\n") { entry ->
                (entry as? JsonObject)?.str("text") ?: entry.toString()
            }
            ?: output.toString()
        is JsonArray -> output.joinToString("\n") { entry ->
            (entry as? JsonObject)?.str("text") ?: (entry as? JsonPrimitive)?.content ?: entry.toString()
        }
    }
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.length > maxChars) trimmed.take(maxChars) + "…" else trimmed
}

internal data class DiffModel(val filePath: String, val tag: String, val deleted: List<String>, val added: List<String>)

internal fun diffFor(name: String?, input: JsonElement?): DiffModel? {
    if (input == null) return null
    return when (name) {
        "Edit" -> {
            val old = input.str("old_string") ?: return null
            val new = input.str("new_string") ?: return null
            DiffModel(input.str("file_path") ?: "?", "EDIT", old.lines(), new.lines())
        }
        "Write" -> {
            val content = input.str("content") ?: return null
            DiffModel(input.str("file_path") ?: "?", "WRITE", emptyList(), content.lines().take(80))
        }
        else -> null
    }
}
