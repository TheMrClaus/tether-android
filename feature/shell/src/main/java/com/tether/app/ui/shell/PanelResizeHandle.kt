package com.tether.app.ui.shell

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens
import kotlin.math.abs
import kotlin.math.roundToInt

/** `.panel-resize-handle { width: 1rem }` (globals.css 3909): it straddles its column's edge. */
val PanelHandleWidth = 16.dp

/**
 * The draggable edge of a desktop column (components/panel-resize-handle.tsx, issue #186).
 *
 * It never lays anything out: it reads the column's [renderedWidth], reports a LIVE width while a
 * drag is under way ([onLive]; null puts the committed width back) and commits the settled width
 * ([onCommit]; null restores the active theme's default). As on the web:
 *
 * - drag: a move under 2px is a tap, not a drag; the width is `panelWidthFromDrag` of the start
 *   width and the pointer's travel, clamped against the viewport (panel-resize-handle.tsx:162-172);
 *   committed on release, put back on cancel. Mouse drags need the primary button.
 * - double tap (the web's double-click) resets to the theme default.
 * - keyboard (a hardware keyboard on a tablet): ←/→ move the EDGE by 16px, Shift by 64px
 *   (`panelWidthKeyDelta`); Home resets; modified keys are left alone (panel-resize-handle.tsx:174-196).
 * - TalkBack: the web's `role="separator"` with `aria-valuenow/min/max` becomes an adjustable range
 *   (the width in px between the bounds) with the label as its name; swiping adjusts it, and a
 *   "Reset to default width" action stands in for Home / double-click.
 *
 * Look (globals.css 3887-3942): nothing at rest; a 1px `--line-strong` hairline down its middle
 * while hovered (mouse) or dragged; keyboard focus draws the 2px violet outline inset 3px. The
 * drawn strip is 16dp wide; Compose extends its touch area to the 48dp minimum touch target where
 * no other control is hit, so the edge takes a comfortable touch without widening the strip that
 * the web keeps narrow to spare the rail's and transcript's own edge controls.
 */
@Composable
fun PanelResizeHandle(
    kind: PanelKind,
    renderedWidth: Int,
    viewportWidth: Int,
    onLive: (Int?) -> Unit,
    onCommit: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    var dragging by remember { mutableStateOf(false) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val width by rememberUpdatedState(renderedWidth)
    val viewport by rememberUpdatedState(viewportWidth)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)

    val bounds = PanelWidthGeometry.bounds(kind, viewportWidth)
    val now = PanelWidthGeometry.clamp(kind, renderedWidth.toDouble(), viewportWidth)

    fun reset() {
        live(null)
        commit(null)
    }

    fun nudge(delta: Int): Boolean {
        val current = PanelWidthGeometry.clamp(kind, width.toDouble(), viewport)
        val next = PanelWidthGeometry.clamp(kind, (current + delta).toDouble(), viewport)
        if (next != current) commit(next)
        return true
    }

    Box(
        modifier
            .width(PanelHandleWidth)
            .fillMaxHeight()
            .onGloballyPositioned { coordinates[0] = it }
            .hoverable(interaction)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onKeyEvent false
                when (event.key) {
                    Key.MoveHome -> { reset(); true }
                    Key.DirectionRight -> nudge(PanelWidthGeometry.keyDelta(kind, PanelWidthGeometry.Arrow.Right, event.isShiftPressed))
                    Key.DirectionLeft -> nudge(PanelWidthGeometry.keyDelta(kind, PanelWidthGeometry.Arrow.Left, event.isShiftPressed))
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(kind) {
                var lastTapAt = -1L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val start = coordinates[0] ?: return@awaitEachGesture
                    val startX = start.localToRoot(down.position).x
                    val startWidth = width.toDouble()
                    if (startWidth <= 0.0) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    var moved = false
                    var settled = startWidth.roundToInt()
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = true
                                change.consume()
                                break
                            }
                            val here = coordinates[0] ?: break
                            val deltaDp = (here.localToRoot(change.position).x - startX) / density
                            if (!moved && abs(deltaDp) < 2f) continue // a click, not a drag
                            moved = true
                            change.consume()
                            settled = PanelWidthGeometry.fromDrag(kind, startWidth, deltaDp.toDouble(), viewport)
                            live(settled)
                        }
                    } finally {
                        dragging = false
                        if (moved) {
                            if (released) commit(settled) else live(null)
                        }
                    }
                    if (released && !moved) {
                        val at = down.uptimeMillis
                        if (lastTapAt >= 0 && at - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis) {
                            lastTapAt = -1L
                            reset()
                        } else {
                            lastTapAt = at
                        }
                    }
                }
            }
            .drawBehind {
                if (hovered || dragging) {
                    val px = 1.dp.toPx()
                    drawRect(t.lineStrong, Offset((size.width - px) / 2f, 0f), Size(px, size.height))
                }
                if (focused) {
                    // `outline: 2px solid var(--violet); outline-offset: -3px`: the band 1-3px inside.
                    val stroke = 2.dp.toPx()
                    val inset = 2.dp.toPx()
                    drawRect(
                        t.violet,
                        Offset(inset, inset),
                        Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(stroke),
                    )
                }
            }
            .semantics {
                contentDescription = kind.label
                stateDescription = "$now of ${bounds.min} to ${bounds.max}"
                progressBarRangeInfo = ProgressBarRangeInfo(now.toFloat(), bounds.min.toFloat()..bounds.max.toFloat())
                setProgress { target ->
                    val next = PanelWidthGeometry.clamp(kind, target.toDouble(), viewportWidth)
                    if (next != now) commit(next)
                    true
                }
                customActions = listOf(CustomAccessibilityAction("Reset to default width") { reset(); true })
            }
            .testTag(if (kind == PanelKind.Rail) ShellTags.RailHandle else ShellTags.InspectorHandle),
    )
}
