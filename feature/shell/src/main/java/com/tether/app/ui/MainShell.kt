package com.tether.app.ui

import com.tether.app.ui.util.RecompositionProbe
import android.content.Context
import androidx.compose.foundation.border
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
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
import com.tether.app.ui.text.copyExact
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
import com.tether.app.ui.inspector.InspectorReads
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.TextOnlyDelta
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.util.rememberLatched
import com.tether.app.ui.components.ProvideWindowWidthDp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.windowWidthDp
import com.tether.app.ui.chat.LocalCardStates
import com.tether.app.ui.chat.LocalWorkspaceFileOpener
import com.tether.app.ui.chat.WorkspaceFileLinks
import com.tether.app.ui.chat.LocalTranscriptScrollStore
import com.tether.app.ui.chat.TranscriptScrollStore
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

/**
 * ta-jtfq: the session's view of its tree for the statusline and the inspector, read where they are drawn. Held while a
 * delta only appends words to an agent message (neither shows those), so a stream does not recompose them.
 */
@Composable
private fun rememberSessionView(trees: State<Map<String, JsObj>>, sessionId: String?): SessionView? {
    val tree = sessionId?.let { trees.value[it] }
    val held = rememberLatched(tree, sessionId) { was, next -> TextOnlyDelta.isTextOnly(was, next) }
    return remember(held) { held?.let(::SessionView) }
}

/** ta-coik.52: the Overview's filter choice and the server it was read for (null origin: no server). */
internal data class OverviewChoiceFor(val origin: String?, val choice: com.tether.app.ui.overview.OverviewChoice)

/** T15.2: the Overview's filter choice survives a rotation and a return to the Overview (overview.tsx:42). */
/** ta-coik.20: the Rename dialog's target (the session id and the name it had); saved so a rotation keeps the dialog and its typed name. */
private data class RenameTarget(val id: String, val name: String)

private val RenameTargetSaver = androidx.compose.runtime.saveable.listSaver<RenameTarget?, String>(
    save = { t -> if (t == null) emptyList() else listOf(t.id, t.name) },
    restore = { v -> if (v.size == 2) RenameTarget(v[0], v[1]) else null },
)

private val OverviewChoiceSaver = androidx.compose.runtime.saveable.listSaver<OverviewChoiceFor?, String?>(
    save = { listOf(it?.origin, it?.choice?.workspace, it?.choice?.provider, it?.choice?.status?.key) },
    restore = { saved -> saved[3]?.let { OverviewChoiceFor(saved[0], overviewChoiceOf(com.tether.app.ui.prefs.OverviewFilters(saved[1], saved[2], it))) } },
)

/** overview.tsx 90fbb9f :43-55 `readChoice`: a status that is not one of the tabs reads as Active. */
internal fun overviewChoiceOf(filters: com.tether.app.ui.prefs.OverviewFilters) = com.tether.app.ui.overview.OverviewChoice(
    workspace = filters.workspace?.takeIf(String::isNotEmpty),
    provider = filters.provider?.takeIf(String::isNotEmpty),
    status = com.tether.app.ui.overview.StatusTab.entries.firstOrNull { it.key == filters.status } ?: com.tether.app.ui.overview.StatusTab.Active,
)

/** overview.tsx :56-62 `writeChoice`'s record. */
internal fun overviewFiltersOf(choice: com.tether.app.ui.overview.OverviewChoice) =
    com.tether.app.ui.prefs.OverviewFilters(choice.workspace, choice.provider, choice.status.key)

/** How long a copy control reads "Copied" (dashboard.tsx:1202, 1221, 1233). */
private const val CopiedFeedbackMs = 1_500L

/** T13.2 r3: an open End session confirmation: the session, and the server origin it was opened for. */
private data class EndTarget(val session: AgentSession, val drawnFor: String?)

/**
 * The signed-in app wired to the view model: below the 768dp layout cutoff (the web's 48rem) the phone shell (the
 * web's mobile layout, [PhoneShell]); at or above it the expanded shell (the web's desktop layout,
 * [ExpandedShell]) with its column widths and collapsed rail persisted in [prefs] (the web's
 * per-device `sidebarWidth` / `inspectorWidth` / `sidebarCollapsed`). Both take the same slots
 * and the same shell state, so a window that crosses the cutoff keeps its popover and panel state.
 */
@Composable
fun MainShell(vm: TetherViewModel, prefs: UiPrefs) {
    // ta-09ca: measure the window ONCE and provide it, so a Dialog or Popup (each with its own
    // LocalWindowInfo) reads the same width as the shell. Measured before the provider, so this is
    // the window's own container size.
    ProvideWindowWidthDp { MainShellBody(vm, prefs) }
}

@Composable
private fun MainShellBody(vm: TetherViewModel, prefs: UiPrefs) {
    RecompositionProbe("MainShellBody")
    val t = LocalTetherTokens.current
    val context = LocalContext.current
    val shell = rememberPhoneShellState()
    // T6.3 round 4 (H1): ONE store for every attention card, here above the phone / expanded switch
    // and the no-session branch, so a rotation, a window resize across 768dp or a session switch
    // never drops what the operator ticked (ChatScreen falls back to its own only when unprovided).
    val cardStates = rememberSaveable(saver = CardStateStore.Saver) { CardStateStore() }
    // I-2: the records belong to the CONFIGURED server (not the socket's origin, so a drop keeps
    // them): signing in to another server empties the store. Bound during composition, before any
    // card of the new server reads it.
    val configuredServer by vm.client.serverUrl.collectAsStateWithLifecycle()
    cardStates.bindTo(configuredServer)
    // ta-jyj0: and ONE transcript scroll position, above the same switch: a rotation that swaps PhoneShell
    // for ExpandedShell keeps the reader's place (and saved across the activity's recreation: MainActivity handles no configuration change itself).
    val transcriptScroll = rememberSaveable(saver = TranscriptScrollStore.Saver) { TranscriptScrollStore() }
    val layout = shellLayoutFor(windowWidthDp())
    val persisted = rememberPersistedPanels(prefs, vm.client.serverUrl)
    // ta-jtfq: the two per-delta maps are held as State and read only where they are shown (the chat, the statusline,
    // the inspector), never here: a streamed delta changes both at the delta rate, and a read in this body would
    // recompose the whole shell, its drawer and the composer for it.
    val projectionTrees = vm.client.projectionTrees.collectAsStateWithLifecycle()

    val sessions by vm.client.sessions.collectAsStateWithLifecycle()
    val projections = vm.client.projections.collectAsStateWithLifecycle()
    val providers by vm.client.providers.collectAsStateWithLifecycle()
    val connection by vm.client.connection.collectAsStateWithLifecycle()
    // ta-coik.42: the web's two slots (dashboard.tsx 90fbb9f :274 `activeId`, :292 `pendingSessionId`).
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val pendingSessionId by vm.pendingSessionId.collectAsStateWithLifecycle()
    val workspaceRoot by vm.client.workspaceRoot.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val unseenWarnings by vm.unseenWarnings.collectAsStateWithLifecycle()
    // ta-abm (dashboard.tsx:345): Send pressed, the create in flight — the sheet gives way to the
    // hand-off stage, which takes the workspace ahead of the session, the Overview and the empty stage.
    val draftOpen by vm.draftOpen.collectAsStateWithLifecycle()
    val draft by vm.draftComposer.state.collectAsStateWithLifecycle()
    val draftLaunching = draftOpen && draft.creating
    val draftCatalog by vm.client.providerCatalog.collectAsStateWithLifecycle()
    val draftCatalogLive by vm.client.providerCatalogLive.collectAsStateWithLifecycle()
    // dashboard.tsx openDraft: raising the sheet closes the drawer.
    LaunchedEffect(draftOpen) { if (draftOpen) shell.closeDrawer() }

    // T15.4 (dashboard.tsx view model, lib/dashboard-view.mjs): the top-level view on screen and the
    // ones behind it (Android Back plays the web's Back between views). Null current = boot: the
    // view is not resolved yet, so nothing view-specific is painted. Saved across rotation and
    // process death; resolved once per shell, like the web's once-per-load `bootView`.
    // A session already chosen by a link (a notification or deep link applied before this shell
    // composed) resolves the boot at once: the explicit session link wins, so nothing is read.
    var viewHistorySaved by rememberSaveable {
        val linked = vm.pendingSessionId.value != null || vm.activeId.value != null || vm.openingHistoryId.value != null || vm.bootLinkPending.value
        mutableStateOf(ViewHistory(if (linked) DashboardView.Sessions else null).encode())
    }
    // T15.4 r2: whether the operator has moved between views yet (the bar, the rail, a hand-off,
    // Back). Until then the boot is still the app's own, so a cold-start link may still claim it.
    var userNavigated by rememberSaveable { mutableStateOf(false) }
    val viewHistory = ViewHistory.decode(viewHistorySaved)
    val view = viewHistory.current
    // T9.2: the Usage page (the web's `/usage` route, not a console view): shown over the views,
    // never stored as the last view; any move to a view leaves it (the web navigates away).
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    val navigateTo: (DashboardView) -> Unit = { next ->
        usageOpen = false
        // ta-coik.43 (dashboard.tsx 90fbb9f :1357-1365, `pushView("sessions", target)`): a Sessions
        // entry starts out naming the target; the effect below keeps it on the chat shown.
        val target = vm.pendingSessionId.value ?: vm.activeId.value
        viewHistorySaved = ViewHistory.decode(viewHistorySaved).navigate(next, target).encode()
    }
    val overviewOpen = view == DashboardView.Overview
    // T9.3: Scheduled (dashboard.tsx:223 `scheduledActionsOpen`), in the workspace area beside the rail.
    val scheduledOpen = view == DashboardView.Scheduled
    // T15.2: the selected session is on screen only in Sessions (dashboard.tsx:725 `activeSession`);
    // elsewhere the shell gets none: no header, stage, chat or inspector, and nothing marks it seen.
    val sessionsView = view == DashboardView.Sessions && !usageOpen
    // Saveable: a rotation recreates the activity, and an open Settings (its tab and draft) comes back.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // T8.4: the tab Settings opens on (General; Advanced from the GitHub dialog's "Set up GitHub connection").
    var settingsTab by rememberSaveable { mutableStateOf(com.tether.app.ui.settings.SettingsTab.General) }
    // ta-3e7: the Studio welcome's "Open workspace" (dashboard.tsx folderDialogRef), at shell level.
    var workspacePickerOpen by rememberSaveable { mutableStateOf(false) }
    // ta-coik.52 (overview.tsx 90fbb9f :24, :43-62): the filter choice is kept per server, as the web's
    // per-origin `tether:overviewFilters`: read for the server on screen (the Overview waits for that
    // read, as the web's first render already has it) and written on every change.
    val overviewServer = com.tether.app.client.serverOrigin(vm.client.serverUrl.collectAsStateWithLifecycle().value)
    var overviewChoiceFor by rememberSaveable(stateSaver = OverviewChoiceSaver) { mutableStateOf<OverviewChoiceFor?>(null) }
    LaunchedEffect(prefs, overviewServer) {
        if (overviewChoiceFor?.origin != overviewServer || overviewChoiceFor == null) {
            overviewChoiceFor = OverviewChoiceFor(overviewServer, overviewChoiceOf(prefs.overviewFilters(overviewServer)))
        }
    }
    val overviewChoice = overviewChoiceFor?.takeIf { it.origin == overviewServer }?.choice
    val overviewScope = rememberCoroutineScope()
    val setOverviewChoice: (com.tether.app.ui.overview.OverviewChoice) -> Unit = { next ->
        overviewChoiceFor = OverviewChoiceFor(overviewServer, next)
        overviewScope.launch { prefs.setOverviewFilters(overviewServer, overviewFiltersOf(next)) }
    }
    var reviewTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    // ta-coik.41 (dashboard.tsx 90fbb9f :609-611, :709-717): the selection is on screen only while
    // its chat is listed (`visibleSessions`: an ended one only with "Show ended sessions" on); one that
    // left the list stays selected, so it is back on screen when it returns.
    // Until the stored setting is first read (null) nothing is on screen, so an ended chat never
    // flashes in; saved, so a rotation does not pass through that state (and remount the chat).
    var showEnded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(prefs) { prefs.showEnded(vm.client.serverUrl).distinctUntilChanged().collect { showEnded = it } }
    LaunchedEffect(showEnded) { vm.setShowEnded(showEnded) }
    val visibleSessions = if (showEnded == false) sessions.filter { it.status != "exited" } else sessions
    // ta-coik.42 (dashboard.tsx 90fbb9f :710-717): the pending target while it is listed, else the pick;
    // resolved here from the same list the shell shows, so the switch lands in the same frame.
    val selectedId = selectionShown(pendingSessionId, activeId) { id -> visibleSessions.any { it.id == id } }
    val selectedSession = if (showEnded == null) null else visibleSessions.firstOrNull { it.id == selectedId }
    val session = selectedSession?.takeIf { sessionsView }
    // ta-coik.39 r2: the chat view on screen, as the web mounts its ChatView (dashboard.tsx 90fbb9f
    // :1572-1647: Sessions, a listed selection, no create in flight; the Usage page is another route
    // there). Reported on every change; the view model counts only a real mount, so a recomposition
    // or rotation (which reports the same chat again) attaches nothing.
    // ta-coik.43 (dashboard.tsx 90fbb9f :1080-1096, `replaceState`): while Sessions shows, its entry
    // names the chat on screen; with chats listed and none shown it names none; an empty list (a
    // cold boot still waiting for its target) leaves it alone.
    val noneListed = visibleSessions.isEmpty()
    LaunchedEffect(sessionsView, showEnded == null, selectedSession?.id, noneListed, viewHistorySaved) {
        if (!sessionsView || showEnded == null) return@LaunchedEffect
        val shown = selectedSession?.id
        if (shown == null && noneListed) return@LaunchedEffect
        val history = ViewHistory.decode(viewHistorySaved)
        if (history.current == DashboardView.Sessions) viewHistorySaved = history.withSession(shown).encode()
    }
    val mountedChat = session?.takeUnless { draftLaunching }?.id
    SideEffect { transcriptScroll.retainOnly(mountedChat) }
    LaunchedEffect(mountedChat) { vm.chatViewShown(mountedChat) }
    // ta-coik.41: the web's remembered-chat restore, one-time pick and last-opened record.
    WebSelectionEffects(
        vm = vm,
        prefs = prefs,
        scope = rememberCoroutineScope(),
        view = view,
        sessionsView = sessionsView,
        session = session,
        workspaceRoot = workspaceRoot,
        onReopened = shell::closeDrawer,
    )
    // dashboard.tsx:1359 reads the stored record at the moment of navigation.
    val rememberedChat = remember(prefs) { RememberedChat() }
    LaunchedEffect(prefs) {
        kotlinx.coroutines.flow.combine(prefs.preferences, vm.client.serverUrl) { p, url -> p.lastOpenedFor(com.tether.app.client.serverOrigin(url)) }
            .collect { rememberedChat.value = it }
    }
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
    // T9.2: the Accounts dialog (usage-accounts-dialog.tsx) and its two confirmations; the state
    // outlives the dialog as the web's mounted `<dialog>` does. Each call goes to the server shown.
    val usageScope = rememberCoroutineScope()
    val usageOrigin: () -> String? = { com.tether.app.client.serverOrigin(vm.client.serverUrl.value) }
    val accounts = remember(vm) { com.tether.app.ui.usage.UsageAccountsState(usageScope, { vm.client.usage }, usageOrigin) }
    val codexReset = remember(vm) { com.tether.app.ui.usage.CodexResetState(usageScope, { vm.client.usage }, usageOrigin) }
    val claudeReset = remember(vm) { com.tether.app.ui.usage.ClaudeResetState(usageScope, { vm.client.usage }, usageOrigin) }
    // A rotation brings an open Accounts dialog back, refetching (its reading is not saved).
    var accountsWasOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (accountsWasOpen && !accounts.visible) accounts.open() }
    SideEffect { accountsWasOpen = accounts.visible }
    val sessionControls by vm.client.sessionControls.collectAsStateWithLifecycle()
    val openUsagePage: () -> Unit = {
        shell.closeDrawer()
        userNavigated = true
        usageOpen = true
    }
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
    // ta-9jnm: a file path in the agent's prose opens in this browser (the same capability as the Files key).
    val fileLinkCwd = session?.cwd.orEmpty()
    val fileLinks = remember(fileLinkCwd, fileBrowser) {
        fileLinkCwd.takeIf { it.isNotEmpty() }?.let { cwd -> WorkspaceFileLinks(cwd) { path -> fileBrowser.open(path) } }
    }
    var renaming by rememberSaveable(stateSaver = RenameTargetSaver) { mutableStateOf<RenameTarget?>(null) }
    var confirmEnd by remember { mutableStateOf<EndTarget?>(null) }
    // T10.1 (dashboard.tsx:1170-1180 `endSession`): Settings → General's "Confirm before ending".
    // Off, the header's End session sends at once; on (and until the stored value is read), it asks.
    val confirmBeforeEndFlow = remember(prefs) { prefs.preferencesFor(vm.client.serverUrl).map { it.confirmBeforeEnd }.distinctUntilChanged() }
    val confirmBeforeEnd by confirmBeforeEndFlow.collectAsStateWithLifecycle(initialValue = true)
    // ta-28i + ta-coik.64: the working directory, the session id and the resume command are server text:
    // a copy puts the EXACT string on the clipboard (dashboard.tsx writeText(cwd / id / command)); the
    // screen draws a hidden control as a visible token and the notice only says how many the copy holds.
    // One-line names are counted strictly (a zero-width space or a line break included).
    val copyNotices = remember { CopyNotices() }
    var copiedPath by remember { mutableStateOf(false) }
    var copiedTetherId by remember { mutableStateOf(false) }
    var copiedResumeCommand by remember { mutableStateOf(false) }
    LaunchedEffect(copiedPath) { if (copiedPath) { delay(CopiedFeedbackMs); copiedPath = false } }
    LaunchedEffect(copiedTetherId) { if (copiedTetherId) { delay(CopiedFeedbackMs); copiedTetherId = false } }
    LaunchedEffect(copiedResumeCommand) { if (copiedResumeCommand) { delay(CopiedFeedbackMs); copiedResumeCommand = false } }
    // T9.1 (dashboard.tsx:793-828): the inspector's reads for the opened session (ta-dl4: each on
    // the web's own dependency list).
    InspectorReads(vm.client, session, connected)
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
    // `reopen` and the `created` follow all `pushView("sessions")`). ta-coik.42: a new pick or pending
    // target, not a pending target becoming listed (the web pushes nothing then).
    LaunchedEffect(vm) { vm.activeId.drop(1).collect { if (it != null) navigateTo(DashboardView.Sessions) } }
    LaunchedEffect(vm) { vm.pendingSessionId.drop(1).collect { if (it != null) navigateTo(DashboardView.Sessions) } }
    LaunchedEffect(vm) { vm.openingHistoryId.drop(1).collect { if (it != null) navigateTo(DashboardView.Sessions) } }
    // T15.4 (dashboard.tsx bootView): resolve the view once. A session chosen by a link wins
    // (above, or while the read is in flight: the selection collectors have resolved it); else the
    // remembered view; else an install that kept preferences before that record keeps last-session
    // restoration (Sessions); a fresh install starts on the Overview.
    LaunchedEffect(Unit) {
        if (ViewHistory.decode(viewHistorySaved).current != null) return@LaunchedEffect
        // ta-coik.47: the server's own remembered view (the web's localStorage is per origin).
        val boot = prefs.viewBoot(com.tether.app.client.serverOrigin(vm.client.serverUrl.value))
        if (ViewHistory.decode(viewHistorySaved).current != null) return@LaunchedEffect
        val (resolved, _) = DashboardViews.resolve(
            sessionLink = vm.pendingSessionId.value != null || vm.activeId.value != null || vm.openingHistoryId.value != null || vm.bootLinkPending.value,
            storedView = boot.storedView,
            hasExistingPreferences = boot.hasExistingPreferences,
        )
        navigateTo(resolved)
    }
    // T15.4 r2 (the web's `?session=` on a cold load): a link the app was launched with wins the
    // boot even when it only becomes known after the view resolved from storage (it waits for the
    // stored settings, then for the session list). Until the operator navigates, the boot view is
    // REPLACED by Sessions with nothing behind it, so Back still leaves the app (T4.4).
    LaunchedEffect(vm) {
        vm.bootLinkPending.collect { pending ->
            if (!pending || userNavigated) return@collect
            if (ViewHistory.decode(viewHistorySaved).canGoBack) return@collect
            viewHistorySaved = ViewHistory(DashboardView.Sessions).encode()
        }
    }
    // dashboard.tsx `writeStoredView`: remember the last top-level view for the next launch.
    val viewOrigin = com.tether.app.client.serverOrigin(vm.client.serverUrl.collectAsStateWithLifecycle().value)
    LaunchedEffect(view, viewOrigin) { view?.let { prefs.setLastView(viewOrigin, it.key) } }
    // T15.2 / ta-4711 (dashboard.tsx:1411-1461 reviewRequest): the hand-off lands in the session; once a snapshot on this
    // connection has confirmed it, EVERY update of its projection re-checks the request (the web re-runs on each one):
    // no longer pending says so, pending is handed to the session's transcript, which brings the card to the centre and
    // focuses it, then says it is shown. One 8 s bound runs from the tap over all of it: a session that never confirms,
    // or a card that is never drawn, says it could not be found. Nothing is answered.
    var reviewHandoff by remember { mutableStateOf<String?>(null) }
    var reviewShown by remember { mutableStateOf(false) }
    reviewTarget?.let { target ->
        LaunchedEffect(target) {
            val (sessionId, requestId) = target
            reviewShown = false
            reviewHandoff = null
            try {
                val outcome = kotlinx.coroutines.withTimeoutOrNull(com.tether.app.ui.overview.OverviewPresentation.REVIEW_WAIT_MS) {
                    vm.client.liveSessions.first { sessionId in it }
                    kotlinx.coroutines.flow.combine(
                        vm.client.projectionTrees.map { com.tether.app.ui.overview.OverviewPresentation.reviewStillPending(it[sessionId], requestId) }.distinctUntilChanged(),
                        androidx.compose.runtime.snapshotFlow { reviewShown },
                    ) { pending, shown -> pending to shown }
                        .first { (pending, shown) ->
                            if (pending == true) reviewHandoff = requestId
                            shown || pending == false
                        }
                }
                when {
                    outcome == null -> vm.reportLocalError(com.tether.app.ui.overview.OverviewPresentation.REVIEW_NOT_FOUND)
                    outcome.second -> Unit
                    else -> vm.reportLocalError(com.tether.app.ui.overview.OverviewPresentation.REVIEW_RESOLVED)
                }
                reviewTarget = null
            } finally {
                reviewHandoff = null
            }
        }
    }

    // issue #189: a remembered session whose snapshot has not arrived yet reads as "reopening",
    // never as the welcome stage.
    // ta-coik.41 (dashboard.tsx:1702): `pendingSessionId && !visibleSessions.length`.
    val emptyStage = if (pendingSessionId != null && visibleSessions.isEmpty()) {
        EmptyStage.Reopening(connected)
    } else {
        EmptyStage.Welcome(connected, providers.map { ProviderAvailability(it.label, it.available, it.id) })
    }

    val metrics = TelemetryMetrics.from(session?.metrics)

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
                // T9.2: Accounts opens the usage accounts dialog (topbar.tsx onOpenUsage); Usage opens
                // the Usage page (the web's `/usage`).
                onOpenUsage = { accounts.open() },
                onOpenUsageAnalytics = openUsagePage,
                onOpenSettings = { settingsOpen = true },
                // dashboard.tsx:1351 navigateTo. Sessions comes back to the conversation still
                // selected (or the empty workspace).
                onNavigate = { next ->
                    shell.closeDrawer()
                    userNavigated = true
                    // ta-coik.41 (dashboard.tsx:1352-1364): with nothing selected, the remembered chat.
                    if (next == DashboardView.Sessions) vm.onNavigateToSessions(visibleSessions, rememberedChat.value)
                    navigateTo(next)
                },
                views = setOf(DashboardView.Overview, DashboardView.Sessions, DashboardView.Scheduled),
                // dashboard.tsx:199 openLog: acknowledge the warnings, open, fetch fresh stats.
                onOpenLog = {
                    vm.openLog()
                    showLog = true
                    refreshStats()
                },
                // topbar.tsx 90fbb9f :257: Lock signs out at once (ta-coik.5: no app-only confirmation).
                onLogout = { vm.logout() },
            )
        val headerActions = WorkspaceHeaderActions(
                onRename = { renaming = session?.let { RenameTarget(it.id, it.name) } },
                // r3: the confirmation is bound to the session AND the server it was opened for.
                onEndSession = {
                    session?.let {
                        // dashboard.tsx 90fbb9f :1174: the chat on screen becomes the pick first.
                        vm.commitSelection(it.id)
                        // ta-coik.22: live as on the web (workspace-header.tsx 90fbb9f :133); bound to
                        // the server ([endSessionServer]); offline the client says it was not ended.
                        val server = com.tether.app.ui.chat.endSessionServer(consentOrigin, vm.client.serverUrl.value)
                        // dashboard.tsx 1bf4a465 :1192-1198: an isolated session's end asks about its teardown
                        // first (TeardownConfirmDialog), and that dialog is the confirmation.
                        if (confirmBeforeEnd && it.worktree == null) confirmEnd = EndTarget(it, server)
                        else vm.client.endSession(it.id, server)
                    }
                },
                onTogglePinned = { session?.let { vm.client.pin(it.id, !it.pinned) } },
                onCopyPath = {
                    session?.let {
                        if (copyExact(context, SafeText.line(it.cwd), copyNotices, raw = it.cwd, label = "Working directory", strict = true)) copiedPath = true
                    }
                },
                onCopyTetherId = {
                    session?.let {
                        if (copyExact(context, SafeText.line(it.id), copyNotices, raw = it.id, label = "Tether session id", strict = true)) copiedTetherId = true
                    }
                },
                // dashboard.tsx copyResumeCommand: the server-built command, exactly, nothing when there is none.
                onCopyResumeCommand = {
                    session?.resumeCommand?.let { cmd ->
                        if (copyExact(context, SafeText.exact(cmd), copyNotices, raw = cmd, label = "Resume command", strict = true)) copiedResumeCommand = true
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
                            userNavigated = true
                            vm.selectSession(id)
                            navigateTo(DashboardView.Sessions)
                            shell.onSessionSelected()
                        },
                        onClose = shell::closeDrawer,
                        onOpenSettings = { settingsOpen = true },
                        selectedOnScreen = sessionsView,
                        // T9.3 dashboard.tsx:387-392 openScheduledActions: the sheet and the drawer
                        // close, and Scheduled shows.
                        onOpenScheduledActions = {
                            vm.closeDraft()
                            shell.closeTelemetry()
                            shell.closeDrawer()
                            userNavigated = true
                            navigateTo(DashboardView.Scheduled)
                        },
                        scheduledActionsActive = scheduledOpen,
                    )
                },
                chat = {
                    val projection = selectedId?.let { projections.value[it] }
                    CompositionLocalProvider(
                        LocalCardStates provides cardStates,
                        LocalTranscriptScrollStore provides transcriptScroll,
                        LocalWorkspaceFileOpener provides fileLinks,
                    ) {
                        ChatScreen(
                            vm = vm,
                            session = session,
                            projection = projection,
                            workspaceRoot = workspaceRoot,
                            prefs = prefs,
                            modifier = Modifier.fillMaxSize(),
                            onOpenDrawer = shell::openDrawer,
                            showWorkspaceHeader = false,
                            // The web's `activeSession?.id ===` guard (dashboard.tsx:1424): only the target's session.
                            reviewFocus = reviewHandoff?.takeIf { session?.id != null && session.id == reviewTarget?.first },
                            onReviewShown = { reviewShown = true },
                        )
                    }
                },
                // T9.1: the full inspector, in the phone's telemetry sheet and the expanded column alike.
                inspector = { session?.let { InspectorHost(vm, it, rememberSessionView(projectionTrees, it.id), onUseCodexReset = codexReset::open) } },
                // T9.2 (workspace-header.tsx:113, dashboard.tsx:1615): the DeepSeek peak badge reads the
                // session's harness and its live model (the pick, else the default the harness applied).
                headerBadge = { s ->
                    com.tether.app.ui.usage.DeepSeekPeakBadge(
                        provider = s.provider,
                        model = com.tether.app.protocol.helpers.DeepseekPeak.deepSeekLiveModel(
                            com.tether.app.protocol.tree.JsStr(s.provider),
                            s.model?.let { com.tether.app.protocol.tree.JsStr(it) },
                            sessionControls[s.id]?.defaultModel?.let { com.tether.app.protocol.tree.JsStr(it) },
                        ),
                        variant = com.tether.app.ui.usage.DeepSeekPeakVariant.Full,
                    )
                },
                // T4.3's live gauge, dial and statusline (docs/parity/screens/statusline/README.md).
                gauge = { host -> ContextGauge(metrics, showLabel = host.showLabel, pressed = host.open, onClick = host.onToggle, stale = staleReading) },
                statusline = { expanded ->
                    SessionStatusline(metrics, rememberSessionView(projectionTrees, session?.id), horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.End, stale = staleReading)
                },
                // ta-3e7: components/studio-welcome.tsx on the empty Sessions stage (both Studio lightings).
                studioWelcome = { expanded ->
                    com.tether.app.ui.shell.StudioWelcome(
                        connected = connected,
                        providers = (emptyStage as? EmptyStage.Welcome)?.providers.orEmpty(),
                        onNewSession = vm::openDraft,
                        onOpenWorkspace = { workspacePickerOpen = true },
                        expanded = expanded,
                    )
                },
                launching = if (!draftLaunching) null else {
                    {
                        val row = com.tether.app.client.NewSessionGuard.rows(
                            if (draftCatalogLive) draftCatalog else null,
                            providers,
                        ).firstOrNull { it.choice.key == (draft.form["key"] as? com.tether.app.protocol.tree.JsStr)?.value }
                        com.tether.app.ui.draft.DraftLaunching(
                            text = draft.text,
                            attachments = draft.attachments,
                            providerLabel = com.tether.app.ui.draft.draftProviderLabel(row, providers),
                        )
                    }
                },
                overview = if (draftLaunching) {
                    null
                } else if (usageOpen) {
                    {
                        com.tether.app.ui.usage.UsagePage(
                            source = vm.client.usage,
                            origin = com.tether.app.client.serverOrigin(configuredServer),
                            // The empty range's "Open console" (usage-dashboard.tsx:155, a link to "/").
                            onOpenConsole = { usageOpen = false },
                        )
                    }
                } else if (view == null) {
                    // Boot: no view-specific content yet (the web's "boot" view paints none).
                    { Box(Modifier.fillMaxSize()) }
                } else if (scheduledOpen) {
                    {
                        // T9.3 dashboard.tsx:1587-1600: "Open last session" / "Open conversation" select
                        // the session (`selectSession`), which shows Sessions.
                        ScheduledHost(vm, prefs, workspaceRoot) { id ->
                            userNavigated = true
                            vm.selectSession(id)
                            navigateTo(DashboardView.Sessions)
                            shell.onSessionSelected()
                        }
                    }
                } else if (!overviewOpen) {
                    null
                } else if (overviewChoice == null) {
                    // ta-coik.52: until this server's stored filters are read, nothing (no feed asked for).
                    { Box(Modifier.fillMaxSize()) }
                } else {
                    {
                        // Read-only: every action hands off to an existing handler the operator taps.
                        // Opening or reviewing SELECTS the session (attach, draft) and never marks it seen
                        // here; the session view's own rule applies once it is on screen.
                        val openFromOverview: (String) -> Unit = { id ->
                            userNavigated = true
                            vm.selectSession(id)
                            navigateTo(DashboardView.Sessions)
                            shell.onSessionSelected()
                        }
                        com.tether.app.ui.overview.OverviewHost(
                            client = vm.client,
                            choice = overviewChoice,
                            onChoice = setOverviewChoice,
                            actions = com.tether.app.ui.overview.OverviewActions(
                                onOpenSession = openFromOverview,
                                onReviewRequest = { id, requestId ->
                                    reviewTarget = id to requestId
                                    openFromOverview(id)
                                },
                                onNewSession = vm::openDraft,
                                onOpenEventLog = {
                                    vm.openLog()
                                    showLog = true
                                    refreshStats()
                                },
                            ),
                            // T15.3: the Host & usage tile (polls only while shown and started); T9.2: its
                            // "View usage" opens the Usage page (overview-host.tsx:143).
                            hostUsage = { com.tether.app.ui.overview.HostUsageHost(vm.client, onViewUsage = openUsagePage) },
                        )
                    }
                },
            )
        // T15.4: Back steps to the view behind this one (the web's browser Back between views; from
        // an Overview opened over a session, that session). The shell's own surfaces (menu, drawer,
        // popover, sheet) register later and so close first. Nothing behind: Back leaves the app.
        // ta-coik.43 (dashboard.tsx 90fbb9f :1110-1118, popstate): Back onto a Sessions entry that
        // names a conversation makes it the pending target, shown once it is listed.
        androidx.activity.compose.BackHandler(enabled = viewHistory.canGoBack) {
            userNavigated = true
            val previous = ViewHistory.decode(viewHistorySaved).back()
            viewHistorySaved = previous.encode()
            if (previous.current == DashboardView.Sessions) previous.session?.let(vm::returnToSession)
        }
        // T9.2: Back on the Usage page returns to the console view under it (the browser's Back).
        androidx.activity.compose.BackHandler(enabled = usageOpen) { usageOpen = false }
        val current = if (usageOpen) TopBarDestination.Usage else view?.let(TopBarDestination::of)
        val link = when (connection) {
            ConnectionState.Connected -> LinkReadout.Connected
            ConnectionState.Connecting -> LinkReadout.Connecting
            else -> LinkReadout.Reconnecting
        }
        // dashboard.tsx:1446: the rail belongs to Sessions (and Scheduled); the Overview is full width.
        val showRail = (view == DashboardView.Sessions || view == DashboardView.Scheduled) && !usageOpen
        CompositionLocalProvider(com.tether.app.ui.shell.LocalShellFreshness provides shellFreshness) {
        if (layout == TetherLayoutClass.Expanded) {
            ExpandedShell(
                state = shell,
                panels = persisted.panels,
                onPanelsChange = persisted.onChange,
                session = session.takeUnless { draftLaunching },
                workspaceRoot = workspaceRoot,
                emptyStage = emptyStage,
                topbar = topbarActions,
                header = headerActions,
                slots = slots,
                link = link,
                unseenWarnings = unseenWarnings,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                copiedResumeCommand = copiedResumeCommand,
                onStartSession = vm::openDraft,
                current = current,
                showRail = showRail,
            )
        } else {
            PhoneShell(
                state = shell,
                session = session.takeUnless { draftLaunching },
                workspaceRoot = workspaceRoot,
                emptyStage = emptyStage,
                unseenWarnings = unseenWarnings,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                copiedResumeCommand = copiedResumeCommand,
                onStartSession = vm::openDraft,
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
            // ta-54j: no timer — the web's error toast stays until dismissed (dashboard.tsx:1980-1986,
            // use-tether.ts dismissError); the close button is the only way out.
            ErrorToast(
                message = message.text,
                fromServer = message.fromServer,
                onClose = { vm.dismissToast() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .zIndex(20f),
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

    // T9.2: the Accounts dialog and the reset confirmations (the inspector's Codex one too).
    com.tether.app.ui.usage.UsageAccountsDialog(accounts, codexReset, claudeReset)

    // T8.5 dashboard.tsx:1886-1887: the metadata draft panel, at the shell's root so a draft shows
    // whatever the phone's sheet or the tablet's columns are drawing.
    com.tether.app.ui.metadata.MetadataDraftPanelHost(vm.client)

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

    // T15.4: the top bar's Settings and the rail footer's open the same dialog (T10.1).
    if (settingsOpen) {
        ShellSettings(vm, prefs, workspaceRoot, settingsTab, onDismiss = {
            settingsOpen = false
            settingsTab = com.tether.app.ui.settings.SettingsTab.General
        })
    }
    if (workspacePickerOpen) com.tether.app.ui.WorkspacePickerHost(vm, prefs, workspaceRoot, scope = scope, onDismiss = { workspacePickerOpen = false })

    // ta-abm (T8.1 slice 2): the new-session sheet, raised by every New session key (the drawer's,
    // a block's "+", the Overview's, the empty stage's); the draft it draws is the view model's.
    // T8.4: "Set up GitHub connection" (github-work-dialog.tsx :251-254, dashboard.tsx openFullSettings)
    // opens Settings on Advanced, where the GitHub connection is (ta-coik.21); the draft stays.
    com.tether.app.ui.draft.DraftComposerHost(vm, prefs, onOpenGitHubSettings = {
        settingsTab = com.tether.app.ui.settings.SettingsTab.Advanced
        settingsOpen = true
    })

    // ta-m7ef (dashboard.tsx 1bf4a465 :1933-1941): ending an isolated session asks about its teardown first; the
    // client holds the pending end (EndSessionFlow), this draws its confirmation.
    val endConfirmation by vm.client.endConfirmation.collectAsStateWithLifecycle()
    endConfirmation?.let { pending ->
        com.tether.app.ui.chat.TeardownConfirmDialog(
            confirmation = pending,
            onRun = vm.client::runTeardown,
            onSkip = vm.client::endWithoutTeardown,
            onCancel = vm.client::cancelEnd,
        )
    }

    renaming?.let { target ->
        var name by rememberSaveable(target.id) { mutableStateOf(target.name) }
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
        // r3: never on another server than the one it was opened for (the client refuses it too).
        // ta-coik.22: otherwise live and open, as the web's dialog, whatever the link or the copy.
        val endable = com.tether.app.ui.chat.endConfirmLive(drawnFor, consentOrigin)
        com.tether.app.ui.chat.EndSessionDialog(
            sessionName = target.name,
            // The session and server it was opened for (not the row itself, which moves on every update).
            identity = target.id to drawnFor,
            endable = endable,
            onConfirm = {
                confirmEnd = null
                vm.client.endSession(target.id, drawnFor)
            },
            onCancel = { confirmEnd = null },
        )
    }
}

/**
 * T10.1: the Settings dialog with the sidebar's current workspace (use-tether.ts `currentWorkspace`:
 * the operator's pick, else the last-opened or default folder, else the server's root) for
 * General's "Use current", read here so the shell itself never recomposes on preference changes.
 */
@Composable
private fun ShellSettings(vm: TetherViewModel, prefs: UiPrefs, workspaceRoot: String?, tab: com.tether.app.ui.settings.SettingsTab, onDismiss: () -> Unit) {
    val preferences by remember(prefs, vm.client) { prefs.preferencesFor(vm.client.serverUrl) }.collectAsStateWithLifecycle(initialValue = com.tether.app.ui.prefs.TetherPreferences.Default)
    val picked by vm.currentWorkspace.collectAsStateWithLifecycle()
    val current = com.tether.app.ui.sidebar.SidebarController.resolveCurrentWorkspace(picked, preferences, workspaceRoot)
    com.tether.app.ui.settings.SettingsDialog(vm.client, prefs, currentWorkspace = current.orEmpty(), onDismiss = onDismiss, initialTab = tab)
}

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
            // studio.css:404 `:root .error-toast { border-radius: 0.875rem }`.
            .background(t.dangerWash, RoundedCornerShape(14.dp))
            .border(1.dp, t.brick, RoundedCornerShape(14.dp))
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

/** ta-coik.41: the stored `lastOpenedSession`, held for a synchronous read (not composition state). */
private class RememberedChat {
    var value: com.tether.app.ui.prefs.LastOpenedSession? = null
}
