package com.tether.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.layoutClassFor
import com.tether.app.ui.theme.LocalTetherTokens

/** Test tags of the shell chrome (stable hooks for behaviour tests and screenshots). */
object ShellTags {
    const val Shell = "shell"
    const val Topbar = "shell-topbar"
    const val LinkBanner = "shell-link-banner"
    const val FreshnessChip = "shell-freshness"
    const val StatusPill = "shell-status-pill"
    const val MenuKey = "shell-menu"
    const val FilesKey = "shell-files"
    const val LogKey = "shell-log"
    const val LockKey = "shell-lock"
    const val WorkspaceHeader = "shell-workspace-header"
    const val RenameKey = "shell-rename"
    const val TelemetryHandle = "shell-telemetry-handle"
    const val LinksKey = "shell-links"
    const val LinksPopover = "shell-links-popover"
    const val PinKey = "shell-pin"
    const val EndSessionKey = "shell-end-session"
    const val TelemetrySheet = "shell-telemetry-sheet"
    const val TelemetryClose = "shell-telemetry-close"
    const val Stage = "shell-stage"
    const val EmptyWorkspace = "shell-empty"
    const val StartSessionKey = "shell-start-session"
    const val Drawer = "shell-drawer"
    const val DrawerBackdrop = "shell-drawer-backdrop"

    // The expanded (desktop) layout, T4.2.
    const val Sidebar = "shell-sidebar"
    const val Workspace = "shell-workspace"
    const val InspectorColumn = "shell-inspector-column"
    const val RailHandle = "shell-rail-handle"
    const val InspectorHandle = "shell-inspector-handle"
    const val ExpandDock = "shell-expand-dock"
    const val ConnectionReadout = "shell-connection"
    const val Dial = "shell-dial"

    // T15.4: the redesigned top bar and its utility menu.
    const val Brand = "shell-brand"
    const val SettingsKey = "shell-settings"
    const val AccountsKey = "shell-accounts"
    const val ToolsMenuKey = "shell-tools"
    const val ToolsMenu = "shell-tools-menu"
    const val MenuFiles = "shell-menu-files"
    const val MenuAccounts = "shell-menu-accounts"
    const val WarningBadge = "shell-warning-badge"
    fun nav(destination: TopBarDestination) = "shell-nav:${destination.name.lowercase()}"
    fun menuNav(destination: TopBarDestination) = "shell-menu-nav:${destination.name.lowercase()}"
}

/**
 * Which shell a window gets (PLAN D10, TRACKER decision 2026-09-27): below the WindowSizeClass
 * expanded width (840dp) the web's MOBILE layout; at or above it the desktop layout (T4.2).
 */
fun shellLayoutFor(widthDp: Int): TetherLayoutClass = layoutClassFor(widthDp)

/**
 * Everything the shell hosts but does not own — each one is another task's surface. The phone
 * layout ([PhoneShell]) and the expanded one ([ExpandedShell]) take the same slots: the drawer's
 * list is the expanded layout's sidebar column, and the inspector is both the telemetry panel's
 * body and the third column.
 */
class PhoneShellSlots(
    /** The drawer's session list (T5.1; today's SessionDrawer); the expanded layout's rail. */
    val drawer: @Composable () -> Unit,
    /** The chat stage — transcript + composer (T6.x / T7.x; today's ChatScreen). */
    val chat: @Composable () -> Unit,
    /** The telemetry panel body and the expanded layout's third column: the inspector (T9.1). */
    val inspector: @Composable ColumnScope.() -> Unit,
    /** The header's context gauge, the panel's handle (T4.3). */
    val gauge: GaugeSlot = { host -> TelemetryHandlePlaceholder(host.open == true, host.onToggle ?: {}) },
    /** The header's elapsed dial, shown from 48rem only, so only the expanded layout hosts it (T4.3). */
    val dial: DialSlot = { session ->
        com.tether.app.ui.statusline.SessionDial(session.startedAt, session.endedAt, active = session.status != "exited")
    },
    /** The statusline in the "Session links" popover (T4.3). */
    val statusline: StatuslineSlot = {},
    /** Studio's empty stage, StudioWelcome (T8.1). */
    val studioWelcome: (@Composable () -> Unit)? = null,
    /**
     * T15.2: the Overview (feature:overview). Non-null while it is showing: it replaces the
     * workspace's header, stage and empty stage (the host passes no session meanwhile).
     */
    val overview: (@Composable () -> Unit)? = null,
    /**
     * ta-abm (dashboard.tsx `draftLaunching`): the new session's hand-off stage. Non-null while the
     * draft's create is in flight: it takes the workspace column ahead of the Overview and the empty
     * stage (the host passes no session meanwhile).
     */
    val launching: (@Composable () -> Unit)? = null,
)

/**
 * The web's MOBILE layout (components/dashboard.tsx below 48rem): a topbar row over the workspace
 * column, the session list as an off-canvas drawer, and — once a session is open — the workspace
 * header, the collapsible telemetry panel and the chat stage stacked in that column.
 *
 * `.dashboard-shell` rows: `calc(3.5rem + safe-top) minmax(0, 1fr)`; `.workspace` is `--mineral`
 * on a phone (globals.css 11721; Studio `--graphite`, studio.css 350). While the telemetry panel
 * is open the chat stage stays composed (its scroll and composer state survive, like the web's
 * `display: none` stage) but is neither placed, drawn, touchable nor announced.
 *
 * Back closes the topmost open surface ([PhoneShellState.handleBack]); otherwise it falls
 * through to the system.
 */
@Composable
fun PhoneShell(
    state: PhoneShellState,
    session: AgentSession?,
    workspaceRoot: String?,
    emptyStage: EmptyStage,
    topbar: TopbarActions,
    header: WorkspaceHeaderActions,
    slots: PhoneShellSlots,
    modifier: Modifier = Modifier,
    unseenWarnings: Int = 0,
    copiedPath: Boolean = false,
    copiedTetherId: Boolean = false,
    onStartSession: () -> Unit = {},
    /** T15.4: the top bar's current destination (null while the console resolves its view). */
    current: TopBarDestination? = TopBarDestination.Sessions,
    /** T15.4: the link state the bar prints (on every width since the redesign). */
    link: LinkReadout = LinkReadout.Connected,
    /** T15.4: the Sessions rail exists (dashboard.tsx `showRail`): false on the Overview, so no drawer. */
    showRail: Boolean = true,
) {
    val t = LocalTetherTokens.current
    BackHandler(enabled = state.canHandleBack) { state.handleBack() }
    LaunchedEffect(showRail) { if (!showRail) state.closeDrawer() }
    val windowWidth = LocalWindowInfo.current.containerSize.width.let { with(LocalDensity.current) { it.toDp().value.toInt() } }
    val topbarState = TopbarState(
        current = current,
        link = link,
        wide = false,
        drawerKey = showRail,
        menuOpen = state.menuOpen,
        viewportWidth = windowWidth,
        unseenWarnings = unseenWarnings,
        fileBrowserDisabled = session == null,
    )
    val barActions = topbar.copy(onOpenDrawer = {
        state.openDrawer()
        topbar.onOpenDrawer()
    })

    Box(modifier.fillMaxSize().testTag(ShellTags.Shell)) {
        Column(Modifier.fillMaxSize().background(t.graphite)) {
            TetherTopbar(actions = barActions, state = topbarState, onToggleMenu = state::toggleMenu)
            // T13.2: the link banner, under the topbar (never a modal).
            LocalShellFreshness.current.banner?.let { com.tether.app.ui.components.ConnectionBanner(it, Modifier.testTag(ShellTags.LinkBanner)) }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (session != null) {
                    WorkspaceHeader(
                        session = session,
                        telemetryOpen = state.telemetryOpen,
                        linksOpen = state.linksOpen,
                        onToggleTelemetry = state::toggleTelemetry,
                        onToggleLinks = state::toggleLinks,
                        actions = header,
                        gauge = slots.gauge,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val collapsed = state.telemetryOpen
                        Box(
                            Modifier
                                .fillMaxSize()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        if (!collapsed) placeable.place(0, 0)
                                    }
                                }
                                // Collapsed: the stage stays composed but leaves the semantics tree.
                                .then(if (collapsed) Modifier.clearAndSetSemantics { } else Modifier)
                                .testTag(ShellTags.Stage),
                        ) { slots.chat() }
                        if (collapsed) {
                            TelemetrySheet(
                                onClose = state::closeTelemetry,
                                modifier = Modifier.fillMaxSize(),
                                body = slots.inspector,
                            )
                        }
                    }
                } else {
                    // T15.2: the Overview takes the workspace column when it is showing (no session is).
                    val overview = slots.overview
                    val launching = slots.launching
                    if (launching != null) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { launching() }
                    } else if (overview != null) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { overview() }
                    } else {
                        EmptyWorkspace(
                            stage = emptyStage,
                            onStartSession = onStartSession,
                            studioWelcome = slots.studioWelcome,
                        )
                    }
                }
            }
        }

        if (showRail) SessionDrawerHost(open = state.drawerOpen, onClose = state::closeDrawer) { slots.drawer() }

        if (session != null && state.linksOpen) {
            SessionLinksPopover(
                session = session,
                workspaceRoot = workspaceRoot,
                copiedPath = copiedPath,
                copiedTetherId = copiedTetherId,
                actions = header,
                onDismiss = state::closeLinks,
                statusline = slots.statusline,
            )
        }

        if (state.menuOpen) TopbarMenu(actions = barActions, state = topbarState, onDismiss = state::closeMenu)
    }
}
