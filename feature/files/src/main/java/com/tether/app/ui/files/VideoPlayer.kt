package com.tether.app.ui.files

import android.media.MediaPlayer
import android.view.Surface
import android.widget.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where a preview video is: opening (no frame size yet), ready (prepared, at its first frame), or failed. */
sealed interface VideoPhase {
    data object Opening : VideoPhase

    /** [width] x [height] is the frame's size, 0 x 0 when the file has no picture. */
    data class Ready(val width: Int, val height: Int) : VideoPhase

    data object Failed : VideoPhase
}

/**
 * One open preview video, owned by the browser state (never by a composable: a rotation, the
 * fullscreen toggle or a layout change re-creates the view, not the playback). It is released by
 * the state on Back, another selection, a folder change, the dialog closing, sign-out and a
 * server switch. [phase] and [playing] are snapshot state the UI reads.
 */
interface VideoPlayer {
    val phase: VideoPhase
    val playing: Boolean

    /** What the platform media-controller bar drives (play/pause, seek, times). */
    val control: MediaController.MediaPlayerControl

    /** The frame goes to [surface]. A view that goes away calls [detachSurface] with its own. */
    fun attachSurface(surface: Surface)
    fun detachSurface(surface: Surface)

    /** The app stopped: stop playing but keep the place. */
    fun pause()

    /** Idempotent; the player, its connection and its bytes are gone. */
    fun release()
}

/**
 * [VideoPlayer] on the platform [MediaPlayer] over a [WorkspaceMediaDataSource]: the same engine
 * chat's tool-media clips use, with no media library added. No autoplay: it stops prepared, on its
 * first frame, until the controller's play is pressed (the web's `<video controls preload=metadata>`).
 *
 * Any failure (the data source cannot open, the server errors or changes, the decoder refuses) ends
 * as [VideoPhase.Failed] and one call of [onFailed]; nothing is thrown at the UI. All calls are
 * made on [main]'s thread (the [MediaPlayer] posts its callbacks to the thread that created it).
 */
class MediaVideoPlayer(
    private val source: WorkspaceMediaDataSource,
    private val main: CoroutineScope,
    private val onFailed: () -> Unit,
    private val newPlayer: () -> MediaPlayer = { MediaPlayer() },
) : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
        private set
    override var playing: Boolean by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var released = false
    private var buffered by mutableIntStateOf(0)
    private val opening: Job

    init {
        opening = main.launch {
            val opened = source.open()
            if (released) return@launch
            if (!opened) {
                fail()
                return@launch
            }
            try {
                val mp = newPlayer()
                player = mp
                mp.setOnPreparedListener { onPrepared(it) }
                mp.setOnVideoSizeChangedListener { _, w, h -> if (prepared && !released) phase = VideoPhase.Ready(w, h) }
                mp.setOnCompletionListener { playing = false }
                mp.setOnBufferingUpdateListener { _, percent -> buffered = percent }
                mp.setOnErrorListener { _, _, _ ->
                    fail()
                    true
                }
                mp.setDataSource(source)
                surface?.let(mp::setSurface)
                mp.prepareAsync()
            } catch (_: Exception) {
                fail()
            }
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        if (released) return
        prepared = true
        phase = VideoPhase.Ready(mp.videoWidth, mp.videoHeight)
        // No start(): a seek to the start draws the first frame on the surface and stays paused.
        showFrame()
    }

    private fun showFrame() {
        val mp = player ?: return
        if (!prepared || released || playing || surface == null) return
        try {
            mp.seekTo(mp.currentPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (_: IllegalStateException) {
        }
    }

    private fun fail() {
        if (released || phase == VideoPhase.Failed) return
        phase = VideoPhase.Failed
        playing = false
        teardown()
        onFailed()
    }

    override val control = object : MediaController.MediaPlayerControl {
        private fun <T> mp(fallback: T, block: (MediaPlayer) -> T): T {
            val mp = player ?: return fallback
            if (!prepared || released) return fallback
            return try {
                block(mp)
            } catch (_: IllegalStateException) {
                fallback
            }
        }

        override fun start() {
            mp(Unit) {
                it.start()
                playing = true
            }
        }

        override fun pause() {
            mp(Unit) {
                it.pause()
                playing = false
            }
        }

        override fun getDuration(): Int = mp(0) { it.duration }
        override fun getCurrentPosition(): Int = mp(0) { it.currentPosition }
        override fun seekTo(pos: Int) = mp(Unit) { it.seekTo(pos.toLong(), MediaPlayer.SEEK_CLOSEST) }
        override fun isPlaying(): Boolean = playing
        override fun getBufferPercentage(): Int = buffered
        override fun canPause(): Boolean = true
        override fun canSeekBackward(): Boolean = true
        override fun canSeekForward(): Boolean = true
        override fun getAudioSessionId(): Int = mp(0) { it.audioSessionId }
    }

    override fun attachSurface(surface: Surface) {
        if (released) return
        this.surface = surface
        try {
            player?.setSurface(surface)
        } catch (_: IllegalStateException) {
        }
        // A new surface is blank: paint the frame the video is paused on.
        showFrame()
    }

    override fun detachSurface(surface: Surface) {
        // Only the surface in use: a replaced view's late teardown must not blank its successor's.
        if (this.surface !== surface) return
        this.surface = null
        try {
            player?.setSurface(null)
        } catch (_: IllegalStateException) {
        }
    }

    override fun pause() {
        if (released) return
        control.pause()
    }

    override fun release() {
        if (released) return
        released = true
        opening.cancel()
        playing = false
        teardown()
    }

    /** The source first: a reader blocked on the network returns at once instead of holding [MediaPlayer.release]. */
    private fun teardown() {
        source.close()
        val mp = player
        player = null
        surface = null
        prepared = false
        if (mp != null) {
            try {
                mp.setOnPreparedListener(null)
                mp.setOnErrorListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnVideoSizeChangedListener(null)
                mp.setOnBufferingUpdateListener(null)
                mp.reset()
            } catch (_: Exception) {
            } finally {
                mp.release()
            }
        }
    }
}
