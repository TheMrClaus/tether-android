package com.tether.app.ui

import com.tether.app.ui.util.RecompositionProbe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.client.serverOrigin
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
import com.tether.app.ui.settings.SettingsDialog
import com.tether.app.ui.sidebar.SessionSidebar
import com.tether.app.ui.sidebar.SidebarController
import com.tether.app.ui.sidebar.SidebarModel
import com.tether.app.ui.sidebar.SidebarState
import com.tether.app.ui.sidebar.sidebarCollator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * T5.1: the session list the shell hosts — the phone drawer's content (T4.1's `drawer` slot) and
 * the expanded layout's sidebar column (T4.2). One composable for both; the host owns the
 * container. Wires [SessionSidebar] (components/session-sidebar.tsx) to the view model, the
 * client's sidebar sync and the preferences exactly as components/dashboard.tsx does
 * ([SidebarController] holds the rules: mark-seen, order, pinned workspaces, watched discovery).
 */
@Composable
fun SessionDrawer(
    vm: TetherViewModel,
    prefs: UiPrefs,
    sessions: List<AgentSession>,
    selectedId: String?,
    workspaceRoot: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * T15.4: the host's Settings (the top bar's and the rail footer's open the same one). Null: the
     * drawer raises its own settings sheet.
     */
    onOpenSettings: (() -> Unit)? = null,
    /**
     * T15.2 (OVERVIEW_STUDIO_PLAN.md §4, dashboard.tsx:725 `activeSession = view === "sessions" ? …`):
     * false while the selected session is NOT on screen (another view is showing). Its settled
     * report is then not seen, so it is never marked seen.
     */
    selectedOnScreen: Boolean = true,
    /** T9.3 (dashboard.tsx:387 openScheduledActions): the rail's Scheduled actions key. Null: unavailable. */
    onOpenScheduledActions: (() -> Unit)? = null,
    /** T9.3: the Scheduled destination is on screen (the key reads `is-active`). */
    scheduledActionsActive: Boolean = false,
    /** The wall clock behind the relative times and the "Older" band's 7-day horizon (the web's Date.now()). */
    clock: () -> Long = System::currentTimeMillis,
) {
    RecompositionProbe("SessionDrawer")
    val client = vm.client
    val scope = rememberCoroutineScope()
    // ta-coik.47: the folded blocks and seen stamps are the server's (the web's localStorage is per origin).
    val stored by remember(prefs, client) { prefs.preferencesFor(client.serverUrl) }.collectAsStateWithLifecycle(initialValue = null)
    val preferences = stored ?: TetherPreferences.Default
    val connection by client.connection.collectAsStateWithLifecycle()
    val historiesByCwd by client.historiesByCwd.collectAsStateWithLifecycle()
    val sessionOrders by client.sessionOrders.collectAsStateWithLifecycle()
    val remoteSeen by client.remoteSeen.collectAsStateWithLifecycle()
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val archiveStale by client.archiveStale.collectAsStateWithLifecycle()
    val directories by client.directories.collectAsStateWithLifecycle()
    // T5.3: the debounced workspace content search (use-tether.ts searchResults).
    val contentHits by client.searchResults.collectAsStateWithLifecycle()
    val pickedWorkspace by vm.currentWorkspace.collectAsStateWithLifecycle()
    val connected = connection == ConnectionState.Connected
    val syncStates by client.syncStates.collectAsStateWithLifecycle()
    val consentOrigin by client.consentOrigin.collectAsStateWithLifecycle()
    // T9.3 dashboard.tsx:1542: the key's count — the schedules not completed plus the continuations.
    val scheduled by client.scheduledActions.collectAsStateWithLifecycle()

    val latestPrefs by rememberUpdatedState(preferences)
    val controller = remember(vm, prefs) {
        SidebarController(
            client = client,
            readPreferences = { latestPrefs },
            updatePreferences = { transform -> scope.launchPreferenceWrite { prefs.updatePreferencesFor(serverOrigin(client.serverUrl.value), transform) } },
            selectWorkspace = vm::selectWorkspace,
        )
    }

    // T5.2: the resumed row stays highlighted until its `created` reply (TetherViewModel).
    val openingHistoryId by vm.openingHistoryId.collectAsStateWithLifecycle()
    // ta-coik.42 (dashboard.tsx 90fbb9f :638-639): a history row is open when its live session is the
    // pick or the pending target.
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val pendingSessionId by vm.pendingSessionId.collectAsStateWithLifecycle()
    // ta-coik.20: the typed search and the harness filter survive a rotation.
    var query by rememberSaveable { mutableStateOf("") }
    var harness by rememberSaveable { mutableStateOf<String?>(null) }
    var folderPicker by remember { mutableStateOf(false) }
    // Saveable: a rotation recreates the activity, and an open Settings (its tab and draft) comes back.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Relative times tick while the list is composed (the web re-renders on every broadcast).
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = clock()
        }
    }

    val current = controller.currentWorkspace(pickedWorkspace, preferences, workspaceRoot)
    val pinned = controller.pinnedWorkspaces(serverSettings, preferences)
    val workspaces = SidebarModel.sidebarWorkspaces(pinned, current)
    val collator = remember { sidebarCollator() }
    // ta-2vm7: the rows are rebuilt only when what they are built from changes. Every other recomposition
    // (the 30 s tick, a keystroke in the filter, the connection) used to rebuild every block, a JsObj per
    // row, with the heap already full.
    val rows = remember(
        sessions, preferences.showEndedSessions, historiesByCwd, workspaces, preferences.lastSeenSessions,
        sessionOrders, preferences.sidebarSort, activeId, openingHistoryId, pendingSessionId, collator,
    ) {
        SidebarModel.sidebarSessions(
            visible = SidebarModel.visibleSessions(sessions, preferences.showEndedSessions),
            historiesByCwd = historiesByCwd,
            workspaces = workspaces,
            lastSeen = preferences.lastSeenSessions,
            sessionOrders = sessionOrders,
            sort = preferences.sidebarSort,
            activeId = activeId,
            openingHistoryId = openingHistoryId,
            pendingSessionId = pendingSessionId,
            collator = collator,
        )
    }
    val state = SidebarState(
        connected = connected,
        currentWorkspace = current.orEmpty(),
        workspaceRoot = workspaceRoot.orEmpty(),
        workspaces = workspaces,
        pinned = pinned,
        collapsed = preferences.collapsedWorkspaces,
        activity = SidebarModel.projectActivity(sessions, workspaces),
        sidebarSessions = rows,
        filteredSessions = SidebarModel.filteredSessions(rows, query, harness, contentHits, workspaces, current, preferences.sidebarSort, collator),
        query = query,
        harness = harness,
        activeOnly = preferences.sidebarActiveOnly,
        unreadOnly = preferences.sidebarUnreadOnly,
        hideAgentRuns = preferences.sidebarHideAgentRuns,
        sort = preferences.sidebarSort,
        activeSessionId = selectedId,
        openingHistoryId = openingHistoryId,
        sessionOrders = sessionOrders,
        now = now,
        syncStates = syncStates,
        origin = consentOrigin,
        scheduledActionCount = scheduled.schedules.count { it.status != "completed" } + scheduled.continuations.size,
        scheduledActionsActive = scheduledActionsActive,
        archiveStale = archiveStale,
    )

    // T5.3 dashboard.tsx:835-844 — debounce the typed filter into a server-side content search;
    // the instant title filter above stays live on every keystroke and the hits merge in later.
    LaunchedEffect(query, current) {
        val cwd = current?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        client.search(cwd, query.trim())
    }
    // dashboard.tsx:142-144 — the server settings (pinned workspaces) on every connection.
    LaunchedEffect(connected) { if (connected) client.requestServerSettings() }
    // dashboard.tsx:354-367 — adopt the server's kept list, or seed it once from this device.
    LaunchedEffect(serverSettings, preferences.pinnedProjects) { controller.syncPinned(serverSettings, preferences) }
    // use-tether.ts:869-873 + dashboard.tsx:95-105 — another device saw a conversation.
    LaunchedEffect(remoteSeen) { controller.applyRemoteSeen(remoteSeen) }
    // use-tether.ts:771-782 / 1359-1384 — keep every block discovered.
    LaunchedEffect(connected, workspaces, current) { controller.watch(connected, workspaces, current) }
    // use-tether.ts:1316-1330 — the fallback rediscover poll while connected.
    LaunchedEffect(connected, workspaces, current) {
        while (connected) {
            delay(SidebarController.REDISCOVER_INTERVAL_MS)
            controller.rediscover(workspaces, current)
        }
    }
    // dashboard.tsx:966-973 — the visible, settled session's latest report is on screen. ta-coik.51:
    // `activeSession` is looked up among the LISTED sessions (:709-717, :725 `visibleSessions`), so an
    // ended target with "Show ended sessions" off is not on screen and is not marked seen; nothing is
    // until the stored setting is read.
    val active = stored?.let { SidebarModel.visibleSessions(sessions, it.showEndedSessions) }
        ?.firstOrNull { it.id == selectedId }?.takeIf { selectedOnScreen }
    LaunchedEffect(active?.historyId, active?.status, active?.updatedAt) {
        active?.let { controller.onActiveSettled(it) }
    }
    // T4.4: a session opened by a link may live in a project no block owns; ta-coik.42 moved making its
    // block current to the shell's pending-target effect (WebSelectionEffects, dashboard.tsx :1153-1160).

    SessionSidebar(
        state = state,
        modifier = modifier,
        actions = controller.actions(
            workspaces = workspaces,
            current = current,
            pinned = pinned,
            sessions = sessions,
            sessionOrders = sessionOrders,
            onClose = onClose,
            onSelect = onSelect,
            onResume = vm::resumeHistory,
            onQuery = { query = it },
            onHarness = { harness = it },
            // ta-abm: the new-session sheet is the shell's (dashboard.tsx openDraft); the draft is the view model's.
            onNewSession = vm::openDraft,
            onBrowseWorkspace = {
                folderPicker = true
                client.browse(current ?: workspaceRoot)
            },
            onOpenSettings = onOpenSettings ?: { settingsOpen = true },
            onOpenGlobalSearch = vm::openGlobalSearch,
        ).copy(onOpenScheduledActions = onOpenScheduledActions),
    )

    // Outside the sidebar's token scope: the web raises these outside `.session-sidebar`.
    if (folderPicker) {
        WorkspaceFolderPicker(client, directories, current, pinned, controller, onDismiss = { folderPicker = false })
    }
    // T10.1: the Settings dialog (feature/settings); "Use current" takes this list's current workspace.
    if (settingsOpen) SettingsDialog(client, prefs, currentWorkspace = current.orEmpty(), onDismiss = { settingsOpen = false })
}

/**
 * The drawer's folder picker and [WorkspacePickerHost]'s (dashboard.tsx 90fbb9f :1849): choosing a
 * folder pins it and makes it current; the picker stays open showing "Opening…" / "couldn't open —
 * retrying" until the server confirms the workspace ([TetherClient.workspaceSelect]), and offers
 * "Create a new folder" and the "couldn't load this folder" state.
 */
@Composable
private fun WorkspaceFolderPicker(
    client: com.tether.app.client.TetherClient,
    directories: com.tether.app.protocol.model.DirectoryListing?,
    current: String?,
    pinned: List<String>,
    controller: SidebarController,
    onDismiss: () -> Unit,
) {
    val browseStatus by client.browseStatus.collectAsStateWithLifecycle()
    FolderPickerDialog(
        directories = directories,
        current = current,
        onDismiss = onDismiss,
        onBrowse = { client.browse(it) },
        onChoose = { cwd -> controller.chooseWorkspace(cwd, pinned, current) },
        onCreateFolder = { cwd, name -> client.createFolder(cwd, name) },
        browseStatus = browseStatus,
        selectStatus = client.workspaceSelect,
    )
}

/**
 * ta-3e7: the workspace folder picker for a surface outside the drawer — the Studio welcome's
 * "Open workspace" (dashboard.tsx 1727: `browseWorkspace(currentWorkspace || workspaceRoot)`, then
 * the one FolderPickerDialog the sidebar's "Add workspace" also opens). Composed while open; it
 * lists the current workspace (else the server's root) as it opens, and a chosen folder is pinned
 * and made current exactly as from the drawer. The host composes it at shell level, so it opens
 * whether or not the rail is showing.
 *
 * [scope] runs the preference write (the pin and the unfold) and must OUTLIVE this picker: choosing
 * dismisses it before the write has run, so a scope of its own would cancel the write with it
 * (ta-3e7 r2). The shell passes its own, as the drawer's controller writes on the drawer's.
 */
@Composable
fun WorkspacePickerHost(
    vm: TetherViewModel,
    prefs: UiPrefs,
    workspaceRoot: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    val client = vm.client
    val preferences by remember(prefs, client) { prefs.preferencesFor(client.serverUrl) }.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default)
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val directories by client.directories.collectAsStateWithLifecycle()
    val pickedWorkspace by vm.currentWorkspace.collectAsStateWithLifecycle()
    val latestPrefs by rememberUpdatedState(preferences)
    val controller = remember(vm, prefs, scope) {
        SidebarController(
            client = client,
            readPreferences = { latestPrefs },
            updatePreferences = { transform -> scope.launchPreferenceWrite { prefs.updatePreferencesFor(serverOrigin(client.serverUrl.value), transform) } },
            selectWorkspace = vm::selectWorkspace,
        )
    }
    val current = controller.currentWorkspace(pickedWorkspace, preferences, workspaceRoot)
    val pinned = controller.pinnedWorkspaces(serverSettings, preferences)
    // The stored preferences, not the first frame's defaults, decide which folder is current.
    LaunchedEffect(Unit) {
        val stored = prefs.preferencesFor(client.serverUrl).first()
        client.browse(controller.currentWorkspace(vm.currentWorkspace.value, stored, workspaceRoot) ?: workspaceRoot)
    }
    WorkspaceFolderPicker(client, directories, current, pinned, controller, onDismiss)
}

/** dashboard.tsx:842 — the sidebar filter's content search waits this long after the last keystroke. */
internal const val SEARCH_DEBOUNCE_MS = 250L
