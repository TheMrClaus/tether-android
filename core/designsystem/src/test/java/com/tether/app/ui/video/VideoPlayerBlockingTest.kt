package com.tether.app.ui.video

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * ta-coik.68 round 6 (F-4): the app hung (ANR) when a call on the main thread reached the platform player while the
 * player's data source was inside a [MediaDataSource.readAt] that waits for bytes not downloaded yet (a seek past the
 * download, then a rotation: `TextureView.onSurfaceTextureDestroyed` -> `MediaPlayer.setSurface`). On a device every
 * call into the platform player takes the native player's one lock, which the stuck call holds across the whole wait:
 * [StuckPlatform] models that, with a source whose readAt really waits on a latch. The calls here are the ones the
 * main thread makes (rotation, scroll-out / leave, the controller, the viewer's close, release); each must return in
 * [BOUND_MS] while the read still waits. Before the fix they waited for the read (the watchdog frees the read after
 * [WATCHDOG_MS] so a failing run ends instead of hanging).
 */
@RunWith(RobolectricTestRunner::class)
class VideoPlayerBlockingTest {
    @get:Rule val compose = createComposeRule()

    /** A source whose readAt waits for bytes until [freeReaders] (or [close]): the slow link, a seek past the download. */
    private class WaitingSource : PlayableSource() {
        private val bytes = CountDownLatch(1)
        val reading = AtomicBoolean(false)
        @Volatile var closed = false

        override suspend fun open() = true
        override fun getSize(): Long = 5_000_000

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (closed) throw IOException("closed")
            reading.set(true)
            try {
                bytes.await(60, TimeUnit.SECONDS)
            } finally {
                reading.set(false)
            }
            if (closed) throw IOException("closed")
            return -1
        }

        fun freeReaders() = bytes.countDown()

        override fun close() {
            closed = true
            bytes.countDown()
        }
    }

    /**
     * The platform player on the device: every call into it waits while a read is in progress (the native player's
     * lock is held by the call that is waiting on the data source), and nothing else about it is modelled.
     */
    private class StuckPlatform(private val source: WaitingSource) : MediaPlayer() {
        private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

        /**
         * A copy taken under the list's lock. The player thread appends while the test thread reads; a synchronizedList
         * only locks single calls, so iterating it directly (filter, count) can throw ConcurrentModificationException.
         */
        val calls: List<String> get() = synchronized(recorded) { recorded.toList() }
        @Volatile var prepared: MediaPlayer.OnPreparedListener? = null
        @Volatile var released = false

        /** When set, the platform's reset waits for it (a player thread that is busy letting go). */
        @Volatile var holdReset: CountDownLatch? = null
        @Volatile private var pos = 0
        @Volatile private var running = false

        /** MediaPlayer's own constructor calls some of these before this class's fields exist. */
        private var ready = false

        init {
            ready = true
        }

        private fun native(name: String) {
            if (!ready) return
            recorded += name
            val limit = System.nanoTime() + 30_000_000_000L
            while (source.reading.get() && System.nanoTime() < limit) Thread.sleep(2)
        }

        override fun setDataSource(dataSource: MediaDataSource) = Unit
        override fun prepareAsync() = Unit
        override fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) { prepared = l }
        override fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) = Unit
        override fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) = Unit
        override fun setOnVideoSizeChangedListener(l: MediaPlayer.OnVideoSizeChangedListener?) = Unit
        override fun setOnBufferingUpdateListener(l: MediaPlayer.OnBufferingUpdateListener?) = Unit
        override fun setSurface(surface: Surface?) = native(if (surface == null) "setSurface(null)" else "setSurface")
        override fun start() { native("start"); running = true }
        override fun pause() { native("pause"); running = false }
        override fun seekTo(msec: Long, mode: Int) { native("seekTo($msec)"); pos = msec.toInt() }
        override fun getCurrentPosition(): Int { native("currentPosition"); return pos }
        override fun getDuration(): Int { native("duration"); return 120_000 }
        override fun isPlaying(): Boolean { native("isPlaying"); return running }
        override fun getVideoWidth(): Int { native("videoWidth"); return 320 }
        override fun getVideoHeight(): Int { native("videoHeight"); return 240 }
        override fun getAudioSessionId(): Int { native("audioSessionId"); return 0 }
        override fun reset() {
            native("reset")
            holdReset?.await(30, TimeUnit.SECONDS)
        }
        override fun release() { native("release"); released = true }
    }

    /** A texture that says when it was released. */
    private class WatchedTexture : SurfaceTexture(0) {
        @Volatile var gone = false

        override fun release() {
            gone = true
            super.release()
        }
    }

    private val source = WaitingSource()
    private lateinit var platform: StuckPlatform
    private var failures = 0
    private var watchdog: Thread? = null

    @After fun tearDown() {
        watchdog?.interrupt()
        source.freeReaders()
    }

    private fun surface() = Surface(SurfaceTexture(0))

    private fun until(what: String, check: () -> Boolean) {
        val limit = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < limit) Thread.sleep(5)
        assertTrue("never: $what", check())
    }

    /** A read the platform made on its own thread: it starts waiting on the source, and stays there. */
    private fun startWaitingRead() {
        Thread { try { source.readAt(4_000_000, ByteArray(16), 0, 16) } catch (_: IOException) { } }.apply { isDaemon = true }.start()
        until("a read is waiting for bytes") { source.reading.get() }
        // From here a call that waits for the read is a failure, and the watchdog ends it instead of hanging the run.
        watchdog = Thread {
            try {
                Thread.sleep(WATCHDOG_MS)
                source.freeReaders()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
    }

    /** A clip that plays (on [first], when given). */
    private fun playing(first: Surface?): MediaVideoPlayer {
        val p = MediaVideoPlayer(
            source, CoroutineScope(Dispatchers.Unconfined), onFailed = { failures++ }, playWhenReady = true,
            newPlayer = { StuckPlatform(source).also { platform = it } },
        )
        first?.let(p::attachSurface)
        platform.prepared!!.onPrepared(platform)
        until("it plays") { p.playing }
        return p
    }

    /** A clip that plays on [first] while the platform's read waits (a seek past what is downloaded). */
    private fun playingThenWaiting(first: Surface): MediaVideoPlayer = playing(first).also { startWaitingRead() }

    /** [action] is a call the main thread makes: it returns while the read still waits. */
    private fun mainStaysFree(what: String, action: () -> Unit) {
        val start = System.nanoTime()
        action()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("the main thread was blocked $ms ms in $what while a read waited for bytes (bound $BOUND_MS ms)", ms < BOUND_MS)
    }

    @Test fun aRotationWhileAReadWaitsNeverBlocksTheMainThreadAndKeepsTheCallsInOrder() {
        val first = surface()
        val second = surface()
        val p = playingThenWaiting(first)
        mainStaysFree("detachSurface (the TextureView's surface destroyed)") { p.detachSurface(first) }
        mainStaysFree("attachSurface (the new TextureView's surface)") { p.attachSurface(second) }
        assertTrue("it plays on", p.playing)
        // The read arrives: what was asked of the player happens, in the order it was asked.
        source.freeReaders()
        until("the surface was swapped") { platform.calls.count { it.startsWith("setSurface") } >= 3 }
        assertEquals(listOf("setSurface", "setSurface(null)", "setSurface"), platform.calls.filter { it.startsWith("setSurface") }.takeLast(3))
        assertEquals(0, failures)
        p.release()
    }

    @Test fun theControllerAndAppStopWhileAReadWaitsNeverBlockTheMainThread() {
        val p = playingThenWaiting(surface())
        mainStaysFree("seekTo (the controller's bar)") { p.control.seekTo(90_000) }
        mainStaysFree("getCurrentPosition (the controller's poll)") { p.control.currentPosition }
        mainStaysFree("getDuration") { p.control.duration }
        mainStaysFree("getAudioSessionId") { p.control.audioSessionId }
        mainStaysFree("pause (the controller)") { p.control.pause() }
        mainStaysFree("start (the controller)") { p.control.start() }
        mainStaysFree("pause (the app stopped)") { p.pause() }
        assertFalse(p.playing)
        p.release()
    }

    @Test fun releasingWhileAReadWaitsNeverBlocksTheMainThreadAndFreesThePlayerOnceTheReadReturns() {
        val p = playingThenWaiting(surface())
        mainStaysFree("release (leaving the session, closing the viewer, signing out)") { p.release() }
        assertFalse(p.playing)
        assertTrue("the source is closed at once, which returns the read", source.closed)
        until("the platform player was reset and released") { platform.released }
        assertEquals("reset before release", listOf("reset", "release"), platform.calls.filter { it == "reset" || it == "release" })
        assertEquals(0, failures)
    }

    @Test fun theSurfaceDestroyedOfAPlayingClipReturnsAtOnceAndTheTextureIsKeptUntilThePlayerLetGo() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val p = playing(null)
        val host = VideoHost(activity, p, radiusPx = 12f)
        val texture = WatchedTexture()
        val listener = host.texture.surfaceTextureListener!!
        listener.onSurfaceTextureAvailable(texture, 10, 10)
        until("the host's surface reached the platform") { platform.calls.contains("setSurface") }
        startWaitingRead()
        var release = true
        mainStaysFree("onSurfaceTextureDestroyed (a rotation)") { release = listener.onSurfaceTextureDestroyed(texture) }
        // The player may still be drawing into the texture: the view must not release it; the host does, after.
        assertFalse("the view must leave the texture to the host", release)
        assertFalse("not released while the player may still use it", texture.gone)
        source.freeReaders()
        until("the texture is released once the player let go of its surface") { texture.gone }
        host.dispose()
        p.release()
    }

    @Test fun aSurfaceDestroyedAfterTheReleaseKeepsItsTextureUntilThePlatformPlayerLetGo() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val p = playing(null)
        val host = VideoHost(activity, p, radiusPx = 12f)
        val texture = WatchedTexture()
        val listener = host.texture.surfaceTextureListener!!
        listener.onSurfaceTextureAvailable(texture, 10, 10)
        until("the host's surface reached the platform") { platform.calls.contains("setSurface") }
        // The player is released (the session left): its reset / release is queued on the player's thread, and that
        // thread is busy in the reset when the texture is destroyed.
        val hold = CountDownLatch(1)
        platform.holdReset = hold
        p.release()
        until("the platform player is in its reset") { platform.calls.contains("reset") }
        listener.onSurfaceTextureDestroyed(texture)
        Thread.sleep(300)
        assertFalse("the platform player still holds the surface: the texture must not be released yet", texture.gone)
        assertFalse(platform.released)
        hold.countDown()
        until("the texture is released once the player let go") { texture.gone }
        assertTrue("and only after the platform player was released", platform.released)
        host.dispose()
    }

    private companion object {
        const val BOUND_MS = 1_000L
        const val WATCHDOG_MS = 3_000L
    }
}
