package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ta-dh1: the Engines tab's cards (settings-dialog.tsx 887c222 :24-45 `ENGINES`), in the web's
 * order: one per real engine (fake is a mode, gemini replay-only). [home] / [command] / [launch]
 * are the ServerSettings keys the card edits; [launch] is Claude's only (the full wrapper command
 * at spawn). [homeOptional] (issue #86): Claude's home may be empty (the operator's real HOME), so
 * its switch is never blocked on it; the others fail closed without one.
 */
enum class EngineCard(
    val id: String,
    val label: String,
    val glyph: String,
    val home: ServerSetting,
    val command: ServerSetting,
    val launch: ServerSetting?,
    val homeOptional: Boolean,
) {
    Claude("claude", "Claude Code", "C", ServerSetting.ClaudeHome, ServerSetting.ClaudeCommand, ServerSetting.ClaudeLaunchCommand, homeOptional = true),
    Codex("codex", "Codex", "X", ServerSetting.CodexHome, ServerSetting.CodexCommand, null, homeOptional = false),
    Opencode("opencode", "OpenCode", "O", ServerSetting.OpencodeHome, ServerSetting.OpencodeCommand, null, homeOptional = false),
    Reasonix("reasonix", "Reasonix", "R", ServerSetting.ReasonixHome, ServerSetting.ReasonixCommand, null, homeOptional = false),
    Pi("pi", "Pi", "P", ServerSetting.PiHome, ServerSetting.PiCommand, null, homeOptional = false),
    Dsh("dsh", "DeepSeek Harness", "D", ServerSetting.DshHome, ServerSetting.DshCommand, null, homeOptional = false),
    ;

    companion object {
        /** The command keys: the web sends an emptied command as "" (the others as null). */
        val commandSettings: Set<ServerSetting> by lazy { entries.map { it.command }.toSet() }
    }
}

/**
 * ta-dh1: one `EngineDetection` (lib/protocol.ts 887c222 :1528, lib/engine-detect.mjs), the parts
 * the card reads, decoded tolerantly: a part of another type reads as absent. A string longer than
 * [MAX_TEXT] reads as absent too (no host path is that long, and the server would refuse it as a
 * home), so a hostile frame cannot hand the card an unbounded value.
 */
data class EngineDetection(
    /** `det.found`, by JavaScript truthiness. */
    val found: Boolean,
    val version: String?,
    /** "host install" | "user PATH" | "bundled" | "not found", or anything else (read as "not found"). */
    val source: String?,
    /** The detected config home (the home field's placeholder; "Use detected" when no home is set). */
    val configDir: String?,
    /** The resolved binary (the command field's placeholder). */
    val binPath: String?,
) {
    companion object {
        const val MAX_TEXT = 4096

        /**
         * `detected[id]` as the web reads it: absent, null or a falsy value is no detection
         * ("scanning…"); any other value is one, its parts read from it when it is an object.
         */
        fun of(element: JsonElement?): EngineDetection? {
            when (element) {
                null, is JsonNull -> return null
                is JsonPrimitive -> return if (truthy(element)) EngineDetection(false, null, null, null, null) else null
                is JsonArray -> return EngineDetection(false, null, null, null, null)
                is JsonObject -> Unit
            }
            val o = element as JsonObject
            return EngineDetection(
                found = truthy(o["found"]),
                version = (o["version"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.length <= MAX_TEXT },
                source = string(o["source"]),
                configDir = string(o["configDir"]),
                binPath = string(o["binPath"]),
            )
        }

        private fun string(e: JsonElement?): String? =
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_TEXT }

        private fun truthy(e: JsonElement?): Boolean = when (e) {
            null, is JsonNull -> false
            is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else when (e.content) {
                "true" -> true
                "false" -> false
                else -> e.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false
            }
            else -> true
        }

        /** settings-dialog.tsx:82-89 `detectionSourceLabel`. */
        fun sourceLabel(source: String?): String = when (source) {
            "host install" -> "host install"
            "user PATH" -> "user PATH"
            "bundled" -> "bundled"
            else -> "not found"
        }
    }
}

/**
 * JavaScript's `String.prototype.trim()`: the ECMAScript WhiteSpace and LineTerminator code points
 * removed from both ends (TAB, VT, FF, SP, NBSP, ZWNBSP U+FEFF, every Zs, LF, CR, LS, PS). Kotlin's
 * `trim()` differs on U+FEFF and U+001C-U+001F, so a value is trimmed exactly as the web trims it.
 */
fun jsTrim(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && jsSpace(text[start])) start++
    while (end > start && jsSpace(text[end - 1])) end--
    return text.substring(start, end)
}

/** ta-q6p: JavaScript's `\s` and trim set (shared with the profile command split). */
internal fun jsSpace(c: Char): Boolean =
    c == '\t' || c == '\u000B' || c == '\u000C' || c == ' ' || c == '\u00A0' || c == '\uFEFF' ||
        c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
