package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.coalesce
import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * T2.2: the pure sidebar folds that live INSIDE components/session-sidebar.tsx (not lib/, so the
 * helper corpus has no table for them — S0.2 skippedModules): the Active / Unread lenses
 * (hasWorkInProgress / hasUnseenWork), the Archived split, per-workspace block grouping, delegate
 * child grouping, and row naming. Ported from the component source at PARITY_BASE; covered by the
 * hand-written SessionSidebarTest. A row is the web's SidebarSession
 * (`{ key, workspace?, createdAt, updatedAt, lastMessageAt?, live?, history?, unread?, digest? }`).
 */
object SessionSidebar {

    // components/session-sidebar.tsx:45 — v93 per-block page size ("Show more").
    const val WORKSPACE_PAGE_SIZE = 10

    // components/session-sidebar.tsx:75 — the harnesses the sidebar filter can narrow to.
    val SIDEBAR_HARNESSES: List<Pair<String, String>> = listOf(
        "claude" to "Claude",
        "codex" to "Codex",
        "reasonix" to "Reasonix",
        "opencode" to "OpenCode",
        "gemini" to "Gemini",
    )

    // components/session-sidebar.tsx:82
    private val HARNESS_LABELS: Map<String, String> = SIDEBAR_HARNESSES.toMap()

    // components/session-sidebar.tsx:113 — `live.mode`, else "headless" for a history-only row.
    fun sidebarSessionMode(entry: JsValue?): JsValue? =
        coalesce(entry["live"]["mode"], if (truthy(entry["history"])) JsStr("headless") else null)

    private fun trimmedName(holder: JsValue?): String? {
        val name = holder["name"]
        return if (isNullish(name)) null else jsTrim(jsToString(name))
    }

    // components/session-sidebar.tsx:117 — the discovered (native) title wins unless renamed in Tether.
    fun sidebarSessionName(entry: JsValue?): String {
        val liveName = trimmedName(entry["live"])
        val historyName = trimmedName(entry["history"])
        if (!historyName.isNullOrEmpty() && !truthy(entry["live"]["nameIsCustom"])) return historyName
        if (!liveName.isNullOrEmpty()) return liveName
        if (!historyName.isNullOrEmpty()) return historyName
        val provider = coalesce(entry["live"]["provider"], entry["history"]["provider"])
        // `${HARNESS_LABELS[provider]} session`: a provider missing from the filter list prints "undefined".
        return if (truthy(provider)) "${HARNESS_LABELS[jsToString(provider)] ?: "undefined"} session" else "Chat"
    }

    // components/session-sidebar.tsx:137 — the block badge: first letter/number of the folder name.
    fun workspaceInitial(cwd: String): String {
        val name = Format.projectName(cwd)
        var letter: String? = null
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (isLetterOrNumber(cp)) {
                letter = String(Character.toChars(cp))
                break
            }
            i += Character.charCount(cp)
        }
        return (letter ?: name.getOrNull(0)?.toString() ?: "?").uppercase()
    }

    /** `/[\p{L}\p{N}]/u`: any letter, or any number (Nd, Nl, No). */
    private fun isLetterOrNumber(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    // components/session-sidebar.tsx:418 — "Active Only": a turn streaming/executing, or parked on the operator.
    fun hasWorkInProgress(entry: JsValue?): Boolean {
        val status = entry["live"]["status"]
        return status.isStr("active") || status.isStr("waiting")
    }

    // components/session-sidebar.tsx:429 — the collapsed "Archived" group (a handed-off source stays out).
    fun isArchivedLike(entry: JsValue?): Boolean = truthy(entry["live"]["runtimeArchived"])

    // components/session-sidebar.tsx:437 — "Unread": the client unread flag or the server's changed-while-away digest.
    fun hasUnseenWork(entry: JsValue?): Boolean = truthy(entry["unread"]) || truthy(entry["digest"])

    /** components/session-sidebar.tsx:447 — top-level rows, and delegate children keyed by their parent's row key. */
    data class GroupedRows(val rows: List<JsValue>, val childrenByKey: Map<String, List<JsValue>>)

    // components/session-sidebar.tsx:454 — a row is a child only when its parent is ALSO listed.
    fun groupDelegateChildren(entries: List<JsValue>): GroupedRows {
        val keyByLiveId = HashMap<String, JsValue?>()
        for (entry in entries) {
            val id = entry["live"]["id"]
            if (truthy(id)) keyByLiveId[jsToString(id)] = entry["key"]
        }
        val rows = ArrayList<JsValue>()
        val childrenByKey = LinkedHashMap<String, MutableList<JsValue>>()
        for (entry in entries) {
            val parentId = entry["live"]["parentSessionId"]
            val parentKey = if (truthy(parentId)) keyByLiveId[jsToString(parentId)] else null
            if (truthy(parentKey) && !com.tether.app.protocol.fold.strictEquals(parentKey, entry["key"])) {
                childrenByKey.getOrPut(jsToString(parentKey)) { ArrayList() }.add(entry)
            } else {
                rows.add(entry)
            }
        }
        return GroupedRows(rows, childrenByKey)
    }

    // components/session-sidebar.tsx:551 — a row must satisfy EVERY enabled attention toggle.
    fun passesAttention(entry: JsValue?, activeOnly: Boolean, unreadOnly: Boolean): Boolean =
        (!activeOnly || hasWorkInProgress(entry)) && (!unreadOnly || hasUnseenWork(entry))

    /** What the sidebar renders from the harness/query-filtered rows (components/session-sidebar.tsx:549-576). */
    data class SidebarRows(
        /** The main list: every lensed row passing the attention toggles, or the open (non-archived) rows. */
        val visibleRows: List<JsValue>,
        /** The collapsed Archived group's rows (lensed). */
        val archivedRows: List<JsValue>,
        /** Unlinked agent-CLI runs hidden per workspace ("N background CLI runs — Show"). */
        val hiddenByWorkspace: Map<JsValue, Int>,
        /** A lens is on: every block is forced open, empty blocks dropped, drag disabled. */
        val filtering: Boolean,
    )

    // components/session-sidebar.tsx:549 — the lens pipeline: provenance lens first, then Archived / attention.
    fun sidebarRows(
        filteredSidebarSessions: JsArr,
        sessionQuery: String,
        activeOnly: Boolean,
        unreadOnly: Boolean,
        hideAgentRuns: Boolean,
        harnessFilter: String?,
    ): SidebarRows {
        val querying = jsTrim(sessionQuery).isNotEmpty()
        val attentionOn = activeOnly || unreadOnly
        val lens = HistoryOrigin.partitionAgentCliRuns(
            filteredSidebarSessions,
            enabled = hideAgentRuns,
            querying = querying,
            counts = if (attentionOn) { entry -> passesAttention(entry, activeOnly, unreadOnly) } else { entry -> !isArchivedLike(entry) },
        )
        val lensedRows = lens.rows
        val openRows = lensedRows.filter { !isArchivedLike(it) }
        val archivedRows = lensedRows.filter { isArchivedLike(it) }
        val visibleRows = if (attentionOn) lensedRows.filter { passesAttention(it, activeOnly, unreadOnly) } else openRows
        val filtering = attentionOn || querying || harnessFilter != null
        return SidebarRows(visibleRows, archivedRows, lens.hiddenByWorkspace, filtering)
    }

    // components/session-sidebar.tsx:605 — rows grouped by block (`workspace ?? ""`), order preserved.
    fun rowsByWorkspace(visibleRows: List<JsValue>): Map<String, List<JsValue>> {
        val groups = LinkedHashMap<String, MutableList<JsValue>>()
        for (entry in visibleRows) {
            val workspace = entry["workspace"].let { if (isNullish(it)) "" else jsToString(it) }
            groups.getOrPut(workspace) { ArrayList() }.add(entry)
        }
        return groups
    }

    // components/session-sidebar.tsx:631 — per block: flat while filtering, else delegate children grouped.
    fun groupedByWorkspace(rowsByWorkspace: Map<String, List<JsValue>>, filtering: Boolean): Map<String, GroupedRows> =
        rowsByWorkspace.mapValues { (_, entries) -> if (filtering) GroupedRows(entries, emptyMap()) else groupDelegateChildren(entries) }

    // components/session-sidebar.tsx:641 — child rows whose parent is also listed never join a drag order.
    fun delegateChildKeys(sidebarSessions: List<JsValue>): Set<String> {
        val liveIds = HashSet<String>()
        for (entry in sidebarSessions) entry["live"]["id"].let { if (truthy(it)) liveIds.add(jsToString(it)) }
        val keys = LinkedHashSet<String>()
        for (entry in sidebarSessions) {
            val parentId = entry["live"]["parentSessionId"]
            if (truthy(parentId) && jsToString(parentId) in liveIds) keys.add(jsToString(entry["key"]))
        }
        return keys
    }
}
