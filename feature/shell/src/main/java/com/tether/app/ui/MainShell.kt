package com.tether.app.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tether.app.ui.search.GlobalSearchHost
import com.tether.app.ui.text.CopyNoticeHost
import com.tether.app.ui.text.CopyNotices
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.copySafely
import com.tether.app.ui.text.proseText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.chat.ChatScreen
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.files.WorkspaceFileBrowser
import com.tether.app.ui.files.rememberFileBrowserState
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.inspector.InspectorHost
import com.tether.app.ui.log.LogDialog
import com.tether.app.ui.log.LogDialogState
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.shell.EmptyStage
import com.tether.app.ui.shell.PhoneShell
import com.tether.app.ui.shell.PhoneShellSlots
import com.tether.app.ui.shell.ProviderAvailability
import com.tether.app.ui.shell.TopbarActions
import com.tether.app.ui.shell.WorkspaceHeaderActions
import com.tether.app.ui.shell.rememberPhoneShellState
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.chat.LocalCardStates
import com.tether.app.ui.chat.CardStateStore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import com.tether.app.ui.shell.DashboardView
import com.tether.app.ui.shell.DashboardViews
import com.tether.app.ui.shell.ExpandedShell
import com.tether.app.ui.shell.LinkReadout
import com.tether.app.ui.shell.TopBarDestination
import com.tether.app.ui.shell.ViewHistory
import com.tether.app.ui.shell.rememberPersistedPanels
import com.tether.app.ui.shell.shellLayoutFor
import com.tether.app.ui.statusline.ContextGauge
import com.tether.app.ui.statusline.SessionStatusline
import com.tether.app.ui.statusline.TelemetryMetrics

/** T15.2: the Overview's filter choice survives a rotation and a return to the Overview (overview.tsx:42). */
private val OverviewChoiceSaver = androidx.compose.runtime.saveable.listSaver<com.tether.app.ui.overview.OverviewChoice, String>(
    save = { listOf(it.workspace.orEmpty(), it.provider.orEmpty(), it.status.key) },
    restore = { saved ->
        com.tether.app.ui.overview.OverviewChoice(
            workspace = saved[0].ifEmpty { null },
            provider = saved[1].ifEmpty { null },
            status = com.tether.app.ui.overview.StatusTab.entries.firstOrNull { it.key == saved[2] } ?: com.tether.app.ui.overview.StatusTab.Active,
        )
    },
)

/** How long a copy control reads "Copied" (dashboard.tsx:1202, 1221, 1233). */
private const val CopiedFeedbackMs = 1_500L

/** T13.2 r3: an open End session confirmation: the session, and the server origin it was opened for. */
private data class EndTarget(val session: AgentSession, val drawnFor: String?)

/**
 * The signed-in app wired to the view model: below the 840dp layout cutoff the phone shell (the
 * web's mobile layout, [PhoneShell]); at or above it the expanded shell (the web's desktop layout,
 * [ExpandedShell]) with its column widths and collapsed rail persisted in [prefs] (the web's
 * per-device `sidebarWidth` / `inspectorWidth` / `sidebarCollapsed`). Both take the same slots
 * and the same shell state, so a window that crosses the cutoff keeps its popover and panel state.
 */
@Composable
fun MainShell(vm: TetherViewModel, prefs: UiPrefs) {
    val t = LocalTetherTokens.current
    val context = LocalContext.current
    val shell = rememberPhoneShellState()
    // T6.3 round 4 (H1): ONE store for every attention card, here above the phone / expanded switch
    // and the no-session branch, so a rotation, a window resize across 840dp or a session switch
    // never drops what the operator ticked (ChatScreen falls back to its own only when unprovided).
    val cardStates = rememberSaveable(saver = CardStateStore.Saver) { CardStateStore() }
    // I-2: the records belong to the CONFIGURED server (not the socket's origin, so a drop keeps
    // them): signing in to another server empties the store. Bound during composition, before any
    // card of the new server reads it.
    val configuredServer by vm.client.serverUrl.collectAsStateWithLifecycle()
    cardStates.bindTo(configuredServer)
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
    val layout = shellLayoutFor(windowWidthDp)
    val persisted = rememberPersistedPanels(prefs)
    val projectionTrees by vm.client.projectionTrees.collectAsStateWithLifecycle()

    val sessions by vm.client.sessions.collectAsStateWithLifecycle()
    val projections by vm.client.projections.collectAsStateWithLifecycle()
    val providers by vm.client.providers.collectAsStateWithLifecycle()
    val connection by vm.client.connection.collectAsStateWithLifecycle()
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
    val workspaceRoot by vm.client.workspaceRoot.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val unseenWarnings by vm.unseenWarnings.collectAsStateWithLifecycle()

    // T15.4 (dashboard.tsx view model, lib/dashboard-view.mjs): the top-level view on screen and the
    // ones behind it (Android Back plays the web's Back between views). Null current = boot: the
    // view is not resolved yet, so nothing view-specific is painted. Saved across rotation and
    // process death; resolved once per shell, like the web's once-per-load `bootView`.
    var viewHistorySaved by rememberSaveable { mutableStateOf(ViewHistory(null).encode()) }
    val viewHistory = ViewHistory.decode(viewHistorySaved)
    val view = viewHistory.current
    val navigateTo: (DashboardView) -> Unit = { next ->
        viewHistorySaved = ViewHistory.decode(viewHistorySaved).navigate(next).encode()
    }
    // Only a view with a screen here is ever shown (Scheduled is T9.3's; the bar shows it unavailable).
    val overviewOpen = view == DashboardView.Overview
    // T15.2: the selected session is on screen only in Sessions (dashboard.tsx:725 `activeSession`);
    // elsewhere the shell gets none: no header, stage, chat or inspector, and nothing marks it seen.
    val sessionsView = view == DashboardView.Sessions
    var settingsOpen by remember { mutableStateOf(false) }
    var overviewChoice by rememberSaveable(stateSaver = OverviewChoiceSaver) { mutableStateOf(com.tether.app.ui.overview.OverviewChoice()) }
    var reviewTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedSession = sessions.firstOrNull { it.id == selectedId }
    val session = selectedSession?.takeIf { sessionsView }
    val projection = selectedId?.let { projections[it] }
    val connected = connection == ConnectionState.Connected
    // T13.2 (SYNC_DESIGN §4): the link banner, and how current each session's copy is.
    val syncStates by vm.client.syncStates.collectAsStateWithLifecycle()
    val liveSessions by vm.client.liveSessions.collectAsStateWithLifecycle()
    // r3: the server the header's End session is drawn for (bound into its confirmation).
    val consentOrigin by vm.client.consentOrigin.collectAsStateWithLifecycle()
    val freshnessNow = com.tether.app.ui.components.rememberTickingNow()
    val shellFreshness = com.tether.app.ui.shell.ShellFreshness(
        banner = com.tether.app.ui.shell.ShellFreshness.bannerFor(connection),
        syncStates = syncStates,
        listLive = connected,
        now = freshnessNow,
        liveSessions = liveSessions,
        reportsFreshness = vm.client.reportsFreshness,
    )
    // r2 (SYNC_DESIGN §4.2): the gauge and the statusline read the session's copy; qualified while it is not live.
    val staleReading = session?.let { shellFreshness.staleLabel(it.id) }

    var showLog by remember { mutableStateOf(false) }
    // The web's <dialog> stays mounted, so its filters and last stats survive a close and reopen.
    val logState = remember { LogDialogState() }
    val eventLog by vm.client.eventLog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val refreshStats: () -> Unit = { scope.launch { logState.onStats(vm.client.fetchStats()) } }
    // T11.1: the workspace file browser (topbar Files key), on the selected session's cwd.
    val fileBrowser = rememberFileBrowserState(vm.client)
    SideEffect {
        fileBrowser.cwd = session?.cwd.orEmpty()
        fileBrowser.sessionName = session?.name.orEmpty()
    }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AgentSession?>(null) }
    var confirmEnd by remember { mutableStateOf<EndTarget?>(null) }
    // ta-28i: the working directory and the session id are server text: a copy carries them the SAFE
    // way (a hidden control as its visible token), and the notice's "Copy raw" is the only raw path.
    // r2: one-line names (TAB / LF / CR are tokens) copied strictly: EVERY token counts, a zero-width
    // space or a line break included, so the notice always says when the copy is not plain text.
    // The session id is copied without being shown, which is why this copy is strict.
    val copyNotices = remember { CopyNotices() }
    var copiedPath by remember { mutableStateOf(false) }
    var copiedTetherId by remember { mutableStateOf(false) }
    LaunchedEffect(copiedPath) { if (copiedPath) { delay(CopiedFeedbackMs); copiedPath = false } }
    LaunchedEffect(copiedTetherId) { if (copiedTetherId) { delay(CopiedFeedbackMs); copiedTetherId = false } }
    // T9.1 (dashboard.tsx:793-828): the inspector's reads, once per opened session — the diff summary
    // for any session (the server answers null for a non-repo cwd and pushes fresh summaries after),
    // an isolated checkout's scripts, and a checkout-pr session's change request.
    LaunchedEffect(session?.id, connected) {
        val s = session ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        vm.client.requestWorktreeDiff(s.id)
        if (s.worktree != null) vm.client.requestWorktreeScripts(s.id)
        if (s.worktree?.mode == "checkout-pr") vm.client.requestChangeRequest(s.id)
    }
    // T4.4: a session opened by a link reads like a sidebar pick: the drawer and the previous
    // session's popover close, so the session is on screen. T15.4: it shows Sessions (dashboard.tsx
    // `pushView("sessions", id)`): from another view Back returns there; from Sessions nothing is
    // pushed, so Back still leaves the app.
    LaunchedEffect(vm) {
        vm.openRequests.collect {
            navigateTo(DashboardView.Sessions)
            shell.onSessionSelected()
        }
    }
    // T15.2/T15.4: any new selection (a create, a resume, a link) shows Sessions (`selectSession`,
    // `reopen` and the `created` follow all `pushView("sessions")`).
    LaunchedEffect(vm) { vm.selectedSessionId.drop(1).collect { if (it != null) navigateTo(DashboardView.Sessions) } }
    LaunchedEffect(vm) { vm.openingHistoryId.drop(1).collect { if (it != null) navigateTo(DashboardView.Sessions) } }
    // T15.4 (dashboard.tsx bootView): resolve the view once. A session already chosen by a link
    // (a notification or deep link applied before this shell) wins; else the remembered view; else
    // an install that kept preferences before that record keeps last-session restoration
    // (Sessions); a fresh install starts on the Overview. A selection made while the read is in
    // flight has already resolved it (above), and wins.
    LaunchedEffect(Unit) {
        if (ViewHistory.decode(viewHistorySaved).current != null) return@LaunchedEffect
        val boot = prefs.viewBoot()
        if (ViewHistory.decode(viewHistorySaved).current != null) return@LaunchedEffect
        val (resolved, _) = DashboardViews.resolve(
            sessionLink = vm.selectedSessionId.value != null || vm.openingHistoryId.value != null,
            storedView = boot.storedView,
            hasExistingPreferences = boot.hasExistingPreferences,
        )
        navigateTo(resolved)
    }
    // dashboard.tsx `writeStoredView`: remember the last top-level view for the next launch.
    LaunchedEffect(view) { view?.let { prefs.setLastView(it.key) } }
    // T15.2 (dashboard.tsx:1392-1443 reviewRequest): the hand-off lands in the session; once a
    // snapshot on this connection has confirmed it, a request no longer pending is said so. A
    // session that never confirms within the bound says it could not be found. Nothing is answered.
    reviewTarget?.let { target ->
        LaunchedEffect(target) {
            val (sessionId, requestId) = target
            val confirmed = kotlinx.coroutines.withTimeoutOrNull(com.tether.app.ui.overview.OverviewPresentation.REVIEW_WAIT_MS) {
                vm.client.liveSessions.first { sessionId in it }
            }
            val pending = if (confirmed == null) null else com.tether.app.ui.overview.OverviewPresentation.reviewStillPending(vm.client.projectionTrees.value[sessionId], requestId)
            when (pending) {
                null -> vm.reportLocalError(com.tether.app.ui.overview.OverviewPresentation.REVIEW_NOT_FOUND)
                false -> vm.reportLocalError(com.tether.app.ui.overview.OverviewPresentation.REVIEW_RESOLVED)
                true -> Unit
            }
            reviewTarget = null
        }
    }

    // issue #189: a remembered session whose snapshot has not arrived yet reads as "reopening",
    // never as the welcome stage.
    val emptyStage = if (selectedId != null && session == null && sessions.isEmpty()) {
        EmptyStage.Reopening(connected)
    } else {
        EmptyStage.Welcome(connected, providers.map { ProviderAvailability(it.label, it.available) })
    }

    val metrics = TelemetryMetrics.from(session?.metrics)
    val sessionView = session?.let { s -> projectionTrees[s.id]?.let(::SessionView) }

    // T5.3 dashboard.tsx:1186-1194: Ctrl/Cmd+Shift+F opens the global search from anywhere.
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            isGlobalSearchShortcut(event).also { if (it) vm.openGlobalSearch() }
        },
    ) {
        val topbarActions = TopbarActions(
                onOpenDrawer = {},
                // dashboard.tsx:1264; the key is disabled without a session (fileBrowserDisabled).
                onOpenFiles = { fileBrowser.open() },
                // Hosts not built yet (Accounts and the Usage page, T9.2): shown unavailable.
                onOpenUsage = null,
                onOpenUsageAnalytics = null,
                onOpenSettings = { settingsOpen = true },
                // dashboard.tsx:1351 navigateTo. Sessions comes back to the conversation still
                // selected (or the empty workspace); Scheduled (T9.3) has no screen here yet.
                onNavigate = { next ->
                    shell.closeDrawer()
                    navigateTo(next)
                },
                views = setOf(DashboardView.Overview, DashboardView.Sessions),
                // dashboard.tsx:199 openLog: acknowledge the warnings, open, fetch fresh stats.
                onOpenLog = {
                    vm.openLog()
                    showLog = true
                    refreshStats()
                },
                onLogout = { showLogoutConfirm = true },
            )
        val headerActions = WorkspaceHeaderActions(
                onRename = { renaming = session },
                // r3: the confirmation is bound to the session AND the server it was opened for.
                onEndSession = { confirmEnd = session?.let { EndTarget(it, consentOrigin) } },
                onTogglePinned = { session?.let { vm.client.pin(it.id, !it.pinned) } },
                onCopyPath = {
                    session?.let {
                        if (copySafely(context, SafeText.line(it.cwd), copyNotices, raw = it.cwd, label = "Working directory", strict = true)) copiedPath = true
                    }
                },
                onCopyTetherId = {
                    session?.let {
                        if (copySafely(context, SafeText.line(it.id), copyNotices, raw = it.id, label = "Tether session id", strict = true)) copiedTetherId = true
                    }
                },
            )
        val slots = PhoneShellSlots(
                drawer = {
                    SessionDrawer(
                        vm = vm,
                        prefs = prefs,
                        sessions = sessions,
                        selectedId = selectedId,
                        workspaceRoot = workspaceRoot,
                        onSelect = { id ->
                            vm.selectSession(id)
                            navigateTo(DashboardView.Sessions)
                            shell.onSessionSelected()
                        },
                        onClose = shell::closeDrawer,
                        onOpenSettings = { settingsOpen = true },
                        selectedOnScreen = sessionsView,
                    )
                },
                chat = {
                    CompositionLocalProvider(LocalCardStates provides cardStates) {
                        ChatScreen(
                            vm = vm,
                            session = session,
                            projection = projection,
                            workspaceRoot = workspaceRoot,
                            prefs = prefs,
                            modifier = Modifier.fillMaxSize(),
                            onOpenDrawer = shell::openDrawer,
                            showWorkspaceHeader = false,
                        )
                    }
                },
                // T9.1: the full inspector, in the phone's telemetry sheet and the expanded column alike.
                inspector = { session?.let { InspectorHost(vm, it, sessionView) } },
                // T4.3's live gauge, dial and statusline (docs/parity/screens/statusline/README.md).
                gauge = { host -> ContextGauge(metrics, showLabel = host.showLabel, pressed = host.open, onClick = host.onToggle, stale = staleReading) },
                statusline = { expanded ->
                    SessionStatusline(metrics, sessionView, horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.End, stale = staleReading)
                },
                overview = if (view == null) {
                    // Boot: no view-specific content yet (the web's "boot" view paints none).
                    { Box(Modifier.fillMaxSize()) }
                } else if (!overviewOpen) {
                    null
                } else {
                    {
                        // Read-only: every action hands off to an existing handler the operator taps.
                        // Opening or reviewing SELECTS the session (attach, draft) and never marks it seen
                        // here; the session view's own rule applies once it is on screen.
                        val openFromOverview: (String) -> Unit = { id ->
                            vm.selectSession(id)
                            navigateTo(DashboardView.Sessions)
                            shell.onSessionSelected()
                        }
                        com.tether.app.ui.overview.OverviewHost(
                            client = vm.client,
                            choice = overviewChoice,
                            onChoice = { overviewChoice = it },
                            actions = com.tether.app.ui.overview.OverviewActions(
                                onOpenSession = openFromOverview,
                                onReviewRequest = { id, requestId ->
                                    reviewTarget = id to requestId
                                    openFromOverview(id)
                                },
                                onNewSession = { showProviderPicker = true },
                                onOpenEventLog = {
                                    vm.openLog()
                                    showLog = true
                                    refreshStats()
                                },
                            ),
                            // T15.3: the Host & usage tile goes here.
                            hostUsage = null,
                        )
                    }
                },
            )
        // T6.7 r2: when the toast goes away, every armed key re-arms (a tap aimed at the toast as it
        // vanished never lands on the key that was under it).
        var toastBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val armEpoch = rememberToastArmEpoch(toast != null, toastBounds)
        // T15.4: Back steps to the view behind this one (the web's browser Back between views; from
        // an Overview opened over a session, that session). The shell's own surfaces (menu, drawer,
        // popover, sheet) register later and so close first. Nothing behind: Back leaves the app.
        androidx.activity.compose.BackHandler(enabled = viewHistory.canGoBack) {
            viewHistorySaved = ViewHistory.decode(viewHistorySaved).back().encode()
        }
        val current = view?.let(TopBarDestination::of)
        val link = when (connection) {
            ConnectionState.Connected -> LinkReadout.Connected
            ConnectionState.Connecting -> LinkReadout.Connecting
            else -> LinkReadout.Reconnecting
        }
        // dashboard.tsx:1446: the rail belongs to Sessions (and Scheduled); the Overview is full width.
        val showRail = view == DashboardView.Sessions || view == DashboardView.Scheduled
        CompositionLocalProvider(com.tether.app.ui.shell.LocalShellFreshness provides shellFreshness, com.tether.app.ui.chat.LocalArmEpoch provides armEpoch) {
        if (layout == TetherLayoutClass.Expanded) {
            ExpandedShell(
                state = shell,
                panels = persisted.panels,
                onPanelsChange = persisted.onChange,
                session = session,
                workspaceRoot = workspaceRoot,
                emptyStage = emptyStage,
                topbar = topbarActions,
                header = headerActions,
                slots = slots,
                link = link,
                unseenWarnings = unseenWarnings,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                onStartSession = { showProviderPicker = true },
                current = current,
                showRail = showRail,
            )
        } else {
            PhoneShell(
                state = shell,
                session = session,
                workspaceRoot = workspaceRoot,
                emptyStage = emptyStage,
                unseenWarnings = unseenWarnings,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                onStartSession = { showProviderPicker = true },
                topbar = topbarActions,
                header = headerActions,
                slots = slots,
                current = current,
                link = link,
                showRail = showRail,
            )
        }
        }

        toast?.let { message ->
            LaunchedEffect(message) {
                delay(10_000)
                vm.dismissToast()
            }
            ErrorToast(
                message = message.text,
                fromServer = message.fromServer,
                onClose = { vm.dismissToast() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .zIndex(20f)
                    .onGloballyPositioned { toastBounds = it.boundsInWindow() },
            )
        }

        CopyNoticeHost(
            copyNotices,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .zIndex(21f),
        )
    }

    WorkspaceFileBrowser(fileBrowser)

    // T5.3: the cross-harness global search modal (dashboard.tsx:1701-1711).
    GlobalSearchHost(vm = vm, prefs = prefs, sessions = sessions, workspaceRoot = workspaceRoot, onCloseDrawer = shell::closeDrawer)

    if (showLog) {
        LogDialog(
            entries = eventLog.entries,
            sessions = sessions,
            state = logState,
            onRefresh = refreshStats,
            onDismiss = { showLog = false },
        )
    }

    // T15.4: the top bar's Settings and the rail footer's open the same sheet.
    if (settingsOpen) com.tether.app.ui.InterimSettingsDialog(prefs, onDismiss = { settingsOpen = false })

    if (showLogoutConfirm) {
        TetherDialog(onDismiss = { showLogoutConfirm = false }, title = "Sign out") {
            Text(
                // The server URL is kept to prefill the sign-in screen; the
                // credential is forgotten (and a cookie session revoked).
                "Sign out of this server?",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.6.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherKey(onClick = { showLogoutConfirm = false }, classes = KeyClasses.ButtonSecondary, label = "Cancel")
                TetherKey(
                    onClick = {
                        showLogoutConfirm = false
                        vm.logout()
                    },
                    classes = KeyClasses.ButtonDanger,
                    label = "Sign out",
                    icon = TetherIcons.LogOut,
                )
            }
        }
    }

    if (showProviderPicker) {
        TetherDialog(onDismiss = { showProviderPicker = false }, title = "New session") {
            providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = provider.available) {
                            showProviderPicker = false
                            vm.createSession(provider.id)
                        }
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderGlyph(provider.glyph)
                    Text(
                        provider.label,
                        color = if (provider.available) t.ink else t.faint,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 13.6.sp,
                    )
                }
            }
        }
    }

    renaming?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        val submit = {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty() && trimmed != target.name) vm.client.rename(target.id, trimmed)
            renaming = null
        }
        TetherDialog(
            onDismiss = { renaming = null },
            title = "Rename session",
            footer = {
                TetherKey(onClick = { renaming = null }, classes = KeyClasses.ButtonSecondary, label = "Cancel")
                TetherKey(onClick = submit, classes = KeyClasses.ButtonPrimary, label = "Rename", enabled = name.isNotBlank())
            },
        ) {
            TetherInputWell(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }

    confirmEnd?.let { drawn ->
        val (target, drawnFor) = drawn
        // T13.2 r2: the confirmation acts only while the session is still live (a link that dropped
        // under the open dialog disables it; the client refuses it too). r3: and only on the server
        // it was opened for (a switch under the open dialog disables it; the client refuses it too).
        // T6.7: the shared confirmation closes itself the moment either stops holding.
        val endable = shellFreshness.sessionLive(target.id) && drawnFor != null && drawnFor == consentOrigin
        com.tether.app.ui.chat.EndSessionDialog(
            sessionName = target.name,
            // The session and server it was opened for (not the row itself, which moves on every update).
            identity = target.id to drawnFor,
            endable = endable,
            onConfirm = {
                confirmEnd = null
                vm.client.kill(target.id, drawnFor, requireLive = true)
            },
            onCancel = { confirmEnd = null },
        )
    }
}

/**
 * T6.7 r2/r3: the arm epoch every armed key re-arms on. It moves when the toast uncovers something:
 * it goes away, or its bounds ([bounds], in the window) no longer cover what they covered (it shrank
 * or moved). A toast that grows or keeps its bounds (new words, same size) moves nothing, so a
 * server's stream of text changes cannot keep the keys disarmed.
 */
@Composable
internal fun rememberToastArmEpoch(shown: Boolean, bounds: androidx.compose.ui.geometry.Rect?): Int {
    var epoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val covered = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    LaunchedEffect(shown, bounds) {
        val before = covered[0]
        if (!shown) {
            if (before != null) epoch++
            covered[0] = null
        } else if (bounds != null) {
            if (before != null && !bounds.covers(before)) epoch++
            covered[0] = bounds
        }
    }
    return epoch
}

private fun androidx.compose.ui.geometry.Rect.covers(other: androidx.compose.ui.geometry.Rect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

/** T6.7: the caption over an error toast whose words a server wrote. */
const val SERVER_ERROR_CAPTION = "From the server"

/** T6.7: the error toast's tag (its words are its semantics). */
const val ERROR_TOAST_TAG = "error-toast"

/**
 * `.error-toast` (dashboard.tsx:1940-1946): fixed at the bottom, danger-wash surface, 1px brick
 * border, the AlertCircle glyph, the words, and the 44dp "Dismiss error" X. `role="alert"`: TalkBack
 * reads it the moment it appears. T6.7: words a SERVER wrote ([fromServer]; the client cleaned them)
 * sit under a "From the server" caption and are read as "Server error: …", so a server's text can
 * never pass for the app's own ("The secure link is reconnecting…"). Android addition: the web's
 * toast shows the server's text bare.
 */
@Composable
fun ErrorToast(message: String, onClose: () -> Unit, modifier: Modifier = Modifier, fromServer: Boolean = false) {
    val t = LocalTetherTokens.current
    // ta-28i r2: the words (a server's, often) are prose: every explicit bidi control a token, drawn
    // and read alike, laid out in their content's direction.
    val drawn = proseText(message)
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // T6.7 r2/r3: the toast is a surface, not a hole. A pointer handler here makes the toast
            // the hit target, so no touch on it reaches the composer's keys (Interrupt, Send)
            // underneath. It only observes: consuming would cancel the X's own tap on the first
            // move between down and up (the Final pass runs parent-first).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                }
            }
            .background(t.dangerWash, RoundedCornerShape(TetherDimens.radiusSm))
            .border(1.dp, t.brick, RoundedCornerShape(TetherDimens.radiusSm))
            .padding(12.dp)
            .testTag(ERROR_TOAST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TetherIcons.CircleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(18.dp))
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics {
                    contentDescription = if (fromServer) "Server error: ${drawn.text}" else "Error: ${drawn.text}"
                    liveRegion = LiveRegionMode.Assertive
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (fromServer) {
                Text(
                    text = SERVER_ERROR_CAPTION.uppercase(),
                    color = t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.strong,
                    fontSize = 9.9.sp,
                    letterSpacing = 0.06.em,
                )
            }
            Text(
                text = drawn,
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 12.5.sp,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
            Icon(TetherIcons.X, contentDescription = "Dismiss error", tint = t.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** T5.3 dashboard.tsx:1186-1194 — Ctrl/Cmd+Shift+F (not Alt) opens the global search. */
internal fun isGlobalSearchShortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    return event.type == KeyEventType.KeyDown && event.key == Key.F && ctrl && event.isShiftPressed && !event.isAltPressed
}
