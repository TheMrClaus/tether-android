package com.tether.app.client

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * T11.1: the workspace file browser's HTTP routes (`/api/files`, `/api/files/list|mkdir|touch|rename|move|copy|upload`, server.mjs 7152-7390), the
 * native twin of the `fetch` calls in components/workspace-file-browser.tsx.
 *
 * Trust boundary: every call goes ONLY to the paired server, with the one credential in force
 * (see [HttpWorkspaceFiles]); a redirect is never followed. Everything the server sends back
 * (names, paths, file bytes, error copy) is untrusted content: it is shown as text or decoded
 * as a bitmap, never executed, and never used to build a local filesystem path.
 *
 * Paths are the server's own absolute paths (the listing's `path` / `current` / `parent`), sent
 * back verbatim; the server resolves every one through its workspace containment authority.
 */
interface WorkspaceFiles {
    /** `GET /api/files/list?path=`: the folder's entries (the server orders them: folders first, then by name). */
    suspend fun list(path: String): FilesResult<WorkspaceFileListing>

    /** `POST /api/files/mkdir {path, name}`: a folder [name] inside [parent]. */
    suspend fun mkdir(parent: String, name: String): FilesResult<WorkspaceMutation>

    /** `POST /api/files/touch {path, name}`: an empty file [name] inside [parent]. */
    suspend fun touch(parent: String, name: String): FilesResult<WorkspaceMutation>

    /** `POST /api/files/rename {path, name}`. */
    suspend fun rename(path: String, name: String): FilesResult<WorkspaceMutation>

    /** `POST /api/files/move {path, destination}`: [destination] is a folder. */
    suspend fun move(path: String, destination: String): FilesResult<WorkspaceMutation>

    /** `POST /api/files/copy {path, destination}`: [destination] is a folder. */
    suspend fun copy(path: String, destination: String): FilesResult<WorkspaceMutation>

    /** `DELETE /api/files?path=`: irreversible (a folder goes with everything inside it). */
    suspend fun delete(path: String): FilesResult<WorkspaceMutation>

    /**
     * `PUT /api/files/upload?path=&name=` with the raw bytes as the body, streamed from
     * [source] (never buffered whole) and refused locally past [MAX_UPLOAD_BYTES], the
     * server's own cap. [overwrite] adds `overwrite=1`; the web never sets it.
     */
    suspend fun upload(parent: String, name: String, source: UploadSource, overwrite: Boolean = false): FilesResult<WorkspaceMutation>

    /** `HEAD /api/files?path=`: the file's current length and type, no body. */
    suspend fun head(path: String): FilesResult<FileHead>

    /**
     * The text preview (workspace-file-browser.tsx selectFile): `GET /api/files?path=` with
     * `Range: bytes=0-(MAX_TEXT_PREVIEW_BYTES-1)` when [listedSize] > 0, and at most
     * [MAX_TEXT_PREVIEW_BYTES] read even if the server ignores the range, decoded as UTF-8.
     */
    suspend fun readText(path: String, listedSize: Long): FilesResult<String>

    /**
     * `GET /api/files?path=` streamed into [sink] and cut off past [maxBytes] (refused before
     * any byte when the declared length is already over). Returns the byte count.
     */
    suspend fun download(path: String, maxBytes: Long, sink: OutputStream): FilesResult<Long>

    /** No server / not signed in: every call fails without touching the network. */
    object Unavailable : WorkspaceFiles {
        private fun <T> no(fallback: String): FilesResult<T> = FilesResult.Failed(fallback)
        override suspend fun list(path: String): FilesResult<WorkspaceFileListing> = no(FilesCopy.LIST_FALLBACK)
        override suspend fun mkdir(parent: String, name: String): FilesResult<WorkspaceMutation> = no(FilesCopy.MKDIR_FALLBACK)
        override suspend fun touch(parent: String, name: String): FilesResult<WorkspaceMutation> = no(FilesCopy.TOUCH_FALLBACK)
        override suspend fun rename(path: String, name: String): FilesResult<WorkspaceMutation> = no(FilesCopy.RENAME_FALLBACK)
        override suspend fun move(path: String, destination: String): FilesResult<WorkspaceMutation> = no(FilesCopy.ACTION_FALLBACK)
        override suspend fun copy(path: String, destination: String): FilesResult<WorkspaceMutation> = no(FilesCopy.ACTION_FALLBACK)
        override suspend fun delete(path: String): FilesResult<WorkspaceMutation> = no(FilesCopy.DELETE_FALLBACK)
        override suspend fun upload(parent: String, name: String, source: UploadSource, overwrite: Boolean): FilesResult<WorkspaceMutation> =
            no(FilesCopy.uploadFallback(name))
        override suspend fun head(path: String): FilesResult<FileHead> = no(FilesCopy.FILE_FALLBACK)
        override suspend fun readText(path: String, listedSize: Long): FilesResult<String> = no(FilesCopy.TEXT_FALLBACK)
        override suspend fun download(path: String, maxBytes: Long, sink: OutputStream): FilesResult<Long> = no(FilesCopy.FILE_FALLBACK)
    }

    companion object {
        /** workspace-file-browser.tsx MAX_TEXT_PREVIEW_BYTES. */
        const val MAX_TEXT_PREVIEW_BYTES: Long = 1024L * 1024L

        /** server.mjs MAX_UPLOAD_BYTES (the server answers 413 past it). */
        const val MAX_UPLOAD_BYTES: Long = 512L * 1024L * 1024L

        /**
         * A listing larger than this is refused rather than parsed (the web has no cap; a folder
         * whose JSON listing passes 8 MiB is ~50k entries, far past anything the list can show).
         */
        const val MAX_LISTING_BYTES: Long = 8L * 1024L * 1024L
    }
}

/** lib/protocol.ts WorkspaceFileEntry. */
data class WorkspaceFileEntry(
    val name: String,
    val path: String,
    val size: Long,
    /** Milliseconds since the epoch (the server's `mtimeMs`, which can carry a fraction). */
    val mtime: Double,
    val isDirectory: Boolean,
)

data class WorkspaceBreadcrumb(val name: String, val path: String)

/** lib/protocol.ts WorkspaceFileListing. */
data class WorkspaceFileListing(
    val current: String,
    val parent: String?,
    val breadcrumbs: List<WorkspaceBreadcrumb>,
    val entries: List<WorkspaceFileEntry>,
)

/** lib/protocol.ts WorkspaceMutationResult: `parent` is the folder to re-list. */
data class WorkspaceMutation(val parent: String?, val path: String? = null, val size: Long? = null)

/** What `HEAD /api/files` reports. [length] is null when the server sent no usable Content-Length. */
data class FileHead(val length: Long?, val contentType: String?)

sealed interface FilesResult<out T> {
    data class Ok<T>(val value: T) : FilesResult<T>

    /**
     * [message] is the server's `{error}` copy when it sent one (web readJsonOrThrow), else the
     * operation's fallback. [status] is the HTTP status, null for a local refusal or a transport
     * failure. [tooLarge] marks a size cap (local or the server's 413).
     */
    data class Failed(val message: String, val status: Int? = null, val tooLarge: Boolean = false) : FilesResult<Nothing>
}

/** An upload body: opened once, read once (the request body is one-shot, never replayed). */
interface UploadSource {
    /** Bytes, when known up front (sent as Content-Length); null streams it chunked. */
    val length: Long?

    @Throws(IOException::class)
    fun open(): InputStream
}

/** The web's user-facing copy for every /api/files outcome it names. */
object FilesCopy {
    const val LIST_FALLBACK = "This folder could not be opened."
    const val TEXT_FALLBACK = "This text file could not be opened."
    const val MKDIR_FALLBACK = "That folder could not be created."
    const val TOUCH_FALLBACK = "That file could not be created."
    const val RENAME_FALLBACK = "That item could not be renamed."
    const val DELETE_FALLBACK = "That item could not be deleted."
    const val ACTION_FALLBACK = "That action could not be completed."
    const val UPLOAD_TOO_LARGE = "Uploads are limited to 512 MB."
    fun uploadFallback(name: String) = "\"$name\" could not be uploaded."

    /** Native-only copy (no web counterpart). */
    const val FILE_FALLBACK = "This file could not be opened."
    const val UNREACHABLE = "The server could not be reached."
    const val NOT_SIGNED_IN = "Sign in to browse workspace files."
    const val LOCAL_NETWORK_BLOCKED = "Local network access is blocked."
    const val DOWNLOAD_TOO_LARGE = "This file is too large to open here."
}
