package com.tether.app.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ta-coik.68: a tool-media clip plays inline as the web's `<video controls>` does
 * (chat-tool-render.tsx:129), while it downloads. The server answers /api/tool-media with 200 and
 * the whole body, never a Range (lib/tool-media-store.mjs:250-264), so a clip is ONE GET into a
 * scratch file that the player reads as it grows; a read of bytes that are not there yet waits for
 * them. The web has no whole-download limit and no stall timer either (a plain `<video src controls>`):
 * a slow or stalled clip just waits; only a real error (a connect failure, an HTTP error, a dropped socket)
 * ends it, and a failed clip is played again with a fresh download.
 */

/**
 * One clip's download: a single GET of its `/api/tool-media/<sha>.mp4` path (the paired credential,
 * no redirect, today's [HttpToolMedia] rules) into a part file under the server's clip-cache
 * directory, readable while it is written. A cached clip is not fetched again. What the sha256 and
 * MP4 checks decide is only whether the FINISHED file takes its content address (the cache): a clip
 * that fails them is not cached, but it is never stopped or failed for it, a player keeps reading the
 * part file to its end (the web plays whatever the server sends). The part file is deleted on every
 * exit that is not that rename (a failure, [close], a cancelled [run]); a file that failed the checks
 * but is being played from is deleted by [close].
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
    private val maxBytes: Long = MediaLimits.MAX_VIDEO_BYTES,
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

    /**
     * The source answered [ToolMediaResult.Ok]: the whole body is in the part file and only its check and its
     * move into the cache are left. A [close] that lands now (the session left on the last byte) must not
     * throw that clip away: a clip that finished and verifies is kept.
     */
    private var fetchedOk: ToolMediaResult.Ok? = null

    /** The body is whole: the source said so, or every byte of the length the server declared is written. Under [lock]. */
    private fun wholeBody(): ToolMediaResult.Ok? =
        fetchedOk ?: if (declared >= 0 && written >= declared) ToolMediaResult.Ok(written, "video/mp4") else null

    /** Terminal: [MediaVideo.Ok] (verified, cached) or why not. Null while it downloads. */
    private var verdict: MediaVideo? = null
    private var readable: File? = null
    private var part: File? = null

    /**
     * The whole body is in the part file but the checks (sha256, MP4 magic) or the rename into the cache said no: the
     * clip is not cached and is no "verified clip" for the one-shot [outcome], but a player reading it plays it to
     * the end. Under [lock].
     */
    private var unverified = false

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
            // A whole body whose check and cache rename are still to come is not thrown away (see [fetchedOk]).
            scope to (if (verdict == null && wholeBody() != null) null else part.also { part = null })
        }
        s?.cancel()
        p?.let { ToolMediaCache.deletePart(it) }
        first.complete(Unit)
    }

    // --- what a reader sees ---------------------------------------------------------------------

    /** Suspends until a byte is there (or the download ended); false when there is nothing to play. */
    suspend fun awaitReadable(): Boolean {
        first.await()
        return lock.withLock { !closed && (verdict == null || verdict is MediaVideo.Ok || unverified) && written > 0 }
    }

    /** The clip's length: the file's once it is whole, the server's declared one before, else unknown (-1). */
    fun size(): Long = lock.withLock { if (streamEnded) written else declared }

    fun readableFile(): File? = lock.withLock { readable }

    fun wake() = lock.withLock { changed.signalAll() }

    /**
     * Why it did not finish, null while it is going well or when every byte is there (a clip that is not cached
     * for failing a check is not a failure: it plays).
     */
    fun failure(): MediaVideo? = lock.withLock { verdict?.takeIf { it !is MediaVideo.Ok && !unverified } }

    /** Every byte is in a file a retriever can read whole (verified and cached, or whole but not cached). */
    fun isWhole(): Boolean = lock.withLock { verdict is MediaVideo.Ok || unverified }

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
                // A transport failure ends the read; a clip that only failed the cache's checks is read to its end.
                if (v != null && v !is MediaVideo.Ok && !unverified) throw IOException("the video could not be downloaded")
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

    /**
     * Blocks until the file has at least [target] bytes, or the body is whole, or this download was closed or failed
     * (it then returns as well: the reader finds the failure on its next read). [target] is read again at every wake-up.
     */
    fun awaitFrontier(target: () -> Long, readerClosed: () -> Boolean) {
        lock.lock()
        try {
            while (true) {
                if (closed || readerClosed()) return
                val v = verdict
                if (v != null && v !is MediaVideo.Ok && !unverified) return
                if (written >= target() || streamEnded) return
                try {
                    changed.await()
                } catch (_: InterruptedException) {
                    return
                }
            }
        } finally {
            lock.unlock()
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
            // Held while this download runs: another clip's start sweeps the folder and must not take it (a stalled
            // clip's part is old by its mtime and is still being played).
            ToolMediaCache.hold(file)
            val go = lock.withLock {
                if (closed) {
                    false
                } else {
                    part = file
                    readable = file
                    true
                }
            }
            if (!go) return
            val result = try {
                fetch(file)
            } catch (e: CancellationException) {
                // Released on the very last byte: a body that arrived whole is still checked and cached.
                val whole = lock.withLock { wholeBody() }
                if (whole != null) settle(conclude(file, cached, whole))
                throw e
            }
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
            // A whole file that failed the cache's checks and is being played from stays until [close]; any other
            // part is deleted here.
            val kept = lock.withLock { (unverified && !closed && scope != null && part === temp).also { if (!it && part === temp) part = null } }
            if (!kept) temp?.let { ToolMediaCache.deletePart(it) }
            settle(MediaVideo.Failed)
            first.complete(Unit)
        }
    }

    /**
     * The one GET into [file]. No stall rule: like the web's `<video>`, a slow or stalled body waits (the source
     * has no read timeout); a connect failure, an HTTP error or a dropped socket is its answer.
     */
    private suspend fun fetch(file: File): ToolMediaResult = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(file).use { out -> source.fetch(src, maxBytes, ProgressSink(out)) }
                .also { if (it is ToolMediaResult.Ok) lock.withLock { fetchedOk = it } }
        } catch (_: IOException) {
            ToolMediaResult.Failed()
        }
    }

    /** What the finished (or failed) fetch comes to; a whole file is hashed, checked and, when it passes, takes its content address. */
    private fun conclude(file: File, cached: File, result: ToolMediaResult): MediaVideo = when {
        result == ToolMediaResult.TooLarge -> MediaVideo.TooLarge
        result is ToolMediaResult.Blocked -> MediaVideo.Blocked
        result !is ToolMediaResult.Ok -> MediaVideo.Failed
        else -> {
            lock.withLock {
                streamEnded = true
                changed.signalAll()
            }
            // These checks decide only whether the finished file takes its content address (the cache): a file that
            // fails them is not cached and is never stopped, a player reading it plays it to its end.
            when {
                // The CLOSED file re-hashed: a server cannot swap content under a name the transcript holds.
                sha256OfFile(file) != expected -> notCached()
                !MediaMagic.matches(file, "video/mp4") -> notCached()
                // Closed or not: a finished, verified clip takes its content address (see [fetchedOk]).
                else -> lock.withLock {
                    if (!file.renameTo(cached)) {
                        unverified = true
                        MediaVideo.Failed
                    } else {
                        ToolMediaCache.release(file)
                        readable = cached
                        part = null
                        MediaVideo.Ok(cached)
                    }
                }
            }
        }
    }

    /** The whole body is in the part file but is not what the cache keeps: not cached, still playable. */
    private fun notCached(): MediaVideo {
        lock.withLock {
            unverified = true
            changed.signalAll()
        }
        return MediaVideo.Failed
    }

    /** The part file's writer: publishes how far it got to the readers, and learns the declared length. */
    private inner class ProgressSink(private val out: OutputStream) : OutputStream(), DeclaredLengthSink {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            out.write(b, off, len)
            lock.withLock {
                written += len
                changed.signalAll()
            }
            first.complete(Unit)
        }

        override fun flush() = out.flush()

        override fun close() = out.close()

        override fun declaredLength(bytes: Long) {
            lock.withLock { declared = bytes }
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

    /** Where the read that last had to wait for bytes was: what the player's buffering is measured from. */
    @Volatile private var waitedAt = 0L

    override fun awaitReadAhead(bytes: Long) = download.awaitFrontier({ waitedAt + bytes }, { closed })

    override fun getSize(): Long = download.size()

    override fun stillAt(positionMs: Int, maxSide: Int): Bitmap? {
        // Only a whole, verified file: a partial one may have its moov atom still to come.
        if (!download.isWhole()) return null
        val file = download.readableFile() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            retriever.getScaledFrameAtTime(
                positionMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("the video was closed")
        if (size == 0) return 0
        if (position < 0) throw IOException("negative position")
        val available = download.awaitBytes(position, { closed }) { waiting ->
            if (waiting) waitedAt = position
            onWaiting?.invoke(waiting)
        }
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

    /**
     * A new view's bytes: the download is made and started on the first one. A download that failed is not reused:
     * the web plays a failed `<video>` again (its native controls, or a re-mount), so a play after a failure
     * starts a fresh GET, from the inline box, the viewer, or a reopened viewer alike.
     */
    internal fun reader(): ClipReader? {
        val failed = download?.takeIf { it.failure() != null }
        if (failed != null) {
            failed.close()
            download = null
        }
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

    /**
     * A play: the decoder is made here (never on render), the one GET starts, it plays when prepared. A play while
     * the clip loads or plays does nothing; a play after a failure (the box's error, the viewer's) starts again
     * with a fresh download, as the web's `<video>` can be played again.
     */
    fun play() {
        val current = player
        if (current != null && failure == null && current.phase != VideoPhase.Failed) return
        if (current != null || failure != null) {
            current?.release()
            player = null
            failure = null
        }
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
    private val newPlayer: () -> MediaPlayer = { MediaPlayer() },
    private val makePlayer: ((PlayableSource, () -> Unit) -> VideoPlayer)? = null,
    /** Where each clip's platform player is called (a seam for tests: inline); null is a thread of its own. */
    private val playerThread: java.util.concurrent.Executor? = null,
) {
    private val clips = HashMap<String, ToolClip>()

    /**
     * What the full-size viewer shows: the items of the row it was opened from and the one on screen. Here,
     * not in a row's composition: the phone and expanded shells compose separate transcripts, a lazy
     * transcript may not compose the opening row at all after a switch, so the viewer is hosted by the
     * chat screen ([ToolViewerHost]) from this state, and its player comes through the switch.
     */
    var viewerItems: List<ToolMediaItem> by mutableStateOf(emptyList())
        private set
    var viewerIndex: Int? by mutableStateOf(null)
        private set

    /** The `src` the viewer shows, or null. */
    val openViewerSrc: String? get() = viewerIndex?.let { viewerItems.getOrNull(it)?.src }

    /** Opens the viewer on [items] at [index]. */
    fun openViewer(items: List<ToolMediaItem>, index: Int) {
        val previous = openViewerSrc
        viewerItems = items
        viewerIndex = index.takeIf { it in items.indices }
        if (previous != null && previous != openViewerSrc) clip(previous)?.viewer?.release()
    }

    /** Moves the viewer to another item of the same row; the player of the item it leaves is released. */
    fun moveViewer(index: Int) = openViewer(viewerItems, index)

    fun closeViewer() {
        val previous = openViewerSrc
        viewerIndex = null
        viewerItems = emptyList()
        if (previous != null) clip(previous)?.viewer?.release()
    }

    private val players: ClipPlayerFactory = makePlayer ?: { src, failed ->
        MediaVideoPlayer(src, main, failed, playWhenReady = true, newPlayer = newPlayer, playerThread = playerThread)
    }

    /** Null when [src] is not an mp4 tool-media path (nothing else is ever requested). */
    fun clip(src: String): ToolClip? {
        if (ToolMediaSource.extensionOf(src) != "mp4") return null
        val sha = namedSha256(src) ?: return null
        return synchronized(clips) {
            clips.getOrPut(src) {
                ToolClip(src, { origin()?.let { ClipDownload(source, cacheDir, it, src, sha) } }, players)
            }
        }
    }

    /** Every clip idle, every download stopped, every part file deleted. */
    fun releaseAll() {
        viewerIndex = null
        viewerItems = emptyList()
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
    private val leaveGraceMs: Long = LEAVE_GRACE_MS,
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

    private var boundSession: String? = null
    private var leaving: Job? = null

    /**
     * The chat screen is on screen. The phone and expanded shells each compose their own chat, so a
     * rotation that switches shell leaves one and enters the other in the same pass: entering cancels the
     * pending release a leave started.
     */
    fun chatEntered() {
        leaving?.cancel()
        leaving = null
    }

    /** The chat screen left composition: released after [leaveGraceMs] unless a chat screen enters first. */
    fun chatLeft() {
        leaving?.cancel()
        leaving = viewModelScope.launch {
            delay(leaveGraceMs)
            registry.releaseAll()
        }
    }

    /** The session on screen: moving to another one (or to none) releases every clip of the one it leaves. */
    fun onSession(id: String?) {
        val was = boundSession
        boundSession = id
        if (was != null && was != id) registry.releaseAll()
    }

    override fun onCleared() {
        registry.releaseAll()
    }

    companion object {
        /** A shell switch recomposes the chat within a frame; this is how long "left" must last to count. */
        const val LEAVE_GRACE_MS = 1_000L

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

/**
 * Shows the registry's viewer ([ToolClipRegistry.viewerIndex]) over whatever the chat screen draws.
 * Nothing without a registry (previews, tests): [ToolMediaRow] then hosts its own.
 */
@Composable
internal fun ToolViewerHost() {
    val registry = LocalToolClips.current ?: return
    val index = registry.viewerIndex ?: return
    val items = registry.viewerItems
    if (index in items.indices) MediaLightbox(items, index, onIndexChange = registry::moveViewer, onClose = registry::closeViewer)
}
