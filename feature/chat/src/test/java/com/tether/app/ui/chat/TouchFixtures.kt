package com.tether.app.ui.chat

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ta-8hcc fixtures: sessions whose tool calls touch files outside the session's working directory (the owner's shape: the
 * session runs in repo A, writes in repo B, and then names the files in its reply), folded by the real reducer.
 */
internal object TouchFixtures {
    const val CWD = "/ws-A"
    private const val T = 1_000L

    /** A tool call that names one path under [key] (Claude `file_path`, a notebook's `notebook_path`, OpenCode `filePath`, a search's `path`). */
    fun tool(turn: String, id: String, name: String, key: String?, value: String? = null): AgentEvent =
        ev("tool_start", turn, ts = T) {
            put("toolId", id); put("name", name)
            putJsonObject("input") { if (key != null && value != null) put(key, value) }
        }

    /** A Codex `file_change` with one change per path (`input.changes`). */
    fun fileChange(turn: String, id: String, vararg paths: String): AgentEvent =
        ev("tool_start", turn, ts = T) {
            put("toolId", id); put("name", "file_change")
            putJsonObject("input") { putJsonArray("changes") { paths.forEach { p -> addJsonObject { put("path", p); put("kind", "add") } } } }
        }

    /** A tool call with no input at all (a truncated or missing replay). */
    fun bare(turn: String, id: String, name: String): AgentEvent =
        ev("tool_start", turn, ts = T) { put("toolId", id); put("name", name) }

    /** An agent run whose own thread called [thread] (tool entries: name, key, path). */
    fun agent(turn: String, id: String, vararg thread: Triple<String, String, String>): List<AgentEvent> = listOf(
        ev("tool_start", turn, ts = T) {
            put("toolId", id); put("name", "Agent")
            putJsonObject("input") { put("description", "Write the digests"); put("subagent_type", "general-purpose") }
        },
        ev("subagent_message", turn, ts = T) {
            put("parentToolUseId", id)
            putJsonArray("items") {
                thread.forEachIndexed { n, (name, key, path) ->
                    addJsonObject {
                        put("key", "$id-$n"); put("kind", "tool"); put("name", name)
                        putJsonObject("input") { put(key, path) }
                    }
                }
            }
        },
        ev("tool_end", turn, ts = T) { put("toolId", id); put("output", "done"); put("isError", false) },
    )

    /** One finished turn: the prompt, [calls], then [reply] as the completed message `<turn>:m0` (null: no message). */
    fun turn(turn: String, calls: List<AgentEvent>, reply: String?): List<AgentEvent> = buildList {
        add(ev("turn_started", turn, ts = T) { put("idempotencyKey", "k-$turn") })
        add(ev("user_message_accepted", turn, ts = T) { put("text", "Prompt of $turn") })
        addAll(calls)
        if (reply != null) {
            add(ev("message_started", turn, ts = T) { put("blockId", "$turn:m0") })
            add(ev("message_completed", turn, ts = T) { put("blockId", "$turn:m0"); put("text", reply) })
        }
        add(ev("turn_end", turn, ts = T) { put("outcome", "ok") })
    }

    fun fold(vararg events: List<AgentEvent>): ChatFixtures.Folded = ChatFixtures.fold(*events.toList().flatten().toTypedArray())

    /** The index of [folded], and where a mention made in message `<turn>:m0` resolves. */
    fun resolve(folded: ChatFixtures.Folded, mention: String, turn: String? = null): String? =
        touchedFilesOf(folded.tree).resolve(mention, turn?.let { FileMentionAnchor(it, "$it:m0") })

    @Suppress("unused") private fun JsonObjectBuilder.unused() = Unit
}
