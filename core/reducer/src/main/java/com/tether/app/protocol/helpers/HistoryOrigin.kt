package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * T2.2: faithful port of lib/history-origin.mjs — who started a native session (issue #180).
 * `origin` is DISPLAY METADATA: nothing may branch a policy decision on it.
 */
object HistoryOrigin {

    // lib/history-origin.mjs:30
    val HISTORY_ORIGINS: List<String> = listOf("tether", "interactive", "agent-cli-child", "unknown")

    // lib/history-origin.mjs:32
    private val CODEX_ORIGINATORS = mapOf(
        "tether" to "tether",
        "codex-tui" to "interactive",
        "codex_cli_rs" to "interactive",
        "codex_chatgpt_android_remote" to "interactive",
        "codex_exec" to "agent-cli-child",
    )

    // lib/history-origin.mjs:40
    private val CLAUDE_ENTRYPOINTS = mapOf("sdk-ts" to "tether", "cli" to "interactive", "sdk-cli" to "agent-cli-child")

    // lib/history-origin.mjs:47
    fun codexOriginFromOriginator(originator: JsValue?): String {
        if (originator !is JsStr) return "unknown"
        if (SpawnMarker.parseSpawnMarker(originator) != null) return "agent-cli-child"
        return CODEX_ORIGINATORS[originator.value] ?: "unknown"
    }

    // lib/history-origin.mjs:54
    fun claudeOriginFromEntrypoint(entrypoint: JsValue?): String {
        if (entrypoint !is JsStr) return "unknown"
        return CLAUDE_ENTRYPOINTS[entrypoint.value] ?: "unknown"
    }

    // lib/history-origin.mjs:60 — the first non-empty `entrypoint` the records carry, or null.
    fun claudeEntrypointFromRecords(records: JsValue?): String? {
        for (item in jsIterate(if (truthy(records)) records else JsArr.EMPTY)) {
            val entrypoint = item["entrypoint"]
            if (entrypoint is JsStr && entrypoint.value.isNotEmpty()) return entrypoint.value
        }
        return null
    }

    // lib/history-origin.mjs:71 — an absent/unrecognised origin is "unknown", i.e. visible.
    fun historyOriginOf(history: JsValue?): String {
        val origin = history["origin"]
        return if (origin is JsStr && origin.value in HISTORY_ORIGINS) origin.value else "unknown"
    }

    // lib/history-origin.mjs:81 — a history-only row whose provenance is exactly agent-cli-child.
    fun isHideableAgentCliRun(entry: JsValue?): Boolean {
        if (!truthy(entry) || truthy(entry["live"]) || !truthy(entry["history"])) return false
        return historyOriginOf(entry["history"]) == "agent-cli-child"
    }

    // lib/history-origin.mjs:91 — a hideable row LINKED to a Tether parent (issue #181).
    fun isLinkedAgentCliRun(entry: JsValue?): Boolean =
        isHideableAgentCliRun(entry) && truthy(entry["history"]["spawnedBy"]["tetherSessionId"])

    /** `{ rows, hiddenByWorkspace }`: `hiddenByWorkspace` is a JS Map keyed by `entry.workspace ?? ""`. */
    data class AgentCliPartition(val rows: JsArr, val hiddenByWorkspace: Map<JsValue, Int>)

    // lib/history-origin.mjs:109 — apply the agent-CLI-run lens to the sidebar entries.
    fun partitionAgentCliRuns(
        entries: JsValue?,
        enabled: Boolean = true,
        querying: Boolean = false,
        counts: (JsValue?) -> Boolean = { true },
    ): AgentCliPartition {
        val list = entries as? JsArr ?: JsArr.EMPTY
        val hiddenByWorkspace = LinkedHashMap<JsValue, Int>()
        if (!enabled || querying) return AgentCliPartition(list, hiddenByWorkspace)
        val rows = ArrayList<JsValue>()
        for (entry in list) {
            if (!isHideableAgentCliRun(entry)) {
                rows.add(entry)
                continue
            }
            if (isLinkedAgentCliRun(entry) || !counts(entry)) continue
            val workspace = entry["workspace"].let { if (com.tether.app.protocol.fold.isNullish(it)) JsStr("") else it!! }
            hiddenByWorkspace[workspace] = (hiddenByWorkspace[workspace] ?: 0) + 1
        }
        return AgentCliPartition(JsArr.of(rows), hiddenByWorkspace)
    }
}
