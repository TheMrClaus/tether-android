package com.tether.app.ui.chat

import android.media.MediaPlayer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tether.app.client.ConnectionState
import com.tether.app.client.DeclaredLengthSink
import com.tether.app.client.TetherClient
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.ui.video.MediaVideoPlayer
import com.tether.app.ui.video.PlayableSource
import com.tether.app.ui.video.VideoPhase
import com.tether.app.ui.video.VideoPlayer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * ta-coik.68: a tool-media clip plays inline as the web's `<video controls>` does
 * (chat-tool-render.tsx:129), while it downloads. The server answers /api/tool-media with 200 and
 * the whole body, never a Range (lib/tool-media-store.mjs:250-264), so a clip is ONE GET into a
 * scratch file that the player reads as it grows; a read of bytes that are not there yet waits for
 * them. The web has no whole-download limit either: only a stall (no bytes for a while) ends it.
 */

/**
 * One clip's download: a single GET of its `/api/tool-media/<sha>.mp4` path (the paired credential,
 * no redirect, today's [HttpToolMedia] rules) into a part file under the server's clip-cache
 * directory, readable while it is written. A cached clip is not fetched again. What the sha256 and
 * MP4 checks decide is only whether the FINISHED file takes its content address (the cache); a
 * player may already be reading the part file before then. The part file is deleted on every exit
 * that is not that rename (a failure, a stall, [close], a cancelled [run]).
 *
 * [run] does the work in the caller's coroutine (one-shot whole-file use, [ToolMediaRepository.video]);
 * [start] does it on a scope of its own that [close] cancels (the player's use).
 */
internal class ClipDownload(
    private val source: ToolMediaSource,
    private val cacheDir: File,
    private val origin: String,
    private val src: String,
    private val expected: String,
    private val stallMs: Long = MediaLimits.VIDEO_STALL_MS,
    private val maxBytes: Long = MediaLimits.MAX_VIDEO_BYTES,
    private val pollMs: Long = (stallMs / 10).coerceIn(10L, 1_000L),
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Everything below is guarded by [lock].
    private var started = false
    private var closed = false
    private var written = 0L
    private var declared = -1L

    /** Every byte of the body is in the file (it may not be verified yet). */
    private var streamEnded = false

    /** Terminal: [MediaVideo.Ok] (verified, cached) or why not. Null while it downloads. */
    private var verdict: MediaVideo? = null
    private var readable: File? = null
    private var part: File? = null
    private var lastProgressNs = System.nanoTime()

    private var scope: CoroutineScope? = null
    private val first = CompletableDeferred<Unit>()

    /** Begins on a scope of this download's own, once. */
    fun start() {
        if (!markStarted()) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lock.withLock { scope = s }
        s.launch { body() }
    }

    /** Does the whole download in the caller's coroutine; returns at its end. A second use does nothing. */
    suspend fun run() {
        if (!markStarted()) return
        body()
    }

    private fun markStarted(): Boolean = lock.withLock { if (started || closed) false else { started = true; true } }

    /** Stops the download, deletes the part file, wakes every reader (their reads then fail). */
    fun close() {
        val (s, p) = lock.withLock {
            if (closed) return
            closed = true
            changed.signalAll()
            scope to part.also { part = null }
        }
        s?.cancel()
        p?.delete()
        first.complete(Unit)
    }

    // --- what a reader sees ---------------------------------------------------------------------

    /** Suspends until a byte is there (or the download ended); false when there is nothing to play. */
    suspend fun awaitReadable(): Boolean {
        first.await()
        return lock.withLock { !closed && (verdict == null || verdict is MediaVideo.Ok) && written > 0 }
    }

    /** The clip's length: the file's once it is whole, the server's declared one before, else unknown (-1). */
    fun size(): Long = lock.withLock { if (streamEnded) written else declared }

    fun readableFile(): File? = lock.withLock { readable }

    fun wake() = lock.withLock { changed.signalAll() }

    /** Why it did not finish, null while it is going well or when it is whole and verified. */
    fun failure(): MediaVideo? = lock.withLock { verdict?.takeIf { it !is MediaVideo.Ok } }

    /** The final answer, null while it is still going. */
    fun outcome(): MediaVideo? = lock.withLock { verdict }

    internal val partFile: File? get() = lock.withLock { part }
    internal val bytesWritten: Long get() = lock.withLock { written }

    /**
     * Blocks until a byte at [position] is in the file and says how many are there from it, or -1 at
     * the end of the clip. Throws when the download failed, was closed, or [readerClosed]. [onWaiting]
     * is told when it starts and stops waiting.
     */
    fun awaitBytes(position: Long, readerClosed: () -> Boolean, onWaiting: (Boolean) -> Unit): Long {
        var waited = false
        lock.lock()
        try {
            while (true) {
                if (closed || readerClosed()) throw IOException("the video was closed")
                val v = verdict
                if (v != null && v !is MediaVideo.Ok) throw IOException("the video could not be downloaded")
                if (position < written) return written - position
                if (streamEnded) return -1
                if (!waited) {
                    waited = true
                    onWaiting(true)
                }
                try {
                    changed.await()
                } catch (_: InterruptedException) {
                    throw IOException("interrupted")
                }
            }
        } finally {
            lock.unlock()
            if (waited) onWaiting(false)
        }
    }

    // --- the download ---------------------------------------------------------------------------

    private fun settle(result: MediaVideo) {
        lock.withLock {
            if (verdict == null) verdict = result
            changed.signalAll()
        }
    }

    private suspend fun body() {
        var temp: File? = null
        try {
            val dir = ToolMediaCache.dirFor(cacheDir, origin).apply { mkdirs() }
            ToolMediaCache.evict(dir)
            val cached = File(dir, "$expected.mp4")
            if (cached.isFile && cached.length() > 0) {
                cached.setLastModified(System.currentTimeMillis())
                lock.withLock {
                    readable = cached
                    written = cached.length()
                    streamEnded = true
                    verdict = MediaVideo.Ok(cached)
                    changed.signalAll()
                }
                return
            }
            // L2: a temp file of its own (never a shared name).
            val file = File.createTempFile(expected, ".part", dir).also { temp = it }
            val go = lock.withLock {
                if (closed) {
                    false
                } else {
                    part = file
                    readable = file
                    lastProgressNs = System.nanoTime()
                    true
                }
            }
            if (!go) return
            val result = fetch(file)
            val answer = conclude(file, cached, result)
            settle(answer)
            if (answer is MediaVideo.Ok) ToolMediaCache.evict(dir)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            // Security review M1: a heap run dry is "too large", never a crash.
            settle(MediaVideo.TooLarge)
        } catch (_: IOException) {
            // R3-L1: the folder swept by a sign-in change mid-download, a full disk: failed, not a crash.
            settle(MediaVideo.Failed)
        } catch (_: RuntimeException) {
            settle(MediaVideo.Failed)
        } finally {
            temp?.let { if (it.exists()) it.delete() }
            lock.withLock { if (part === temp) part = null }
            settle(MediaVideo.Failed)
            first.complete(Unit)
        }
    }

    /** The one GET into [file]; null when it stalled (no bytes for [stallMs]). */
    private suspend fun fetch(file: File): ToolMediaResult? = coroutineScope {
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val fetching = async(Dispatchers.IO) {
            try {
                FileOutputStream(file).use { out -> source.fetch(src, maxBytes, ProgressSink(out)) }
            } catch (_: IOException) {
                ToolMediaResult.Failed()
            }
        }
        val watchdog = launch {
            while (true) {
                delay(pollMs)
                val quiet = lock.withLock { (System.nanoTime() - lastProgressNs) / 1_000_000L }
                if (quiet > stallMs) {
                    stalled.set(true)
                    fetching.cancel()
                    break
                }
            }
        }
        try {
            fetching.await()
        } catch (e: CancellationException) {
            if (stalled.get()) null else throw e
        } finally {
            watchdog.cancel()
        }
    }

    /** What the finished (or failed) fetch comes to; a whole file is hashed, checked and takes its content address. */
    private fun conclude(file: File, cached: File, result: ToolMediaResult?): MediaVideo = when {
        result == null -> MediaVideo.Failed
        result == ToolMediaResult.TooLarge -> MediaVideo.TooLarge
        result is ToolMediaResult.Blocked -> MediaVideo.Blocked
        result !is ToolMediaResult.Ok -> MediaVideo.Failed
        else -> {
            lock.withLock {
                streamEnded = true
                changed.signalAll()
            }
            when {
                // The CLOSED file re-hashed: a server cannot swap content under a name the transcript holds.
                sha256OfFile(file) != expected -> MediaVideo.Failed
                !MediaMagic.matches(file, "video/mp4") -> MediaVideo.Failed
                else -> lock.withLock {
                    if (closed || !file.renameTo(cached)) {
                        MediaVideo.Failed
                    } else {
                        readable = cached
                        part = null
                        MediaVideo.Ok(cached)
                    }
                }
            }
        }
    }

    /** The part file's writer: publishes how far it got to the readers, and learns the declared length. */
    private inner class ProgressSink(private val out: OutputStream) : OutputStream(), DeclaredLengthSink {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            out.write(b, off, len)
            lock.withLock {
                written += len
                lastProgressNs = System.nanoTime()
                changed.signalAll()
            }
            first.complete(Unit)
        }

        override fun flush() = out.flush()

        override fun close() = out.close()

        override fun declaredLength(bytes: Long) {
            lock.withLock {
                declared = bytes
                lastProgressNs = System.nanoTime()
            }
        }
    }
}

/**
 * One player's view of a [ClipDownload] (the inline box and the lightbox each have their own, over
 * the same bytes): `readAt` waits for bytes that are not there yet, never makes a request, and
 * [close] ends only this view; the download is stopped by whoever owns it.
 */
internal class ClipReader(private val download: ClipDownload) : PlayableSource() {
    @Volatile private var closed = false
    private var file: RandomAccessFile? = null

    override suspend fun open(): Boolean = download.awaitReadable()

    override fun getSize(): Long = download.size()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("the video was closed")
        if (size == 0) return 0
        if (position < 0) throw IOException("negative position")
        val available = download.awaitBytes(position, { closed }) { waiting -> onWaiting?.invoke(waiting) }
        if (available < 0) return -1
        val count = minOf(size.toLong(), available).toInt()
        synchronized(this) {
            if (closed) throw IOException("the video was closed")
            val raf = file ?: RandomAccessFile(download.readableFile() ?: throw IOException("no file"), "r").also { file = it }
            raf.seek(position)
            raf.readFully(buffer, offset, count)
        }
        return count
    }

    override fun close() {
        closed = true
        download.wake()
        synchronized(this) {
            try {
                file?.close()
            } catch (_: IOException) {
            }
            file = null
        }
    }
}

/** Where one clip view is, as the row and the viewer draw it. */
sealed interface ClipState {
    /** Never played here, or released: no decoder, no request. */
    data object Idle : ClipState

    /** Playing was asked for; the first frame is not there yet. */
    data object Loading : ClipState

    /** [width] x [height] is the frame's size, 0 x 0 when the clip has no picture. */
    data class Ready(val width: Int, val height: Int) : ClipState

    /** [kind] is [MediaVideo.Failed], [MediaVideo.TooLarge] or [MediaVideo.Blocked]. */
    data class Error(val kind: MediaVideo) : ClipState
}

/** Makes the player for one [PlayableSource]; [onFailed] is its single failure callback. */
internal typealias ClipPlayerFactory = (PlayableSource, () -> Unit) -> VideoPlayer

/**
 * One tool-media clip, keyed by its `src` for the session, owned by the [ToolClipRegistry] (never
 * by a composable: the transcript is a LazyColumn, a rotation re-creates the view, and a clip that
 * scrolls away keeps playing as the web's does). It owns the one download and two views onto it
 * ([inline], [viewer]) that each have their own player, as the web has two `<video>` elements.
 */
@Stable
class ToolClip internal constructor(
    val src: String,
    private val newDownload: () -> ClipDownload?,
    private val makePlayer: ClipPlayerFactory,
) {
    private var download: ClipDownload? = null

    /** The clip's frame size once a player has reported it: a remounted or rotated row never goes back to 2:1. */
    var knownSize by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val inline = ClipView(this)
    val viewer = ClipView(this)

    internal fun noteSize(width: Int, height: Int) {
        if (width > 0 && height > 0) knownSize = width to height
    }

    /** A new view's bytes: the download is made and started on the first one. */
    internal fun reader(): ClipReader? {
        val d = download ?: newDownload()?.also { download = it } ?: return null
        d.start()
        return ClipReader(d)
    }

    internal fun player(reader: ClipReader, onFailed: () -> Unit): VideoPlayer = makePlayer(reader, onFailed)

    internal fun failure(): MediaVideo? = download?.failure()

    internal val currentDownload: ClipDownload? get() = download

    /** Both views idle, the download stopped and its part file gone. A completed clip stays in the cache. */
    fun release() {
        inline.release()
        viewer.release()
        download?.close()
        download = null
    }
}

/** One player's life for a [ToolClip]: nothing until [play], then its [state]; [release] returns it to idle. */
@Stable
class ClipView internal constructor(private val clip: ToolClip) {
    var player: VideoPlayer? by mutableStateOf(null)
        private set
    private var failure: MediaVideo? by mutableStateOf(null)

    val state: ClipState
        get() {
            failure?.let { return ClipState.Error(it) }
            val p = player ?: return ClipState.Idle
            return when (val phase = p.phase) {
                VideoPhase.Opening -> ClipState.Loading
                is VideoPhase.Ready -> ClipState.Ready(phase.width, phase.height)
                VideoPhase.Failed -> ClipState.Error(clip.failure() ?: MediaVideo.Failed)
            }
        }

    /** The first play: the decoder is made here (never on render), the one GET starts, it plays when prepared. */
    fun play() {
        if (player != null || failure != null) return
        val reader = clip.reader()
        if (reader == null) {
            failure = MediaVideo.Failed
            return
        }
        player = clip.player(reader) { failure = clip.failure() ?: MediaVideo.Failed }
    }

    fun release() {
        player?.release()
        player = null
        failure = null
    }
}

/**
 * The session's clips (ta-coik.68): get-or-make the [ToolClip] for a `/api/tool-media/<sha>.mp4`
 * path, release them all ([releaseAll]) when the session ends. Main-thread use. [origin] is read
 * afresh per download (the paired server); a clip with no server is not playable.
 */
class ToolClipRegistry(
    private val source: ToolMediaSource,
    private val cacheDir: File,
    private val origin: () -> String?,
    private val main: CoroutineScope,
    private val stallMs: Long = MediaLimits.VIDEO_STALL_MS,
    private val newPlayer: () -> MediaPlayer = { MediaPlayer() },
    private val makePlayer: ((PlayableSource, () -> Unit) -> VideoPlayer)? = null,
) {
    private val clips = HashMap<String, ToolClip>()

    private val players: ClipPlayerFactory = makePlayer ?: { src, failed ->
        MediaVideoPlayer(src, main, failed, playWhenReady = true, newPlayer = newPlayer)
    }

    /** Null when [src] is not an mp4 tool-media path (nothing else is ever requested). */
    fun clip(src: String): ToolClip? {
        if (ToolMediaSource.extensionOf(src) != "mp4") return null
        val sha = namedSha256(src) ?: return null
        return synchronized(clips) {
            clips.getOrPut(src) {
                ToolClip(src, { origin()?.let { ClipDownload(source, cacheDir, it, src, sha, stallMs) } }, players)
            }
        }
    }

    /** Every clip idle, every download stopped, every part file deleted. */
    fun releaseAll() {
        val all = synchronized(clips) { clips.values.toList() }
        all.forEach { it.release() }
    }
}

/** The transcript's clips; null (previews, tests) = a clip's play does nothing. */
val LocalToolClips = staticCompositionLocalOf<ToolClipRegistry?> { null }

/**
 * Owns the [ToolClipRegistry] outside composition, so a rotation keeps a playing clip, and tears it
 * down on identity: sign-out, a rejected credential or another server (the sign-in generation is
 * part of [identity], as the file browser's ta-3pf teardown) releases every clip and starts a fresh
 * registry. Leaving a session is [releaseAll], called by the chat screen.
 */
class ToolClipsViewModel(
    private val create: (CoroutineScope) -> ToolClipRegistry,
    identity: Flow<String?>,
) : ViewModel() {
    var registry by mutableStateOf(create(viewModelScope))
        private set

    private var bound: String? = null

    init {
        viewModelScope.launch { identity.distinctUntilChanged().collect(::onIdentity) }
    }

    private fun onIdentity(identity: String?) {
        val was = bound
        bound = identity
        if (was == null || was == identity) return
        registry.releaseAll()
        registry = create(viewModelScope)
    }

    fun releaseAll() = registry.releaseAll()

    override fun onCleared() {
        registry.releaseAll()
    }

    companion object {
        /** The server a signed-in client talks to, with the sign-in generation; null while nobody is signed in there. */
        fun identityOf(client: TetherClient): Flow<String?> =
            combine(
                combine(client.configured, client.connection, client.serverUrl, ::identity),
                client.eventLog.map { it.generation }.distinctUntilChanged(),
                ::withGeneration,
            )

        fun withGeneration(identity: String?, generation: Long): String? = identity?.let { "$it#$generation" }

        fun identity(configured: Boolean, connection: ConnectionState, serverUrl: String?): String? =
            if (configured && connection !is ConnectionState.AuthRequired) serverUrl else null
    }
}
