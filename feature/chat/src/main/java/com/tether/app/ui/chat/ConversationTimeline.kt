package com.tether.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.oklabMix
import com.tether.app.ui.components.rememberTetherHaptics
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.ThemeFamily
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The column the rail takes beside the transcript: the web's mobile rail (3.35rem) / desktop gutter (3.4rem). */
internal fun timelineColumnWidth(side: TimelineSide): Dp = if (side == TimelineSide.Right) 54.dp else 54.4.dp

/** The rail itself (conversation-timeline.module.css `.timeline`: 2.8rem desktop, 3.35rem mobile). */
private fun railWidth(side: TimelineSide): Dp = if (side == TimelineSide.Right) 53.6.dp else 44.8.dp

/** `--rail-inset`: where the scale line sits from the docked edge (0.7rem desktop; mobile max(0.78rem, safe area)). */
private fun railInset(side: TimelineSide): Dp = if (side == TimelineSide.Right) 12.48.dp else 11.2.dp

internal const val TIMELINE_TAG = "conversation-timeline"
internal const val TIMELINE_BUBBLE_TAG = "timeline-bubble"
internal fun timelineMarkTag(index: Int) = "timeline-mark-$index"

/** What the rail's pointer handling reads each event (it outlives recompositions). */
private class RailGeometry(
    val size: Int,
    val visible: List<Int>,
    val cluster: TimelineCluster,
    val railHeight: Float,
    val fallback: Int,
)

/**
 * T6.5: the compact conversation index (tether components/conversation-timeline.tsx at
 * PARITY_BASE). A fixed stack of up to 10 graduations hanging from an always-drawn scale line;
 * the violet needle is the prompt at the transcript's reading line. Browsing changes which prompts
 * occupy the fixed slots (a touch scrub whose bubble shows from the first touch and whose release
 * jumps, a mouse wheel, hover), never the stack itself. Read-only navigation: nothing here reaches
 * the server. [onScrolledUp] is told before a jump moves the transcript up (the web's scroll-up
 * disengages follow mode).
 */
@Composable
internal fun ConversationTimeline(
    points: List<TimelinePoint>,
    listState: LazyListState,
    storyPointToLazyIndex: Map<Int, Int>,
    itemKeyToSpIndex: Map<Any, Int>,
    side: TimelineSide,
    liveCopy: Boolean,
    zone: ZoneId,
    onScrolledUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    val t = LocalTetherTokens.current
    val reducedMotion = LocalReducedMotion.current
    val density = LocalDensity.current
    val unit = density.density
    val scope = rememberCoroutineScope()
    val haptics = rememberTetherHaptics()

    var browseAnchor by remember { mutableStateOf<Int?>(null) }
    var hoverSlot by remember { mutableIntStateOf(-1) }
    var keyboardSlot by remember { mutableIntStateOf(-1) }
    var scrubIndex by remember { mutableIntStateOf(-1) }
    var scrubSlot by remember { mutableIntStateOf(TimelineModel.DEFAULT_ANCHOR_SLOT) }
    var scrubbing by remember { mutableStateOf(false) }
    val lastHapticIndex = remember { intArrayOf(-1) }

    val size = points.size
    val latestSize by rememberUpdatedState(size)
    val latestLazy by rememberUpdatedState(storyPointToLazyIndex)
    val latestScrolledUp by rememberUpdatedState(onScrolledUp)
    val latestReduced by rememberUpdatedState(reducedMotion)

    fun scrollToStoryPoint(index: Int) {
        if (index < 0 || index >= latestSize) return
        val lazyIndex = latestLazy[index] ?: return
        val info = listState.layoutInfo
        val vh = info.viewportSize.height.toFloat()
        val jumpLine = vh * TimelineModel.JUMP_LINE
        val row = info.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
        val rowTop = row?.let { (it.offset - info.viewportStartOffset).toFloat() }
        val distance = if (rowTop != null) {
            abs(rowTop - jumpLine)
        } else {
            val shown = info.visibleItemsInfo
            val average = if (shown.isEmpty()) vh else shown.sumOf { it.size }.toFloat() / shown.size
            abs(lazyIndex - listState.firstVisibleItemIndex) * average
        }
        val up = if (rowTop != null) rowTop < jumpLine else lazyIndex < listState.firstVisibleItemIndex
        if (up) latestScrolledUp()
        // The row's top lands at 28% of the viewport (scrollOffset counts from past the top padding).
        val scrollOffset = -info.viewportStartOffset - jumpLine.roundToInt()
        scope.launch {
            if (TimelineModel.smoothJump(distance, vh, latestReduced)) listState.animateScrollToItem(lazyIndex, scrollOffset)
            else listState.scrollToItem(lazyIndex, scrollOffset)
        }
    }

    fun updateScrubIndex(index: Int) {
        if (index < 0 || index >= latestSize) return
        scrubIndex = index
        browseAnchor = index
        if (lastHapticIndex[0] != index) {
            lastHapticIndex[0] = index
            haptics.scrubStep()
        }
    }

    fun finishScrub(jump: Boolean) {
        val index = scrubIndex
        scrubbing = false
        scrubIndex = -1
        scrubSlot = TimelineModel.DEFAULT_ANCHOR_SLOT
        lastHapticIndex[0] = -1
        if (jump && index >= 0) scrollToStoryPoint(index)
    }

    // The prompt nearest the transcript's reading line (40% down): the needle.
    val activeIndex by remember(itemKeyToSpIndex, size) {
        derivedStateOf {
            val info = listState.layoutInfo
            val rows = info.visibleItemsInfo.mapNotNull { item ->
                itemKeyToSpIndex[item.key]?.let { it to (item.offset - info.viewportStartOffset).toFloat() }
            }
            TimelineModel.activeIndex(rows, info.viewportSize.height.toFloat(), size)
        }
    }

    val visible = TimelineModel.window(size, activeIndex, browseAnchor, scrubbing, scrubSlot)
    val visibleCount = visible.size
    val focusSlot = if (scrubbing) visible.indexOf(scrubIndex) else hoverSlot
    val focusIndex = if (focusSlot >= 0) visible.getOrNull(focusSlot) ?: -1 else -1
    val inspecting = focusIndex in 0 until size

    val precision = t.skin.family == ThemeFamily.Precision
    // Precision's graduations take the live tone (the module's [data-theme="precision"|"machine"] rules).
    val markColor = if (precision) oklabMix(t.muted, t.running, 0.7f) else t.muted
    val focusColor = if (precision) t.running else t.white

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(railWidth(side))
            .testTag(TIMELINE_TAG)
            .semantics {
                contentDescription = TimelineModel.LABEL
                isTraversalGroup = true
            },
    ) {
        val railHeight = constraints.maxHeight.toFloat()
        val railWidthPx = constraints.maxWidth.toFloat()
        if (railHeight <= 0f) return@BoxWithConstraints
        val cluster = TimelineModel.cluster(visibleCount, railHeight, unit)
        val fallback = if (activeIndex >= 0) activeIndex else max(0, size - 1)
        val geometry by rememberUpdatedState(RailGeometry(size, visible, cluster, railHeight, fallback))

        // Each dash eases to its length (170ms) and tone (150ms), keyed by prompt like the web's buttons.
        val widths = FloatArray(visibleCount)
        val opacities = FloatArray(visibleCount)
        visible.forEachIndexed { slot, index ->
            val point = points[index]
            key(point.turnId, point.blockId) {
                val isActive = index == activeIndex
                val distance = if (inspecting) abs(slot - focusSlot) else Int.MAX_VALUE
                val width by animateFloatAsState(
                    TimelineModel.markWidth(inspecting, isActive, distance),
                    if (reducedMotion) snap() else tween(170),
                    label = "mark-width",
                )
                val opacity by animateFloatAsState(
                    TimelineModel.markOpacity(inspecting, isActive, distance),
                    if (reducedMotion) snap() else tween(150),
                    label = "mark-opacity",
                )
                widths[slot] = width
                opacities[slot] = opacity
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(side, unit) {
                    awaitPointerEventScope {
                        var dragId: PointerId? = null
                        var startY = 0f
                        var startIndex = 0
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val g = geometry
                                val y = change.position.y
                                val mouse = change.type == PointerType.Mouse
                                val slot = TimelineModel.slotAt(y, g.visible.size, g.cluster, unit)
                                when (event.type) {
                                    PointerEventType.Scroll -> {
                                        if (slot < 0) continue
                                        val next = TimelineModel.wheelAnchor(
                                            browseAnchor, g.fallback, change.scrollDelta.y, event.keyboardModifiers.isShiftPressed, g.size,
                                        ) ?: continue
                                        change.consume()
                                        browseAnchor = next
                                        hoverSlot = slot
                                    }
                                    PointerEventType.Press -> {
                                        // A mouse browses by hover and clicks on release (the web ignores its pointerdown).
                                        if (mouse || dragId != null) continue
                                        val index = g.visible.getOrNull(slot) ?: continue
                                        change.consume()
                                        dragId = change.id
                                        startY = y
                                        startIndex = index
                                        scrubbing = true
                                        scrubSlot = slot
                                        updateScrubIndex(index)
                                    }
                                    PointerEventType.Release -> {
                                        if (dragId != null && change.id == dragId) {
                                            val cancelled = change.isConsumed
                                            change.consume()
                                            dragId = null
                                            finishScrub(jump = !cancelled)
                                        } else if (mouse && dragId == null) {
                                            val index = g.visible.getOrNull(slot)
                                            if (index != null) {
                                                change.consume()
                                                scrollToStoryPoint(index)
                                            }
                                        }
                                    }
                                    PointerEventType.Exit -> if (mouse && dragId == null) {
                                        hoverSlot = -1
                                        browseAnchor = null
                                    }
                                    else -> {
                                        if (dragId != null && change.id == dragId) {
                                            if (change.pressed) {
                                                change.consume()
                                                updateScrubIndex(TimelineModel.scrubIndex(startIndex, startY, y, g.size, g.railHeight, side, unit))
                                            }
                                        } else if (mouse && dragId == null) {
                                            hoverSlot = slot
                                        }
                                    }
                                }
                            }
                        } finally {
                            // pointercancel: the scrub ends where it is, without a jump.
                            if (dragId != null) finishScrub(jump = false)
                        }
                    }
                },
        ) {
            val inset = with(density) { railInset(side).toPx() }
            val markHeight = with(density) { 2.dp.toPx() }
            val hairline = with(density) { 1.dp.toPx() }
            val glow = with(density) { 3.dp.toPx() }
            val guideColor = if (inspecting) t.muted else t.lineStrong.copy(alpha = t.lineStrong.alpha * 0.8f)
            Canvas(Modifier.fillMaxSize()) {
                // The scale line the ticks hang from: always drawn, lit while inspecting.
                val guideTop = max(0f, cluster.top - cluster.pitch)
                val guideHeight = cluster.height + cluster.pitch * 2
                val guideLeft = if (side == TimelineSide.Right) railWidthPx - inset - hairline / 2 else inset - hairline / 2
                drawRect(guideColor, topLeft = Offset(guideLeft, guideTop), size = Size(hairline, guideHeight))

                visible.forEachIndexed { slot, index ->
                    val y = cluster.top + slot * cluster.pitch
                    val isActive = index == activeIndex
                    val focused = slot == keyboardSlot
                    val width = widths[slot] * unit
                    val left = if (side == TimelineSide.Right) railWidthPx - inset - width else inset
                    val top = y - markHeight / 2
                    val base = when {
                        focused -> focusColor
                        isActive -> t.violet
                        else -> markColor
                    }
                    if (focused) {
                        drawRoundRect(
                            t.focusGlow,
                            topLeft = Offset(left - glow, top - glow),
                            size = Size(width + glow * 2, markHeight + glow * 2),
                            cornerRadius = CornerRadius(glow + markHeight / 2),
                        )
                    }
                    // The free end is rounded (999px); the end on the scale line is square.
                    val color = base.copy(alpha = base.alpha * (if (focused) 1f else opacities[slot]))
                    val r = markHeight / 2
                    val squareLeft = if (side == TimelineSide.Right) left + width - min(r, width) else left
                    drawRoundRect(color, Offset(left, top), Size(width, markHeight), CornerRadius(r))
                    drawRect(color, Offset(squareLeft, top), Size(min(r, width), markHeight))
                }
            }

            // One button per visible mark (TalkBack, keyboard): its focus inspects the mark like hover.
            val slotHeightPx = if (cluster.pitch > 0f) cluster.pitch else TimelineModel.HOVER_SLOP * 2 * unit
            val slotHeight = with(density) { slotHeightPx.toDp() }
            visible.forEachIndexed { slot, index ->
                val point = points[index]
                key(point.turnId, point.blockId) {
                    val label = TimelineModel.markLabel(point, zone)
                    val isActive = index == activeIndex
                    Box(
                        Modifier
                            .offset { IntOffset(0, (cluster.top + slot * cluster.pitch - slotHeightPx / 2).roundToInt()) }
                            .fillMaxWidth()
                            .height(slotHeight)
                            .testTag(timelineMarkTag(index))
                            .semantics {
                                role = Role.Button
                                contentDescription = label
                                if (isActive) stateDescription = TimelineModel.CURRENT
                                onClick {
                                    scrollToStoryPoint(index)
                                    true
                                }
                            }
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    keyboardSlot = slot
                                    hoverSlot = slot
                                } else if (keyboardSlot == slot) {
                                    keyboardSlot = -1
                                    hoverSlot = -1
                                }
                            }
                            .onKeyEvent { event ->
                                val activate = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                                    event.key == Key.Spacebar || event.key == Key.DirectionCenter
                                if (activate && event.type == KeyEventType.KeyUp) scrollToStoryPoint(index)
                                activate
                            }
                            .focusable(),
                    )
                }
            }

            if (inspecting) {
                val focusedY = cluster.top + focusSlot * cluster.pitch
                TimelineBubble(
                    point = points[focusIndex],
                    index = focusIndex,
                    size = size,
                    side = side,
                    liveCopy = liveCopy,
                    zone = zone,
                    // top: clamp(4.5rem, var(--bubble-top), calc(100% - 4.5rem)), centred there.
                    centreY = TimelineModel.clamp(focusedY, 72f * unit, railHeight - 72f * unit),
                    railWidthPx = railWidthPx,
                )
            }
        }
    }
}

/** conversation-timeline.module.css `.bubble`: the prompt/reply readout beside the focused mark. */
@Composable
private fun TimelineBubble(
    point: TimelinePoint,
    index: Int,
    size: Int,
    side: TimelineSide,
    liveCopy: Boolean,
    zone: ZoneId,
    centreY: Float,
    railWidthPx: Float,
) {
    val t = LocalTetherTokens.current
    val reducedMotion = LocalReducedMotion.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val mobile = side == TimelineSide.Right
    val precision = t.skin.family == ThemeFamily.Precision
    // width: min(19rem, 100vw - 4.6rem) mobile; min(21rem, 100vw - 7rem) desktop.
    val width = if (mobile) minOf(304.dp, screenWidth - 73.6.dp) else minOf(336.dp, screenWidth - 112.dp)
    val shape = RoundedCornerShape(if (precision) 12.48.dp else 16.dp)
    val enter = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reducedMotion) enter.animateTo(1f, tween(150)) }
    val (time, counter) = TimelineModel.meta(index, size, point, zone)

    Column(
        Modifier
            .layout { measurable, _ ->
                val w = width.roundToPx().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w))
                layout(0, 0) {
                    // mobile: right: calc(100% - 0.1rem); desktop: left: calc(100% + 0.15rem).
                    val x = if (mobile) (1.6.dp.toPx() - w).roundToInt() else (railWidthPx + 2.4.dp.toPx()).roundToInt()
                    placeable.place(x, (centreY - placeable.height / 2f).roundToInt())
                }
            }
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 4.dp.toPx()
                val s = 0.985f + 0.015f * enter.value
                scaleX = s
                scaleY = s
            }
            .testTag(TIMELINE_BUBBLE_TAG)
            .cssSurface(
                shape = shape,
                background = t.graphiteRaised.copy(alpha = t.graphiteRaised.alpha * 0.95f),
                border = CssBorder(1.dp, oklabMix(t.line, t.seamLip, 0.84f)),
                shadows = listOf(
                    softShadow(y = 18.dp, blur = 42.dp, spread = (-24).dp, color = t.contact.copy(alpha = 0.55f)),
                    softShadow(y = 5.dp, blur = 16.dp, spread = (-10).dp, color = t.contact.copy(alpha = 0.28f)),
                    hardShadow(y = 1.dp, color = t.litStrong, inset = true),
                ),
            )
            .padding(
                start = if (mobile) 12.48.dp else 13.76.dp,
                end = if (mobile) 12.48.dp else 13.76.dp,
                top = if (mobile) 11.52.dp else 12.48.dp,
                bottom = if (mobile) 11.84.dp else 12.8.dp,
            ),
    ) {
        // The scale readout: when it was sent and "n / N", in mono (aria-hidden on the web).
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.4.dp).clearAndSetSemantics { },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (text in listOf(time, counter)) {
                Text(
                    text,
                    color = t.faint,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight(500),
                    fontSize = 9.92.sp,
                    letterSpacing = 0.04.em,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }
        Text(
            TimelineModel.promptText(point),
            color = t.white,
            fontFamily = Manrope,
            fontWeight = FontWeight(660),
            fontSize = if (mobile) 12.8.sp else 13.12.sp,
            lineHeight = 1.42.em,
            letterSpacing = (-0.006).em,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // `.reply`: a hairline (line at 62%), then the dot and the reply.
        Box(Modifier.padding(top = 7.36.dp).fillMaxWidth().height(1.dp).background(t.line.copy(alpha = t.line.alpha * 0.62f)))
        Row(Modifier.padding(top = 7.2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(5.44.dp)) {
                Box(
                    Modifier
                        .padding(top = 6.08.dp)
                        .size(3.84.dp)
                        .background((if (precision) t.running else t.muted).copy(alpha = 0.72f), CircleShape),
                )
            }
            Text(
                TimelineModel.replyText(point, liveCopy),
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = FontWeight(480),
                fontSize = if (mobile) 11.2.sp else 11.52.sp,
                lineHeight = 1.48.em,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
