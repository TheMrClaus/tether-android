package com.tether.app.ui.chat

import android.view.Surface
import android.widget.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.ui.video.PlayableSource
import com.tether.app.ui.video.VideoPhase
import com.tether.app.ui.video.VideoPlayer
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation

/** A [VideoPlayer] with no decoder: its phase is whatever a test sets; it records its release. */
class StubVideoPlayer(val reader: PlayableSource, val onFailed: () -> Unit) : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
    override var playing: Boolean by mutableStateOf(false)
    override var buffering: Boolean by mutableStateOf(false)
    var released = false
    override val control: MediaController.MediaPlayerControl get() = throw UnsupportedOperationException("no decoder in this test")
    override fun attachSurface(surface: Surface) = Unit
    override fun detachSurface(surface: Surface) = Unit
    override fun pause() = Unit
    override fun release() {
        released = true
        reader.close()
    }
}

/** The mp4-shaped clip the fixtures serve, and its tool-media path. */
object ClipFixtures {
    val bytes = ByteArray(4096) { (it * 11).toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }
    val src = "/api/tool-media/${sha256Hex(bytes)}.mp4"
    const val ORIGIN = "https://tether.example"
}

/** A server whose one answer is [answer]; with [park] it writes a little and then waits (a download in flight). */
class ClipServer(private val answer: ToolMediaResult? = null, private val park: Boolean = false) : ToolMediaSource {
    val calls = AtomicInteger()

    override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
        calls.incrementAndGet()
        if (park) {
            sink.write(ClipFixtures.bytes, 0, 1024)
            awaitCancellation()
        }
        answer?.let { return it }
        sink.write(ClipFixtures.bytes)
        return ToolMediaResult.Ok(ClipFixtures.bytes.size.toLong(), "video/mp4")
    }
}
