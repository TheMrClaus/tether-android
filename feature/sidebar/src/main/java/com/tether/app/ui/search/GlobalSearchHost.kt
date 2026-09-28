package com.tether.app.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.protocol.SearchHit
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.SidebarController
import com.tether.app.ui.sidebar.SidebarModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * T5.3: the dashboard's half of the global search (dashboard.tsx:1157-1194, 1701-1711), hosted
 * by the shell beside the sidebar so it opens from anywhere (the sidebar key, Ctrl+Shift+F). The
 * form and the open flag live in [TetherViewModel] (a rotation keeps both); the results are the
 * client's ([com.tether.app.client.TetherClient.globalSearchResults]). Opening a result follows
 * the web's `openGlobalHit`: the conversation's live session is selected, otherwise the
 * conversation is resumed (the sidebar's `reopen`), and either way the query arms the in-chat
 * find bar of the session that opens.
 */
@Composable
fun GlobalSearchHost(
    vm: TetherViewModel,
    prefs: UiPrefs,
    sessions: List<AgentSession>,
    workspaceRoot: String?,
    /** The phone drawer closes behind an opened result (`setDrawerOpen(false)`). */
    onCloseDrawer: () -> Unit,
) {
    val open by vm.globalSearchOpen.collectAsStateWithLifecycle()
    val form by vm.globalSearchForm.collectAsStateWithLifecycle()
    val client = vm.client
    val results by client.globalSearchResults.collectAsStateWithLifecycle()
    val preferences by prefs.preferences.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default)
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val pickedWorkspace by vm.currentWorkspace.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val latestPrefs by rememberUpdatedState(preferences)
    val controller = remember(vm, prefs) {
        SidebarController(
            client = client,
            readPreferences = { latestPrefs },
            updatePreferences = { transform -> scope.launch { prefs.updatePreferences(transform) } },
            selectWorkspace = vm::selectWorkspace,
        )
    }
    val current = controller.currentWorkspace(pickedWorkspace, preferences, workspaceRoot)
    val workspaces = SidebarModel.sidebarWorkspaces(controller.pinnedWorkspaces(serverSettings, preferences), current)

    // global-search.tsx:79-96 — the debounced search on every query / filter change while open,
    // `since` stamped when it fires. A blank query also goes through (it clears the results).
    LaunchedEffect(open, form, current) {
        if (!open) return@LaunchedEffect
        delay(GLOBAL_SEARCH_DEBOUNCE_MS)
        client.runGlobalSearch(GlobalSearchModel.params(form, current.orEmpty(), System.currentTimeMillis()))
    }

    if (!open) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    GlobalSearchDialog(
        form = form,
        onFormChange = vm::updateGlobalSearchForm,
        results = results,
        workspaceRoot = workspaceRoot.orEmpty(),
        now = now,
        onClose = vm::closeGlobalSearch,
        onOpenHit = { hit, query -> openGlobalHit(vm, controller, sessions, workspaces, current, hit, query, onCloseDrawer) },
    )
}

/**
 * dashboard.tsx:1167-1184 `openGlobalHit`. A live session of the conversation opens through
 * T4.4's [TetherViewModel.openSession], the one path for selecting a session from outside the
 * sidebar: its `openRequests` event makes the session's block current (SessionDrawer) and closes
 * the phone drawer (MainShell); no mark-seen, as on the web. Otherwise the sidebar's `reopen`:
 * the owning block becomes current, `resume` goes out, and only a sent resume marks it seen and
 * closes the drawer. Then the find request, and the modal closes (clearing its results).
 */
internal fun openGlobalHit(
    vm: TetherViewModel,
    controller: SidebarController,
    sessions: List<AgentSession>,
    workspaces: List<String>,
    current: String?,
    hit: SearchHit,
    query: String,
    onCloseDrawer: () -> Unit,
) {
    val live = sessions.firstOrNull { it.historyId == hit.historyId }
    if (live != null) {
        vm.openSession(live.id)
    } else {
        controller.focusWorkspaceFor(hit.cwd, workspaces, current)
        val history = hit.toHistory()
        if (vm.resumeHistory(history)) {
            controller.markSeen(history.historyId)
            onCloseDrawer()
        }
    }
    vm.requestFind(query, hit.historyId)
    vm.closeGlobalSearch()
}
