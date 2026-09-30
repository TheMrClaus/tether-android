package com.tether.app.ui.chat

import com.tether.app.client.LabelText
import com.tether.app.protocol.helpers.ConversationStoryPoints
import com.tether.app.protocol.tree.JsObj
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which edge the rail docks to: the web's `.left` (desktop, >= 64rem) or `.right` (mobile). */
internal enum class TimelineSide { Left, Right }

/** One operator prompt on the rail, its texts already cleaned for display. */
internal data class TimelinePoint(val turnId: String, val blockId: String, val prompt: String, val reply: String, val ts: Double?)

/** The rail's fixed slot stack, in px: the pitch between slots, the first slot's y, the stack's height. */
internal data class TimelineCluster(val pitch: Float, val top: Float, val height: Float)

/**
 * T6.5: the pure half of the conversation timeline (tether components/conversation-timeline.tsx at
 * PARITY_BASE): which prompts occupy the fixed slots, the slot geometry, the dash lengths, the
 * scrub and wheel steps, and the copy. Lengths are dp (the web's CSS px); functions that take
 * [unit] (px per dp) work in px.
 */
internal object TimelineModel {
    /**
     * The owner's wider-bubble limits (0.5.0.1, decision logged 2026-09-27); the web's are 220/260.
     * The port itself ([ConversationStoryPoints]) is faithful and takes them as parameters.
     */
    const val PROMPT_MAX = 270
    const val REPLY_MAX = 320

    // conversation-timeline.tsx:22-26
    const val VISIBLE_MARK_COUNT = 10
    const val DEFAULT_ANCHOR_SLOT = 4
    const val PREFERRED_MARK_PITCH = 10f
    const val CLUSTER_EDGE = 14f
    const val HOVER_SLOP = 18f

    // conversation-timeline.tsx:52-57: graduations — resting tick, needle, hover magnification.
    const val REST_WIDTH = 10f
    const val ACTIVE_WIDTH = 16f
    val MAGNIFIED_WIDTHS = floatArrayOf(28f, 22f, 16f, 12f, REST_WIDTH)
    val MAGNIFIED_OPACITIES = floatArrayOf(1f, 0.86f, 0.72f, 0.6f, 0.5f)
    const val REST_OPACITY = 0.5f

    /** The web's wheel step divisor (`deltaY / 36`) and Chrome's pixel delta for one wheel notch. */
    const val WHEEL_STEP_PX = 36f
    const val WHEEL_NOTCH_PX = 100f

    /** The transcript's reading line (active prompt) and where a jump lands, as fractions of its height. */
    const val READING_LINE = 0.4f
    const val JUMP_LINE = 0.28f

    const val LABEL = "Conversation prompts"
    const val ATTACHMENT_SENT = "Attachment sent"
    const val REPLY_PENDING = "Agent reply pending…"

    /** T13.2: a copy that is not live must not say a reply is on its way. */
    const val REPLY_NOT_SAVED = "No reply in the saved copy"
    const val NO_TIME = "—"
    const val CURRENT = "Current step"

    /** The story points of [tree] (T2.2's faithful port at the owner's limits), cleaned for display. */
    fun points(tree: JsObj?): List<TimelinePoint> =
        ConversationStoryPoints.storyPointsFromSession(tree, promptMax = PROMPT_MAX, replyMax = REPLY_MAX).map { p ->
            TimelinePoint(p.turnId, p.blockId, LabelText.clean(p.prompt, PROMPT_MAX), LabelText.clean(p.reply, REPLY_MAX), p.ts)
        }

    /** The prompt nearest the anchor: the one being browsed, else the active one, else the newest. */
    fun anchorIndex(size: Int, activeIndex: Int, browseAnchor: Int?): Int {
        val fallback = if (activeIndex >= 0) activeIndex else max(0, size - 1)
        return clamp(browseAnchor ?: fallback, 0, max(0, size - 1))
    }

    /** conversation-timeline.tsx:215-221: the prompt indices in the fixed slots, top to bottom. */
    fun window(size: Int, activeIndex: Int, browseAnchor: Int?, scrubbing: Boolean, scrubSlot: Int): List<Int> {
        val visibleCount = min(VISIBLE_MARK_COUNT, size)
        val anchorSlotTarget = if (scrubbing) scrubSlot else DEFAULT_ANCHOR_SLOT
        val maximumStart = max(0, size - visibleCount)
        val start = clamp(anchorIndex(size, activeIndex, browseAnchor) - anchorSlotTarget, 0, maximumStart)
        return List(visibleCount) { start + it }
    }

    /** conversation-timeline.tsx:222-229: the stack is centred in the rail, 14 from either end. */
    fun cluster(visibleCount: Int, railHeight: Float, unit: Float): TimelineCluster {
        val edge = CLUSTER_EDGE * unit
        val available = max(0f, railHeight - edge * 2)
        val pitch = if (visibleCount > 1) min(PREFERRED_MARK_PITCH * unit, available / (visibleCount - 1)) else 0f
        val height = pitch * max(0, visibleCount - 1)
        val maximumTop = max(edge, railHeight - edge - height)
        val centred = (railHeight - height) / 2f
        return TimelineCluster(pitch, clamp(centred, edge, maximumTop), height)
    }

    /** conversation-timeline.tsx:231-241: the slot under [y] (rail px), or -1 past the hover slop. */
    fun slotAt(y: Float, visibleCount: Int, cluster: TimelineCluster, unit: Float): Int {
        if (visibleCount == 0) return -1
        val slop = HOVER_SLOP * unit
        if (visibleCount == 1 || cluster.pitch <= 0f) return if (abs(y - cluster.top) <= slop) 0 else -1
        val slot = clamp(((y - cluster.top) / cluster.pitch).roundToInt(), 0, visibleCount - 1)
        val slotY = cluster.top + slot * cluster.pitch
        return if (abs(y - slotY) <= slop) slot else -1
    }

    /** conversation-timeline.tsx:352-357: a dash's length (dp); the needle never shrinks below its rest. */
    fun markWidth(inspecting: Boolean, isActive: Boolean, distance: Int): Float =
        if (inspecting) {
            max(if (isActive) ACTIVE_WIDTH else 0f, MAGNIFIED_WIDTHS[min(distance, MAGNIFIED_WIDTHS.size - 1)])
        } else if (isActive) ACTIVE_WIDTH else REST_WIDTH

    fun markOpacity(inspecting: Boolean, isActive: Boolean, distance: Int): Float =
        if (inspecting) {
            max(if (isActive) 1f else 0f, MAGNIFIED_OPACITIES[min(distance, MAGNIFIED_OPACITIES.size - 1)])
        } else if (isActive) 1f else REST_OPACITY

    /**
     * conversation-timeline.tsx:286-299: the prompt a drag from [startY] to [y] (px) reaches. Right
     * rail (mobile): drag up browses older prompts over a tighter span; left rail: drag up browses newer.
     */
    fun scrubIndex(startIndex: Int, startY: Float, y: Float, size: Int, railHeight: Float, side: TimelineSide, unit: Float): Int {
        val mobile = side == TimelineSide.Right
        val span = max((if (mobile) 120f else 140f) * unit, railHeight * (if (mobile) 0.32f else 0.72f))
        val sign = if (mobile) -1f else 1f
        val delta = sign * (startY - y)
        val indexDelta = ((delta / span) * max(1, size - 1)).roundToInt()
        return clamp(startIndex + indexDelta, 0, size - 1)
    }

    /**
     * conversation-timeline.tsx:262-271: the browse anchor after a wheel turn of [notches] (positive
     * = down = newer). Shift pages by the visible count. Null: the wheel does nothing here.
     */
    fun wheelAnchor(current: Int?, fallback: Int, notches: Float, shift: Boolean, size: Int): Int? {
        val visibleCount = min(VISIBLE_MARK_COUNT, size)
        if (size <= visibleCount || notches == 0f) return null
        val deltaY = notches * WHEEL_NOTCH_PX
        val magnitude = max(1, (abs(deltaY) / WHEEL_STEP_PX).roundToInt())
        val direction = if (deltaY > 0) 1 else -1
        val step = if (shift) visibleCount else magnitude
        return clamp((current ?: fallback) + direction * step, 0, size - 1)
    }

    fun timeLabel(ts: Double?, zone: ZoneId): String = ConversationStoryPoints.storyPointTimeLabel(ts, zone)

    /** conversation-timeline.tsx:375: the mark's accessible name. */
    fun markLabel(point: TimelinePoint, zone: ZoneId): String {
        val time = timeLabel(point.ts, zone)
        return "Jump to your message${if (time.isNotEmpty()) " at $time" else ""}: ${point.prompt.ifEmpty { "attachment" }}"
    }

    /** The bubble's readout: when it was sent ("—" unknown), and "n / N". */
    fun meta(index: Int, size: Int, point: TimelinePoint, zone: ZoneId): Pair<String, String> =
        timeLabel(point.ts, zone).ifEmpty { NO_TIME } to "${index + 1} / $size"

    fun promptText(point: TimelinePoint): String = point.prompt.ifEmpty { ATTACHMENT_SENT }

    fun replyText(point: TimelinePoint, liveCopy: Boolean): String =
        point.reply.ifEmpty { if (liveCopy) REPLY_PENDING else REPLY_NOT_SAVED }

    /**
     * conversation-timeline.tsx:138-160: the prompt whose row top is nearest the reading line. The
     * web measures every prompt, on screen or not; a lazy list only lays out what is on screen, so
     * when no prompt row is ([rows] empty, e.g. mid-way through a long reply) the nearest is the
     * last prompt above the first visible row ([firstVisibleRow], a lazy index), else the first
     * one below it ([promptRows]: story point -> lazy index). -1: no prompt at all.
     */
    fun activeIndex(
        rows: List<Pair<Int, Float>>,
        viewportHeight: Float,
        size: Int,
        firstVisibleRow: Int = -1,
        promptRows: Map<Int, Int> = emptyMap(),
    ): Int {
        val onScreen = nearestOnScreen(rows, viewportHeight, size)
        if (onScreen >= 0 || firstVisibleRow < 0) return onScreen
        var above = -1
        var aboveRow = Int.MIN_VALUE
        var below = -1
        var belowRow = Int.MAX_VALUE
        for ((index, row) in promptRows) {
            if (index < 0 || index >= size) continue
            if (row < firstVisibleRow && row > aboveRow) {
                above = index
                aboveRow = row
            } else if (row > firstVisibleRow && row < belowRow) {
                below = index
                belowRow = row
            }
        }
        return if (above >= 0) above else below
    }

    private fun nearestOnScreen(rows: List<Pair<Int, Float>>, viewportHeight: Float, size: Int): Int {
        val target = viewportHeight * READING_LINE
        var nearest = -1
        var distance = Float.POSITIVE_INFINITY
        for ((index, top) in rows) {
            if (index < 0 || index >= size) continue
            val d = abs(top - target)
            if (d < distance) {
                distance = d
                nearest = index
            }
        }
        return nearest
    }

    /** conversation-timeline.tsx:176-182: smooth only under 2 viewports of travel, never with reduced motion. */
    fun smoothJump(distance: Float, viewportHeight: Float, reducedMotion: Boolean): Boolean =
        !reducedMotion && distance < viewportHeight * 2

    fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
    fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
}
