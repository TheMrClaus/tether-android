package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.SidebarSort
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.ProvideTokenScope
import com.tether.app.protocol.helpers.SessionSidebar as Web

/** The dashboard's `<SessionSidebar>` callbacks (session-sidebar.tsx:475-541). Null = host not built yet. */
data class SidebarActions(
    val onCloseDrawer: () -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onBrowseWorkspace: () -> Unit = {},
    val onToggleWorkspaceCollapsed: (String) -> Unit = {},
    val onTogglePinnedProject: (String) -> Unit = {},
    val onNewSessionIn: (String) -> Unit = {},
    val onSessionQueryChange: (String) -> Unit = {},
    val onHarnessFilterChange: (String?) -> Unit = {},
    val onToggleActiveOnly: () -> Unit = {},
    val onToggleUnreadOnly: () -> Unit = {},
    val onToggleHideAgentRuns: () -> Unit = {},
    val onSortModeChange: (SidebarSort) -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onReopenHistory: (HistorySession) -> Unit = {},
    /** (session id, the server origin the control was armed for: T13.2 r3). */
    val onEndSession: (String, String?) -> Unit = { _, _ -> },
    val onReorderSessions: (String, List<String>) -> Unit = { _, _ -> },
    val onResetSessionOrder: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onCollapse: (() -> Unit)? = null,
    /** Global search (T5.3). */
    val onOpenGlobalSearch: (() -> Unit)? = null,
    /** Scheduled actions (T9.3). */
    val onOpenScheduledActions: (() -> Unit)? = null,
)

/** Transient UI state a screenshot or test can start from (the web's component state). */
data class SidebarUiSeed(
    val armedKey: String? = null,
    val swipedKey: String? = null,
    val drag: DragPreview? = null,
    val harnessMenuOpen: Boolean = false,
    val sortMenuOpen: Boolean = false,
    val archivedOpen: Boolean = false,
    val openChildren: Set<String> = emptySet(),
)

object SidebarTags {
    const val Root = "sidebar"
    const val List = "sidebar-list"
    const val NewSession = "sidebar-new-session"
    const val AddWorkspace = "sidebar-add-workspace"
    fun row(key: String) = "sidebar-row:$key"
    fun freshness(key: String) = "sidebar-freshness:$key"
    fun blockDot(workspace: String) = "sidebar-block-dot:$workspace"
    fun handle(key: String) = "sidebar-handle:$key"
    fun end(key: String) = "sidebar-end:$key"
    fun archive(key: String) = "sidebar-archive:$key"
    fun block(workspace: String) = "sidebar-block:$workspace"
}

/** How long an armed end control stays armed (session-sidebar.tsx:40). */
const val END_SESSION_ARM_MS = 4000L

/**
 * T5.1: the web's `components/session-sidebar.tsx` — the drawer's content on a phone and the
 * sidebar column's content in the expanded layout (the host owns the container: width, padding,
 * floor). Stateless over [state]; the only local state is the web component's own (armed end
 * control, drag, swipe, menus, "Show more" depth). Studio redeclares the palette inside the rail
 * ([StudioSidebarScope]).
 */
@Composable
fun SessionSidebar(
    state: SidebarState,
    actions: SidebarActions,
    modifier: Modifier = Modifier,
    layout: TetherLayoutClass = currentLayoutClass(),
    seed: SidebarUiSeed = SidebarUiSeed(),
) {
    val base = LocalTetherTokens.current
    ProvideTokenScope(StudioSidebarScope) {
        SidebarContent(state, actions, modifier, layout, seed)
    }
}

@Composable
private fun SidebarContent(
    state: SidebarState,
    actions: SidebarActions,
    modifier: Modifier,
    layout: TetherLayoutClass,
    seed: SidebarUiSeed,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val phone = layout == TetherLayoutClass.Phone

    var visibleCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var armedKey by remember { mutableStateOf(seed.armedKey) }
    // T13.2 r3: the server the armed end control was armed for; its second tap ends on that server only.
    val latestOrigin by rememberUpdatedState(state.origin)
    var armedOrigin by remember { mutableStateOf(if (seed.armedKey != null) state.origin else null) }
    var drag by remember { mutableStateOf<DragSession?>(seed.drag?.let { DragSession(it.key, "", it.order, it.order, engaged = true) }) }
    var harnessOpen by remember { mutableStateOf(seed.harnessMenuOpen) }
    var sortOpen by remember { mutableStateOf(seed.sortMenuOpen) }
    var archivedOpen by remember { mutableStateOf(seed.archivedOpen) }
    val openChildren = remember { mutableStateMapOf<String, Boolean>().apply { seed.openChildren.forEach { put(it, true) } } }
    val rowBounds = remember { HashMap<String, Rect>() }
    val endBounds = remember { HashMap<String, Rect>() }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val listState = rememberLazyListState()
    var listBounds by remember { mutableStateOf(Rect.Zero) }

    // session-sidebar.tsx:819 — an armed control silently disarms after END_SESSION_ARM_MS.
    LaunchedEffect(armedKey) {
        if (armedKey != null && seed.armedKey == null) {
            kotlinx.coroutines.delay(END_SESSION_ARM_MS)
            armedKey = null
        }
    }

    val dragPreview = drag?.takeIf { it.engaged }?.let { DragPreview(it.key, it.order) }
    val view = SidebarViewModel.view(state, visibleCounts, dragPreview)

    val latestState by rememberUpdatedState(state)
    val latestView by rememberUpdatedState(view)
    val latestCommit by rememberUpdatedState(actions.onReorderSessions)
    val dragController = remember {
        DragController(
            stateOf = { latestState },
            viewOf = { latestView },
            getDrag = { drag },
            setDrag = { drag = it },
            rowBounds = rowBounds,
            onCommit = { latestCommit },
        )
    }

    // Auto-scroll while a drag sits near the list's top/bottom edge (session-sidebar.tsx:675-682, 751-758).
    val scrollDir = drag?.scrollDirection ?: 0
    val density = LocalDensity.current
    LaunchedEffect(scrollDir) {
        if (scrollDir == 0) return@LaunchedEffect
        val step = with(density) { 12.dp.toPx() }
        while (true) {
            listState.scrollBy(scrollDir * step)
            dragController.reorderAt(drag?.pointer ?: return@LaunchedEffect)
            androidx.compose.runtime.withFrameNanos { }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoords = it }
            // session-sidebar.tsx:821 handleEndBlur — pressing anywhere but the armed end control
            // disarms it (another row's end control re-arms on its own click right after).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val key = armedKey ?: return@awaitEachGesture
                    val at = rootCoords?.localToRoot(down.position) ?: return@awaitEachGesture
                    if (endBounds[key]?.contains(at) != true) armedKey = null
                }
            }
            .testTag(SidebarTags.Root),
    ) {
        if (phone) MobileHeader(onClose = actions.onCloseDrawer)

        NewSessionKey(onClick = actions.onNewSession)
        ScheduledNav(count = state.scheduledActionCount, onClick = actions.onOpenScheduledActions)

        ListHeader(
            openCount = view.openCount,
            state = state,
            actions = actions,
            phone = phone,
            harnessOpen = harnessOpen,
            onHarnessOpen = { harnessOpen = it; if (it) sortOpen = false },
            sortOpen = sortOpen,
            onSortOpen = { sortOpen = it; if (it) harnessOpen = false },
        )
        GlobalSearch(onClick = actions.onOpenGlobalSearch)
        if (state.sidebarSessions.size > 5) SessionFilter(state.query, actions.onSessionQueryChange)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { listBounds = it.boundsInRoot(); dragController.listBounds = listBounds }
                .semantics { contentDescription = "Workspaces" }
                .testTag(SidebarTags.List),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            view.blocks.forEachIndexed { position, block ->
                item(key = "block:${block.workspace}") {
                    WorkspaceBlock(
                        block = block,
                        state = state,
                        view = view,
                        actions = actions,
                        phone = phone,
                        first = position == 0,
                        armedKey = armedKey,
                        armedOrigin = armedOrigin,
                        onArm = { key ->
                            armedKey = key
                            armedOrigin = if (key != null) latestOrigin else null
                        },
                        swipedSeed = seed.swipedKey,
                        dragController = dragController,
                        draggingKey = drag?.takeIf { it.engaged }?.key,
                        openChildren = openChildren,
                        rowBounds = rowBounds,
                        endBounds = endBounds,
                        onShowMore = { ws -> visibleCounts = visibleCounts + (ws to ((visibleCounts[ws] ?: Web.WORKSPACE_PAGE_SIZE) + Web.WORKSPACE_PAGE_SIZE)) },
                        onShowLess = { ws -> visibleCounts = visibleCounts - ws },
                    )
                }
            }
            if (view.filtering && !view.anyVisible && state.sidebarSessions.isNotEmpty()) {
                item(key = "empty-filter") {
                    SidebarEmpty(if (view.querying || state.harness != null) "No sessions match your filter." else "No sessions need attention right now.")
                }
            }
            if (state.workspaces.isEmpty() && state.connected) {
                item(key = "empty") { SidebarEmpty("Add a workspace to see its sessions here.") }
            }
            item(key = "add-workspace") { AddWorkspaceRow(actions.onBrowseWorkspace) }
        }

        if (!view.attentionOn && view.archived.isNotEmpty()) {
            ArchivedGroup(
                rows = view.archived,
                open = archivedOpen,
                onToggle = { archivedOpen = !archivedOpen },
                now = state.now,
                onOpen = { entry ->
                    val live = entry.live
                    if (live != null) actions.onSelectSession(live.id) else entry.history?.let(actions.onReopenHistory)
                },
                // The web's list shrinks for an open group; here the group is capped and scrolls.
                modifier = Modifier.heightIn(max = 12f.rem),
            )
        }
        SidebarFooter(phone = phone, onOpenSettings = actions.onOpenSettings, onCollapse = actions.onCollapse)
    }
}

// ── Header ──────────────────────────────────────────────────────────────────────

/** `.sidebar-mobile-header` (globals.css 848-855; Studio 444: 1rem, margin-bottom 1rem). */
@Composable
private fun MobileHeader(onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 3f.rem)
            .padding(bottom = 1f.rem),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Workspaces",
            style = css(type.ui, 1f, 700),
            color = t.white,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box(
            Modifier
                .size(2.75f.rem)
                .clickable(role = Role.Button, onClickLabel = null, onClick = onClose)
                .semantics { contentDescription = "Close sessions" },
            contentAlignment = Alignment.Center,
        ) { SmallIcon(TetherIcons.X, t.white, 20.dp) }
    }
}

/** `.new-session-button` + its `<kbd>N</kbd>` (globals.css 889-925, 9003-9010, 10953-10962; studio.css 302-303). */
@Composable
private fun NewSessionKey(onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    TetherKey(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(SidebarTags.NewSession),
        classes = KeyClasses.NewSession,
        label = "New session",
        icon = TetherIcons.Plus,
        iconSize = 17.dp,
        fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
        minHeight = 2.875f.rem,
        contentArrangement = Arrangement.spacedBy(0.65f.rem),
        contentPadding = 0.875f.rem,
        trailing = {
            Spacer(Modifier.weight(1f))
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .clearAndSetSemantics { }
                    .then(
                        Modifier.background(Color.White.copy(alpha = 0.15f), shape),
                    )
                    .padding(horizontal = 0.4f.rem, vertical = 0.05f.rem),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "N",
                    style = css(type.ui, 0.7f, 550),
                    color = Color(0xFFE3EBFF),
                )
            }
        },
    )
}

/** `.scheduled-actions-nav` (globals.css 10399-10446, 10987-10993; studio.css 305-307). */
@Composable
private fun ScheduledNav(count: Int, onClick: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Row(
        Modifier
            .padding(top = 0.6f.rem, bottom = 1f.rem)
            .fillMaxWidth()
            .heightIn(min = 2.75f.rem)
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .alpha(if (onClick == null) 0.48f else 1f)
            .border(1.dp, Color.Transparent, shape)
            .padding(start = 0.875f.rem, end = 0.875f.rem),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        SmallIcon(TetherIcons.CalendarClock, t.muted, 17.dp)
        Text("Scheduled actions", style = css(type.ui, 0.8125f, 650), color = t.muted, modifier = Modifier.weight(1f))
        if (count > 0) CountPill(count, t.lineStrong, 0.64f)
    }
}

@Composable
private fun CountPill(count: Int, border: Color, rem: Float) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Box(
        Modifier.widthIn(min = 1.4f.rem).heightIn(min = 1.4f.rem).border(1.dp, border, RoundedCornerShape(999.dp)).padding(horizontal = 0.3f.rem),
        contentAlignment = Alignment.Center,
    ) { Text("$count", style = css(type.mono, rem, 650, lineHeight = 1f), color = t.faint) }
}

/** `.session-list-header`: the legend, the open count, and the filter bank (globals.css 928-1042, 10995-11075; studio.css 308-314, 445-447). */
@Composable
private fun ListHeader(
    openCount: Int,
    state: SidebarState,
    actions: SidebarActions,
    phone: Boolean,
    harnessOpen: Boolean,
    onHarnessOpen: (Boolean) -> Unit,
    sortOpen: Boolean,
    onSortOpen: (Boolean) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 0.45f.rem,
                end = 0.45f.rem,
                top = 0.dp,
                bottom = 0.625f.rem,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else t.css.spaceSm),
    ) {
        // The legend keeps its natural width (the web's flex row never shrinks it); on a rail too
        // narrow for both, the bank runs past the edge as on the web (tablet shot) rather than
        // squeezing the count away.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.45f.rem)) {
            Text(
                "Workspaces",
                style = css(type.ui, 0.75f, 700),
                color = t.faint,
                maxLines = 1,
                modifier = Modifier.semantics { heading(); contentDescription = "Workspaces, $openCount open" },
            )
            Text("$openCount", style = css(type.ui, 0.7f, 500), color = t.faint, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
            FilterBank(state, actions, phone, harnessOpen, onHarnessOpen, sortOpen, onSortOpen)
        }
    }
}

@Composable
private fun FilterBank(
    state: SidebarState,
    actions: SidebarActions,
    phone: Boolean,
    harnessOpen: Boolean,
    onHarnessOpen: (Boolean) -> Unit,
    sortOpen: Boolean,
    onSortOpen: (Boolean) -> Unit,
) {
    val t = LocalTetherTokens.current
    val bankShape = RoundedCornerShape(t.radiusKey - 1.dp)
    Row(
        Modifier.then(
            Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val harnessLabel = state.harness?.let { id -> Web.SIDEBAR_HARNESSES.firstOrNull { it.first == id }?.second }
        Box {
            BankKey(
                icon = TetherIcons.Filter,
                on = state.harness != null,
                description = if (harnessLabel != null) "Filtered to $harnessLabel — tap to change" else "Filter sessions by harness",
                phone = phone,
                expanded = harnessOpen,
                onClick = { onHarnessOpen(!harnessOpen) },
            )
            if (harnessOpen) {
                SidebarMenu(onDismiss = { onHarnessOpen(false) }, alignStart = true, label = "Filter by harness") {
                    MenuItem("All harnesses", checked = state.harness == null) { actions.onHarnessFilterChange(null); onHarnessOpen(false) }
                    Web.SIDEBAR_HARNESSES.forEach { (id, label) ->
                        MenuItem(label, checked = state.harness == id, leading = { ProviderCap(id, 1.5f.rem, inRow = false, letterRem = 0.65f) }) {
                            actions.onHarnessFilterChange(id)
                            onHarnessOpen(false)
                        }
                    }
                }
            }
        }
        BankKey(
            TetherIcons.Activity, state.activeOnly,
            "Active", phone, switch = true,
            stateText = if (state.activeOnly) "Showing sessions with work in progress" else "Show sessions with work in progress",
            onClick = actions.onToggleActiveOnly,
        )
        BankKey(
            TetherIcons.Mail, state.unreadOnly,
            "Unread", phone, switch = true,
            stateText = if (state.unreadOnly) "Showing sessions with unread reports" else "Show sessions with unread reports",
            onClick = actions.onToggleUnreadOnly,
        )
        BankKey(
            TetherIcons.BotOff, state.hideAgentRuns,
            "Hide runs", phone, switch = true,
            stateText = if (state.hideAgentRuns) "Hiding background CLI runs launched by agents" else "Hide background CLI runs launched by agents",
            onClick = actions.onToggleHideAgentRuns,
        )
        Box {
            val sortLabel = if (state.sort == SidebarSort.Created) "Created Date" else "Last Active"
            BankKey(
                icon = TetherIcons.ArrowDownUp,
                on = false,
                description = "Sort sessions by $sortLabel",
                phone = phone,
                expanded = sortOpen,
                onClick = { onSortOpen(!sortOpen) },
            )
            if (sortOpen) {
                SidebarMenu(onDismiss = { onSortOpen(false) }, alignStart = false, label = "Sort sessions") {
                    MenuItem("Created Date", checked = state.sort == SidebarSort.Created, trailingCheck = true) { actions.onSortModeChange(SidebarSort.Created); onSortOpen(false) }
                    MenuItem("Last Active", checked = state.sort == SidebarSort.LastActive, trailingCheck = true) { actions.onSortModeChange(SidebarSort.LastActive); onSortOpen(false) }
                }
            }
        }
    }
}

/**
 * One key of the filter bank: 1.7rem icon keys at every width (the labels are visually hidden,
 * globals.css 11057-11075); Studio 1.9rem × 2rem, 2.75rem on phones (studio.css 310-313, 445-447).
 * A latched key carries the violet selected tone AND its switch state in words (never colour alone).
 */
@Composable
private fun BankKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    description: String,
    phone: Boolean,
    switch: Boolean = false,
    stateText: String? = null,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    // Studio phones draw 2.75rem keys (studio.css 445-447); 2.5rem here so the legend and its count
    // fit the 21rem drawer on one line (the web's row lets the title shrink under the bank). The
    // touch target stays ≥ 44dp (Compose's minimum touch size).
    val w = (if (phone) 2.5f else 1.9f).rem
    val h = (if (phone) 2.75f else 2f).rem
    val shape = RoundedCornerShape(0.4f.rem)
    val shadows = if (on) listOf(
        com.tether.app.ui.theme.CssShadow(inset = true, offsetX = 0.dp, offsetY = 0.dp, blur = 0.dp, spread = 1.dp, color = t.violetStrong),
    ) else emptyList()
    Box(
        Modifier
            .size(w, h)
            .semantics {
                contentDescription = description
                if (switch) {
                    role = Role.Switch
                    stateDescription = (if (on) "On. " else "Off. ") + (stateText ?: "")
                } else {
                    role = Role.Button
                    if (expanded != null) stateDescription = if (expanded) "Menu open" else "Menu closed"
                }
                if (on) selected = true
            }
            .clickable(onClick = onClick)
            .cssSurface(shape, if (on) t.violetWash else Color.Transparent, null, shadows),
        contentAlignment = Alignment.Center,
    ) { SmallIcon(icon, if (on) t.violet else t.muted, if (switch) 13.dp else 12.dp) }
}

/** `.sidebar-harness-menu` / `.sidebar-sort-menu` (globals.css 988-1042). */
@Composable
private fun SidebarMenu(onDismiss: () -> Unit, alignStart: Boolean, label: String, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    val gap = with(density) { (t.css.spaceXs + 1.7f.rem).roundToPx() }
    Popup(
        alignment = if (alignStart) Alignment.TopStart else Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, gap),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .widthIn(min = 10f.rem)
                .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, CssBorder(1.dp, t.lineStrong), t.css.shadowMenu)
                .padding(t.css.spaceXs)
                .semantics { contentDescription = label },
        ) { content() }
    }
}

@Composable
private fun MenuItem(
    text: String,
    checked: Boolean,
    trailingCheck: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .heightIn(min = 2.75f.rem)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = checked
            }
            .clickable(onClick = onClick)
            .padding(horizontal = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        leading?.invoke()
        Text(text, style = css(type.ui, 0.78f, 550), color = if (checked) t.violet else t.ink, modifier = if (trailingCheck) Modifier.weight(1f, fill = false).widthIn(min = 7f.rem) else Modifier)
        if (trailingCheck && checked) SmallIcon(TetherIcons.Check, t.violet, 13.dp)
    }
}

/** `.session-global-search` (globals.css 1058-1075, 11078-11090; studio.css 314-315). T5.3 hosts the search. */
@Composable
private fun GlobalSearch(onClick: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(0.625f.rem)
    Row(
        Modifier
            .padding(bottom = 1f.rem)
            .fillMaxWidth()
            .heightIn(min = 2.75f.rem)
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .semantics { if (onClick == null) disabled() }
            .cssSurface(
                shape,
                Color(0xFF111A2B),
                CssBorder(1.dp, t.line),
                emptyList(),
            )
            .padding(horizontal = 0.75f.rem),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val ink = t.muted
        SmallIcon(TetherIcons.Search, ink, 14.dp)
        Text("Search all conversations…", style = css(type.ui, 0.8f, 400), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.session-filter` (globals.css 1044-1094, 8928-8943, 11091): a recessed well, shown past 5 rows. */
@Composable
private fun SessionFilter(query: String, onChange: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusSm)
    Row(
        Modifier
            .padding(bottom = t.css.spaceXs)
            .fillMaxWidth()
            .cssSurface(shape, t.mineralDeep, CssBorder(1.dp, t.lineStrong))
            .padding(horizontal = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        SmallIcon(TetherIcons.Search, t.faint, 14.dp)
        val style = css(type.ui, 0.8f, 400).copy(color = t.ink)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(t.violet),
            interactionSource = interaction,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 2.75f.rem)
                .semantics { contentDescription = "Filter sessions" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Filter sessions…", style = style, color = t.faint, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

// ── Blocks ──────────────────────────────────────────────────────────────────────

@Composable
private fun WorkspaceBlock(
    block: BlockView,
    state: SidebarState,
    view: SidebarView,
    actions: SidebarActions,
    phone: Boolean,
    first: Boolean,
    armedKey: String?,
    armedOrigin: String?,
    onArm: (String?) -> Unit,
    swipedSeed: String?,
    dragController: DragController,
    draggingKey: String?,
    openChildren: MutableMap<String, Boolean>,
    rowBounds: MutableMap<String, Rect>,
    endBounds: MutableMap<String, Rect>,
    onShowMore: (String) -> Unit,
    onShowLess: (String) -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 0.75f.rem)
            .testTag(SidebarTags.block(block.workspace))
            .semantics { contentDescription = SafeText.line(block.name) },
        verticalArrangement = Arrangement.spacedBy(0.15f.rem),
    ) {
        BlockHeader(block, actions, offline = !state.connected)
        if (!block.collapsed) {
            Column(
                Modifier.padding(start = 0.dp, top = 0.3f.rem, bottom = 0.1f.rem),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                block.rows.forEach { entry ->
                    val row: @Composable (SidebarEntry, Boolean) -> Unit = { e, draggable ->
                        SessionRow(
                            entry = e,
                            workspace = block.workspace,
                            active = SidebarViewModel.isActiveEntry(state, e),
                            now = state.now,
                            // T13.2: offline, the row's status is from a saved list (SYNC_DESIGN §4.2):
                            // r2: it says "was" whether or not the client has an entry for it; the
                            // entry only adds the copy's glyph and age.
                            offline = !state.connected,
                            sync = if (state.connected) null else e.live?.id?.let { state.syncStates[it] },
                            armed = armedKey == e.key,
                            armedOrigin = armedOrigin,
                            origin = state.origin,
                            onArm = onArm,
                            phone = phone,
                            swipedSeed = swipedSeed == e.key,
                            dragEnabled = view.dragEnabled && draggable && e.key !in view.delegateChildKeys,
                            dragging = draggingKey == e.key,
                            dragController = dragController,
                            rowBounds = rowBounds,
                            endBounds = endBounds,
                            actions = actions,
                        )
                    }
                    row(entry, true)
                    val children = block.childrenByKey[entry.key].orEmpty()
                    if (children.isNotEmpty()) {
                        val activeChild = children.any { SidebarViewModel.isActiveEntry(state, it) }
                        val open = activeChild || openChildren[entry.key] == true
                        DelegateToggle(children.size, open) { openChildren[entry.key] = !open }
                        if (open) {
                            Column(
                                Modifier
                                    .padding(start = 1.1f.rem)
                                    .drawBehind { drawRect(t.line, Offset.Zero, androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
                                    .padding(start = 1.dp + 0.2f.rem),
                            ) { children.forEach { child -> row(child, false) } }
                        }
                    }
                }
                if (block.totalRows == 0 && block.hiddenRuns == 0 && state.connected) {
                    val type = LocalTetherTypography.current
                    Text(
                        if (view.filtering) "No sessions match this filter" else "No sessions yet",
                        style = css(type.ui, 0.72f, 400),
                        color = t.faint,
                        modifier = Modifier.padding(start = 3.25f.rem, end = t.css.spaceMd, top = t.css.spaceXs, bottom = t.css.spaceSm),
                    )
                }
                if (block.remaining > 0) MoreRow(TetherIcons.ChevronDown, "Show more", count = block.remaining) { onShowMore(block.workspace) }
                if (block.remaining == 0 && block.expanded) MoreRow(TetherIcons.ChevronUp, "Show less") { onShowLess(block.workspace) }
                if (block.hiddenRuns > 0) {
                    MoreRow(
                        TetherIcons.Eye,
                        "${block.hiddenRuns} background CLI run${if (block.hiddenRuns == 1) "" else "s"}",
                        action = "Show",
                        onClick = actions.onToggleHideAgentRuns,
                    )
                }
                if (block.manualOrder && !view.filtering) ResetOrderRow { actions.onResetSessionOrder(block.workspace) }
            }
        }
    }
}

/** `.workspace-block-header` (globals.css 3035-3137; studio.css 317-322). */
@Composable
private fun BlockHeader(block: BlockView, actions: SidebarActions, offline: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val ink = if (block.isCurrent) t.white else t.muted
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 2.75f.rem)
            .then(
                // Studio zeroes the header's border width (studio.css 318); the current wash stays.
                if (block.isCurrent) Modifier.background(t.violetWash, shape).border(1.dp, Color.Transparent, shape)
                else Modifier.border(1.dp, Color.Transparent, shape),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 2.75f.rem)
                .semantics(mergeDescendants = true) {
                    stateDescription = buildString {
                        append(if (block.collapsed) "Collapsed" else "Expanded")
                        if (block.isCurrent) append(", current workspace")
                        block.activity?.let { a ->
                            // T13.2 r2: from a saved list the counts are what WAS, never "waiting" now.
                            when {
                                offline && a.waiting > 0 -> append(", ${a.waiting} ${if (a.waiting == 1) "was" else "were"} waiting")
                                offline && a.active > 0 -> append(", ${a.active} ${if (a.active == 1) "was" else "were"} active")
                                a.waiting > 0 -> append(", ${a.waiting} waiting")
                                a.active > 0 -> append(", ${a.active} active")
                            }
                        }
                    }
                }
                .clickable(role = Role.Button) { actions.onToggleWorkspaceCollapsed(block.workspace) }
                .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.625f.rem),
        ) {
            // .workspace-badge: the folder's initial (neutral; the name identifies).
            val badgeShape = RoundedCornerShape(0.4f.rem)
            Box(
                Modifier
                    .size(1.5f.rem)
                    .clearAndSetSemantics { }
                    .then(
                        Modifier.background(Color(0xFF283650), badgeShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Web.workspaceInitial(block.workspace),
                    style = css(type.ui, 0.75f, 700, lineHeight = 1f),
                    color = if (block.isCurrent) t.violet else Color(0xFFC2D1ED),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.1f.rem)) {
                // ta-28i: a workspace's folder name and path are code (server text), LTR.
                Text(codeLabel(block.name), style = css(type.ui, 0.79f, 650), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                block.path?.let {
                    Text(codeLabel(it), style = css(type.ui, 0.66f, 400), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            block.activity?.let { a ->
                // .project-dot: waiting (violet, radar ping) or active (running); the count is in words above.
                // T13.2 r2: offline, a faint still dot (no ping, no running tone): a saved list claims nothing now.
                if (offline && (a.waiting > 0 || a.active > 0)) {
                    StatusDot(t.faint, size = 0.5f.rem, modifier = Modifier.testTag(SidebarTags.blockDot(block.workspace)))
                } else if (a.waiting > 0) {
                    // `.project-dot-waiting { box-shadow: 0 0 0 3px var(--violet-wash) }` — the ring the
                    // radar ping animates away from; it is what remains under reduced motion.
                    val reduced = com.tether.app.ui.theme.LocalReducedMotion.current
                    val wash = t.violetWash
                    WaitingPingDot(
                        t.violet,
                        dotSize = 0.5f.rem,
                        modifier = if (reduced) Modifier.drawBehind { drawCircle(wash, radius = size.minDimension / 2 + 3.dp.toPx()) } else Modifier,
                    )
                }
                else if (a.active > 0) StatusDot(t.running, size = 0.5f.rem)
            }
            if (block.index < 9 && block.pinned) {
                Box(
                    Modifier
                        .clearAndSetSemantics { }
                        .widthIn(min = 1.15f.rem)
                        .border(1.dp, t.line, RoundedCornerShape(0.25f.rem))
                        .padding(horizontal = 0.3f.rem, vertical = 0.05f.rem),
                    contentAlignment = Alignment.Center,
                ) { Text("${block.index + 1}", style = css(type.mono, 0.6f, 400), color = t.faint) }
            }
            SmallIcon(TetherIcons.ChevronRight, t.faint, 14.dp, Modifier.rotate(if (block.collapsed) 0f else 90f))
        }
        HeaderAction(TetherIcons.Plus, 15.dp, "New session in ${SafeText.line(block.name)}", t.faint) { actions.onNewSessionIn(block.workspace) }
        HeaderAction(
            if (block.pinned) FilledStar else TetherIcons.Star,
            14.dp,
            if (block.pinned) "Unpin ${SafeText.line(block.name)}" else "Keep ${SafeText.line(block.name)} in the sidebar",
            if (block.pinned) t.violet else t.faint,
            state = if (block.pinned) "Pinned" else "Not pinned",
        ) { actions.onTogglePinnedProject(block.workspace) }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    description: String,
    tint: Color,
    state: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(2.25f.rem)
            .heightIn(min = 2.75f.rem)
            .semantics {
                contentDescription = description
                state?.let { stateDescription = it }
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SmallIcon(icon, tint, size) }
}

/** `.session-children-toggle` (globals.css 3185-3217): "N delegate(s)", collapsed by default. */
@Composable
private fun DelegateToggle(count: Int, open: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .padding(start = 0.9f.rem)
            .heightIn(min = 2.75f.rem)
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        SmallIcon(TetherIcons.ChevronDown, t.faint, 12.dp, Modifier.rotate(if (open) 180f else 0f))
        Text("$count delegate${if (count == 1) "" else "s"}", style = css(type.ui, 0.72f, 400), color = t.faint)
    }
}

/** `.workspace-block-more` (globals.css 3157-3230). */
@Composable
private fun MoreRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    count: Int? = null,
    action: String? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 2.75f.rem)
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 0.9f.rem, end = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        SmallIcon(icon, t.faint, 14.dp)
        Text(text, style = css(type.ui, 0.74f, 400), color = t.faint, modifier = Modifier.weight(1f))
        count?.let { CountPill(it, t.line, 0.62f) }
        action?.let { Text(it, style = css(type.ui, 0.74f, 680), color = t.muted) }
    }
}

/** `.session-order-reset` (globals.css 1105-1124). */
@Composable
private fun ResetOrderRow(onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 2.75f.rem)
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(bottom = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs, Alignment.CenterHorizontally),
    ) {
        SmallIcon(TetherIcons.RotateCcw, t.faint, 13.dp)
        Text("Reset to default order", style = css(type.ui, 0.7f, 400), color = t.faint)
    }
}

/** `.workspace-add-row` (globals.css 3232-3252; studio.css 343-344). */
@Composable
private fun AddWorkspaceRow(onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val line = t.line
    Row(
        Modifier
            .padding(top = t.css.spaceSm)
            .fillMaxWidth()
            .heightIn(min = 2.75f.rem)
            .then(
                Modifier,
            )
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 0.8f.rem, end = t.css.spaceSm)
            .testTag(SidebarTags.AddWorkspace),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val ink = t.muted
        SmallIcon(TetherIcons.FolderPlus, ink, 15.dp)
        Text("Add workspace", style = css(type.ui, 0.75f, 600), color = ink)
    }
}

@Composable
private fun SidebarEmpty(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = css(type.ui, 0.8f, 400, lineHeight = 1.6f),
        color = t.faint,
        modifier = Modifier.widthIn(max = 16f.rem).padding(horizontal = t.css.spaceMd, vertical = t.css.space2xl),
    )
}

// ── Archived + footer ───────────────────────────────────────────────────────────

/** `.session-archived-group` (globals.css 1332-1410): collapsed by default, quiet, no status colour. */
@Composable
private fun ArchivedGroup(
    rows: List<SidebarEntry>,
    open: Boolean,
    onToggle: () -> Unit,
    now: Long,
    onOpen: (SidebarEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        modifier
            .padding(top = t.css.spaceXs)
            .fillMaxWidth()
            .drawBehind { drawRect(t.line, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(top = t.css.spaceXs),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 2.75f.rem)
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.35f.rem),
        ) {
            SmallIcon(TetherIcons.Archive, t.faint, 12.dp)
            Text("Archived", style = css(type.ui, 0.68f, 400), color = t.faint)
            Text("${rows.size}", style = css(type.ui, 0.68f * 0.833f, 400), color = t.faint)
        }
        if (open) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 0.15f.rem, bottom = t.css.spaceXs),
                verticalArrangement = Arrangement.spacedBy(0.1f.rem),
            ) {
                rows.forEach { entry ->
                    val provider = entry.live?.provider?.takeIf { it.isNotEmpty() } ?: entry.history?.provider ?: return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 2.75f.rem)
                            .semantics(mergeDescendants = true) { }
                            .clickable(role = Role.Button) { onOpen(entry) }
                            .alpha(0.8f)
                            .padding(horizontal = t.css.spaceMd, vertical = 0.2f.rem)
                            .testTag(SidebarTags.row(entry.key)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                    ) {
                        ProviderCap(provider, 1.5f.rem, inRow = false, letterRem = 0.65f)
                        Column(verticalArrangement = Arrangement.spacedBy(0.05f.rem)) {
                            Text(SidebarViewModel.archivedName(entry), style = css(type.ui, 0.72f, 550), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Ended ${Format.relativeTime(jsUpdatedAt(entry), now.toDouble())} ago",
                                style = css(type.ui, 0.62f, 400),
                                color = t.faint,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun jsUpdatedAt(entry: SidebarEntry): Double = (entry.js["updatedAt"] as? com.tether.app.protocol.tree.JsNum)?.value ?: 0.0

/** `.sidebar-footer` (globals.css 1619-1640, 3264-3271, 11120-11129; studio.css 345-348). */
@Composable
private fun SidebarFooter(phone: Boolean, onOpenSettings: () -> Unit, onCollapse: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(line, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, px))
            }
            .padding(top = 0.75f.rem, start = t.css.spaceXs, end = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Collapse is desktop-only: below 48rem the drawer has its own close.
        if (!phone && onCollapse != null) {
            FooterButton(TetherIcons.PanelLeftClose, "Collapse sidebar", onClick = onCollapse)
        }
        FooterButton(TetherIcons.Settings, "Open settings", label = "Settings", onClick = onOpenSettings)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            StatusDot(t.running, size = 0.4f.rem)
            Text(
                "Private runtime",
                style = css(type.ui, 0.66f, 500),
                color = t.faint,
                modifier = Modifier.semantics { contentDescription = "Private runtime" },
            )
        }
    }
}

@Composable
private fun FooterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, label: String? = null, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .heightIn(min = 2.75f.rem)
            .widthIn(min = 2.75f.rem)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (label != null) 0.25f.rem else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.5f.rem, Alignment.CenterHorizontally),
    ) {
        SmallIcon(icon, t.muted, 17.dp)
        label?.let { Text(it, style = css(type.ui, 0.75f, 500), color = t.muted, modifier = Modifier.clearAndSetSemantics { }) }
    }
}

/** Keeps the lazy list's scroll API in one place for the drag controller. */
internal suspend fun LazyListState.scrollBy(px: Float) {
    scroll { scrollBy(px) }
}
