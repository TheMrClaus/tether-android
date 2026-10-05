package com.tether.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.LastOpenedSession
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
import com.tether.app.ui.shell.DashboardView
import com.tether.app.ui.sidebar.SidebarController
import com.tether.app.ui.sidebar.SidebarModel
import kotlinx.coroutines.CoroutineScope

/**
 * ta-coik.41: the web's selection effects (dashboard.tsx 90fbb9f), fed from the stored preferences
 * once they are read (never the defaults of a first frame):
 * - the first resolved view seeds the remembered chat ([TetherViewModel.onBootView], :736-748);
 * - the boot restore reopens it by historyId, or gives it up ([TetherViewModel.bootRestoreStep],
 *   :856-886; `reopen` :465-479: the block becomes current, resume, seen, the drawer closes);
 * - the one-time pick ([TetherViewModel.pickIfNothingSelected], :752-763);
 * - the chat on screen is remembered as `lastOpenedSession` (:829-835), per server origin (r2);
 * - ta-coik.47: the device-wide records of before (remembered chat, folded blocks, seen stamps) move
 *   to the current server once;
 * - ta-coik.42: while a pending target is listed, its block is the current workspace (:1153-1160).
 * Its own composable, so a preference change recomposes this and not the shell.
 */
@Composable
internal fun WebSelectionEffects(
    vm: TetherViewModel,
    prefs: UiPrefs,
    scope: CoroutineScope,
    view: DashboardView?,
    sessionsView: Boolean,
    session: AgentSession?,
    workspaceRoot: String?,
    onReopened: () -> Unit,
) {
    val stored by remember(prefs) { prefs.preferences }.collectAsStateWithLifecycle(initialValue = null)
    val loaded = stored ?: return
    val client = vm.client
    val sessions by client.sessions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val pending by vm.pendingSessionId.collectAsStateWithLifecycle()
    val openingHistoryId by vm.openingHistoryId.collectAsStateWithLifecycle()
    val bootLinkPending by vm.bootLinkPending.collectAsStateWithLifecycle()
    val picked by vm.currentWorkspace.collectAsStateWithLifecycle()
    val historiesByCwd by client.historiesByCwd.collectAsStateWithLifecycle()
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val grace by vm.restoreGraceElapsed.collectAsStateWithLifecycle()
    val listLive by vm.listLive.collectAsStateWithLifecycle()
    val serverUrl by client.serverUrl.collectAsStateWithLifecycle()
    // r2: each server remembers its own chat (the web's localStorage is per origin).
    val origin = com.tether.app.client.serverOrigin(serverUrl)
    // ta-coik.47: and its own folded blocks and seen stamps.
    val scoped = loaded.forServer(origin)
    val remembered = scoped.lastOpenedSession

    val visible = SidebarModel.visibleSessions(sessions, loaded.showEndedSessions)
    val current = SidebarController.resolveCurrentWorkspace(picked, scoped, workspaceRoot)
    val workspaces = SidebarModel.sidebarWorkspaces(SidebarController.pinnedWorkspacesOf(serverSettings, loaded), current)
    val histories = current?.let { historiesByCwd[it] }.orEmpty()

    val latestPrefs by rememberUpdatedState(scoped)
    val controller = remember(vm, prefs, scope) {
        SidebarController(
            client = client,
            readPreferences = { latestPrefs },
            updatePreferences = { transform ->
                scope.launchPreferenceWrite { prefs.updatePreferencesFor(com.tether.app.client.serverOrigin(client.serverUrl.value), transform) }
            },
            selectWorkspace = vm::selectWorkspace,
        )
    }

    // r2: the single chat remembered before per-server memory becomes this server's, once; ta-coik.47:
    // with the folded blocks and seen stamps the app kept device-wide.
    LaunchedEffect(origin, loaded.hasDeviceWideServerRecords) {
        if (origin != null && loaded.hasDeviceWideServerRecords) {
            scope.launchPreferenceWrite { prefs.updatePreferences { it.migrateToServer(origin) } }
        }
    }
    // use-tether.ts :783-785: `ready` fixes the current workspace (the remembered chat's folder, the
    // default one, else the server's root), so a chat opened later does not move it; again after a
    // sign-out or another server clears it (r2).
    LaunchedEffect(workspaceRoot, picked == null) {
        if (workspaceRoot != null && picked == null) vm.settleWorkspace(SidebarController.resolveCurrentWorkspace(null, scoped, workspaceRoot))
    }
    // :736-748: the first resolved view (once per view model and sign-in).
    LaunchedEffect(view != null) {
        if (view != null) vm.onBootView(view == DashboardView.Sessions, remembered)
    }
    // :856-886: the boot restore.
    LaunchedEffect(sessionsView, sessions, histories, grace, pending, remembered) {
        val hit = vm.bootRestoreStep(sessionsView, histories, remembered) ?: return@LaunchedEffect
        controller.focusWorkspaceFor(hit.cwd, workspaces, current)
        if (vm.resumeHistory(hit)) {
            controller.markSeen(hit.historyId)
            onReopened()
        }
    }
    // :752-763: the one-time pick.
    LaunchedEffect(view, sessionsView, activeId, pending, openingHistoryId, bootLinkPending, visible, current, listLive) {
        vm.pickIfNothingSelected(sessionsView, visible, current)
    }
    // :1153-1160: a pending target (a link, a live search hit, the remembered chat, a `created`) may
    // live in a project no block owns; once listed, its block (or its own folder) becomes the current
    // workspace, re-checked on the same inputs as the web's effect while the target stays pending.
    val pendingCwd = pending?.let { id -> sessions.firstOrNull { it.id == id } }?.cwd
    LaunchedEffect(pending, pendingCwd, workspaces, current) {
        if (pendingCwd != null) controller.focusWorkspaceFor(pendingCwd, workspaces, current)
    }
    // :829-835: remember the chat on screen.
    LaunchedEffect(session?.id, session?.cwd, origin) {
        val shown = session ?: return@LaunchedEffect
        val opened = LastOpenedSession(shown.cwd, shown.id, shown.historyId)
        scope.launchPreferenceWrite { prefs.updatePreferences { it.rememberOpenedFor(origin, opened) } }
    }
}
