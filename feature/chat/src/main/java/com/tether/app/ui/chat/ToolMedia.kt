package com.tether.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/*
 * T6.2: tool-result / attachment / spawned-run media (chat-tool-render.tsx `ToolMedia` +
 * `MediaLightbox`). The web hands the journaled URL to an <img>/<video>; natively the bytes come
 * over [ToolMediaSource] (the paired credential, the paired origin only, no redirects), images are
 * decoded under hard bounds, and a clip is downloaded (bounded) before it plays in a platform
 * VideoView. No WebView anywhere.
 */

/** What loading one picture came to. */
@Immutable
sealed interface MediaImage {
    data class Ok(val bitmap: ImageBitmap) : MediaImage
    data object TooLarge : MediaImage
    data object Failed : MediaImage
}

/** What loading one clip came to: a local, bounded copy to play. */
@Immutable
sealed interface MediaVideo {
    data class Ok(val file: File) : MediaVideo
    data object TooLarge : MediaVideo
    data object Failed : MediaVideo
}

/** The seam the transcript loads media through (a fake in tests and goldens). */
interface ToolMediaLoader {
    /** [full]: the viewer's larger decode; otherwise the in-row thumbnail. */
    suspend fun image(item: ToolMediaItem, full: Boolean = false): MediaImage
    suspend fun video(item: ToolMediaItem): MediaVideo
}

/** Null = no loader (previews): media tiles stay in their placeholder state. */
val LocalToolMediaLoader = staticCompositionLocalOf<ToolMediaLoader?> { null }

/**
 * Bounds for in-transcript media. Native-only: the web's <img> has no cap, but a hostile or broken
 * server must never be able to exhaust a phone's memory with pictures (security review M1). Every
 * picture streams to a temporary file (never a byte array), at most [CONCURRENT_IMAGE_LOADS] at a
 * time app-wide, and decodes under the T11.1 three-way bound — tighter for a row thumbnail than
 * for the viewer, since a transcript can hold many.
 */
object MediaLimits {
    /** Encoded bytes fetched for one picture (declared and streamed). */
    const val MAX_IMAGE_BYTES: Long = 32L * 1024L * 1024L

    /** A row thumbnail: at most this many pixels a side and bytes decoded (it shows ≤ 320dp tall). */
    const val THUMB_SIDE: Int = 512
    const val THUMB_DECODED_BYTES: Long = 1L * 1024L * 1024L

    /** The viewer's decode. */
    const val FULL_SIDE: Int = 2048
    const val FULL_DECODED_BYTES: Long = 16L * 1024L * 1024L

    /** A header claiming more pixels than this is refused outright (a decompression bomb). */
    const val MAX_IMAGE_PIXELS: Long = 100_000_000L

    /** A clip is downloaded before it plays: the server's own cap. */
    const val MAX_VIDEO_BYTES: Long = ToolMediaSource.MAX_MEDIA_BYTES

    /** Decoded pictures kept in memory across scrolling, by the bitmaps' own allocation size. */
    const val CACHE_BYTES: Int = 24 * 1024 * 1024

    /** Picture loads in flight at once, across the whole app. */
    const val CONCURRENT_IMAGE_LOADS: Int = 2

    /**
     * Tiles one CARD draws in total — its own result and every sub-agent entry share this budget
     * (R3-M1: a per-row budget let 50 entries × 12 tiles hold 600 bitmaps) — then "+N more" tiles.
     */
    const val MAX_TILES: Int = 12

    /** One picture / one clip may take this long end to end, then it fails. */
    const val IMAGE_TIMEOUT_MS: Long = 60_000
    const val VIDEO_TIMEOUT_MS: Long = 10 * 60_000

    /** The image types a `data:` URI may carry (lib/tool-media-store.mjs IMAGE_MEDIA_TYPES). */
    val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
}

/**
 * The card-wide tile budget (R3-M1): [MediaLimits.MAX_TILES] handed out in order across [rows]
 * (the card's own result first, then each sub-agent entry); a row past the budget gets 0 tiles
 * and only its "+N more" tile.
 */
internal fun tileBudget(rows: List<Int>, budget: Int = MediaLimits.MAX_TILES): List<Int> {
    var left = budget
    return rows.map { n -> minOf(n, left).also { left -= it } }
}

/** The app-wide gates: picture loads share one semaphore, clip downloads one lock per content hash. */
internal object MediaGates {
    val images = kotlinx.coroutines.sync.Semaphore(MediaLimits.CONCURRENT_IMAGE_LOADS)
    private val clips = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    fun clip(sha: String): kotlinx.coroutines.sync.Mutex = clips.getOrPut(sha) { kotlinx.coroutines.sync.Mutex() }
}

/**
 * L3: the file's first bytes must be the format its extension / declared type names — PNG
 * `89 50 4E 47`, JPEG `FF D8 FF`, GIF `GIF8`, WebP `RIFF….WEBP`, MP4 `ftyp` at offset 4 — before
 * anything decodes or plays it.
 */
object MediaMagic {
    fun matches(file: File, mediaType: String): Boolean {
        val head = ByteArray(12)
        val n = try {
            java.io.FileInputStream(file).use { input -> input.read(head) }
        } catch (_: java.io.IOException) {
            return false
        }
        return matches(head.copyOf(maxOf(n, 0)), mediaType)
    }

    fun matches(head: ByteArray, mediaType: String): Boolean {
        fun at(offset: Int, vararg bytes: Int) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
        return when (mediaType) {
            "image/png" -> at(0, 0x89, 0x50, 0x4E, 0x47)
            "image/jpeg" -> at(0, 0xFF, 0xD8, 0xFF)
            "image/gif" -> at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code)
            "image/webp" -> at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code)
            "video/mp4" -> at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code)
            else -> false
        }
    }
}

/** Bounded bitmap decoding from a FILE (the T11.1 BoundedImages plan, with the transcript's bounds). */
object BoundedMediaDecoder {
    fun decode(
        file: File,
        maxSide: Int = MediaLimits.FULL_SIDE,
        maxBytes: Long = MediaLimits.FULL_DECODED_BYTES,
        decodeFile: (String, BitmapFactory.Options) -> Bitmap? = { path, o -> BitmapFactory.decodeFile(path, o) },
    ): MediaImage = try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        decodeFile(file.path, options)
        val sample = plan(options.outWidth, options.outHeight, if (options.outConfig == Bitmap.Config.RGBA_F16) 8 else 4, maxSide, maxBytes)
        when {
            options.outWidth <= 0 || options.outHeight <= 0 -> MediaImage.Failed
            sample == null -> MediaImage.TooLarge
            else -> {
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val bitmap = decodeFile(file.path, options)
                when {
                    bitmap == null -> MediaImage.Failed
                    bitmap.allocationByteCount > maxBytes -> {
                        bitmap.recycle()
                        MediaImage.TooLarge
                    }
                    else -> MediaImage.Ok(bitmap.asImageBitmap())
                }
            }
        }
    } catch (_: OutOfMemoryError) {
        MediaImage.TooLarge
    } catch (_: RuntimeException) {
        MediaImage.Failed
    }

    /** The power-of-two sample that fits both caps, or null past [MediaLimits.MAX_IMAGE_PIXELS]. */
    fun plan(width: Int, height: Int, bytesPerPixel: Int, maxSide: Int = MediaLimits.FULL_SIDE, maxBytes: Long = MediaLimits.FULL_DECODED_BYTES): Int? {
        if (width <= 0 || height <= 0) return 1
        if (width.toLong() * height > MediaLimits.MAX_IMAGE_PIXELS) return null
        var sample = 1
        while (true) {
            val w = (width / sample).toLong()
            val h = (height / sample).toLong()
            if (w <= maxSide && h <= maxSide && w * h * bytesPerPixel <= maxBytes) return sample
            sample *= 2
        }
    }
}

/** A string's characters as bytes (base64 is ASCII), read in place: the payload is never copied. */
internal class AsciiInputStream(private val text: String, private var index: Int = 0) : java.io.InputStream() {
    override fun read(): Int = if (index < text.length) text[index++].code and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (index >= text.length) return -1
        val n = minOf(len, text.length - index)
        for (i in 0 until n) b[off + i] = text[index + i].code.toByte()
        index += n
        return n
    }
}

/** Copies at most [max] bytes; false when the source has more (the copy is then useless). */
internal fun copyBounded(input: java.io.InputStream, out: java.io.OutputStream, max: Long): Boolean {
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n == -1) return true
        total += n
        if (total > max) return false
        out.write(chunk, 0, n)
    }
}

/** A `data:<type>;base64,<payload>` URI: its declared type and where the payload starts (never copied out). */
internal data class DataUri(val mediaType: String, val payloadStart: Int, val payloadLength: Int)

internal fun parseDataUri(src: String): DataUri? {
    if (!src.startsWith("data:")) return null
    val comma = src.indexOf(',')
    if (comma < 0) return null
    val meta = src.substring(5, comma)
    if (!meta.endsWith(";base64")) return null
    return DataUri(meta.removeSuffix(";base64"), comma + 1, src.length - comma - 1)
}

/**
 * The on-disk clip cache (`<cacheDir>/tool-media/<origin key>/<sha256>.mp4`), one directory per
 * paired server. [sync] runs whenever the sign-in changes: signed out, everything goes; signed in,
 * every other server's directory goes. [evict] keeps a directory under [MAX_BYTES] and drops files
 * older than [MAX_AGE_MS], oldest first. Content-addressed files are only kept after their bytes
 * hash to their own name (see [ToolMediaRepository]).
 */
object ToolMediaCache {
    const val DIR = "tool-media"
    const val MAX_BYTES: Long = 256L * 1024L * 1024L
    const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** The directory name for one server: a hash of its origin (the URL itself never lands on disk). */
    fun originKey(origin: String): String = sha256Hex(origin.toByteArray(Charsets.UTF_8)).substring(0, 16)

    fun dirFor(cacheDir: File, origin: String): File = File(File(cacheDir, DIR), originKey(origin))

    fun sync(cacheDir: File, signedIn: Boolean, origin: String?) {
        // R3-L3: picture downloads in flight belong to the old sign-in too.
        File(cacheDir, ToolMediaRepository.TMP_DIR).deleteRecursively()
        val root = File(cacheDir, DIR)
        if (!signedIn || origin == null) {
            root.deleteRecursively()
            return
        }
        val keep = originKey(origin)
        root.listFiles()?.forEach { if (it.name != keep) it.deleteRecursively() }
    }

    fun evict(dir: File, now: Long = System.currentTimeMillis(), maxBytes: Long = MAX_BYTES, maxAgeMs: Long = MAX_AGE_MS) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            val stale = now - file.lastModified() > maxAgeMs || file.name.endsWith(".part") && now - file.lastModified() > 60_000
            if (stale || total > maxBytes) {
                total -= file.length()
                file.delete()
            }
        }
    }
}

/**
 * Keeps the clip cache to the sign-in in force, for as long as the caller's scope lives (UiRoot):
 * once the stored settings are read, every change of (signed in, server) applies
 * [ToolMediaCache.sync] — a sign-out drops every clip, a sign-in to another server drops the
 * previous server's. An unchanged pair (a re-launch after a rotation) is applied again, which
 * keeps the current server's clips.
 */
suspend fun syncToolMediaCache(client: com.tether.app.client.TetherClient, cacheDir: File) {
    client.storedSettingsLoaded.first { it }
    kotlinx.coroutines.flow.combine(client.configured, client.serverUrl) { signedIn, origin -> signedIn to origin }
        .distinctUntilChanged()
        .collect { (signedIn, origin) -> withContext(Dispatchers.IO) { ToolMediaCache.sync(cacheDir, signedIn, origin) } }
}

internal fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The sha256 a `/api/tool-media/<sha256>.<ext>` path names (the server content-addresses by it). */
internal fun namedSha256(src: String): String? = if (ToolMediaSource.extensionOf(src) != null) src.substringAfterLast('/').substringBefore('.') else null

/** sha256 of a closed file, streamed. */
internal fun sha256OfFile(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    java.io.FileInputStream(file).use { input ->
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n == -1) break
            digest.update(chunk, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The production [ToolMediaLoader]: `data:` pictures (legacy base64 tool results) decode locally,
 * `/api/tool-media/…` ones come over [source]; decoded pictures are cached (keyed by the sha256 of
 * their source), clips under the server's [ToolMediaCache] directory. Fetched bytes must hash to
 * the sha256 their path names, and every file's magic bytes must be its format, else it is
 * dropped: a server cannot swap content under a name the transcript already holds. Pictures stream
 * to a temporary file (no in-memory copy), two at a time app-wide, each within a timeout; any
 * OutOfMemoryError on the way is "too large", never a crash.
 */
class ToolMediaRepository(
    private val source: ToolMediaSource,
    private val cacheDir: File,
    private val origin: String? = null,
    // L4: end-to-end timeouts (parameters only so tests need not wait a minute).
    private val imageTimeoutMs: Long = MediaLimits.IMAGE_TIMEOUT_MS,
    private val videoTimeoutMs: Long = MediaLimits.VIDEO_TIMEOUT_MS,
) : ToolMediaLoader {
    private val cache = object : LruCache<String, ImageBitmap>(MediaLimits.CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    private val tmpDir: File get() = File(cacheDir, TMP_DIR).apply { mkdirs() }

    override suspend fun image(item: ToolMediaItem, full: Boolean): MediaImage {
        if (item.isVideo) return MediaImage.Failed
        // R3-L2: an over-size data: picture is refused before anything touches it (no hashing).
        parseDataUri(item.src)?.let { if (it.payloadLength.toLong() / 4 * 3 > MediaLimits.MAX_IMAGE_BYTES) return MediaImage.TooLarge }
        return try {
            // The key is computed once, off the main thread.
            val key = withContext(Dispatchers.IO) { cacheKey(item.src, full) }
            cache.get(key)?.let { return MediaImage.Ok(it) }
            // The timeout starts once a load slot is held (a queued picture is not "slow").
            val result = MediaGates.images.withPermit {
                kotlinx.coroutines.withTimeoutOrNull(imageTimeoutMs) { withContext(Dispatchers.IO) { loadImage(item.src, full) } }
            } ?: MediaImage.Failed
            if (result is MediaImage.Ok) cache.put(key, result.bitmap)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            MediaImage.TooLarge
        } catch (_: Exception) {
            // R3-L1: an I/O failure (a cache folder swept mid-load, a full disk) is a failed load, never a crash.
            MediaImage.Failed
        }
    }

    private suspend fun loadImage(src: String, full: Boolean): MediaImage {
        val side = if (full) MediaLimits.FULL_SIDE else MediaLimits.THUMB_SIDE
        val bytes = if (full) MediaLimits.FULL_DECODED_BYTES else MediaLimits.THUMB_DECODED_BYTES
        var tmp: File? = null
        try {
            val file = File.createTempFile("img", ".part", tmpDir).also { tmp = it }
            val data = parseDataUri(src)
            if (data != null) {
                val type = data.mediaType
                if (type !in MediaLimits.IMAGE_TYPES) return MediaImage.Failed
                // Base64 is 4 characters per 3 bytes: refuse before decoding what could not fit.
                if (data.payloadLength.toLong() / 4 * 3 > MediaLimits.MAX_IMAGE_BYTES) return MediaImage.TooLarge
                val fits = try {
                    android.util.Base64InputStream(AsciiInputStream(src, data.payloadStart), Base64.DEFAULT).use { input ->
                        FileOutputStream(file).use { out -> copyBounded(input, out, MediaLimits.MAX_IMAGE_BYTES) }
                    }
                } catch (_: java.io.IOException) {
                    return MediaImage.Failed
                } catch (_: IllegalArgumentException) {
                    return MediaImage.Failed
                }
                if (!fits) return MediaImage.TooLarge
                if (!MediaMagic.matches(file, type)) return MediaImage.Failed
                return BoundedMediaDecoder.decode(file, side, bytes)
            }
            val ext = ToolMediaSource.extensionOf(src) ?: return MediaImage.Failed
            if (ext == "mp4") return MediaImage.Failed
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val result = java.security.DigestOutputStream(FileOutputStream(file), digest).use { out ->
                source.fetch(src, MediaLimits.MAX_IMAGE_BYTES, out)
            }
            return when (result) {
                is ToolMediaResult.Ok -> {
                    val hashed = digest.digest().joinToString("") { "%02x".format(it) }
                    when {
                        hashed != namedSha256(src) -> MediaImage.Failed
                        !MediaMagic.matches(file, ToolMediaSource.CONTENT_TYPE_BY_EXT.getValue(ext)) -> MediaImage.Failed
                        else -> BoundedMediaDecoder.decode(file, side, bytes)
                    }
                }
                ToolMediaResult.TooLarge -> MediaImage.TooLarge
                else -> MediaImage.Failed
            }
        } catch (_: java.io.IOException) {
            return MediaImage.Failed
        } finally {
            tmp?.delete()
        }
    }

    override suspend fun video(item: ToolMediaItem): MediaVideo {
        val ext = ToolMediaSource.extensionOf(item.src)
        if (ext != "mp4" || origin == null) return MediaVideo.Failed
        val expected = namedSha256(item.src) ?: return MediaVideo.Failed
        return try {
            MediaGates.clip(expected).withLock {
                kotlinx.coroutines.withTimeoutOrNull(videoTimeoutMs) { withContext(Dispatchers.IO) { downloadClip(item.src, expected) } }
            } ?: MediaVideo.Failed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            MediaVideo.TooLarge
        } catch (_: Exception) {
            MediaVideo.Failed
        }
    }

    private suspend fun downloadClip(src: String, expected: String): MediaVideo {
        val dir = ToolMediaCache.dirFor(cacheDir, origin!!).apply { mkdirs() }
        ToolMediaCache.evict(dir)
        val file = File(dir, "$expected.mp4")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return MediaVideo.Ok(file)
        }
        // L2: a temp file of its own (never a shared name), removed however this ends (cancellation
        // included), and the CLOSED file re-hashed before it takes the content address.
        var part: File? = null
        try {
            val temp = File.createTempFile(expected, ".part", dir).also { part = it }
            val result = try {
                FileOutputStream(temp).use { out -> source.fetch(src, MediaLimits.MAX_VIDEO_BYTES, out) }
            } catch (_: java.io.IOException) {
                ToolMediaResult.Failed()
            }
            return when {
                result == ToolMediaResult.TooLarge -> MediaVideo.TooLarge
                result !is ToolMediaResult.Ok -> MediaVideo.Failed
                sha256OfFile(temp) != expected -> MediaVideo.Failed
                !MediaMagic.matches(temp, "video/mp4") -> MediaVideo.Failed
                !temp.renameTo(file) -> MediaVideo.Failed
                else -> MediaVideo.Ok(file).also { ToolMediaCache.evict(dir) }
            }
        } catch (_: java.io.IOException) {
            // R3-L1: the folder swept by a sign-in change mid-download, a full disk: failed, not a crash.
            return MediaVideo.Failed
        } finally {
            part?.let { if (it.exists()) it.delete() }
        }
    }

    internal companion object {
        const val TMP_DIR = "tool-media-tmp"

        /** Memory-cache key: the sha256 of the source (a data: URI can be megabytes long). */
        fun cacheKey(src: String, full: Boolean): String {
            keysComputed.incrementAndGet()
            return sha256Hex(src.toByteArray(Charsets.UTF_8)) + if (full) ":full" else ":thumb"
        }

        /** How many keys were hashed (a test seam: an over-size data: picture must cost none). */
        internal val keysComputed = java.util.concurrent.atomic.AtomicInteger()
    }
}

// --- The inline row ---------------------------------------------------------------------------

/**
 * `.chat-tool-media` (globals.css:5108-5131): a wrapping row, `space-sm` gaps, padded `space-sm
 * space-md` under a `--line` rule ([bare]: the bubble-attachment variant, no padding or rule).
 * Each picture shows at its own size up to the row width and 320dp tall, `--radius-md` corners;
 * a tap opens the viewer. A clip shows a play tile (the web's inline <video controls> has no
 * native twin without a player per row); a tap opens the viewer, which plays it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolMediaRow(items: List<ToolMediaItem>, modifier: Modifier = Modifier, bare: Boolean = false, limit: Int = MediaLimits.MAX_TILES) {
    if (items.isEmpty()) return
    val t = LocalTetherTokens.current
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val line = t.line
    FlowRow(
        modifier
            .fillMaxWidth()
            .then(
                if (bare) {
                    Modifier
                } else {
                    Modifier
                        .drawBehind { drawRect(line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                        .padding(top = 1.dp)
                        .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
                },
            )
            .testTag("tool-media"),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val shown = limit.coerceIn(0, items.size)
        items.take(shown).forEachIndexed { index, item ->
            MediaTile(item, onOpen = { openIndex = index })
        }
        // At most [limit] tiles load here (the card's share of MAX_TILES); the rest open in the viewer.
        if (items.size > shown) MoreTile(items.size - shown) { openIndex = shown }
    }
    openIndex?.let { index ->
        if (index in items.indices) {
            MediaLightbox(items, index, onIndexChange = { openIndex = it }, onClose = { openIndex = null })
        }
    }
}

/** "+N more": the pictures past the row's tiles, opened in the viewer at the first of them. */
@Composable
private fun MoreTile(count: Int, onOpen: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    val label = "+${localeCount(count)} more"
    Box(
        Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "View $count more", onClick = onOpen)
            .semantics { contentDescription = "$label pictures" }
            .size(44.dp)
            .background(t.tintMd)
            .testTag("tool-media-more"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontFamily = type.ui, fontSize = 11.52.sp), color = t.ink)
    }
}

@Composable
private fun MediaTile(item: ToolMediaItem, onOpen: () -> Unit) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val label = if (item.isVideo) "View video full size" else "View image full size"
    val clickable = Modifier
        .clip(shape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
        .semantics { contentDescription = label }
    if (item.isVideo) {
        Box(
            clickable.size(width = 240.dp, height = 135.dp).background(t.graphite),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).background(t.scrim, CircleShape), contentAlignment = Alignment.Center) {
                Icon(TetherIcons.Play, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
            }
            Icon(
                TetherIcons.Maximize2,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(t.css.spaceXs).size(14.dp),
            )
        }
        return
    }
    val image = rememberMediaImage(item)
    when (val state = image) {
        is MediaImage.Ok -> {
            // CSS px = dp: a picture shows at its own size, capped at the row width and 320dp.
            val w = state.bitmap.width.dp
            val h = state.bitmap.height.dp
            Image(
                bitmap = state.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // `max-width: 100%; max-height: 320px; object-fit: contain` at the picture's own ratio.
                modifier = clickable.widthIn(max = w).heightIn(max = 320.dp).aspectRatio(w.value / h.value),
            )
        }
        null -> Box(clickable.size(44.dp).background(t.tintXs), contentAlignment = Alignment.Center) {
            SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 14.dp)
        }
        else -> MediaUnavailable(if (state == MediaImage.TooLarge) "Image too large to show" else "Image unavailable", clickable = Modifier.clip(shape))
    }
}

@Composable
private fun MediaUnavailable(text: String, clickable: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        clickable.background(t.tintXs).padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.FileImage, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text(text, style = TextStyle(fontFamily = type.ui, fontSize = 11.52.sp), color = t.muted)
    }
}

/** Null while loading; the loader's answer after. No loader (a preview) stays null. */
@Composable
private fun rememberMediaImage(item: ToolMediaItem, full: Boolean = false): MediaImage? {
    val loader = LocalToolMediaLoader.current
    val state by produceState<MediaImage?>(initialValue = null, item.src, loader, full) {
        value = loader?.image(item, full)
    }
    return state
}

// --- The viewer --------------------------------------------------------------------------------

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private fun clampScale(value: Float) = min(MAX_SCALE, max(MIN_SCALE, value))

/** "100%" (`Math.round(scale * 100)%`). */
internal fun zoomLabel(scale: Float): String = "${(scale * 100).roundToInt()}%"

/**
 * `MediaLightbox` (chat-tool-render.tsx:179-368, globals.css:5160-5237): a full-screen `--graphite`
 * viewer. Toolbar (images): zoom out / % / zoom in / reset, then close; prev/next while there is
 * somewhere to go. Pinch zooms (1×–6×), a drag pans once zoomed, a double tap toggles 1× / 2×, a
 * tap on the empty stage or Back closes. Changing item resets the zoom.
 */
@Composable
internal fun MediaLightbox(items: List<ToolMediaItem>, index: Int, onIndexChange: (Int) -> Unit, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val item = items[index]
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    val canPrev = index > 0
    val canNext = index < items.size - 1
    fun zoomBy(factor: Float) {
        scale = clampScale(scale * factor)
        if (scale == MIN_SCALE) offset = Offset.Zero
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(t.graphite)
                .semantics { contentDescription = if (item.isVideo) "Video viewer" else "Image viewer" }
                .testTag("media-lightbox"),
        ) {
            val line = t.line
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!item.isVideo) {
                    ViewerKey(TetherIcons.ZoomOut, "Zoom out", enabled = scale > MIN_SCALE) { zoomBy(1 / 1.4f) }
                    Text(
                        zoomLabel(scale),
                        style = TextStyle(fontFamily = type.mono, fontSize = 11.52.sp, textAlign = TextAlign.Center),
                        color = t.muted,
                        modifier = Modifier.widthIn(min = 51.2.dp).padding(horizontal = t.css.spaceXs),
                    )
                    ViewerKey(TetherIcons.ZoomIn, "Zoom in", enabled = scale < MAX_SCALE) { zoomBy(1.4f) }
                    ViewerKey(TetherIcons.RotateCcw, "Reset zoom", enabled = scale > MIN_SCALE) {
                        scale = MIN_SCALE
                        offset = Offset.Zero
                    }
                    Spacer(Modifier.size(t.css.spaceMd - t.css.spaceXs))
                }
                ViewerKey(TetherIcons.X, "Close", enabled = true, onClick = onClose)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
                    .padding(t.css.spaceLg),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isVideo) {
                    ViewerVideo(item)
                } else {
                    val image = rememberMediaImage(item, full = true)
                    when (image) {
                        is MediaImage.Ok -> Image(
                            bitmap = image.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(index) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            scale = if (scale > MIN_SCALE) MIN_SCALE else 2f
                                            offset = Offset.Zero
                                        },
                                    )
                                }
                                .pointerInput(index) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val next = clampScale(scale * zoom)
                                        scale = next
                                        offset = if (next == MIN_SCALE) Offset.Zero else offset + pan
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                        null -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 18.dp)
                        else -> MediaUnavailable(if (image == MediaImage.TooLarge) "Image too large to show" else "Image unavailable", Modifier)
                    }
                }
                if (canPrev) {
                    NavKey(TetherIcons.ChevronLeft, "Previous media", Modifier.align(Alignment.CenterStart)) { onIndexChange(index - 1) }
                }
                if (canNext) {
                    NavKey(TetherIcons.ChevronRight, "Next media", Modifier.align(Alignment.CenterEnd)) { onIndexChange(index + 1) }
                }
            }
        }
    }
}

@Composable
private fun ViewerKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) t.muted else t.faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NavKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(t.radiusSm))
            .background(t.scrim)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
    }
}

/** A downloaded, bounded clip in a platform VideoView with its media controls; plays on open. */
@Composable
private fun ViewerVideo(item: ToolMediaItem) {
    val t = LocalTetherTokens.current
    val loader = LocalToolMediaLoader.current
    val video by produceState<MediaVideo?>(initialValue = null, item.src, loader) { value = loader?.video(item) }
    when (val v = video) {
        is MediaVideo.Ok -> AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setVideoURI(Uri.fromFile(v.file))
                    setOnPreparedListener { start() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        null -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 18.dp, contentDescription = "Loading video")
        else -> MediaUnavailable(if (v == MediaVideo.TooLarge) "Video too large to play" else "Video unavailable", Modifier)
    }
}

// --- v122 spawned-run media ---------------------------------------------------------------------

/**
 * One source's pictures on a spawned run (subagent-runs.tsx `SpawnedRunMedia`, issue #190):
 * `SpawnedRunProjection.media` filtered to [source] ("input" = handed to the child at spawn,
 * "viewed" = recovered from its rollout), as tool-media items, plus their non-empty labels.
 */
internal fun spawnedRunMedia(media: com.tether.app.protocol.tree.JsValue?, source: String): Pair<List<ToolMediaItem>, List<String>> {
    val items = (media as? com.tether.app.protocol.tree.JsArr)
        ?.filter { asString((it as? com.tether.app.protocol.tree.JsObj)?.get("source")) == source }
        ?: emptyList()
    val labels = items.mapNotNull { asString((it as com.tether.app.protocol.tree.JsObj)["label"])?.takeIf(String::isNotEmpty) }
    return extractToolMedia(com.tether.app.protocol.tree.JsArr.of(items)) to labels
}

/**
 * `.subrun-media`: a "<heading> · N" chip (its labels — the file names — as the accessible name,
 * the web's `title`) over the same [ToolMediaRow] and viewer tool results use. Nothing for no
 * pictures. The spawned-run panel that hosts it is T6.4's.
 */
@Composable
fun SpawnedRunMedia(media: com.tether.app.protocol.tree.JsValue?, source: String, heading: String, modifier: Modifier = Modifier) {
    val (items, labels) = remember(media, source) { spawnedRunMedia(media, source) }
    if (items.isEmpty()) return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val pill = RoundedCornerShape(999.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
        Text(
            "$heading · ${items.size}",
            style = TextStyle(fontFamily = type.ui, fontSize = 10.88.sp),
            color = t.muted,
            modifier = Modifier
                .background(t.tintXs, pill)
                .border(1.dp, t.line, pill)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .semantics { contentDescription = (listOf("$heading · ${items.size}") + labels).joinToString("\n") },
        )
        ToolMediaRow(items, bare = true)
    }
}
