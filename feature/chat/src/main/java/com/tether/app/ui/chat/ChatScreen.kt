package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Cpu
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.reduce.RUN_RUNNING
import com.tether.app.protocol.reduce.collectSubagentRuns
import com.tether.app.protocol.reduce.subagentRosterSummary
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.components.statusToneOf
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.compactNumber
import com.tether.app.ui.util.compactPath
import com.tether.app.ui.util.statusCopy

/** The chat workspace: workspace header, transcript, composer (visual-spec §4). */
@Composable
fun ChatScreen(
    vm: TetherViewModel,
    session: AgentSession?,
    projection: SessionProjection?,
    workspaceRoot: String?,
    prefs: UiPrefs,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    /** False when a shell hosts the workspace header itself (T4.1's phone shell). */
    showWorkspaceHeader: Boolean = true,
) {
    val t = LocalTetherTokens.current
    val showThinking by prefs.showThinking.collectAsStateWithLifecycle(
        initialValue = TetherPreferences.Default.showThinking,
    )
    val controlsMap by vm.client.sessionControls.collectAsStateWithLifecycle()
    // The v128 projection trees: block `ts` stamps for the bubbles' send times.
    val trees by vm.client.projectionTrees.collectAsStateWithLifecycle()

    // T6.2: tool / attachment / spawned-run media over the paired, no-redirect HTTP path.
    val context = LocalContext.current
    val serverUrl by vm.client.serverUrl.collectAsStateWithLifecycle()
    val mediaLoader = remember(vm.client, serverUrl) { ToolMediaRepository(vm.client.toolMedia, context.cacheDir, serverUrl) }

    val selectedRunIds by vm.selectedRunIdBySession.collectAsStateWithLifecycle()
    val runs = remember(projection) { collectSubagentRuns(projection) }
    // Resolve by lookup, never by trusting the stored id: a run that vanished
    // from a re-snapshot degrades to the Session tab on its own.
    val activeRun = session?.let { selectedRunIds[it.id] }?.let { id -> runs.firstOrNull { it.runId == id } }

    // T6.3: the consent state of this session's cards (SYNC_DESIGN §4.2, §5.1 I2/I3).
    val connection by vm.client.connection.collectAsStateWithLifecycle()
    val liveSessions by vm.client.liveSessions.collectAsStateWithLifecycle()
    val decided by vm.client.decidedRequests.collectAsStateWithLifecycle()
    val unconfirmed by vm.client.unconfirmedRequests.collectAsStateWithLifecycle()
    val consentOrigin by vm.client.consentOrigin.collectAsStateWithLifecycle()
    val providers by vm.client.providers.collectAsStateWithLifecycle()
    val consent = remember(session, connection, liveSessions, decided, unconfirmed, consentOrigin, vm) {
        consentActionsFor(vm, session, connection, consentOrigin, liveSessions, decided, unconfirmed)
    }
    val showApprovals = session == null || providers.firstOrNull { it.id == session.provider }?.capabilities?.interactiveApprovals != false
    // Round 3: ONE saved store for every card of this screen (transcript and run tabs alike), above
    // the lazy lists and the tab switch, so a narrowed grant outlives a scroll, a tab and a drop.
    val cardStates = rememberSaveable(saver = CardStateStore.Saver) { CardStateStore() }

    LaunchedEffect(session?.id, session?.provider) {
        val s = session
        if (s != null && s.provider == "claude") vm.client.requestSessionControls(s.id)
    }

    // T5.3 in-chat find (chat-view.tsx:2013-2120): per conversation, reset on a session switch.
    val find = rememberChatFindState(session?.id)
    val findFocus = remember { FocusRequester() }
    val counter = remember(session?.id) { FindCounter() }
    val needle = findNeedle(find.query.text)
    val results = remember(projection, needle, find.open) { findResults(projection, needle, find.open, counter) }
    val active = activeHitIndex(results, find.index)
    val transcriptFind = if (find.open) TranscriptFind(results, needle, results.hits.getOrNull(active)) else null
    // dashboard.tsx:1459 — a global-search result arms the find bar of ITS conversation only.
    val findRequest by vm.findRequest.collectAsStateWithLifecycle()
    val request = findRequest?.takeIf { session?.historyId != null && it.historyId == session.historyId }
    LaunchedEffect(request?.nonce, session?.id) {
        // The web also focuses the box here; a phone's soft keyboard would cover the match the
        // bar just jumped to, so the query waits for a tap (Ctrl+F still focuses it).
        request?.let { find.apply(it.query, it.nonce) }
    }
    var focusFind by remember { mutableStateOf(0) }
    LaunchedEffect(focusFind) { if (focusFind > 0) runCatching { findFocus.requestFocus() } }

    Box(
        modifier.onPreviewKeyEvent { event ->
            // chat-view.tsx:2092-2098: Ctrl/Cmd+F (not Shift, not Alt) opens or refocuses the bar.
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            if (event.type == KeyEventType.KeyDown && event.key == Key.F && ctrl && !event.isShiftPressed && !event.isAltPressed && session != null) {
                find.openSelected()
                focusFind++
                true
            } else {
                false
            }
        },
    ) {
    Column(Modifier.fillMaxSize().background(t.mineralDeep)) {
        if (session != null && showWorkspaceHeader) {
            WorkspaceHeader(vm = vm, session = session, workspaceRoot = workspaceRoot)
        }

        if (session != null && runs.isNotEmpty()) {
            SubagentTabs(
                runs = runs,
                activeRunId = activeRun?.runId,
                onSelect = { runId -> vm.selectRun(session.id, runId) },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalToolMediaLoader provides mediaLoader, LocalCardStates provides cardStates) {
            when {
                session == null -> EmptyCentered(
                    title = "No session selected",
                    hint = "Open the menu to pick or create a session.",
                ) {
                    TetherKey(onClick = onOpenDrawer, classes = KeyClasses.ButtonSecondary, label = "Sessions")
                }

                projection == null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 18.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Connecting to the session…",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 13.6.sp,
                    )
                }

                projection.turnOrder.isEmpty() -> EmptyCentered(
                    label = "HEADLESS AGENT",
                    title = "Send a message to start the conversation.",
                    hint = "Tools that need permission will surface an approval here before they run.",
                )

                activeRun != null -> RunTab(
                    projection = projection,
                    tree = trees[session.id],
                    run = activeRun,
                    showThinking = showThinking,
                    consent = consent,
                    showApprovals = showApprovals,
                )

                else -> ChatTranscript(
                    find = transcriptFind,
                    projection = projection,
                    tree = trees[session.id],
                    showThinking = showThinking,
                    onFetchTurns = { from, to -> vm.client.fetchTurns(session.id, from, to) },
                    consent = consent,
                    showApprovals = showApprovals,
                    roster = if (runs.isNotEmpty()) {
                        {
                            SubagentRoster(
                                runs = runs,
                                summary = subagentRosterSummary(runs),
                                activeRunId = null,
                                onSelect = { runId -> vm.selectRun(session.id, runId) },
                            )
                        }
                    } else {
                        null
                    },
                    richCodex = isRichCodexSession(session.provider, session.engineGeneration),
                    richOpencode = isRichOpencodeSession(session.provider, session.engineGeneration),
                )
            }
            }
        }

        // The composer deck draws its own top seam (`.chat-composer` border-top + lip, T7.1).
        Composer(
            session = session,
            projection = projection,
            controls = session?.let { controlsMap[it.id] },
            serverNow = { vm.serverNow(session?.id) },
            onSend = { text, attachments -> session?.let { vm.sendOrQueue(it.id, text, attachments) } ?: false },
            onInterrupt = { session?.let { vm.client.interrupt(it.id) } },
            onQueueEdit = { queueId, text -> session?.let { vm.client.queueEdit(it.id, queueId, text) } },
            onQueueRemove = { queueId -> session?.let { vm.client.queueRemove(it.id, queueId) } },
            onSetMode = { mode -> session?.let { vm.client.setMode(it.id, mode) } },
            onSetModel = { model -> session?.let { vm.client.setModel(it.id, model) } ?: false },
            onRequestControls = { session?.let { vm.client.requestSessionControls(it.id) } },
            onAttachError = { message -> vm.reportLocalError(message) },
            // A plain read, not a subscription: only the opening value matters here.
            initialDraft = session?.let { vm.loadedDraft(it.id) },
            awaitDraft = { session?.let { vm.awaitDraft(it.id) } ?: "" },
            onDraftChange = { text -> session?.let { vm.setDraft(it.id, text) } },
        )
    }
    if (find.open && session != null) {
        ChatFindBar(
            query = find.query,
            onQueryChange = { value ->
                if (value.text != find.query.text) find.index = 0
                find.query = value
            },
            count = findCount(needle, results, find.index),
            canStep = results.hits.isNotEmpty(),
            onStep = { delta -> find.index = stepMatch(results, find.index, delta) },
            onClose = find::close,
            focusRequester = findFocus,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = t.css.spaceSm, end = t.css.spaceMd),
        )
    }
    }
}

/**
 * A sub-agent run tab: the run panel replaces the transcript, but the active
 * turn's pending approval/question cards render below it on every tab — a
 * card the turn is stalled on must never be hidden behind a tab.
 */
@Composable
private fun RunTab(
    projection: SessionProjection,
    tree: com.tether.app.protocol.tree.JsObj?,
    run: com.tether.app.protocol.reduce.SubagentRun,
    showThinking: Boolean,
    consent: ConsentActions,
    showApprovals: Boolean,
) {
    val listState = rememberLazyListState()
    val state = remember(projection, tree) { cardTree(projection, tree) }
    val pending = remember(state, showApprovals) { if (showApprovals) pendingApprovals(state) else emptyList() }
    val pendingQ = remember(state) { pendingQuestions(state) }
    val answeredIds = remember(state) { answeredRequestIds(state) }

    // Web parity: a running run follows the newest activity as its thread
    // grows (steps stream in, pending cards arrive); a finished run parks at
    // the top. Re-key on the growing content so the effect re-fires, and read
    // the current last index inside the effect so it tracks new items.
    LaunchedEffect(run.runId, run.steps, pending.size, pendingQ.size, run.status) {
        val lastIndex = pending.size + pendingQ.size // panel is item 0; cards follow
        if (run.status == RUN_RUNNING) {
            listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        } else {
            listState.scrollToItem(0)
        }
    }

    CompositionLocalProvider(LocalConsent provides consent) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "run-panel") {
            SubagentRunPanel(run = run, showThinking = showThinking)
        }
        pending.forEach { approval ->
            item(key = "approval/${approval.requestId}/${approval.contentFp}") { ApprovalCard(approval) }
        }
        pendingQ.forEach { question ->
            item(key = "question/${question.requestId}/${question.contentFp}") { QuestionCard(question, answered = question.requestId in answeredIds) }
        }
    }
    }
}

/**
 * T6.3: the consent actions of [session]'s cards. The ONLY place a card's tap becomes a client
 * call; the client re-checks everything (ConsentGuard) before a frame leaves.
 */
internal fun consentActionsFor(
    vm: TetherViewModel,
    session: AgentSession?,
    connection: com.tether.app.client.ConnectionState,
    origin: String?,
    liveSessions: Set<String>,
    decided: Set<String>,
    unconfirmed: Set<String> = emptySet(),
): ConsentActions {
    val s = session ?: return ConsentActions.Unavailable
    return ConsentActions(
        sessionId = s.id,
        origin = origin,
        // No live socket origin = no live socket: the fingerprints would name no server.
        lock = consentLock(connection == com.tether.app.client.ConnectionState.Connected && origin != null, s.id in liveSessions, s),
        decided = decided,
        unconfirmed = unconfirmed,
        questionUnavailable = if (s.provider == "opencode" && s.engineGeneration != "opencode-serve-v2") ConsentActions.LEGACY_OPENCODE_QUESTION else null,
        onApproval = { requestId, fingerprint, choiceId, decision, granted -> vm.client.approval(s.id, requestId, fingerprint, choiceId, decision, granted) },
        onAnswer = { requestId, fingerprint, picks, skipped -> vm.client.answerQuestion(s.id, requestId, fingerprint, picks, skipped) },
        onOpenRun = { runId -> vm.selectRun(s.id, runId) },
    )
}

@Composable
private fun EmptyCentered(
    title: String,
    hint: String,
    label: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (label != null) {
            Text(
                label,
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 10.7.sp,
                letterSpacing = 0.08.em,
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            title,
            color = t.ink,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 16.8.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            color = t.muted,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 13.6.sp,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Workspace header: session name + status badge + actions; mono path line. */
@Composable
private fun WorkspaceHeader(vm: TetherViewModel, session: AgentSession, workspaceRoot: String?) {
    val t = LocalTetherTokens.current
    var showTelemetry by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(t.graphite)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.name,
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 15.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // `.status-badge`: dot + printed word in an etched pill (never colour alone).
            TetherStatusPill(
                label = statusCopy(session.status),
                tone = statusToneOf(session.status),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = { showTelemetry = true }, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(TetherIcons.Gauge, contentDescription = "Telemetry", tint = t.muted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { vm.client.pin(session.id, !session.pinned) }, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(
                    TetherIcons.Pin,
                    contentDescription = if (session.pinned) "Unpin" else "Pin",
                    tint = if (session.pinned) t.violet else t.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (session.status != "exited") {
                TetherKey(
                    onClick = { confirmEnd = true },
                    classes = KeyClasses.EndSession,
                    icon = TetherIcons.CircleStop,
                    iconSize = 16.dp,
                    contentDescription = "End session",
                    wear = false,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                compactPath(session.cwd, workspaceRoot),
                color = t.faint,
                fontFamily = JetBrainsMono,
                fontSize = 10.9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            session.metrics?.let { metrics ->
                val parts = buildList {
                    metrics.model?.let { add(it) }
                    metrics.totalTokens?.let { add("${compactNumber(it)} tok") }
                    metrics.contextPercent?.let { add("${it.toInt()}% ctx") }
                }
                if (parts.isNotEmpty()) {
                    val ctx = metrics.contextPercent ?: 0.0
                    Text(
                        parts.joinToString(" · "),
                        color = when {
                            ctx >= 90 -> t.danger
                            ctx >= 75 -> t.warning
                            else -> t.faint
                        },
                        fontFamily = JetBrainsMono,
                        fontSize = 10.6.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }

    if (showTelemetry) {
        TetherDialog(onDismiss = { showTelemetry = false }, title = "Telemetry") {
            val rows = buildList {
                add("Provider" to session.provider)
                session.model?.let { add("Model" to it) }
                session.metrics?.effort?.let { add("Effort" to it) }
                session.metrics?.totalTokens?.let { add("Total tokens" to compactNumber(it)) }
                session.metrics?.contextPercent?.let { add("Context" to "${it.toInt()}%") }
                session.metrics?.gitBranch?.let { add("Branch" to it) }
                add("Directory" to session.cwd)
            }
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        label.uppercase(),
                        color = t.faint,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.strong,
                        fontSize = 9.9.sp,
                        letterSpacing = 0.06.em,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        value,
                        color = t.ink,
                        fontFamily = JetBrainsMono,
                        fontSize = 11.8.sp,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }

    if (confirmEnd) {
        TetherDialog(onDismiss = { confirmEnd = false }, title = "End session") {
            Text(
                "Stop the agent process for \"${session.name}\"?",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.6.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherKey(onClick = { confirmEnd = false }, classes = KeyClasses.ButtonSecondary, label = "Cancel")
                TetherKey(
                    onClick = {
                        confirmEnd = false
                        vm.client.kill(session.id)
                    },
                    classes = KeyClasses.ButtonDanger,
                    label = "End session",
                    icon = TetherIcons.CircleStop,
                )
            }
        }
    }
}
