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
import androidx.compose.foundation.layout.offset
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
    /** Vector m02: the merged search bar starts in the Filter scope (true) or the All scope (false); null = Filter when a query is set, else All. */
    val searchFilterScope: Boolean? = null,
)

object SidebarTags {
    const val Root = "sidebar"
    const val List = "sidebar-list"
    const val NewSession = "sidebar-new-session"
    /** Vector m02: a top-level session's card, and the current one's. */
    const val Card = "sidebar-card"
    const val CurrentCard = "sidebar-card-current"
    const val AddWorkspace = "sidebar-add-workspace"
    /** T9.3: the rail's Scheduled actions key. */
    const val Scheduled = "sidebar-scheduled"
    fun row(key: String) = "sidebar-row:$key"
    fun freshness(key: String) = "sidebar-freshness:$key"
    fun blockDot(workspace: String) = "sidebar-block-dot:$workspace"
    const val RailChevron = "sidebar-rail-chevron"
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
 * so the panel's edges cannot drift between the app and its pictures. W53: the tablet rail reads [Floor], [Cap] and the row numbers too (one list); the phone-only parts are the swipe, the header and the footer bar.
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
 * old look back (one line each). W53: the list-body numbers here are read by the tablet rail as well (one list in the app); the filter field is always drawn on both.
 */
object PhoneLook {
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
    // W53: one list in the app: the tablet rail draws the phone's list body (cards, rows, floors); [phone] alone is the drawer (swipe, header, footer bar).
    val compact = true
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
    // Vector m02: the merged search bar's scope (web session-sidebar.tsx searchScope): All = the field opens the global search.
    var searchAll by remember { mutableStateOf(!(seed.searchFilterScope ?: state.query.isNotEmpty())) }
    val openChildren = remember { mutableStateMapOf<String, Boolean>().apply { seed.openChildren.forEach { put(it, true) } } }
    // HG (tth-0bs) session-sidebar.tsx:925-943 — open/closed and the last reason set, per chain ROOT key.
    val chainOpen = remember { mutableStateMapOf<String, Boolean>().apply { seed.openChains.forEach { put(it, true) } } }
    val chainSeen = remember { mutableStateMapOf<String, List<String>>().apply { putAll(seed.seenChains) } }
    val rowBounds = remember { HashMap<String, Rect>() }
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
            .testTag(SidebarTags.Root)
            .then(if (phone) Modifier.padding(horizontal = PhoneLook.InnerGutter) else Modifier),
    ) {
        // Vector m02: the merged search bar, then the quick-filter chips (labelled, Fira Code) under it.
        val phoneSearch: @Composable () -> Unit = {
            PhoneSearchBar(
                query = state.query,
                allScope = searchAll,
                workspaceCount = state.workspaces.size,
                onQuery = actions.onSessionQueryChange,
                onOpenGlobal = actions.onOpenGlobalSearch,
                onScope = { searchAll = !searchAll },
            )
        }
        val phoneFilters: @Composable () -> Unit = {
            PhoneChipRow(
                state, actions,
                harnessOpen, { harnessOpen = it; if (it) sortOpen = false },
                sortOpen, { sortOpen = it; if (it) harnessOpen = false },
            )
        }
        if (phone) {
            // m02: "+ New session" and "Scheduled" keys and Close share the header; "Private runtime" is the footer's.
            PhoneHeader(
                scheduledCount = state.scheduledActionCount,
                scheduledActive = state.scheduledActionsActive,
                onNewSession = actions.onNewSession,
                onScheduled = actions.onOpenScheduledActions,
                onClose = actions.onCloseDrawer,
            )
            PhoneRule()
            if (!headerScrolls) {
                phoneSearch()
                phoneFilters()
                Spacer(Modifier.height(8.dp))
            }
        } else {
            // W53: the web's SB rail chrome (RailChrome.kt).
            RailChrome(
                state = state,
                actions = actions,
                openCount = view.openCount,
                harnessOpen = harnessOpen,
                onHarnessOpen = { harnessOpen = it; if (it) sortOpen = false },
                sortOpen = sortOpen,
                onSortOpen = { sortOpen = it; if (it) harnessOpen = false },
            )
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
                item(key = "hdr-filter") { phoneSearch() }
                item(key = "hdr-chips") { phoneFilters() }
            }
            view.blocks.forEachIndexed { position, block ->
                item(key = "block:${block.workspace}") {
                    WorkspaceBlock(
                        block = block,
                        state = state,
                        view = view,
                        actions = actions,
                        phone = phone,
                        compact = compact,
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
                SidebarAddRow(TetherIcons.Archive, 14.dp, ArchiveStaleCopy.ENTRY, ArchiveStaleTags.Entry, compact, topGap = 16.dp) {
                    val step = ArchiveStaleModel.open(archiveState)
                    archiveState = step.state
                    archiveOrigin = latestOrigin
                    actions.onClearArchiveStale()
                    archiveOpen = true
                    step.request?.let { actions.onArchiveStale(it.mode, it.days, latestState.activeSessionId, latestOrigin) }
                }
            }
            item(key = "add-workspace") { AddWorkspaceRow(actions.onBrowseWorkspace, compact) }
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
                compact = compact,
            )
        }
        SidebarFooter(phone = phone, connected = state.connected, onOpenSettings = actions.onOpenSettings, onCollapse = actions.onCollapse)
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

/** The phone drawer's rule between its controls and the list: 8 dp of air, then a full-width 1 dp `--line`. */
@Composable
private fun PhoneRule() {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

@Composable
internal fun CountPill(count: Int, border: Color, rem: Float, ink: Color = LocalTetherTokens.current.faint) {
    val type = LocalTetherTypography.current
    Box(
        Modifier.widthIn(min = 1.4f.rem).heightIn(min = 1.4f.rem).border(1.dp, border, RoundedCornerShape(999.dp)).padding(horizontal = 0.3f.rem),
        contentAlignment = Alignment.Center,
    ) { Text("$count", style = css(type.mono, rem, 650, lineHeight = 1f), color = ink) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FilterBank(
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
    }
    val activeKey: @Composable () -> Unit = {
        val stateText = if (state.activeOnly) "Showing sessions with work in progress" else "Show sessions with work in progress"
        BankKey(TetherIcons.Activity, state.activeOnly, "Active", phone, switch = true, stateText = stateText, onClick = actions.onToggleActiveOnly)
    }
    val unreadKey: @Composable () -> Unit = {
        val stateText = if (state.unreadOnly) "Showing sessions with unread reports" else "Show sessions with unread reports"
        BankKey(TetherIcons.Mail, state.unreadOnly, "Unread", phone, switch = true, stateText = stateText, onClick = actions.onToggleUnreadOnly)
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
                SidebarMenu(onDismiss = { onSortOpen(false) }, alignStart = false, label = "Sort sessions") {
                    MenuItem("Created Date", checked = state.sort == SidebarSort.Created, trailingCheck = true) { actions.onSortModeChange(SidebarSort.Created); onSortOpen(false) }
                    MenuItem("Last Active", checked = state.sort == SidebarSort.LastActive, trailingCheck = true) { actions.onSortModeChange(SidebarSort.LastActive); onSortOpen(false) }
                }
            }
        }
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
    // W53: the tablet rail's keys are the web's coarse toggles at the app's floor: 48 x 48, radius 6, glyph 15 (studio.css:1285-1287).
    val w = PhoneDrawer.Floor
    val h = PhoneDrawer.Floor
    val shape = RoundedCornerShape(6.dp)
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
    ) { SmallIcon(icon, if (on) t.violet else t.muted, 15.dp) }
}

/** `.sidebar-harness-menu` / `.sidebar-sort-menu` (globals.css 988-1042). */
@Composable
internal fun SidebarMenu(onDismiss: () -> Unit, alignStart: Boolean, label: String, compact: Boolean = true, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    // The phone's 48 dp keys put the menu a full key below the anchor's top (ta-1jj7).
    val gap = with(density) { (t.css.spaceXs + (if (compact) PhoneDrawer.Floor else 1.7f.rem)).roundToPx() }
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
internal fun MenuItem(
    text: String,
    checked: Boolean,
    trailingCheck: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    compact: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
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

// ── Blocks ──────────────────────────────────────────────────────────────────────

@Composable
private fun WorkspaceBlock(
    block: BlockView,
    state: SidebarState,
    view: SidebarView,
    actions: SidebarActions,
    phone: Boolean,
    compact: Boolean,
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
    onShowMore: (String) -> Unit,
    onShowLess: (String) -> Unit,
    onToggleOlder: (String) -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first || compact) 0.dp else 0.75f.rem)
            .testTag(SidebarTags.block(block.workspace))
            .semantics { contentDescription = SafeText.line(block.name) },
        verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 0.15f.rem),
    ) {
        // m02 (P8C-FIX1 B1): on the phone a 1 dp `--line` hairline at the card inset width stands between blocks (none above the first), in place of the 16 dp gap.
        if (compact && !first) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line).testTag(PhoneChromeTags.BlockRule))
        BlockHeader(block, actions, offline = !state.connected, compact = compact, rail = !phone)
        if (!block.collapsed) {
            Column(
                // Vector m02: each top-level session (with its delegates / chain) is its own row CARD; 6 dp between cards.
                if (compact) Modifier.padding(top = 4.dp) else Modifier.padding(start = 0.dp, top = 0.3f.rem, bottom = 0.1f.rem),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 0.dp),
            ) {
                block.rows.forEachIndexed { unit, entry ->
                    // A row with its delegates / chain is ONE unit = one card; the card is current when any row in it is.
                    val unitChain = block.chainSummaries[entry.key]
                    val unitActive = SidebarViewModel.isActiveEntry(state, entry) ||
                        block.childrenByKey[entry.key].orEmpty().any { SidebarViewModel.isActiveEntry(state, it) } ||
                        unitChain?.older?.any { SidebarViewModel.isActiveEntry(state, it) } == true
                    UnitCard(unitActive) {
                    val row: @Composable (SidebarEntry, Boolean, SessionVariant, String?) -> Unit = { e, draggable, variant, headProvider ->
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
                            actions = actions,
                            variant = variant,
                            headProvider = headProvider,
                        )
                    }
                    val children = block.childrenByKey[entry.key].orEmpty()
                    val chain = block.chainSummaries[entry.key]
                    val chainIsOpen = chain != null && chainOpen[chain.rootKey] == true
                    // ta-hkk7.5: the toggle directly under the head row is grouped with it (the spine starts at the tile); a chain toggle
                    // that follows a delegate block keeps the web's constant 14 dp into that block.
                    val chainToggle: @Composable () -> Unit = {
                        if (chain != null) {
                            ChainToggle(
                                chain = chain,
                                headName = SafeText.line(LabelText.title(Web.sidebarSessionName(entry.js))),
                                open = chainIsOpen,
                                compact = compact,
                                focusSeed = focusChain == chain.rootKey,
                                tag = SidebarTags.chainToggle(entry.key),
                                spineOverhang = if (children.isNotEmpty()) (PhoneLook.RowMinHeight - PhoneLook.Tile) / 2 else 0.dp,
                            ) { chainOpen[chain.rootKey] = !chainIsOpen }
                        }
                    }
                    if (children.isNotEmpty()) {
                        val activeChild = children.any { SidebarViewModel.isActiveEntry(state, it) }
                        val open = activeChild || openChildren[entry.key] == true
                        HeadSpineGroup(compact, { row(entry, true, SessionVariant.Top, null) }) { DelegateToggle(children.size, open, compact) { openChildren[entry.key] = !open } }
                        if (open) {
                            NestedRows(compact) {
                                children.forEachIndexed { i, child -> NestedItem(compact, i == children.lastIndex) { row(child, false, SessionVariant.Delegate, null) } }
                            }
                        }
                        chainToggle()
                    } else if (chain != null) {
                        HeadSpineGroup(compact, { row(entry, true, SessionVariant.Top, null) }, chainToggle)
                    } else {
                        row(entry, true, SessionVariant.Top, null)
                    }
                    // HG (tth-0bs) session-sidebar.tsx:985-1030 — the chain's earlier sessions, behind one toggle (above).
                    if (chain != null) {
                        // Closed rows are not composed: not visible, not focusable, not in semantics (`hidden`).
                        if (chainIsOpen) {
                            // web tsx:1098: the head's provider, live first.
                            val headProvider = entry.live?.provider ?: entry.history?.provider
                            NestedRows(compact) {
                                chain.older.forEachIndexed { mi, member ->
                                    val kids = block.childrenByKey[member.key].orEmpty()
                                    // web: the member's delegates are its following siblings, so a member with delegates is not the last child.
                                    val memberRow: @Composable () -> Unit = { NestedItem(compact, mi == chain.older.lastIndex && kids.isEmpty()) { row(member, false, SessionVariant.Member, headProvider) } }
                                    if (kids.isEmpty()) memberRow()
                                    else {
                                        val kidOpen = kids.any { SidebarViewModel.isActiveEntry(state, it) } || openChildren[member.key] == true
                                        HeadSpineGroup(compact, memberRow) { DelegateToggle(kids.size, kidOpen, compact) { openChildren[member.key] = !kidOpen } }
                                        if (kidOpen) {
                                            NestedRows(compact) {
                                                kids.forEachIndexed { i, kid -> NestedItem(compact, i == kids.lastIndex) { row(kid, false, SessionVariant.Delegate, null) } }
                                            }
                                        }
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
                        style = css(type.ui, if (compact) 0.8125f else 0.72f, 400),
                        color = t.faint,
                        modifier = if (compact) Modifier.fillMaxWidth().heightIn(min = PhoneDrawer.Floor).wrapContentHeight(Alignment.CenterVertically).padding(start = 56.dp, end = 12.dp)
                        else Modifier.padding(start = 3.25f.rem, end = t.css.spaceMd, top = t.css.spaceXs, bottom = t.css.spaceSm),
                    )
                }
                if (block.remaining > 0) MoreRow(TetherIcons.ChevronDown, "Show more", count = block.remaining, compact = compact) { onShowMore(block.workspace) }
                if (block.remaining == 0 && block.expanded) MoreRow(TetherIcons.ChevronUp, "Show less", compact = compact) { onShowLess(block.workspace) }
                // Vector m02: ONE row, "› Older (N) · M background CLI runs  Show": each part keeps its own action
                // (the Older part toggles the band, "Show" shows the hidden agent runs).
                if (block.olderCount > 0 || block.hiddenRuns > 0) {
                    OlderRunsRow(
                        olderCount = block.olderCount,
                        olderOpen = block.olderOpen,
                        hiddenRuns = block.hiddenRuns,
                        olderTag = SidebarTags.older(block.workspace),
                        onToggleOlder = { onToggleOlder(block.workspace) },
                        onShowRuns = actions.onToggleHideAgentRuns,
                    )
                }
                if (block.manualOrder && !view.filtering) ResetOrderRow(compact) { actions.onResetSessionOrder(block.workspace) }
            }
        }
    }
}

/**
 * Vector m02: a top-level session's CARD: one face (`--bg-panel`), radius 12, a 1 dp `--border` hairline; the current one carries a 1 dp
 * accent outline instead (no start bar). The accent clears 3:1 against the drawer's own face in both lightings (UnitCardContrastTest).
 */
@Composable
private fun UnitCard(current: Boolean, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(PhoneLook.CardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.css.bgPanel, shape)
            .border(1.dp, if (current) t.violet else t.line, shape)
            .testTag(if (current) SidebarTags.CurrentCard else SidebarTags.Card),
    ) { content() }
}

/**
 * Vector m02: the merged "› Older (57) · 245 background CLI runs  Show" row. Two targets, each its own state: the Older part
 * (chevron + "Older (N)") toggles the band's expansion; the runs part (words + "Show") shows the hidden agent runs. With only one
 * of the two present the row is that part alone; an Older-only row ends with the "Show" / "Hide" word of its own action.
 */
@Composable
private fun OlderRunsRow(
    olderCount: Int,
    olderOpen: Boolean,
    hiddenRuns: Int,
    olderTag: String,
    onToggleOlder: () -> Unit,
    onShowRuns: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = css(type.ui, 0.8125f, 500, lineHeight = 1.4f)
    val both = olderCount > 0 && hiddenRuns > 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (olderCount > 0) {
            Row(
                Modifier
                    .then(if (both) Modifier else Modifier.weight(1f))
                    .heightIn(min = PhoneDrawer.Floor)
                    .semantics(mergeDescendants = true) { stateDescription = if (olderOpen) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClick = onToggleOlder)
                    .padding(end = 4.dp)
                    .testTag(olderTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SmallIcon(if (olderOpen) TetherIcons.ChevronDown else TetherIcons.ChevronRight, t.faint, 14.dp)
                Text(if (olderOpen) "Hide older ($olderCount)" else "Older ($olderCount)", style = style, color = t.muted, maxLines = 1, softWrap = false)
                if (!both) {
                    Spacer(Modifier.weight(1f))
                    Text(if (olderOpen) "Hide" else "Show", style = css(type.ui, 0.875f, 700), color = t.violet, maxLines = 1)
                }
            }
        }
        if (hiddenRuns > 0) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = PhoneDrawer.Floor)
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable(role = Role.Button, onClick = onShowRuns)
                    .padding(start = if (both) 0.dp else 6.dp)
                    .testTag(PhoneChromeTags.HiddenRuns),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    (if (both) "· " else "") + "$hiddenRuns background CLI run${if (hiddenRuns == 1) "" else "s"}",
                    style = style, color = t.muted, maxLines = 2, modifier = Modifier.weight(1f),
                )
                Text("Show", style = css(type.ui, 0.875f, 700), color = t.violet, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** `.workspace-block-header` (globals.css 3035-3137; studio.css 317-322). */
@Composable
private fun BlockHeader(block: BlockView, actions: SidebarActions, offline: Boolean, compact: Boolean, rail: Boolean) {
    PhoneBlockHeader(block, actions, offline, rail)
}

/**
 * W39 (ta-v0h8): the phone's workspace header: a plain 48 dp row on the rail, no band. A 32 dp letter tile
 * centred on x = 24, the name 16/700 at x = 56, the activity dot, the chevron (points down open, right
 * collapsed); then the star and the + keys. The current workspace shows in the tile (violet letter + ring).
 */
@Composable
private fun PhoneBlockHeader(block: BlockView, actions: SidebarActions, offline: Boolean, rail: Boolean) {
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
            // W53b: the tablet rail orders the header as the web does (session-sidebar.tsx workspace-block-toggle: chevron, then
            // .workspace-badge, then the name; studio.css:1318): the chevron FIRST, no trailing chevron column, no 48 dp lead slot.
            if (rail) SmallIcon(TetherIcons.ChevronDown, t.faint, 16.dp, Modifier.padding(start = 2.dp, end = 1.dp).rotate(rotation).testTag(SidebarTags.RailChevron))
            // .workspace-badge: 32 dp, centred on x = 24 of the 48 dp leading slot (the rows' tiles share the line); 24 dp on the rail (the web's badge is 22).
            Box(if (rail) Modifier.clearAndSetSemantics { } else Modifier.width(PhoneLook.Lead).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                val badgeShape = RoundedCornerShape(t.radiusSm)
                Box(
                    Modifier
                        .size(if (rail) 24.dp else 32.dp)
                        .background(t.css.bgPanel, badgeShape)
                        .border(1.dp, if (block.isCurrent) t.violetStrong else t.css.border, badgeShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Web.workspaceInitial(block.workspace),
                        style = css(type.ui, 0.875f, 700, lineHeight = 1f),
                        color = if (block.isCurrent) t.violet else t.css.inkStrong,
                    )
                }
                // W53b: on the rail the activity dot rides the tile's corner (out of the flow: the name keeps the width).
                if (rail) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp)) { BlockActivityDot(block, offline, withGap = false) }
            }
            Spacer(Modifier.width(if (rail) 4.dp else 8.dp))
            Column(Modifier.weight(1f)) {
                // ta-28i: a workspace's folder name and path are code (server text), LTR.
                // S3a (D-L34-5/22): the workspace's open count follows the name, drawn on collapsed headers too; the name ellipsizes first.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (rail) 3.dp else 6.dp)) {
                    Text(codeLabel(block.name), style = css(type.ui, 1f, 700), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(
                        "${block.openCount}",
                        // Vector m02: the count is Fira Code.
                        style = css(type.mono, 0.8125f, 500),
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
            if (!rail) BlockActivityDot(block, offline, withGap = true)
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
            // Vector m02: the phone header draws no chevron (the whole header still folds the block; its state is in its description).
        }
        HeaderAction(
            if (block.pinned) FilledStar else TetherIcons.Star,
            18.dp,
            if (block.pinned) "Unpin ${SafeText.line(block.name)}" else "Keep ${SafeText.line(block.name)} in the sidebar",
            if (block.pinned) t.violet else t.muted,
            state = if (block.pinned) "Pinned" else "Not pinned",
            compact = true,
        ) { actions.onTogglePinnedProject(block.workspace) }
        HeaderAction(TetherIcons.Plus, 20.dp, "New session in ${SafeText.line(block.name)}", t.violet, compact = true) { actions.onNewSessionIn(block.workspace) }
    }
}

/** The workspace's activity dot: waiting (violet, pinging), active (running) or, from a saved list, a faint one. */
@Composable
private fun BlockActivityDot(block: BlockView, offline: Boolean, withGap: Boolean) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val a = block.activity ?: return
    if (withGap) Spacer(Modifier.width(8.dp))
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

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    description: String,
    tint: Color,
    state: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(if (compact) PhoneDrawer.Floor else 2.25f.rem)
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
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
private fun ChainToggle(chain: ChainView, headName: String, open: Boolean, compact: Boolean, focusSeed: Boolean, tag: String, spineOverhang: androidx.compose.ui.unit.Dp = 0.dp, onToggle: () -> Unit) {
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
    val ink = if (compact) t.muted else if (hovered || focused) t.muted else t.faint
    val shape = RoundedCornerShape(t.radiusSm)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val elbow = t.lineStrong
    // The rail's chevron points down and flips to up; the phone's (W39) points along the text and turns down when open.
    val rotation by animateFloatAsState(
        if (compact) (if (open) 90f else if (rtl) 180f else 0f) else if (open) 180f else 0f,
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
        modifier = (if (compact) Modifier else Modifier.padding(start = 0.9f.rem))
            .fillMaxWidth()
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
            .then(
                // W39: CLOSED the └ from the head tile's bottom centre (x = 24) into the toggle's mid-height; OPEN the spine runs straight on (web `bottom:0`).
                if (compact) Modifier.headSpine(open, elbow, spineOverhang) else Modifier,
            )
            .focusRing(focused && keyboardMode, shape, t.violet, offset = (-2).dp)
            .clip(shape)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
            .then(
                if (compact) Modifier.padding(start = 38.dp, end = 12.dp)
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
        if (compact) {
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
private fun DelegateToggle(count: Int, open: Boolean, compact: Boolean, onToggle: () -> Unit) {
    if (compact) {
        PhoneDelegateToggle(count, open, onToggle)
        return
    }
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .padding(start = 0.9f.rem)
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
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
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
            .semantics(mergeDescendants = true) { if (expanded != null) stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (compact) 0.dp else 0.9f.rem, end = if (compact) 12.dp else t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else t.css.spaceSm),
    ) {
        // W39: on the phone the glyph is centred on x = 24 and the label starts at x = 56.
        if (compact) Box(Modifier.width(PhoneLook.Lead), contentAlignment = Alignment.Center) { SmallIcon(icon, t.faint, 16.dp) } else SmallIcon(icon, t.faint, 14.dp)
        if (compact) Spacer(Modifier.width(8.dp))
        Text(text, style = css(type.ui, if (compact) 0.8125f else 0.74f, if (compact) 500 else 400), color = t.faint, modifier = Modifier.weight(1f))
        count?.let { CountPill(it, t.line, 0.62f) }
        action?.let { Text(it, style = css(type.ui, 0.74f, 680), color = t.muted) }
    }
}

/** `.session-order-reset` (globals.css 1105-1124). */
@Composable
private fun ResetOrderRow(compact: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(bottom = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Start else Arrangement.spacedBy(t.css.spaceXs, Alignment.CenterHorizontally),
    ) {
        if (compact) {
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
private fun AddWorkspaceRow(onClick: () -> Unit, compact: Boolean) =
    SidebarAddRow(TetherIcons.FolderPlus, 15.dp, "Add workspace", SidebarTags.AddWorkspace, compact, onClick = onClick)

/** A `.workspace-add-row` key: the Add workspace row and the Archive idle sessions row share it. */
@Composable
private fun SidebarAddRow(icon: androidx.compose.ui.graphics.vector.ImageVector, iconSize: androidx.compose.ui.unit.Dp, label: String, tag: String, compact: Boolean, topGap: androidx.compose.ui.unit.Dp? = null, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val line = t.line
    Row(
        Modifier
            .padding(top = if (compact) topGap ?: 0.dp else t.css.spaceSm)
            .fillMaxWidth()
            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
            .then(
                Modifier,
            )
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (compact) 0.dp else 0.8f.rem, end = t.css.spaceSm)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else t.css.spaceSm),
    ) {
        val ink = t.muted
        if (compact) {
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
    compact: Boolean = false,
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
                .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = if (compact) 0.dp else t.css.spaceMd, vertical = if (compact) 0.dp else t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 0.35f.rem),
        ) {
            if (compact) {
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
                            .heightIn(min = if (compact) PhoneDrawer.Floor else 2.75f.rem)
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
private fun SidebarFooter(phone: Boolean, connected: Boolean, onOpenSettings: () -> Unit, onCollapse: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    if (phone) {
        PhoneFooter(connected, onOpenSettings)
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
    // W53: the tablet footer's keys are at the 48 dp floor too.
    val floor = PhoneDrawer.Floor
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
