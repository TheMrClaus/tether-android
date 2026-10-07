package com.tether.app.ui.video

import android.media.MediaPlayer
import androidx.compose.ui.test.junit4.createComposeRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * ta-coik.68 F-5 (device): a clip read from a link slower than its bitrate showed its first frame while the control bar's
 * clock ran to the end. A player that is waiting for bytes pauses the platform player (as the web's media element holds
 * its time and waits on underrun) and plays on once enough has arrived past the read point. Like the player's other
 * tests the platform player's thread is run inline; the read-ahead wait is on the player's own IO coroutine.
 */
@RunWith(RobolectricTestRunner::class)
class MediaVideoPlayerBufferingTest {
    @get:Rule val compose = createComposeRule()

    private val created = mutableListOf<MediaPlayer>()
    private val source = FakeSource()

    private fun player(): MediaVideoPlayer = MediaVideoPlayer(
        source,
        CoroutineScope(Dispatchers.Unconfined),
        onFailed = {},
        playerThread = inlinePlayerThread,
        playWhenReady = true,
        newPlayer = {
            MediaPlayer().also {
                created += it
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(source), ShadowMediaPlayer.MediaInfo(60_000, 0))
            }
        },
    )

    private fun playing(): MediaVideoPlayer {
        val p = player()
        shadowOf(created[0]).invokePreparedListener()
        shadowOf(created[0]).setCurrentPosition(5_000)
        assertTrue(created[0].isPlaying)
        return p
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("never: $what")
    }

    @Test fun aReadThatWaitsPausesThePlatformPlayerAndTheClipIsBufferingAtItsPlace() {
        source.readAheadGate = CountDownLatch(1)
        val p = playing()
        source.onWaiting?.invoke(true)
        assertFalse("the platform clock does not run on while the bytes are missing", created[0].isPlaying)
        assertTrue("the user's play is not undone: the bar still offers pause", p.playing)
        assertTrue(p.buffering)
        val held = p.control.currentPosition
        Thread.sleep(80)
        assertEquals("the time is held", held, p.control.currentPosition)
        assertEquals(5_000, held)
        p.release()
    }

    @Test fun itPlaysOnWhenEnoughHasArrivedPastTheReadPoint() {
        source.readAheadGate = CountDownLatch(1)
        val p = playing()
        source.onWaiting?.invoke(true)
        eventually("asked for bytes past the read point") { source.readAheadAsks.isNotEmpty() }
        val ask = source.readAheadAsks.single()
        assertTrue("some seconds of the clip, not one byte and not the whole file: $ask", ask in 65_536L..8L * 1024 * 1024)
        // The read that waited got its bytes but the read-ahead is not there yet: still paused.
        source.onWaiting?.invoke(false)
        assertFalse(created[0].isPlaying)
        assertTrue(p.buffering)
        source.readAheadGate!!.countDown()
        eventually("playing on") { created[0].isPlaying }
        assertFalse(p.buffering)
        assertTrue(p.playing)
        p.release()
    }

    @Test fun aUserPauseWhileBufferingStaysPausedWhenTheBytesArrive() {
        source.readAheadGate = CountDownLatch(1)
        val p = playing()
        source.onWaiting?.invoke(true)
        p.control.pause()
        assertFalse(p.playing)
        assertFalse(p.buffering)
        source.onWaiting?.invoke(false)
        source.readAheadGate!!.countDown()
        Thread.sleep(150)
        assertFalse("the user's pause holds", created[0].isPlaying)
        assertFalse(p.playing)
        // And a play that follows is a play (and a later wait buffers again).
        p.control.start()
        assertTrue(created[0].isPlaying)
        p.release()
    }

    @Test fun releaseWhileBufferingDoesNotBlockAndNothingPlaysAfterIt() {
        source.readAheadGate = CountDownLatch(1)
        val p = playing()
        source.onWaiting?.invoke(true)
        val done = CountDownLatch(1)
        Thread { p.release(); done.countDown() }.start()
        assertTrue("release returned", done.await(5, TimeUnit.SECONDS))
        assertTrue("the source is closed (which frees the wait)", source.closed)
        Thread.sleep(100)
        assertFalse(p.buffering)
        assertFalse(p.playing)
    }

    @Test fun aClipStartedWhileAReadWaitsBuffersFirstAndThenPlays() {
        source.readAheadGate = CountDownLatch(1)
        val p = player()
        source.onWaiting?.invoke(true) // the platform's first reads of a clip still downloading
        shadowOf(created[0]).invokePreparedListener()
        assertTrue(p.playing)
        assertTrue(p.buffering)
        assertFalse(created[0].isPlaying)
        source.onWaiting?.invoke(false)
        source.readAheadGate!!.countDown()
        eventually("playing") { created[0].isPlaying }
        assertFalse(p.buffering)
        p.release()
    }
}
