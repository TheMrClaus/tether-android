package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.client.ExistsBatches
import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the server has the file a relative mention names (the web's `lib/mention-existence.ts`). A relative mention
 * is drawn as a link unless the server said, in a 200 JSON answer, that its file is NOT there: [get] is null for every
 * other state (not asked, pending, a failed or refused ask, a path too long to send), and null keeps the link.
 *
 * Paths asked within one short tick go out together, split by [ExistsBatches.split]; the answer is cached per absolute
 * path. [endTurn] moves the epoch, and a path answered in an earlier epoch is asked again (the old answer stays
 * until the new one lands). A failed ask is not retried within its epoch.
 */
class MentionExistence(
    private val files: WorkspaceFiles,
    private val scope: CoroutineScope,
    private val batchMs: Long = BATCH_MS,
) {
    private class Entry(val exists: Boolean, val epoch: Int)

    private val lock = Any()
    private val cache = HashMap<String, Entry>()
    private val asked = HashMap<String, Int>() // in flight or failed or unsendable, with the epoch it was asked in
    private val pending = LinkedHashMap<String, Int>()
    private var epoch = 0
    private var scheduled = false

    /** Moves on every stored answer: read in composition, it rebuilds the prose that depends on it. */
    var version by mutableIntStateOf(0)
        private set

    /** The server's explicit answer for [absPath], or null. */
    fun get(absPath: String): Boolean? = synchronized(lock) { cache[absPath]?.exists }

    /** Ask about [absPath] if it is unknown or its answer is older than the epoch. */
    fun request(absPath: String) {
        val start = synchronized(lock) {
            val known = cache[absPath]
            if (known != null && known.epoch >= epoch) return
            val before = asked[absPath]
            if (before != null && before >= epoch) return
            pending[absPath] = epoch
            if (scheduled) false else true.also { scheduled = true }
        }
        if (start) scope.launch {
            delay(batchMs)
            flush()
        }
    }

    /** The session went from active to not active: every answer is asked again when next needed. */
    fun endTurn() {
        synchronized(lock) {
            epoch += 1
            version += 1
        }
    }

    private suspend fun flush() {
        val batch = synchronized(lock) {
            scheduled = false
            val all = pending.keys.toList()
            pending.clear()
            all
        }
        if (batch.isEmpty()) return
        val split = ExistsBatches.split(batch)
        val at = synchronized(lock) {
            // Too long to send: never asked, never an answer (the link stays).
            split.unsendable.forEach { asked[it] = epoch }
            split.chunks.forEach { chunk -> chunk.forEach { asked[it] = epoch } }
            epoch
        }
        for (chunk in split.chunks) {
            scope.launch {
                val result = try {
                    files.exists(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                if (result !is FilesResult.Ok) return@launch
                synchronized(lock) {
                    chunk.forEachIndexed { n, path ->
                        result.value.getOrNull(n)?.let { cache[path] = Entry(it, at) }
                    }
                    version += 1
                }
            }
        }
    }

    companion object {
        const val BATCH_MS = 30L
    }
}

/** The existence checker for the session on screen (null: none, every relative mention stays a link). */
val LocalMentionExistence = staticCompositionLocalOf<MentionExistence?> { null }
