package com.tether.app.ui.sidebar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.tether.app.client.LabelText
import com.tether.app.ui.components.originalWords
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.SidebarRowModel
import com.tether.app.protocol.fold.truthy
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.tether.app.ui.components.focusRing
import androidx.compose.ui.semantics.stateDescription
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.CssFlexRow
import com.tether.app.ui.components.flexFloor
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.components.WholeWordText
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.tether.app.protocol.helpers.SessionSidebar as Web

// Swipe-to-archive geometry (session-sidebar.tsx:68-70), in CSS px = dp.
private const val ARCHIVE_WIDTH = 80f
private const val ARCHIVE_OPEN_PX = 40f
private const val ARCHIVE_OPEN_MAX = 88f

/** A drag in progress (session-sidebar.tsx:580-595). */
data class DragSession(
    val key: String,
    val workspace: String,
    val initialOrder: List<String>,
    val order: List<String>,
    val engaged: Boolean = false,
    val pointer: Offset? = null,
    val scrollDirection: Int = 0,
)

/**
 * The hold-to-reorder gesture (session-sidebar.tsx:654-761): the provider cap is the handle;
 * touch engages after 350ms without moving 8px, a mouse after 150ms or 3px; only rows of the SAME
 * block are drop targets; the list auto-scrolls within 52px of its edges; a changed order is
 * committed once, on release, as the block's full explicit order.
 */
class DragController internal constructor(
    private val stateOf: () -> SidebarState,
    private val viewOf: () -> SidebarView,
    private val getDrag: () -> DragSession?,
    private val setDrag: (DragSession?) -> Unit,
    private val rowBounds: Map<String, Rect>,
    private val onCommit: () -> (String, List<String>) -> Unit,
) {
    internal var listBounds: Rect = Rect.Zero

    private fun chainsOf(workspace: String) = viewOf().chainKeysByWorkspace[workspace].orEmpty()

    /** HG: the block's full row order with one key per handover chain (its head). */
    private fun collapsedBase(workspace: String): List<String> =
        SidebarViewModel.collapsedDragOrder(SidebarViewModel.dragBaseOrder(stateOf(), workspace, viewOf().delegateChildKeys), chainsOf(workspace))

    fun begin(entry: SidebarEntry) {
        val workspace = entry.workspace ?: return
        val order = collapsedBase(workspace)
        setDrag(DragSession(entry.key, workspace, order, order))
    }

    fun engage() {
        val drag = getDrag() ?: return
        if (!drag.engaged) setDrag(drag.copy(engaged = true))
    }

    fun move(pointer: Offset, edgePx: Float) {
        val drag = getDrag()?.takeIf { it.engaged } ?: return
        val direction = when {
            listBounds == Rect.Zero -> 0
            pointer.y < listBounds.top + edgePx -> -1
            pointer.y > listBounds.bottom - edgePx -> 1
            else -> 0
        }
        setDrag(drag.copy(pointer = pointer, scrollDirection = direction))
        reorderAt(pointer)
    }

    /** session-sidebar.tsx:662-673 — the row under the pointer, if it is in the dragged block. */
    fun reorderAt(pointer: Offset) {
        val drag = getDrag()?.takeIf { it.engaged } ?: return
        val target = rowBounds.entries.firstOrNull { (_, r) -> pointer.y >= r.top && pointer.y < r.bottom }?.key ?: return
        if (target == drag.key || target !in drag.order) return
        val next = SidebarViewModel.move(drag.order, drag.key, target)
        if (next == drag.order) return
        setDrag(drag.copy(order = next))
    }

    /** session-sidebar.tsx:684-696. */
    fun finish() {
        val drag = getDrag() ?: return
        if (drag.engaged && drag.order != drag.initialOrder) onCommit()(drag.workspace, SidebarViewModel.committedOrder(drag.order, chainsOf(drag.workspace)))
        setDrag(null)
    }

    /** TalkBack's "Move up" / "Move down" on the handle: the same full-order commit, one step. */
    fun moveByOne(entry: SidebarEntry, delta: Int): Boolean {
        val workspace = entry.workspace ?: return false
        val order = collapsedBase(workspace)
        val from = order.indexOf(entry.key)
        val to = from + delta
        if (from < 0 || to !in order.indices) return false
        val next = SidebarViewModel.move(order, entry.key, order[to])
        if (next == order) return false
        onCommit()(workspace, SidebarViewModel.committedOrder(next, chainsOf(workspace)))
        return true
    }
}

/**
 * `SessionItem` (session-sidebar.tsx:143-410) with the material-layer row (globals.css 1126-1330,
 * 1439-1570, 11097-11118, 11654-11741; studio.css 323-341).
 *
 * On the phone (ta-1jj7, owner-directed design: the compact full-screen drawer) the row is two
 * lines at the 48 dp floor: the provider cap in a 48 dp handle, then ONE name line with an end
 * ellipsis over a meta line (status words and time, the location beside them when 72 dp or more
 * remains), then the 48 dp end key. Nothing the rail's row carries is dropped; the full name stays
 * in the TalkBack label. Every phone branch is under [phone]; the rail's row is unchanged.
 */
@Composable
internal fun SessionRow(
    entry: SidebarEntry,
    workspace: String,
    active: Boolean,
    now: Long,
    /**
     * T13.2 r2: the list is not live (no connection). The row's status then says "was" on a still
     * dot whatever [sync] holds, and End session is disabled: a saved list never ends a session.
     */
    offline: Boolean,
    /** T13.2: this row's freshness while the list is not live (null = none known): the glyph and age only. */
    sync: com.tether.app.client.SessionSync? = null,
    armed: Boolean,
    /** T13.2 r3: the server origin the end control was armed for (its second tap ends there only). */
    armedOrigin: String?,
    /** T13.2 r3: the server origin the row is drawn for; a swipe captures it when it opens. */
    origin: String?,
    onArm: (String?) -> Unit,
    /** S3a: this row's kebab menu is open; [onMenuKey] opens (the row's key) or closes (null) the list's one menu. */
    menuOpen: Boolean = false,
    onMenuKey: (String?) -> Unit = {},
    /** The open session's Hand over row state, and which session that is: the menu offers Hand over on that row only. */
    activeHandover: com.tether.app.client.Handover.MenuState = com.tether.app.client.Handover.MenuState.Hidden,
    activeSessionId: String? = null,
    /** The live session this row was handed off to (its start gives "Handed over · age"), when it is listed. */
    handoverSuccessor: com.tether.app.protocol.model.AgentSession? = null,
    /** A group head with at least one older member: wears "Current" when nothing else claims the badge slot. */
    isGroupHead: Boolean = false,
    phone: Boolean,
    swipedSeed: Boolean,
    dragEnabled: Boolean,
    dragging: Boolean,
    dragController: DragController,
    rowBounds: MutableMap<String, Rect>,
    endBounds: MutableMap<String, Rect>,
    actions: SidebarActions,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val live = entry.live
    val provider = live?.provider?.takeIf { it.isNotEmpty() } ?: entry.history?.provider ?: ""
    // ta-28i: the server's title by the label rule (no bidi control, mark or invisible; cut at a
    // cluster boundary), drawn in its content's direction; it is also what TalkBack reads.
    val name = LabelText.title(Web.sidebarSessionName(entry.js))
    val updatedAt = SidebarModel.rowUpdatedAt(entry)
    val mode = (Web.sidebarSessionMode(entry.js) as? com.tether.app.protocol.tree.JsStr)?.value
    val endable = live != null && !live.runtimeArchived
    // T13.2 r2: shown but inert while offline (the client refuses an End session then, too).
    val endLive = endable && !offline
    val location = SidebarModel.rowLocation(entry, workspace)
    val unseen = Web.hasUnseenWork(entry.js)
    val digest = entry.history?.digest?.takeIf { entry.js["digest"] != null }
    val swipeEnabled = phone && endLive
    // S3a (SL-spec-3 §a.3/§a.4/§a.1): the SB row model's decisions, ported from the web (core/reducer SidebarRowModel).
    val digestFacts = if (truthy(entry.js["digest"])) SidebarRowModel.RowDigestFacts(digest?.newTurns ?: 0) else null
    val badge = SidebarRowModel.rowBadge(unread = entry.unread, digest = digestFacts, isGroupHead = isGroupHead)
    val statusKind = SidebarRowModel.rowStatusKind(live?.let { SidebarRowModel.RowLiveFacts(it.status, it.handedOffTo) })
    val handoverAt = if (statusKind == SidebarRowModel.RowStatusKind.Handed) SidebarRowModel.handoverAgeAt(updatedAt, handoverSuccessor?.startedAt) else null
    val rowCwd = live?.cwd?.takeIf { it.isNotEmpty() } ?: entry.history?.cwd.orEmpty()
    val handoverAvailable = activeHandover != com.tether.app.client.Handover.MenuState.Hidden && live != null && activeSessionId != null && live.id == activeSessionId
    val menuGroups = SidebarRowModel.rowMenuGroups(
        SidebarRowModel.RowMenuFacts(hasLive = live != null, endable = endable, cwd = rowCwd, resumeCommand = live?.resumeCommand, handoverAvailable = handoverAvailable),
    )

    // ── swipe (mobile shortcut; the X stays the primary end control, issue #175) ──
    val swipeX = remember { Animatable(if (swipedSeed && swipeEnabled) -ARCHIVE_WIDTH else 0f) }
    var swipeOpen by remember { mutableStateOf(swipedSeed && swipeEnabled) }
    // T13.2 r3: the swipe is step one of the end: the server it was opened for is the one it ends on.
    val latestOrigin by rememberUpdatedState(origin)
    var swipeOrigin by remember { mutableStateOf(if (swipedSeed && swipeEnabled) origin else null) }
    var swipeActive by remember { mutableStateOf(false) }
    var suppressClick by remember { mutableStateOf(false) }
    var onHandle by remember { mutableStateOf(false) }
    LaunchedEffect(phone, active) {
        if (!(swipedSeed && swipeEnabled)) {
            swipeX.snapTo(0f)
            swipeOpen = false
            swipeActive = false
        }
    }
    val snapSpec = if (reduced) snap() else tween<Float>(240, easing = t.css.easeOut.toEasing())
    fun snapTo(open: Boolean) {
        swipeOpen = open
        swipeOrigin = if (open) latestOrigin else null
        scope.launch { swipeX.animateTo(if (open) -ARCHIVE_WIDTH else 0f, snapSpec) }
    }

    // W39 (ta-v0h8): on the phone the card clips the row, so the row itself is square (the card rounds its ends).
    val rowShape = if (phone) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(0.625f.rem)
    val rowFace = when {
        active -> Color(0xFF243657)
        else -> Color.Transparent
    }
    val rowBorder = null
    val rowShadows = emptyList<CssShadow>()
    val ink = if (active) t.white else t.ink

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowBounds[entry.key] = it.boundsInRoot() }
            .alpha(if (dragging) 0.72f else 1f)
            .cssSurface(rowShape, rowFace, rowBorder, rowShadows)
            .then(if (phone) Modifier.clipToBounds() else Modifier.padding(end = 0.15f.rem))
            .testTag(SidebarTags.row(entry.key)),
    ) {
        if (swipeEnabled) {
            // .session-item-swipe-archive: revealed behind the row; fires at once (the swipe was step one).
            val revealed = swipeOpen || swipeActive || swipeX.value < 0f
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) { Column(
                Modifier
                    .width(ARCHIVE_WIDTH.dp)
                    .fillMaxHeight()
                    .alpha(if (revealed) 1f else 0f)
                    .background(t.danger, if (phone) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(t.radiusSm))
                    .semantics {
                        contentDescription = "Archive $name"
                        role = Role.Button
                    }
                    .clickable(enabled = swipeOpen) {
                        val drawnFor = swipeOrigin
                        snapTo(false)
                        actions.onEndSession(live.id, drawnFor)
                    }
                    .testTag(SidebarTags.archive(entry.key)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.2f.rem, Alignment.CenterVertically),
            ) {
                // W40-R1 (web a710c3dc): `mineral` on `danger`, 8.56:1 (white on danger was 1.77:1).
                SmallIcon(TetherIcons.Archive, t.mineral, 14.dp)
                Text("ARCHIVE", style = css(type.ui, 0.6f, 700, trackingEm = 0.02f), color = t.mineral, modifier = Modifier.clearAndSetSemantics { })
            } }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset((swipeX.value * density.density).roundToInt(), 0) }
                // `.session-item-swipe { background: inherit }`: the row's own surface travels with it.
                .then(if (rowFace != Color.Transparent) Modifier.background(rowFace, rowShape) else Modifier)
                // W39: the selected row's 3 dp `violet` bar at the start edge, full row height (mirrors in RTL).
                .then(
                    if (phone && active) Modifier.drawBehind {
                        val w = PhoneLook.SelectedBar.toPx()
                        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - w else 0f
                        drawRect(t.violet, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                    } else Modifier,
                )
                .then(
                    if (!swipeEnabled) Modifier else Modifier.pointerInput(entry.key, swipeOpen) {
                        val slop = 6.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            if (down.type != PointerType.Touch || onHandle) {
                                onHandle = false
                                return@awaitEachGesture
                            }
                            suppressClick = false
                            val startSwipe = if (swipeOpen) -ARCHIVE_WIDTH else 0f
                            var current = startSwipe
                            var engaged = false
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (!engaged) {
                                    // Dead zone; a vertical commit is the list's scroll (touch-action: pan-y).
                                    if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                                    if (abs(dx) < slop) continue
                                    engaged = true
                                    swipeActive = true
                                }
                                moved = true
                                current = (startSwipe + dx / density.density).coerceIn(-ARCHIVE_OPEN_MAX, 0f)
                                scope.launch { swipeX.snapTo(current) }
                                change.consume()
                            }
                            if (moved) {
                                suppressClick = true
                                snapTo(current <= -ARCHIVE_OPEN_PX)
                            }
                            swipeActive = false
                        }
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // .session-drag-handle: the provider cap, a 44dp hit target (2.5rem wide + Compose's
            // minimum touch size) around it.
            var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(
                Modifier
                    .width(if (phone) PhoneDrawer.Floor else 2.4f.rem)
                    .heightIn(min = if (phone) PhoneLook.RowMinHeight else 2.75f.rem)
                    .onGloballyPositioned { handleCoords = it }
                    .testTag(SidebarTags.handle(entry.key))
                    .semantics {
                        contentDescription = if (dragEnabled) "Hold and drag to move $name" else "Clear filters to reorder $name"
                        if (dragEnabled) {
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") { dragController.moveByOne(entry, -1) },
                                CustomAccessibilityAction("Move down") { dragController.moveByOne(entry, 1) },
                            )
                        }
                    }
                    .pointerInput(entry.key, dragEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            onHandle = true
                            if (!dragEnabled) return@awaitEachGesture
                            val touch = down.type == PointerType.Touch
                            dragController.begin(entry)
                            val cancelPx = 8.dp.toPx()
                            val enginePx = 3.dp.toPx()
                            try {
                                val outcome = withTimeoutOrNull(if (touch) 350L else 150L) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "up"
                                        if (!change.pressed) return@withTimeoutOrNull "up"
                                        if (change.isConsumed) return@withTimeoutOrNull "cancel"
                                        val d = hypot(change.position.x - down.position.x, change.position.y - down.position.y)
                                        if (touch && d > cancelPx) return@withTimeoutOrNull "cancel"
                                        if (!touch && d > enginePx) return@withTimeoutOrNull "engage"
                                    }
                                    @Suppress("UNREACHABLE_CODE") "up"
                                }
                                if (outcome == "up" || outcome == "cancel") return@awaitEachGesture
                                dragController.engage()
                                val edge = 52.dp.toPx()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    val root = handleCoords?.localToRoot(change.position) ?: continue
                                    dragController.move(root, edge)
                                }
                            } finally {
                                dragController.finish()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (phone) {
                    ProviderCap(
                        provider,
                        if (PhoneLook.MonochromeProviderTile) PhoneLook.Tile else PhoneDrawer.Cap,
                        Modifier.alpha(if (dragEnabled) 1f else 0.72f),
                        selected = active,
                        letterRem = 0.65f,
                        monochrome = PhoneLook.MonochromeProviderTile,
                    )
                } else {
                    ProviderCap(provider, 1.75f.rem, Modifier.alpha(if (dragEnabled) 1f else 0.72f), selected = active)
                }
            }
            // .session-item: the nav button — copy + chevron.
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = if (phone) PhoneLook.RowMinHeight else 3.5f.rem)
                    .semantics(mergeDescendants = true) {
                        contentDescription = rowDescription(name, entry, mode, unseen, now, updatedAt, location, offline, sync, phone, provider, badge, handoverAt)
                        if (active) selected = true
                    }
                    .clickable(role = Role.Button) {
                        if (suppressClick) {
                            suppressClick = false
                        } else if (swipeOpen) {
                            snapTo(false)
                        } else if (live != null) {
                            actions.onSelectSession(live.id)
                        } else {
                            entry.history?.let(actions.onReopenHistory)
                        }
                    }
                    // :root .session-item: 0.4rem space-sm, padding-left 0 (Studio 0.75rem 0.25rem, studio.css 325).
                    .then(
                        // The title column starts at 56 dp (the 48 dp handle + 8): the card's one text edge.
                        if (phone) Modifier.padding(top = PhoneLook.RowVPad, bottom = PhoneLook.RowVPad, start = 8.dp, end = 8.dp)
                        else Modifier.padding(vertical = 0.75f.rem, horizontal = 0.25f.rem),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                // The grid gap: space-md on a phone, space-sm from 48rem (globals.css 4052-4058).
                horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else t.css.spaceSm),
            ) {
                if (phone) {
                    PhoneRowWithBadge(
                        badge = badge,
                        badgeTag = SidebarTags.badge(entry.key),
                        modifier = Modifier.weight(1f),
                    ) {
                        PhoneRowBody(
                            entry = entry, name = name, active = active,
                            location = location, now = now, updatedAt = updatedAt, offline = offline, sync = sync, handoverAt = handoverAt,
                            modifier = Modifier,
                        )
                    }
                } else {
                    Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(0.2f.rem)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(0.4f.rem)) {
                            if (unseen) {
                                Box(Modifier.padding(top = 0.45f.rem).size(7.dp).background(t.violet, RoundedCornerShape(50)))
                            }
                            // `.session-item-head strong`: -webkit-line-clamp 2, whole words (globals.css 10917-10923, ta-z4c1).
                            WholeWordText(
                                name,
                                style = (css(type.ui, 0.78f, 600, lineHeight = 1.45f))
                                    .copy(textDirection = proseDirection),
                                color = (if (active) Color.White else t.ink),
                                maxLines = 2,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (live?.handedOffTo != null) SmallIcon(TetherIcons.ArrowRightLeft, ink, 12.dp, Modifier.padding(top = 0.2f.rem))
                        }
                        StatusLine(entry, now, updatedAt, offline, sync)
                        digest?.let { d -> DigestBlock(d, phone = false) }
                        // ta-28i: the location is a path: code, LTR.
                        location?.let { Text(codeLabel(it), style = css(type.ui, 0.64f, 400), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        entry.snippet?.let { SnippetLine(LabelText.hint(it), entry.matchCount, phone = false) }
                    }
                    // `<ChevronRight size={16}>`, drawn at 16px even in the desktop's 0.75rem grid column.
                    SmallIcon(TetherIcons.ChevronRight, ink, 16.dp)
                }
            }
            // S3a: the phone's trailing 48 dp slot is the kebab and its menu (the End two-step moved inside it).
            if (phone) {
                if (menuGroups.isEmpty()) {
                    // OI-5: no menu item (a history row without a cwd): an empty, non-semantic slot keeps the column aligned.
                    Box(Modifier.size(PhoneDrawer.Floor))
                } else {
                    RowKebab(
                        entry = entry, name = name, open = menuOpen, dragging = dragging,
                        onToggle = {
                            // A swiped-open row snaps back first, so the anchor sits in place (SL-spec-3 §b.4).
                            if (swipeOpen) snapTo(false)
                            onMenuKey(if (menuOpen) null else entry.key)
                        },
                    ) {
                        RowMenu(
                            name = name,
                            digest = digest,
                            groups = menuGroups,
                            pinned = live?.pinned == true,
                            handover = if (handoverAvailable) activeHandover else null,
                            endArmed = armed,
                            endLive = endLive,
                            liveId = live?.id,
                            cwd = rowCwd,
                            resumeCommand = live?.resumeCommand,
                            onDismiss = { onMenuKey(null) },
                            onRename = { live?.let { actions.onRenameSession(it.id, it.name) } },
                            onTogglePin = { live?.let { actions.onTogglePinSession(it.id, !it.pinned) } },
                            onHandOver = actions.onHandOver,
                            onStopHandover = actions.onStopHandover,
                            onEnd = {
                                if (live != null) {
                                    if (armed) {
                                        onArm(null)
                                        actions.onEndSession(live.id, armedOrigin)
                                        onMenuKey(null)
                                    } else {
                                        onArm(entry.key)
                                    }
                                }
                            },
                            onDisarm = { if (armed) onArm(null) },
                        )
                    }
                }
            }
            // .session-item-end: two taps by design (no modal); the rail's (the phone's moved into the kebab menu).
            if (endable && !phone) {
                val endShape = RoundedCornerShape(t.radiusSm)
                Box(
                    Modifier
                        .padding(end = if (phone) 0.dp else 0.3f.rem)
                        .size(if (phone) PhoneDrawer.Floor else 1.75f.rem)
                        .onGloballyPositioned { endBounds[entry.key] = it.boundsInRoot() }
                        .semantics {
                            contentDescription = when {
                                !endLive -> "End $name, unavailable: connect to end it"
                                armed -> "Tap again to end $name"
                                else -> "End $name"
                            }
                            role = Role.Button
                        }
                        .alpha(if (endLive) 1f else 0.55f)
                        .clickable(enabled = endLive) {
                            if (armed) {
                                onArm(null)
                                actions.onEndSession(live.id, armedOrigin)
                            } else {
                                onArm(entry.key)
                            }
                        }
                        .then(if (armed && endLive) Modifier.background(t.dangerWash, endShape) else Modifier)
                        .testTag(SidebarTags.end(entry.key)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (armed && endLive) {
                        Text("END?", style = css(type.ui, 0.62f, 700, trackingEm = 0.02f), color = t.danger, modifier = Modifier.clearAndSetSemantics { })
                    } else {
                        SmallIcon(TetherIcons.X, t.faint, if (phone) 12.dp else 11.dp)
                    }
                }
            }
        }
    }
}

/** The digest under a row: "N new turns since you left" and its snippet (the phone draws both at 0.65rem). */
@Composable
private fun DigestBlock(d: com.tether.app.protocol.model.HistoryDigest, phone: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val rem = if (phone) 0.75f else 0.7f
    Column(Modifier.padding(top = 0.1f.rem), verticalArrangement = Arrangement.spacedBy(0.05f.rem)) {
        if (d.newTurns > 0) {
            Text("${d.newTurns} new turn${if (d.newTurns == 1) "" else "s"} since you left", style = css(type.ui, rem, 600), color = t.violet)
        }
        val snippet = LabelText.hint(d.snippet)
        if (snippet.isNotEmpty()) Text(snippet, style = css(type.ui, rem, 400).copy(textDirection = proseDirection), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The phone row's text (ta-1jj7, owner-directed design), S3a: TWO lines for every row. Line 1: the name on ONE line with an end
 * ellipsis (the label carries the whole name; the unseen dot, the handoff glyph and the digest left the row: the trailing badge and
 * the kebab menu carry them). Line 2: the status words and time with the location beside them (see [PhoneMetaLine]), then a
 * search snippet when the row surfaced by a content hit. No chevron: the row is the target and says nothing more.
 */
@Composable
private fun PhoneRowBody(
    entry: SidebarEntry,
    name: String,
    active: Boolean,
    location: String?,
    now: Long,
    updatedAt: Long,
    offline: Boolean,
    sync: com.tether.app.client.SessionSync?,
    handoverAt: Long?,
    modifier: Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // `.session-item-head strong` with one line: the plain end ellipsis (WholeWordText at maxLines 1);
        // a one-line name has no line break to put inside a word (ta-z4c1 keeps the rail's two lines).
        WholeWordText(
            name,
            style = css(type.ui, 0.875f, 600, lineHeight = 1.4286f).copy(textDirection = proseDirection),
            color = if (active) Color.White else t.ink,
            maxLines = 1,
        )
        PhoneMetaLine(
            status = { StatusLine(entry, now, updatedAt, offline, sync, phone = true, handoverAt = handoverAt) },
            // ta-28i: the location is a path: code, LTR.
            location = location?.let { path -> { Text(codeLabel(path), style = css(type.ui, 0.75f, 400, lineHeight = 1.3333f), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
        entry.snippet?.let { SnippetLine(LabelText.hint(it), entry.matchCount, phone = true) }
    }
}

/** S3a: below this width the title column keeps with the badge inline, the badge drops to its own line (director A1: one named constant). */
internal val PhoneBadgeInlineMinTitle = 120.dp

/** S3a: from this font scale the badge always sits on its own line (the existing [PhoneMetaLine] threshold). */
internal const val PhoneBadgeStackFontScale = 1.5f

/**
 * SL-spec-3 §a.3: the row's copy column and its ONE badge. Inline (default): `[copy, weight 1][8][badge]` with the badge centred on
 * the row's height at the end edge. Stacked: the badge drops under the copy, starting at the title column, 4 dp below it. Decided per
 * row, in this layout: from font scale [PhoneBadgeStackFontScale], or when the copy column with the badge inline would measure under
 * [PhoneBadgeInlineMinTitle] (director amendment A1; no device-class flag).
 */
@Composable
internal fun PhoneRowWithBadge(
    badge: SidebarRowModel.RowBadge?,
    badgeTag: String,
    modifier: Modifier,
    body: @Composable () -> Unit,
) {
    if (badge == null) {
        Box(modifier) { body() }
        return
    }
    val fontScale = LocalDensity.current.fontScale
    androidx.compose.ui.layout.Layout(
        content = {
            Box { body() }
            RowBadgePill(badge, Modifier.testTag(badgeTag))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val vGap = 4.dp.roundToPx()
        val room = constraints.maxWidth
        val b = measurables[1].measure(androidx.compose.ui.unit.Constraints(0, room, 0, constraints.maxHeight))
        val inlineTitle = room - gap - b.width
        val stacked = fontScale >= PhoneBadgeStackFontScale || inlineTitle < PhoneBadgeInlineMinTitle.roundToPx()
        if (stacked) {
            val c = measurables[0].measure(androidx.compose.ui.unit.Constraints(0, room, 0, constraints.maxHeight))
            layout(room, c.height + vGap + b.height) {
                c.placeRelative(0, 0)
                b.placeRelative(0, c.height + vGap)
            }
        } else {
            val c = measurables[0].measure(androidx.compose.ui.unit.Constraints(0, inlineTitle, 0, constraints.maxHeight))
            val h = maxOf(c.height, b.height)
            layout(room, h) {
                c.placeRelative(0, (h - c.height) / 2)
                b.placeRelative(room - b.width, (h - b.height) / 2)
            }
        }
    }
}

/**
 * SL-spec-3 §a.3 (web CSS:1371-1374): the one neutral pill. `turns` / `New`: `slate` fill, `lineStrong` border, `white` text;
 * `Current`: transparent, `lineStrong` border, `muted` text. Min height 22, 8 dp padding, radius 6, 12/650 tabular, one line:
 * it never truncates (the title does). Its words are in the row's description, so it draws no semantics of its own.
 */
@Composable
internal fun RowBadgePill(badge: SidebarRowModel.RowBadge, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val current = badge is SidebarRowModel.RowBadge.Current
    val text = when (badge) {
        is SidebarRowModel.RowBadge.Turns -> SidebarRowModel.newTurnsText(badge.n)
        SidebarRowModel.RowBadge.New -> "New"
        SidebarRowModel.RowBadge.Current -> "Current"
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .heightIn(min = 22.dp)
            .background(if (current) Color.Transparent else t.slate, shape)
            .border(1.dp, t.lineStrong, shape)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = css(type.ui, 0.75f, 650, lineHeight = 1.3333f).copy(fontFeatureSettings = "tnum").ltrWords(),
            color = if (current) t.muted else t.white,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The badge's words for TalkBack (web SS:428-437): the count carries "since you left" and its raw number. */
internal fun badgeDescription(badge: SidebarRowModel.RowBadge): String = when (badge) {
    is SidebarRowModel.RowBadge.Turns -> "${badge.n} new turn${if (badge.n == 1) "" else "s"} since you left"
    SidebarRowModel.RowBadge.New -> "New — changed since you last looked"
    SidebarRowModel.RowBadge.Current -> "Current — latest session in this group"
}

/**
 * SL-spec-3 §a.1: the phone row's kebab in End ×'s 48 dp slot: `EllipsisVertical` 20 `faint`; while its menu is open (or pressed) a
 * 36 dp `keyFaceHover` face with a `white` glyph. Role Button, `Actions for <name>`, state `Menu closed` / `Menu open`, long-press
 * tooltip "Session actions". [menu] is the popup, anchored to the 48 dp cell. Inert while the row is lifted for a drag.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RowKebab(
    entry: SidebarEntry,
    name: String,
    open: Boolean,
    dragging: Boolean,
    onToggle: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val lit = open || pressed
    val face = RoundedCornerShape(t.radiusSm)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Session actions") } },
        state = rememberTooltipState(),
        modifier = Modifier
            .size(PhoneDrawer.Floor)
            .clickable(interaction, indication = null, enabled = !dragging, role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = "Actions for $name"
                role = Role.Button
                stateDescription = if (open) "Menu open" else "Menu closed"
            }
            .testTag(SidebarTags.kebab(entry.key)),
    ) {
        Box(Modifier.size(PhoneDrawer.Floor), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(36.dp)
                    .focusRing(focused, face, t.violet)
                    .then(if (lit) Modifier.background(t.keyFaceHover, face) else Modifier),
                contentAlignment = Alignment.Center,
            ) { SmallIcon(TetherIcons.EllipsisVertical, if (lit) t.white else t.faint, 20.dp) }
            if (open) menu()
        }
    }
}

/** The phone's location is given this much beside the status words before it drops to its own line. */
internal val PhoneLocationMinWidth = 72.dp

/**
 * The phone row's second line. Below font scale 1.5 it is one row: the status cluster at its natural
 * width, a 5.6 dp gap, then the location, which takes what is left (an end ellipsis) and drops to a line
 * of its own ONLY when less than [PhoneLocationMinWidth] remains. From 1.5x the pieces may wrap (rows
 * already grow with the text). The location is never absent: it is also in the row's label.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PhoneMetaLine(status: @Composable () -> Unit, location: (@Composable () -> Unit)?) {
    if (location == null) {
        status()
        return
    }
    if (LocalDensity.current.fontScale >= 1.5f) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.6.dp),
            verticalArrangement = Arrangement.spacedBy(1.6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            status()
            location()
        }
        return
    }
    androidx.compose.ui.layout.Layout(
        content = {
            Box { status() }
            Box { location() }
        },
    ) { measurables, constraints ->
        val gap = 5.6.dp.roundToPx()
        val vGap = 1.6.dp.roundToPx()
        val room = constraints.maxWidth
        val loose = androidx.compose.ui.unit.Constraints(0, room, 0, constraints.maxHeight)
        val s = measurables[0].measure(loose)
        val remaining = room - s.width - gap
        if (remaining >= PhoneLocationMinWidth.roundToPx()) {
            val l = measurables[1].measure(androidx.compose.ui.unit.Constraints(0, remaining, 0, constraints.maxHeight))
            val h = maxOf(s.height, l.height)
            layout(s.width + gap + l.width, h) {
                s.placeRelative(0, (h - s.height) / 2)
                l.placeRelative(s.width + gap, (h - l.height) / 2)
            }
        } else {
            val l = measurables[1].measure(loose)
            layout(maxOf(s.width, l.width), s.height + vGap + l.height) {
                s.placeRelative(0, 0)
                l.placeRelative(0, s.height + vGap)
            }
        }
    }
}

/** `.mode-tag` (globals.css 1537-1555, 11117; hidden in Studio, studio.css 331). */
@Composable
private fun ModeTag(mode: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val headless = mode == "headless"
    Box(
        Modifier
            .padding(top = 0.25f.rem)
            .cssSurface(RoundedCornerShape(999.dp), if (headless) t.violetWash else t.slate, CssBorder(1.dp, if (headless) t.violetStrong else t.line))
            .padding(horizontal = 0.3f.rem, vertical = 0.06f.rem),
    ) {
        Text(if (headless) "CHAT" else "TERM", style = css(type.ui, 0.5f, 700, trackingEm = 0.03f, lineHeight = 1.15f), color = if (headless) t.violet else t.faint, modifier = Modifier.originalWords(if (headless) "Chat" else "Terminal"))
    }
}

/**
 * `.status-line` (globals.css 1557-1609): the dot (spinner while active, radar ping while waiting)
 * AND the words — status is never colour alone. A history-only row shows "8m ago".
 */
@Composable
private fun StatusLine(entry: SidebarEntry, now: Long, updatedAt: Long, offline: Boolean, sync: com.tether.app.client.SessionSync?, phone: Boolean = false, handoverAt: Long? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    // The phone's words and time are 0.65rem (ta-1jj7, owner-directed design); the rail's stay 0.68rem.
    val style = if (phone) css(type.ui, 0.75f, 500, lineHeight = 1.3333f) else css(type.ui, 0.68f, 500)
    val live = entry.live
    val rel = Format.relativeTime(updatedAt.toDouble(), now.toDouble())
    // T13.2: an offline row carries more (the "was" words and the copy's glyph): its pieces wrap
    // whole at a large font instead of squeezing. A live row keeps its single line.
    StatusFlow(wrap = offline && live != null) {
        if (phone && live != null) {
            // S3a (SL-spec-3 §a.4 + SL1 director fix 1): the phone's words, then ONE shared " · " (faint, no spacer before it) and the age.
            // "Handed over" is the web's word (rowStatusKind); offline it stays "Handed over" with no "was" (a past fact), on a still dot.
            val handed = handoverAt != null
            val was = if (offline && !handed) com.tether.app.ui.components.FreshnessCopy.qualifiedStatus(live.status, null, now) else null
            val color = when {
                was != null || handed -> t.faint
                live.status == "active" -> t.running
                live.status == "waiting" -> t.violet
                else -> t.faint
            }
            val word = when {
                handed -> SidebarRowModel.HANDED_OVER_WORD
                was != null -> was
                else -> Format.statusCopy[live.status] ?: LabelText.label(live.status)
            }
            val wordColor = when {
                handed -> t.muted
                was != null -> t.faint
                live.status != "active" && live.status != "waiting" -> t.muted
                else -> color
            }
            val age = if (handed) Format.relativeTime(handoverAt!!.toDouble(), now.toDouble()) else rel
            val animated = !offline && !handed
            when {
                animated && live.status == "active" -> SpinnerRing(color, size = 0.65f.rem, stroke = 1.5.dp, modifier = Modifier.flexFloor(3.dp))
                animated && live.status == "waiting" -> WaitingPingDot(color, dotSize = 0.4f.rem, modifier = Modifier.flexFloor(0.dp))
                handed -> StatusDot(t.faint, size = 6.dp, modifier = Modifier.flexFloor(0.dp))
                else -> StatusDot(color, size = 0.4f.rem, modifier = Modifier.flexFloor(0.dp))
            }
            val wordStyle = if (live.status == "waiting" && !handed && was == null) style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)) else style
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = wordColor)); append(word); pop()
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = t.faint, fontWeight = androidx.compose.ui.text.font.FontWeight(500))); append(META_SEPARATOR + age); pop()
                },
                style = wordStyle.ltrWords(),
                softWrap = false,
            )
            if (sync != null) com.tether.app.ui.components.FreshnessGlyph(sync, now, Modifier.testTag(SidebarTags.freshness(entry.key)))
            return@StatusFlow
        }
        // r2: offline, "was" whether or not the client has an entry for the row.
        val was = if (live != null && offline) com.tether.app.ui.components.FreshnessCopy.qualifiedStatus(live.status, null, now) else null
        if (live != null && was != null) {
            // T13.2: a saved list never claims the agent is running or waiting on you now: the
            // words say "was", on a faint still dot (no spinner, no violet ping).
            StatusDot(t.faint, size = 0.4f.rem, modifier = Modifier.flexFloor(0.dp))
            Text(was, style = style, color = t.faint, softWrap = false)
            Text("· $rel", style = style.ltrWords(), color = t.faint, softWrap = false)
        } else if (live != null) {
            val color = when (live.status) {
                "active" -> t.running
                "waiting" -> t.violet
                else -> t.faint
            }
            // W39: on the phone the word is `muted` at rest (7.2:1) and a waiting row's word is 600.
            val wordColor = if (phone && live.status != "active" && live.status != "waiting") t.muted else color
            val wordStyle = if (phone && live.status == "waiting") style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)) else style
            // Flex floors: the dot and the ping shrink to 0, the spinner to its two 1.5dp borders.
            when (live.status) {
                "active" -> SpinnerRing(color, size = 0.65f.rem, stroke = 1.5.dp, modifier = Modifier.flexFloor(3.dp))
                "waiting" -> WaitingPingDot(color, dotSize = 0.4f.rem, modifier = Modifier.flexFloor(0.dp))
                else -> StatusDot(color, size = 0.4f.rem, modifier = Modifier.flexFloor(0.dp))
            }
            // ta-28i r2: a status this build does not know is the server's word: the label rule.
            Text(Format.statusCopy[live.status] ?: LabelText.label(live.status), style = wordStyle, color = wordColor)
            Text("· $rel", style = style.ltrWords(), color = t.faint)
        } else {
            SmallIcon(TetherIcons.History, t.faint, 12.dp)
            Text("$rel ago", style = style.ltrWords(), color = t.faint)
        }
        // T13.2 (SYNC_DESIGN §4.2): `history` + "12m" for a saved copy, `cloud-off` for none.
        if (live != null && sync != null) com.tether.app.ui.components.FreshnessGlyph(sync, now, Modifier.testTag(SidebarTags.freshness(entry.key)))
    }
}

/** The ONE separator between a meta line's word and what follows it (status · age, offline, search hits, handed over · age). */
internal const val META_SEPARATOR = " · "

/** The status line's container: a Row, or (T13.2, [wrap]) a FlowRow whose pieces wrap whole. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StatusFlow(wrap: Boolean, content: @Composable () -> Unit) {
    if (!wrap) {
        // `.status-line` (globals.css 1003): flex, nowrap, every item `0 1 auto`: it shrinks like CSS, so a
        // word never splits (ta-z4c1).
        CssFlexRow(gap = 0.35f.rem) { content() }
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(0.35f.rem),
        verticalArrangement = Arrangement.spacedBy(0.1f.rem),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** What TalkBack reads for the row's nav button: name, unseen, mode, status in words, time, location. */
private fun rowDescription(
    name: String,
    entry: SidebarEntry,
    mode: String?,
    unseen: Boolean,
    now: Long,
    updatedAt: Long,
    location: String?,
    offline: Boolean,
    sync: com.tether.app.client.SessionSync?,
    phone: Boolean,
    provider: String,
    badge: SidebarRowModel.RowBadge? = null,
    handoverAt: Long? = null,
): String = buildString {
    append(name)

    // S3a (SL-spec-3 §b.1): the phone reads `name, handed off to X, mode, status, age, freshness, location, matched, badge. digest`;
    // the badge's own words replace the old ", changed since you last looked" and ", N new turns since you left".
    if (!phone && unseen) append(", changed since you last looked")
    if (entry.live?.handedOffTo != null) {
        if (phone) append(", ").append(entry.handedOffDescription?.replaceFirstChar { it.lowercase() } ?: "handed off to another session") else append(", handed off")
    }
    when (mode) {
        "headless" -> append(", chat")
        "terminal" -> append(", terminal")
    }
    val rel = Format.relativeTime(updatedAt.toDouble(), now.toDouble())
    val live = entry.live
    val was = if (live != null && offline) com.tether.app.ui.components.FreshnessCopy.qualifiedStatus(live.status, null, now) else null
    if (live != null && phone && handoverAt != null) append(", ${SidebarRowModel.HANDED_OVER_WORD}, ${Format.relativeTime(handoverAt.toDouble(), now.toDouble())}")
    else if (live != null) append(", ${was ?: Format.statusCopy[live.status] ?: LabelText.label(live.status)}, $rel")
    else append(", $rel ago")
    // T13.2: the full sentence the row's glyph stands for.
    if (live != null) com.tether.app.ui.components.FreshnessCopy.sessionLabel(sync, now)?.let { append(", $it") }
    if (!phone) {
        entry.history?.digest?.takeIf { entry.js["digest"] != null && it.newTurns > 0 }?.let {
            append(", ${it.newTurns} new turn${if (it.newTurns == 1) "" else "s"} since you left")
        }
    }
    location?.let { append(", ").append(SafeText.line(it)) }
    // W39: the phone's tile is a single-ink mark: the provider's name is in words here instead.
    if (phone && PhoneLook.MonochromeProviderTile && provider.isNotEmpty()) {
        append(", ").append(SafeText.line(Web.SIDEBAR_HARNESSES.firstOrNull { it.first == provider }?.second ?: provider))
    }
    // T5.3: a content-search hit says where it matched. ta-28i: by the label rule, as drawn.
    entry.snippet?.let { LabelText.hint(it) }?.let { snippet ->
        append(", matched: $snippet")
        if (entry.matchCount > 1) append(", ${entry.matchCount} matches")
    }
    // S3a: the badge's words at their place, then the digest's snippet (it left the row's face; the menu's block also shows it).
    if (phone) {
        badge?.let { append(", ").append(badgeDescription(it)) }
        entry.history?.digest?.takeIf { entry.js["digest"] != null }?.let { d -> LabelText.hint(d.snippet).takeIf { it.isNotEmpty() }?.let { append(". ").append(it) } }
    }
}

/**
 * T5.3 `.session-item-snippet` (globals.css 1514-1532): a single faint 0.7rem line under the
 * title — the Search glyph at 11px, the context window, and "· N matches" at 0.8 opacity when
 * the hit counted more than one (session-sidebar.tsx:384-389). The row's description carries it.
 */
@Composable
internal fun SnippetLine(snippet: String, matchCount: Int, phone: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = css(type.ui, if (phone) 0.75f else 0.7f, 400)
    Row(
        Modifier.padding(top = 0.1f.rem),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else 0.25f.rem),
    ) {
        SmallIcon(TetherIcons.Search, t.faint, 11.dp)
        if (phone) androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
        Text(snippet, style = style.copy(textDirection = proseDirection), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (matchCount > 1) {
            // The phone shares the status line's " · " (no spacer before it); the rail keeps its non-breaking form.
            Text(if (phone) "$META_SEPARATOR$matchCount matches" else "·\u00A0$matchCount matches", style = style.ltrWords(), color = t.faint, maxLines = 1, softWrap = false, modifier = Modifier.alpha(0.8f))
        }
    }
}

