package com.tether.app.ui.sidebar

import com.tether.app.ui.util.RecompositionProbe
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.resolveKey
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import com.tether.app.client.LabelText
import com.tether.app.protocol.helpers.SidebarOrder
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.theme.LocalReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.testTag
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
import com.tether.app.ui.components.CssFlexRow
import com.tether.app.ui.components.FlexJustify
import com.tether.app.ui.components.flexFloor
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
    /**
     * v141 `archive-stale` (use-tether.ts requestArchiveStale): (mode, days, the open session exempted, the server
     * origin the dialog was opened for). True when the frame went out.
     */
    val onArchiveStale: (String, Int, String?, String?) -> Boolean = { _, _, _, _ -> false },
    /** use-tether.ts `setArchiveStale(null)`: opening the dialog drops the last reply. */
    val onClearArchiveStale: () -> Unit = {},
    /** S3a, the row menu (web `onRenameSession` / `onTogglePinSession` / `onHandOver` / `onStopHandover`). (session id, its name). */
    val onRenameSession: (String, String) -> Unit = { _, _ -> },
    /** (session id, the new pinned state). */
    val onTogglePinSession: (String, Boolean) -> Unit = { _, _ -> },
    /** The open session's Hand over confirm (the shell's); only the open row offers it. */
    val onHandOver: () -> Unit = {},
    val onStopHandover: () -> Unit = {},
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
    /** Blocks whose "Older" band starts expanded (the web's `olderOpen` state). */
    val olderOpen: Set<String> = emptySet(),
    /** HG (tth-0bs): handover chains (by ROOT key) whose earlier sessions start open. */
    val openChains: Set<String> = emptySet(),
    /** Chains (by ROOT key) that have already shown these reasons: a preloaded `seen`, so nothing rises on mount. */
    val seenChains: Map<String, List<String>> = emptyMap(),
    /** Keyboard mode with focus on this chain's toggle (ROOT key), for the focus golden. */
    val focusChain: String? = null,
    /** The "Archive idle sessions" dialog starts open, in this state (a golden's starting point). */
    val archiveStale: ArchiveStaleState? = null,
    /** S3a: the row whose menu starts open (a golden's starting point). */
    val menuKey: String? = null,
)

object SidebarTags {
    const val Root = "sidebar"
    const val List = "sidebar-list"
    const val NewSession = "sidebar-new-session"
    const val AddWorkspace = "sidebar-add-workspace"
    /** T9.3: the rail's Scheduled actions key. */
    const val Scheduled = "sidebar-scheduled"
    fun row(key: String) = "sidebar-row:$key"
    fun freshness(key: String) = "sidebar-freshness:$key"
    fun blockDot(workspace: String) = "sidebar-block-dot:$workspace"
    fun handle(key: String) = "sidebar-handle:$key"
    fun end(key: String) = "sidebar-end:$key"
    /** S3a: the phone row's kebab (the tablet keeps [end]). */
    fun kebab(key: String) = "sidebar-kebab:$key"
    const val RowMenu = "sidebar-row-menu"
    const val RowMenuDigest = "sidebar-row-menu-digest"
    fun menuItem(id: com.tether.app.protocol.helpers.SidebarRowModel.RowMenuItemId) = "sidebar-menu-item:${id.name}"
    fun badge(key: String) = "sidebar-badge:$key"
    fun workspaceCount(workspace: String) = "sidebar-workspace-count:$workspace"
    fun archive(key: String) = "sidebar-archive:$key"
    fun block(workspace: String) = "sidebar-block:$workspace"
    fun older(workspace: String) = "sidebar-older:$workspace"
    /** The chain toggle under the head row [headKey]. */
    fun chainToggle(headKey: String) = "sidebar-chain-toggle:$headKey"
}

/** How long an armed end control stays armed (session-sidebar.tsx:40). */
const val END_SESSION_ARM_MS = 4000L

/**
 * The phone drawer's shared numbers (ta-1jj7, owner-directed design: the compact full-screen drawer).
 * The shell's host (SessionDrawerHost) and the goldens' host copy (SidebarFixtures) both read these,
 * so the panel's edges cannot drift between the app and its pictures. Tablet code never reads them.
 */
object PhoneDrawer {
    /** The panel's design minimum at the top, start and end (each side is the larger of this, the system bars and the cutout). */
    val Edge = 8.dp

    /** The panel's design minimum at the foot; ta-8znp: the navigation bar raises it, as the web's `max(space-md, safe-area-inset-bottom)`. */
    val Bottom = 12.dp

    /** The one touch floor: every control on the phone drawer is at least this tall and wide. */
    val Floor = 48.dp

    /** The provider cap inside a session row's 48 dp handle. */
    val Cap = 24.dp

    /**
     * Below this window height (dp at font scale 1.0, times the font scale) the header's lower rows scroll
     * with the list instead of sitting above it: the list keeps a usable viewport.
     */
    const val FixedHeaderMinHeightDp = 480

    /** Whether the header scrolls with the list: [windowHeightDp] under [FixedHeaderMinHeightDp] x [fontScale]. */
    fun headerScrolls(windowHeightDp: Float, fontScale: Float): Boolean = windowHeightDp < FixedHeaderMinHeightDp * fontScale
}

/**
 * W39 (ta-v0h8): the phone drawer's look (the session-list redesign, SL-spec as amended by the director).
 * The four looks the live web does not all share yet each sit behind ONE constant here: flip it to put the
 * old look back (one line each). Phone only: nothing here is read by the tablet rail.
 */
object PhoneLook {
    /** The filter field is always drawn (the web's SB rule). false: only past 5 sessions, as before. */
    const val FilterFieldAlwaysVisible = true

    /** The row's provider tile is a neutral single-ink tile. false: the brand tile ([ProviderTile]). Off: the owner put the coloured logos back (W40-R1, ta-3sq0). */
    const val MonochromeProviderTile = false

    /** The workspace header's keyboard digit hint is hidden (hint only: no shortcut is bound to it). */
    const val HideKbdDigits = true

    /** A row is at least this tall, with [RowVPad] above and below its two text lines (director amendment 2). */
    val RowMinHeight = 56.dp
    val RowVPad = 9.dp

    /** The 28 dp tile inside the 48 dp drag handle; the handle centres it on x = 24 of the card. */
    val Tile = 28.dp

    /** A delegate child's tile (web `.session-item-lead-tile .provider-glyph`, 1.375rem). */
    val ChildTile = 22.dp

    /** The 3 dp selected bar. */
    val SelectedBar = 3.dp

    /** The card's corner radius; its fill is `graphiteRaised`. */
    val CardRadius = 12.dp

    /** Added to [PhoneDrawer.Edge] so content sits 12 dp from the panel's edge. */
    val InnerGutter = 4.dp

    /** The leading slot (handle, avatar, glyph): 48 dp, content centred on x = 24; the title column starts after it + 8. */
    val Lead = 48.dp
}

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
    RecompositionProbe("SessionSidebar")
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
    // ta-1jj7 (owner-directed design): on a short window the header's lower rows scroll with the list.
    val fontScale = LocalDensity.current.fontScale
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
    val headerScrolls = phone && PhoneDrawer.headerScrolls(windowHeightDp, fontScale)

    var visibleCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // session-sidebar.tsx:609 — blocks whose "Older" band is expanded; collapsed by default, per block.
    var olderOpen by remember { mutableStateOf(seed.olderOpen) }
    // archive-stale-dialog.tsx:27-32 — the dialog's own state; it persists across opens like the web's mounted dialog.
    var archiveOpen by remember { mutableStateOf(seed.archiveStale != null) }
    var archiveState by remember { mutableStateOf(seed.archiveStale ?: ArchiveStaleState()) }
    // The server the dialog was opened for: a run is never delivered to another one.
    var archiveOrigin by remember { mutableStateOf(state.origin) }
    var armedKey by remember { mutableStateOf(seed.armedKey) }
    // S3a: the row whose kebab menu is open (one shared menu per list, as the web's one popover per rail).
    var menuKey by remember { mutableStateOf(seed.menuKey) }
    // T13.2 r3: the server the armed end control was armed for; its second tap ends on that server only.
    val latestOrigin by rememberUpdatedState(state.origin)
    var armedOrigin by remember { mutableStateOf(if (seed.armedKey != null) state.origin else null) }
    var drag by remember { mutableStateOf<DragSession?>(seed.drag?.let { DragSession(it.key, "", it.order, it.order, engaged = true) }) }
    var harnessOpen by remember { mutableStateOf(seed.harnessMenuOpen) }
    var sortOpen by remember { mutableStateOf(seed.sortMenuOpen) }
    var archivedOpen by remember { mutableStateOf(seed.archivedOpen) }
    val openChildren = remember { mutableStateMapOf<String, Boolean>().apply { seed.openChildren.forEach { put(it, true) } } }
    // HG (tth-0bs) session-sidebar.tsx:925-943 — open/closed and the last reason set, per chain ROOT key.
    val chainOpen = remember { mutableStateMapOf<String, Boolean>().apply { seed.openChains.forEach { put(it, true) } } }
    val chainSeen = remember { mutableStateMapOf<String, List<String>>().apply { putAll(seed.seenChains) } }
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

    // The web closes the menu on a list scroll (the anchor would have moved); an armed End never outlives it.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && menuKey != null) { menuKey = null; armedKey = null }
    }

    val dragPreview = drag?.takeIf { it.engaged }?.let { DragPreview(it.key, it.order) }
    val view = SidebarViewModel.view(state, visibleCounts, dragPreview, olderOpen)

    // A RISING edge (a reason key not yet seen) opens the chain, on mount too; a falling edge does nothing.
    view.blocks.forEach { block ->
        block.chainSummaries.values.forEach { chain ->
            val before = chainSeen[chain.rootKey].orEmpty()
            val rising = chain.reasonKeys.any { it !in before }
            if (rising && chainOpen[chain.rootKey] != true) chainOpen[chain.rootKey] = true
            if (rising || before.size != chain.reasonKeys.size) chainSeen[chain.rootKey] = chain.reasonKeys
        }
    }

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
            .testTag(SidebarTags.Root)
            .then(if (phone) Modifier.padding(horizontal = PhoneLook.InnerGutter) else Modifier),
    ) {
        val scheduledRow: @Composable () -> Unit = {
            ScheduledNav(count = state.scheduledActionCount, active = state.scheduledActionsActive, onClick = actions.onOpenScheduledActions, phone = phone)
        }
        val legendRow: @Composable () -> Unit = {
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
        }
        val showFilter = state.sidebarSessions.size > 5 || (phone && PhoneLook.FilterFieldAlwaysVisible)
        // W39: the phone's filter chips + icon keys (Active, Unread, harness, Hide runs, Sort) under the field.
        val phoneFilters: @Composable () -> Unit = {
            FilterBank(
                state, actions, true,
                harnessOpen, { harnessOpen = it; if (it) sortOpen = false },
                sortOpen, { sortOpen = it; if (it) harnessOpen = false },
            )
        }
        if (phone) {
            // W39: the title block (Workspaces, N open, Private runtime) and the three keys share the header.
            PhoneHeader(openCount = view.openCount, onNewSession = actions.onNewSession, onSearch = actions.onOpenGlobalSearch, onClose = actions.onCloseDrawer)
            if (!headerScrolls) {
                if (showFilter) SessionFilter(state.query, actions.onSessionQueryChange, phone = true)
                phoneFilters()
                PhoneRule()
            }
        } else {
            NewSessionKey(onClick = actions.onNewSession, phone = false, modifier = Modifier.fillMaxWidth())
            scheduledRow()
            legendRow()
            GlobalSearch(onClick = actions.onOpenGlobalSearch)
            if (showFilter) SessionFilter(state.query, actions.onSessionQueryChange, phone = false)
        }

        LazyColumn(
            state = listState,
            contentPadding = if (phone) PaddingValues(top = 4.dp) else PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { listBounds = it.boundsInRoot(); dragController.listBounds = listBounds }
                .semantics { contentDescription = "Workspaces" }
                .testTag(SidebarTags.List),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (headerScrolls) {
                if (showFilter) item(key = "hdr-filter") { SessionFilter(state.query, actions.onSessionQueryChange, phone = true) }
                item(key = "hdr-chips") { phoneFilters() }
                item(key = "hdr-rule") { PhoneRule() }
            }
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
                        menuKey = menuKey,
                        onMenuKey = { key ->
                            // Closing a menu, or moving to another row's, for any reason disarms End (SL-spec-3 §b.3).
                            if (key != menuKey) armedKey = null
                            menuKey = key
                        },
                        onArm = { key ->
                            armedKey = key
                            armedOrigin = if (key != null) latestOrigin else null
                        },
                        swipedSeed = seed.swipedKey,
                        dragController = dragController,
                        draggingKey = drag?.takeIf { it.engaged }?.key,
                        openChildren = openChildren,
                        chainOpen = chainOpen,
                        focusChain = seed.focusChain,
                        rowBounds = rowBounds,
                        endBounds = endBounds,
                        onShowMore = { ws -> visibleCounts = visibleCounts + (ws to ((visibleCounts[ws] ?: Web.WORKSPACE_PAGE_SIZE) + Web.WORKSPACE_PAGE_SIZE)) },
                        onShowLess = { ws -> visibleCounts = visibleCounts - ws },
                        onToggleOlder = { ws -> olderOpen = if (ws in olderOpen) olderOpen - ws else olderOpen + ws },
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
            item(key = "archive-stale") {
                // session-sidebar.tsx:1236 — opens the dialog and asks for a preview of the threshold chosen last.
                SidebarAddRow(TetherIcons.Archive, 14.dp, ArchiveStaleCopy.ENTRY, ArchiveStaleTags.Entry, phone, topGap = if (phone) 16.dp else null) {
                    val step = ArchiveStaleModel.open(archiveState)
                    archiveState = step.state
                    archiveOrigin = latestOrigin
                    actions.onClearArchiveStale()
                    archiveOpen = true
                    step.request?.let { actions.onArchiveStale(it.mode, it.days, latestState.activeSessionId, latestOrigin) }
                }
            }
            item(key = "add-workspace") { AddWorkspaceRow(actions.onBrowseWorkspace, phone) }
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
                phone = phone,
            )
        }
        SidebarFooter(phone = phone, onOpenSettings = actions.onOpenSettings, onCollapse = actions.onCollapse, phoneScheduled = scheduledRow)
    }

    if (archiveOpen) {
        ArchiveStaleDialog(
            state = archiveState,
            reply = state.archiveStale,
            onState = { archiveState = it },
            onRequest = { actions.onArchiveStale(it.mode, it.days, latestState.activeSessionId, archiveOrigin) },
            onClose = { archiveOpen = false },
        )
    }
}

// ── Header ──────────────────────────────────────────────────────────────────────

/**
 * W39 (ta-v0h8): the phone drawer's header, one calm block. Start: "Workspaces" 20/700 with its open count
 * (the legend's number and its "Workspaces, N open" name, moved here), and a green "Private runtime" line
 * under it (moved from the footer). End: Search all conversations, the cobalt icon-only New session (tooltip
 * "New session (N)") and Close, each at the 48 dp floor. At a large font the keys wrap under the title.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PhoneHeader(openCount: Int, onNewSession: () -> Unit, onSearch: (() -> Unit)?, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = PhoneLook.RowMinHeight),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Workspaces",
                    style = css(type.ui, 1.25f, 700, lineHeight = 1.2f),
                    color = t.white,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline().semantics { heading(); contentDescription = "Workspaces, $openCount open" },
                )
                Text(
                    "$openCount",
                    style = css(type.ui, 0.875f, 500),
                    color = t.faint,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline().clearAndSetSemantics { },
                )
            }
            Row(
                Modifier.padding(top = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Private runtime" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(t.running, size = 6.dp)
                Text("Private runtime", style = css(type.ui, 0.75f, 500, lineHeight = 1.3333f), color = t.muted, maxLines = 1)
            }
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(PhoneDrawer.Floor)
                    .clickable(enabled = onSearch != null, role = Role.Button) { onSearch?.invoke() }
                    .semantics {
                        contentDescription = "Search all conversations"
                        if (onSearch == null) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) { SmallIcon(TetherIcons.Search, t.muted, 20.dp) }
            // The one primary action: the cobalt key, icon only; the name and the long-press tooltip say what it is.
            // The key IS the tooltip's anchor (one node: click, name and long-press together, as the chain toggle, ta-btv8).
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val look = resolveKey(t, KeyClasses.NewSession, if (pressed) KeyState.Pressed else KeyState.Rest)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("New session (N)") } },
                state = rememberTooltipState(),
                modifier = Modifier
                    .size(PhoneDrawer.Floor)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onNewSession)
                    .clearAndSetSemantics {
                        testTag = SidebarTags.NewSession
                        contentDescription = "New session"
                        role = Role.Button
                        onClick { onNewSession(); true }
                    },
            ) {
                // The face is 44 dp inside the 48 dp touch target.
                Box(
                    Modifier.fillMaxSize().padding(2.dp).cssSurface(RoundedCornerShape(t.radiusMd), look.face, CssBorder(1.dp, look.border), look.shadows),
                    contentAlignment = Alignment.Center,
                ) { SmallIcon(TetherIcons.Plus, look.ink, 22.dp) }
            }
            Box(
                Modifier
                    .size(PhoneDrawer.Floor)
                    .clickable(role = Role.Button, onClickLabel = null, onClick = onClose)
                    .semantics { contentDescription = "Close sessions" },
                contentAlignment = Alignment.Center,
            ) { SmallIcon(TetherIcons.X, t.white, 20.dp) }
        }
    }
}

/** The phone drawer's rule between its controls and the list: 8 dp of air, then a full-width 1 dp `--line`. */
@Composable
private fun PhoneRule() {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** `.new-session-button` + its `<kbd>N</kbd>` (globals.css 889-925, 9003-9010, 10953-10962; studio.css 302-303). */
@Composable
private fun NewSessionKey(onClick: () -> Unit, phone: Boolean, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    TetherKey(
        onClick = onClick,
        modifier = modifier.testTag(SidebarTags.NewSession),
        classes = KeyClasses.NewSession,
        label = "New session",
        icon = TetherIcons.Plus,
        iconSize = 17.dp,
        fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
        minHeight = if (phone) PhoneDrawer.Floor else 2.875f.rem,
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

/**
 * `.scheduled-actions-nav` (globals.css 10399-10446, 10987-10993; studio.css 305-307). T9.3: while
 * the destination is on screen it is `is-active` (studio.css 312: white on `--graphite-raised`; the
 * count's ring `--violet-strong`, its figure `--violet-deep`) and `aria-current="page"`.
 */
@Composable
private fun ScheduledNav(count: Int, active: Boolean, onClick: (() -> Unit)?, phone: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    if (phone) {
        // W39: on the phone it is the footer's second destination: icon 20, "Scheduled actions" 13/600, the count as today.
        Row(
            Modifier
                .heightIn(min = PhoneDrawer.Floor)
                .clip(shape)
                .then(if (active) Modifier.background(t.graphiteRaised, shape).semantics { selected = true } else Modifier)
                // C1: the clickable View had no name or role on device; one Button node, named, with its count.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = if (count > 0) "Scheduled actions, $count" else "Scheduled actions"
                }
                .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
                .alpha(if (onClick == null) 0.48f else 1f)
                .padding(horizontal = 8.dp)
                .testTag(SidebarTags.Scheduled),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = if (active) t.white else t.muted
            SmallIcon(TetherIcons.CalendarClock, ink, 20.dp)
            Text("Scheduled actions", style = css(type.ui, 0.8125f, 600), color = ink, maxLines = 1)
            if (count > 0) CountPill(count, if (active) t.violetStrong else t.lineStrong, 0.64f, if (active) t.violetDeep else t.faint)
        }
        return
    }
    Row(
        Modifier
            .padding(top = if (phone) 0.dp else 0.6f.rem, bottom = if (phone) 0.dp else 1f.rem)
            .fillMaxWidth()
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
            .then(if (active) Modifier.background(t.graphiteRaised, shape).semantics { selected = true } else Modifier)
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .alpha(if (onClick == null) 0.48f else 1f)
            .border(1.dp, Color.Transparent, shape)
            .padding(start = 0.875f.rem, end = 0.875f.rem)
            .testTag(SidebarTags.Scheduled),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val ink = if (active) t.white else t.muted
        SmallIcon(TetherIcons.CalendarClock, ink, 17.dp)
        Text("Scheduled actions", style = css(type.ui, 0.8125f, 650), color = ink, modifier = Modifier.weight(1f))
        if (count > 0) CountPill(count, if (active) t.violetStrong else t.lineStrong, 0.64f, if (active) t.violetDeep else t.faint)
    }
}

@Composable
private fun CountPill(count: Int, border: Color, rem: Float, ink: Color = LocalTetherTokens.current.faint) {
    val type = LocalTetherTypography.current
    Box(
        Modifier.widthIn(min = 1.4f.rem).heightIn(min = 1.4f.rem).border(1.dp, border, RoundedCornerShape(999.dp)).padding(horizontal = 0.3f.rem),
        contentAlignment = Alignment.Center,
    ) { Text("$count", style = css(type.mono, rem, 650, lineHeight = 1f), color = ink) }
}

/** `.session-list-header`: the legend, the open count, and the filter bank (globals.css 928-1042, 10995-11075; studio.css 308-314, 445-447). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // The legend keeps its natural width (the web's flex row never shrinks it); on a rail too
    // narrow for both, the bank runs past the edge as on the web (tablet shot) rather than
    // squeezing the count away. On the phone drawer (ta-1jj7, owner-directed design) the bank wraps
    // under the legend instead, so nothing runs past the edge at a large font.
    val legend: @Composable () -> Unit = {
        Row(
            Modifier.then(if (phone) Modifier.heightIn(min = PhoneDrawer.Floor) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.45f.rem),
        ) {
            Text(
                "Workspaces",
                style = css(type.ui, 0.75f, 700),
                color = t.faint,
                maxLines = 1,
                modifier = Modifier.semantics { heading(); contentDescription = "Workspaces, $openCount open" },
            )
            Text("$openCount", style = css(type.ui, 0.7f, 500), color = t.faint, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
        }
    }
    if (phone) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(start = 0.45f.rem),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            legend()
            FilterBank(state, actions, phone, harnessOpen, onHarnessOpen, sortOpen, onSortOpen)
        }
        return
    }
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
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        legend()
        Spacer(Modifier.weight(1f))
        Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
            FilterBank(state, actions, phone, harnessOpen, onHarnessOpen, sortOpen, onSortOpen)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val harnessKey: @Composable () -> Unit = {
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
                SidebarMenu(onDismiss = { onHarnessOpen(false) }, alignStart = true, label = "Filter by harness", phone = phone) {
                    MenuItem("All harnesses", checked = state.harness == null, phone = phone) { actions.onHarnessFilterChange(null); onHarnessOpen(false) }
                    Web.SIDEBAR_HARNESSES.forEach { (id, label) ->
                        MenuItem(label, checked = state.harness == id, leading = { ProviderCap(id, 1.5f.rem, inRow = false, letterRem = 0.65f) }, phone = phone) {
                            actions.onHarnessFilterChange(id)
                            onHarnessOpen(false)
                        }
                    }
                }
            }
        }
    }
    val activeKey: @Composable () -> Unit = {
        val stateText = if (state.activeOnly) "Showing sessions with work in progress" else "Show sessions with work in progress"
        if (phone) PhoneChip(TetherIcons.Activity, "Active", state.activeOnly, stateText, actions.onToggleActiveOnly)
        else BankKey(TetherIcons.Activity, state.activeOnly, "Active", phone, switch = true, stateText = stateText, onClick = actions.onToggleActiveOnly)
    }
    val unreadKey: @Composable () -> Unit = {
        val stateText = if (state.unreadOnly) "Showing sessions with unread reports" else "Show sessions with unread reports"
        if (phone) PhoneChip(TetherIcons.Mail, "Unread", state.unreadOnly, stateText, actions.onToggleUnreadOnly)
        else BankKey(TetherIcons.Mail, state.unreadOnly, "Unread", phone, switch = true, stateText = stateText, onClick = actions.onToggleUnreadOnly)
    }
    val hideKey: @Composable () -> Unit = {
        BankKey(
            TetherIcons.BotOff, state.hideAgentRuns,
            "Hide runs", phone, switch = true,
            stateText = if (state.hideAgentRuns) "Hiding background CLI runs launched by agents" else "Hide background CLI runs launched by agents",
            onClick = actions.onToggleHideAgentRuns,
        )
    }
    val sortKey: @Composable () -> Unit = {
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
                SidebarMenu(onDismiss = { onSortOpen(false) }, alignStart = false, label = "Sort sessions", phone = phone) {
                    MenuItem("Created Date", checked = state.sort == SidebarSort.Created, trailingCheck = true, phone = phone) { actions.onSortModeChange(SidebarSort.Created); onSortOpen(false) }
                    MenuItem("Last Active", checked = state.sort == SidebarSort.LastActive, trailingCheck = true, phone = phone) { actions.onSortModeChange(SidebarSort.LastActive); onSortOpen(false) }
                }
            }
        }
    }
    if (phone) {
        // W39: Active and Unread are labelled chips (start); harness, Hide runs and Sort stay icon keys (end).
        // The two groups wrap at a large font, the icon keys under the chips, aligned to the end.
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                activeKey()
                unreadKey()
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                harnessKey()
                hideKey()
                sortKey()
            }
        }
        return
    }
    Row(
        Modifier.then(
            Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        harnessKey()
        activeKey()
        unreadKey()
        hideKey()
        sortKey()
    }
}

/**
 * W39: a labelled filter chip (Active, Unread): the same switch as the rail's icon key, with its words
 * drawn. 48 dp to touch, 36 dp to see; latched = `violetWash` + a hairline `violetStrong` + `violet`.
 */
@Composable
private fun PhoneChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, stateText: String, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        Modifier
            .heightIn(min = PhoneDrawer.Floor)
            .semantics {
                contentDescription = label
                role = Role.Switch
                stateDescription = (if (on) "On. " else "Off. ") + stateText
                if (on) selected = true
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (on) t.violet else t.muted
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .cssSurface(shape, if (on) t.violetWash else Color.Transparent, CssBorder(1.dp, if (on) t.violetStrong else t.line), emptyList())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallIcon(icon, ink, 16.dp)
            Text(label, style = css(type.ui, 0.8125f, 600), color = ink, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
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
    // The phone drawer's keys are 48 x 48 (ta-1jj7, owner-directed design: one touch floor); the
    // rail keeps Studio's 1.9rem x 2rem.
    val w = if (phone) PhoneDrawer.Floor else 1.9f.rem
    val h = if (phone) PhoneDrawer.Floor else 2f.rem
    val shape = RoundedCornerShape(0.4f.rem)
    val shadows = if (on) listOf(
        com.tether.app.ui.theme.CssShadow(inset = true, offsetX = 0.dp, offsetY = 0.dp, blur = 0.dp, spread = 1.dp, color = t.violetStrong),
    ) else emptyList()
    if (phone) {
        // W39: a 48 dp key around a 36 dp pill (latched = `violetWash`), glyph 18.
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
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(36.dp).cssSurface(RoundedCornerShape(t.radiusMd), if (on) t.violetWash else Color.Transparent, null, shadows),
                contentAlignment = Alignment.Center,
            ) { SmallIcon(icon, if (on) t.violet else t.muted, 18.dp) }
        }
        return
    }
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
private fun SidebarMenu(onDismiss: () -> Unit, alignStart: Boolean, label: String, phone: Boolean = false, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    // The phone's 48 dp keys put the menu a full key below the anchor's top (ta-1jj7).
    val gap = with(density) { (t.css.spaceXs + (if (phone) PhoneDrawer.Floor else 1.7f.rem)).roundToPx() }
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
    phone: Boolean = false,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
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
private fun SessionFilter(query: String, onChange: (String) -> Unit, phone: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    if (phone) {
        // W39: 48 tall, full width, radius 12; the funnel glyph (the web's), a 15 sp field, a 2 dp `violet` ring in focus.
        val focused by interaction.collectIsFocusedAsState()
        val fieldShape = RoundedCornerShape(t.radiusMd)
        Row(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .cssSurface(fieldShape, t.mineralDeep, CssBorder(if (focused) 2.dp else 1.dp, if (focused) t.violet else t.line))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallIcon(TetherIcons.Filter, t.faint, 16.dp)
            val fieldStyle = css(type.ui, 0.9375f, 400).copy(color = t.ink)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(t.violet),
                interactionSource = interaction,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhoneDrawer.Floor)
                    .semantics { contentDescription = "Filter sessions" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Filter sessions…", style = fieldStyle.ltrWords(), color = t.faint, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        return
    }
    val shape = RoundedCornerShape(t.radiusSm)
    Row(
        Modifier
            .padding(bottom = if (phone) 0.dp else t.css.spaceXs)
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
                .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
                .semantics { contentDescription = "Filter sessions" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Filter sessions…", style = style.ltrWords(), color = t.faint, maxLines = 1)
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
    menuKey: String?,
    onMenuKey: (String?) -> Unit,
    onArm: (String?) -> Unit,
    swipedSeed: String?,
    dragController: DragController,
    draggingKey: String?,
    openChildren: MutableMap<String, Boolean>,
    chainOpen: MutableMap<String, Boolean>,
    focusChain: String?,
    rowBounds: MutableMap<String, Rect>,
    endBounds: MutableMap<String, Rect>,
    onShowMore: (String) -> Unit,
    onShowLess: (String) -> Unit,
    onToggleOlder: (String) -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else if (phone) 16.dp else 0.75f.rem)
            .testTag(SidebarTags.block(block.workspace))
            .semantics { contentDescription = SafeText.line(block.name) },
        verticalArrangement = Arrangement.spacedBy(if (phone) 0.dp else 0.15f.rem),
    ) {
        BlockHeader(block, actions, offline = !state.connected, phone = phone)
        if (!block.collapsed) {
            Column(
                // W39: on the phone the workspace's sessions sit in ONE rounded card (`graphiteRaised`, radius 12) that
                // clips them; the 4 dp above it is the header's.
                if (phone) Modifier.padding(top = 4.dp).clip(RoundedCornerShape(PhoneLook.CardRadius)).background(t.graphiteRaised)
                else Modifier.padding(start = 0.dp, top = 0.3f.rem, bottom = 0.1f.rem),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                block.rows.forEachIndexed { unit, entry ->
                    // Between top-level units (a row with its delegates / chain is ONE unit): a hairline inset to the title column.
                    if (phone && unit > 0) PhoneDivider()
                    val row: @Composable (SidebarEntry, Boolean, Boolean) -> Unit = { e, draggable, child ->
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
                            menuOpen = menuKey == e.key,
                            onMenuKey = onMenuKey,
                            activeHandover = state.activeHandover,
                            activeSessionId = state.activeSessionId,
                            handoverSuccessor = e.live?.handedOffTo?.let { view.liveSessions[it] },
                            isGroupHead = block.chainSummaries.containsKey(e.key),
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
                            tileSize = if (child) PhoneLook.ChildTile else PhoneLook.Tile,
                        )
                    }
                    row(entry, true, false)
                    val children = block.childrenByKey[entry.key].orEmpty()
                    if (children.isNotEmpty()) {
                        val activeChild = children.any { SidebarViewModel.isActiveEntry(state, it) }
                        val open = activeChild || openChildren[entry.key] == true
                        DelegateToggle(children.size, open, phone) { openChildren[entry.key] = !open }
                        if (open) {
                            Column(
                                Modifier
                                    .padding(start = 1.1f.rem)
                                    .drawBehind { drawRect(t.line, Offset.Zero, androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
                                    .padding(start = 1.dp + 0.2f.rem),
                            ) { children.forEach { child -> row(child, false, true) } }
                        }
                    }
                    // HG (tth-0bs) session-sidebar.tsx:985-1030 — the chain's earlier sessions, behind one toggle.
                    val chain = block.chainSummaries[entry.key]
                    if (chain != null) {
                        val open = chainOpen[chain.rootKey] == true
                        ChainToggle(
                            chain = chain,
                            headName = SafeText.line(LabelText.title(Web.sidebarSessionName(entry.js))),
                            open = open,
                            phone = phone,
                            focusSeed = focusChain == chain.rootKey,
                            tag = SidebarTags.chainToggle(entry.key),
                        ) { chainOpen[chain.rootKey] = !open }
                        // Closed rows are not composed: not visible, not focusable, not in semantics (`hidden`).
                        if (open) {
                            Column(
                                Modifier
                                    .padding(start = 1.1f.rem)
                                    .drawBehind { drawRect(t.line, Offset.Zero, androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
                                    .padding(start = 1.dp + 0.2f.rem),
                            ) {
                                chain.older.forEach { member ->
                                    row(member, false, false)
                                    val kids = block.childrenByKey[member.key].orEmpty()
                                    if (kids.isNotEmpty()) {
                                        val kidOpen = kids.any { SidebarViewModel.isActiveEntry(state, it) } || openChildren[member.key] == true
                                        DelegateToggle(kids.size, kidOpen, phone) { openChildren[member.key] = !kidOpen }
                                        if (kidOpen) {
                                            Column(
                                                Modifier
                                                    .padding(start = 1.1f.rem)
                                                    .drawBehind { drawRect(t.line, Offset.Zero, androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
                                                    .padding(start = 1.dp + 0.2f.rem),
                                            ) { kids.forEach { kid -> row(kid, false, true) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (block.totalRows == 0 && block.hiddenRuns == 0 && state.connected) {
                    val type = LocalTetherTypography.current
                    Text(
                        if (view.filtering) "No sessions match this filter" else "No sessions yet",
                        style = css(type.ui, if (phone) 0.8125f else 0.72f, 400),
                        color = t.faint,
                        modifier = if (phone) Modifier.fillMaxWidth().heightIn(min = PhoneDrawer.Floor).wrapContentHeight(Alignment.CenterVertically).padding(start = 56.dp, end = 12.dp)
                        else Modifier.padding(start = 3.25f.rem, end = t.css.spaceMd, top = t.css.spaceXs, bottom = t.css.spaceSm),
                    )
                }
                if (block.remaining > 0) MoreRow(TetherIcons.ChevronDown, "Show more", count = block.remaining, phone = phone) { onShowMore(block.workspace) }
                if (block.remaining == 0 && block.expanded) MoreRow(TetherIcons.ChevronUp, "Show less", phone = phone) { onShowLess(block.workspace) }
                // session-sidebar.tsx:1193 — "Older" N / "Hide older": idle > 7 days, nothing is archived.
                if (block.olderCount > 0) {
                    MoreRow(
                        if (block.olderOpen) TetherIcons.ChevronUp else TetherIcons.ChevronDown,
                        if (block.olderOpen) "Hide older" else "Older",
                        count = block.olderCount,
                        phone = phone,
                        modifier = Modifier.testTag(SidebarTags.older(block.workspace)),
                        expanded = block.olderOpen,
                        onClick = { onToggleOlder(block.workspace) },
                    )
                }
                if (block.hiddenRuns > 0) {
                    MoreRow(
                        TetherIcons.Eye,
                        "${block.hiddenRuns} background CLI run${if (block.hiddenRuns == 1) "" else "s"}",
                        action = "Show",
                        phone = phone,
                        onClick = actions.onToggleHideAgentRuns,
                    )
                }
                if (block.manualOrder && !view.filtering) ResetOrderRow(phone) { actions.onResetSessionOrder(block.workspace) }
            }
        }
    }
}

/** The card's hairline between two top-level units: 1 dp `--line`, from the title column (x = 56) to the card's end. */
@Composable
private fun PhoneDivider() {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(t.line))
}

/** `.workspace-block-header` (globals.css 3035-3137; studio.css 317-322). */
@Composable
private fun BlockHeader(block: BlockView, actions: SidebarActions, offline: Boolean, phone: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    if (phone) {
        PhoneBlockHeader(block, actions, offline)
        return
    }
    val shape = RoundedCornerShape(t.radiusSm)
    val ink = if (block.isCurrent) t.white else t.muted
    val minHeight = if (phone) PhoneDrawer.Floor else 2.75f.rem
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
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
                .heightIn(min = minHeight)
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
                // The phone's badge centre sits over the cap centre of the rows below it (ta-1jj7).
                .padding(start = if (phone) 12.dp else t.css.spaceSm, end = t.css.spaceSm, top = t.css.spaceXs, bottom = t.css.spaceXs),
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
        HeaderAction(TetherIcons.Plus, 15.dp, "New session in ${SafeText.line(block.name)}", t.faint, phone = phone) { actions.onNewSessionIn(block.workspace) }
        HeaderAction(
            if (block.pinned) FilledStar else TetherIcons.Star,
            14.dp,
            if (block.pinned) "Unpin ${SafeText.line(block.name)}" else "Keep ${SafeText.line(block.name)} in the sidebar",
            if (block.pinned) t.violet else t.faint,
            state = if (block.pinned) "Pinned" else "Not pinned",
            phone = phone,
        ) { actions.onTogglePinnedProject(block.workspace) }
    }
}

/**
 * W39 (ta-v0h8): the phone's workspace header: a plain 48 dp row on the rail, no band. A 32 dp letter tile
 * centred on x = 24, the name 16/700 at x = 56, the activity dot, the chevron (points down open, right
 * collapsed); then the star and the + keys. The current workspace shows in the tile (violet letter + ring).
 */
@Composable
private fun PhoneBlockHeader(block: BlockView, actions: SidebarActions, offline: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val rotation by animateFloatAsState(
        if (block.collapsed) (if (rtl) 90f else -90f) else 0f,
        if (reduced) snap() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()),
        label = "workspaceChevron",
    )
    Row(Modifier.fillMaxWidth().heightIn(min = PhoneDrawer.Floor), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = PhoneDrawer.Floor)
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
                .clickable(role = Role.Button) { actions.onToggleWorkspaceCollapsed(block.workspace) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // .workspace-badge: 32 dp, centred on x = 24 of the 48 dp leading slot (the rows' tiles share the line).
            Box(Modifier.width(PhoneLook.Lead).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                val badgeShape = RoundedCornerShape(t.radiusSm)
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Color(0xFF283650), badgeShape)
                        .then(if (block.isCurrent) Modifier.border(1.dp, t.violetStrong, badgeShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Web.workspaceInitial(block.workspace),
                        style = css(type.ui, 0.875f, 700, lineHeight = 1f),
                        color = if (block.isCurrent) t.violet else Color(0xFFC2D1ED),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // ta-28i: a workspace's folder name and path are code (server text), LTR.
                // S3a (D-L34-5/22): the workspace's open count follows the name, drawn on collapsed headers too; the name ellipsizes first.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(codeLabel(block.name), style = css(type.ui, 1f, 700), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(
                        "${block.openCount}",
                        style = css(type.ui, 0.875f, 500).copy(fontFeatureSettings = "tnum"),
                        color = t.faint,
                        maxLines = 1,
                        softWrap = false,
                        // The web's visually hidden " sessions", agreeing with its count (web d07c853f: "1 session").
                        modifier = Modifier.semantics { contentDescription = "${block.openCount} session${if (block.openCount == 1) "" else "s"}" }.testTag(SidebarTags.workspaceCount(block.workspace)),
                    )
                }
                block.path?.let {
                    Text(codeLabel(it), style = css(type.ui, 0.75f, 400), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            block.activity?.let { a ->
                Spacer(Modifier.width(8.dp))
                if (offline && (a.waiting > 0 || a.active > 0)) {
                    StatusDot(t.faint, size = 0.5f.rem, modifier = Modifier.testTag(SidebarTags.blockDot(block.workspace)))
                } else if (a.waiting > 0) {
                    val wash = t.violetWash
                    WaitingPingDot(
                        t.violet,
                        dotSize = 0.5f.rem,
                        modifier = if (reduced) Modifier.drawBehind { drawCircle(wash, radius = size.minDimension / 2 + 3.dp.toPx()) } else Modifier,
                    )
                } else if (a.active > 0) StatusDot(t.running, size = 0.5f.rem)
            }
            // The keyboard digit hint (the pinned workspaces' 1-9) is not drawn on a touch phone; nothing is bound to it.
            if (!PhoneLook.HideKbdDigits && block.index < 9 && block.pinned) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clearAndSetSemantics { }
                        .widthIn(min = 1.15f.rem)
                        .border(1.dp, t.line, RoundedCornerShape(0.25f.rem))
                        .padding(horizontal = 0.3f.rem, vertical = 0.05f.rem),
                    contentAlignment = Alignment.Center,
                ) { Text("${block.index + 1}", style = css(type.mono, 0.6f, 400), color = t.faint) }
            }
            SmallIcon(TetherIcons.ChevronDown, t.faint, 16.dp, Modifier.padding(horizontal = 8.dp).rotate(rotation))
        }
        HeaderAction(
            if (block.pinned) FilledStar else TetherIcons.Star,
            18.dp,
            if (block.pinned) "Unpin ${SafeText.line(block.name)}" else "Keep ${SafeText.line(block.name)} in the sidebar",
            if (block.pinned) t.violet else t.faint,
            state = if (block.pinned) "Pinned" else "Not pinned",
            phone = true,
        ) { actions.onTogglePinnedProject(block.workspace) }
        HeaderAction(TetherIcons.Plus, 20.dp, "New session in ${SafeText.line(block.name)}", t.faint, phone = true) { actions.onNewSessionIn(block.workspace) }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    description: String,
    tint: Color,
    state: String? = null,
    phone: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(if (phone) PhoneDrawer.Floor else 2.25f.rem)
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
            .semantics {
                contentDescription = description
                state?.let { stateDescription = it }
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SmallIcon(icon, tint, size) }
}

/**
 * `.session-chain-toggle` (globals.css 2606-2640; session-sidebar.tsx:992-1009): "N earlier sessions" under a handover
 * chain's head, a top-aligned slim row (chevron, label, "· 2 needs you" suffix while closed). The title is a long-press
 * tooltip. A tap, Enter or Space flips [open]; nothing moves focus. The ring is inset and shows in keyboard mode only.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChainToggle(chain: ChainView, headName: String, open: Boolean, phone: Boolean, focusSeed: Boolean, tag: String, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val input = androidx.compose.ui.platform.LocalInputModeManager.current
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (focusSeed) {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            runCatching { focusRequester.requestFocus() }
        }
    }
    val keyboardMode = input.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    val ink = if (phone) t.muted else if (hovered || focused) t.muted else t.faint
    val shape = RoundedCornerShape(t.radiusSm)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val elbow = t.lineStrong
    // The rail's chevron points down and flips to up; the phone's (W39) points along the text and turns down when open.
    val rotation by animateFloatAsState(
        if (phone) (if (open) 90f else if (rtl) 180f else 0f) else if (open) 180f else 0f,
        if (reduced) snap() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()),
        label = "chainChevron",
    )
    val showSuffix = !open && chain.suffix != null
    val suffixInk = when (chain.suffix?.kind) {
        SidebarOrder.ChainSuffixKind.Waiting -> t.violet
        SidebarOrder.ChainSuffixKind.Active -> t.running
        else -> ink
    }
    val name = "${chain.label} of $headName" + if (showSuffix) ", ${chain.suffixText}" else ""
    val style = css(type.ui, 0.72f, 400, lineHeight = 1.3889f).ltrWords()
    // The anchor IS the toggle: the tooltip's long-press semantics and the button's click/role/state/name live on ONE node
    // (an inner clickable under the anchor Box left a separate non-clickable anchor node on device, ta-btv8).
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Earlier sessions in this group, newest first") } },
        state = rememberTooltipState(),
        modifier = (if (phone) Modifier else Modifier.padding(start = 0.9f.rem))
            .fillMaxWidth()
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
            .then(
                // W39: the └ elbow, from the head tile's bottom centre (x = 24) down to the toggle's mid-height, then right to x = 36.
                if (phone) Modifier.drawBehind {
                    val flip = layoutDirection == LayoutDirection.Rtl
                    fun x(v: Float) = if (flip) size.width - v.dp.toPx() else v.dp.toPx()
                    val mid = size.height / 2
                    val r = 6.dp.toPx()
                    val path = Path().apply {
                        moveTo(x(24f), 0f)
                        lineTo(x(24f), mid - r)
                        quadraticTo(x(24f), mid, if (flip) x(24f) - r else x(24f) + r, mid)
                        lineTo(x(36f), mid)
                    }
                    drawPath(path, elbow, style = Stroke(1.dp.toPx()))
                } else Modifier,
            )
            .focusRing(focused && keyboardMode, shape, t.violet, offset = (-2).dp)
            .clip(shape)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
            .then(
                if (phone) Modifier.padding(start = 38.dp, end = 12.dp)
                else Modifier.padding(start = t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceXs),
            )
            // Compose only gives a node its Button class/clickable on device when it has no exposed children; the label
            // Text kept showing up as a child of a merged node, so the node states its own semantics and clears the rest.
            .clearAndSetSemantics {
                testTag = tag
                contentDescription = name
                stateDescription = if (open) "Expanded" else "Collapsed"
                role = Role.Button
                onClick { onToggle(); true }
            },
    ) {
        if (phone) {
            // W39: chevron 16 centred at x = 46, the label from x = 56 (13/500 `muted`), the suffix as before.
            val phoneStyle = css(type.ui, 0.8125f, 500, lineHeight = 1.3846f).ltrWords()
            Box(Modifier.fillMaxWidth().heightIn(min = PhoneDrawer.Floor), contentAlignment = Alignment.CenterStart) {
                FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically) {
                    SmallIcon(TetherIcons.ChevronRight, t.faint, 16.dp, Modifier.padding(end = 2.dp).rotate(rotation))
                    Text(chain.label, style = phoneStyle, color = ink, maxLines = 1)
                    if (showSuffix) Text("· ${chain.suffixText}", style = phoneStyle, color = suffixInk, modifier = Modifier.padding(start = 6.dp))
                }
            }
            return@TooltipBox
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            SmallIcon(TetherIcons.ChevronDown, ink, 12.dp, Modifier.padding(top = 0.125f.rem).rotate(rotation))
            Text(chain.label, style = style, color = ink, maxLines = 1)
            if (showSuffix) Text("· ${chain.suffixText}", style = style, color = suffixInk)
        }
    }
}

/** `.session-children-toggle` (globals.css 3185-3217): "N delegate(s)", collapsed by default. */
@Composable
private fun DelegateToggle(count: Int, open: Boolean, phone: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .padding(start = 0.9f.rem)
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
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
    phone: Boolean = false,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
            .semantics(mergeDescendants = true) { if (expanded != null) stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (phone) 0.dp else 0.9f.rem, end = if (phone) 12.dp else t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else t.css.spaceSm),
    ) {
        // W39: on the phone the glyph is centred on x = 24 and the label starts at x = 56.
        if (phone) Box(Modifier.width(PhoneLook.Lead), contentAlignment = Alignment.Center) { SmallIcon(icon, t.faint, 16.dp) } else SmallIcon(icon, t.faint, 14.dp)
        if (phone) Spacer(Modifier.width(8.dp))
        Text(text, style = css(type.ui, if (phone) 0.8125f else 0.74f, if (phone) 500 else 400), color = t.faint, modifier = Modifier.weight(1f))
        count?.let { CountPill(it, t.line, 0.62f) }
        action?.let { Text(it, style = css(type.ui, 0.74f, 680), color = t.muted) }
    }
}

/** `.session-order-reset` (globals.css 1105-1124). */
@Composable
private fun ResetOrderRow(phone: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(bottom = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (phone) Arrangement.Start else Arrangement.spacedBy(t.css.spaceXs, Alignment.CenterHorizontally),
    ) {
        if (phone) {
            Box(Modifier.width(PhoneLook.Lead), contentAlignment = Alignment.Center) { SmallIcon(TetherIcons.RotateCcw, t.faint, 16.dp) }
            Spacer(Modifier.width(8.dp))
            Text("Reset to default order", style = css(type.ui, 0.8125f, 500), color = t.faint)
        } else {
            SmallIcon(TetherIcons.RotateCcw, t.faint, 13.dp)
            Text("Reset to default order", style = css(type.ui, 0.7f, 400), color = t.faint)
        }
    }
}

/** `.workspace-add-row` (globals.css 3232-3252; studio.css 343-344). */
@Composable
private fun AddWorkspaceRow(onClick: () -> Unit, phone: Boolean) =
    SidebarAddRow(TetherIcons.FolderPlus, 15.dp, "Add workspace", SidebarTags.AddWorkspace, phone, onClick = onClick)

/** A `.workspace-add-row` key: the Add workspace row and the Archive idle sessions row share it. */
@Composable
private fun SidebarAddRow(icon: androidx.compose.ui.graphics.vector.ImageVector, iconSize: androidx.compose.ui.unit.Dp, label: String, tag: String, phone: Boolean, topGap: androidx.compose.ui.unit.Dp? = null, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val line = t.line
    Row(
        Modifier
            .padding(top = if (phone) topGap ?: 0.dp else t.css.spaceSm)
            .fillMaxWidth()
            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
            .then(
                Modifier,
            )
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (phone) 0.dp else 0.8f.rem, end = t.css.spaceSm)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else t.css.spaceSm),
    ) {
        val ink = t.muted
        if (phone) {
            // W39: glyph 18 centred on x = 24, label 13/500 `muted` at x = 56, no divider.
            Box(Modifier.width(PhoneLook.Lead), contentAlignment = Alignment.Center) { SmallIcon(icon, t.faint, 18.dp) }
            Spacer(Modifier.width(8.dp))
            Text(label, style = css(type.ui, 0.8125f, 500).ltrWords(), color = ink)
        } else {
            SmallIcon(icon, ink, iconSize)
            Text(label, style = css(type.ui, 0.75f, 600).ltrWords(), color = ink)
        }
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
    phone: Boolean = false,
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
                .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = if (phone) 0.dp else t.css.spaceMd, vertical = if (phone) 0.dp else t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else 0.35f.rem),
        ) {
            if (phone) {
                // W39: Archive 18 on x = 24, "Archived" 14/600 `ink` at x = 56, the count, and a chevron at the end.
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Box(Modifier.width(PhoneLook.Lead), contentAlignment = Alignment.Center) { SmallIcon(TetherIcons.Archive, t.muted, 18.dp) }
                Spacer(Modifier.width(8.dp))
                Text("Archived", style = css(type.ui, 0.875f, 600), color = t.ink)
                Text("${rows.size}", style = css(type.ui, 0.8125f, 500), color = t.faint, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                SmallIcon(TetherIcons.ChevronRight, t.faint, 16.dp, Modifier.padding(end = 12.dp).rotate(if (open) 90f else if (rtl) 180f else 0f))
            } else {
                SmallIcon(TetherIcons.Archive, t.faint, 12.dp)
                Text("Archived", style = css(type.ui, 0.68f, 400), color = t.faint)
                Text("${rows.size}", style = css(type.ui, 0.68f * 0.833f, 400), color = t.faint)
            }
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
                            .heightIn(min = if (phone) PhoneDrawer.Floor else 2.75f.rem)
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
                                "Ended ${Format.relativeTimeAgo(jsUpdatedAt(entry), now.toDouble())}",
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
private fun SidebarFooter(phone: Boolean, onOpenSettings: () -> Unit, onCollapse: (() -> Unit)?, phoneScheduled: @Composable () -> Unit = {}) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    if (phone) {
        // W39: a quiet two-destination bar, 56 tall: Settings (start), Scheduled actions (end). "Private runtime" is the header's.
        androidx.compose.foundation.layout.FlowRow(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(line, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                .heightIn(min = PhoneLook.RowMinHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FooterButton(TetherIcons.Settings, "Open settings", label = "Settings", phone = true, onClick = onOpenSettings)
            phoneScheduled()
        }
        return
    }
    // `.sidebar-footer` (globals.css 1065): flex, space-between; each side `0 1 auto`, so at a large text
    // size the status span shrinks to its words and never splits one (ta-z4c1).
    CssFlexRow(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(line, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, px))
            }
            .padding(top = if (phone) 0.dp else 0.75f.rem, start = t.css.spaceXs, end = t.css.spaceXs),
        justify = FlexJustify.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Collapse is desktop-only: below 48rem the drawer has its own close.
            if (!phone && onCollapse != null) {
                FooterButton(TetherIcons.PanelLeftClose, "Collapse sidebar", phone = false, onClick = onCollapse)
            }
            FooterButton(TetherIcons.Settings, "Open settings", label = "Settings", phone = phone, onClick = onOpenSettings)
        }
        CssFlexRow(gap = t.css.spaceSm) {
            StatusDot(t.running, size = 0.4f.rem, modifier = Modifier.flexFloor(0.dp))
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
private fun FooterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, label: String? = null, phone: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val floor = if (phone) PhoneDrawer.Floor else 2.75f.rem
    Row(
        Modifier
            .heightIn(min = floor)
            .widthIn(min = floor)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (phone) 8.dp else if (label != null) 0.25f.rem else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 0.5f.rem, Alignment.CenterHorizontally),
    ) {
        SmallIcon(icon, t.muted, if (phone) 20.dp else 17.dp)
        label?.let { Text(it, style = css(type.ui, if (phone) 0.8125f else 0.75f, if (phone) 600 else 500), color = t.muted, modifier = Modifier.clearAndSetSemantics { }) }
    }
}

/** Keeps the lazy list's scroll API in one place for the drag controller. */
internal suspend fun LazyListState.scrollBy(px: Float) {
    scroll { scrollBy(px) }
}
