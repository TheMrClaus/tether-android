package com.tether.app.ui.shell

import com.tether.app.ui.util.RecompositionProbe
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.resolveKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import kotlin.math.roundToInt

/** A width the handle set that the persisted preferences have not echoed back yet (null = default). */
private data class PendingWidth(val width: Int?)

/**
 * The web's DESKTOP layout (components/dashboard.tsx at ≥ 48rem; PLAN D10: windows at or above
 * the 768dp breakpoint, the web's 48rem): the topbar across a grid of columns — the session rail, the
 * workspace, and from 100rem the inspector — with draggable column edges and persisted widths.
 *
 * - Grid (globals.css 3957-3962, 4139-4143): `var(--rail-width) minmax(0, 1fr) [var(--inspector-width)]`
 *   under a 3rem topbar row (Studio 4rem). The widths come from [panels] (the stored
 *   `sidebarWidth` / `inspectorWidth`, re-clamped against the window every layout) or the theme
 *   default ([PanelWidthGeometry.effectiveWidth]).
 * - The rail ([PhoneShellSlots.drawer]) is a static column, not a drawer; collapsing it
 *   ([PanelPrefs.sidebarCollapsed]) drops the column and shows the bottom-left expand dock
 *   (3964-4003). The rail's own collapse key lives in its footer (T5.1).
 * - The workspace holds the header (with the dial and the labelled gauge), and the stage padded as
 *   a bay around the chat screen ([PhoneShellSlots.chat]); with no session, the empty stage.
 * - Telemetry: below 100rem the gauge opens the telemetry sheet floating beside the conversation
 *   (11894-11910); from 100rem the inspector column shows the readings and the gauge is a quiet
 *   indicator (dashboard.tsx:74-82). Either way the body is [PhoneShellSlots.inspector].
 * - Resizing ([PanelResizeHandle]): each handle straddles its column's inner edge; a drag updates
 *   the width live and commits once on release through [onPanelsChange] — the web's
 *   `updatePreferences({ ...preferences, sidebarWidth })`, which the host persists.
 *
 * Back closes the Session links popover, then the floating telemetry sheet; otherwise it falls
 * through. The phone drawer does not exist here, so it is closed on entry.
 */
@Composable
fun ExpandedShell(
    state: PhoneShellState,
    panels: PanelPrefs,
    onPanelsChange: (PanelPrefs) -> Unit,
    session: AgentSession?,
    workspaceRoot: String?,
    emptyStage: EmptyStage,
    topbar: TopbarActions,
    header: WorkspaceHeaderActions,
    slots: PhoneShellSlots,
    modifier: Modifier = Modifier,
    link: LinkReadout = LinkReadout.Connected,
    unseenWarnings: Int = 0,
    copiedPath: Boolean = false,
    copiedTetherId: Boolean = false,
    copiedResumeCommand: Boolean = false,
    onStartSession: () -> Unit = {},
    /** T15.4: the top bar's current destination (null while the console resolves its view). */
    current: TopBarDestination? = TopBarDestination.Sessions,
    /**
     * T15.4 (dashboard.tsx `showRail`): the Sessions rail exists. The Overview is full width under
     * the top bar: no rail, no resize handle, no expand dock (and no inspector: no session shows).
     */
    showRail: Boolean = true,
) {
    RecompositionProbe("ExpandedShell")
    val t = LocalTetherTokens.current
    LaunchedEffect(Unit) { state.closeDrawer() }
    // ta-coik.31: opening the tools menu takes focus and the keyboard away from the composer (the web's focus move).
    PutKeyboardAwayWhile(state.menuOpen)

    BoxWithConstraints(modifier.fillMaxSize().testTag(ShellTags.Shell)) {
        val viewport = maxWidth.value.roundToInt()
        val columnLayout = viewport >= ExpandedBreakpoints.INSPECTOR_COLUMN
        val sheetShown = session != null && state.telemetryOpen && !columnLayout
        BackHandler(enabled = state.menuOpen || state.linksOpen || sheetShown) { state.handleBack() }

        var pendingRail by remember { mutableStateOf<PendingWidth?>(null) }
        var pendingInspector by remember { mutableStateOf<PendingWidth?>(null) }
        LaunchedEffect(panels.sidebarWidth) { pendingRail = null }
        LaunchedEffect(panels.inspectorWidth) { pendingInspector = null }
        val railStored = pendingRail.let { if (it != null) it.width else panels.sidebarWidth }
        val inspectorStored = pendingInspector.let { if (it != null) it.width else panels.inspectorWidth }
        val railWidth = PanelWidthGeometry.effectiveWidth(PanelKind.Rail, railStored, viewport)
        val inspectorWidth = PanelWidthGeometry.effectiveWidth(PanelKind.Inspector, inspectorStored, viewport)
        val collapsed = panels.sidebarCollapsed || !showRail
        // `.is-wide-workspace` (no active session): the inspector column is 0 and its handle hidden.
        val inspectorColumn = columnLayout && session != null
        // r2: what the bar could not fit, listed by its menu.
        val fold = remember { TopbarFold() }
        // ta-7njx: the header cluster's yield, shared with the "Session links" menu's Pin row.
        val headerFit = remember { HeaderFit() }
        val topbarState = TopbarState(
            current = current,
            link = link,
            wide = true,
            drawerKey = false,
            menuOpen = state.menuOpen,
            viewportWidth = viewport,
            unseenWarnings = unseenWarnings,
            fileBrowserDisabled = session == null,
        )

        Column(Modifier.fillMaxSize().background(t.graphite)) {
            TetherTopbar(actions = topbar, state = topbarState, onToggleMenu = state::toggleMenu, fold = fold)
            // T13.2: the link banner, under the topbar (never a modal).
            LocalShellFreshness.current.banner?.let { com.tether.app.ui.components.ConnectionBanner(it, Modifier.testTag(ShellTags.LinkBanner)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (!collapsed) SidebarColumn(railWidth.dp) { slots.drawer() }
                    WorkspaceColumn(
                        state = state,
                        session = session,
                        emptyStage = emptyStage,
                        header = header,
                        headerFit = headerFit,
                        slots = slots,
                        viewport = viewport,
                        columnLayout = columnLayout,
                        sheetShown = sheetShown,
                        onStartSession = onStartSession,
                        modifier = Modifier.weight(1f),
                    )
                    if (inspectorColumn) InspectorColumn(inspectorWidth.dp, slots.inspector)
                }
                if (!collapsed) {
                    PanelResizeHandle(
                        kind = PanelKind.Rail,
                        renderedWidth = railWidth,
                        viewportWidth = viewport,
                        onLive = { w -> pendingRail = w?.let { PendingWidth(it) } },
                        onCommit = { w ->
                            pendingRail = PendingWidth(w)
                            onPanelsChange(panels.withWidth(PanelKind.Rail, w))
                        },
                        // `justify-self: end; transform: translateX(50%)` over the rail's edge.
                        modifier = Modifier.offset(x = railWidth.dp - PanelHandleWidth / 2).zIndex(3f),
                    )
                } else if (showRail) {
                    ExpandDock(
                        onExpand = { onPanelsChange(panels.copy(sidebarCollapsed = false)) },
                        modifier = Modifier.align(Alignment.BottomStart).zIndex(3f),
                    )
                }
                if (inspectorColumn) {
                    PanelResizeHandle(
                        kind = PanelKind.Inspector,
                        renderedWidth = inspectorWidth,
                        viewportWidth = viewport,
                        onLive = { w -> pendingInspector = w?.let { PendingWidth(it) } },
                        onCommit = { w ->
                            pendingInspector = PendingWidth(w)
                            onPanelsChange(panels.withWidth(PanelKind.Inspector, w))
                        },
                        // `justify-self: start; transform: translateX(-50%)` over the inspector's edge.
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = -(inspectorWidth.dp - PanelHandleWidth / 2))
                            .zIndex(3f),
                    )
                }
            }
        }

        if (session != null && state.linksOpen) {
            SessionLinksPopover(
                session = session,
                workspaceRoot = workspaceRoot,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                copiedResumeCommand = copiedResumeCommand,
                actions = header,
                onDismiss = state::closeLinks,
                statusline = slots.statusline,
                expanded = true,
                endInset = if (inspectorColumn) inspectorWidth.dp + t.css.spaceLg else null,
                showStatusline = !columnLayout,
                pinInMenu = headerFit.pinInMenu,
            )
        }

        if (state.menuOpen) TopbarMenu(actions = topbar, state = topbarState, onDismiss = state::closeMenu, fold = fold)
    }
}

/**
 * `.session-sidebar` from 48rem: a static grid column (globals.css 4033-4040). Studio: the fixed ink-blue finish (`#141d2e`), no edge,
 * `padding: 1.35rem 0.875rem 0.75rem` (studio.css 295-301, 429). The web's `aria-label="Agent
 * sessions"` names the pane.
 */
@Composable
private fun SidebarColumn(width: Dp, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .testTag(ShellTags.Sidebar)
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(StudioDrawer.background)
            }
            .padding(end = 0.dp)
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    .union(WindowInsets(bottom = 12.dp)),
            )
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 21.6.dp,
            )
            .semantics { paneTitle = "Agent sessions" },
    ) { content() }
}

/**
 * `<main className="workspace">`: the header, then the stage (or the empty stage), on Studio's
 * `--graphite` (studio.css 350).
 * The stage is the bay around the chat screen — `padding: space-md`, and on the left
 * `calc(space-lg + 7px)`, from 64rem `calc(2.75rem + space-sm)` for the timeline rail (11219-11225,
 * 11889-11914); Studio `padding: 0` (studio.css 367). The screen itself is the chat's (T6).
 */
@Composable
private fun WorkspaceColumn(
    state: PhoneShellState,
    session: AgentSession?,
    emptyStage: EmptyStage,
    header: WorkspaceHeaderActions,
    headerFit: HeaderFit,
    slots: PhoneShellSlots,
    viewport: Int,
    columnLayout: Boolean,
    sheetShown: Boolean,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .drawBehind {
                drawRect(t.graphite)
            }
            .testTag(ShellTags.Workspace),
    ) {
        val workspaceWidth = maxWidth
        Column(Modifier.fillMaxSize()) {
            if (session != null) {
                WorkspaceHeader(
                    session = session,
                    telemetryOpen = state.telemetryOpen && !columnLayout,
                    linksOpen = state.linksOpen,
                    onToggleTelemetry = state::toggleTelemetry,
                    onToggleLinks = state::toggleLinks,
                    actions = header,
                    gauge = slots.gauge,
                    expanded = true,
                    gaugeIsHandle = !columnLayout,
                    dial = slots.dial,
                    badge = slots.headerBadge?.let { badge -> { badge(session) } },
                    fit = headerFit,
                )
                val left = 0.dp
                val edge = 0.dp
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = left, top = edge, end = edge, bottom = edge)
                        .testTag(ShellTags.Stage),
                ) { slots.chat() }
            } else {
                // T15.2: the Overview takes the whole workspace when it is showing (no session is).
                val overview = slots.overview
                val launching = slots.launching
                if (launching != null) {
                    // ta-abm: the draft's hand-off stage (dashboard.tsx `draftLaunching`).
                    Box(Modifier.weight(1f).fillMaxWidth()) { launching() }
                } else if (overview != null) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { overview() }
                } else {
                    EmptyWorkspace(
                        stage = emptyStage,
                        onStartSession = onStartSession,
                        studioWelcome = slots.studioWelcome,
                        expanded = true,
                        viewportWidth = viewport,
                    )
                }
            }
        }
        if (sheetShown) {
            TelemetrySheet(
                onClose = state::closeTelemetry,
                floating = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = t.css.spaceSm, bottom = t.css.spaceSm)
                    .width(minOf(368.dp, workspaceWidth - 16.dp))
                    .fillMaxHeight()
                    .zIndex(8f),
                body = slots.inspector,
            )
        }
    }
}

/**
 * `.inspector` as the third column (≥ 100rem, globals.css 4145-4160, 10868-10874): `--graphite`,
 * `1px --line-strong` left edge with the `--seam-lip` shade inside, `padding: space-lg space-lg
 * space-xl`, scrolling. Studio: `--mineral-deep`, `1px --line`, `padding: 1.5rem 1.25rem`
 * (studio.css 398). The web's `aria-label="Session details"` names the pane.
 */
@Composable
private fun InspectorColumn(width: Dp, body: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .testTag(ShellTags.InspectorColumn)
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(t.mineralDeep)
                drawRect(t.line, Offset.Zero, Size(px, size.height))
            }
            .padding(start = 1.dp)
            .semantics { paneTitle = "Session details" }
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 24.dp,
                bottom = 24.dp,
            ),
        content = body,
    )
}

/**
 * `.sidebar-expand-dock` (dashboard.tsx:1351-1360; globals.css 3993-4003): while the rail is
 * collapsed, a 2.75rem icon key fixed `space-md` from the bottom-left corner — `--graphite-raised`,
 * `1px --line-strong`, `--edge-highlight` + `--shadow-raised`, `--muted` PanelLeftOpen. Pressed
 * seats it like every icon control (`:root .icon-button:active` wins by source order, 8981).
 */
@Composable
private fun ExpandDock(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val look: (KeyState) -> ChromeLook = remember(t) {
        { state ->
            if (state == KeyState.Pressed) {
                val k = resolveKey(t, KeyClasses.IconButton, state)
                ChromeLook(k.face, t.lineStrong, t.muted, k.shadows, t.radiusSm)
            } else {
                ChromeLook(t.graphiteRaised, t.lineStrong, t.muted, t.css.shadowRaised, t.radiusSm)
            }
        }
    }
    ChromeIconKey(
        onClick = onExpand,
        icon = TetherIcons.PanelLeftOpen,
        contentDescription = "Expand sidebar",
        look = look,
        width = 44.dp,
        height = 44.dp,
        iconSize = 18.dp,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).union(WindowInsets(bottom = t.css.spaceMd)))
            .padding(start = t.css.spaceMd)
            .testTag(ShellTags.ExpandDock),
    )
}
