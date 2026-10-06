package com.tether.app.ui.files

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The platform controller clips itself to the video box's radius at its bottom corners only (design ruling, L1 capture). */
@RunWith(RobolectricTestRunner::class)
class VideoControllerCornersTest {
    private fun outlineOf(view: View): Pair<Rect, Float> {
        val outline = Outline()
        view.outlineProvider.getOutline(view, outline)
        val rect = Rect()
        assertTrue(outline.getRect(rect))
        return rect to outline.radius
    }

    @Test fun theBottomCornersAreRoundedByTheBoxRadiusAndTheTopStaysStraight() {
        val view = View(ApplicationProvider.getApplicationContext())
        roundBottomCorners(view, 12f)
        view.layout(0, 0, 300, 88)
        assertTrue("it clips its own children (the dark backing)", view.clipToOutline)
        val (rect, radius) = outlineOf(view)
        assertEquals(12f, radius, 0.001f)
        assertEquals(0, rect.left)
        assertEquals(300, rect.right)
        assertEquals(88, rect.bottom)
        // The rounded top corners sit 12 px above the view, outside it: the visible top edge is square.
        assertEquals(-12, rect.top)
    }

    @Test fun theOutlineFollowsTheViewsSize() {
        val view = View(ApplicationProvider.getApplicationContext())
        roundBottomCorners(view, 31.5f)
        view.layout(0, 0, 640, 120)
        val (rect, radius) = outlineOf(view)
        assertEquals(31.5f, radius, 0.001f)
        assertEquals(640, rect.right)
        assertEquals(120, rect.bottom)
    }
}
