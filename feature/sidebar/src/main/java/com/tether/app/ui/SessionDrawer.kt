package com.tether.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.SessionSidebar
import com.tether.app.ui.sidebar.SidebarController
import com.tether.app.ui.sidebar.SidebarModel
import com.tether.app.ui.sidebar.SidebarState
import com.tether.app.ui.sidebar.sidebarCollator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    val client = vm.client
    val scope = rememberCoroutineScope()
    val preferences by prefs.preferences.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default)
    val connection by client.connection.collectAsStateWithLifecycle()
    val historiesByCwd by client.historiesByCwd.collectAsStateWithLifecycle()
    val sessionOrders by client.sessionOrders.collectAsStateWithLifecycle()
    val remoteSeen by client.remoteSeen.collectAsStateWithLifecycle()
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val providers by client.providers.collectAsStateWithLifecycle()
    val directories by client.directories.collectAsStateWithLifecycle()
    // T5.3: the debounced workspace content search (use-tether.ts searchResults).
    val contentHits by client.searchResults.collectAsStateWithLifecycle()
    val pickedWorkspace by vm.currentWorkspace.collectAsStateWithLifecycle()
    val connected = connection == ConnectionState.Connected
    val syncStates by client.syncStates.collectAsStateWithLifecycle()
    val consentOrigin by client.consentOrigin.collectAsStateWithLifecycle()

    val latestPrefs by rememberUpdatedState(preferences)
    val controller = remember(vm, prefs) {
        SidebarController(
            client = client,
            readPreferences = { latestPrefs },
            updatePreferences = { transform -> scope.launch { prefs.updatePreferences(transform) } },
            selectWorkspace = vm::selectWorkspace,
        )
    }

    // T5.2: the resumed row stays highlighted until its `created` reply (TetherViewModel).
    val openingHistoryId by vm.openingHistoryId.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var harness by remember { mutableStateOf<String?>(null) }
    var providerPicker by remember { mutableStateOf(false) }
    var folderPicker by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    // Relative times tick while the list is composed (the web re-renders on every broadcast).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val current = controller.currentWorkspace(pickedWorkspace, preferences, workspaceRoot)
    val pinned = controller.pinnedWorkspaces(serverSettings, preferences)
    val workspaces = SidebarModel.sidebarWorkspaces(pinned, current)
    val collator = remember { sidebarCollator() }
    val rows = SidebarModel.sidebarSessions(
        visible = SidebarModel.visibleSessions(sessions, preferences.showEndedSessions),
        historiesByCwd = historiesByCwd,
        workspaces = workspaces,
        lastSeen = preferences.lastSeenSessions,
        sessionOrders = sessionOrders,
        sort = preferences.sidebarSort,
        activeId = selectedId,
        openingHistoryId = openingHistoryId,
        collator = collator,
    )
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
    // dashboard.tsx:966-973 — the visible, settled session's latest report is on screen.
    val active = sessions.firstOrNull { it.id == selectedId }
    LaunchedEffect(active?.historyId, active?.status, active?.updatedAt) {
        active?.let { controller.onActiveSettled(it) }
    }
    // T4.4, dashboard.tsx:1058-1066: a session opened by a link may live in a project no block
    // owns; its block (or its own folder) becomes the current workspace, so it is listed.
    val latestSessions by rememberUpdatedState(sessions)
    val latestWorkspaces by rememberUpdatedState(workspaces)
    val latestCurrent by rememberUpdatedState(current)
    LaunchedEffect(controller) {
        vm.openRequests.collect { id ->
            latestSessions.firstOrNull { it.id == id }?.let { controller.focusWorkspaceFor(it.cwd, latestWorkspaces, latestCurrent) }
        }
    }

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
            onNewSession = { providerPicker = true },
            onBrowseWorkspace = {
                folderPicker = true
                client.browse(current ?: workspaceRoot)
            },
            onOpenSettings = { settingsOpen = true },
            onOpenGlobalSearch = vm::openGlobalSearch,
        ),
    )

    // Outside the sidebar's token scope: the web raises these outside `.session-sidebar`.
    if (providerPicker) {
        ProviderPickerDialog(providers, onDismiss = { providerPicker = false }) { provider ->
            providerPicker = false
            vm.createSession(provider)
        }
    }
    if (folderPicker) {
        FolderPickerDialog(
            directories = directories,
            current = current,
            onDismiss = { folderPicker = false },
            onBrowse = { client.browse(it) },
            onChoose = { cwd ->
                folderPicker = false
                controller.chooseWorkspace(cwd, pinned, current)
            },
        )
    }
    if (settingsOpen) InterimSettingsDialog(prefs, onDismiss = { settingsOpen = false })
}

/** dashboard.tsx:842 — the sidebar filter's content search waits this long after the last keystroke. */
internal const val SEARCH_DEBOUNCE_MS = 250L
