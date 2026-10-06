package com.tether.app.ui.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.tether.app.client.FilesCopy
import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFiles
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The document the user chose in the system "create document" picker, behind a seam (the real one
 * is the ContentResolver; tests pass their own). Every call may throw anything a provider throws.
 */
interface DocumentTarget {
    /** The document's current size, null when the provider does not say. */
    fun sizeOf(uri: Uri): Long?
    fun openForWrite(uri: Uri, truncate: Boolean): OutputStream
    fun delete(uri: Uri)
}

class ResolverDocuments(private val resolver: ContentResolver) : DocumentTarget {
    override fun sizeOf(uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        }

    override fun openForWrite(uri: Uri, truncate: Boolean): OutputStream =
        resolver.openOutputStream(uri, if (truncate) "wt" else "w") ?: throw IOException("the provider gave no stream")

    override fun delete(uri: Uri) {
        DocumentsContract.deleteDocument(resolver, uri)
    }
}

/**
 * The real [BrowserPlatform]. Every byte comes through [WorkspaceFiles.download] (streamed, with a
 * cap) into the app cache ([FileCache]); a document the user chose is written only once the whole
 * file is in hand. Nothing is ever written to a path derived from a server-supplied name, and no
 * provider, decoder or I/O failure escapes as an exception: each is an error result.
 */
class AndroidBrowserPlatform(
    private val context: Context,
    private val documents: DocumentTarget = ResolverDocuments(context.contentResolver),
    private val cache: FileCache = FileCache(context.cacheDir),
    /** Runs a sweep off the main thread (tests run it inline). */
    private val background: (() -> Unit) -> Unit = FileCache::sweepInBackground,
    /** The content URI the share sheet gets (the provider's grant); a seam only for tests. */
    private val uriFor: (File) -> Uri = { file -> FileProvider.getUriForFile(context, FileCache.authority(context), file) },
) : BrowserPlatform {

    /** Shared copies made but not yet handed to the share sheet, by id (their directory). */
    private val unclaimed = ConcurrentHashMap<String, File>()

    /**
     * The web's `<img>` has no size cap, so neither does this: the file streams to a scratch copy on
     * disk (no byte limit; a full disk is an I/O failure) and is decoded from there, sampled down to
     * fit the memory bounds ([BoundedImages]) rather than refused. Any failure is "could not be displayed".
     */
    override suspend fun loadImage(files: WorkspaceFiles, entry: WorkspaceFileEntry): ImageLoad = withContext(Dispatchers.IO) {
        var scratch: File? = null
        try {
            val file = cache.newScratch().also { scratch = it }
            val result = file.outputStream().use { out -> files.download(entry.path, Long.MAX_VALUE, out) }
            when (result) {
                is FilesResult.Failed -> ImageLoad.Failed(FileBrowserState.IMAGE_ERROR)
                is FilesResult.Ok -> when (val decoded = BoundedImages.decode(file)) {
                    is Decoded.Ok -> ImageLoad.Ok(decoded.bitmap.asImageBitmap())
                    Decoded.Failed -> ImageLoad.Failed(FileBrowserState.IMAGE_ERROR)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            ImageLoad.Failed(FileBrowserState.IMAGE_ERROR)
        } catch (_: RuntimeException) {
            ImageLoad.Failed(FileBrowserState.IMAGE_ERROR)
        } finally {
            // The decoded bitmap is all the preview keeps; the copy goes at once.
            scratch?.delete()
        }
    }

    /**
     * An SVG has no byte cap (the web's `<img>` has none): it streams into a scratch copy and is
     * parsed from there, the copy deleted at once. A file too big to parse is an out-of-memory in the
     * parser, which is "could not be displayed", never a crash.
     */
    override suspend fun loadSvg(files: WorkspaceFiles, entry: WorkspaceFileEntry): SvgLoad = withContext(Dispatchers.IO) {
        var scratch: File? = null
        try {
            val file = cache.newScratch("svg").also { scratch = it }
            val result = file.outputStream().use { out -> files.download(entry.path, Long.MAX_VALUE, out) }
            when (result) {
                is FilesResult.Failed -> SvgLoad.Failed(result.message)
                is FilesResult.Ok -> SvgImages.parse(file)?.let { SvgLoad.Ok(it) } ?: SvgLoad.Failed(FileBrowserState.IMAGE_ERROR)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            SvgLoad.Failed(FileBrowserState.IMAGE_ERROR)
        } catch (_: RuntimeException) {
            SvgLoad.Failed(FileBrowserState.IMAGE_ERROR)
        } finally {
            scratch?.delete()
        }
    }

    override fun openVideo(files: WorkspaceFiles, entry: WorkspaceFileEntry, onFailed: () -> Unit): VideoPlayer =
        MediaVideoPlayer(
            WorkspaceMediaDataSource(files, entry.path),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            onFailed,
        )

    /**
     * Download to a scratch copy first; only a complete file is written to [target], so a failed
     * download never touches it. A failure deletes the document only when it was empty before
     * (the one this flow's picker just created), never a file the user chose to overwrite.
     */
    override suspend fun saveTo(files: WorkspaceFiles, entry: WorkspaceFileEntry, target: Uri): FilesResult<Long> = withContext(Dispatchers.IO) {
        var scratch: File? = null
        val wasEmpty = try {
            documents.sizeOf(target) == 0L
        } catch (_: RuntimeException) {
            false
        }
        try {
            val file = cache.newScratch("save").also { scratch = it }
            val fetched = file.outputStream().use { out -> files.download(entry.path, BrowserLimits.MAX_EXPORT_BYTES, out) }
            if (fetched is FilesResult.Failed) {
                discardCreated(target, wasEmpty)
                return@withContext fetched
            }
            // Truncate only a document that had content; an empty one needs no "t" (not every provider supports it).
            documents.openForWrite(target, truncate = !wasEmpty).use { out -> file.inputStream().use { it.copyTo(out) } }
            fetched
        } catch (e: CancellationException) {
            discardCreated(target, wasEmpty)
            throw e
        } catch (_: IOException) {
            discardCreated(target, wasEmpty)
            FilesResult.Failed(SAVE_FAILED)
        } catch (_: RuntimeException) {
            // SecurityException (grant revoked), IllegalArgumentException / UnsupportedOperationException (mode).
            discardCreated(target, wasEmpty)
            FilesResult.Failed(SAVE_FAILED)
        } finally {
            scratch?.delete()
        }
    }

    private fun discardCreated(target: Uri, wasEmpty: Boolean) {
        if (!wasEmpty) return
        try {
            documents.delete(target)
        } catch (_: Exception) {
            // Best effort: an empty document is all that can be left.
        }
    }

    override suspend fun shareCopy(files: WorkspaceFiles, entry: WorkspaceFileEntry): FilesResult<ShareReady> = withContext(Dispatchers.IO) {
        if (entry.size > BrowserLimits.MAX_EXPORT_BYTES) return@withContext FilesResult.Failed(FilesCopy.DOWNLOAD_TOO_LARGE, tooLarge = true)
        var dir: File? = null
        var handedOver = false
        try {
            val file = cache.newShareFile(entry.name)
            val parent = checkNotNull(file.parentFile)
            dir = parent
            // Tracked from the start: a copy whose result is lost to a cancellation is still found.
            unclaimed[parent.path] = parent
            val result = file.outputStream().use { out -> files.download(entry.path, BrowserLimits.MAX_EXPORT_BYTES, out) }
            when (result) {
                is FilesResult.Failed -> result
                is FilesResult.Ok -> {
                    val uri = uriFor(file)
                    handedOver = true
                    FilesResult.Ok(ShareReady(uri, mimeFor(file), file.name, parent.path))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            FilesResult.Failed(SHARE_FAILED)
        } catch (_: RuntimeException) {
            FilesResult.Failed(SHARE_FAILED)
        } finally {
            if (!handedOver) {
                dir?.let {
                    unclaimed.remove(it.path)
                    it.deleteRecursively()
                }
            }
        }
    }

    /** The share sheet has it: its window starts now, and only an expiry sweep removes it. */
    override fun claimShare(share: ShareReady) {
        val now = System.currentTimeMillis()
        unclaimed.remove(share.id)?.walkTopDown()?.forEach { it.setLastModified(now) }
    }

    override fun discardShare(share: ShareReady) {
        unclaimed.remove(share.id)?.let { dir -> background { dir.deleteRecursively() } }
    }

    override fun discardUnclaimedShares() {
        val dirs = unclaimed.keys.toList().mapNotNull { unclaimed.remove(it) }
        if (dirs.isNotEmpty()) background { dirs.forEach { it.deleteRecursively() } }
    }

    override fun sweep(mode: SweepMode) {
        background {
            when (mode) {
                SweepMode.Expired -> cache.sweepExpired()
                SweepMode.All -> cache.sweepAll()
            }
        }
    }

    companion object {
        const val SAVE_FAILED = "The file could not be saved."
        const val SHARE_FAILED = "The file could not be prepared for sharing."

        /** By extension only (never the server's Content-Type, never sniffed). */
        fun mimeFor(file: File): String =
            java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }
}
