package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * T9.1: the inspector wired to the view model — the session row, the provider list, the
 * projection, the client's per-session replies and the shared run selection (the transcript's tab
 * strip and this roster select the same run, as on the web). The clock ticks once a minute, like
 * the web's reset-grant countdown.
 */
@Composable
fun ColumnScope.InspectorHost(
    vm: TetherViewModel,
    session: AgentSession,
    view: SessionView?,
    /** T9.2: the shared Codex confirmation (inspector.tsx:761-766 `resetCreditDialogRef.open`). */
    onUseCodexReset: ((com.tether.app.ui.usage.CodexResetRequest) -> Unit)? = null,
) {
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
    // ta-coik.10: the attention strip's rate-limit / wrap-up alerts expire on their own at the
    // window's reset (the web's RateLimitNotice / WrapUpNotice timers; the CLI may never send the
    // clearing event), so the panel is re-derived at that instant too.
    val expiry = rememberRateLimitExpiry(view)
    val runs = remember(view) { collectSubagentRuns(view?.obj) }
    val replies = InspectorReplies(diffs[session.id], scripts[session.id], changeRequests[session.id])
    // Keyed on the minute tick and the expiry; derived at the real instant it recomputes.
    val model = remember(session, providers, view, runs, selected[session.id], replies, now, origin, expiry) {
        inspectorModel(session, providers, view, runs, selected[session.id], replies, ReadingEnv.current(), origin)
    }
    Inspector(
        model = model,
        state = view,
        onSelectRun = { vm.selectRun(session.id, it) },
        fileDiffs = fileDiffs[session.id],
        onRequestFileDiff = { path -> vm.client.requestGitFileDiff(session.id, path) },
        serviceOpen = vm.client.serviceOpen,
        onUseCodexReset = onUseCodexReset?.let { open -> codexResetRequest(session)?.let { request -> { open(request) } } },
    )
}

/**
 * inspector.tsx:761-766: this Codex session's banked resets as the confirmation's request — its
 * own id (a hint for its warm engine), the count and credits, and its 5-hour / weekly readings.
 * Null when it has none to spend (the row is not shown then either).
 */
internal fun codexResetRequest(session: AgentSession): com.tether.app.ui.usage.CodexResetRequest? {
    if (session.provider != "codex") return null
    val metrics = session.metrics ?: return null
    val credits = (metrics.codexResetCredits as? kotlinx.serialization.json.JsonObject)?.let(com.tether.app.client.UsageJson::resetCredits) ?: return null
    if (credits.availableCount <= 0) return null
    val telemetry = com.tether.app.ui.statusline.TelemetryMetrics.from(metrics)
    fun window(w: com.tether.app.ui.statusline.TelemetryWindow?) = w?.let { com.tether.app.client.AccountWindow(it.usedPercent, 0.0, it.resetsAt) }
    return com.tether.app.ui.usage.CodexResetRequest(
        availableCount = credits.availableCount,
        credits = credits.credits,
        windows = com.tether.app.client.AccountWindows(window(telemetry?.fiveHour), window(telemetry?.weekly), null, emptyList()),
        sessionId = session.id,
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

/** Changes value once the projection's `rateLimit.resetsAt` passes (0 until then). */
@Composable
internal fun rememberRateLimitExpiry(view: SessionView?): Int {
    val resetsAt = ((view?.obj?.get("rateLimit") as? JsObj)?.get("resetsAt") as? JsNum)?.value?.takeIf { it.isFinite() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(resetsAt) {
        if (resetsAt == null) return@LaunchedEffect
        delay(max(0.0, resetsAt - System.currentTimeMillis()).toLong() + 1)
        tick++
    }
    return tick
}
