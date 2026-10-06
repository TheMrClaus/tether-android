package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFiles
import com.tether.app.ui.video.PlayableSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The platform player's bytes: a workspace file read by `Range` requests through
 * [WorkspaceFiles.readRange] (the web's `<video>` does the same), so a seek is one request and no
 * file is ever downloaded whole, whatever its size. No size cap, as the web has none.
 *
 * Every read goes to the paired server with the one credential in force, read afresh per request
 * (the same authority as every /api/files call, no redirect followed). The first read ([open])
 * PINS the server it came from: a later read that would go to another origin, or fail because the
 * session ended, fails the playback instead of mixing servers' bytes (ta-3pf; the browser tears
 * the player down on sign-out and a server switch as well, see FileBrowserState.dispose).
 *
 * [readAt] is called by the player's own thread, never the main thread, and blocks it for the
 * duration of one request; [close] cancels any request in flight so a release is never held up.
 */
class WorkspaceMediaDataSource(
    private val files: WorkspaceFiles,
    private val path: String,
    private val blockSize: Int = BLOCK_BYTES,
) : PlayableSource() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var closed = false
    private var pinnedOrigin: String? = null
    private var total: Long = UNKNOWN

    /** The start of the file (what a container's header is) and the last block read (sequential playback). */
    private var head: Block? = null
    private var recent: Block? = null

    private class Block(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    /** The server this source is pinned to, once [open] has succeeded. */
    val origin: String? get() = synchronized(lock) { pinnedOrigin }

    /**
     * The first read: learns the file's length and pins the server. False when the file cannot be
     * read (no such file, signed out, unreachable) or is empty: there is nothing to play.
     */
    override suspend fun open(): Boolean {
        val result = files.readRange(path, 0, blockSize)
        if (closed || result !is FilesResult.Ok) return false
        val read = result.value
        synchronized(lock) {
            pinnedOrigin = read.origin
            total = read.total ?: UNKNOWN
            head = Block(0, read.bytes)
            recent = head
        }
        return read.bytes.isNotEmpty()
    }

    override fun getSize(): Long = synchronized(lock) { total }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("the video was closed")
        if (size == 0) return 0
        if (position < 0) throw IOException("negative position")
        synchronized(lock) {
            if (total != UNKNOWN && position >= total) return -1
            val block = cached(position) ?: fetch(position) ?: return -1
            val count = minOf(size.toLong(), block.end - position).toInt()
            System.arraycopy(block.bytes, (position - block.start).toInt(), buffer, offset, count)
            return count
        }
    }

    private fun cached(position: Long): Block? =
        listOfNotNull(recent, head).firstOrNull { position >= it.start && position < it.end }

    /** One block from [position], or null at the end of the file. Throws when the read is refused or the file changed. */
    private fun fetch(position: Long): Block? {
        val wanted = if (total == UNKNOWN) blockSize.toLong() else minOf(blockSize.toLong(), total - position)
        val pin = pinnedOrigin
        val result = try {
            runBlocking { scope.async { files.readRange(path, position, wanted.toInt(), pin) }.await() }
        } catch (_: CancellationException) {
            throw IOException("the video was closed")
        }
        when (result) {
            is FilesResult.Failed -> throw IOException("the video could not be read")
            is FilesResult.Ok -> {
                val read = result.value
                // Never bytes from another server, whatever the authority says now.
                if (read.origin != pin) throw IOException("the server changed")
                // A file that changed size under the player is not the file it started on.
                val declared = read.total
                if (declared != null && total != UNKNOWN && declared != total) throw IOException("the file changed")
                if (declared != null) total = declared
                if (read.bytes.isEmpty()) return null
                return Block(position, read.bytes).also { recent = it }
            }
        }
    }

    override fun close() {
        closed = true
        scope.cancel()
    }

    companion object {
        /** One request's worth: big enough to keep a stream fed, small enough that a seek is quick. */
        const val BLOCK_BYTES = 256 * 1024
        private const val UNKNOWN = -1L
    }
}
