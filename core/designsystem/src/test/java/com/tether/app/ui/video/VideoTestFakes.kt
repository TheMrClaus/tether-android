package com.tether.app.ui.video

import android.view.Surface
import android.widget.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/** A [PlayableSource] with no bytes: [open] answers [opens] (after [gate]); reads fail once it is closed. */
class FakeSource : PlayableSource() {
    var opens = true
    var gate: CompletableDeferred<Unit>? = null
    @Volatile var closed = false
    var still: android.graphics.Bitmap? = null
    var stillCalls = 0

    @Volatile var stillGate: java.util.concurrent.CountDownLatch? = null

    override fun stillAt(positionMs: Int, maxSide: Int): android.graphics.Bitmap? {
        stillCalls++
        stillGate?.await()
        return still
    }

    override suspend fun open(): Boolean {
        gate?.await()
        return opens
    }

    override fun getSize(): Long = 5_000_000

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("closed")
        return -1
    }

    override fun close() {
        closed = true
    }
}

/** A [VideoPlayer] that only records: its phase is whatever a test sets, and it counts releases. */
class RecordingVideoPlayer : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
    override var playing: Boolean by mutableStateOf(false)
    var releases = 0
    val stills = mutableListOf<android.graphics.Bitmap?>()
    override fun keepStill(bitmap: android.graphics.Bitmap?) { stills += bitmap }
    var attached: Surface? = null

    override val control = object : MediaController.MediaPlayerControl {
        override fun start() { playing = true }
        override fun pause() { playing = false }
        override fun getDuration() = 60_000
        override fun getCurrentPosition() = 0
        override fun seekTo(pos: Int) = Unit
        override fun isPlaying() = playing
        override fun getBufferPercentage() = 0
        override fun canPause() = true
        override fun canSeekBackward() = true
        override fun canSeekForward() = true
        override fun getAudioSessionId() = 0
    }

    override fun attachSurface(surface: Surface) { attached = surface }
    override fun detachSurface(surface: Surface) { if (attached === surface) attached = null }
    override fun pause() { playing = false }
    override fun release() { releases++ }
}
