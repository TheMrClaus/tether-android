package com.tether.app.ui.video

import android.graphics.Bitmap
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
 * One open video, owned by state outside composition (the file browser's state, chat's tool-clip
 * registry: a rotation, the fullscreen toggle or a layout change re-creates the view, not the
 * playback). Its owner releases it. [phase], [playing] and [buffering] are snapshot state the UI
 * reads.
 */
interface VideoPlayer {
    val phase: VideoPhase
    val playing: Boolean

    /** Playing, but the source is waiting for bytes that have not arrived yet. */
    val buffering: Boolean get() = false

    /**
     * The last picture a surface showed, kept for the moment after that surface is gone (a rotation,
     * scrolled away) until the next one draws its own: a paused or ended platform player does not always
     * paint a frame onto a fresh surface. Set by [keepStill]; the view draws it under the surface.
     */
    val still: Bitmap? get() = null
    fun keepStill(bitmap: Bitmap?) {}

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
 * [VideoPlayer] on the platform [MediaPlayer] over a [PlayableSource], with no media library
 * added. Without [playWhenReady] it stops prepared, on its first frame, until the controller's play
 * is pressed (the file browser's `<video controls preload=metadata>`); with it, it starts as soon as
 * it is prepared (a tap on an inline tool clip, the lightbox's `autoPlay`).
 *
 * Any failure (the data source cannot open, the server errors or changes, the decoder refuses) ends
 * as [VideoPhase.Failed] and one call of [onFailed]; nothing is thrown at the UI. All calls are
 * made on [main]'s thread (the [MediaPlayer] posts its callbacks to the thread that created it).
 */
class MediaVideoPlayer(
    private val source: PlayableSource,
    private val main: CoroutineScope,
    private val onFailed: () -> Unit,
    private val playWhenReady: Boolean = false,
    private val newPlayer: () -> MediaPlayer = { MediaPlayer() },
) : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
        private set
    override var playing: Boolean by mutableStateOf(false)
        private set
    override var buffering: Boolean by mutableStateOf(false)
        private set
    override var still: Bitmap? by mutableStateOf(null)
        private set

    override fun keepStill(bitmap: Bitmap?) {
        if (!released) still = bitmap
    }

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false

    /**
     * Where the platform player is, tracked here because every call is only valid in some states
     * (pause() in PREPARED is an invalid operation: error -38, and the player then reports an error).
     */
    private var engine = Engine.Idle
    private var released = false
    private var buffered by mutableIntStateOf(0)
    private val opening: Job

    init {
        source.onWaiting = { waiting -> main.launch { if (!released) buffering = waiting && playing } }
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
                mp.setOnCompletionListener {
                    if (engine == Engine.Started) engine = Engine.Completed
                    playing = false
                    buffering = false
                }
                mp.setOnBufferingUpdateListener { _, percent -> buffered = percent }
                mp.setOnErrorListener { _, what, _ ->
                    // -38: the platform refusing a call made in the wrong state. Nothing is wrong with the
                    // video; it is never a playback failure (and our own calls are guarded against it).
                    if (what != MEDIA_ERROR_INVALID_OPERATION) fail()
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
        engine = Engine.Prepared
        phase = VideoPhase.Ready(mp.videoWidth, mp.videoHeight)
        if (playWhenReady) {
            control.start()
            return
        }
        // No start(): a seek to the start draws the first frame on the surface and stays paused.
        showFrame()
    }

    private fun showFrame() {
        val mp = player ?: return
        if (!prepared || released || playing || surface == null || engine == Engine.Idle) return
        try {
            // An ended clip's position is its duration, where there is no frame to draw: its last one.
            val at = if (engine == Engine.Completed) (mp.duration - 1).coerceAtLeast(0) else mp.currentPosition
            mp.seekTo(at.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (_: IllegalStateException) {
        }
    }

    private fun fail() {
        if (released || phase == VideoPhase.Failed) return
        phase = VideoPhase.Failed
        playing = false
        buffering = false
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
            // Valid from prepared, paused and completed (a completed clip plays again from its start).
            if (engine == Engine.Started) return
            mp(Unit) {
                // An ended clip plays again from its start; said outright, since a frame seek made on a new
                // surface (showFrame) may have moved its place.
                if (engine == Engine.Completed) it.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                it.start()
                engine = Engine.Started
                playing = true
            }
        }

        override fun pause() {
            // Only a playing player can pause: in PREPARED, PAUSED or COMPLETED it is an invalid operation.
            if (engine != Engine.Started) return
            mp(Unit) {
                it.pause()
                engine = Engine.Paused
                playing = false
                buffering = false
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
        // The picture goes; the player, its place and its playing / paused state stay (a rotation re-attaches).
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
        buffering = false
        still = null
        teardown()
    }

    /** The source first: a reader blocked on the network returns at once instead of holding [MediaPlayer.release]. */
    private fun teardown() {
        source.close()
        val mp = player
        player = null
        surface = null
        prepared = false
        engine = Engine.Idle
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

private enum class Engine { Idle, Prepared, Started, Paused, Completed }

/** MediaPlayer's `MEDIA_ERROR_INVALID_OPERATION` is -38 (the native -ENOSYS), not in the SDK constants. */
private const val MEDIA_ERROR_INVALID_OPERATION = -38
