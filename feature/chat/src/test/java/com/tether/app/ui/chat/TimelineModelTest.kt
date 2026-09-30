package com.tether.app.ui.chat

import com.tether.app.protocol.helpers.ConversationStoryPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * T6.5: the timeline's pure half against tether components/conversation-timeline.tsx and
 * lib/conversation-story-points.ts (PARITY_BASE): the story points and their copy, the owner's
 * 270/320 limits, the display cleaning, the fixed window, the slot geometry, the dash table, the
 * scrub and wheel steps.
 */
class TimelineModelTest {
    private val utc = ZoneOffset.UTC

    private fun point(prompt: String = "p", reply: String = "r", ts: Double? = null) = TimelinePoint("t", "b", prompt, reply, ts)

    @Test fun theSeededScenarioGivesOnePointPerPromptPairedWithItsFirstReply() {
        val points = TimelineModel.points(TimelineFixtures.seeded.tree)
        assertEquals(TimelineFixtures.PROMPTS, points.map { it.prompt })
        assertEquals(TimelineFixtures.PROMPTS.map { "(fake engine) you said: $it" }, points.map { it.reply })
        assertEquals((1..5).map { "timeline-$it" }, points.map { it.turnId })
        assertTrue(points.all { it.ts == TimelineFixtures.T_SEEDED.toDouble() })
        assertEquals(emptyList<TimelinePoint>(), TimelineModel.points(null))
    }

    @Test fun theOwnersLimitsApplyAndTheWebsStayTheHelpersDefault() {
        val long = ChatFixtures.fold(*ChatFixtures.turn("t1", "p".repeat(400), "r".repeat(400), 0L))
        val point = TimelineModel.points(long.tree).single()
        assertEquals("p".repeat(269) + "…", point.prompt)
        assertEquals("r".repeat(319) + "…", point.reply)
        val web = ConversationStoryPoints.storyPointsFromSession(long.tree).single()
        assertEquals(220, web.prompt.length)
        assertEquals(260, web.reply.length)
    }

    @Test fun serverTextIsCleanedForDisplay() {
        val points = TimelineModel.points(TimelineFixtures.edges.tree)
        // Attachment-only: the names, as the web joins them.
        assertEquals("screenshot.png, report.pdf", points[0].prompt)
        assertEquals("Both files read.", points[0].reply)
        // No bidi override, no zero-width space, whitespace collapsed.
        assertEquals("Fix the parser bug now", points[1].prompt)
        // The last prompt has no reply yet.
        assertEquals("Still thinking?", points[2].prompt)
        assertEquals("", points[2].reply)
    }

    @Test fun theCopyMatchesTheWeb() {
        val p = point(prompt = "Which files does it touch?", ts = TimelineFixtures.T_SEEDED.toDouble())
        assertEquals("Jump to your message at 01:02: Which files does it touch?", TimelineModel.markLabel(p, utc))
        assertEquals("Jump to your message: attachment", TimelineModel.markLabel(point(prompt = ""), utc))
        assertEquals("01:02" to "2 / 5", TimelineModel.meta(1, 5, p, utc))
        assertEquals("—" to "1 / 1", TimelineModel.meta(0, 1, point(), utc))
        assertEquals("Attachment sent", TimelineModel.promptText(point(prompt = "")))
        assertEquals("Conversation prompts", TimelineModel.LABEL)
    }

    @Test fun aMissingReplyIsPendingOnlyOnALiveCopy() {
        assertEquals("Agent reply pending…", TimelineModel.replyText(point(reply = ""), liveCopy = true))
        assertEquals("No reply in the saved copy", TimelineModel.replyText(point(reply = ""), liveCopy = false))
        assertEquals("r", TimelineModel.replyText(point(reply = "r"), liveCopy = false))
    }

    @Test fun theWindowIsTenFixedSlotsAnchoredAtTheFifth() {
        assertEquals((0..4).toList(), TimelineModel.window(5, -1, null, false, 4))
        // No reading line yet: the newest ten.
        assertEquals((15..24).toList(), TimelineModel.window(25, -1, null, false, 4))
        // The active prompt sits in slot 4, clamped at either end.
        assertEquals((8..17).toList(), TimelineModel.window(25, 12, null, false, 4))
        assertEquals((0..9).toList(), TimelineModel.window(25, 2, null, false, 4))
        assertEquals((15..24).toList(), TimelineModel.window(25, 23, null, false, 4))
        // Browsing wins over the active prompt; a scrub anchors at the slot it started on.
        assertEquals((0..9).toList(), TimelineModel.window(25, 20, 0, false, 4))
        assertEquals((5..14).toList(), TimelineModel.window(25, 20, 12, true, 7))
        assertEquals((15..24).toList(), TimelineModel.window(25, -1, 99, false, 4))
    }

    @Test fun theClusterIsCentredWithTenPitchAndShrinksInAShortRail() {
        val tall = TimelineModel.cluster(10, 678f, 1f)
        assertEquals(10f, tall.pitch, 0f)
        assertEquals(90f, tall.height, 0f)
        assertEquals(294f, tall.top, 0f)
        val short = TimelineModel.cluster(10, 100f, 1f)
        assertEquals(8f, short.pitch, 1e-4f)
        assertEquals(14f, short.top, 1e-4f)
        val one = TimelineModel.cluster(1, 200f, 2f)
        assertEquals(0f, one.pitch, 0f)
        assertEquals(100f, one.top, 0f)
        // In px: the dp lengths scale.
        assertEquals(26.25f, TimelineModel.cluster(10, 2000f, 2.625f).pitch, 1e-4f)
    }

    @Test fun aSlotIsHitWithinEighteenOfIt() {
        val c = TimelineModel.cluster(10, 678f, 1f)
        assertEquals(0, TimelineModel.slotAt(294f, 10, c, 1f))
        assertEquals(3, TimelineModel.slotAt(325f, 10, c, 1f))
        assertEquals(9, TimelineModel.slotAt(384f + 18f, 10, c, 1f))
        assertEquals(-1, TimelineModel.slotAt(384f + 18.5f, 10, c, 1f))
        assertEquals(-1, TimelineModel.slotAt(294f - 18.5f, 10, c, 1f))
        val one = TimelineModel.cluster(1, 200f, 1f)
        assertEquals(0, TimelineModel.slotAt(118f, 1, one, 1f))
        assertEquals(-1, TimelineModel.slotAt(119f, 1, one, 1f))
        assertEquals(-1, TimelineModel.slotAt(100f, 0, one, 1f))
    }

    @Test fun theDashTableIsTheWebs() {
        assertEquals(10f, TimelineModel.markWidth(false, false, Int.MAX_VALUE), 0f)
        assertEquals(16f, TimelineModel.markWidth(false, true, Int.MAX_VALUE), 0f)
        assertEquals(listOf(28f, 22f, 16f, 12f, 10f, 10f), (0..5).map { TimelineModel.markWidth(true, false, it) })
        // The needle never shrinks below its rest while a neighbour is inspected.
        assertEquals(16f, TimelineModel.markWidth(true, true, 4), 0f)
        assertEquals(28f, TimelineModel.markWidth(true, true, 0), 0f)
        assertEquals(0.5f, TimelineModel.markOpacity(false, false, Int.MAX_VALUE), 0f)
        assertEquals(1f, TimelineModel.markOpacity(false, true, Int.MAX_VALUE), 0f)
        assertEquals(listOf(1f, 0.86f, 0.72f, 0.6f, 0.5f, 0.5f), (0..5).map { TimelineModel.markOpacity(true, false, it) })
        assertEquals(1f, TimelineModel.markOpacity(true, true, 3), 0f)
    }

    @Test fun aScrubOnTheRightRailBrowsesOlderUpwardsOverATighterSpan() {
        // Mobile span: max(120, h * 0.32). A 100-dp rail: 120; eleven prompts, ten steps.
        assertEquals(0, TimelineModel.scrubIndex(10, 300f, 180f, 11, 100f, TimelineSide.Right, 1f))
        assertEquals(5, TimelineModel.scrubIndex(10, 300f, 240f, 11, 100f, TimelineSide.Right, 1f))
        assertEquals(10, TimelineModel.scrubIndex(5, 300f, 400f, 11, 100f, TimelineSide.Right, 1f))
        // A tall rail widens the span: 1000 * 0.32 = 320.
        assertEquals(5, TimelineModel.scrubIndex(10, 500f, 340f, 11, 1000f, TimelineSide.Right, 1f))
        // Desktop: drag up browses newer, span max(140, h * 0.72).
        assertEquals(10, TimelineModel.scrubIndex(0, 300f, 160f, 11, 100f, TimelineSide.Left, 1f))
        assertEquals(0, TimelineModel.scrubIndex(0, 300f, 400f, 11, 100f, TimelineSide.Left, 1f))
        // In px the dp span scales: 120dp at 2 px/dp = 240px.
        assertEquals(5, TimelineModel.scrubIndex(10, 600f, 480f, 11, 200f, TimelineSide.Right, 2f))
        // A single prompt stays put.
        assertEquals(0, TimelineModel.scrubIndex(0, 0f, -500f, 1, 100f, TimelineSide.Right, 1f))
    }

    @Test fun theWheelStepsLikeChromesPixelDeltas() {
        // One notch = 100px: round(100 / 36) = 3 prompts; down is newer.
        assertEquals(13, TimelineModel.wheelAnchor(null, 10, 1f, false, 25))
        assertEquals(7, TimelineModel.wheelAnchor(null, 10, -1f, false, 25))
        assertEquals(4, TimelineModel.wheelAnchor(7, 10, -1f, false, 25))
        // A small touchpad delta still moves one.
        assertEquals(11, TimelineModel.wheelAnchor(null, 10, 0.1f, false, 25))
        // Shift pages by the visible count; clamped.
        assertEquals(20, TimelineModel.wheelAnchor(null, 10, 1f, true, 25))
        assertEquals(24, TimelineModel.wheelAnchor(20, 10, 1f, true, 25))
        assertEquals(0, TimelineModel.wheelAnchor(2, 10, -1f, true, 25))
        // Everything already visible: the wheel does nothing.
        assertNull(TimelineModel.wheelAnchor(null, 4, 1f, false, 10))
    }

    @Test fun theNeedleIsThePromptNearestTheReadingLine() {
        // Reading line: 40% of 1000 = 400.
        assertEquals(1, TimelineModel.activeIndex(listOf(0 to 100f, 1 to 380f, 2 to 700f), 1000f, 3))
        assertEquals(2, TimelineModel.activeIndex(listOf(2 to 900f), 1000f, 3))
        assertEquals(-1, TimelineModel.activeIndex(emptyList(), 1000f, 3))
        assertEquals(-1, TimelineModel.activeIndex(listOf(5 to 400f), 1000f, 3))
    }

    @Test fun withNoPromptRowOnScreenTheNeedleIsTheNearestPromptOffScreen() {
        // Story point -> lazy row: prompts on rows 1, 3, 5, 7 (their replies between).
        val rows = mapOf(0 to 1, 1 to 3, 2 to 5, 3 to 7)
        // Deep in the reply on row 4: the last prompt above it.
        assertEquals(1, TimelineModel.activeIndex(emptyList(), 1000f, 4, firstVisibleRow = 4, promptRows = rows))
        assertEquals(3, TimelineModel.activeIndex(emptyList(), 1000f, 4, firstVisibleRow = 8, promptRows = rows))
        // Nothing above (a leading row 0 on screen): the first prompt below.
        assertEquals(0, TimelineModel.activeIndex(emptyList(), 1000f, 4, firstVisibleRow = 0, promptRows = rows))
        // A prompt row on screen still wins by distance.
        assertEquals(2, TimelineModel.activeIndex(listOf(2 to 380f), 1000f, 4, firstVisibleRow = 4, promptRows = rows))
        // Nothing laid out, or no prompts: no needle.
        assertEquals(-1, TimelineModel.activeIndex(emptyList(), 1000f, 4, firstVisibleRow = -1, promptRows = rows))
        assertEquals(-1, TimelineModel.activeIndex(emptyList(), 1000f, 0, firstVisibleRow = 4, promptRows = emptyMap()))
        // Out-of-range story points are ignored.
        assertEquals(1, TimelineModel.activeIndex(emptyList(), 1000f, 2, firstVisibleRow = 6, promptRows = rows))
    }

    @Test fun theLinesAreTheWebsLiterals() {
        // conversation-timeline.tsx:143 `clientHeight * 0.4`, :176 `clientHeight * 0.28`.
        assertEquals(0.4f, TimelineModel.READING_LINE, 0f)
        assertEquals(0.28f, TimelineModel.JUMP_LINE, 0f)
    }

    @Test fun aJumpSnapsPastTwoViewportsOrUnderReducedMotion() {
        assertTrue(TimelineModel.smoothJump(1999f, 1000f, false))
        assertFalse(TimelineModel.smoothJump(2000f, 1000f, false))
        assertFalse(TimelineModel.smoothJump(10f, 1000f, true))
    }
}
