package com.tether.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.tether.app.ui.shell.ExpandedShell
import com.tether.app.ui.shell.LinkReadout
import com.tether.app.ui.shell.rememberPersistedPanels
import com.tether.app.ui.shell.shellLayoutFor
import com.tether.app.ui.statusline.ContextGauge
import com.tether.app.ui.statusline.SessionStatusline
import com.tether.app.ui.statusline.TelemetryMetrics

/** How long a copy control reads "Copied" (dashboard.tsx:1202, 1221, 1233). */
private const val CopiedFeedbackMs = 1_500L

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
    val toast by vm.activeToast.collectAsStateWithLifecycle()
    val unseenWarnings by vm.unseenWarnings.collectAsStateWithLifecycle()

    val session = sessions.firstOrNull { it.id == selectedId }
    val projection = selectedId?.let { projections[it] }
    val connected = connection == ConnectionState.Connected

    var showLog by remember { mutableStateOf(false) }
    // The web's <dialog> stays mounted, so its filters and last stats survive a close and reopen.
    val logState = remember { LogDialogState() }
    val eventLog by vm.client.eventLog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val refreshStats: () -> Unit = { scope.launch { logState.onStats(vm.client.fetchStats()) } }
    // T11.1: the workspace file browser (topbar Files key), on the selected session's cwd.
    val fileBrowser = rememberFileBrowserState(vm.client.files)
    SideEffect {
        fileBrowser.cwd = session?.cwd.orEmpty()
        fileBrowser.sessionName = session?.name.orEmpty()
    }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AgentSession?>(null) }
    var confirmEnd by remember { mutableStateOf<AgentSession?>(null) }
    var copiedPath by remember { mutableStateOf(false) }
    var copiedTetherId by remember { mutableStateOf(false) }
    LaunchedEffect(copiedPath) { if (copiedPath) { delay(CopiedFeedbackMs); copiedPath = false } }
    LaunchedEffect(copiedTetherId) { if (copiedTetherId) { delay(CopiedFeedbackMs); copiedTetherId = false } }

    // issue #189: a remembered session whose snapshot has not arrived yet reads as "reopening",
    // never as the welcome stage.
    val emptyStage = if (selectedId != null && session == null && sessions.isEmpty()) {
        EmptyStage.Reopening(connected)
    } else {
        EmptyStage.Welcome(connected, providers.map { ProviderAvailability(it.label, it.available) })
    }

    val metrics = TelemetryMetrics.from(session?.metrics)
    val sessionView = session?.let { s -> projectionTrees[s.id]?.let(::SessionView) }

    Box(Modifier.fillMaxSize()) {
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
                onEndSession = { confirmEnd = session },
                onTogglePinned = { session?.let { vm.client.pin(it.id, !it.pinned) } },
                onCopyPath = {
                    session?.let {
                        copyToClipboard(context, "Working directory", it.cwd)
                        copiedPath = true
                    }
                },
                onCopyTetherId = {
                    session?.let {
                        copyToClipboard(context, "Tether session id", it.id)
                        copiedTetherId = true
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
                },
                inspector = { session?.let { InterimTelemetry(it) } },
                // T4.3's live gauge, dial and statusline (docs/parity/screens/statusline/README.md).
                gauge = { host -> ContextGauge(metrics, showLabel = host.showLabel, pressed = host.open, onClick = host.onToggle) },
                statusline = { expanded ->
                    SessionStatusline(metrics, sessionView, horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.End)
                },
            )
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

        toast?.let { message ->
            LaunchedEffect(message) {
                delay(10_000)
                vm.dismissToast()
            }
            ErrorToast(
                message = message,
                onClose = { vm.dismissToast() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .zIndex(20f),
            )
        }
    }

    WorkspaceFileBrowser(fileBrowser)

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

    confirmEnd?.let { target ->
        TetherDialog(onDismiss = { confirmEnd = null }, title = "End session") {
            Text(
                "Stop the agent process for \"${target.name}\"?",
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
                        vm.client.kill(target.id)
                    },
                    classes = KeyClasses.ButtonDanger,
                    label = "End session",
                    icon = TetherIcons.CircleStop,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * The telemetry rows the app showed before the shell (its old Telemetry dialog), kept as the
 * panel's body until the inspector (T9.1) fills the slot.
 */
@Composable
private fun InterimTelemetry(session: AgentSession) {
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
}

/** Fixed-bottom error toast: danger-wash surface, 1px brick border. */
@Composable
fun ErrorToast(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(t.dangerWash, RoundedCornerShape(TetherDimens.radiusSm))
            .border(1.dp, t.brick, RoundedCornerShape(TetherDimens.radiusSm))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TetherIcons.CircleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(18.dp))
        Text(
            text = message,
            color = t.white,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
            Icon(TetherIcons.X, contentDescription = "Dismiss", tint = t.muted, modifier = Modifier.size(16.dp))
        }
    }
}
