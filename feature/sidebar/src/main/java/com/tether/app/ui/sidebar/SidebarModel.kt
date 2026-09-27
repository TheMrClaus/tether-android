package com.tether.app.ui.sidebar

import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.JsCollator
import com.tether.app.protocol.helpers.SidebarOrder
import com.tether.app.protocol.helpers.SidebarWorkspaces
import com.tether.app.protocol.helpers.get
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import com.tether.app.ui.prefs.SidebarSort
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.max

/**
 * One sidebar row: the web's `SidebarSession` ([js], what the verified helpers in
 * protocol.helpers read) plus the typed session / history it was built from.
 */
data class SidebarEntry(val js: JsObj, val live: AgentSession?, val history: HistorySession?) {
    val key: String get() = (js["key"] as JsStr).value
    val workspace: String? get() = (js["workspace"] as? JsStr)?.value
    val unread: Boolean get() = truthy(js["unread"])
}

/** Live `waiting` / `active` counts per workspace block (dashboard.tsx:897-910). */
data class WorkspaceActivity(val waiting: Int, val active: Int)

/**
 * T5.1: the dashboard.tsx derivations that feed `<SessionSidebar>` (dashboard.tsx:564-910), built
 * on the verified helpers (lib/sidebar-order.mjs, lib/sidebar-workspaces.mjs ports). Pure.
 */
object SidebarModel {

    /** Encodes defaults (`mode: "headless"` must reach the helpers) and omits nulls (JS `undefined`). */
    private val SidebarJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun liveJs(session: AgentSession): JsObj =
        JsCodec.fromJson(SidebarJson.encodeToJsonElement(AgentSession.serializer(), session)) as JsObj

    fun historyJs(history: HistorySession): JsObj =
        JsCodec.fromJson(SidebarJson.encodeToJsonElement(HistorySession.serializer(), history)) as JsObj

    private fun roots(workspaces: List<String>): JsArr = JsArr.of(workspaces.map { JsStr(it) })

    /** lib/sidebar-workspaces.mjs:81 — the deepest listed workspace containing [cwd]. */
    fun workspaceGroupFor(cwd: String, workspaces: List<String>): String? =
        SidebarWorkspaces.workspaceGroupFor(JsStr(cwd), roots(workspaces))

    /** dashboard.tsx:564 — `showEndedSessions || status !== "exited"`. */
    fun visibleSessions(sessions: List<AgentSession>, showEnded: Boolean): List<AgentSession> =
        sessions.filter { showEnded || it.status != "exited" }

    /** dashboard.tsx:349 + 368: the kept list (server wins once it has one), then the current one. */
    fun pinnedWorkspaces(serverPinned: List<String>?, localPinned: List<String>): List<String> =
        SidebarWorkspaces.effectivePinnedWorkspaces(
            serverPinned?.let { list -> JsArr.of(list.map { JsStr(it) }) },
            JsArr.of(localPinned.map { JsStr(it) }),
        )

    fun sidebarWorkspaces(pinned: List<String>, current: String?): List<String> =
        SidebarWorkspaces.sidebarWorkspaceList(JsArr.of(pinned.map { JsStr(it) }), current?.let(::JsStr))

    /**
     * dashboard.tsx:567-649 — every block's rows: live sessions deduped per native chat, linked to
     * their discovered history, digest/unread from the merged lastSeen, sorted by recency, then
     * the server's explicit order.
     */
    fun sidebarSessions(
        visible: List<AgentSession>,
        historiesByCwd: Map<String, List<HistorySession>>,
        workspaces: List<String>,
        lastSeen: Map<String, Long>,
        sessionOrders: Map<String, List<String>>,
        sort: SidebarSort,
        activeId: String?,
        openingHistoryId: String?,
        collator: JsCollator,
        pendingSessionId: String? = null,
    ): List<SidebarEntry> {
        val rows = ArrayList<SidebarEntry>()
        for (workspace in workspaces) {
            val liveRows = visible.filter { workspaceGroupFor(it.cwd, workspaces) == workspace }
            val histories = historiesByCwd[workspace].orEmpty().filter { workspaceGroupFor(it.cwd, workspaces) == workspace }
            rows += buildBlock(workspace, liveRows, histories, lastSeen, sessionOrders[workspace].orEmpty(), sort, activeId, openingHistoryId, pendingSessionId, collator)
        }
        return rows
    }

    private fun buildBlock(
        workspace: String,
        liveRows: List<AgentSession>,
        histories: List<HistorySession>,
        lastSeen: Map<String, Long>,
        order: List<String>,
        sort: SidebarSort,
        activeId: String?,
        openingHistoryId: String?,
        pendingSessionId: String?,
        collator: JsCollator,
    ): List<SidebarEntry> {
        // dashboard.tsx:578 — one row per native chat, keeping the most recently active one.
        val deduped = LinkedHashMap<String, AgentSession>()
        for (session in liveRows) {
            val chatKey = session.resumeTargetNativeId?.takeIf { it.isNotEmpty() }
                ?: session.nativeSessionId?.takeIf { it.isNotEmpty() }
                ?: "id:${session.id}"
            val kept = deduped[chatKey]
            if (kept == null || session.updatedAt > kept.updatedAt) deduped[chatKey] = session
        }
        val uniqueLive = deduped.values.toList()
        val linkedLive = HashSet<String>()
        val liveByHistory = LinkedHashMap<String, AgentSession>()
        for (session in uniqueLive) session.historyId?.takeIf { it.isNotEmpty() }?.let { liveByHistory[it] = session }

        val entries = ArrayList<SidebarEntry>()
        for (history in histories) {
            val live = liveByHistory[history.historyId]
            if (live != null) linkedLive += live.id
            val lastSeenAt = max(lastSeen[history.historyId] ?: 0L, history.lastSeenAt ?: 0L)
            val isOpen = history.historyId == openingHistoryId ||
                (live != null && (live.id == activeId || live.id == pendingSessionId))
            // The "changed while away" digest only on a not-open row still unseen locally.
            val digest = history.digest?.takeIf { !isOpen && lastSeenAt < history.updatedAt }
            // A live row that finished while another was open reads unread before discovery catches up.
            val unread = !isOpen &&
                (live == null || live.status == "ready" || live.status == "exited") &&
                lastSeenAt < (live?.updatedAt ?: history.updatedAt)
            val historyJs = historyJs(history)
            val js = JsObj.of(
                "key" to js("history:${history.historyId}"),
                "workspace" to js(workspace),
                "createdAt" to js(history.createdAt ?: history.updatedAt),
                "updatedAt" to js(live?.updatedAt?.takeIf { it != 0L } ?: history.updatedAt),
                "lastMessageAt" to js(live?.lastMessageAt?.takeIf { it != 0L } ?: history.updatedAt),
                "live" to live?.let(::liveJs),
                "history" to historyJs,
                "digest" to digest?.let { historyJs["digest"] },
                "unread" to js(unread),
            )
            entries += SidebarEntry(js, live, history)
        }
        for (live in uniqueLive) {
            if (live.id in linkedLive) continue
            val js = JsObj.of(
                "key" to js("live:${live.id}"),
                "workspace" to js(workspace),
                "createdAt" to js(live.startedAt),
                "updatedAt" to js(live.updatedAt),
                "lastMessageAt" to live.lastMessageAt?.let(::js),
                "live" to liveJs(live),
            )
            entries += SidebarEntry(js, live, null)
        }
        val byKey = entries.associateBy { it.key }
        val byRecency = SidebarOrder.sortSidebarEntries(JsArr.of(entries.map { it.js }), js(sort.id), collator)
        val ordered = SidebarOrder.applySidebarOrder(byRecency, if (order.isEmpty()) JsArr.EMPTY else JsArr.of(order.map { JsStr(it) }))
        return ordered.map { byKey.getValue((it["key"] as JsStr).value) }
    }

    /**
     * dashboard.tsx:846-893 — the harness narrowing plus the instant title filter. Content hits
     * (the debounced `search`) are T5.3's; rows keep their per-block order.
     */
    fun filteredSessions(rows: List<SidebarEntry>, query: String, harness: String?): List<SidebarEntry> {
        val byHarness = if (harness != null) rows.filter { (it.live?.provider ?: it.history?.provider) == harness } else rows
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return byHarness
        return byHarness.filter { entry ->
            val name = (entry.live?.name?.takeIf { it.isNotEmpty() } ?: entry.history?.name ?: "").lowercase(Locale.ROOT)
            name.contains(q)
        }
    }

    /** dashboard.tsx:897-910 — from the FULL session list, so a folded block still flags "needs you". */
    fun projectActivity(sessions: List<AgentSession>, workspaces: List<String>): Map<String, WorkspaceActivity> {
        val out = LinkedHashMap<String, WorkspaceActivity>()
        for (session in sessions) {
            if (session.status == "exited") continue
            val workspace = workspaceGroupFor(session.cwd, workspaces) ?: continue
            val entry = out[workspace] ?: WorkspaceActivity(0, 0)
            out[workspace] = when (session.status) {
                "waiting" -> entry.copy(waiting = entry.waiting + 1)
                "active" -> entry.copy(active = entry.active + 1)
                else -> entry
            }
        }
        return out
    }

    /** session-sidebar.tsx:171 — never throws for a live row missing updatedAt. */
    fun rowUpdatedAt(entry: SidebarEntry): Long =
        entry.live?.updatedAt ?: entry.history?.updatedAt ?: entry.live?.startedAt ?: 0L

    /** session-sidebar.tsx:1074-1078 — the sub-path of a row living below its block's top folder. */
    fun rowLocation(entry: SidebarEntry, workspace: String): String? {
        val cwd = entry.live?.cwd?.takeIf { it.isNotEmpty() } ?: entry.history?.cwd ?: ""
        return if (cwd.isNotEmpty() && cwd != workspace) Format.compactPath(cwd, workspace) else null
    }
}
