package com.tether.app.ui.sidebar

import android.icu.text.Collator
import android.icu.util.ULocale
import com.tether.app.client.TetherClient
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.helpers.JsCollator
import com.tether.app.protocol.helpers.SidebarWorkspaces
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.prefs.TetherPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * `localeCompare` as the browser runs it: ICU's default collator for the device locale, tertiary
 * strength (the same collator as feature/chat's IcuJsCollator; see protocol.helpers.JsCollator).
 */
fun sidebarCollator(locale: Locale = Locale.getDefault()): JsCollator {
    val collator = Collator.getInstance(ULocale.forLocale(locale)).apply { strength = Collator.TERTIARY }
    return JsCollator { a, b -> synchronized(collator) { collator.compare(a, b) } }
}

/**
 * T5.1: the dashboard.tsx / use-tether.ts rules behind the sidebar, over [TetherClient] and the
 * `tether.preferences.v1` model. Every method names the web lines it mirrors; the frames are
 * the client's (RealTetherClient → SidebarSync).
 */
class SidebarController(
    private val client: TetherClient,
    private val readPreferences: () -> TetherPreferences,
    private val updatePreferences: ((TetherPreferences) -> TetherPreferences) -> Unit,
    private val selectWorkspace: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var watched: List<String> = emptyList()
    private var watchedConnected = false

    /**
     * use-tether.ts:770 — the operator's pick, else this device's last-opened / default workspace
     * (dashboard.tsx:140), else the server's root.
     */
    fun currentWorkspace(picked: String?, prefs: TetherPreferences, workspaceRoot: String?): String? =
        resolveCurrentWorkspace(picked, prefs, workspaceRoot)

    /** v128 `pinnedWorkspaces` from the server-settings frame: a string array, or null ("never written"). */
    fun serverPinned(settings: ServerMessage.ServerSettings?): List<String>? = serverPinnedOf(settings)

    /** dashboard.tsx:349 — the server's list once it has one, else this device's. */
    fun pinnedWorkspaces(settings: ServerMessage.ServerSettings?, prefs: TetherPreferences): List<String> =
        pinnedWorkspacesOf(settings, prefs)

    /**
     * dashboard.tsx:354-367: adopt the server's list when it differs (a workspace pinned on another
     * device appears here); seed the server ONCE from this device when it has never held one.
     */
    fun syncPinned(settings: ServerMessage.ServerSettings?, prefs: TetherPreferences) {
        if (settings == null) return
        val server = serverPinned(settings)
        if (server != null) {
            if (server != prefs.pinnedProjects) updatePreferences { it.copy(pinnedProjects = server) }
            return
        }
        if (prefs.pinnedProjects.isEmpty()) return
        client.setPinnedWorkspaces(prefs.pinnedProjects)
    }

    /**
     * dashboard.tsx:328-338 — stamp a conversation seen "now": locally at once, then `mark-seen`
     * so every other device clears its badge. A stamp that does not advance is a no-op.
     */
    fun markSeen(historyId: String?, seenAt: Long = clock()) {
        if (historyId.isNullOrEmpty()) return
        if ((readPreferences().lastSeenSessions[historyId] ?: 0L) >= seenAt) return
        val stamp = maxOf(clock(), seenAt)
        updatePreferences { it.copy(lastSeenSessions = it.lastSeenSessions + (historyId to stamp)) }
        client.markSeen(historyId, stamp)
    }

    /** dashboard.tsx:966-973 — the visible session settled (not mid-turn): its report is seen. */
    fun onActiveSettled(active: AgentSession) {
        val historyId = active.historyId ?: return
        if (active.status == "active") return
        markSeen(historyId, active.updatedAt)
    }

    /** dashboard.tsx:95-105 — a `seen` broadcast raises the local stamp, never lowers it. */
    fun applyRemoteSeen(remote: Map<String, Long>) {
        val local = readPreferences().lastSeenSessions
        val advanced = remote.filter { (id, at) -> (local[id] ?: 0L) < at }
        if (advanced.isEmpty()) return
        updatePreferences { prefs ->
            val merged = prefs.lastSeenSessions.toMutableMap()
            for ((id, at) in advanced) if ((merged[id] ?: 0L) < at) merged[id] = at
            prefs.copy(lastSeenSessions = merged)
        }
    }

    private fun watchSet(workspaces: List<String>, current: String?): List<String> =
        LinkedHashSet<String>().apply {
            current?.takeIf { it.isNotEmpty() }?.let(::add)
            workspaces.filter { it.isNotEmpty() }.forEach(::add)
        }.toList()

    /**
     * use-tether.ts:771-782 (every watched workspace on each connection) and 1359-1384
     * (watchWorkspaces: discover the added roots, or narrow with the current one). `watch` is the
     * complete set on every frame, so the server's per-socket subscription is always whole.
     */
    fun watch(connected: Boolean, workspaces: List<String>, current: String?) {
        val next = watchSet(workspaces, current)
        if (!connected) {
            watchedConnected = false
            return
        }
        val lastSeen = readPreferences().lastSeenSessions
        if (!watchedConnected) {
            watchedConnected = true
            watched = next
            for (cwd in next) client.discoverWorkspace(cwd, lastSeen, next)
            return
        }
        val added = next.filter { it !in watched }
        val removed = watched.filter { it !in next }
        if (added.isEmpty() && removed.isEmpty()) return
        watched = next
        val toDiscover = added.ifEmpty { listOfNotNull(current?.takeIf { it.isNotEmpty() }) }
        for (cwd in toDiscover) client.discoverWorkspace(cwd, lastSeen, next)
    }

    /** use-tether.ts:1316-1330 — the 20s fallback poll over every watched workspace. */
    fun rediscover(workspaces: List<String>, current: String?) {
        val set = watchSet(workspaces, current)
        val lastSeen = readPreferences().lastSeenSessions
        for (cwd in set) client.discoverWorkspace(cwd, lastSeen, set)
    }

    /**
     * Make [cwd] current. The view model's own `discover` subscribes to that folder alone, so the
     * complete watch set is re-declared right after (use-tether.ts selectWorkspace keeps `watch`),
     * T8.2: as the durable workspace intent (use-tether.ts 90fbb9f :1376-1384), redelivered until the
     * server confirms it — the folder picker's "Opening…" state.
     */
    private fun makeCurrent(cwd: String, workspaces: List<String>) {
        selectWorkspace(cwd)
        val set = watchSet(workspaces, cwd)
        watched = set
        client.activateWorkspace(cwd, readPreferences().lastSeenSessions, set)
    }

    /** dashboard.tsx:374-377 — the block that OWNS the folder becomes current. */
    fun focusWorkspaceFor(cwd: String, workspaces: List<String>, current: String?) {
        val root = SidebarModel.workspaceGroupFor(cwd, workspaces) ?: cwd
        if (root.isNotEmpty() && root != current) makeCurrent(root, workspaces)
    }

    /** dashboard.tsx:1119-1131 — "Add workspace": current AND kept, unfolded, written owner-level. */
    fun chooseWorkspace(cwd: String, pinned: List<String>, current: String?) {
        val next = SidebarWorkspaces.pinWorkspace(JsArr.of(pinned.map { JsStr(it) }), JsStr(cwd))
        updatePreferences { it.copy(pinnedProjects = next, collapsedWorkspaces = it.collapsedWorkspaces - cwd) }
        client.setPinnedWorkspaces(next)
        if (cwd != current) makeCurrent(cwd, SidebarModel.sidebarWorkspaces(next, cwd))
    }

    /** dashboard.tsx:1147-1155 — pin/unpin a block, on this device and owner-level. */
    fun togglePinned(cwd: String, pinned: List<String>) {
        if (cwd.isEmpty()) return
        val next = if (cwd in pinned) pinned - cwd else pinned + cwd
        updatePreferences { it.copy(pinnedProjects = next) }
        client.setPinnedWorkspaces(next)
    }

    /** dashboard.tsx:1133-1137. */
    fun toggleCollapsed(cwd: String) = updatePreferences { prefs ->
        val collapsed = prefs.collapsedWorkspaces
        prefs.copy(collapsedWorkspaces = if (cwd in collapsed) collapsed - cwd else collapsed + cwd)
    }

    /** dashboard.tsx:1298-1304 — choosing a sort clears every listed block's manual order. */
    fun changeSort(sort: com.tether.app.ui.prefs.SidebarSort, workspaces: List<String>, orders: Map<String, List<String>>) {
        updatePreferences { it.copy(sidebarSort = sort) }
        for (workspace in workspaces) if (orders[workspace].orEmpty().isNotEmpty()) client.setSessionOrder(workspace, emptyList())
    }

    /** The sidebar's callbacks, wired as dashboard.tsx:1272-1330 wires `<SessionSidebar>`. */
    fun actions(
        workspaces: List<String>,
        current: String?,
        pinned: List<String>,
        sessions: List<AgentSession>,
        sessionOrders: Map<String, List<String>>,
        onClose: () -> Unit,
        onSelect: (String) -> Unit,
        /** T5.2: TetherViewModel.resumeHistory — sends `resume`; true when it went out. */
        onResume: (HistorySession) -> Boolean,
        onQuery: (String) -> Unit,
        onHarness: (String?) -> Unit,
        onNewSession: () -> Unit,
        onBrowseWorkspace: () -> Unit,
        onOpenSettings: () -> Unit,
        /** T5.3: dashboard.tsx:1157 openGlobalSearch (TetherViewModel.openGlobalSearch). */
        onOpenGlobalSearch: (() -> Unit)? = null,
    ): SidebarActions = SidebarActions(
        onCloseDrawer = onClose,
        onNewSession = onNewSession,
        onBrowseWorkspace = onBrowseWorkspace,
        onToggleWorkspaceCollapsed = ::toggleCollapsed,
        onTogglePinnedProject = { togglePinned(it, pinned) },
        // dashboard.tsx:1141-1144 — "+" on a block: that workspace becomes current, then the composer.
        onNewSessionIn = { cwd ->
            if (cwd != current) makeCurrent(cwd, workspaces)
            onNewSession()
        },
        onSessionQueryChange = onQuery,
        onHarnessFilterChange = onHarness,
        onToggleActiveOnly = { updatePreferences { it.copy(sidebarActiveOnly = !it.sidebarActiveOnly) } },
        onToggleUnreadOnly = { updatePreferences { it.copy(sidebarUnreadOnly = !it.sidebarUnreadOnly) } },
        onToggleHideAgentRuns = { updatePreferences { it.copy(sidebarHideAgentRuns = !it.sidebarHideAgentRuns) } },
        onSortModeChange = { changeSort(it, workspaces, sessionOrders) },
        // dashboard.tsx:976-985 — select, follow its block, mark it seen.
        onSelectSession = { id ->
            onSelect(id)
            val target = sessions.firstOrNull { it.id == id }
            if (target != null) focusWorkspaceFor(target.cwd, workspaces, current)
            markSeen(target?.historyId)
        },
        // dashboard.tsx:394-409 — resume a history row into a live session: the block that owns
        // it becomes current; only a sent `resume` marks it seen, opens it and closes the drawer.
        onReopenHistory = { history ->
            focusWorkspaceFor(history.cwd, workspaces, current)
            if (onResume(history)) {
                markSeen(history.historyId)
                onClose()
            }
        },
        // dashboard.tsx:1311-1314 — the row's own two-tap arm (or the swipe) IS the confirmation.
        // T13.2 r2: drawn from the live session LIST (the row is inert offline), not from a copy of
        // the session, so it needs no attach; the client still refuses it without a live link.
        // r3: bound to the server the row was armed for (a switch in between is refused).
        // ta-m7ef (dashboard.tsx 1bf4a465 :1556): an isolated session's end asks about its teardown first.
        onEndSession = { id, drawnFor -> client.endSession(id, drawnFor) },
        onReorderSessions = { workspace, order -> client.setSessionOrder(workspace, order) },
        onResetSessionOrder = { workspace -> client.setSessionOrder(workspace, emptyList()) },
        onOpenSettings = onOpenSettings,
        onCollapse = { updatePreferences { it.copy(sidebarCollapsed = true) } },
        onOpenGlobalSearch = onOpenGlobalSearch,
        // use-tether.ts requestArchiveStale / setArchiveStale(null): the dialog's preview and bounded run.
        onArchiveStale = { mode, days, except, origin -> client.requestArchiveStale(mode, days, except, origin) },
        onClearArchiveStale = client::clearArchiveStale,
    )

    companion object {
        /** use-tether.ts:1316. */
        const val REDISCOVER_INTERVAL_MS = 20_000L

        /** [serverPinned], for a host without a controller (T9.3's Scheduled workspace suggestions). */
        fun serverPinnedOf(settings: ServerMessage.ServerSettings?): List<String>? =
            (settings?.settings?.get("pinnedWorkspaces") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        /** [pinnedWorkspaces], for a host without a controller (dashboard.tsx:420 `pinnedProjects`). */
        fun pinnedWorkspacesOf(settings: ServerMessage.ServerSettings?, prefs: TetherPreferences): List<String> =
            SidebarModel.pinnedWorkspaces(serverPinnedOf(settings), prefs.pinnedProjects)

        /**
         * [currentWorkspace]'s rule on its own, for a host without a controller (T10.1: the shell's
         * Settings, whose "Use current" takes the sidebar's current workspace, as the web's does).
         */
        fun resolveCurrentWorkspace(picked: String?, prefs: TetherPreferences, workspaceRoot: String?): String? =
            picked?.takeIf { it.isNotEmpty() }
                ?: prefs.lastOpenedSession?.cwd?.takeIf { it.isNotEmpty() }
                ?: prefs.defaultWorkspace.takeIf { it.isNotEmpty() }
                ?: workspaceRoot?.takeIf { it.isNotEmpty() }
    }
}
