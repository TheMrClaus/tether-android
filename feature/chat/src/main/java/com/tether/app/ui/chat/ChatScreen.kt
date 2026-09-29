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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.composables.icons.lucide.Cpu
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.SessionProjection
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
    val tree = session?.let { trees[it.id] }
    // T6.4: the runs read the TREE (background tasks and spawned runs are not in the typed projection).
    val runs = remember(projection, tree) { projection?.let { collectSubagentRuns(cardTree(it, tree)) } ?: emptyList() }
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
    // T13.2: how current this session's copy is (SYNC_DESIGN §4); it also narrows "live" below.
    val syncStates by vm.client.syncStates.collectAsStateWithLifecycle()
    val sync = session?.let { syncStates[it.id] }
    // r2: a client that reports freshness at all never unlocks a session it has no entry for.
    val reportsFreshness = vm.client.reportsFreshness
    val liveNow = ChatFreshness.isLive(session?.id, liveSessions, sync, reportsFreshness)
    // T6.4: a denial's origin link opens the run's tab AND lands on the refused step.
    var runFocus by remember(session?.id) { mutableStateOf<RunFocus?>(null) }
    val onFocusCall: (String, String) -> Unit = remember(session?.id, vm) {
        { runId, toolId ->
            session?.let { vm.selectRun(it.id, runId) }
            runFocus = RunFocus(runId, toolId, (runFocus?.nonce ?: 0) + 1)
        }
    }
    val consent = remember(session, connection, liveSessions, decided, unconfirmed, consentOrigin, vm, onFocusCall, sync, reportsFreshness) {
        consentActionsFor(vm, session, connection, consentOrigin, liveSessions, decided, unconfirmed, onFocusCall, sync, reportsFreshness)
    }
    // T6.4: background commands — open one's output; STOP one (a tap on its Stop key, and only then).
    var openCommandId by remember(session?.id) { mutableStateOf<String?>(null) }
    val onOpenCommand: (String) -> Unit = remember(session?.id) { { id -> openCommandId = id } }
    val stopLock = stopLockCopy(
        consentLock(connection == com.tether.app.client.ConnectionState.Connected && consentOrigin != null, liveNow, session),
    )
    // Round 2: one "Stopping…" latch per command for this session (the bar and the sheet share it);
    // L3: the stop is bound to the server origin this row was drawn for.
    // L-3 (r3): a stop sent on one link never latches the keys past it.
    val stopLatches = rememberStopLatches(session?.id, Triple(connection, liveNow, consentOrigin))
    val commandActions = remember(session?.id, stopLock, consentOrigin, vm, stopLatches) {
        val s = session
        val drawnFor = consentOrigin
        if (s == null) CommandActions.Unavailable else CommandActions(stopLock, onOpenCommand, { commandId -> vm.client.stopCommand(s.id, commandId, drawnFor) }, stopLatches)
    }
    // T13.2 r2: Interrupt (the key and a queued row's "Interrupt now") follows the Stop keys' lock:
    // a saved or catching-up copy's "busy" never interrupts a real turn. Bound to the server the key
    // was drawn for; the client re-checks it all under its lock.
    val liveness = ComposerLiveness(interruptLock = stopLock, stale = ChatFreshness.staleCopy(liveNow, sync))
    val onInterrupt: () -> Unit = remember(session?.id, consentOrigin, vm) {
        val s = session
        val drawnFor = consentOrigin
        val interrupt: () -> Unit = { if (s != null) vm.client.interrupt(s.id, drawnFor) }
        interrupt
    }
    val showApprovals = session == null || providers.firstOrNull { it.id == session.provider }?.capabilities?.interactiveApprovals != false
    // ONE store for every card of this screen (transcript and run tabs alike): the shell's (round 4,
    // H1: above the layout switch), or one saved here when nobody provides it.
    val cardStates = rememberCardStates()

    // T7.2: the session controls (chat-view.tsx:2299-2319). Reads only: the models / commands reply
    // for every live-row engine, and the Codex v2 catalogs, re-read as the session warms (a turn
    // starts or ends) and once it is live on a (new) connection. Nothing here sends a control.
    val isLive = liveNow
    val activeTurnId = projection?.activeTurnId
    LaunchedEffect(session?.id, session?.provider, session?.readOnly, activeTurnId, isLive) {
        val s = session
        if (s != null && !s.readOnly && isLive) {
            vm.client.requestSessionControls(s.id)
            if (s.provider == "codex" && s.engineGeneration == com.tether.app.client.CODEX_V2) vm.client.requestCodexControls(s.id)
        }
    }
    val codexMap by vm.client.codexControls.collectAsStateWithLifecycle()
    val opencodeMap by vm.client.opencodeControls.collectAsStateWithLifecycle()
    val pinnedModels by remember(prefs) { prefs.preferences.map { it.pinnedModels }.distinctUntilChanged() }.collectAsStateWithLifecycle(emptyList())
    val controlLock = consentLock(connection == com.tether.app.client.ConnectionState.Connected && consentOrigin != null, isLive, session)
    val controlActions = remember(session?.id, controlLock, consentOrigin, vm, codexMap[session?.id], opencodeMap[session?.id]) {
        val s = session
        // Bound to the server this row was drawn for: a tap on another server's row is refused.
        val drawnFor = consentOrigin
        if (s == null) {
            SessionControlActions.Unavailable
        } else {
            SessionControlActions(
                sessionId = s.id,
                origin = drawnFor,
                lock = controlLock,
                onControl = { control -> vm.client.sessionControl(s.id, control, drawnFor) },
                codex = codexMap[s.id],
                opencode = opencodeMap[s.id],
                onRequestCodex = { vm.client.requestCodexControls(s.id) },
                onRequestOpencode = { vm.client.requestOpencodeControls(s.id) },
            )
        }
    }

    // T6.6: the notices' X (dismiss-notice: link + liveness only, the server allows it read-only)
    // and the limit card (rate-limit-resume: T7.2's guarded path and lock). Taps only.
    val noticeLink = Triple(connection, isLive, consentOrigin)
    val noticeActions = remember(session?.id, noticeLink, controlLock, vm) {
        val s = session
        val drawnFor = consentOrigin
        if (s == null) {
            NoticeActions.Unavailable
        } else {
            NoticeActions(
                sessionId = s.id,
                lock = noticeLock(connection == com.tether.app.client.ConnectionState.Connected && drawnFor != null, isLive),
                controlLock = controlLock,
                link = noticeLink,
                onDismiss = { key -> vm.client.dismissNotice(s.id, key, drawnFor) },
                onRateLimit = { control -> vm.client.sessionControl(s.id, control, drawnFor) },
                onRefused = { message -> vm.reportLocalError(message) },
            )
        }
    }
    // T6.6: a handed-off source names (and links to) the session it continued in.
    val allSessions by vm.client.sessions.collectAsStateWithLifecycle()
    val handoffTarget = session?.handedOffTo?.takeIf { it.isNotEmpty() }?.let { id -> allSessions.firstOrNull { it.id == id } }

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
            WorkspaceHeader(vm = vm, session = session, workspaceRoot = workspaceRoot, endAllowed = connection == com.tether.app.client.ConnectionState.Connected && liveNow, origin = consentOrigin)
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

                // T13.2: offline with nothing on the device: say so, not "Connecting…". r2: only while
                // not connected; connected, the attach is on its way and the loading state below shows.
                projection == null && sync?.freshness == com.tether.app.client.Freshness.NotDownloaded &&
                    connection != com.tether.app.client.ConnectionState.Connected -> SessionNotDownloaded()

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
                    tree = tree,
                    run = activeRun,
                    showThinking = showThinking,
                    consent = consent,
                    showApprovals = showApprovals,
                    focus = runFocus,
                    onFocusShown = { runFocus = null },
                )

                else -> CompositionLocalProvider(LocalOlderTurnsUnavailable provides ChatFreshness.olderTurnsUnavailable(sync)) { ChatTranscript(
                    find = transcriptFind,
                    projection = projection,
                    tree = trees[session.id],
                    showThinking = showThinking,
                    onFetchTurns = { from, to -> vm.client.fetchTurns(session.id, from, to) },
                    consent = consent,
                    notices = noticeActions,
                    showApprovals = showApprovals,
                    roster = if (runs.isNotEmpty()) {
                        {
                            SubagentRoster(
                                runs = runs,
                                activeRunId = null,
                                onSelect = { runId -> vm.selectRun(session.id, runId) },
                            )
                        }
                    } else {
                        null
                    },
                    onOpenCommand = onOpenCommand,
                    richCodex = isRichCodexSession(session.provider, session.engineGeneration),
                    richOpencode = isRichOpencodeSession(session.provider, session.engineGeneration),
                ) }
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
            onInterrupt = onInterrupt,
            onQueueEdit = { queueId, text -> session?.let { vm.client.queueEdit(it.id, queueId, text) } },
            onQueueRemove = { queueId -> session?.let { vm.client.queueRemove(it.id, queueId) } },
            onRequestControls = { session?.let { vm.client.requestSessionControls(it.id) } },
            liveness = liveness,
            onAttachError = { message -> vm.reportLocalError(message) },
            // A plain read, not a subscription: only the opening value matters here.
            initialDraft = session?.let { vm.loadedDraft(it.id) },
            awaitDraft = { session?.let { vm.awaitDraft(it.id) } ?: "" },
            onDraftChange = { text -> session?.let { vm.setDraft(it.id, text) } },
            tree = tree,
            commandActions = commandActions,
            controlActions = controlActions,
            pinnedModels = pinnedModels,
            handoffTarget = handoffTarget,
            onOpenSession = { id -> vm.selectSession(id) },
        )
    }
    CommandOutputDialog(
        command = openCommandId?.let { id -> backgroundCommands(tree).firstOrNull { it.commandId == id } },
        actions = commandActions,
        onClose = { openCommandId = null },
    )
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
    run: SubagentRun,
    showThinking: Boolean,
    consent: ConsentActions,
    showApprovals: Boolean,
    focus: RunFocus?,
    onFocusShown: () -> Unit,
) {
    val state = remember(projection, tree) { cardTree(projection, tree) }
    val pending = remember(state, showApprovals, consent.sessionId) { if (showApprovals) pendingApprovals(state, consent.sessionId) else emptyList() }
    val pendingQ = remember(state, consent.sessionId) { pendingQuestions(state, consent.sessionId) }
    val answeredIds = remember(state) { answeredRequestIds(state) }
    CompositionLocalProvider(LocalConsent provides consent) {
        SubagentRunTab(run, showThinking, pending, pendingQ, answeredIds, focus, onFocusShown)
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
    onFocusCall: ((runId: String, toolId: String) -> Unit)? = null,
    /** T13.2: the session's freshness; a copy that is not Live is never actionable. */
    sync: com.tether.app.client.SessionSync? = null,
    /** r2: the client reports freshness at all (a missing entry is then not live). */
    reportsFreshness: Boolean = true,
): ConsentActions {
    val s = session ?: return ConsentActions.Unavailable
    return ConsentActions(
        sessionId = s.id,
        origin = origin,
        // No live socket origin = no live socket: the fingerprints would name no server.
        lock = consentLock(connection == com.tether.app.client.ConnectionState.Connected && origin != null, ChatFreshness.isLive(s.id, liveSessions, sync, reportsFreshness), s),
        decided = decided,
        unconfirmed = unconfirmed,
        questionUnavailable = if (s.provider == "opencode" && s.engineGeneration != "opencode-serve-v2") ConsentActions.LEGACY_OPENCODE_QUESTION else null,
        onApproval = { requestId, fingerprint, choiceId, decision, granted -> vm.client.approval(s.id, requestId, fingerprint, choiceId, decision, granted) },
        onAnswer = { requestId, fingerprint, picks, skipped -> vm.client.answerQuestion(s.id, requestId, fingerprint, picks, skipped) },
        onOpenRun = { runId -> vm.selectRun(s.id, runId) },
        onFocusCall = onFocusCall ?: { runId, _ -> vm.selectRun(s.id, runId) },
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

/** T13.2 r3: an open End session confirmation: the session and the server origin it was opened for. */
private data class ChatEndDraw(val sessionId: String, val drawnFor: String?)

/**
 * Workspace header: session name + status badge + actions; mono path line. [endAllowed] (T13.2 r2):
 * the link is up and this session's copy is live, so End session may send. [origin] (r3): the
 * server ([com.tether.app.client.TetherClient.consentOrigin]) the header is drawn for; the confirmation is bound to the
 * session and the origin it was opened for, and acts only while both still hold.
 */
@Composable
private fun WorkspaceHeader(vm: TetherViewModel, session: AgentSession, workspaceRoot: String?, endAllowed: Boolean, origin: String?) {
    val t = LocalTetherTokens.current
    var showTelemetry by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf<ChatEndDraw?>(null) }

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
            // T13.2: offline, "Was running" on a still faint dot (SYNC_DESIGN §4.2).
            val connected = vm.client.connection.collectAsStateWithLifecycle().value == com.tether.app.client.ConnectionState.Connected
            val (pillLabel, pillTone) = com.tether.app.ui.components.FreshnessCopy.statusPill(session.status, connected, null, 0L)
            TetherStatusPill(
                label = pillLabel,
                tone = pillTone,
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
                    onClick = { if (endAllowed) confirmEnd = ChatEndDraw(session.id, origin) },
                    classes = KeyClasses.EndSession,
                    icon = TetherIcons.CircleStop,
                    iconSize = 16.dp,
                    contentDescription = "End session",
                    enabled = endAllowed,
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

    confirmEnd?.let { drawn ->
        val endable = endAllowed && drawn.sessionId == session.id && drawn.drawnFor != null && drawn.drawnFor == origin
        TetherDialog(onDismiss = { confirmEnd = null }, title = "End session") {
            Text(
                "Stop the agent process for \"${session.name}\"?",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.6.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherKey(onClick = { confirmEnd = null }, classes = KeyClasses.ButtonSecondary, label = "Cancel")
                TetherKey(
                    onClick = {
                        confirmEnd = null
                        if (endable) vm.client.kill(drawn.sessionId, drawn.drawnFor, requireLive = true)
                    },
                    classes = KeyClasses.ButtonDanger,
                    label = "End session",
                    icon = TetherIcons.CircleStop,
                    enabled = endable,
                )
            }
        }
    }
}
