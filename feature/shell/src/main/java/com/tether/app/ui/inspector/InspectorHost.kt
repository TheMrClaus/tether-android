package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val now = rememberTickingNow(60_000)
    val runs = remember(view) { collectSubagentRuns(view?.obj) }
    val replies = InspectorReplies(diffs[session.id], scripts[session.id], changeRequests[session.id])
    val model = remember(session, providers, view, runs, selected[session.id], replies, now) {
        inspectorModel(session, providers, view, runs, selected[session.id], replies, ReadingEnv(now.toDouble()))
    }
    Inspector(
        model = model,
        state = view,
        onSelectRun = { vm.selectRun(session.id, it) },
        fileDiffs = fileDiffs[session.id],
        onRequestFileDiff = { path -> vm.client.requestGitFileDiff(session.id, path) },
    )
}
