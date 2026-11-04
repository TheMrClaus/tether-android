package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ta-cf59 regression. Unlike NoticeBehaviourTest's isolated node-local probe, this drives
 * physical root coordinates through the production ChatTranscript -> SelectableRow -> notice-row
 * stack. The pixel scan maps the painted X before any touch, so a passing semantics action cannot
 * hide a displaced glyph or a parent that owns one side of the target.
 *
 * Timeline-off geometry is intentionally frozen here for all three shared callers and the long
 * RTL warning; the timeline collision and scheduled-resume path live in the companion regression.
 */
@OptIn(ExperimentalFoundationApi::class)
abstract class NoticeDismissActualContextRegression {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val recorder = NoticeFixtures.Recorder()
    private lateinit var listState: LazyListState
    private lateinit var menu: SelectionMenuProbe

    private data class Caller(
        val name: String,
        val fixture: ChatFixtures.Folded,
        val richCodex: Boolean,
        val rowTag: String,
        val dismissLabel: String,
        val dismissKey: String,
        val lastMatchingDismiss: Boolean = false,
    )

    private data class PaintedTarget(val bounds: androidx.compose.ui.geometry.Rect, val pointsInRoot: List<Pair<String, Offset>>)

    private val longWarning: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = ChatFixtures.T_IDLE) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = ChatFixtures.T_IDLE) {
                put("text", "Check the warning dismissal target in the real transcript.")
            },
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + 1) {
                put("noticeId", "retry-2")
                put("level", "warning")
                put("code", "retrying")
                put("message", "Reconnecting... 2/5")
            },
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + 2) {
                put("noticeId", "retry-3")
                put("level", "warning")
                put("code", "retrying")
                put("message", "Reconnecting... 3/5")
            },
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + 3) {
                put("noticeId", "retry-4")
                put("level", "warning")
                put("code", "retrying")
                put("message", "Reconnecting... 4/5")
            },
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + 4) {
                put("noticeId", "retry-5")
                put("level", "warning")
                put("code", "retrying")
                put("message", "Reconnecting... 5/5")
            },
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE + 5) {
                put("noticeId", "warning-hit-map")
                put("level", "warning")
                put("code", "warning")
                put(
                    "message",
                    "Falling back from WebSockets to HTTPS transport. stream disconnected before completion: websocket closed by server before response.completed",
                )
            },
        )
    }

    private val callers: List<Caller>
        get() = listOf(
            Caller(
                name = "ProviderNoticeRow",
                fixture = longWarning,
                richCodex = true,
                rowTag = "provider-notice",
                dismissLabel = "Dismiss notice",
                dismissKey = "provider_notice:t1:warning-hit-map",
                lastMatchingDismiss = true,
            ),
            Caller(
                name = "CompactionRow",
                fixture = NoticeFixtures.codexNotices,
                richCodex = true,
                rowTag = "compaction-notice",
                dismissLabel = "Dismiss context-compacted notice",
                dismissKey = "context_compacted:t1:cmp-1",
            ),
            Caller(
                name = "SessionNoticeRow",
                fixture = NoticeFixtures.sessionNotices,
                richCodex = false,
                rowTag = "session-notice",
                dismissLabel = "Dismiss external-advancement notice",
                dismissKey = "ext-1",
            ),
        )

    @Test
    fun providerNoticeRowOwnsThePaintedGlyphAndTheSymmetric48DpScreenCoordinateMap() {
        assertPhysicalConfiguration()
        probe(callers[0], LayoutDirection.Ltr)
    }

    @Test
    fun compactionRowOwnsThePaintedGlyphAndTheSymmetric48DpScreenCoordinateMap() {
        assertPhysicalConfiguration()
        probe(callers[1], LayoutDirection.Ltr)
    }

    @Test
    fun sessionNoticeRowOwnsThePaintedGlyphAndTheSymmetric48DpScreenCoordinateMap() {
        assertPhysicalConfiguration()
        probe(callers[2], LayoutDirection.Ltr)
    }

    @Test
    fun aLongRtlProviderWarningKeepsTheSamePaintedScreenCoordinateMap() {
        assertPhysicalConfiguration()
        probe(callers.first(), LayoutDirection.Rtl)
    }

    private fun assertPhysicalConfiguration() {
        assertEquals("density", 2.625f, rule.density.density, 0.001f)
        assertEquals("font scale", expectedFontScale, rule.density.fontScale, 0.001f)
    }

    private fun probe(caller: Caller, direction: LayoutDirection) {
        recorder.dismissed.clear()
        recorder.controls.clear()
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
                        projection = caller.fixture.projection,
                        tree = caller.fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        richCodex = caller.richCodex,
                        notices = recorder.actions(),
                        showTimeline = false,
                    )
                }
            }
        }
        settle()

        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(caller.rowTag))
        settle()
        val dismiss = if (caller.lastMatchingDismiss) {
            rule.onAllNodesWithContentDescription(caller.dismissLabel).onLast()
        } else {
            rule.onNodeWithContentDescription(caller.dismissLabel)
        }
        val mapped = paintedTarget(dismiss, caller)
        val rows = rule.onAllNodesWithTag(caller.rowTag).fetchSemanticsNodes()
        val rowBounds = rows.first { it.boundsInRoot.contains(mapped.bounds.center) }.boundsInRoot
        val existingInset = if (caller.name == "SessionNoticeRow") 0f else 4f
        val actualInset = if (direction == LayoutDirection.Ltr) {
            rowBounds.right - mapped.bounds.right
        } else {
            mapped.bounds.left - rowBounds.left
        }
        assertEquals("${caller.name} timeline-off position", existingInset, actualInset / rule.density.density, 0.75f)
        val initialScroll = scrollPosition()

        mapped.pointsInRoot.forEach { (pointName, point) ->
            val before = recorder.dismissed.toList()
            rule.onRoot().performTouchInput { click(point) }
            settle(32L)
            assertEquals(
                "${caller.name} $direction $pointName must append exactly its own key once",
                before + caller.dismissKey,
                recorder.dismissed,
            )
            assertTrue("${caller.name} $direction $pointName activated an adjacent control", recorder.controls.isEmpty())
            assertEquals("${caller.name} $direction $pointName scrolled the transcript", initialScroll, scrollPosition())
            assertTrue("${caller.name} $direction $pointName opened text selection", menu.shown == null)
        }
    }

    /** Finds the visible X inside the production node, then maps root-coordinate taps around it. */
    private fun paintedTarget(dismiss: SemanticsNodeInteraction, caller: Caller): PaintedTarget {
        val node = dismiss.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val density = rule.density.density
        val targetPx = 48f * density
        assertEquals("${caller.name} target width", targetPx, node.size.width.toFloat(), 1f)
        assertEquals("${caller.name} target height", targetPx, node.size.height.toFloat(), 1f)
        assertEquals("${caller.name} visible width", targetPx, bounds.width, 1f)
        assertEquals("${caller.name} visible height", targetPx, bounds.height, 1f)

        val pixels = dismiss.captureToImage().toPixelMap()
        val background = pixels[(4f * density).roundToInt(), (4f * density).roundToInt()]
        val cx = pixels.width / 2
        val cy = pixels.height / 2
        val radius = (10f * density).roundToInt()
        val painted = buildList {
            for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(pixels.height - 1)) {
                for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(pixels.width - 1)) {
                    if (colourDistance(pixels[x, y], background) > 0.08f) add(x to y)
                }
            }
        }
        assertTrue("${caller.name} pixel probe found no painted X", painted.isNotEmpty())
        val minX = painted.minOf { it.first }
        val maxX = painted.maxOf { it.first }
        val minY = painted.minOf { it.second }
        val maxY = painted.maxOf { it.second }
        val paintCentreLocal = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val paintCentreRoot = Offset(bounds.left + paintCentreLocal.x, bounds.top + paintCentreLocal.y)
        assertTrue(
            "${caller.name} painted X centre $paintCentreRoot is not centred in target ${bounds.center}",
            abs(paintCentreRoot.x - bounds.center.x) <= density && abs(paintCentreRoot.y - bounds.center.y) <= density,
        )
        assertTrue(
            "${caller.name} target is asymmetric around painted X $paintCentreRoot in $bounds",
            abs((paintCentreRoot.x - bounds.left) - (bounds.right - paintCentreRoot.x)) <= 2f * density &&
                abs((paintCentreRoot.y - bounds.top) - (bounds.bottom - paintCentreRoot.y)) <= 2f * density,
        )

        // One physical pixel inside the claimed target. Injection is through the root, never a
        // semantics click or a node-local coordinate conversion.
        val left = bounds.left + 1f
        val right = bounds.right - 1f
        val top = bounds.top + 1f
        val bottom = bounds.bottom - 1f
        val x = paintCentreRoot.x
        val y = paintCentreRoot.y
        return PaintedTarget(
            bounds = bounds,
            pointsInRoot = listOf(
                "painted-centre" to paintCentreRoot,
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

    private fun settle(ms: Long = 700L) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun scrollPosition(): Pair<Int, Int> = rule.runOnIdle {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }

    private fun colourDistance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) + abs(a.alpha - b.alpha)

    protected abstract val expectedFontScale: Float

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
class NoticeDismissActualContextFont1RegressionTest : NoticeDismissActualContextRegression() {
    override val expectedFontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class NoticeDismissActualContextFont13RegressionTest : NoticeDismissActualContextRegression() {
    override val expectedFontScale = 1.3f
}
