package com.tether.app.ui.sidebar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.tether.app.ui.components.CssBorder
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

    fun begin(entry: SidebarEntry) {
        val workspace = entry.workspace ?: return
        val order = SidebarViewModel.dragBaseOrder(stateOf(), workspace, viewOf().delegateChildKeys)
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
        if (drag.engaged && drag.order != drag.initialOrder) onCommit()(drag.workspace, drag.order)
        setDrag(null)
    }

    /** TalkBack's "Move up" / "Move down" on the handle: the same full-order commit, one step. */
    fun moveByOne(entry: SidebarEntry, delta: Int): Boolean {
        val workspace = entry.workspace ?: return false
        val order = SidebarViewModel.dragBaseOrder(stateOf(), workspace, viewOf().delegateChildKeys)
        val from = order.indexOf(entry.key)
        val to = from + delta
        if (from < 0 || to !in order.indices) return false
        val next = SidebarViewModel.move(order, entry.key, order[to])
        if (next == order) return false
        onCommit()(workspace, next)
        return true
    }
}

/**
 * `SessionItem` (session-sidebar.tsx:143-410) with the material-layer row (globals.css 1126-1330,
 * 1439-1570, 11097-11118, 11654-11741; studio.css 323-341).
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

    val rowShape = RoundedCornerShape(0.625f.rem)
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
            .then(if (phone) Modifier.clipToBounds().padding(vertical = 2.dp) else Modifier.padding(end = 0.15f.rem))
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
                    .background(t.danger, RoundedCornerShape(t.radiusSm))
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
                SmallIcon(TetherIcons.Archive, t.white, 14.dp)
                Text("ARCHIVE", style = css(type.ui, 0.6f, 700, trackingEm = 0.02f), color = t.white, modifier = Modifier.clearAndSetSemantics { })
            } }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset((swipeX.value * density.density).roundToInt(), 0) }
                // `.session-item-swipe { background: inherit }`: the row's own surface travels with it.
                .then(if (rowFace != Color.Transparent) Modifier.background(rowFace, rowShape) else Modifier)
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
                    .width(2.4f.rem)
                    .heightIn(min = 2.75f.rem)
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
                ProviderCap(provider, 1.75f.rem, Modifier.alpha(if (dragEnabled) 1f else 0.72f), selected = active)
            }
            // .session-item: the nav button — copy + chevron.
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 3.5f.rem)
                    .semantics(mergeDescendants = true) {
                        contentDescription = rowDescription(name, entry, mode, unseen, now, updatedAt, location, offline, sync)
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
                        Modifier.padding(vertical = 0.75f.rem, horizontal = 0.25f.rem),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                // The grid gap: space-md on a phone, space-sm from 48rem (globals.css 4052-4058).
                horizontalArrangement = Arrangement.spacedBy(if (phone) t.css.spaceMd else t.css.spaceSm),
            ) {
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
                    digest?.let { d ->
                        Column(Modifier.padding(top = 0.1f.rem), verticalArrangement = Arrangement.spacedBy(0.05f.rem)) {
                            if (d.newTurns > 0) {
                                Text("${d.newTurns} new turn${if (d.newTurns == 1) "" else "s"} since you left", style = css(type.ui, 0.7f, 600), color = t.violet)
                            }
                            val snippet = LabelText.hint(d.snippet)
                            if (snippet.isNotEmpty()) Text(snippet, style = css(type.ui, 0.7f, 400).copy(textDirection = proseDirection), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // ta-28i: the location is a path: code, LTR.
                    location?.let { Text(codeLabel(it), style = css(type.ui, 0.64f, 400), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    entry.snippet?.let { SnippetLine(LabelText.hint(it), entry.matchCount) }
                }
                // `<ChevronRight size={16}>`, drawn at 16px even in the desktop's 0.75rem grid column.
                SmallIcon(TetherIcons.ChevronRight, ink, 16.dp)
            }
            // .session-item-end: two taps by design (no modal); 44dp on a phone (coarse pointer).
            if (endable) {
                val endShape = RoundedCornerShape(t.radiusSm)
                Box(
                    Modifier
                        .padding(end = 0.3f.rem)
                        .size(if (phone) 2.75f.rem else 1.75f.rem)
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
                        SmallIcon(TetherIcons.X, t.faint, 11.dp)
                    }
                }
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
private fun StatusLine(entry: SidebarEntry, now: Long, updatedAt: Long, offline: Boolean, sync: com.tether.app.client.SessionSync?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = css(type.ui, 0.68f, 500)
    val live = entry.live
    val rel = Format.relativeTime(updatedAt.toDouble(), now.toDouble())
    // T13.2: an offline row carries more (the "was" words and the copy's glyph): its pieces wrap
    // whole at a large font instead of squeezing. A live row keeps its single line.
    StatusFlow(wrap = offline && live != null) {
        // r2: offline, "was" whether or not the client has an entry for the row.
        val was = if (live != null && offline) com.tether.app.ui.components.FreshnessCopy.qualifiedStatus(live.status, null, now) else null
        if (live != null && was != null) {
            // T13.2: a saved list never claims the agent is running or waiting on you now: the
            // words say "was", on a faint still dot (no spinner, no violet ping).
            StatusDot(t.faint, size = 0.4f.rem)
            Text(was, style = style, color = t.faint, softWrap = false)
            Text("· $rel", style = style, color = t.faint, softWrap = false)
        } else if (live != null) {
            val color = when (live.status) {
                "active" -> t.running
                "waiting" -> t.violet
                else -> t.faint
            }
            when (live.status) {
                "active" -> SpinnerRing(color, size = 0.65f.rem, stroke = 1.5.dp)
                "waiting" -> WaitingPingDot(color, dotSize = 0.4f.rem)
                else -> StatusDot(color, size = 0.4f.rem)
            }
            // ta-28i r2: a status this build does not know is the server's word: the label rule.
            Text(Format.statusCopy[live.status] ?: LabelText.label(live.status), style = style, color = color)
            Text("· $rel", style = style, color = t.faint)
        } else {
            SmallIcon(TetherIcons.History, t.faint, 12.dp)
            Text("$rel ago", style = style, color = t.faint)
        }
        // T13.2 (SYNC_DESIGN §4.2): `history` + "12m" for a saved copy, `cloud-off` for none.
        if (live != null && sync != null) com.tether.app.ui.components.FreshnessGlyph(sync, now, Modifier.testTag(SidebarTags.freshness(entry.key)))
    }
}

/** The status line's container: a Row, or (T13.2, [wrap]) a FlowRow whose pieces wrap whole. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StatusFlow(wrap: Boolean, content: @Composable () -> Unit) {
    if (!wrap) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.35f.rem)) { content() }
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
): String = buildString {
    append(name)
    if (unseen) append(", changed since you last looked")
    if (entry.live?.handedOffTo != null) append(", handed off")
    when (mode) {
        "headless" -> append(", chat")
        "terminal" -> append(", terminal")
    }
    val rel = Format.relativeTime(updatedAt.toDouble(), now.toDouble())
    val live = entry.live
    val was = if (live != null && offline) com.tether.app.ui.components.FreshnessCopy.qualifiedStatus(live.status, null, now) else null
    if (live != null) append(", ${was ?: Format.statusCopy[live.status] ?: LabelText.label(live.status)}, $rel")
    else append(", $rel ago")
    // T13.2: the full sentence the row's glyph stands for.
    if (live != null) com.tether.app.ui.components.FreshnessCopy.sessionLabel(sync, now)?.let { append(", $it") }
    entry.history?.digest?.takeIf { entry.js["digest"] != null && it.newTurns > 0 }?.let {
        append(", ${it.newTurns} new turn${if (it.newTurns == 1) "" else "s"} since you left")
    }
    location?.let { append(", ").append(SafeText.line(it)) }
    // T5.3: a content-search hit says where it matched. ta-28i: by the label rule, as drawn.
    entry.snippet?.let { LabelText.hint(it) }?.let { snippet ->
        append(", matched: $snippet")
        if (entry.matchCount > 1) append(", ${entry.matchCount} matches")
    }
}

/**
 * T5.3 `.session-item-snippet` (globals.css 1514-1532): a single faint 0.7rem line under the
 * title — the Search glyph at 11px, the context window, and "· N matches" at 0.8 opacity when
 * the hit counted more than one (session-sidebar.tsx:384-389). The row's description carries it.
 */
@Composable
private fun SnippetLine(snippet: String, matchCount: Int) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = css(type.ui, 0.7f, 400)
    Row(
        Modifier.padding(top = 0.1f.rem),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.25f.rem),
    ) {
        SmallIcon(TetherIcons.Search, t.faint, 11.dp)
        Text(snippet, style = style.copy(textDirection = proseDirection), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (matchCount > 1) Text("·\u00A0$matchCount matches", style = style, color = t.faint, maxLines = 1, softWrap = false, modifier = Modifier.alpha(0.8f))
    }
}

