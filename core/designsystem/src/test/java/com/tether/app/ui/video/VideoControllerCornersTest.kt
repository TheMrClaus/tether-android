package com.tether.app.ui.video

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The platform controller clips itself to the video BOX's rounded rectangle, in its own coordinates (design
 * ruling, L1 captures): its top corners are cut too whenever they fall inside the box's top curves.
 */
@RunWith(RobolectricTestRunner::class)
class VideoControllerCornersTest {
    private fun outlineOf(view: View): Pair<Rect, Float> {
        val outline = Outline()
        view.outlineProvider.getOutline(view, outline)
        val rect = Rect()
        assertTrue(outline.getRect(rect))
        return rect to outline.radius
    }

    private fun controller(width: Int, height: Int, radius: Float, box: () -> Int): View =
        View(ApplicationProvider.getApplicationContext()).also {
            roundBoxCorners(it, box, radius)
            it.layout(0, 0, width, height)
        }

    @Test fun aTallBoxLeavesTheTopStraightAndRoundsOnlyTheBottom() {
        // Portrait: a 88 px bar in a 420 px box. The box's top is 332 px above the bar's: its curve never reaches it.
        val view = controller(300, 88, 12f) { 420 }
        assertTrue("it clips its own children (the dark backing)", view.clipToOutline)
        val (rect, radius) = outlineOf(view)
        assertEquals(12f, radius, 0.001f)
        assertEquals(0, rect.left)
        assertEquals(300, rect.right)
        assertEquals(88, rect.bottom)
        assertEquals("the box's top edge in the bar's coordinates", -(420 - 88), rect.top)
    }

    @Test fun aBoxBarelyTallerThanTheBarCutsTheTopCornersToo() {
        // Phone landscape: a 240 px box, a 232 px bar. The box's top edge is 8 px above the bar's, so the
        // 12 px curve at the box's top corners lies inside the bar.
        val view = controller(800, 232, 31.5f) { 240 }
        val (rect, radius) = outlineOf(view)
        assertEquals(31.5f, radius, 0.001f)
        assertEquals(-8, rect.top)
        assertEquals(232, rect.bottom)
        assertEquals(800, rect.right)
    }

    @Test fun aBarAsTallAsTheBoxIsTheWholeRoundedBox() {
        val (rect, _) = outlineOf(controller(400, 150, 12f) { 150 })
        assertEquals(0, rect.top)
        assertEquals(150, rect.bottom)
    }

    @Test fun aBoxShorterThanTheBarNeverClipsTheBarsTop() {
        val (rect, _) = outlineOf(controller(400, 150, 12f) { 100 })
        assertEquals(0, rect.top)
    }

    @Test fun theOutlineFollowsTheBoxAndTheBarWhenEitherChanges() {
        var box = 420
        val view = controller(300, 88, 12f) { box }
        assertEquals(-332, outlineOf(view).first.top)
        // A rotation: a shorter box, a taller bar, a wider anchor.
        box = 240
        view.layout(0, 0, 800, 232)
        val (rect, _) = outlineOf(view)
        assertEquals(-8, rect.top)
        assertEquals(800, rect.right)
        assertEquals(232, rect.bottom)
    }
}
