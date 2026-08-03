package com.tether.app.ui.chat

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.reduce.StoryPoint
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherThemeFamily
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val VISIBLE_MARK_COUNT = 10
private const val DEFAULT_ANCHOR_SLOT = 4
private val MAGNIFIED_WIDTHS_DP = floatArrayOf(26f, 20f, 14f, 10f, 6f)
private val MAGNIFIED_OPACITIES = floatArrayOf(1f, 0.82f, 0.64f, 0.5f, 0.38f)

private fun clamp(v: Float, min: Float, max: Float): Float = max(min, min(max, v))
private fun clamp(v: Int, min: Int, max: Int): Int = max(min, min(max, v))

private fun getVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/**
 * Compact conversation index rail — Kotlin/Compose port of aidash's
 * ConversationTimeline. A fixed stack of up to 10 slots alongside the
 * transcript; drag to scrub through story points (operator prompts), release
 * to jump. A strong haptic fires each time the finger crosses into a new dot,
 * matching the Niagara Launcher alphabet-seeker feel.
 *
 * The slot geometry never moves; browsing changes which prompts occupy the
 * fixed slots. The active dot (nearest the transcript's 40% reading line) is
 * always at full opacity; the rest dim.
 */
@Composable
fun ConversationTimeline(
    storyPoints: List<StoryPoint>,
    listState: LazyListState,
    storyPointToLazyIndex: Map<Int, Int>,
    itemKeyToSpIndex: Map<Any, Int>,
    modifier: Modifier = Modifier,
) {
    if (storyPoints.isEmpty()) return

    val t = LocalTetherTokens.current
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val vibrator = remember { getVibrator(context) }
    val lastHapticIndex = remember { mutableIntStateOf(-1) }

    fun fireHaptic() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // PRIMITIVE_THUD is the heaviest primitive (deep impact); scale 1.0 = max.
            if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                        .compose()
                )
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 45, 25, 45), -1))
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No composition API on Q; use the strongest predefined effect.
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 45, 25, 45), -1)
        }
    }

    fun scrollToStoryPoint(index: Int) {
        val lazyIndex = storyPointToLazyIndex[index] ?: return
        scope.launch {
            val info = listState.layoutInfo
            val vh = info.viewportEndOffset - info.viewportStartOffset
            val offset = (vh * 0.28f).roundToInt()
            if (reducedMotion) listState.scrollToItem(lazyIndex, offset)
            else listState.animateScrollToItem(lazyIndex, offset)
        }
    }

    // Scrub state — survives recomposition.
    var browseAnchor by remember { mutableStateOf<Int?>(null) }
    var scrubIndex by remember { mutableIntStateOf(-1) }
    var scrubSlot by remember { mutableIntStateOf(DEFAULT_ANCHOR_SLOT) }
    var scrubbing by remember { mutableStateOf(false) }
    var pointerY by remember { mutableStateOf(0f) }

    fun updateScrubIndex(index: Int) {
        if (index < 0 || index >= storyPoints.size) return
        scrubIndex = index
        browseAnchor = index
        if (lastHapticIndex.value != index) {
            lastHapticIndex.value = index
            fireHaptic()
        }
    }

    fun finishScrub(jump: Boolean) {
        val idx = scrubIndex
        scrubbing = false
        scrubIndex = -1
        scrubSlot = DEFAULT_ANCHOR_SLOT
        lastHapticIndex.value = -1
        pointerY = 0f
        if (jump && idx >= 0) scrollToStoryPoint(idx)
    }

    // Track the story point nearest the transcript's 40% reading line.
    val activeIndex by remember(itemKeyToSpIndex) {
        derivedStateOf {
            val info = listState.layoutInfo
            val vh = info.viewportEndOffset - info.viewportStartOffset
            val target = info.viewportStartOffset + vh * 0.4f
            var nearest = -1
            var minDist = Float.MAX_VALUE
            for (item in info.visibleItemsInfo) {
                val sp = itemKeyToSpIndex[item.key] ?: continue
                val center = item.offset + item.size / 2f
                val dist = abs(center - target)
                if (dist < minDist) { minDist = dist; nearest = sp }
            }
            nearest
        }
    }

    // Window geometry: which story points occupy the fixed slots.
    val fallbackIndex = if (activeIndex >= 0) activeIndex else max(0, storyPoints.size - 1)
    val anchorIndex = clamp(browseAnchor ?: fallbackIndex, 0, max(0, storyPoints.size - 1))
    val visibleCount = min(VISIBLE_MARK_COUNT, storyPoints.size)
    val anchorSlotTarget = if (scrubbing) scrubSlot else DEFAULT_ANCHOR_SLOT
    val maximumStart = max(0, storyPoints.size - visibleCount)
    val windowStart = clamp(anchorIndex - anchorSlotTarget, 0, maximumStart)
    val visibleIndices = (0 until visibleCount).map { windowStart + it }

    val focusSlot = if (scrubbing) visibleIndices.indexOf(scrubIndex) else -1
    val focusIndex = if (focusSlot >= 0) visibleIndices.getOrNull(focusSlot) ?: -1 else -1
    val inspecting = focusIndex >= 0 && focusIndex < storyPoints.size

    // Theme-aware mark colours.
    val isMachineLike = t.family == TetherThemeFamily.Machine || t.family == TetherThemeFamily.Precision
    val markColor = if (isMachineLike) lerp(t.lineStrong, t.running, 0.22f) else lerp(t.lineStrong, t.ink, 0.16f)
    val activeColor = if (isMachineLike) t.running else t.white

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(54.dp),
    ) {
        val railHeightPx = constraints.maxHeight.toFloat()
        val railWidthPx = constraints.maxWidth.toFloat()

        val clusterEdgePx = with(density) { 14.dp.toPx() }
        val markPitchPreferredPx = with(density) { 10.dp.toPx() }
        val markHeightPx = with(density) { 2.dp.toPx() }
        val markPaddingPx = with(density) { 13.dp.toPx() }
        val hoverSlopPx = with(density) { 18.dp.toPx() }

        val availableClusterHeight = max(0f, railHeightPx - clusterEdgePx * 2)
        val pitch = if (visibleCount > 1)
            min(markPitchPreferredPx, availableClusterHeight / (visibleCount - 1)) else 0f
        val clusterHeight = pitch * max(0, visibleCount - 1)
        val centeredTop = (railHeightPx - clusterHeight) / 2f
        val maxTop = max(clusterEdgePx, railHeightPx - clusterEdgePx - clusterHeight)
        val clusterTop = clamp(centeredTop, clusterEdgePx, maxTop)

        val focusedY = if (inspecting && focusSlot >= 0) clusterTop + focusSlot * pitch else -1000f

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(storyPoints.size, railHeightPx) {
                    if (storyPoints.isEmpty()) return@pointerInput

                    val touchSlop = viewConfiguration.touchSlop

                    fun slotFromY(y: Float): Int {
                        if (visibleCount == 0) return -1
                        if (visibleCount == 1 || pitch <= 0f)
                            return if (abs(y - clusterTop) <= hoverSlopPx) 0 else -1
                        val slot = clamp(((y - clusterTop) / pitch).roundToInt(), 0, visibleCount - 1)
                        val slotY = clusterTop + slot * pitch
                        return if (abs(y - slotY) <= hoverSlopPx) slot else -1
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downY = down.position.y
                        pointerY = downY
                        val slot = slotFromY(downY)
                        if (slot < 0) return@awaitEachGesture
                        val startIndex = visibleIndices.getOrNull(slot) ?: return@awaitEachGesture

                        var isDragging = false
                        var lastSeenIndex = startIndex

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp() || !change.pressed) {
                                if (isDragging) finishScrub(jump = true)
                                else if (lastSeenIndex in 0 until storyPoints.size) scrollToStoryPoint(lastSeenIndex)
                                break
                            }
                            pointerY = change.position.y
                            val dy = downY - change.position.y
                            if (!isDragging && abs(dy) > touchSlop) {
                                isDragging = true
                            }
                            if (isDragging) {
                                change.consume()
                                scrubbing = true
                                scrubSlot = slot
                                // Compact span: a short thumb sweep covers the whole
                                // history. 200px ≈ a comfortable arc; scaled up only
                                // when there are very few points so each step stays
                                // reachable.
                                val span = max(200f, railHeightPx * 0.45f)
                                // Right-edge rail: drag up browses older prompts.
                                val sign = -1f
                                val delta = sign * dy
                                val indexDelta = ((delta / span) * max(1, storyPoints.size - 1)).roundToInt()
                                val next = clamp(startIndex + indexDelta, 0, storyPoints.size - 1)
                                lastSeenIndex = next
                                updateScrubIndex(next)
                            }
                        }
                    }
                }
        ) {
            // Marks + guide line
            Canvas(Modifier.fillMaxSize()) {
                if (inspecting) {
                    val guideX = railWidthPx - markPaddingPx - 2f
                    drawLine(
                        color = t.lineStrong.copy(alpha = 0.72f),
                        start = Offset(guideX, max(0f, clusterTop - pitch)),
                        end = Offset(guideX, clusterTop + clusterHeight + pitch),
                        strokeWidth = 1f,
                    )
                }

                visibleIndices.forEachIndexed { slot, index ->
                    val y = clusterTop + slot * pitch
                    val isActive = index == activeIndex
                    val distance = if (inspecting) abs(slot - focusSlot) else Int.MAX_VALUE
                    val distClamped = min(distance, MAGNIFIED_WIDTHS_DP.size - 1)

                    val widthDp = if (inspecting) MAGNIFIED_WIDTHS_DP[distClamped] else 6f
                    val widthPx = with(this) { widthDp.dp.toPx() }
                    val opacity = if (inspecting) {
                        max(if (isActive) 1f else 0f, MAGNIFIED_OPACITIES[distClamped])
                    } else {
                        if (isActive) 1f else 0.38f
                    }
                    val color = if (isActive) activeColor else markColor

                    val markRight = railWidthPx - markPaddingPx
                    val markLeft = markRight - widthPx
                    val markTop = y - markHeightPx / 2f

                    drawRoundRect(
                        color = color.copy(alpha = opacity),
                        topLeft = Offset(markLeft, markTop),
                        size = Size(widthPx, markHeightPx),
                        cornerRadius = CornerRadius(markHeightPx, markHeightPx),
                    )

                    if (isActive && isMachineLike) {
                        drawRoundRect(
                            color = activeColor.copy(alpha = 0.08f),
                            topLeft = Offset(markLeft - 2f, markTop - 2f),
                            size = Size(widthPx + 4f, markHeightPx + 4f),
                            cornerRadius = CornerRadius(markHeightPx + 2f, markHeightPx + 2f),
                        )
                    }
                }
            }

            // Snippet bubble (during scrub only) — hugs the rail, follows the thumb.
            // Width adapts to the available space left of the rail, capped so ~6 words
            // of the prompt fit. maxLines raised so longer prompts are readable.
            if (inspecting && focusIndex >= 0) {
                val point = storyPoints[focusIndex]
                var bubbleHeight by remember { mutableStateOf(0) }
                var bubbleWidthPx by remember { mutableStateOf(0f) }

                val bubbleAlpha by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = if (reducedMotion) snap() else tween(130),
                    label = "bubble-in",
                )

                val bubbleTargetY = if (scrubbing) pointerY else focusedY
                val animatedBubbleY by animateFloatAsState(
                    targetValue = bubbleTargetY,
                    animationSpec = if (reducedMotion) snap() else tween(80),
                    label = "bubble-y",
                )

                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .widthIn(min = 200.dp, max = 320.dp)
                        .alpha(bubbleAlpha)
                        .onSizeChanged {
                            bubbleHeight = it.height
                            bubbleWidthPx = it.width.toFloat()
                        }
                        .offset {
                            val gapPx = with(density) { 4.dp.toPx() }
                            val x = (-(bubbleWidthPx + gapPx)).roundToInt()
                            val yRaw = (animatedBubbleY - bubbleHeight / 2f)
                            val maxY = (railHeightPx - bubbleHeight).roundToInt()
                            val y = yRaw.roundToInt().coerceIn(0, if (maxY > 0) maxY else 0)
                            IntOffset(x, y)
                        }
                        .background(t.graphiteRaised, RoundedCornerShape(16.dp))
                        .border(1.dp, t.line, RoundedCornerShape(16.dp))
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(16.dp),
                            ambientColor = t.contact.copy(alpha = 0.4f),
                            spotColor = t.contact.copy(alpha = 0.3f),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        point.prompt.ifEmpty { "Attachment sent" },
                        color = t.white,
                        fontFamily = Manrope,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.height(1.dp).fillMaxWidth().background(t.line.copy(alpha = 0.6f)))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        point.reply.ifEmpty { "Agent reply pending\u2026" },
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}