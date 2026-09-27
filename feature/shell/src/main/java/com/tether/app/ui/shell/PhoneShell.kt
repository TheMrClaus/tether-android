package com.tether.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
) {
    val t = LocalTetherTokens.current
    BackHandler(enabled = state.canHandleBack) { state.handleBack() }

    Box(modifier.fillMaxSize().testTag(ShellTags.Shell)) {
        Column(Modifier.fillMaxSize().background(if (t.studio) t.graphite else t.mineral)) {
            TetherTopbar(
                actions = topbar.copy(onOpenDrawer = {
                    state.openDrawer()
                    topbar.onOpenDrawer()
                }),
                unseenWarnings = unseenWarnings,
                fileBrowserDisabled = session == null,
            )
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
                    EmptyWorkspace(
                        stage = emptyStage,
                        onStartSession = onStartSession,
                        studioWelcome = slots.studioWelcome,
                    )
                }
            }
        }

        SessionDrawerHost(open = state.drawerOpen, onClose = state::closeDrawer) { slots.drawer() }

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
    }
}
