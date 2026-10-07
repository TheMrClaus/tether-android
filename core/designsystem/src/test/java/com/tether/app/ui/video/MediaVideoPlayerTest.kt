package com.tether.app.ui.video

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * ta-1u4: the platform player's life, on Robolectric's MediaPlayer: no autoplay, one failure path,
 * released for good. ta-8p4l: the player's phase / playing are Compose snapshot state, so the class
 * runs under a compose rule (it applies the global snapshot) rather than writing it bare on the
 * Robolectric main thread, which stalled the recomposition of later classes in the same JVM.
 */
@RunWith(RobolectricTestRunner::class)
class MediaVideoPlayerTest {
    @get:Rule val compose = createComposeRule()

    private val created = mutableListOf<MediaPlayer>()
    private var failures = 0
    private lateinit var source: FakeSource

    @Before fun setUp() {
        source = FakeSource()
    }

    private fun player(): MediaVideoPlayer = MediaVideoPlayer(
        source,
        CoroutineScope(Dispatchers.Unconfined),
        onFailed = { failures++ },
        playerThread = inlinePlayerThread,
        newPlayer = {
            MediaPlayer().also {
                created += it
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(source), ShadowMediaPlayer.MediaInfo(60_000, 0))
            }
        },
    )

    private fun surface() = Surface(SurfaceTexture(0))

    private fun settle() = org.robolectric.shadows.ShadowLooper.idleMainLooper()

    @Test fun itOpensAndStopsOnItsFirstFrameWithoutPlaying() {
        val p = player()
        assertEquals(1, created.size)
        assertTrue(p.phase is VideoPhase.Opening)
        shadowOf(created[0]).invokePreparedListener()
        settle()
        assertTrue(p.phase is VideoPhase.Ready)
        assertFalse("no autoplay", p.playing)
        assertFalse(created[0].isPlaying)
    }

    @Test fun thePlatformControllerStartsAndPauses() {
        val p = player()
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        assertTrue(p.playing)
        assertTrue(created[0].isPlaying)
        p.control.pause()
        assertFalse(p.playing)
        p.control.start()
        p.pause()
        assertFalse("stopping the app pauses", p.playing)
    }

    @Test fun controlsDoNothingBeforeItIsPrepared() {
        val p = player()
        p.control.start()
        assertFalse(p.playing)
        assertEquals(0, p.control.duration)
        assertEquals(0, p.control.currentPosition)
    }

    @Test fun releaseFreesThePlayerAndTheConnectionAndIsIdempotent() {
        val p = player()
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        p.release()
        p.release()
        assertFalse(p.playing)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(created[0]).state)
        assertTrue("the source is closed with the player", source.closed)
        // Nothing a late callback does comes back to life.
        assertEquals(0, failures)
    }

    @Test fun releasedBeforeItOpenedNeverCreatesAPlayerOrCallsBack() {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val p = player()
        assertEquals(0, created.size)
        p.release()
        gate.complete(Unit)
        assertEquals("no MediaPlayer is made for a released player", 0, created.size)
        assertEquals(0, failures)
    }

    @Test fun aFileThatCannotBeReadFailsOnceAndMakesNoPlayer() {
        source.opens = false
        val p = player()
        assertEquals(VideoPhase.Failed, p.phase)
        assertEquals(1, failures)
        assertEquals(0, created.size)
    }

    @Test fun aPlayerErrorFailsOnceAndFreesEverything() {
        val p = player()
        shadowOf(created[0]).invokePreparedListener()
        shadowOf(created[0]).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        assertEquals(VideoPhase.Failed, p.phase)
        assertEquals(1, failures)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(created[0]).state)
        // A second error and a release afterwards change nothing.
        p.release()
        assertEquals(1, failures)
    }

    @Test fun aSurfaceIsAttachedAndOnlyItsOwnerDetachesIt() {
        val p = player()
        val first = surface()
        val second = surface()
        p.attachSurface(first)
        p.attachSurface(second)
        // The replaced view's late teardown must not blank its successor.
        p.detachSurface(first)
        p.release()
    }

    // --- a rotation (ta-1u4 regression): the player outlives its view and is never called in a wrong state ----

    /** Every call that matters, with the platform player's state at the moment of the call. */
    private val calls = mutableListOf<String>()

    private fun recordingPlayer(): MediaVideoPlayer = MediaVideoPlayer(
        source,
        CoroutineScope(Dispatchers.Unconfined),
        onFailed = { failures++ },
        playerThread = inlinePlayerThread,
        newPlayer = {
            object : MediaPlayer() {
                private fun at() = shadowOf(this).state
                override fun start() { calls += "start@${at()}"; super.start() }
                override fun pause() { calls += "pause@${at()}"; super.pause() }
                override fun seekTo(msec: Long, mode: Int) { calls += "seekTo@${at()}"; super.seekTo(msec, mode) }
                override fun setSurface(surface: Surface?) { calls += "setSurface(${if (surface == null) "null" else "surface"})@${at()}"; super.setSurface(surface) }
                override fun reset() { calls += "reset"; super.reset() }
                override fun release() { calls += "release"; super.release() }
            }.also {
                created += it
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(source), ShadowMediaPlayer.MediaInfo(60_000, 0))
            }
        },
    )

    /** What a configuration change does to the view: the old surface goes, a new one comes. */
    private fun MediaVideoPlayer.rotate(old: Surface): Surface {
        detachSurface(old)
        return surface().also { attachSurface(it) }
    }

    private fun assertNeverInvalid() {
        assertTrue("pause outside a started player: $calls", calls.none { it.startsWith("pause@") && it != "pause@STARTED" })
        assertTrue("no reset / release: $calls", calls.none { it == "reset" || it == "release" })
        assertEquals("no failure surfaced", 0, failures)
    }

    @Test fun rotatingWhilePreparedNeverPausesAndKeepsTheFirstFrame() {
        val p = recordingPlayer()
        val first = surface()
        p.attachSurface(first)
        shadowOf(created[0]).invokePreparedListener()
        val second = p.rotate(first)
        // The app-stop path ran too (the old activity stopped): nothing to pause, nothing happens.
        p.pause()
        p.rotate(second)
        assertTrue(p.phase is VideoPhase.Ready)
        assertFalse(p.playing)
        assertEquals(ShadowMediaPlayer.State.PREPARED, shadowOf(created[0]).state)
        assertTrue("the frame is painted again on the new surface: $calls", calls.any { it.startsWith("seekTo@") })
        assertNeverInvalid()
    }

    @Test fun anInvalidOperationErrorIsNotAPlaybackFailure() {
        val p = recordingPlayer()
        shadowOf(created[0]).invokePreparedListener()
        shadowOf(created[0]).invokeErrorListener(-38, 0)
        assertTrue(p.phase is VideoPhase.Ready)
        assertEquals(0, failures)
        assertNeverInvalid()
        // A real error still is one.
        shadowOf(created[0]).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        assertEquals(VideoPhase.Failed, p.phase)
        assertEquals(1, failures)
    }

    @Test fun rotatingWhilePlayingKeepsPlayingWithoutAPauseOrAReset() {
        val p = recordingPlayer()
        val first = surface()
        p.attachSurface(first)
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        val second = p.rotate(first)
        p.rotate(second)
        assertTrue(p.playing)
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(created[0]).state)
        assertTrue("no frame seek while it plays: $calls", calls.drop(calls.indexOf("start@PREPARED")).none { it.startsWith("seekTo@") })
        assertNeverInvalid()
    }

    @Test fun rotatingWhilePausedStaysPausedAndRepaintsItsFrame() {
        val p = recordingPlayer()
        val first = surface()
        p.attachSurface(first)
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        p.control.pause()
        val before = calls.count { it.startsWith("seekTo@") }
        val second = p.rotate(first)
        p.pause() // the app-stop path: already paused, no second pause
        p.rotate(second)
        assertFalse(p.playing)
        assertEquals(ShadowMediaPlayer.State.PAUSED, shadowOf(created[0]).state)
        assertTrue("the paused frame is repainted: $calls", calls.count { it.startsWith("seekTo@") } > before)
        assertEquals("one pause, the user's", 1, calls.count { it.startsWith("pause@") })
        assertNeverInvalid()
    }

    @Test fun rotatingAfterCompletionNeverPausesAndCanPlayAgain() {
        val p = recordingPlayer()
        val first = surface()
        p.attachSurface(first)
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        shadowOf(created[0]).setCurrentPosition(60_000) // the end: where the platform is when a clip really ended
        shadowOf(created[0]).invokeCompletionListener()
        assertFalse(p.playing)
        val second = p.rotate(first)
        p.pause()
        p.rotate(second)
        assertFalse(p.playing)
        assertTrue("no pause after the clip ended: $calls", calls.none { it.startsWith("pause@") })
        p.control.start()
        assertTrue(p.playing)
        assertEquals(0, failures)
    }

    @Test fun stoppingTheAppPausesAPlayingVideoOnceAndAPreparedOneNever() {
        val p = recordingPlayer()
        shadowOf(created[0]).invokePreparedListener()
        p.pause()
        assertTrue(calls.none { it.startsWith("pause@") })
        p.control.start()
        p.pause()
        p.pause()
        assertEquals(listOf("pause@STARTED"), calls.filter { it.startsWith("pause@") })
        assertFalse(p.playing)
    }

    // --- ta-coik.68: play on prepare, and a source that waits ----------------------------------------------

    private fun autoPlayer(): MediaVideoPlayer = MediaVideoPlayer(
        source,
        CoroutineScope(Dispatchers.Unconfined),
        onFailed = { failures++ },
        playerThread = inlinePlayerThread,
        playWhenReady = true,
        newPlayer = {
            MediaPlayer().also {
                created += it
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(source), ShadowMediaPlayer.MediaInfo(60_000, 0))
            }
        },
    )

    @Test fun playWhenReadyStartsAsSoonAsItIsPreparedAndNotBefore() {
        val p = autoPlayer()
        assertFalse("not before it is prepared", p.playing)
        shadowOf(created[0]).invokePreparedListener()
        assertTrue(p.phase is VideoPhase.Ready)
        assertTrue(p.playing)
        assertTrue(created[0].isPlaying)
        assertEquals(0, failures)
    }

    @Test fun aReadThatWaitsShowsAsBufferingOnlyWhilePlaying() {
        val p = autoPlayer()
        shadowOf(created[0]).invokePreparedListener()
        source.onWaiting?.invoke(true)
        assertTrue(p.buffering)
        source.onWaiting?.invoke(false)
        // ta-coik.68 F-5: it keeps buffering until the source has the bytes past the read point (here: at once, on its own coroutine).
        val until = System.nanoTime() + 10_000_000_000L
        while (p.buffering && System.nanoTime() < until) Thread.sleep(10)
        assertFalse(p.buffering)
        p.control.pause()
        source.onWaiting?.invoke(true)
        assertFalse("paused: nothing is buffering", p.buffering)
    }

    @Test fun releaseClearsBufferingAndLateWaitsChangeNothing() {
        val p = autoPlayer()
        shadowOf(created[0]).invokePreparedListener()
        source.onWaiting?.invoke(true)
        p.release()
        assertFalse(p.buffering)
        source.onWaiting?.invoke(true)
        assertFalse(p.buffering)
    }

    // --- ta-coik.68 device findings: an ended clip's picture comes back; its play restarts from 0 -----------

    @Test fun anEndedClipRepaintsItsLastFrameOnANewSurfaceNotItsPastTheEndPosition() {
        val p = recordingPlayer()
        val first = surface()
        p.attachSurface(first)
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        shadowOf(created[0]).setCurrentPosition(60_000) // the end: where the platform is when a clip really ended
        shadowOf(created[0]).invokeCompletionListener()
        calls.clear()
        val second = p.rotate(first)
        assertTrue("the frame is redrawn on the new surface: $calls", calls.any { it.startsWith("seekTo@") })
        assertEquals(0, failures)
        p.detachSurface(second)
    }

    @Test fun anEndedClipsPlaySeeksToItsStartThenStarts() {
        val p = recordingPlayer()
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        shadowOf(created[0]).setCurrentPosition(60_000) // the end: where the platform is when a clip really ended
        shadowOf(created[0]).invokeCompletionListener()
        calls.clear()
        p.control.start()
        assertTrue(p.playing)
        val seek = calls.indexOfFirst { it.startsWith("seekTo@") }
        val start = calls.indexOfFirst { it.startsWith("start@") }
        assertTrue("seek to 0 before the start: $calls", seek in 0 until start)
    }

    @Test fun aPausedClipRepaintsItsFrameOnANewSurface() {
        val p = recordingPlayer()
        val first = surface()
        p.attachSurface(first)
        shadowOf(created[0]).invokePreparedListener()
        p.control.start()
        p.control.pause()
        calls.clear()
        p.rotate(first)
        assertTrue("the paused frame is redrawn: $calls", calls.any { it.startsWith("seekTo@") })
    }

    @Test fun theKeptStillIsTheViewsUntilANewFrameOrRelease() {
        val p = autoPlayer()
        shadowOf(created[0]).invokePreparedListener()
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        assertEquals(null, p.still)
        p.keepStill(bitmap)
        assertTrue(p.still === bitmap)
        p.keepStill(null)
        assertEquals(null, p.still)
        p.keepStill(bitmap)
        p.release()
        assertEquals("released: no picture is held", null, p.still)
        p.keepStill(bitmap)
        assertEquals(null, p.still)
    }

    // --- ta-coik.68 round 4: a picture from the bytes when the platform player paints nothing ----------------

    private fun awaitStill(p: MediaVideoPlayer): android.graphics.Bitmap? {
        val until = System.nanoTime() + 5_000_000_000L
        while (p.still == null && System.nanoTime() < until) Thread.sleep(10)
        return p.still
    }

    @Test fun aPausedClipOnANewSurfaceGetsItsPictureDecodedFromItsBytes() {
        val marker = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        source.still = marker
        val p = player()
        shadowOf(created[0]).invokePreparedListener()
        p.attachSurface(surface())
        assertTrue("decoded from the source, no surface involved", awaitStill(p) === marker)
        // The first frame the surface paints (the host's clear) takes it away.
        p.keepStill(null)
        assertEquals(null, p.still)
    }

    @Test fun aPlayingClipNeedsNoDecodedPictureAndALateDecodeAfterAFrameIsDropped() {
        val marker = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        source.still = marker
        val p = autoPlayer()
        shadowOf(created[0]).invokePreparedListener()
        p.attachSurface(surface())
        assertEquals(0, source.stillCalls)
        // Paused with a surface: decode requested; a frame drawn meanwhile (the epoch moves) makes the result stale.
        p.control.pause()
        val gate = java.util.concurrent.CountDownLatch(1)
        source.stillGate = gate
        p.attachSurface(surface())
        p.keepStill(null)
        gate.countDown()
        Thread.sleep(300)
        assertEquals("stale: a frame was drawn first", null, p.still)
    }

    // --- ta-coik.68 round 5 (F-1, F-2): a playing clip keeps its place through a surface change; an ended or
    // paused one keeps its picture. The platform is modelled: what a surface change does to its place, the late
    // "ended" event, and a picture that only the clip's own bytes can give. ---------------------------------------

    /** A platform player with a place of its own, which the tests move the way the device was seen to. */
    private class Platform : MediaPlayer() {
        @Volatile var pos = 0
        @Volatile var running = false
        val dur = 120_000
        val seeks = java.util.Collections.synchronizedList(mutableListOf<Int>())
        var prepared: MediaPlayer.OnPreparedListener? = null
        var completed: MediaPlayer.OnCompletionListener? = null

        /** What a surface change does to a playing clip: it restarts from an earlier sync frame (null: nothing). */
        var rewindsTo: Int? = null

        override fun setDataSource(dataSource: android.media.MediaDataSource) = Unit
        override fun prepareAsync() = Unit
        override fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) { prepared = l }
        override fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) { completed = l }
        override fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) = Unit
        override fun setOnVideoSizeChangedListener(l: MediaPlayer.OnVideoSizeChangedListener?) = Unit
        override fun setOnBufferingUpdateListener(l: MediaPlayer.OnBufferingUpdateListener?) = Unit
        override fun setSurface(surface: Surface?) {
            val to = rewindsTo
            if (running && to != null && pos > to) pos = to
        }
        override fun start() { running = true }
        override fun pause() { running = false }
        override fun seekTo(msec: Long, mode: Int) { seeks += msec.toInt(); pos = msec.toInt() }
        override fun getCurrentPosition() = pos
        override fun getDuration() = dur
        override fun isPlaying() = running
        override fun getVideoWidth() = 320
        override fun getVideoHeight() = 240
        override fun getAudioSessionId() = 0
        override fun reset() = Unit
        override fun release() = Unit

        fun ends() { pos = dur; running = false; completed?.onCompletion(this) }
    }

    private val platforms = mutableListOf<Platform>()

    private fun modelled(): MediaVideoPlayer {
        val p = MediaVideoPlayer(
            source, CoroutineScope(Dispatchers.Unconfined), onFailed = { failures++ }, playWhenReady = false,
            newPlayer = { Platform().also { platforms += it } }, settleStepMs = 10, playerThread = inlinePlayerThread,
        )
        platforms[0].prepared?.onPrepared(platforms[0])
        return p
    }

    private fun until(what: String, check: () -> Boolean) {
        val limit = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < limit) Thread.sleep(10)
        assertTrue("never: $what", check())
    }

    @Test fun aPlayingClipIsPutBackWhereItWasWhenASurfaceChangeRewoundIt() {
        val p = modelled()
        val platform = platforms[0]
        val first = surface()
        p.attachSurface(first)
        p.control.start()
        platform.pos = 62_667
        platform.rewindsTo = 53_000
        val second = p.rotate(first)
        assertTrue("it is still playing", p.playing && platform.running)
        until("the place is where it was (at ${platform.pos})") { platform.pos >= 62_000 }
        assertTrue(p.playing && platform.running)
        p.detachSurface(second)
        p.release()
    }

    @Test fun aReplayedClipWhoseStaleEndEventArrivesLateKeepsPlayingThroughARotation() {
        val p = modelled()
        val platform = platforms[0]
        val first = surface()
        p.attachSurface(first)
        p.control.start()
        platform.ends()
        assertFalse(p.playing)
        // Play again from the start; the end of the frame seek a new surface made at the end arrives late.
        p.control.start()
        assertTrue(p.playing)
        platform.pos = 800
        platform.completed?.onCompletion(platform)
        assertTrue("a stale end does not end a clip that has just been started", p.playing)
        platform.pos = 20_000
        platform.seeks.clear()
        val second = p.rotate(first)
        p.rotate(second)
        assertTrue("still playing after the rotation", p.playing && platform.running)
        assertTrue("never sought to its end: ${platform.seeks}", platform.seeks.none { it >= platform.dur - 1_000 })
        assertEquals("from where it was", 20_000, platform.pos)
    }

    @Test fun aClipTheBookkeepingCallsEndedButThePlatformPlaysIsNotSoughtToItsEnd() {
        val p = modelled()
        val platform = platforms[0]
        val first = surface()
        p.attachSurface(first)
        p.control.start()
        platform.ends()
        // The platform plays on (a replay the bookkeeping missed) at 20 s.
        platform.running = true
        platform.pos = 20_000
        platform.seeks.clear()
        p.rotate(first)
        assertTrue("the platform is the truth: it plays", p.playing)
        assertTrue("not sought to the end: ${platform.seeks}", platform.seeks.none { it >= platform.dur - 1_000 })
    }

    @Test fun anEndedClipHasItsPictureAtOnceOnANewSurfaceWithNoDecodeToWaitFor() {
        val marker = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        source.still = marker
        val p = modelled()
        val platform = platforms[0]
        val first = surface()
        p.attachSurface(first)
        p.control.start()
        platform.ends()
        until("the picture of the end was decoded when it ended") { source.stillCalls >= 1 }
        Thread.sleep(200)
        // A frame was drawn on the first surface (clears the held picture), then a rotation: the next surface
        // has the picture in the same pass, before any decode could have finished.
        p.keepStill(null)
        source.stillGate = java.util.concurrent.CountDownLatch(1)
        val second = p.rotate(first)
        assertTrue("the ended picture is there at once", p.still === marker)
        source.stillGate?.countDown()
        p.keepStill(null)
        p.rotate(second)
        assertTrue("and again on the next surface", p.still === marker)
    }

    @Test fun aPausedClipHasItsPictureAtOnceOnANewSurface() {
        val marker = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        source.still = marker
        val p = modelled()
        val first = surface()
        p.attachSurface(first)
        p.control.start()
        platforms[0].pos = 9_000
        p.control.pause()
        until("the paused picture was decoded") { source.stillCalls >= 1 }
        Thread.sleep(200)
        p.keepStill(null)
        source.stillGate = java.util.concurrent.CountDownLatch(1)
        p.rotate(first)
        assertTrue("the paused picture is there at once", p.still === marker)
        source.stillGate?.countDown()
    }

    @Test fun aFrameSeekNeverMovesAClipThatIsNotAtItsEndToItsEnd() {
        val p = modelled()
        val platform = platforms[0]
        val first = surface()
        p.attachSurface(first)
        p.control.start()
        platform.ends()
        assertFalse(p.playing)
        // The user drags the bar back on the ended clip: the engine still says "ended", the place is 10 s.
        p.control.seekTo(10_000)
        assertEquals(10_000, platform.pos)
        platform.seeks.clear()
        // A surface change repaints the frame the clip rests at: that place, not its last frame.
        val second = p.rotate(first)
        assertEquals("the frame is shown at the place it is at: ${platform.seeks}", listOf(10_000), platform.seeks)
        p.rotate(second)
        assertTrue("never to its end: ${platform.seeks}", platform.seeks.none { it >= platform.dur - 1_500 })
        // A clip that really is at its end still rests on its last frame, not past it.
        platform.ends()
        platform.seeks.clear()
        p.rotate(second)
        assertEquals(listOf(platform.dur - 1), platform.seeks.take(1))
    }
}
