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

/** Serves ONLY cache/workspace-files/share/ (res/xml/workspace_file_paths.xml). */
class WorkspaceFileProvider : FileProvider(com.tether.app.feature.files.R.xml.workspace_file_paths)

/**
 * The browser's local scratch space: `cacheDir/workspace-files/`. The cache directory is never
 * part of Auto Backup or device transfer (Android excludes `getCacheDir()`; the app's backup
 * rules include nothing from it), the OS may reclaim it, and the browser empties it itself:
 * preview copies are deleted as soon as they are decoded, and the whole directory is swept when
 * the browser opens and when its host leaves composition (sign-out included).
 *
 * No local path is ever built from a server-supplied value: preview copies get random names,
 * and a shared copy's name goes through [LocalNames.safe] inside its own random directory, with
 * the result checked to sit inside that directory.
 */
class FileCache(cacheDir: File) {
    val root: File = File(cacheDir, DIR)
    private val shareRoot = File(root, SHARE_DIR)

    /** A fresh preview scratch file with a random name (never a server name). */
    fun newScratch(): File {
        root.mkdirs()
        return File(root, "preview-${UUID.randomUUID()}.bin")
    }

    /** A new file for a shared copy of [serverName], alone in a random directory. */
    fun newShareFile(serverName: String): File {
        val dir = File(shareRoot, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("could not create a share directory")
        val file = File(dir, LocalNames.safe(serverName))
        // Belt and braces: the sanitised name cannot leave its directory.
        if (file.canonicalFile.parentFile != dir.canonicalFile) throw IOException("unsafe local name")
        return file
    }

    /** Delete everything, or (with [keepSharesYoungerThanMs]) everything but recent shared copies. */
    fun sweep(now: Long = System.currentTimeMillis(), keepSharesYoungerThanMs: Long? = null) {
        val children = root.listFiles() ?: return
        for (child in children) {
            if (child == shareRoot && keepSharesYoungerThanMs != null) {
                shareRoot.listFiles()?.forEach { share ->
                    if (now - share.lastModified() >= keepSharesYoungerThanMs) share.deleteRecursively()
                }
            } else {
                child.deleteRecursively()
            }
        }
    }

    companion object {
        const val DIR = "workspace-files"
        const val SHARE_DIR = "share"

        /** A shared copy stays this long after the browser closes, for the receiving app to read it. */
        const val SHARE_GRACE_MS = 10 * 60 * 1000L

        fun authority(context: Context) = "${context.packageName}.workspacefiles"
    }
}

/**
 * Bitmap decoding for the image preview, bounded twice: the file is at most
 * [BrowserLimits.MAX_IMAGE_PREVIEW_BYTES] (enforced while downloading), and the decode is sampled
 * down so neither side passes [BrowserLimits.MAX_IMAGE_SIDE] — a tiny PNG that claims
 * 30000 x 30000 pixels (a decompression bomb) becomes a small bitmap, never a 3.6 GB one.
 */
object BoundedImages {
    fun decode(file: File, maxSide: Int = BrowserLimits.MAX_IMAGE_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /** The smallest power of two that brings both sides to [maxSide] or less. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2
        return sample
    }
}

/** A document the user picked in the system picker (ACTION_OPEN_DOCUMENT), read as a stream. */
class ContentUploadSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val length: Long?,
) : UploadSource {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("the picked document could not be opened")

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

    /** Decoded images are sampled down to at most this many pixels a side. */
    const val MAX_IMAGE_SIDE: Int = 4096

    /** Saving to the device or sharing out streams at most this much (the server's own upload cap). */
    const val MAX_EXPORT_BYTES: Long = 512L * 1024L * 1024L
}
