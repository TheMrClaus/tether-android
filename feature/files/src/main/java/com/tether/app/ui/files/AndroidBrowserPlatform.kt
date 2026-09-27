package com.tether.app.ui.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.tether.app.client.FilesCopy
import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFiles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [BrowserPlatform]. Every byte comes through [WorkspaceFiles.download] (streamed, with a
 * cap) into either the app cache ([FileCache]) or a document the user chose in the system
 * picker; nothing is ever written to a path derived from a server-supplied name.
 */
class AndroidBrowserPlatform(private val context: Context) : BrowserPlatform {
    private val cache = FileCache(context.cacheDir)

    override suspend fun loadImage(files: WorkspaceFiles, entry: WorkspaceFileEntry): ImageLoad = withContext(Dispatchers.IO) {
        if (entry.size > BrowserLimits.MAX_IMAGE_PREVIEW_BYTES) return@withContext ImageLoad.TooLarge
        val scratch = cache.newScratch()
        try {
            val result = scratch.outputStream().use { out -> files.download(entry.path, BrowserLimits.MAX_IMAGE_PREVIEW_BYTES, out) }
            when (result) {
                is FilesResult.Failed -> if (result.tooLarge) ImageLoad.TooLarge else ImageLoad.Failed(result.message)
                is FilesResult.Ok -> BoundedImages.decode(scratch)?.let { ImageLoad.Ok(it.asImageBitmap()) }
                    ?: ImageLoad.Failed(FileBrowserState.IMAGE_ERROR)
            }
        } catch (_: IOException) {
            ImageLoad.Failed(FileBrowserState.IMAGE_ERROR)
        } finally {
            // The decoded bitmap is all the preview keeps; the copy goes at once.
            scratch.delete()
        }
    }

    override suspend fun saveTo(files: WorkspaceFiles, entry: WorkspaceFileEntry, target: Uri): FilesResult<Long> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val result = try {
            // "wt": truncate whatever the provider already had at that document.
            resolver.openOutputStream(target, "wt")?.use { out -> files.download(entry.path, BrowserLimits.MAX_EXPORT_BYTES, out) }
                ?: FilesResult.Failed(SAVE_FAILED)
        } catch (_: IOException) {
            FilesResult.Failed(SAVE_FAILED)
        } catch (_: SecurityException) {
            FilesResult.Failed(SAVE_FAILED)
        }
        if (result is FilesResult.Failed) {
            // Never leave a partial copy behind in the user's chosen folder.
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
        }
        result
    }

    override suspend fun shareCopy(files: WorkspaceFiles, entry: WorkspaceFileEntry): FilesResult<ShareReady> = withContext(Dispatchers.IO) {
        if (entry.size > BrowserLimits.MAX_EXPORT_BYTES) return@withContext FilesResult.Failed(FilesCopy.DOWNLOAD_TOO_LARGE, tooLarge = true)
        val file = try {
            cache.newShareFile(entry.name)
        } catch (_: IOException) {
            return@withContext FilesResult.Failed(SHARE_FAILED)
        }
        val result = try {
            file.outputStream().use { out -> files.download(entry.path, BrowserLimits.MAX_EXPORT_BYTES, out) }
        } catch (_: IOException) {
            FilesResult.Failed(SHARE_FAILED)
        }
        when (result) {
            is FilesResult.Failed -> {
                file.parentFile?.deleteRecursively()
                result
            }
            is FilesResult.Ok -> FilesResult.Ok(ShareReady(FileProvider.getUriForFile(context, FileCache.authority(context), file), mimeFor(file), file.name))
        }
    }

    override fun sweep(keepRecentShares: Boolean) {
        cache.sweep(keepSharesYoungerThanMs = if (keepRecentShares) FileCache.SHARE_GRACE_MS else null)
    }

    companion object {
        const val SAVE_FAILED = "The file could not be saved."
        const val SHARE_FAILED = "The file could not be prepared for sharing."

        /** By extension only (never the server's Content-Type, never sniffed). */
        fun mimeFor(file: File): String =
            java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }
}
