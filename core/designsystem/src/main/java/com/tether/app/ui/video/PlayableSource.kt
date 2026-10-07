package com.tether.app.ui.video

import android.graphics.Bitmap
import android.media.MediaDataSource

/**
 * The bytes [MediaVideoPlayer] plays, whatever they come from (a workspace file read by `Range`
 * requests, a tool-media clip downloading into a scratch file). The platform's [MediaDataSource]
 * has no way to be opened off the main thread or to say it is waiting, which the player needs:
 *
 *  - [open] is the first read: it suspends until the source can serve bytes (and has pinned
 *    whatever it must), and is false when it cannot (no such file, refused, empty). It is called on
 *    the player's own coroutine, never on a reader thread.
 *  - [close] ends this source for good, from any thread: a [readAt] blocked on the network returns
 *    at once (by throwing) instead of holding the platform player's release.
 *  - [onWaiting] is told `true` when a [readAt] has to wait for bytes that are not there yet and
 *    `false` when it has them, so the UI can show buffering. Called on the reader's thread.
 */
abstract class PlayableSource : MediaDataSource() {
    @Volatile
    var onWaiting: ((Boolean) -> Unit)? = null

    abstract suspend fun open(): Boolean

    /**
     * Blocks (call it off the main thread) until [bytes] are readable past where the read that last had to wait
     * was, the body is whole, or this source is closed or failed (it returns then too: the player reads on and
     * meets the failure). It is how the player knows it may play on after it paused for a read that waited
     * (ta-coik.68 F-5: a player left to run on a link slower than the clip has its clock run while no picture
     * is shown). A source whose read is one request answers at once.
     */
    open fun awaitReadAhead(bytes: Long) {}

    /**
     * The picture at [positionMs], at most [maxSide] px on its longer side, decoded WITHOUT the player or a
     * surface (a local file and a metadata retriever), or null when this source cannot. Blocking: call it off
     * the main thread. It is what a paused or ended clip shows on a fresh surface that the platform player
     * will not paint onto.
     */
    open fun stillAt(positionMs: Int, maxSide: Int): Bitmap? = null

    abstract override fun close()
}
