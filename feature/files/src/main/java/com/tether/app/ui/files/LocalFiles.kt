package com.tether.app.ui.files

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.tether.app.client.UploadSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Serves ONLY cache/workspace-files/share/ (res/xml/workspace_file_paths.xml). Created at process
 * start, so it is also where the cache left behind by a previous process is swept (off the main
 * thread): no scratch copy can be in flight yet, and shared copies go once past their window.
 */
class WorkspaceFileProvider : FileProvider(com.tether.app.feature.files.R.xml.workspace_file_paths) {
    override fun onCreate(): Boolean {
        val created = super.onCreate()
        context?.cacheDir?.let { dir -> FileCache.sweepInBackground { FileCache(dir).sweepAtStartup() } }
        return created
    }
}

/**
 * The browser's local scratch space: `cacheDir/workspace-files/`. The cache directory is never
 * part of Auto Backup or device transfer (Android excludes `getCacheDir()`; the app's backup
 * rules include nothing from it), and the OS may reclaim it. The browser empties it itself:
 *
 * - scratch copies (image previews, saves in progress) are deleted as soon as they are used;
 * - a shared copy lives for [SHARE_GRACE_MS] after it was handed to the share sheet, so the
 *   receiving app can read it, then any sweep removes it;
 * - a sweep runs at process start ([sweepAtStartup]: every scratch copy, expired shares), when
 *   the browser opens or closes ([sweepExpired]: only what is past its window, so nothing in
 *   flight or still being read goes), and when its host leaves composition, sign-out included
 *   ([sweepAll]).
 *
 * No local path is ever built from a server-supplied value: scratch copies get random names, and
 * a shared copy's name goes through [LocalNames.safe] inside its own random directory, with the
 * result checked to sit inside that directory ([placeIn]).
 */
class FileCache(cacheDir: File) {
    val root: File = File(cacheDir, DIR)
    private val shareRoot = File(root, SHARE_DIR)

    /** A fresh scratch file with a random name (never a server name). */
    fun newScratch(kind: String = "preview"): File {
        root.mkdirs()
        return File(root, "$kind-${UUID.randomUUID()}.bin")
    }

    /** A new file for a shared copy of [serverName], alone in a random directory. */
    fun newShareFile(serverName: String): File {
        val dir = File(shareRoot, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("could not create a share directory")
        return placeIn(dir, LocalNames.safe(serverName))
    }

    /** Everything but what is younger than [windowMs] (by its newest file). */
    fun sweepExpired(now: Long = System.currentTimeMillis(), windowMs: Long = SHARE_GRACE_MS) {
        root.listFiles()?.forEach { child ->
            if (child == shareRoot) {
                shareRoot.listFiles()?.forEach { share -> if (now - newest(share) >= windowMs) share.deleteRecursively() }
            } else if (now - newest(child) >= windowMs) {
                child.deleteRecursively()
            }
        }
    }

    /** Process start: no scratch copy can be in use, and expired shares go. */
    fun sweepAtStartup(now: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach { child -> if (child != shareRoot) child.deleteRecursively() }
        sweepExpired(now)
    }

    /** Everything, shares included (the session that downloaded them is over). */
    fun sweepAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun newest(file: File): Long =
        if (file.isDirectory) maxOf(file.lastModified(), file.listFiles()?.maxOfOrNull(::newest) ?: 0L) else file.lastModified()

    companion object {
        const val DIR = "workspace-files"
        const val SHARE_DIR = "share"

        /** A shared copy stays this long for the receiving app to read it. */
        const val SHARE_GRACE_MS = 10 * 60 * 1000L

        fun authority(context: Context) = "${context.packageName}.workspacefiles"

        /**
         * [name] as a file inside [dir], or IOException when it would land anywhere else (a
         * separator, `..`, an absolute path). The last line of defence behind [LocalNames.safe].
         */
        fun placeIn(dir: File, name: String): File {
            val file = File(dir, name)
            if (name.isEmpty() || file.canonicalFile.parentFile != dir.canonicalFile) throw IOException("unsafe local name")
            return file
        }

        /** Sweeps delete recursively: never on the main thread. */
        fun sweepInBackground(sweep: () -> Unit) {
            Thread({ runCatching(sweep) }, "workspace-files-sweep").apply { isDaemon = true }.start()
        }
    }
}

/** What an image decode came to. */
sealed interface Decoded {
    data class Ok(val bitmap: Bitmap) : Decoded
    data object TooLarge : Decoded
    data object Failed : Decoded
}

/**
 * Bitmap decoding for the image preview, bounded three ways: the file is at most
 * [BrowserLimits.MAX_IMAGE_PREVIEW_BYTES] (enforced while downloading); a header that claims more
 * than [BrowserLimits.MAX_IMAGE_PIXELS] is refused before any pixel is decoded; and the decode is
 * sampled down until neither side passes [BrowserLimits.MAX_IMAGE_SIDE] AND the bitmap fits in
 * [BrowserLimits.MAX_DECODED_BYTES] at the config the decoder reports (8 bytes a pixel for a
 * 16-bit/HDR source that decodes to RGBA_F16, 4 otherwise). So neither a decompression bomb nor a
 * wide-gamut 4096² image can exhaust memory or trip the canvas's "bitmap too large" limit.
 */
object BoundedImages {
    fun decode(
        file: File,
        // The platform decoder; a parameter only so tests can make it throw.
        decodeFile: (String, BitmapFactory.Options) -> Bitmap? = { path, options -> BitmapFactory.decodeFile(path, options) },
    ): Decoded = try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        decodeFile(file.path, options)
        val sample = plan(options.outWidth, options.outHeight, bytesPerPixel(options.outConfig))
        when {
            options.outWidth <= 0 || options.outHeight <= 0 -> Decoded.Failed
            sample == null -> Decoded.TooLarge
            else -> {
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val bitmap = decodeFile(file.path, options)
                when {
                    bitmap == null -> Decoded.Failed
                    // The decoder's own answer, whatever config it chose.
                    bitmap.allocationByteCount > BrowserLimits.MAX_DECODED_BYTES -> {
                        bitmap.recycle()
                        Decoded.TooLarge
                    }
                    else -> Decoded.Ok(bitmap)
                }
            }
        }
    } catch (_: OutOfMemoryError) {
        Decoded.TooLarge
    } catch (_: RuntimeException) {
        Decoded.Failed
    }

    /** RGBA_F16 (16-bit PNG, HDR AVIF) is 8 bytes a pixel; count everything else as 4 (ARGB_8888, the widest of the rest). */
    fun bytesPerPixel(config: Bitmap.Config?): Int = if (config == Bitmap.Config.RGBA_F16) 8 else 4

    /**
     * The power-of-two sample that fits [width] × [height] at [bytesPerPixel] under both caps, or
     * null when the header claims more than [BrowserLimits.MAX_IMAGE_PIXELS] (refused, not sampled:
     * the decoder would still inflate every row).
     */
    fun plan(
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        maxSide: Int = BrowserLimits.MAX_IMAGE_SIDE,
        maxBytes: Long = BrowserLimits.MAX_DECODED_BYTES,
    ): Int? {
        if (width <= 0 || height <= 0) return 1
        if (width.toLong() * height > BrowserLimits.MAX_IMAGE_PIXELS) return null
        var sample = 1
        while (true) {
            val w = (width / sample).toLong()
            val h = (height / sample).toLong()
            if (w <= maxSide && h <= maxSide && w * h * bytesPerPixel <= maxBytes) return sample
            sample *= 2
        }
    }
}

/**
 * The name a picked document is uploaded as, like a browser's `File.name`: the provider's display
 * name, last path segment only. Null when it carries a control character (NUL included), which
 * no browser file name can: the upload is refused with the server's own copy instead of sent.
 */
object UploadNames {
    const val FALLBACK = "upload"
    const val INVALID = "Name must be a single name (no path separators, no leading dot)."

    fun fromDisplayName(displayName: String?): String? {
        val last = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        if (last.any { it.code < 0x20 || it.code == 0x7F }) return null
        return last.ifBlank { FALLBACK }
    }
}

/** A document the user picked in the system picker (ACTION_OPEN_DOCUMENT), read as a stream. */
class ContentUploadSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val length: Long?,
) : UploadSource {
    /** A revoked grant or a vanished document is an I/O failure of this upload, never a crash. */
    override fun open(): InputStream = try {
        resolver.openInputStream(uri) ?: throw IOException("the picked document could not be opened")
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw IOException("the picked document could not be opened", e)
    }

    companion object {
        /** The picker's display name and size (either may be missing: the provider decides). */
        fun describe(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                name to size
            } ?: (null to null)
        } catch (_: RuntimeException) {
            null to null
        }
    }
}

object BrowserLimits {
    /** Native-only: the web's <img> has no cap; the app downloads an image to decode it. */
    const val MAX_IMAGE_PREVIEW_BYTES: Long = 32L * 1024L * 1024L

    /** Decoded images are sampled down to at most this many pixels a side… */
    const val MAX_IMAGE_SIDE: Int = 4096

    /** …and at most this many bytes (well under the 100 MB canvas limit). */
    const val MAX_DECODED_BYTES: Long = 64L * 1024L * 1024L

    /** A header claiming more pixels than this is refused outright. */
    const val MAX_IMAGE_PIXELS: Long = 100_000_000L

    /** Saving to the device or sharing out streams at most this much (the server's own upload cap). */
    const val MAX_EXPORT_BYTES: Long = 512L * 1024L * 1024L
}
