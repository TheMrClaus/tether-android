package com.tether.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.scheduled.ScheduledActionsHandlers
import com.tether.app.ui.scheduled.ScheduledActionsView
import com.tether.app.ui.scheduled.rememberScheduledUiState
import com.tether.app.ui.sidebar.SidebarController

/**
 * T9.3: the Scheduled destination wired as dashboard.tsx:1587-1600 wires `ScheduledActionsView`:
 * the client's schedules and continuations, the composer's provider catalog, the sidebar's current
 * workspace and pinned workspaces, and the four sends plus the continuation's cancel
 * (`rate-limit-resume` `dismiss`). [onOpenSession] is the shell's `selectSession` (it shows Sessions).
 *
 * The view's state (tab, open editor) belongs to the server it was drawn for: signing in to another
 * server starts it afresh, so an editor opened on one server never sends to the next.
 */
@Composable
internal fun ScheduledHost(vm: TetherViewModel, prefs: UiPrefs, workspaceRoot: String?, onOpenSession: (String) -> Unit) {
    val client = vm.client
    val state by client.scheduledActions.collectAsStateWithLifecycle()
    val catalog by client.providerCatalog.collectAsStateWithLifecycle()
    val serverSettings by client.serverSettings.collectAsStateWithLifecycle()
    val preferences by remember(prefs, client) { prefs.preferencesFor(client.serverUrl) }.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default)
    val picked by vm.currentWorkspace.collectAsStateWithLifecycle()
    val server by client.serverUrl.collectAsStateWithLifecycle()
    // ta-m7ef (v143 r3, dashboard.tsx 1bf4a465 :1623-1626): the answer to a save's setup check (the latest
    // `worktree-source` of the live socket) and a refusal of it (the latest `error`).
    var setupReply by remember(client) { mutableStateOf<com.tether.app.client.WorktreeSourceReply?>(null) }
    androidx.compose.runtime.LaunchedEffect(client) {
        client.worktreeSources.collect { reply -> if (reply.linkEpoch == client.linkEpoch.value) setupReply = reply }
    }
    val createError by client.createErrors.collectAsStateWithLifecycle()
    key(server) {
        ScheduledActionsView(
            state = state,
            providerEntries = catalog,
            currentWorkspace = SidebarController.resolveCurrentWorkspace(picked, preferences, workspaceRoot).orEmpty(),
            workspaceRoot = workspaceRoot.orEmpty(),
            pinnedProjects = SidebarController.pinnedWorkspacesOf(serverSettings, preferences),
            handlers = ScheduledActionsHandlers(
                onCreate = { client.createSchedule(it) },
                onUpdate = { id, schedule -> client.updateSchedule(id, schedule) },
                onControl = { id, action -> client.controlSchedule(id, action) },
                onCancelContinuation = { sessionId, resetsAt -> client.cancelScheduledContinuation(sessionId, resetsAt) },
                onOpenSession = onOpenSession,
                // The default create a run makes: branch-off from origin's default (dashboard.tsx :1624).
                onCheckSetup = { cwd, requestId ->
                    client.inspectSetup(cwd, com.tether.app.protocol.WorktreeCreateRequest(mode = "branch-off"), requestId, client.linkEpoch.value)
                },
            ),
            ui = rememberScheduledUiState(),
            setupReply = setupReply,
            createError = createError,
        )
    }
}
