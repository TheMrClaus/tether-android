package com.tether.app.ui.video

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * ta-1u4 design fix: the controller anchors to the view that fills the WHOLE video box (so its bar spans the
 * box and meets its rounded corners), the picture is sized and centred inside it, and a controller that is
 * showing is shown again after the anchor has moved (a rotation). No Compose state is written here.
 */
@RunWith(RobolectricTestRunner::class)
class VideoHostTest {
    private class RecordingController(context: Context) : MediaController(context) {
        var anchor: View? = null
        var showing = false
        val calls = mutableListOf<String>()

        override fun setAnchorView(view: View?) {
            anchor = view
            super.setAnchorView(view)
        }

        override fun show(timeout: Int) { calls += "show($timeout)"; showing = true }
        override fun show() { calls += "show()"; showing = true }
        override fun hide() { calls += "hide"; showing = false }
        override fun isShowing(): Boolean = showing
    }

    private lateinit var activity: Activity
    private lateinit var controller: RecordingController
    private lateinit var host: VideoHost
    private lateinit var fake: RecordingVideoPlayer

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fake = RecordingVideoPlayer()
        host = VideoHost(activity, fake, radiusPx = 12f) { RecordingController(it).also { c -> controller = c } }
        activity.setContentView(host.frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun layoutBox(left: Int, top: Int, width: Int, height: Int) {
        host.frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.frame.layout(left, top, left + width, top + height)
    }

    @Test fun theControllerAnchorsToTheFullBoxFrameNotTheClip() {
        assertSame(host.frame, controller.anchor)
        assertSame("the picture lives inside the anchor", host.frame, host.texture.parent)
    }

    @Test fun thePictureIsSizedForTheClipAndCentredInTheFullBox() {
        // A 162x180 pillarboxed clip in a 482x180 box (the landscape case): the box, not the clip, is the anchor.
        host.sync(ready = false, playing = false, clipWidthPx = 162, clipHeightPx = 180)
        layoutBox(0, 0, 482, 180)
        assertEquals(482, host.frame.width)
        assertEquals(162, host.texture.width)
        assertEquals(180, host.texture.height)
        assertEquals((482 - 162) / 2, host.texture.left)
        assertEquals(Gravity.CENTER, (host.texture.layoutParams as FrameLayout.LayoutParams).gravity)
        // The clip's size changes (rotation): the anchor stays the box.
        host.sync(ready = false, playing = false, clipWidthPx = 320, clipHeightPx = 180)
        layoutBox(0, 0, 320, 180)
        assertEquals(320, host.texture.width)
        assertSame(host.frame, controller.anchor)
    }

    @Test fun anUnknownClipSizeFillsTheBox() {
        host.sync(ready = false, playing = false, clipWidthPx = 0, clipHeightPx = 0)
        layoutBox(0, 0, 400, 150)
        assertEquals(400, host.texture.width)
        assertEquals(150, host.texture.height)
    }

    @Test fun aShowingControllerIsShownAgainOnceTheAnchorHasMoved() {
        layoutBox(0, 0, 480, 200)
        host.sync(ready = true, playing = false, clipWidthPx = 480, clipHeightPx = 200)
        ShadowLooper.idleMainLooper()
        assertEquals("held while paused and ready", listOf("show(0)"), controller.calls.filter { it.startsWith("show") || it == "hide" }.take(1))
        controller.calls.clear()
        // Nothing moved: nothing happens.
        host.repositionIfMoved()
        assertTrue(controller.calls.isEmpty())
        // A rotation lays the box out somewhere else: hide, then show again at the new place.
        layoutBox(0, 60, 800, 300)
        host.repositionIfMoved()
        assertEquals(listOf("hide", "show(0)"), controller.calls)
        // Settled there: no loop.
        controller.calls.clear()
        host.repositionIfMoved()
        assertTrue(controller.calls.isEmpty())
    }

    @Test fun aControllerThatIsNotShowingIsLeftAlone() {
        layoutBox(0, 0, 480, 200)
        host.sync(ready = true, playing = false, clipWidthPx = 480, clipHeightPx = 200)
        ShadowLooper.idleMainLooper()
        controller.showing = false // the user hid it
        controller.calls.clear()
        layoutBox(0, 60, 800, 300)
        host.repositionIfMoved()
        assertTrue(controller.calls.isEmpty())
    }

    @Test fun aControllerThatIsNotAllowedStaysHiddenAndATapDoesNotShowIt() {
        layoutBox(0, 0, 400, 300)
        host.sync(ready = true, playing = false, clipWidthPx = 0, clipHeightPx = 0, controllerAllowed = false)
        ShadowLooper.idleMainLooper()
        assertTrue("never shown while not allowed: ${controller.calls}", controller.calls.none { it.startsWith("show") })
        host.frame.performClick()
        assertTrue(controller.calls.none { it.startsWith("show") })
        host.repositionIfMoved()
        assertTrue(controller.calls.none { it.startsWith("show") })
        // Allowed again (scrolled back into view): it is shown, held (paused).
        host.sync(ready = true, playing = false, clipWidthPx = 0, clipHeightPx = 0, controllerAllowed = true)
        ShadowLooper.idleMainLooper()
        assertTrue(controller.calls.contains("show(0)"))
    }

    @Test fun anAllowedControllerGoesAwayWhenItStopsBeingAllowed() {
        layoutBox(0, 0, 400, 300)
        host.sync(ready = true, playing = false, clipWidthPx = 0, clipHeightPx = 0)
        ShadowLooper.idleMainLooper()
        assertTrue(controller.showing)
        host.sync(ready = true, playing = false, clipWidthPx = 0, clipHeightPx = 0, controllerAllowed = false)
        assertTrue("hidden when the box is scrolled out of the viewport", !controller.showing)
    }

    @Test fun theHostSaysWhenAFrameIsOnItsSurface() {
        val told = mutableListOf<Boolean>()
        host.onFrame = { told += it }
        val texture = android.graphics.SurfaceTexture(0)
        host.texture.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 10, 10)
        assertTrue("a new surface has no frame yet", told.isEmpty())
        host.texture.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
        host.texture.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
        assertEquals("told once", listOf(true), told)
        // The surface goes (a rotation, scrolled off): no frame until the next one.
        host.texture.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
        assertEquals(listOf(true, false), told)
        host.texture.surfaceTextureListener!!.onSurfaceTextureAvailable(android.graphics.SurfaceTexture(1), 10, 10)
        assertEquals("the new surface is blank", listOf(true, false), told)
        host.texture.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
        assertEquals(listOf(true, false, true), told)
    }

    @Test fun aNewFrameClearsTheKeptStillAndAFrameIsNotRecapturedWhenThereIsNone() {
        val texture = android.graphics.SurfaceTexture(0)
        host.texture.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 10, 10)
        // Destroyed before any frame: nothing was drawn, so nothing is kept.
        host.texture.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
        assertTrue(fake.stills.isEmpty())
        host.texture.surfaceTextureListener!!.onSurfaceTextureAvailable(android.graphics.SurfaceTexture(2), 10, 10)
        host.texture.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
        assertEquals("the first frame on a surface clears the still kept for it", listOf<android.graphics.Bitmap?>(null), fake.stills)
    }
}
