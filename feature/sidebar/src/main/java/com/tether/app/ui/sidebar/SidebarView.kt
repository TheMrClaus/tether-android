package com.tether.app.ui.sidebar

import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.SidebarOrder
import com.tether.app.protocol.helpers.SidebarWorkspaces
import com.tether.app.protocol.helpers.get
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.helpers.SessionSidebar as Web

/** Everything `<SessionSidebar>` receives from the dashboard (session-sidebar.tsx:475-541). */
data class SidebarState(
    val connected: Boolean,
    val currentWorkspace: String,
    val workspaceRoot: String,
    val workspaces: List<String>,
    val pinned: List<String>,
    val collapsed: List<String>,
    val activity: Map<String, WorkspaceActivity>,
    val sidebarSessions: List<SidebarEntry>,
    val filteredSessions: List<SidebarEntry>,
    val query: String = "",
    val harness: String? = null,
    val activeOnly: Boolean = false,
    val unreadOnly: Boolean = false,
    val hideAgentRuns: Boolean = true,
    val sort: com.tether.app.ui.prefs.SidebarSort = com.tether.app.ui.prefs.SidebarSort.Created,
    val activeSessionId: String? = null,
    val openingHistoryId: String? = null,
    val sessionOrders: Map<String, List<String>> = emptyMap(),
    /** Wall-clock "now" for the relative times (the web's Date.now()). */
    val now: Long = System.currentTimeMillis(),
    val scheduledActionCount: Int = 0,
    /** T9.3: the Scheduled destination is on screen (session-sidebar.tsx `scheduledActionsActive`). */
    val scheduledActionsActive: Boolean = false,
    /** T13.2: the client's per-session freshness (rows mark it only while not [connected]). */
    val syncStates: Map<String, com.tether.app.client.SessionSync> = emptyMap(),
    /**
     * T13.2 r3: the server origin the rows are drawn for ([com.tether.app.client.TetherClient.consentOrigin]).
     * A row's End session carries the origin captured when it was armed (or swiped open).
     */
    val origin: String? = null,
    /** v141: the client's latest `archive-stale-result`, which steps the "Archive idle sessions" dialog. */
    val archiveStale: com.tether.app.client.ArchiveStaleReply? = null,
)

/** One workspace block as rendered (session-sidebar.tsx:997-1190). */
data class BlockView(
    val workspace: String,
    val index: Int,
    val name: String,
    val path: String?,
    val pinned: Boolean,
    val isCurrent: Boolean,
    val collapsed: Boolean,
    val activity: WorkspaceActivity?,
    val rows: List<SidebarEntry>,
    val childrenByKey: Map<String, List<SidebarEntry>>,
    /** HG (tth-0bs): older members of each folded handover chain, newest first, keyed by the chain's HEAD key. */
    val olderByHeadKey: Map<String, List<SidebarEntry>> = emptyMap(),
    /** Per chain head: what its older members (and their delegates) say, for the toggle's auto-open and suffix. */
    val chainSummaries: Map<String, ChainView> = emptyMap(),
    val totalRows: Int,
    val remaining: Int,
    val expanded: Boolean,
    val hiddenRuns: Int,
    val manualOrder: Boolean,
    /** Issue #244: rows idle beyond a week, folded into the collapsed "Older" row (0 = no band). */
    val olderCount: Int = 0,
    /** The band is expanded: its rows are listed after the recent ones. */
    val olderOpen: Boolean = false,
)

/** One folded chain as C2 draws it. [rootKey] (the last older member) keys the open/seen state, so it survives a new head. */
data class ChainView(
    val headKey: String,
    val rootKey: String,
    val older: List<SidebarEntry>,
    val reasonKeys: List<String>,
    val suffix: SidebarOrder.ChainSuffix?,
) {
    val label: String get() = SidebarOrder.chainToggleLabel(older.size)
    val suffixText: String get() = SidebarOrder.chainSuffixText(suffix)
}

data class SidebarView(
    val blocks: List<BlockView>,
    val archived: List<SidebarEntry>,
    val filtering: Boolean,
    val querying: Boolean,
    val attentionOn: Boolean,
    val dragEnabled: Boolean,
    val openCount: Int,
    val anyVisible: Boolean,
    val delegateChildKeys: Set<String>,
    /** HG: per workspace, the chains' key shape (empty while filtering). The drag order holds one key per chain. */
    val chainKeysByWorkspace: Map<String, List<SidebarOrder.HandoverChainKeys>> = emptyMap(),
)

/** A drag in progress previews its block's tentative order (session-sidebar.tsx:613-621). */
data class DragPreview(val key: String, val order: List<String>)

/** session-sidebar.tsx:549-652 + the per-block derivations, over the verified helper ports. */
object SidebarViewModel {

    fun isActiveEntry(state: SidebarState, entry: SidebarEntry): Boolean =
        if (state.openingHistoryId != null) entry.history?.historyId == state.openingHistoryId
        else entry.live != null && entry.live.id == state.activeSessionId

    fun view(
        state: SidebarState,
        visibleCounts: Map<String, Int> = emptyMap(),
        drag: DragPreview? = null,
        olderOpen: Set<String> = emptySet(),
    ): SidebarView {
        val byKey = HashMap<String, SidebarEntry>()
        state.filteredSessions.forEach { byKey[it.key] = it }
        state.sidebarSessions.forEach { byKey.putIfAbsent(it.key, it) }
        fun entry(v: JsValue): SidebarEntry = byKey.getValue((v["key"] as JsStr).value)

        val lensed = Web.sidebarRows(
            JsArr.of(state.filteredSessions.map { it.js }),
            state.query,
            state.activeOnly,
            state.unreadOnly,
            state.hideAgentRuns,
            state.harness,
        )
        val querying = state.query.trim().isNotEmpty()
        val attentionOn = state.activeOnly || state.unreadOnly
        val filtering = lensed.filtering
        val grouped = Web.rowsByWorkspace(lensed.visibleRows).toMutableMap()
        val childKeys = Web.delegateChildKeys(state.sidebarSessions.map { it.js })
        val chainKeys = Web.chainKeysByWorkspace(lensed.visibleRows, childKeys, filtering)
        if (drag != null) {
            val dragged = lensed.visibleRows.firstOrNull { (it["key"] as JsStr).value == drag.key }
            val workspace = (dragged?.get("workspace") as? JsStr)?.value ?: ""
            grouped[workspace]?.let { group ->
                val groupByKey = group.associateBy { (it["key"] as JsStr).value }
                // The drag order holds one key per chain (its head); put the older members back behind it for rendering.
                grouped[workspace] = SidebarOrder.expandChainOrder(drag.order, chainKeys[workspace].orEmpty()).mapNotNull { groupByKey[it] }
            }
        }
        val groupedRows = Web.groupedByWorkspace(grouped, filtering, state.sessionOrders)

        val blocks = ArrayList<BlockView>()
        state.workspaces.forEachIndexed { index, workspace ->
            val grouping = groupedRows[workspace] ?: Web.GroupedRows(emptyList(), emptyMap())
            val rows = grouping.rows
            val isCurrent = workspace == state.currentWorkspace
            val hiddenRuns = lensed.hiddenByWorkspace[JsStr(workspace)] ?: 0
            if (filtering && rows.isEmpty() && !isCurrent && hiddenRuns == 0) return@forEachIndexed
            val name = Format.projectName(workspace)
            val path = Format.compactPath(workspace, state.workspaceRoot)
            val showPath = path != "~/$name" && path != "~"
            // Issue #244 (session-sidebar.tsx:1028-1042): fold rows idle beyond a week. Never while a
            // lens/query is on, and never a pinned / working / waiting / unread / selected row (or a
            // parent with a working / selected delegate child). Expanded, the older rows follow the recent ones.
            val band = if (!filtering) {
                SidebarWorkspaces.splitRecentOlder(
                    rows,
                    state.now,
                    SidebarWorkspaces.SIDEBAR_RECENT_MS,
                    { row -> ((row["lastMessageAt"] as? JsNum) ?: (row["updatedAt"] as? JsNum))?.value },
                ) { row ->
                    val e = entry(row)
                    e.live?.pinned == true || Web.hasWorkInProgress(row) || Web.hasUnseenWork(row) || isActiveEntry(state, e) ||
                        grouping.childrenByKey[e.key].orEmpty().any { Web.hasWorkInProgress(it) || isActiveEntry(state, entry(it)) } ||
                        chainKeepsRow(state, e.key, grouping, ::entry)
                }
            } else {
                SidebarWorkspaces.RecentOlder(rows, emptyList())
            }
            val olderIsOpen = workspace in olderOpen
            val shownRows = if (olderIsOpen) band.recent + band.older else band.recent
            val page = SidebarWorkspaces.paginateWorkspaceRows(
                JsArr.of(shownRows),
                visibleCounts[workspace]?.let { JsNum(it.toDouble()) },
                Web.WORKSPACE_PAGE_SIZE.toDouble(),
            ) { isActiveEntry(state, entry(it)) || chainHoldsSelected(state, (it["key"] as JsStr).value, grouping, ::entry) }
            blocks += BlockView(
                workspace = workspace,
                index = index,
                name = name,
                path = path.takeIf { showPath },
                pinned = workspace in state.pinned,
                isCurrent = isCurrent,
                collapsed = !filtering && workspace in state.collapsed,
                activity = state.activity[workspace],
                rows = (page["visible"] as JsArr).map(::entry),
                childrenByKey = grouping.childrenByKey.mapValues { (_, list) -> list.map(::entry) },
                olderByHeadKey = grouping.olderByHeadKey.mapValues { (_, list) -> list.map(::entry) },
                chainSummaries = chainSummaries(state, grouping, ::entry),
                totalRows = rows.size,
                remaining = (page["remaining"] as JsNum).value.toInt(),
                expanded = page["expanded"] == com.tether.app.protocol.tree.JsBool.TRUE,
                hiddenRuns = hiddenRuns,
                manualOrder = state.sessionOrders[workspace].orEmpty().isNotEmpty(),
                // session-sidebar.tsx:1193 — the toggle only once every recent row is on screen.
                olderCount = if ((page["remaining"] as JsNum).value.toInt() == 0) band.older.size else 0,
                olderOpen = olderIsOpen,
            )
        }
        return SidebarView(
            blocks = blocks,
            archived = lensed.archivedRows.map(::entry),
            filtering = filtering,
            querying = querying,
            attentionOn = attentionOn,
            dragEnabled = !filtering,
            openCount = state.sidebarSessions.count { !Web.isArchivedLike(it.js) },
            anyVisible = lensed.visibleRows.isNotEmpty() || lensed.hiddenByWorkspace.isNotEmpty(),
            delegateChildKeys = childKeys,
            chainKeysByWorkspace = chainKeys,
        )
    }

    // session-sidebar.tsx:945-952 — a folded chain stays reachable: the FULL keep predicate (pinned, work in progress,
    // unseen work, selected) over every older member and their delegates keeps the group out of the Older band ...
    private fun chainKeepsRow(state: SidebarState, headKey: String, grouping: Web.GroupedRows, entry: (JsValue) -> SidebarEntry): Boolean =
        Web.chainFamily(headKey, grouping).any {
            entry(it).live?.pinned == true || Web.hasWorkInProgress(it) || Web.hasUnseenWork(it) || isActiveEntry(state, entry(it))
        }

    // ... and a selected member keeps it on the page.
    private fun chainHoldsSelected(state: SidebarState, headKey: String, grouping: Web.GroupedRows, entry: (JsValue) -> SidebarEntry): Boolean =
        Web.chainFamily(headKey, grouping).any { isActiveEntry(state, entry(it)) }

    // session-sidebar.tsx:905-923 chainViews — facts are taken over each older member PLUS its delegates.
    private fun chainSummaries(state: SidebarState, grouping: Web.GroupedRows, entry: (JsValue) -> SidebarEntry): Map<String, ChainView> =
        grouping.olderByHeadKey.mapValues { (headKey, older) ->
            val facts = older.map { member ->
                val key = (member["key"] as JsStr).value
                val family = listOf(member) + grouping.childrenByKey[key].orEmpty()
                SidebarOrder.ChainMemberFacts(
                    key = key,
                    selected = family.any { isActiveEntry(state, entry(it)) },
                    waiting = family.any { entry(it).live?.status == "waiting" },
                    active = family.any { entry(it).live?.status == "active" },
                    unread = family.any { Web.hasUnseenWork(it) },
                )
            }
            val summary = SidebarOrder.summarizeChainMembers(facts)
            ChainView(headKey, facts.last().key, older.map(entry), summary.reasonKeys, summary.suffix)
        }

    /** session-sidebar.tsx:761 / :746 — the drag order is one key per chain; the saved order expands each head. */
    fun collapsedDragOrder(base: List<String>, chains: List<SidebarOrder.HandoverChainKeys>): List<String> =
        SidebarOrder.collapseChainOrder(base, chains)

    fun committedOrder(order: List<String>, chains: List<SidebarOrder.HandoverChainKeys>): List<String> =
        SidebarOrder.expandChainOrder(order, chains)

    /**
     * session-sidebar.tsx:705-707 — the explicit order covers the block's FULL row list (rows
     * behind "Show more" keep their place), delegate children excluded.
     */
    fun dragBaseOrder(state: SidebarState, workspace: String, childKeys: Set<String>): List<String> =
        state.sidebarSessions.filter { it.workspace == workspace && it.key !in childKeys }.map { it.key }

    /** session-sidebar.tsx:669 — lib/sidebar-order.mjs moveSidebarEntry over `{ key }` rows. */
    fun move(order: List<String>, dragged: String, target: String): List<String> =
        SidebarOrder.moveSidebarEntry(
            JsArr.of(order.map { com.tether.app.protocol.tree.JsObj.of("key" to JsStr(it)) }),
            JsStr(dragged),
            JsStr(target),
        ).map { (it as JsStr).value }

    /** session-sidebar.tsx:1214-1216 — the archived row's name ("" when both are absent). */
    fun archivedName(entry: SidebarEntry): String =
        entry.live?.name?.takeIf { it.isNotEmpty() } ?: entry.history?.name ?: ""
}
