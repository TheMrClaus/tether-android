package com.tether.app.ui.video

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.Surface
import android.widget.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

    /**
     * As [detachSurface], and [onDetached] is called (on any thread) once the player no longer uses [surface]: that
     * can be later, when the player has to wait for something first. A view releases the surface's texture there,
     * not before: a player still drawing into a released texture fails.
     */
    fun detachSurface(surface: Surface, onDetached: () -> Unit) {
        detachSurface(surface)
        onDetached()
    }

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
 * as [VideoPhase.Failed] and one call of [onFailed]; nothing is thrown at the UI.
 *
 * The main thread never calls the platform player after making it (ta-coik.68 round 6, F-4). Every call into a
 * [MediaPlayer] takes the native player's one lock, and a call that is waiting on the data source (a seek past the
 * download, a slow link: [PlayableSource.readAt] waits for bytes) holds it until the bytes arrive, so a call made on
 * the main thread meanwhile (the surface destroyed by a rotation, a seek, a release) hangs the app. So every call goes
 * to one thread of the player's own ([playerThread]), in the order it was asked; what the UI needs to read (place,
 * length) is kept here as a snapshot the player's thread refreshes, and what a call's outcome decides is decided back
 * on [main]. The [MediaPlayer] is made on [main]'s thread (it posts its callbacks to the thread that created it).
 * [release] closes the source first, which returns the read that holds the lock; the reset and release of the
 * platform player then follow on its own thread.
 */
class MediaVideoPlayer(
    private val source: PlayableSource,
    private val main: CoroutineScope,
    private val onFailed: () -> Unit,
    private val playWhenReady: Boolean = false,
    private val newPlayer: () -> MediaPlayer = { MediaPlayer() },
    /** How often the place is checked for [SETTLE_MS] after a surface change while it plays (a seam for tests). */
    private val settleStepMs: Long = 200,
    /** Where the platform player is called (a seam for tests: an inline executor); null is a thread of its own. */
    playerThread: Executor? = null,
) : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
        private set
    override var playing: Boolean by mutableStateOf(false)
        private set
    override var buffering: Boolean by mutableStateOf(false)
        private set
    override var still: Bitmap? by mutableStateOf(null)
        private set

    private val ownThread: ExecutorService? =
        if (playerThread == null) Executors.newSingleThreadExecutor { Thread(it, "tether-video-player").apply { isDaemon = true } } else null
    private val exec: Executor = playerThread ?: ownThread!!

    /** Runs [job] on the player's thread, after everything asked before it; dropped once that thread is gone. */
    private fun onPlayer(job: () -> Unit) {
        try {
            exec.execute {
                try {
                    job()
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * Completed once the platform player's reset and release have run (or at once when there never was one): from
     * then on nothing of it holds a surface. A hand-over that comes after the player's thread was shut down waits
     * for this, never running ahead of the release that is still queued on that thread (a texture released while
     * the platform player may still draw into it).
     */
    private val platformGone = java.util.concurrent.CompletableFuture<Unit>()

    /** Set when a reset / release was queued for a platform player (then [platformGone] is completed by it). */
    @Volatile private var platformQueued = false

    /**
     * As [onPlayer], but [job] (a hand-over, not a call into the platform player) always runs: after what was
     * asked before it on the player's thread, or, when that thread is gone, once the platform player is.
     */
    private fun onPlayerAlways(job: () -> Unit) {
        try {
            exec.execute(job)
        } catch (_: RejectedExecutionException) {
            platformGone.whenComplete { _, _ -> job() }
        }
    }

    /** Asks the player's thread for [block]'s answer without blocking this one; null when it cannot answer. */
    private suspend fun <T : Any> askPlayer(block: () -> T?): T? = suspendCancellableCoroutine { cont ->
        try {
            exec.execute { cont.resume(try { block() } catch (_: Exception) { null }) }
        } catch (_: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    /** Bumped whenever a frame was drawn or a surface came: a decode started before that is stale. */
    private var stillEpoch = 0

    /**
     * The picture of the place the clip rests at (paused, ended), decoded from its bytes while a surface still
     * showed it, so a surface that comes later has it at once (a frame drawn clears [still], not this) and
     * does not wait for a decode that a late frame event may make stale. Dropped when the clip moves.
     */
    private var restingStill: Bitmap? = null

    /** Where the clip played to when its surface went away, -1 when it was not playing then (see [watchPlace]). */
    @Volatile private var placeAtDetach = -1
    private var placeWatch: Job? = null

    /** When [control] last started the clip (nanoTime): an end reported right after is the platform's stale one. */
    private var startedAtNs = 0L
    private var recoveries = 0

    /** The place (ms) as the player's thread last read it, and when (nanoTime): what the controller's bar shows. */
    private class Place(val ms: Int, val atNs: Long)

    @Volatile private var place = Place(0, 0)
    @Volatile private var durationMs = 0
    @Volatile private var audioSession = 0
    private val refreshing = AtomicBoolean(false)

    override fun keepStill(bitmap: Bitmap?) {
        if (released) return
        if (bitmap == null) stillEpoch++
        still = bitmap
    }

    /** Where the picture of a clip that is not playing is: its last frame when it ended, else where it is. On the player's thread. */
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
        val epoch = if (show) ++stillEpoch else stillEpoch
        main.launch {
            val at = askPlayer { restingPlace(mp) } ?: return@launch
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

    /** Reads where the platform player is into [place] (on its thread); at most one read is asked for at a time. */
    private fun refreshPlace() {
        val mp = player ?: return
        if (!refreshing.compareAndSet(false, true)) return
        onPlayer {
            try {
                place = Place(mp.currentPosition, System.nanoTime())
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Where the clip is now: the last read place, carried on by the clock while it plays and is not waiting for bytes. */
    private fun placeNow(): Int {
        val last = place
        if (engine != Engine.Started || buffering) return last.ms
        val ahead = ((System.nanoTime() - last.atNs) / 1_000_000L).toInt().coerceAtLeast(0)
        val end = durationMs
        return if (end > 0) minOf(last.ms + ahead, end) else last.ms + ahead
    }

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var prepared = false

    /**
     * Where the platform player is, tracked here because every call is only valid in some states
     * (pause() in PREPARED is an invalid operation: error -38, and the player then reports an error).
     */
    @Volatile private var engine = Engine.Idle
    @Volatile private var released = false
    private var buffered by mutableIntStateOf(0)
    private val opening: Job

    /** A read of the source is waiting for bytes right now (on [main]). */
    private var waiting = false

    /**
     * Buffering, as the web's media element does on underrun (ta-coik.68 F-5): a read waited for bytes while the clip
     * played, so the platform player is paused (its clock stops with its picture) until [source] has [readAheadBytes]
     * past the read point, then it plays on. [playing] stays true (the user's play is not undone; the bar shows the
     * held time and the spinner). Left to run, a platform player on a link slower than the clip keeps its audio clock
     * going while every video frame is late and dropped: the first frame stayed up while the bar ran to the end.
     * On [main].
     */
    private var stalled = false
    private var readAheadArrived = false
    private var stallJob: Job? = null

    private fun checkStall() {
        if (released || stalled || !waiting || !playing || engine != Engine.Started) return
        val mp = player ?: return
        stalled = true
        readAheadArrived = false
        buffering = true
        onPlayer { mp.pause() }
        stallJob = main.launch {
            withContext(Dispatchers.IO) {
                try {
                    source.awaitReadAhead(readAheadBytes())
                } catch (_: Exception) {
                }
            }
            if (released || !stalled) return@launch
            readAheadArrived = true
            maybeResume()
            buffering = playing && (stalled || waiting)
        }
    }

    /** Plays on once the read that waited has its bytes and the read-ahead is there. A failing source resumes too: the player then meets the failure. */
    private fun maybeResume() {
        if (released || !stalled || !readAheadArrived || waiting || engine != Engine.Started) return
        val mp = player ?: return
        clearStall()
        buffering = false
        place = Place(place.ms, System.nanoTime())
        onPlayer { mp.start() }
    }

    private fun clearStall() {
        stalled = false
        readAheadArrived = false
        stallJob?.cancel()
        stallJob = null
    }

    /** A few seconds of the clip (by the file's mean rate), what a stalled player waits for; asked of the source off the main thread. */
    private fun readAheadBytes(): Long {
        val size = source.size
        val ms = durationMs
        val perMs = if (size > 0 && ms > 0) size.toDouble() / ms else 0.0
        return (perMs * BUFFER_AHEAD_MS).toLong().coerceIn(MIN_READ_AHEAD_BYTES, MAX_READ_AHEAD_BYTES)
    }

    init {
        source.onWaiting = { isWaiting ->
            main.launch {
                if (released) return@launch
                waiting = isWaiting
                if (isWaiting) checkStall() else maybeResume()
                buffering = playing && (stalled || waiting)
            }
        }
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
                onPlayer {
                    try {
                        mp.setDataSource(source)
                        surface?.let(mp::setSurface)
                        mp.prepareAsync()
                    } catch (_: Exception) {
                        main.launch { fail() }
                    }
                }
            } catch (_: Exception) {
                fail()
            }
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        if (released) return
        prepared = true
        engine = Engine.Prepared
        onPlayer {
            val width = mp.videoWidth
            val height = mp.videoHeight
            durationMs = mp.duration
            audioSession = try {
                mp.audioSessionId
            } catch (_: Exception) {
                0
            }
            place = Place(
                try {
                    mp.currentPosition
                } catch (_: Exception) {
                    0
                },
                System.nanoTime(),
            )
            main.launch {
                if (released) return@launch
                phase = VideoPhase.Ready(width, height)
                if (playWhenReady) {
                    control.start()
                    return@launch
                }
                // No start(): a seek to the start draws the first frame on the surface and stays paused.
                showFrame()
            }
        }
    }

    private fun showFrame() {
        val mp = player ?: return
        if (!prepared || released || playing || surface == null || engine == Engine.Idle) return
        onPlayer {
            // A clip started since is not frozen on a frame. An ended clip's position is its duration, where there
            // is no frame to draw: its last one.
            if (!released && engine != Engine.Started) mp.seekTo(restingPlace(mp).toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }

    /**
     * The platform is the truth about whether the clip plays: a late "ended" event (the end of a frame seek made
     * on a new surface, delivered after a replay began) must not leave this player believing it is ended while
     * it plays, which a later surface would then answer by seeking the playing clip to its end.
     */
    private fun syncWithPlatform(running: Boolean) {
        if (released || !prepared || engine == Engine.Started) return
        if (running) {
            engine = Engine.Started
            playing = true
        }
    }

    private class Ending(val end: Int, val position: Int, val running: Boolean)

    private fun onCompleted(mp: MediaPlayer) {
        if (released) return
        if (engine != Engine.Started) {
            endPlayback()
            return
        }
        main.launch {
            val facts = askPlayer { Ending(mp.duration, mp.currentPosition, mp.isPlaying) }
            if (released) return@launch
            val sinceStartMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            // The platform says "ended" for a clip that was started a moment ago and is nowhere near its end.
            // If it stopped, it plays on from where it is; if it plays, the event is ignored.
            val stale = facts != null && engine == Engine.Started &&
                facts.end > 2 * END_SLACK_MS && facts.position < facts.end - END_SLACK_MS && sinceStartMs < STALE_END_MS
            if (stale) {
                if (facts!!.running || recoveries >= MAX_RECOVERIES) return@launch
                recoveries++
                onPlayer { mp.start() }
                return@launch
            }
            endPlayback()
        }
    }

    private fun endPlayback() {
        if (engine == Engine.Started) engine = Engine.Completed
        playing = false
        buffering = false
        clearStall()
        placeWatch?.cancel()
        placeAtDetach = -1
        refreshPlace()
        loadStill(show = false)
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
                val position = askPlayer { mp.currentPosition } ?: return@launch
                if (released || engine != Engine.Started) return@launch
                if (position < at - PLACE_SLACK_MS && corrections < MAX_PLACE_CORRECTIONS) {
                    corrections++
                    onPlayer { mp.seekTo(at.toLong(), MediaPlayer.SEEK_CLOSEST) }
                }
            }
        }
    }

    private fun fail() {
        if (released || phase == VideoPhase.Failed) return
        phase = VideoPhase.Failed
        playing = false
        buffering = false
        clearStall()
        teardown()
        onFailed()
    }

    override val control = object : MediaController.MediaPlayerControl {
        private fun <T> live(fallback: T, block: (MediaPlayer) -> T): T {
            val mp = player ?: return fallback
            if (!prepared || released) return fallback
            return block(mp)
        }

        override fun start() {
            // Valid from prepared, paused and completed (a completed clip plays again from its start).
            if (engine == Engine.Started) return
            live(Unit) { mp ->
                // An ended clip plays again from its start; said outright, since a frame seek made on a new
                // surface (showFrame) may have moved its place.
                val ended = engine == Engine.Completed
                engine = Engine.Started
                playing = true
                startedAtNs = System.nanoTime()
                recoveries = 0
                restingStill = null
                placeAtDetach = -1
                placeWatch?.cancel()
                place = Place(if (ended) 0 else place.ms, startedAtNs)
                onPlayer {
                    if (ended) mp.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                    mp.start()
                }
                // A read that is already waiting (the platform's first reads of a clip still downloading).
                checkStall()
                buffering = playing && (stalled || waiting)
            }
        }

        override fun pause() {
            // Only a playing player can pause: in PREPARED, PAUSED or COMPLETED it is an invalid operation.
            if (engine != Engine.Started) return
            live(Unit) { mp ->
                val at = placeNow()
                engine = Engine.Paused
                playing = false
                buffering = false
                clearStall()
                placeWatch?.cancel()
                placeAtDetach = -1
                place = Place(at, System.nanoTime())
                onPlayer { mp.pause() }
                refreshPlace()
                loadStill(show = false)
            }
        }

        override fun getDuration(): Int = live(0) { durationMs }
        override fun getCurrentPosition(): Int = live(0) {
            refreshPlace()
            placeNow()
        }
        override fun seekTo(pos: Int) = live(Unit) { mp ->
            placeWatch?.cancel()
            placeAtDetach = -1
            restingStill = null
            place = Place(pos, System.nanoTime())
            onPlayer { mp.seekTo(pos.toLong(), MediaPlayer.SEEK_CLOSEST) }
        }
        override fun isPlaying(): Boolean = playing
        override fun getBufferPercentage(): Int = buffered
        override fun canPause(): Boolean = true
        override fun canSeekBackward(): Boolean = true
        override fun canSeekForward(): Boolean = true
        override fun getAudioSessionId(): Int = live(0) { audioSession }
    }

    override fun attachSurface(surface: Surface) {
        if (released) return
        this.surface = surface
        val mp = player
        if (mp == null) {
            settleAttach(surface, running = false)
            return
        }
        onPlayer {
            // A surface that went away (or was replaced) while this waited is not given to the player.
            if (this.surface === surface) {
                try {
                    mp.setSurface(surface)
                } catch (_: IllegalStateException) {
                }
            }
            val running = try {
                mp.isPlaying
            } catch (_: IllegalStateException) {
                false
            }
            main.launch { settleAttach(surface, running) }
        }
    }

    /** What follows a surface being in the platform player, back on [main]: [running] is whether the platform plays. */
    private fun settleAttach(surface: Surface, running: Boolean) {
        if (released || this.surface !== surface) return
        syncWithPlatform(running)
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

    override fun detachSurface(surface: Surface) = detachSurface(surface) {}

    override fun detachSurface(surface: Surface, onDetached: () -> Unit) {
        val mp = player
        // Only the surface in use: a replaced view's late teardown must not blank its successor's.
        if (this.surface !== surface || mp == null) {
            // Nothing of this surface is in the platform player, once what was asked of it before has been done.
            onPlayerAlways(onDetached)
            return
        }
        this.surface = null
        placeAtDetach = -1
        onPlayer {
            // Where a playing clip is when its picture goes: a surface change may take the platform back from here.
            if (engine == Engine.Started) {
                placeAtDetach = try {
                    mp.currentPosition
                } catch (_: IllegalStateException) {
                    -1
                }
            }
            // The picture goes; the player, its place and its playing / paused state stay (a rotation re-attaches).
            try {
                mp.setSurface(null)
            } catch (_: IllegalStateException) {
            }
        }
        onPlayerAlways(onDetached)
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
        clearStall()
        still = null
        restingStill = null
        placeWatch?.cancel()
        teardown()
    }

    /**
     * The source first: a reader blocked on the network returns at once, which frees the platform player's lock. The
     * platform player's reset and release then run on its own thread, after whatever was asked of it before.
     */
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
            } catch (_: Exception) {
            }
            platformQueued = true
            onPlayerAlways {
                try {
                    mp.reset()
                } catch (_: Exception) {
                } finally {
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    platformGone.complete(Unit)
                }
            }
        } else if (!platformQueued) {
            platformGone.complete(Unit)
        }
        // Lets what is queued finish (the release above, a surface's hand-over), then the thread ends.
        ownThread?.shutdown()
    }
}

/** A clip this close (ms) to its duration is at its end. */
private const val END_SLACK_MS = 1_500

/** An "ended" event this soon (ms) after a start, from far before the end, is a stale one. */
private const val STALE_END_MS = 3_000L

private const val MAX_RECOVERIES = 2

/** What a player that waited for bytes has past the read point before it plays on: this much of the clip (by its mean rate), within these bounds. */
private const val BUFFER_AHEAD_MS = 5_000L
private const val MIN_READ_AHEAD_BYTES = 64L * 1024
private const val MAX_READ_AHEAD_BYTES = 8L * 1024 * 1024

/** How long (ms) after a surface change the place of a playing clip is checked, how far (ms) it may have fallen, how often it is put back. */
private const val SETTLE_MS = 4_000L
private const val PLACE_SLACK_MS = 1_000
private const val MAX_PLACE_CORRECTIONS = 3

/** The longest side of a decoded still (px): a box is at most 569 dp wide. */
private const val STILL_MAX_SIDE = 1280

private enum class Engine { Idle, Prepared, Started, Paused, Completed }

/** MediaPlayer's `MEDIA_ERROR_INVALID_OPERATION` is -38 (the native -ENOSYS), not in the SDK constants. */
private const val MEDIA_ERROR_INVALID_OPERATION = -38
