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
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.compactNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.chat.LocalCardStates
import com.tether.app.ui.chat.CardStateStore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import com.tether.app.ui.shell.ExpandedShell
import com.tether.app.ui.shell.LinkReadout
import com.tether.app.ui.shell.rememberPersistedPanels
import com.tether.app.ui.shell.shellLayoutFor
import com.tether.app.ui.statusline.ContextGauge
import com.tether.app.ui.statusline.SessionStatusline
import com.tether.app.ui.statusline.TelemetryMetrics

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

    val session = sessions.firstOrNull { it.id == selectedId }
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
    val copyNotices = remember { CopyNotices() }
    var copiedPath by remember { mutableStateOf(false) }
    var copiedTetherId by remember { mutableStateOf(false) }
    LaunchedEffect(copiedPath) { if (copiedPath) { delay(CopiedFeedbackMs); copiedPath = false } }
    LaunchedEffect(copiedTetherId) { if (copiedTetherId) { delay(CopiedFeedbackMs); copiedTetherId = false } }
    // T4.4: a session opened by a link reads like a sidebar pick: the drawer and the previous
    // session's popover close, so the session is on screen and Back leaves the app.
    LaunchedEffect(vm) { vm.openRequests.collect { shell.onSessionSelected() } }

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
                // Hosts not built yet (accounts/usage T9.2): their keys render disabled.
                onOpenUsage = null,
                onOpenUsageAnalytics = null,
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
                        if (copySafely(context, SafeText.code(it.cwd), copyNotices, raw = it.cwd, label = "Working directory")) copiedPath = true
                    }
                },
                onCopyTetherId = {
                    session?.let {
                        if (copySafely(context, SafeText.code(it.id), copyNotices, raw = it.id, label = "Tether session id")) copiedTetherId = true
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
                            shell.onSessionSelected()
                        },
                        onClose = shell::closeDrawer,
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
                inspector = { session?.let { InterimTelemetry(it, sessionView) } },
                // T4.3's live gauge, dial and statusline (docs/parity/screens/statusline/README.md).
                gauge = { host -> ContextGauge(metrics, showLabel = host.showLabel, pressed = host.open, onClick = host.onToggle, stale = staleReading) },
                statusline = { expanded ->
                    SessionStatusline(metrics, sessionView, horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.End, stale = staleReading)
                },
            )
        // T6.7 r2: when the toast goes away, every armed key re-arms (a tap aimed at the toast as it
        // vanished never lands on the key that was under it).
        var toastBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val armEpoch = rememberToastArmEpoch(toast != null, toastBounds)
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
                link = when (connection) {
                    ConnectionState.Connected -> LinkReadout.Connected
                    ConnectionState.Connecting -> LinkReadout.Connecting
                    else -> LinkReadout.Reconnecting
                },
                unseenWarnings = unseenWarnings,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                onStartSession = { showProviderPicker = true },
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
 * The telemetry rows the app showed before the shell (its old Telemetry dialog), kept as the
 * panel's body until the inspector (T9.1) fills the slot.
 */
@Composable
private fun InterimTelemetry(session: AgentSession, state: SessionView? = null) {
    val t = LocalTetherTokens.current
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
            Text(value, color = t.ink, fontFamily = JetBrainsMono, fontSize = 11.8.sp, modifier = Modifier.weight(0.6f))
        }
    }
    // T6.6 (inspector.tsx:520-556): the limit notice (Wrap-Up while it covers the turn) and MCP health.
    com.tether.app.ui.inspector.InspectorLimitNotice(state)
    com.tether.app.ui.inspector.InspectorMcpHealth(session.provider, session.engineGeneration, state)
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
                    contentDescription = if (fromServer) "Server error: $message" else "Error: $message"
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
                text = message,
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
