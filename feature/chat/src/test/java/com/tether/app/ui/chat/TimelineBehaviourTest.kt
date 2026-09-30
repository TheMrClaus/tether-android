package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.HapticMoment
import com.tether.app.ui.components.LocalTetherHaptics
import com.tether.app.ui.components.TetherHaptics
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** conversation-timeline.tsx:176: `node.offsetTop - scroll.clientHeight * 0.28` (the web's literal). */
private const val WEB_JUMP_LINE = 0.28f

/** Records the haptic moments instead of vibrating. */
internal class RecordingHaptics : TetherHaptics(null) {
    val moments = mutableListOf<HapticMoment>()
    override fun perform(moment: HapticMoment) {
        moments += moment
    }
}

internal fun ComposeContentTestRule.showTimeline(
    fixture: ChatFixtures.Folded,
    liveCopy: Boolean = true,
    reducedMotion: Boolean = true,
    haptics: TetherHaptics = RecordingHaptics(),
    listState: LazyListState = LazyListState(),
    skin: TetherSkin = TetherSkin.StudioDark,
): LazyListState {
    setContent {
        CompositionLocalProvider(LocalTetherHaptics provides haptics) {
            ChatHost(skin, reducedMotion = reducedMotion) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = listState,
                    liveCopy = liveCopy,
                )
            }
        }
    }
    waitForIdle()
    return listState
}

/**
 * The row holding [text] has its top exactly at the web's jump line (conversation-timeline.tsx:176,
 * `node.offsetTop - clientHeight * 0.28`), within a pixel of rounding.
 */
internal fun ComposeContentTestRule.assertRowOnWebJumpLine(text: String, list: LazyListState) {
    val box = onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
    val node = onAllNodesWithText(text).fetchSemanticsNodes().first { it.boundsInRoot.top >= box.top }
    val (rowTop, vh) = runOnIdle {
        val info = list.layoutInfo
        val y = node.boundsInRoot.top - box.top
        val row = info.visibleItemsInfo.first { (it.offset - info.viewportStartOffset) <= y && y < it.offset - info.viewportStartOffset + it.size }
        (row.offset - info.viewportStartOffset).toFloat() to info.viewportSize.height.toFloat()
    }
    assertEquals("\"$text\" row top vs 28% of $vh", vh * WEB_JUMP_LINE, rowTop, 1.5f)
}

/** The prompt indices whose marks are on the rail, top to bottom. */
internal fun ComposeContentTestRule.visibleMarks(): List<Int> =
    onAllNodes(hasTestTagPrefix("timeline-mark-")).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag].removePrefix("timeline-mark-").toInt() }

private fun hasTestTagPrefix(prefix: String) =
    androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

/** Where slot [slot] of [count] sits in the rail (local px), as the rail computes it. */
internal fun androidx.compose.ui.test.InjectionScope.slotOffset(slot: Int, count: Int): Offset {
    val cluster = TimelineModel.cluster(count, height.toFloat(), density)
    return Offset(width / 2f, cluster.top + slot * cluster.pitch)
}

/**
 * T6.5 behaviour on the phone (the right-edge scrubber): TalkBack names, a touch that shows the
 * bubble at once and jumps on release, a scrub that browses older prompts upwards with one haptic
 * per new prompt, a cancelled scrub, keyboard focus, the saved-copy rule, and follow mode.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TimelineBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun rail() = rule.onNodeWithTag(TIMELINE_TAG)
    private var shown = LazyListState()
    private fun mark(index: Int) = rule.onNodeWithTag(timelineMarkTag(index))
    private fun bubble() = rule.onNodeWithTag(TIMELINE_BUBBLE_TAG)
    private fun bubbleShown() = rule.onAllNodes(hasTestTag(TIMELINE_BUBBLE_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun assertOnJumpLine(text: String) = rule.assertRowOnWebJumpLine(text, shown)

    private fun jumpToLatestShown() =
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Jump to latest")).fetchSemanticsNodes().isNotEmpty()

    @Test fun theRailNamesEveryPromptForTalkBackAndMarksTheCurrentOne() {
        shown = rule.showTimeline(TimelineFixtures.seeded)
        val railNode = rail().fetchSemanticsNode()
        assertEquals(listOf("Conversation prompts"), railNode.config[SemanticsProperties.ContentDescription])
        assertEquals(true, railNode.config.getOrNull(SemanticsProperties.IsTraversalGroup))
        assertEquals((0..4).toList(), rule.visibleMarks())
        TimelineFixtures.PROMPTS.forEachIndexed { i, p ->
            val node = mark(i).fetchSemanticsNode()
            assertEquals(listOf("Jump to your message at 01:02: $p"), node.config[SemanticsProperties.ContentDescription])
            assertEquals(Role.Button, node.config[SemanticsProperties.Role])
            assertTrue(node.config.contains(SemanticsActions.OnClick))
        }
        val current = (0..4).filter { mark(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Current step" }
        assertEquals("exactly one needle", 1, current.size)
        // Pinned to the newest: the needle is one of the last prompts on screen.
        assertTrue(current.single() >= 3)
        assertFalse(bubbleShown())
    }

    @Test fun activatingAMarkJumpsToItsPromptAndStopsFollowing() {
        shown = rule.showTimeline(TimelineFixtures.many(12))
        assertFalse(jumpToLatestShown())
        val target = rule.visibleMarks()[3]
        mark(target).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertOnJumpLine("Prompt ${target + 1}")
        // A jump up disengages follow mode, as the web's scroll-up does.
        assertTrue(jumpToLatestShown())
    }

    @Test fun aSmoothJumpLandsOnTheSameLine() {
        shown = rule.showTimeline(TimelineFixtures.many(12), reducedMotion = false)
        // A row on screen above the line, then one scrolled out above (both under 2 viewports away).
        val window = rule.visibleMarks()
        for (target in listOf(window[7], window[3])) {
            mark(target).performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            assertOnJumpLine("Prompt ${target + 1}")
        }
    }

    @Test fun aTouchShowsTheBubbleAtOnceAndReleaseJumps() {
        val haptics = RecordingHaptics()
        shown = rule.showTimeline(TimelineFixtures.many(12), haptics = haptics)
        val target = rule.visibleMarks()[2]
        rail().performTouchInput { down(slotOffset(2, 10)) }
        rule.waitForIdle()
        assertTrue("the bubble shows from the first touch", bubbleShown())
        bubble().assertExistsWith("Prompt ${target + 1}")
        bubble().assertExistsWith("Reply ${target + 1} with a second paragraph so the transcript is long")
        assertEquals(listOf(HapticMoment.ScrubStep), haptics.moments)
        rail().performTouchInput { up() }
        rule.waitForIdle()
        assertFalse(bubbleShown())
        assertOnJumpLine("Prompt ${target + 1}")
    }

    @Test fun aTouchOffTheStackDoesNothing() {
        val haptics = RecordingHaptics()
        val list = LazyListState()
        shown = rule.showTimeline(TimelineFixtures.seeded, haptics = haptics, listState = list)
        val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        rail().performTouchInput {
            down(Offset(width / 2f, 4f))
            up()
        }
        rule.waitForIdle()
        assertFalse(bubbleShown())
        assertTrue(haptics.moments.isEmpty())
        assertEquals(before, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
    }

    @Test fun aScrubBrowsesOlderPromptsUpwardsWithOneHapticPerNewPrompt() {
        val haptics = RecordingHaptics()
        shown = rule.showTimeline(TimelineFixtures.many(25), haptics = haptics)
        val window = rule.visibleMarks()
        assertEquals(10, window.size)
        val start = window.last()
        rail().performTouchInput { down(slotOffset(9, 10)) }
        rule.waitForIdle()
        bubble().assertExistsWith("Prompt ${start + 1}")
        // Up by 60dp: the phone span is max(120dp, 0.32 h) = 216.96dp here, 24 steps.
        rail().performTouchInput { moveBy(Offset(0f, -60f * density)) }
        rule.waitForIdle()
        val span = maxOf(120f, 678f * 0.32f)
        val expected = start + Math.round(-60f / span * 24)
        assertTrue("older, not newer", expected < start)
        assertTrue(bubbleShown())
        bubble().assertExistsWith("Prompt ${expected + 1}")
        // The scrubbed prompt stays in the slot the finger started on.
        assertEquals(expected, rule.visibleMarks()[9])
        assertEquals(2, haptics.moments.size)
        rail().performTouchInput { moveBy(Offset(0f, 1f)) }
        rule.waitForIdle()
        assertEquals("no new prompt, no new haptic", 2, haptics.moments.size)
        rail().performTouchInput { up() }
        rule.waitForIdle()
        assertFalse(bubbleShown())
        assertOnJumpLine("Prompt ${expected + 1}")
        // The window stays around the prompt the scrub reached, back at slot 4.
        assertEquals(expected, rule.visibleMarks()[4])
    }

    private fun needle(): Int? = rule.visibleMarks().singleOrNull {
        mark(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Current step"
    }

    @Test fun deepInALongReplyTheNeedleStaysOnItsPrompt() {
        val list = LazyListState()
        shown = rule.showTimeline(TimelineFixtures.longReply, listState = list)
        // A reader's drag up stops follow mode (else the view stays pinned to the newest).
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        rule.waitForIdle()
        // Find prompt 5's reply: the one row several viewports tall.
        fun scrollTo(k: Int, offset: Int = 0) {
            rule.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollToItem(k, offset) } }
            rule.waitForIdle()
        }
        val vh = rule.runOnIdle { list.layoutInfo.viewportSize.height }
        fun sizeOf(k: Int) = rule.runOnIdle { list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == k }?.size ?: 0 }
        val row = (0 until rule.runOnIdle { list.layoutInfo.totalItemsCount }).first { k ->
            scrollTo(k)
            sizeOf(k) > vh * 3
        }
        scrollTo(row, sizeOf(row) / 2)
        assertTrue("no prompt row on screen", rule.onAllNodesWithText("Prompt 5").fetchSemanticsNodes().isEmpty())
        assertEquals((0..9).toList(), rule.visibleMarks())
        assertEquals(4, needle())
        // The reply's first screen (prompt 5 just scrolled off above): still prompt 5.
        scrollTo(row)
        assertEquals(4, needle())
        // The scroll edges: the very top and the very bottom.
        // On screen the web's rule holds: the prompt row nearest 40% down (literal, as on the web).
        fun nearestOnScreen(): Int {
            val box = rule.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
            val line = box.top + box.height * 0.4f
            return (1..25).mapNotNull { k ->
                rule.onAllNodesWithText("Prompt $k").fetchSemanticsNodes()
                    .firstOrNull { it.boundsInRoot.bottom > box.top && it.boundsInRoot.top < box.bottom }
                    ?.let { k - 1 to kotlin.math.abs(it.boundsInRoot.top - line) }
            }.minBy { it.second }.first
        }
        scrollTo(0)
        assertEquals(nearestOnScreen(), needle())
        assertTrue(needle()!! <= 3)
        assertEquals((0..9).toList(), rule.visibleMarks())
        scrollTo(rule.runOnIdle { list.layoutInfo.totalItemsCount } - 1, Int.MAX_VALUE / 2)
        assertEquals(nearestOnScreen(), needle())
        assertTrue(needle()!! >= 21)
        assertEquals((15..24).toList(), rule.visibleMarks())
    }

    @Test fun aCancelledScrubEndsWithoutAJump() {
        val list = LazyListState()
        shown = rule.showTimeline(TimelineFixtures.many(25), listState = list)
        val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        rail().performTouchInput {
            down(slotOffset(9, 10))
            moveBy(Offset(0f, -80f * density))
        }
        rule.waitForIdle()
        assertTrue(bubbleShown())
        rail().performTouchInput { cancel() }
        rule.waitForIdle()
        assertFalse(bubbleShown())
        assertEquals(before, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        assertFalse(jumpToLatestShown())
    }

    @Test fun keyboardFocusInspectsAMarkAndEnterJumps() {
        shown = rule.showTimeline(TimelineFixtures.seeded)
        mark(2).requestFocus()
        rule.waitForIdle()
        assertTrue(bubbleShown())
        bubble().assertExistsWith(TimelineFixtures.PROMPTS[2])
        mark(2).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertOnJumpLine(TimelineFixtures.PROMPTS[2])
    }

    @Test fun aMissingReplyIsPendingOnlyOnALiveCopy() {
        var live by mutableStateOf(false)
        val f = TimelineFixtures.edges
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                ChatTranscript(projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, liveCopy = live)
            }
        }
        rule.waitForIdle()
        rail().performTouchInput { down(slotOffset(2, 3)) }
        rule.waitForIdle()
        bubble().assertExistsWith("Still thinking?")
        rule.onNodeWithText("No reply in the saved copy", useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Agent reply pending…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        live = true
        rule.waitForIdle()
        rule.onNodeWithText("Agent reply pending…", useUnmergedTree = true).assertExists()
        rail().performTouchInput { up() }
    }

    @Test fun serverTextIsCleanedInTheMarksAndTheBubble() {
        shown = rule.showTimeline(TimelineFixtures.edges)
        assertEquals(listOf("Jump to your message at 01:02: screenshot.png, report.pdf"), mark(0).fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(listOf("Jump to your message at 01:03: Fix the parser bug now"), mark(1).fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        rail().performTouchInput { down(slotOffset(1, 3)) }
        rule.waitForIdle()
        bubble().assertExistsWith("Fix the parser bug now")
        rail().performTouchInput { up() }
    }

    @Test fun theBubbleStaysOnScreenBesideTheRail() {
        shown = rule.showTimeline(TimelineFixtures.seeded)
        rail().performTouchInput { down(slotOffset(4, 5)) }
        rule.waitForIdle()
        val railBounds = rail().fetchSemanticsNode().boundsInRoot
        val bubble = rule.onNodeWithTag(TIMELINE_BUBBLE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("left of the rail", bubble.right <= railBounds.left + 3f * 2.625f)
        assertTrue("on screen", bubble.left >= 0f)
        rail().performTouchInput { up() }
    }
}

private fun SemanticsNodeInteraction.assertExistsWith(text: String) {
    val texts = fetchSemanticsNode().let { node ->
        buildList {
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
                n.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
                n.children.forEach(::walk)
            }
            walk(node)
        }
    }
    assertTrue("bubble texts $texts", text in texts)
}

/** The desktop layout (the expanded class): the rail docks left, a mouse browses by wheel and hover. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TimelineExpandedBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun rail() = rule.onNodeWithTag(TIMELINE_TAG)
    private fun bubbleShown() = rule.onAllNodes(hasTestTag(TIMELINE_BUBBLE_TAG)).fetchSemanticsNodes().isNotEmpty()

    private val list = LazyListState()

    @Composable
    private fun Host(f: ChatFixtures.Folded) {
        ChatHost(TetherSkin.StudioDark, WellHeightTablet, WellWidthTablet) {
            ChatTranscript(projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, listState = list, liveCopy = true)
        }
    }

    @Test fun theRailDocksLeftAndItsBubbleOpensRight() {
        rule.setContent { Host(TimelineFixtures.seeded) }
        rule.waitForIdle()
        val well = rule.onNodeWithTag(WellTag).fetchSemanticsNode().boundsInRoot
        val railBounds = rail().fetchSemanticsNode().boundsInRoot
        assertEquals(well.left, railBounds.left, 0.5f)
        assertEquals(44.8f, railBounds.width, 0.6f)
        rail().performTouchInput { down(slotOffset(0, 5)) }
        rule.waitForIdle()
        val bubble = rule.onNodeWithTag(TIMELINE_BUBBLE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.left >= railBounds.right)
        rail().performTouchInput { up() }
    }

    @Test fun theWheelBrowsesTheWindowAndLeavingResetsIt() {
        rule.setContent { Host(TimelineFixtures.many(25)) }
        rule.waitForIdle()
        val rest = rule.visibleMarks()
        assertEquals(10, rest.size)
        rail().performMouseInput {
            moveTo(slotOffset(4, 10))
            scroll(-3f)
        }
        rule.waitForIdle()
        val browsed = rule.visibleMarks()
        assertTrue("up is older: $rest -> $browsed", browsed.first() < rest.first())
        assertTrue("hover inspects", bubbleShown())
        rail().performMouseInput { moveTo(Offset(width / 2f, -40f)) }
        rule.waitForIdle()
        assertFalse(bubbleShown())
        assertEquals(rest, rule.visibleMarks())
    }

    @Test fun aMouseClickOnAHoveredMarkJumps() {
        rule.setContent { Host(TimelineFixtures.many(12)) }
        rule.waitForIdle()
        val target = rule.visibleMarks()[3]
        rail().performMouseInput {
            moveTo(slotOffset(3, 10))
            click(slotOffset(3, 10))
        }
        rule.waitForIdle()
        rule.assertRowOnWebJumpLine("Prompt ${target + 1}", list)
    }
}

/**
 * On the chat screen: the timeline is read-only navigation (a scrub and a jump put nothing on the
 * wire), and the pending-reply copy follows the session's liveness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TimelineScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun host(live: Boolean): ChatTestClient {
        val client = ChatTestClient()
        val session = chatSession("s1", historyId = "hist-1")
        client.show(session, TimelineFixtures.edges, live = live)
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        return client
    }

    private fun scrubAndJump() {
        rule.onNodeWithTag(TIMELINE_TAG).performTouchInput { down(slotOffset(2, 3)) }
        rule.waitForIdle()
    }

    @Test fun aLiveSessionSaysTheReplyIsPendingAndNothingIsSent() {
        val client = host(live = true)
        scrubAndJump()
        rule.onNodeWithText("Agent reply pending…", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(TIMELINE_TAG).performTouchInput { up() }
        rule.onNodeWithTag(timelineMarkTag(0)).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue("nothing on the wire: ${client.outbox}", client.outbox.isEmpty())
        assertTrue(client.interruptCalls.isEmpty())
        assertTrue(client.consentCalls.isEmpty())
    }

    @Test fun aSavedCopyNeverSaysAReplyIsOnItsWay() {
        host(live = false)
        scrubAndJump()
        rule.onNodeWithText("No reply in the saved copy", useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Agent reply pending…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Conversation prompts").assertExists()
    }
}
