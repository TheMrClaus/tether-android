package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.tether.app.client.NoticeResult
import com.tether.app.client.SessionControl
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exact owner-screenshot fixture reused by the collision, rail-navigation and golden regressions. */
internal object NoticeDismissTimelineRegressionFixtures {
    const val PROMPT = "Generate 10 game concepts and keep the strongest one."
    const val NAVIGATION_MARK_INDEX = 6

    val stackedNotices: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = ChatFixtures.T_IDLE) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = ChatFixtures.T_IDLE) { put("text", PROMPT) },
            *listOf(2, 3, 4, 5).map { attempt ->
                ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + attempt) {
                    put("noticeId", "retry-$attempt")
                    put("level", "warning")
                    put("code", "retrying")
                    put("message", "Reconnecting… $attempt/5")
                }
            }.toTypedArray(),
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + 6) {
                put("noticeId", "transport-fallback")
                put("level", "warning")
                put("code", "warning")
                put(
                    "message",
                    "Falling back from WebSockets to HTTPS transport. stream disconnected before completion: " +
                        "websocket closed by server before response.completed",
                )
            },
        )
    }

    /** The owner collision stack with enough real transcript on both sides to reach the 28% line. */
    val scrollableStackedNotices: ChatFixtures.Folded by lazy {
        val before = (1..NAVIGATION_MARK_INDEX).flatMap { index ->
            ChatFixtures.turn(
                "before-$index",
                "Earlier prompt $index",
                "Earlier reply $index\n\nwith enough preceding content to make the collision prompt scrollable.",
                ChatFixtures.T_IDLE + index * 60_000L,
            ).toList()
        }
        val collisionTs = ChatFixtures.T_IDLE + (NAVIGATION_MARK_INDEX + 1) * 60_000L
        val collision = buildList {
            add(ev("turn_started", "collision", ts = collisionTs) { put("idempotencyKey", "k-collision") })
            add(ev("user_message_accepted", "collision", ts = collisionTs) { put("text", PROMPT) })
            listOf(2, 3, 4, 5).forEach { attempt ->
                add(ev("provider_notice", "collision", ts = collisionTs + attempt) {
                    put("noticeId", "navigation-retry-$attempt")
                    put("level", "warning")
                    put("code", "retrying")
                    put("message", "Reconnecting… $attempt/5")
                })
            }
            add(ev("provider_notice", "collision", ts = collisionTs + 6) {
                put("noticeId", "navigation-transport-fallback")
                put("level", "warning")
                put("code", "warning")
                put(
                    "message",
                    "Falling back from WebSockets to HTTPS transport. stream disconnected before completion: " +
                        "websocket closed by server before response.completed",
                )
            })
            add(ev("turn_end", "collision", ts = collisionTs + 7) { put("outcome", "ok") })
        }
        val after = (1..6).flatMap { index ->
            ChatFixtures.turn(
                "after-$index",
                "Later prompt $index",
                "Later reply $index\n\nwith enough following content to keep the collision prompt off the clamp.",
                collisionTs + (index + 1) * 60_000L,
            ).toList()
        }
        ChatFixtures.fold(*(before + collision + after).toTypedArray())
    }
}

/**
 * ta-cf59 regression for the real ChatTranscript sibling overlay. It discovers each painted X
 * after layout and root-injects the centre, cardinals and corners of the real 48dp/44dp target.
 */
@OptIn(ExperimentalFoundationApi::class)
abstract class NoticeDismissTimelineRegression {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    protected abstract val expectedFontScale: Float

    private val recorder = NoticeFixtures.Recorder()
    private lateinit var listState: LazyListState
    private lateinit var menu: SelectionMenuProbe

    private enum class Kind(
        val fixture: ChatFixtures.Folded,
        val rowTag: String,
        val label: String,
        val targetDp: Float,
        val iconViewportDp: Float,
        val paintedStrokePx: Float,
    ) {
        Notice(
            NoticeDismissTimelineRegressionFixtures.stackedNotices,
            "provider-notice",
            "Dismiss notice",
            48f,
            16f,
            26f,
        ),
        Scheduled(
            NoticeFixtures.scheduled,
            "rate-limit-scheduled",
            "Cancel scheduled resume",
            44f,
            13f,
            20f,
        ),
    }

    private data class RedRootMap(val paintedCentre: Offset, val targetRight: Offset)

    private data class PaintedMap(
        val target: Rect,
        val paintedCentre: Offset,
        val points: List<Pair<String, Offset>>,
    )

    private data class Rendered(
        val map: PaintedMap,
        val row: Rect,
        val rail: Rect?,
    )

    @Test fun noticeTimelineOffKeepsThe48DpMapAndPriorPosition() =
        probe(Kind.Notice, showTimeline = false, LayoutDirection.Ltr)

    @Test fun noticeTimelineOnMovesOnlyIts48DpActionSlotClearOfTheRail() =
        probe(Kind.Notice, showTimeline = true, LayoutDirection.Ltr)

    @Test fun noticeRtlTimelineOnKeepsTheActionOnThePhysicalLeftWithoutClearance() =
        probe(Kind.Notice, showTimeline = true, LayoutDirection.Rtl)

    @Test fun scheduledTimelineOffKeepsThe44Dp13DpMapAndPriorPosition() =
        probe(Kind.Scheduled, showTimeline = false, LayoutDirection.Ltr)

    @Test fun scheduledTimelineOnMovesOnlyIts44DpActionSlotClearOfTheRail() =
        probe(Kind.Scheduled, showTimeline = true, LayoutDirection.Ltr)

    @Test fun scheduledRtlTimelineOnKeepsTheActionOnThePhysicalLeftWithoutClearance() =
        probe(Kind.Scheduled, showTimeline = true, LayoutDirection.Rtl)

    @Test
    fun theOldNoticeCentreAndRightCoordinatesKeepTheirImmutableRailSlotMeaning() {
        assertPhysicalConfiguration()
        setTranscript(Kind.Notice, showTimeline = true, LayoutDirection.Ltr)
        val redMap = when (expectedFontScale) {
            1f -> RedRootMap(Offset(964.5f, 1213f), Offset(1028f, 1213f))
            1.3f -> RedRootMap(Offset(964.5f, 1271f), Offset(1028f, 1271f))
            else -> error("No immutable red map for font scale $expectedFontScale")
        }
        val oldPoints = listOf(
            "old-painted-centre" to redMap.paintedCentre,
            "old-target-right" to redMap.targetRight,
        )
        oldPoints.forEach { (name, point) ->
            val rendered = render(Kind.Notice)
            val rail = checkNotNull(rendered.rail)
            assertTrue("$name no longer lies in the rail: point=$point rail=$rail", rail.contains(point))
            val promptSlot = rule.onNodeWithTag(timelineMarkTag(0)).fetchSemanticsNode().boundsInRoot
            val slot = if (promptSlot.contains(point)) 0 else null
            val before = recorder.dismissed.toList()
            val initialScroll = scrollPosition()
            rule.onRoot().performTouchInput { click(point) }
            settle()
            assertEquals("$name dismissed after the X moved", before, recorder.dismissed)
            if (slot == null) {
                assertEquals("$name navigated outside the mark stack", initialScroll, scrollPosition())
            } else {
                assertEquals("$name resolved to the wrong prompt slot", 0, slot)
                assertTrue("$name did not navigate to its prompt", initialScroll != scrollPosition())
                val transcript = rule.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
                val prompt = rule.onNodeWithText(NoticeDismissTimelineRegressionFixtures.PROMPT)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "$name navigated to the wrong prompt: prompt=$prompt transcript=$transcript",
                    prompt.bottom > transcript.top && prompt.top < transcript.bottom,
                )
            }
            assertTrue("$name fired an adjacent action", recorder.controls.isEmpty())
            assertTrue("$name produced a refusal", recorder.refusals.isEmpty())
            assertEquals("$name fired take-over", 0, recorder.takeOvers)
            assertNull("$name opened text selection", menu.shown)
        }
    }

    private fun probe(kind: Kind, showTimeline: Boolean, direction: LayoutDirection) {
        assertPhysicalConfiguration()
        setTranscript(kind, showTimeline, direction)
        val rendered = render(kind)
        val density = rule.density.density
        val clearance = if (showTimeline && direction == LayoutDirection.Ltr) 38f else 0f

        if (showTimeline) {
            assertNotNull("timeline-on fixture did not draw the rail", rendered.rail)
            val rail = checkNotNull(rendered.rail)
            assertEquals("phone rail width", 53.6f, rail.width / density, 0.5f)
            assertTrue("target still intersects rail: ${rendered.map.target} vs $rail", rendered.map.target.right <= rail.left + 1f)
        } else {
            assertNull("timeline-off fixture unexpectedly drew the rail", rendered.rail)
        }

        if (direction == LayoutDirection.Ltr) {
            val existingEndInset = if (kind == Kind.Notice) 4f else 0f
            assertEquals(
                "${kind.name} trailing slot clearance",
                existingEndInset + clearance,
                (rendered.row.right - rendered.map.target.right) / density,
                0.75f,
            )
        } else {
            val existingStartInset = if (kind == Kind.Notice) 4f else 0f
            assertEquals(
                "${kind.name} RTL physical-left position",
                existingStartInset,
                (rendered.map.target.left - rendered.row.left) / density,
                0.75f,
            )
        }

        val initialScroll = scrollPosition()
        rendered.map.points.forEach { (pointName, point) ->
            val beforeDismiss = recorder.dismissed.toList()
            val beforeControls = recorder.controls.toList()
            rule.onRoot().performTouchInput { click(point) }
            settle(32L)
            when (kind) {
                Kind.Notice -> {
                    assertEquals(
                        "$direction timeline=$showTimeline $pointName must dismiss exactly once",
                        beforeDismiss + "provider_notice:t1:transport-fallback",
                        recorder.dismissed,
                    )
                    assertEquals("$pointName fired scheduled cancel", beforeControls, recorder.controls)
                }
                Kind.Scheduled -> {
                    assertEquals("$pointName fired notice dismiss", beforeDismiss, recorder.dismissed)
                    assertEquals(
                        "$direction timeline=$showTimeline $pointName must cancel exactly once",
                        beforeControls + SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, "dismiss"),
                        recorder.controls,
                    )
                }
            }
            assertEquals("$pointName navigated or scrolled the transcript", initialScroll, scrollPosition())
            assertNull("$pointName opened text selection", menu.shown)
            assertTrue("$pointName produced a refusal", recorder.refusals.isEmpty())
            assertEquals("$pointName fired take-over", 0, recorder.takeOvers)
        }
        assertEquals("nine-point map", 9, if (kind == Kind.Notice) recorder.dismissed.size else recorder.controls.size)
    }

    private fun setTranscript(kind: Kind, showTimeline: Boolean, direction: LayoutDirection) {
        recorder.dismissed.clear()
        recorder.controls.clear()
        recorder.refusals.clear()
        recorder.takeOvers = 0
        listState = LazyListState()
        menu = SelectionMenuProbe()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalTextContextMenuToolbarProvider provides menu,
            ) {
                ChatHost(TetherSkin.StudioDark) {
                    ChatTranscript(
                        projection = kind.fixture.projection,
                        tree = kind.fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        richCodex = kind == Kind.Notice,
                        showTimeline = showTimeline,
                        notices = recorder.actions(),
                    )
                }
            }
        }
        settle()
    }

    private fun render(kind: Kind): Rendered {
        val lastIndex = rule.runOnIdle { listState.layoutInfo.totalItemsCount - 1 }
        assertTrue("fixture produced no transcript rows", lastIndex >= 0)
        rule.onNodeWithTag("chat-transcript").performScrollToIndex(lastIndex)
        settle()

        val target = if (kind == Kind.Notice) {
            val nodes = rule.onAllNodesWithContentDescription(kind.label).fetchSemanticsNodes()
            val bottom = nodes.indices.maxBy { nodes[it].boundsInRoot.top }
            rule.onAllNodesWithContentDescription(kind.label)[bottom]
        } else {
            rule.onNodeWithContentDescription(kind.label)
        }
        val rows = rule.onAllNodesWithTag(kind.rowTag).fetchSemanticsNodes()
        val row = rows.maxBy { it.boundsInRoot.top }.boundsInRoot
        val rail = rule.onAllNodesWithTag(TIMELINE_TAG).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        return Rendered(paintedMap(target, kind), row, rail)
    }

    private fun paintedMap(target: SemanticsNodeInteraction, kind: Kind): PaintedMap {
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertEquals("${kind.name} target width", kind.targetDp, bounds.width / density, 0.5f)
        assertEquals("${kind.name} target height", kind.targetDp, bounds.height / density, 0.5f)

        val pixels = target.captureToImage().toPixelMap()
        val background = pixels[(4f * density).roundToInt(), (4f * density).roundToInt()]
        val centreX = pixels.width / 2
        val centreY = pixels.height / 2
        val radius = (10f * density).roundToInt()
        val painted = buildList {
            for (y in (centreY - radius).coerceAtLeast(0)..(centreY + radius).coerceAtMost(pixels.height - 1)) {
                for (x in (centreX - radius).coerceAtLeast(0)..(centreX + radius).coerceAtMost(pixels.width - 1)) {
                    if (colourDistance(pixels[x, y], background) > 0.08f) add(x to y)
                }
            }
        }
        assertTrue("${kind.name} pixel scan found no painted X", painted.isNotEmpty())
        val minX = painted.minOf { it.first }
        val maxX = painted.maxOf { it.first }
        val minY = painted.minOf { it.second }
        val maxY = painted.maxOf { it.second }
        val paintedWidthPx = (maxX - minX + 1).toFloat()
        val paintedHeightPx = (maxY - minY + 1).toFloat()
        val viewportPx = kind.iconViewportDp * density
        assertTrue("${kind.name} painted stroke filled its ${kind.iconViewportDp}dp viewport", paintedWidthPx < viewportPx)
        assertTrue("${kind.name} painted stroke filled its ${kind.iconViewportDp}dp viewport", paintedHeightPx < viewportPx)
        assertTrue(
            "${kind.name} painted stroke escaped its centred ${kind.iconViewportDp}dp viewport",
            minX >= centreX - viewportPx / 2f && maxX <= centreX + viewportPx / 2f &&
                minY >= centreY - viewportPx / 2f && maxY <= centreY + viewportPx / 2f,
        )
        assertEquals("${kind.name} painted X stroke width", kind.paintedStrokePx, paintedWidthPx, 1f)
        assertEquals("${kind.name} painted X stroke height", kind.paintedStrokePx, paintedHeightPx, 1f)
        val paintedCentre = Offset(bounds.left + (minX + maxX) / 2f, bounds.top + (minY + maxY) / 2f)
        assertTrue(
            "${kind.name} painted X is not centred in target: painted=$paintedCentre target=$bounds",
            abs(paintedCentre.x - bounds.center.x) <= density && abs(paintedCentre.y - bounds.center.y) <= density,
        )
        assertTrue(
            "${kind.name} target is asymmetric around painted X",
            abs((paintedCentre.x - bounds.left) - (bounds.right - paintedCentre.x)) <= 2f * density &&
                abs((paintedCentre.y - bounds.top) - (bounds.bottom - paintedCentre.y)) <= 2f * density,
        )

        val left = bounds.left + 1f
        val right = bounds.right - 1f
        val top = bounds.top + 1f
        val bottom = bounds.bottom - 1f
        val x = paintedCentre.x
        val y = paintedCentre.y
        return PaintedMap(
            bounds,
            paintedCentre,
            listOf(
                "painted-centre" to paintedCentre,
                "left" to Offset(left, y),
                "right" to Offset(right, y),
                "up" to Offset(x, top),
                "down" to Offset(x, bottom),
                "top-left" to Offset(left, top),
                "top-right" to Offset(right, top),
                "bottom-left" to Offset(left, bottom),
                "bottom-right" to Offset(right, bottom),
            ),
        )
    }

    private fun assertPhysicalConfiguration() {
        assertEquals("density", 2.625f, rule.density.density, 0.001f)
        assertEquals("font scale", expectedFontScale, rule.density.fontScale, 0.001f)
    }

    private fun settle(ms: Long = 700L) {
        rule.mainClock.advanceTimeBy(16L)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun scrollPosition(): Pair<Int, Int> = rule.runOnIdle {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    private fun colourDistance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) + abs(a.alpha - b.alpha)

    private class SelectionMenuProbe : TextContextMenuProvider {
        @Volatile var shown: TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1f)
class NoticeDismissTimelineFont1RegressionTest : NoticeDismissTimelineRegression() {
    override val expectedFontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class NoticeDismissTimelineFont13RegressionTest : NoticeDismissTimelineRegression() {
    override val expectedFontScale = 1.3f
}
