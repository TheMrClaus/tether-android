package com.tether.app.share

import com.tether.app.ui.chat.SharedFileSource
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T11.2: a share that has been read and waits for the user to choose where it goes. [files] are
 * already copied out of the sender's provider ([dir], the app's cache), so the share outlives the
 * activity that received it; it lives in memory only, so a process death before the choice drops it.
 */
class PendingShare(val id: Long, val text: String?, val files: List<SharedFileSource>, val dir: File?) {
    fun deleteFiles() {
        dir?.deleteRecursively()
    }
}

/**
 * The one share waiting for a destination (process-wide; main thread). A new share replaces an
 * older one that was never placed, as a second pick replaces the first. The shell shows the chooser
 * whenever one waits and the user is signed in, so a share received while signed out waits through
 * the sign-in, as a link does.
 */
object ShareInbox {
    private val ids = AtomicLong(0)
    private val state = MutableStateFlow<PendingShare?>(null)
    val pending: StateFlow<PendingShare?> = state.asStateFlow()

    fun nextId(): Long = ids.incrementAndGet()

    fun offer(share: PendingShare) {
        val previous = state.value
        state.value = share
        if (previous != null && previous.dir != share.dir) previous.deleteFiles()
    }

    /** The share [id], removed from the inbox (null: it was replaced or already taken). */
    fun take(id: Long): PendingShare? {
        val share = state.value?.takeIf { it.id == id } ?: return null
        state.value = null
        return share
    }

    /** The user dismissed the chooser: the share and its copies go. */
    fun discard(id: Long) {
        take(id)?.deleteFiles()
    }
}
