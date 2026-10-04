package com.tether.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.LastOpenedSession
import com.tether.app.ui.prefs.TetherPreferences
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
 * - the chat on screen is remembered as `lastOpenedSession` (:829-835).
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
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
    val pending by vm.selectionPending.collectAsStateWithLifecycle()
    val openingHistoryId by vm.openingHistoryId.collectAsStateWithLifecycle()
    val bootLinkPending by vm.bootLinkPending.collectAsStateWithLifecycle()
    val picked by vm.currentWorkspace.collectAsStateWithLifecycle()
    val historiesByCwd by client.historiesByCwd.collectAsStateWithLifecycle()
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val grace by vm.restoreGraceElapsed.collectAsStateWithLifecycle()

    val visible = SidebarModel.visibleSessions(sessions, loaded.showEndedSessions)
    val current = SidebarController.resolveCurrentWorkspace(picked, loaded, workspaceRoot)
    val workspaces = SidebarModel.sidebarWorkspaces(SidebarController.pinnedWorkspacesOf(serverSettings, loaded), current)
    val histories = current?.let { historiesByCwd[it] }.orEmpty()

    val latestPrefs by rememberUpdatedState(loaded)
    val controller = remember(vm, prefs, scope) {
        SidebarController(
            client = client,
            readPreferences = { latestPrefs },
            updatePreferences = { transform -> scope.launchPreferenceWrite { prefs.updatePreferences(transform) } },
            selectWorkspace = vm::selectWorkspace,
        )
    }

    // use-tether.ts :783-785: `ready` fixes the current workspace (the remembered chat's folder, the
    // default one, else the server's root), so a chat opened later does not move it.
    LaunchedEffect(workspaceRoot) {
        if (workspaceRoot != null) vm.settleWorkspace(SidebarController.resolveCurrentWorkspace(null, loaded, workspaceRoot))
    }
    // :736-748: the first resolved view (once per view model).
    LaunchedEffect(view != null) {
        if (view != null) vm.onBootView(view == DashboardView.Sessions, loaded.lastOpenedSession)
    }
    // :856-886: the boot restore.
    LaunchedEffect(sessionsView, sessions, histories, grace, selectedId, pending) {
        val hit = vm.bootRestoreStep(sessionsView, histories) ?: return@LaunchedEffect
        controller.focusWorkspaceFor(hit.cwd, workspaces, current)
        if (vm.resumeHistory(hit)) {
            controller.markSeen(hit.historyId)
            onReopened()
        }
    }
    // :752-763: the one-time pick.
    LaunchedEffect(view, sessionsView, selectedId, openingHistoryId, bootLinkPending, visible, current) {
        vm.pickIfNothingSelected(sessionsView, visible, current)
    }
    // :829-835: remember the chat on screen.
    LaunchedEffect(session?.id, session?.cwd) {
        val shown = session ?: return@LaunchedEffect
        scope.launchPreferenceWrite { prefs.updatePreferences { it.rememberOpened(shown) } }
    }
}

/** dashboard.tsx:829-835: `{ cwd, sessionId, historyId }`, written only when the cwd or the id differs. */
internal fun TetherPreferences.rememberOpened(session: AgentSession): TetherPreferences {
    val current = lastOpenedSession
    if (current != null && current.cwd == session.cwd && current.sessionId == session.id) return this
    return copy(lastOpenedSession = LastOpenedSession(session.cwd, session.id, session.historyId))
}
