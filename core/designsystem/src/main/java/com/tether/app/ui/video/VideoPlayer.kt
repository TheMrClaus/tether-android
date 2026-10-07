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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** How often the place is checked for [SETTLE_MS] after a surface change while it plays (a seam for tests). */
    private val settleStepMs: Long = 200,
) : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
        private set
    override var playing: Boolean by mutableStateOf(false)
        private set
    override var buffering: Boolean by mutableStateOf(false)
        private set
    override var still: Bitmap? by mutableStateOf(null)
        private set

    /** Bumped whenever a frame was drawn or a surface came: a decode started before that is stale. */
    private var stillEpoch = 0

    /**
     * The picture of the place the clip rests at (paused, ended), decoded from its bytes while a surface still
     * showed it, so a surface that comes later has it at once (a frame drawn clears [still], not this) and
     * does not wait for a decode that a late frame event may make stale. Dropped when the clip moves.
     */
    private var restingStill: Bitmap? = null

    /** Where the clip played to when its surface went away, -1 when it was not playing then (see [watchPlace]). */
    private var placeAtDetach = -1
    private var placeWatch: Job? = null

    /** When [control] last started the clip (nanoTime): an end reported right after is the platform's stale one. */
    private var startedAtNs = 0L
    private var recoveries = 0

    override fun keepStill(bitmap: Bitmap?) {
        if (released) return
        if (bitmap == null) stillEpoch++
        still = bitmap
    }

    /** Where the picture of a clip that is not playing is: its last frame when it ended, else where it is. */
    private fun restingPlace(mp: MediaPlayer): Int {
        val position = mp.currentPosition
        if (engine != Engine.Completed) return position
        // Only a clip that really is at its end rests on its last frame: the bookkeeping saying "ended" while the
        // platform is elsewhere must never move the clip to the end.
        val end = mp.duration
        return if (end > 0 && position >= end - END_SLACK_MS) (end - 1).coerceAtLeast(0) else position
    }

    /**
     * Decodes the picture the clip rests at from its own bytes, for a surface the platform player will not paint
     * onto; kept as [restingStill] and shown as [still] when [show] and no frame is drawn yet.
     */
    private fun loadStill(show: Boolean) {
        val mp = player ?: return
        val at = try {
            restingPlace(mp)
        } catch (_: IllegalStateException) {
            return
        }
        val epoch = if (show) ++stillEpoch else stillEpoch
        main.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    source.stillAt(at, STILL_MAX_SIDE)
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap == null || released || playing) return@launch
            restingStill = bitmap
            if (show && still == null && epoch == stillEpoch) still = bitmap
        }
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
                mp.setOnCompletionListener { onCompleted(it) }
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
            mp.seekTo(restingPlace(mp).toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * The platform is the truth about whether the clip plays: a late "ended" event (the end of a frame seek made
     * on a new surface, delivered after a replay began) must not leave this player believing it is ended while
     * it plays, which a later surface would then answer by seeking the playing clip to its end.
     */
    private fun syncWithPlatform(mp: MediaPlayer) {
        if (released || !prepared || engine == Engine.Started) return
        val running = try {
            mp.isPlaying
        } catch (_: IllegalStateException) {
            false
        }
        if (running) {
            engine = Engine.Started
            playing = true
        }
    }

    private fun onCompleted(mp: MediaPlayer) {
        if (released) return
        if (engine == Engine.Started && staleEnd(mp)) {
            // The platform says "ended" for a clip that was started a moment ago and is nowhere near its end.
            // If it stopped, it plays on from where it is; if it plays, the event is ignored.
            val running = try {
                mp.isPlaying
            } catch (_: IllegalStateException) {
                false
            }
            if (running || recoveries >= MAX_RECOVERIES) return
            recoveries++
            try {
                mp.start()
            } catch (_: IllegalStateException) {
            }
            return
        }
        if (engine == Engine.Started) engine = Engine.Completed
        playing = false
        buffering = false
        placeWatch?.cancel()
        placeAtDetach = -1
        loadStill(show = false)
    }

    private fun staleEnd(mp: MediaPlayer): Boolean = try {
        val end = mp.duration
        val sinceStartMs = (System.nanoTime() - startedAtNs) / 1_000_000L
        end > 2 * END_SLACK_MS && mp.currentPosition < end - END_SLACK_MS && sinceStartMs < STALE_END_MS
    } catch (_: IllegalStateException) {
        false
    }

    /**
     * A surface change while the clip plays can make the platform restart its decoding from an earlier sync
     * frame (the place goes back, up to a whole GOP). For a short while after the new surface is in, the place is
     * checked and, if it fell back behind where it was, put back there. A seek or a pause of the user ends this.
     */
    private fun watchPlace() {
        val at = placeAtDetach
        placeWatch?.cancel()
        if (at < 0) return
        placeWatch = main.launch {
            var corrections = 0
            var waited = 0L
            while (waited < SETTLE_MS && !released && engine == Engine.Started) {
                delay(settleStepMs)
                waited += settleStepMs
                val mp = player ?: return@launch
                val position = try {
                    mp.currentPosition
                } catch (_: IllegalStateException) {
                    return@launch
                }
                if (position < at - PLACE_SLACK_MS && corrections < MAX_PLACE_CORRECTIONS) {
                    corrections++
                    try {
                        mp.seekTo(at.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } catch (_: IllegalStateException) {
                        return@launch
                    }
                }
            }
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
                startedAtNs = System.nanoTime()
                recoveries = 0
                restingStill = null
                placeAtDetach = -1
                placeWatch?.cancel()
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
                placeWatch?.cancel()
                placeAtDetach = -1
                loadStill(show = false)
            }
        }

        override fun getDuration(): Int = mp(0) { it.duration }
        override fun getCurrentPosition(): Int = mp(0) { it.currentPosition }
        override fun seekTo(pos: Int) = mp(Unit) {
            placeWatch?.cancel()
            placeAtDetach = -1
            restingStill = null
            it.seekTo(pos.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
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
        player?.let(::syncWithPlatform)
        if (playing) {
            watchPlace()
            return
        }
        // A new surface is blank: paint the frame the video is paused on, and (a paused or ended platform
        // player does not always paint onto it) have its picture ready until one is drawn: the one decoded when
        // the clip came to rest, else one decoded now.
        showFrame()
        if (prepared && still == null) {
            val ready = restingStill
            if (ready != null) still = ready else loadStill(show = true)
        }
    }

    override fun detachSurface(surface: Surface) {
        // Only the surface in use: a replaced view's late teardown must not blank its successor's.
        if (this.surface !== surface) return
        this.surface = null
        // Where a playing clip is when its picture goes: a surface change may take the platform back from here.
        placeAtDetach = -1
        if (engine == Engine.Started) {
            placeAtDetach = try {
                player?.currentPosition ?: -1
            } catch (_: IllegalStateException) {
                -1
            }
        }
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
        restingStill = null
        placeWatch?.cancel()
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

/** A clip this close (ms) to its duration is at its end. */
private const val END_SLACK_MS = 1_500

/** An "ended" event this soon (ms) after a start, from far before the end, is a stale one. */
private const val STALE_END_MS = 3_000L

private const val MAX_RECOVERIES = 2

/** How long (ms) after a surface change the place of a playing clip is checked, how far (ms) it may have fallen, how often it is put back. */
private const val SETTLE_MS = 4_000L
private const val PLACE_SLACK_MS = 1_000
private const val MAX_PLACE_CORRECTIONS = 3

/** The longest side of a decoded still (px): a box is at most 569 dp wide. */
private const val STILL_MAX_SIDE = 1280

private enum class Engine { Idle, Prepared, Started, Paused, Completed }

/** MediaPlayer's `MEDIA_ERROR_INVALID_OPERATION` is -38 (the native -ENOSYS), not in the SDK constants. */
private const val MEDIA_ERROR_INVALID_OPERATION = -38
