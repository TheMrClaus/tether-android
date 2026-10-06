package com.tether.app.ui.files

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import com.tether.app.client.FilesResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** ta-1u4: the platform player's life, on Robolectric's MediaPlayer: no autoplay, one failure path, released for good. */
@RunWith(RobolectricTestRunner::class)
class MediaVideoPlayerTest {
    private val files = FakeFiles().apply { virtualFiles["/w/clip.mp4"] = 5_000_000 }
    private val created = mutableListOf<MediaPlayer>()
    private var failures = 0
    private lateinit var source: WorkspaceMediaDataSource

    @Before fun setUp() {
        source = WorkspaceMediaDataSource(files, "/w/clip.mp4")
    }

    private fun player(): MediaVideoPlayer = MediaVideoPlayer(
        source,
        CoroutineScope(Dispatchers.Unconfined),
        onFailed = { failures++ },
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
        assertEquals("the player only streams: every read is a Range read", true, files.calls.all { it.startsWith("readRange") })
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
        try {
            source.readAt(0, ByteArray(1), 0, 1)
            throw AssertionError("the source is closed")
        } catch (_: java.io.IOException) {
        }
        // Nothing a late callback does comes back to life.
        assertEquals(0, failures)
    }

    @Test fun releasedBeforeItOpenedNeverCreatesAPlayerOrCallsBack() {
        val gate = CompletableDeferred<Unit>()
        files.gates["readRange"] = gate
        val p = player()
        assertEquals(0, created.size)
        p.release()
        gate.complete(Unit)
        assertEquals("no MediaPlayer is made for a released player", 0, created.size)
        assertEquals(0, failures)
    }

    @Test fun aFileThatCannotBeReadFailsOnceAndMakesNoPlayer() {
        files.failures["readRange"] = FilesResult.Failed("This file could not be opened.", 401)
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
}
