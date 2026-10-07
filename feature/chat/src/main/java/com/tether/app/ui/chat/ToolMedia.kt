package com.tether.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
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
 * decoded under hard bounds, and a clip plays in the platform MediaPlayer while its one download
 * lands in a scratch file (ToolClips.kt). No WebView anywhere.
 */

/** What loading one picture came to. */
@Immutable
sealed interface MediaImage {
    /**
     * [naturalWidth] x [naturalHeight]: the SOURCE image's real pixel size (the header's, before the
     * bounded decode sampled it down), which is what an inline prose picture is laid out from.
     */
    data class Ok(val bitmap: ImageBitmap, val naturalWidth: Int = bitmap.width, val naturalHeight: Int = bitmap.height) : MediaImage
    data object TooLarge : MediaImage

    /** T6.8: a sign-in gateway answered instead of Tether ([ToolMediaResult.Blocked]). */
    data object Blocked : MediaImage
    data object Failed : MediaImage
}

/** What loading one clip came to: a local, bounded copy to play. */
@Immutable
sealed interface MediaVideo {
    data class Ok(val file: File) : MediaVideo
    data object TooLarge : MediaVideo

    /** T6.8: a sign-in gateway answered instead of Tether ([ToolMediaResult.Blocked]). */
    data object Blocked : MediaVideo
    data object Failed : MediaVideo
}

/**
 * T6.8: what an unshowable picture or clip says. A sign-in page gets its own two lines (the web
 * has no copy here: its `<img>` just breaks), worded like the sign-in screen's gateway notice;
 * nothing from the gateway's answer is ever shown. Every other failure keeps its one line.
 */
internal object MediaCopy {
    const val BLOCKED = "Blocked by a sign-in page"
    const val BLOCKED_DETAIL = "A sign-in gateway (SSO or a proxy) answered instead of Tether. Exempt /api/tool-media/ for paired devices."
    const val IMAGE_TOO_LARGE = "Image too large to show"
    const val IMAGE_UNAVAILABLE = "Image unavailable"
    const val VIDEO_TOO_LARGE = "Video too large to play"
    const val VIDEO_UNAVAILABLE = "Video unavailable"
}

/** The seam the transcript loads media through (a fake in tests and goldens). */
interface ToolMediaLoader {
    /** [full]: the viewer's larger decode; otherwise the in-row thumbnail. */
    suspend fun image(item: ToolMediaItem, full: Boolean = false): MediaImage

    /** ta-coik.58: an inline prose picture, decoded to [MediaLimits.PROSE_SIDE] (default: the thumbnail). */
    suspend fun prose(item: ToolMediaItem): MediaImage = image(item)
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

    /**
     * An inline prose picture (ta-coik.58): enough for a column-wide picture at about 2.6x density
     * without softness, 1280 x 1280 x 4 = ~6.5 MB decoded (a third of [CACHE_BYTES], where the
     * viewer's 16 MB would take two thirds of it).
     */
    const val PROSE_SIDE: Int = 1280
    const val PROSE_DECODED_BYTES: Long = 1280L * 1280L * 4L

    /** The viewer's decode. */
    const val FULL_SIDE: Int = 2048
    const val FULL_DECODED_BYTES: Long = 16L * 1024L * 1024L

    /** A header claiming more pixels than this is refused outright (a decompression bomb). */
    const val MAX_IMAGE_PIXELS: Long = 100_000_000L

    /**
     * A clip's byte bound: 100 MiB, which is the SERVER's own cap, not an app limit
     * (`MAX_MEDIA_BYTES`, tether lib/tool-media-store.mjs:31 at 29537e0): the server never stores or
     * serves a larger one, so a clip that claims more is not one of its clips.
     */
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

    /** One picture may take this long end to end, then it fails. */
    const val IMAGE_TIMEOUT_MS: Long = 60_000

    /**
     * A clip has NO whole-download limit (the browser has none): it fails only when it STALLS, no byte
     * arriving for this long (ta-coik.68).
     */
    const val VIDEO_STALL_MS: Long = 30_000

    /** The image types a `data:` URI may carry (lib/tool-media-store.mjs IMAGE_MEDIA_TYPES). */
    val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
}

/**
 * The card-wide tile budget (R3-M1): [MediaLimits.MAX_TILES] handed out in order across [rows]
 * — in screen order: each drawn sub-agent entry of an OPEN thread first, then the card's own
 * result; a row past the budget gets 0 tiles and only its "+N more" tile.
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

    /** The image type [file]'s first bytes name (png, jpeg, gif, webp), or null. */
    fun imageType(file: File): String? = MediaLimits.IMAGE_TYPES.firstOrNull { matches(file, it) }

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
        // The header's size, read NOW: the full decode below rewrites outWidth/outHeight to the sampled size.
        val naturalWidth = options.outWidth
        val naturalHeight = options.outHeight
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
                    else -> MediaImage.Ok(bitmap.asImageBitmap(), naturalWidth, naturalHeight)
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
    // ta-coik.58: a prose image's other two sources (`/api/files?path=` over the paired credential,
    // `http(s)` with none). The defaults reach nothing (previews and tests).
    private val files: com.tether.app.client.WorkspaceFiles = com.tether.app.client.WorkspaceFiles.Unavailable,
    private val remote: com.tether.app.client.PublicImageSource = com.tether.app.client.PublicImageSource.Unavailable,
    // L4: the picture's end-to-end timeout and the clip's stall timeout (parameters only so tests need not wait).
    private val imageTimeoutMs: Long = MediaLimits.IMAGE_TIMEOUT_MS,
    private val videoStallMs: Long = MediaLimits.VIDEO_STALL_MS,
) : ToolMediaLoader {
    private val cache = object : LruCache<String, MediaImage.Ok>(MediaLimits.CACHE_BYTES) {
        override fun sizeOf(key: String, value: MediaImage.Ok): Int = value.bitmap.asAndroidBitmap().allocationByteCount
    }

    private val tmpDir: File get() = File(cacheDir, TMP_DIR).apply { mkdirs() }

    override suspend fun image(item: ToolMediaItem, full: Boolean): MediaImage = load(item, if (full) Tier.Full else Tier.Thumb)

    override suspend fun prose(item: ToolMediaItem): MediaImage = load(item, Tier.Prose)

    private suspend fun load(item: ToolMediaItem, tier: Tier): MediaImage {
        if (item.isVideo) return MediaImage.Failed
        // R3-L2: an over-size data: picture is refused before anything touches it (no hashing).
        parseDataUri(item.src)?.let { if (it.payloadLength.toLong() / 4 * 3 > MediaLimits.MAX_IMAGE_BYTES) return MediaImage.TooLarge }
        return try {
            // The key is computed once, off the main thread.
            val key = withContext(Dispatchers.IO) { cacheKey(item.src, tier) }
            cache.get(key)?.let { return it }
            // The timeout starts once a load slot is held (a queued picture is not "slow").
            val result = MediaGates.images.withPermit {
                kotlinx.coroutines.withTimeoutOrNull(imageTimeoutMs) { withContext(Dispatchers.IO) { loadImage(item.src, tier) } }
            } ?: MediaImage.Failed
            if (result is MediaImage.Ok) cache.put(key, result)
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

    private suspend fun loadImage(src: String, tier: Tier): MediaImage {
        val side = tier.side
        val bytes = tier.bytes
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
            if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
                return proseImage(file, side, bytes) { out -> remote.fetch(src, MediaLimits.MAX_IMAGE_BYTES, out) }
            }
            if (src.startsWith(FILES_ROUTE_PREFIX, ignoreCase = true)) {
                val path = servedFilePath(src) ?: return MediaImage.Failed
                return proseImage(file, side, bytes) { out ->
                    when (val r = files.download(path, MediaLimits.MAX_IMAGE_BYTES, out)) {
                        is com.tether.app.client.FilesResult.Ok -> ToolMediaResult.Ok(r.value, "")
                        is com.tether.app.client.FilesResult.Failed -> if (r.tooLarge) ToolMediaResult.TooLarge else ToolMediaResult.Failed(r.status)
                    }
                }
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
                is ToolMediaResult.Blocked -> MediaImage.Blocked
                else -> MediaImage.Failed
            }
        } catch (_: java.io.IOException) {
            return MediaImage.Failed
        } finally {
            tmp?.delete()
        }
    }

    /**
     * ta-coik.58: one prose picture from a source that names no content hash (a served file, a public
     * URL): streamed into [file] under the same byte cap, then it must BE a png / jpeg / gif / webp
     * by its first bytes (the declared type is not trusted) before the bounded decode.
     */
    private suspend fun proseImage(
        file: File,
        side: Int,
        bytes: Long,
        fetch: suspend (java.io.OutputStream) -> ToolMediaResult,
    ): MediaImage {
        val result = FileOutputStream(file).use { out -> fetch(out) }
        return when {
            result == ToolMediaResult.TooLarge -> MediaImage.TooLarge
            result is ToolMediaResult.Blocked -> MediaImage.Blocked
            result !is ToolMediaResult.Ok -> MediaImage.Failed
            MediaMagic.imageType(file) == null -> MediaImage.Failed
            else -> BoundedMediaDecoder.decode(file, side, bytes)
        }
    }

    /**
     * The whole clip, verified: one [ClipDownload] run to its end (the same engine the inline player
     * reads while it downloads), then its cached file. Not a streaming path and not time-boxed: a
     * stall is the only way it gives up.
     */
    override suspend fun video(item: ToolMediaItem): MediaVideo {
        val ext = ToolMediaSource.extensionOf(item.src)
        if (ext != "mp4" || origin == null) return MediaVideo.Failed
        val expected = namedSha256(item.src) ?: return MediaVideo.Failed
        return try {
            MediaGates.clip(expected).withLock {
                withContext(Dispatchers.IO) {
                    val download = ClipDownload(source, cacheDir, origin, item.src, expected, videoStallMs)
                    download.run()
                    download.outcome() ?: MediaVideo.Failed
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            MediaVideo.TooLarge
        } catch (_: Exception) {
            MediaVideo.Failed
        }
    }

    /** A decode tier: the transcript's tile thumbnail, an inline prose picture, the viewer. */
    internal enum class Tier(val key: String, val side: Int, val bytes: Long) {
        Thumb("thumb", MediaLimits.THUMB_SIDE, MediaLimits.THUMB_DECODED_BYTES),
        Prose("prose", MediaLimits.PROSE_SIDE, MediaLimits.PROSE_DECODED_BYTES),
        Full("full", MediaLimits.FULL_SIDE, MediaLimits.FULL_DECODED_BYTES),
    }

    internal companion object {
        const val TMP_DIR = "tool-media-tmp"

        /** Memory-cache key: the sha256 of the source (a data: URI can be megabytes long). */
        fun cacheKey(src: String, tier: Tier): String {
            keysComputed.incrementAndGet()
            return sha256Hex(src.toByteArray(Charsets.UTF_8)) + ":" + tier.key
        }

        fun cacheKey(src: String, full: Boolean): String = cacheKey(src, if (full) Tier.Full else Tier.Thumb)

        /** How many keys were hashed (a test seam: an over-size data: picture must cost none). */
        internal val keysComputed = java.util.concurrent.atomic.AtomicInteger()
    }
}

// --- The inline row ---------------------------------------------------------------------------

/**
 * `.chat-tool-media` (globals.css:5108-5131): a wrapping row, `space-sm` gaps, padded `space-sm
 * space-md` under a `--line` rule ([bare]: the bubble-attachment variant, no padding or rule).
 * Each picture shows at its own size up to the row width and 320dp tall, `--radius-md` corners;
 * a tap opens the viewer. A clip is the web's inline <video controls> (ToolMediaVideo.kt): a tap
 * plays it in the row, its expand key opens the viewer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolMediaRow(items: List<ToolMediaItem>, modifier: Modifier = Modifier, bare: Boolean = false, limit: Int = MediaLimits.MAX_TILES) {
    if (items.isEmpty()) return
    val t = LocalTetherTokens.current
    // ta-coik.68: which item the viewer shows lives in the clip registry (keyed by src), not in this row: the
    // phone and expanded shells each compose their own transcript, so a rotation that switches shell builds
    // this row anew, and its own state would be gone. No registry (previews, tests): the row's own saved index.
    val registry = LocalToolClips.current
    var localIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val openSrc = registry?.openViewerSrc
    val openIndex: Int? = if (registry != null) openSrc?.let { src -> items.indexOfFirst { it.src == src }.takeIf { it >= 0 } } else localIndex
    fun setOpen(index: Int?) {
        if (registry != null) registry.openViewer(index?.let { items[it].src }) else localIndex = index
    }
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
            MediaTile(item, onOpen = { setOpen(index) })
        }
        // At most [limit] tiles load here (the card's share of MAX_TILES); the rest open in the viewer.
        if (items.size > shown) MoreTile(items.size - shown) { setOpen(shown) }
    }
    openIndex?.let { index ->
        if (index in items.indices) {
            MediaLightbox(items, index, onIndexChange = { setOpen(it) }, onClose = { setOpen(null) })
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
    val label = "View image full size"
    val clickable = Modifier
        .clip(shape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
        .semantics { contentDescription = label }
    if (item.isVideo) {
        // ta-coik.68: the web's inline <video controls>, plus its expand key (ToolMediaVideo.kt).
        InlineVideo(item, onOpen)
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
        else -> ImageUnavailable(state, clickable = Modifier.clip(shape))
    }
}

@Composable
private fun ImageUnavailable(state: MediaImage, clickable: Modifier) = when (state) {
    MediaImage.Blocked -> MediaUnavailable(MediaCopy.BLOCKED, clickable, detail = MediaCopy.BLOCKED_DETAIL)
    MediaImage.TooLarge -> MediaUnavailable(MediaCopy.IMAGE_TOO_LARGE, clickable)
    else -> MediaUnavailable(MediaCopy.IMAGE_UNAVAILABLE, clickable)
}

/** [plain]: no tint behind it (a clip's box draws its own surface); [icon]: the glyph for the kind of media. */
@Composable
internal fun MediaUnavailable(
    text: String,
    clickable: Modifier,
    detail: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector = TetherIcons.FileImage,
    plain: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        clickable.then(if (plain) Modifier else Modifier.background(t.tintXs)).padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs),
        verticalAlignment = if (detail == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        if (detail == null) {
            Text(text, style = TextStyle(fontFamily = type.ui, fontSize = 11.52.sp), color = t.muted)
        } else {
            Column(Modifier.widthIn(max = 280.dp)) {
                Text(text, style = TextStyle(fontFamily = type.ui, fontSize = 11.52.sp), color = t.ink)
                Text(detail, style = TextStyle(fontFamily = type.ui, fontSize = 11.52.sp), color = t.muted)
            }
        }
    }
}

/** Null while loading; the loader's answer after. No loader (a preview) stays null. */
@Composable
internal fun rememberMediaImage(item: ToolMediaItem, full: Boolean = false, prose: Boolean = false): MediaImage? {
    val loader = LocalToolMediaLoader.current
    val state by produceState<MediaImage?>(initialValue = null, item.src, loader, full, prose) {
        value = if (prose) loader?.prose(item) else loader?.image(item, full)
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
                        else -> ImageUnavailable(image, Modifier)
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
