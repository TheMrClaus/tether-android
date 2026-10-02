package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.TetherClient
import com.tether.app.client.serverOrigin
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.chat.collectSubagentRuns
import com.tether.app.ui.components.rememberTickingNow
import com.tether.app.ui.statusline.ReadingEnv

/**
 * T9.1: the inspector wired to the view model — the session row, the provider list, the
 * projection, the client's per-session replies and the shared run selection (the transcript's tab
 * strip and this roster select the same run, as on the web). The clock ticks once a minute, like
 * the web's reset-grant countdown.
 */
@Composable
fun ColumnScope.InspectorHost(vm: TetherViewModel, session: AgentSession, view: SessionView?) {
    val providers by vm.client.providers.collectAsStateWithLifecycle()
    val diffs by vm.client.worktreeDiffs.collectAsStateWithLifecycle()
    val fileDiffs by vm.client.gitFileDiffs.collectAsStateWithLifecycle()
    val scripts by vm.client.worktreeScripts.collectAsStateWithLifecycle()
    val changeRequests by vm.client.changeRequests.collectAsStateWithLifecycle()
    val selected by vm.selectedRunIdBySession.collectAsStateWithLifecycle()
    // T15.7: a service's "Open" link resolves only against the paired server's canonical origin.
    val serverUrl by vm.client.serverUrl.collectAsStateWithLifecycle()
    val origin = serverOrigin(serverUrl)
    val now = rememberTickingNow(60_000)
    val runs = remember(view) { collectSubagentRuns(view?.obj) }
    val replies = InspectorReplies(diffs[session.id], scripts[session.id], changeRequests[session.id])
    val model = remember(session, providers, view, runs, selected[session.id], replies, now, origin) {
        inspectorModel(session, providers, view, runs, selected[session.id], replies, ReadingEnv(now.toDouble()), origin)
    }
    Inspector(
        model = model,
        state = view,
        onSelectRun = { vm.selectRun(session.id, it) },
        fileDiffs = fileDiffs[session.id],
        onRequestFileDiff = { path -> vm.client.requestGitFileDiff(session.id, path) },
        serviceOpen = vm.client.serviceOpen,
    )
}

/**
 * T9.1 (dashboard.tsx:793-828): the inspector's reads for the opened [session], each re-sent on the
 * web's own dependency list (ta-dl4) — the diff summary on the session id (the server answers null
 * for a non-repo cwd and pushes fresh summaries after), an isolated checkout's scripts on the id and
 * its worktree (by value, so an unrelated session update re-sends nothing), and a checkout-pr
 * session's change request on the id and the worktree's mode. Every read also waits for (and is
 * re-sent on) a [connected] link, and the client sends only on an open, handshaken socket.
 */
@Composable
fun InspectorReads(client: TetherClient, session: AgentSession?, connected: Boolean) {
    val id = session?.id
    val worktree = session?.worktree
    val mode = worktree?.mode
    LaunchedEffect(id, connected) {
        if (id != null && connected) client.requestWorktreeDiff(id)
    }
    LaunchedEffect(id, worktree, connected) {
        if (id != null && connected && worktree != null) client.requestWorktreeScripts(id)
    }
    LaunchedEffect(id, mode, connected) {
        if (id != null && connected && mode == "checkout-pr") client.requestChangeRequest(id)
    }
}
