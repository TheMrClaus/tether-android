package com.tether.app.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.tether.app.client.DeclaredLengthSink
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/**
 * ta-coik.68: a clip's decoded picture (the paused / ended frame a new surface shows at once) is taken from the
 * clip's own bytes only when they are WHOLE: a partial file may have its moov atom still to come, and a retriever
 * on it answers a wrong frame or none. [ClipReader.stillAt] must not touch the retriever until the download is whole.
 */
@RunWith(RobolectricTestRunner::class)
class ClipReaderStillTest {
    @get:Rule val tmp = TemporaryFolder()

    private val origin = "https://tether.example"
    private val clip = ByteArray(4096) { (it * 7).toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }
    private val sha get() = sha256Hex(clip)
    private val frame = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)

    /** Two chunks; the second waits for [gate]. */
    private fun source(gate: CompletableDeferred<Unit>) = object : ToolMediaSource {
        override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
            (sink as? DeclaredLengthSink)?.declaredLength(clip.size.toLong())
            sink.write(clip, 0, 2048)
            gate.await()
            sink.write(clip, 2048, 2048)
            return ToolMediaResult.Ok(clip.size.toLong(), "video/mp4")
        }
    }

    /** The platform retriever knows [file] as a 320x240 clip with a frame at one second. */
    private fun decodable(file: File) {
        val ds = DataSource.toDataSource(file.path)
        ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "320")
        ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "240")
        ShadowMediaMetadataRetriever.addScaledFrame(ds, 1_000_000L, 160, 120, frame)
    }

    @Test fun thePictureComesFromTheWholeFileOnlyNeverFromAPartialOne() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val d = ClipDownload(source(gate), tmp.root, origin, "/api/tool-media/$sha.mp4", sha)
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (d.bytesWritten < 2048 && System.nanoTime() < deadline) Thread.sleep(10)
        val part = d.partFile!!
        decodable(part)
        decodable(File(ToolMediaCache.dirFor(tmp.root, origin), "$sha.mp4"))
        // Half the bytes are in: even a retriever that would answer must not be asked.
        assertNull("a partial file gives no picture", reader.stillAt(1000, 160))
        gate.complete(Unit)
        while (d.outcome() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull("the whole, verified file does", reader.stillAt(1000, 160))
        reader.close()
    }
}
