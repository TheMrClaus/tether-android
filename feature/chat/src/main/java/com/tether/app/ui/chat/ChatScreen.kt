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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import com.composables.icons.lucide.Cpu
import com.tether.app.client.HttpPublicImages
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.components.originalWords
import com.tether.app.ui.components.statusToneOf
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
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
    // ta-coik.52: every preference is the server's own (the web's localStorage is per origin).
    val showThinking by remember(prefs, vm.client) { prefs.showThinking(vm.client.serverUrl) }.collectAsStateWithLifecycle(
        initialValue = TetherPreferences.Default.showThinking,
    )
    val controlsMap by vm.client.sessionControls.collectAsStateWithLifecycle()
    // The v128 projection trees: block `ts` stamps for the bubbles' send times.
    val trees by vm.client.projectionTrees.collectAsStateWithLifecycle()

    // T6.2: tool / attachment / spawned-run media over the paired, no-redirect HTTP path.
    val context = LocalContext.current
    val serverUrl by vm.client.serverUrl.collectAsStateWithLifecycle()
    val mediaLoader = remember(vm.client, serverUrl) { ToolMediaRepository(vm.client.toolMedia, context.cacheDir, serverUrl, files = vm.client.files, remote = HttpPublicImages()) }
    // ta-coik.68: the session's playing clips live outside composition (a rotation keeps them; the transcript
    // scrolls them away without stopping them) and are released on leaving the session, sign-out and a server switch.
    val toolClips = viewModel(key = "tool-clips") {
        ToolClipsViewModel(
            create = { scope -> ToolClipRegistry(vm.client.toolMedia, context.applicationContext.cacheDir, { vm.client.serverUrl.value }, scope) },
            identity = ToolClipsViewModel.identityOf(vm.client),
        )
    }
    // The phone and expanded shells compose separate chat screens (a rotation can switch them without recreating
    // the activity): leaving is released after a short grace that entering the other one cancels.
    DisposableEffect(toolClips) {
        toolClips.chatEntered()
        onDispose { toolClips.chatLeft() }
    }
    LaunchedEffect(session?.id) { toolClips.onSession(session?.id) }

    val selectedRunIds by vm.selectedRunIdBySession.collectAsStateWithLifecycle()
    val tree = session?.let { trees[it.id] }
    // T6.4: the runs read the TREE (background tasks and spawned runs are not in the typed projection).
    // ta-coik.37: built off the main thread after the first build for a session (ChatDerivation.kt).
    val derivationObserver = LocalChatDerivationObserver.current
    val runsInputs = remember(projection, tree, derivationObserver) { RunsInputs(projection, tree) }
    val runs = rememberDerived(sessionKey = session?.id, inputs = runsInputs, capture = {}) { _ -> deriveRuns(runsInputs.projection, runsInputs.tree, derivationObserver) }
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
    val consent = remember(session, decided, unconfirmed, consentOrigin, vm, onFocusCall) {
        consentActionsFor(vm, session, consentOrigin, decided, unconfirmed, onFocusCall)
    }
    // T6.4: background commands — open one's output; STOP one (a tap on its Stop key, and only then).
    var openCommandId by remember(session?.id) { mutableStateOf<String?>(null) }
    val onOpenCommand: (String) -> Unit = remember(session?.id) { { id -> openCommandId = id } }
    // ta-coik.22: a copy that is not live (offline, catching up) leaves the command keys live, as the
    // web does ([commandKeyLock]); a tap asks the client, which refuses off a live link and says why.
    val stopLock = stopLockCopy(
        commandKeyLock(consentLock(connection == com.tether.app.client.ConnectionState.Connected && consentOrigin != null, session)),
    )
    // L3: the stop is bound to the server origin this row was drawn for. ta-coik.22: no "Stopping…"
    // latch (the web's Stop key has none), and no lock at all: the web draws the running commands'
    // Stop live on every session, read-only and handed off included (chat-view.tsx 90fbb9f
    // :3852-3876, above the composer's handoff / read-only branches); the server answers a refusal.
    val commandActions = remember(session?.id, consentOrigin, vm) {
        val s = session
        val drawnFor = consentOrigin
        if (s == null) CommandActions.Unavailable else CommandActions(null, onOpenCommand, { commandId -> vm.client.stopCommand(s.id, commandId, drawnFor) })
    }
    // T13.2 r2: Interrupt (the key and a queued row's "Interrupt now") follows the Stop keys' lock.
    // ta-coik.22: like the web's, they stay live on a copy that is not live; bound to the server the
    // key was drawn for and to its turn, the client re-checks it all under its lock.
    // T6.7 r3: a failed interrupt of the turn that is still cancelling unlocks the keys for a retry.
    val failedInterrupts by vm.client.failedInterrupts.collectAsStateWithLifecycle()
    val liveness = ComposerLiveness(interruptLock = stopLock, stale = ChatFreshness.staleCopy(liveNow, sync), failedInterruptTurn = session?.let { failedInterrupts[it.id] })
    // T6.7: and to the turn the tapped key was drawn for (the Composer passes it).
    val onInterrupt: (String) -> com.tether.app.client.InterruptResult = remember(session?.id, consentOrigin, vm) {
        val s = session
        val drawnFor = consentOrigin
        val interrupt: (String) -> com.tether.app.client.InterruptResult = { turnId ->
            if (s != null) vm.client.interrupt(s.id, drawnFor, turnId) else com.tether.app.client.InterruptResult.Locked
        }
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
    val prefsScope = rememberCoroutineScope()
    val pinnedModels by remember(prefs, vm.client) { prefs.preferencesFor(vm.client.serverUrl).map { it.pinnedModels }.distinctUntilChanged() }.collectAsStateWithLifecycle(emptyList())
    // T13.2 / T6.6 r3: the session controls and the auto-continue grant (and its pending
    // confirmation, which closes on any lock). ta-coik.24: neither locks offline or catching up, as
    // on the web (chat-view.tsx 90fbb9f :2503, :2970, :3018, :4491, :4495 call `send` whatever the
    // link, use-tether.ts :337-344); the client sends on an open socket for the server they were drawn
    // for and says the link is reconnecting otherwise. Read-only and handed off keep their lock.
    val controlLock = commandKeyLock(consentLock(connection == com.tether.app.client.ConnectionState.Connected && consentOrigin != null, session))
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

    // T6.6: the notices' X (dismiss-notice; the server allows it read-only) and the limit card
    // (rate-limit-resume: T7.2's guarded path). Taps only. ta-coik.23: as on the web (use-tether.ts
    // 90fbb9f :337-344 `send`, :1657-1665), neither locks offline or catching up: the client sends on
    // an open socket for the server they were drawn for, and says the link is reconnecting otherwise.
    // ta-coik.23 r2: nor on read-only / handed off (the web's keys are live there; the server answers).
    val noticeLink = Triple(connection, liveNow, consentOrigin)
    val noticeActions = remember(session?.id, noticeLink, vm) {
        val s = session
        val drawnFor = consentOrigin
        if (s == null) {
            NoticeActions.Unavailable
        } else {
            NoticeActions(
                sessionId = s.id,
                link = noticeLink,
                onDismiss = { key -> vm.client.dismissNotice(s.id, key, drawnFor) },
                onRateLimit = { control -> vm.client.sessionControl(s.id, control, drawnFor) },
                onRefused = { message -> vm.reportLocalError(message) },
                // T8.5 (dashboard.tsx 90fbb9f :325-336, 1688).
                onTakeOverInNewSession = { vm.takeOverInNewSession(s.id) },
            )
        }
    }
    // T7.3: the `!` command mode (offered per provider by the server's `ready`), the foreground
    // command's Background key and the `@` Agents (the catalog the server pushed on this link). Every
    // send is bound to the server the composer was drawn for; the client re-checks it all.
    val providerCatalog by vm.client.providerCatalog.collectAsStateWithLifecycle()
    val runActions = remember(session, consentOrigin, vm, providers, providerCatalog) {
        val s = session
        val drawnFor = consentOrigin
        if (s == null) {
            ComposerCommandActions.Unavailable
        } else {
            ComposerCommandActions(
                commandMode = com.tether.app.client.CommandGuard.commandModeOffered(s, providers),
                onRun = { command, background -> vm.client.runCommand(s.id, command, background, drawnFor) },
                onBackground = { turnId -> vm.client.backgroundCommand(s.id, drawnFor, turnId) },
                origin = drawnFor,
                agents = com.tether.app.client.CommandGuard.delegateAgents(s, providerCatalog),
                onRequestAgents = { vm.client.requestProviderCatalog() },
                // r2: bound to the server the chip was picked on; the client re-checks it with the record.
                onSendDelegated = { text, attachments, mention -> vm.sendDelegated(s.id, text, attachments, mention, drawnFor) },
            )
        }
    }
    // T7.4: the staged attachments (the view model's: they survive a rotation), staged under the
    // configured server, and sent only by an explicit Send bound to the server the composer is
    // drawn for (the client re-checks it all under its lock).
    val stagedSet by vm.stagedAttachments.current.collectAsStateWithLifecycle()
    // r2: a pick is staged only while its session is still the selected, listed, unlocked one.
    val stager = remember(vm) { AttachmentStager(vm.stagedAttachments, { vm.attachmentOrigin() }, allowed = vm::attachmentsAllowed) }
    val attachments = remember(session?.id, stagedSet, consentOrigin, vm, stager) {
        val s = session
        val drawnFor = consentOrigin
        if (s == null) {
            ComposerAttachments.Unavailable
        } else {
            ComposerAttachments(
                staged = vm.stagedAttachments.items(vm.attachmentOrigin(), s.id),
                stage = { sources -> stager.stage(s.id, sources) },
                onRemove = { id -> vm.stagedAttachments.remove(vm.attachmentOrigin(), s.id, id) },
                send = { text, mention -> vm.sendAttachments(s.id, text, mention, drawnFor) },
            )
        }
    }
    // T8.4: the attach sheet's "Add issue or PR" reads the server signed in to (an HTTP read, as the web's fetch).
    val configured by vm.client.configured.collectAsStateWithLifecycle()
    val githubOrigin = if (configured) com.tether.app.client.serverOrigin(serverUrl) else null
    val composerGitHub = remember(vm.client, githubOrigin) { ComposerGitHub(vm.client.githubWork, githubOrigin) }
    // T6.6: a handed-off source names (and links to) the session it continued in.
    val allSessions by vm.client.sessions.collectAsStateWithLifecycle()
    val handoffTarget = session?.handedOffTo?.takeIf { it.isNotEmpty() }?.let { id -> allSessions.firstOrNull { it.id == id } }
    // T8.5 (chat-view.tsx 90fbb9f :156-165, dashboard.tsx :1683-1686): the `@` picker's takeover —
    // the live roster, the derived briefs, and the brief / handoff frames with THIS session as target.
    val handoffBriefs by vm.client.handoffBriefs.collectAsStateWithLifecycle()
    val takeover = remember(session?.id, allSessions, handoffBriefs, vm) {
        session?.let { s ->
            ComposerTakeover(
                sessions = allSessions,
                briefs = handoffBriefs,
                onRequestBrief = { sourceId -> vm.client.requestHandoffBrief(sourceId, s.id) },
                onHandoff = { sourceId, text -> vm.client.handoff(sourceId, s.id, text) },
                onClearBrief = { sourceId -> vm.client.clearHandoffBrief(sourceId) },
            )
        }
    }

    // ta-coik.19 (web issue #135, chat-view.tsx 90fbb9f :1983-1992): this session's unresolved and
    // given-up sends. Dismiss is the failed bubble's one action (use-tether.ts :473-478): local only.
    val pendingSends by vm.client.pendingSends.collectAsStateWithLifecycle()
    val failedSends by vm.client.failedSends.collectAsStateWithLifecycle()
    val sends = remember(session?.id, pendingSends, failedSends, vm) {
        SendBubbles.forSession(session?.id, pendingSends, failedSends) { key -> vm.client.dismissFailedSend(key) }
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
    // T8.6 (dashboard.tsx 90fbb9f :229-236): the in-console browser pane's open flag, over sessions.
    var browserOpen by remember { mutableStateOf(false) }
    // dashboard.tsx :235, :799-812: the picked elements, each tagged with its session so a session
    // switch simply shows another slice; a new pick prunes any other session's, and the page it was
    // picked on travels with it (chat-view.tsx `browserPageUrl`).
    var browserPicks by remember { mutableStateOf(emptyList<SessionPick>()) }
    var browserPageUrl by remember { mutableStateOf<String?>(null) }
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
            // T10.1: Settings → General's "Confirm before ending" (dashboard.tsx:1170-1180); until read, it asks.
            val confirmBeforeEnd by remember(prefs, vm.client) { prefs.preferencesFor(vm.client.serverUrl).map { it.confirmBeforeEnd }.distinctUntilChanged() }.collectAsStateWithLifecycle(true)
            WorkspaceHeader(vm = vm, session = session, workspaceRoot = workspaceRoot, origin = consentOrigin, server = endSessionServer(consentOrigin, serverUrl), confirmBeforeEnd = confirmBeforeEnd)
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
            CompositionLocalProvider(LocalToolMediaLoader provides mediaLoader, LocalToolClips provides toolClips.registry, LocalCardStates provides cardStates) {
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
                    connection != com.tether.app.client.ConnectionState.Connected -> WithSendBubbles(sends) { SessionNotDownloaded() }

                projection == null -> WithSendBubbles(sends) { Column(
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
                } }

                projection.turnOrder.isEmpty() -> WithSendBubbles(sends) {
                    EmptyCentered(
                        label = "Headless agent",
                        title = "Send a message to start the conversation.",
                        hint = "Tools that need permission will surface an approval here before they run.",
                    )
                }

                activeRun != null -> WithSendBubbles(sends) { RunTab(
                    projection = projection,
                    tree = tree,
                    run = activeRun,
                    showThinking = showThinking,
                    consent = consent,
                    showApprovals = showApprovals,
                    focus = runFocus,
                    onFocusShown = { runFocus = null },
                ) }

                // ta-coik.33: one transcript per conversation, as the web remounts its ChatView per session
                // (dashboard.tsx `key={activeSession.id}`): the scroll position and the follow mode never
                // carry over from the chat opened before, so a chat always opens at its latest message.
                else -> CompositionLocalProvider(LocalOlderTurnsUnavailable provides ChatFreshness.olderTurnsUnavailable(sync)) { key(session.id) { ChatTranscript(
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
                    liveCopy = liveNow,
                    richCodex = isRichCodexSession(session.provider, session.engineGeneration),
                    richOpencode = isRichOpencodeSession(session.provider, session.engineGeneration),
                    sends = sends,
                ) } }
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
            attachments = attachments,
            // A plain read, not a subscription: only the opening value matters here.
            initialDraft = session?.let { vm.loadedDraft(it.id) },
            awaitDraft = { session?.let { vm.awaitDraft(it.id) } ?: "" },
            onDraftChange = { text -> session?.let { vm.setDraft(it.id, text) } },
            // T11.2: text shared from another app into this session (taken once, appended to the draft).
            inserts = remember(session?.id, vm) {
                val id = session?.id
                if (id == null) {
                    kotlinx.coroutines.flow.emptyFlow()
                } else {
                    vm.composerInserts.filter { id in it }.mapNotNull { vm.takeComposerInsert(id) }
                }
            },
            tree = tree,
            commandActions = commandActions,
            controlActions = controlActions,
            pinnedModels = pinnedModels,
            // ta-coik.55: written to the record of the server this picker reads (chat-view.tsx 90fbb9f :2281-2286).
            onToggleModelPin = { id -> prefsScope.launchPreferenceWrite { prefs.toggleModelPin(com.tether.app.client.serverOrigin(serverUrl), id) } },
            handoffTarget = handoffTarget,
            onOpenSession = { id -> vm.selectSession(id) },
            runActions = runActions,
            onWarmControls = { session?.let { vm.client.requestWarmSessionControls(it.id) } },
            sendRows = sends.pending,
            github = composerGitHub,
            takeover = takeover,
            browser = if (session != null) ComposerBrowser(browserOpen) { browserOpen = !browserOpen } else null,
            browserPicks = if (session != null) {
                ComposerPicks(
                    items = browserPicks.filter { it.sessionId == session.id }.map { it.pick },
                    pageUrl = browserPageUrl,
                    onRemove = { pick -> browserPicks = browserPicks.filterNot { it.pick === pick } },
                    onClear = { browserPicks = browserPicks.filter { it.sessionId != session.id } },
                    send = { text, shots -> vm.sendAttachments(session.id, text, null, consentOrigin, shots) },
                )
            } else {
                null
            },
        )
    }
    CommandOutputDialog(
        command = openCommandId?.let { id -> backgroundCommands(tree).firstOrNull { it.commandId == id } },
        actions = commandActions,
        onClose = { openCommandId = null },
    )
    // dashboard.tsx 90fbb9f :1762-1772: over the chat, keyed by session.
    if (browserOpen && session != null) {
        BrowserPaneHost(
            opener = vm.client.browserSockets,
            sessionId = session.id,
            onClose = { browserOpen = false },
            onPicked = { pick, pageUrl ->
                browserPicks = browserPicks.filter { it.sessionId == session.id } + SessionPick(session.id, pick)
                browserPageUrl = pageUrl
            },
            modifier = Modifier.align(Alignment.TopEnd),
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
    origin: String?,
    decided: Set<String>,
    unconfirmed: Set<String> = emptySet(),
    onFocusCall: ((runId: String, toolId: String) -> Unit)? = null,
): ConsentActions {
    val s = session ?: return ConsentActions.Unavailable
    return ConsentActions(
        sessionId = s.id,
        origin = origin,
        // ta-coik.24 / ta-coik.26: no lock at all, as on the web: its cards disable only once
        // `submitted` (chat-view.tsx 90fbb9f :1016-1021, :1191-1210), draw on a read-only or
        // handed-off session as on any other (:3672-3678, no such check) and send on any open
        // socket (use-tether.ts :337-344, :1667-1691): a send refused by a closed socket leaves them
        // answerable and shows "The secure link is reconnecting. Your input was not sent.", and the
        // server answers a read-only or handed-off session's decision with an `error`, shown. The
        // client does the same (transmitConsent) and keeps what the web keeps: the request must be
        // the one the card drew.
        lock = null,
        decided = decided,
        unconfirmed = unconfirmed,
        questionUnavailable = if (s.provider == "opencode" && s.engineGeneration != "opencode-serve-v2") ConsentActions.LEGACY_OPENCODE_QUESTION else null,
        onApproval = { requestId, fingerprint, choiceId, decision, granted -> vm.client.approval(s.id, requestId, fingerprint, choiceId, decision, granted) },
        onAnswer = { requestId, fingerprint, picks, skipped -> vm.client.answerQuestion(s.id, requestId, fingerprint, picks, skipped) },
        onOpenRun = { runId -> vm.selectRun(s.id, runId) },
        onFocusCall = onFocusCall ?: { runId, _ -> vm.selectRun(s.id, runId) },
    )
}

/**
 * ta-coik.19: the web draws the send bubbles at the foot of `.chat-scroll` whatever it shows above
 * them (chat-view.tsx 90fbb9f :3402-3411 the loading and empty states, a run tab; :3730-3737 the
 * bubbles). Here the views that are not the transcript's list get them below, scrolling on their own
 * once they would take more than most of the well.
 */
@Composable
internal fun WithSendBubbles(sends: SendBubbles, content: @Composable () -> Unit) {
    if (sends.isEmpty) {
        content()
        return
    }
    val t = LocalTetherTokens.current
    val spacing = transcriptSpacing(t, com.tether.app.ui.components.currentLayoutClass() == com.tether.app.ui.components.TetherLayoutClass.Phone)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = maxHeight * 0.6f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            SendBubbleColumn(
                sends,
                spacing.scrollGap,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = cap)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.padding),
            )
        }
    }
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
                label.uppercase(),
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 10.7.sp,
                letterSpacing = 0.08.em,
                modifier = Modifier.originalWords(label),
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
 * Workspace header: session name + status badge + actions; mono path line. ta-coik.22: End session
 * is live whenever the session has not exited, as on the web (workspace-header.tsx 90fbb9f :133);
 * offline the client says the session was not ended. [origin] (r3): the live link's server
 * ([com.tether.app.client.TetherClient.consentOrigin]); [server]: the server a confirmation is bound
 * to ([endSessionServer]); it acts while the link is not to another server ([endConfirmLive]).
 * [confirmBeforeEnd] (T10.1): off, End session sends at once instead of asking.
 */
@Composable
private fun WorkspaceHeader(vm: TetherViewModel, session: AgentSession, workspaceRoot: String?, origin: String?, server: String?, confirmBeforeEnd: Boolean = true) {
    val t = LocalTetherTokens.current
    var showTelemetry by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf<ChatEndDraw?>(null) }

    Column(Modifier.fillMaxWidth().background(t.graphite)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ta-28i: the session's title by the label rule.
            Text(
                com.tether.app.client.LabelText.title(session.name),
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
                    onClick = {
                        // dashboard.tsx 1bf4a465 :1192-1198: an isolated session's end asks about its teardown
                        // first (TeardownConfirmDialog), and that dialog is the confirmation.
                        if (confirmBeforeEnd && session.worktree == null) confirmEnd = ChatEndDraw(session.id, server)
                        else vm.client.endSession(session.id, server)
                    },
                    classes = KeyClasses.EndSession,
                    icon = TetherIcons.CircleStop,
                    iconSize = 16.dp,
                    contentDescription = "End session",
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
                        modifier = Modifier.weight(0.4f).originalWords(label),
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
        // r3: never on another server than the one it was opened for. ta-coik.22: otherwise live, as
        // the web's confirm key is, whatever the link or the copy (the client says when it cannot send).
        val endable = drawn.sessionId == session.id && endConfirmLive(drawn.drawnFor, origin)
        EndSessionDialog(
            sessionName = session.name,
            identity = drawn,
            endable = endable,
            onConfirm = {
                confirmEnd = null
                vm.client.endSession(drawn.sessionId, drawn.drawnFor)
            },
            onCancel = { confirmEnd = null },
        )
    }
}

/** A picked element and the session it was picked for (dashboard.tsx :235 `BrowserPick & { sessionId }`). */
private class SessionPick(val sessionId: String, val pick: com.tether.app.client.BrowserPick)
