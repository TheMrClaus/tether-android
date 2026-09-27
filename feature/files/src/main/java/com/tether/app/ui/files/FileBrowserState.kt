package com.tether.app.ui.files

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.tether.app.client.FilesResult
import com.tether.app.client.UploadSource
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFileListing
import com.tether.app.client.WorkspaceFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** workspace-file-browser.tsx NamePromptMode. */
enum class NamePromptMode { NewFolder, NewFile, Rename }

data class NamePrompt(val mode: NamePromptMode, val entry: WorkspaceFileEntry? = null, val value: String = "")

enum class DestinationMode { Move, Copy }

/** workspace-file-browser.tsx DestinationPickerState. */
data class DestinationPicker(
    val entry: WorkspaceFileEntry,
    val mode: DestinationMode,
    val path: String,
    val listing: WorkspaceFileListing? = null,
    val loading: Boolean = true,
    val error: String = "",
)

/** One document picked for upload: the name the server will get and its byte stream. */
class PickedUpload(val name: String, val source: UploadSource)

/** A downloaded copy ready to hand to the system share sheet. */
data class ShareReady(val uri: Uri, val mimeType: String, val name: String)

sealed interface ImageLoad {
    data class Ok(val image: ImageBitmap) : ImageLoad
    data object TooLarge : ImageLoad
    data class Failed(val message: String) : ImageLoad
}

/**
 * The platform side of the browser, behind a seam so the state machine runs on the JVM:
 * decoding a preview image, saving to a document the user picked, making a shareable copy, and
 * sweeping the scratch cache. [AndroidBrowserPlatform] is the real one.
 */
interface BrowserPlatform {
    suspend fun loadImage(files: WorkspaceFiles, entry: WorkspaceFileEntry): ImageLoad
    suspend fun saveTo(files: WorkspaceFiles, entry: WorkspaceFileEntry, target: Uri): FilesResult<Long>
    suspend fun shareCopy(files: WorkspaceFiles, entry: WorkspaceFileEntry): FilesResult<ShareReady>
    fun sweep(keepRecentShares: Boolean)
}

/**
 * The browser's state and every operation of components/workspace-file-browser.tsx, ported
 * one for one (loadDirectory, selectFile, open(initialPath), the name prompt, delete confirm,
 * the destination picker, uploads), plus the native "save to device" / "share" exports.
 *
 * Starting a listing cancels the previous listing and preview (the web's AbortControllers), and
 * a response that lands after its request was superseded never touches the state.
 */
@Stable
class FileBrowserState(
    private val files: WorkspaceFiles,
    private val platform: BrowserPlatform,
    private val scope: CoroutineScope,
) {
    var cwd by mutableStateOf("")
    var sessionName by mutableStateOf("")
    var isOpen by mutableStateOf(false)
        private set

    var listing by mutableStateOf<WorkspaceFileListing?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var selected by mutableStateOf<WorkspaceFileEntry?>(null)
        private set
    var text by mutableStateOf<String?>(null)
        private set
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var imageTooLarge by mutableStateOf(false)
        private set
    var previewLoading by mutableStateOf(false)
        private set
    var previewError by mutableStateOf("")
        private set
    var previewFullscreen by mutableStateOf(false)
        private set
    var mutationError by mutableStateOf("")
        private set

    /** Native: a neutral outcome line ("Saved …") in the slot the web uses for mutation errors. */
    var notice by mutableStateOf("")
        private set

    /** Native: the upload in flight (the web gives no feedback during a long PUT). */
    var uploading by mutableStateOf<String?>(null)
        private set

    var itemActions by mutableStateOf<WorkspaceFileEntry?>(null)
        private set
    var namePrompt by mutableStateOf<NamePrompt?>(null)
        private set
    var namePromptError by mutableStateOf("")
        private set
    var deleteTarget by mutableStateOf<WorkspaceFileEntry?>(null)
        private set
    var deleteError by mutableStateOf("")
        private set
    var destPicker by mutableStateOf<DestinationPicker?>(null)
        private set

    /** Native: a confirm is in flight, so its key cannot send the same destructive request twice. */
    var submitting by mutableStateOf(false)
        private set

    /** Set when a shareable copy is ready; the host starts the share sheet and calls [shareHandled]. */
    var pendingShare by mutableStateOf<ShareReady?>(null)
        private set

    private var requestedPath = ""
    private var pendingSelectPath: String? = null
    private var listingJob: Job? = null
    private var previewJob: Job? = null
    private var destJob: Job? = null

    /** `listing?.current || cwd`. */
    val currentDir: String get() = listing?.current ?: cwd

    val selectedKind: PreviewKind? get() = selected?.let(FileKinds::previewKind)

    /**
     * web `open(initialPath?)`: a directory loads directly; otherwise its parent loads and the file
     * is selected once that listing lands.
     */
    fun open(initialPath: String? = null) {
        if (cwd.isEmpty()) return
        if (!isOpen) platform.sweep(keepRecentShares = false)
        isOpen = true
        val target = initialPath?.trim()?.takeIf { it.isNotEmpty() } ?: cwd
        if (target == cwd) {
            loadDirectory(cwd)
            return
        }
        loadDirectory(target) { openedAsDirectory ->
            if (!openedAsDirectory) {
                val parent = target.replace(PARENT_TAIL, "").ifEmpty { "/" }
                pendingSelectPath = target
                loadDirectory(parent)
            }
        }
    }

    /** The dialog closed: abort requests, leave fullscreen, drop preview scratch files. */
    fun close() {
        listingJob?.cancel()
        previewJob?.cancel()
        destJob?.cancel()
        isOpen = false
        previewFullscreen = false
        itemActions = null
        namePrompt = null
        deleteTarget = null
        destPicker = null
        platform.sweep(keepRecentShares = true)
    }

    /** web `loadDirectory(path)`; [then] gets whether it opened (false when it failed or was superseded). */
    fun loadDirectory(path: String, then: (Boolean) -> Unit = {}) {
        requestedPath = path
        listingJob?.cancel()
        previewJob?.cancel()
        loading = true
        error = ""
        selected = null
        text = null
        image = null
        imageTooLarge = false
        previewError = ""
        previewLoading = false
        previewFullscreen = false
        mutationError = ""
        notice = ""
        var job: Job? = null
        job = scope.launch {
            val result = files.list(path)
            if (listingJob !== job) return@launch
            loading = false
            when (result) {
                is FilesResult.Ok -> {
                    listing = result.value
                    selectPending(result.value)
                    then(true)
                }
                is FilesResult.Failed -> {
                    error = result.message
                    then(false)
                }
            }
        }
        listingJob = job
    }

    private fun selectPending(listing: WorkspaceFileListing) {
        val target = pendingSelectPath ?: return
        pendingSelectPath = null
        val match = listing.entries.firstOrNull { it.path == target }
        if (match != null && !match.isDirectory) selectFile(match)
    }

    /** "Try again" after a failed listing. */
    fun retry() = loadDirectory(requestedPath.ifEmpty { cwd })

    fun openParent() {
        val parent = listing?.parent ?: return
        if (!loading) loadDirectory(parent)
    }

    /** A row's name: a folder opens, a file previews. */
    fun activate(entry: WorkspaceFileEntry) = if (entry.isDirectory) loadDirectory(entry.path) else selectFile(entry)

    /** web `selectFile(entry)`, plus the native image fetch (the web's <img> loads itself). */
    fun selectFile(entry: WorkspaceFileEntry) {
        previewJob?.cancel()
        selected = entry
        text = null
        image = null
        imageTooLarge = false
        previewError = ""
        val kind = FileKinds.previewKind(entry)
        val nativeImage = FileKinds.nativeImage(entry.name)
        val textFits = kind == PreviewKind.Text && entry.size <= WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES
        if (!textFits && !nativeImage) {
            previewLoading = false
            return
        }
        previewLoading = true
        var job: Job? = null
        job = scope.launch {
            if (textFits) {
                val result = files.readText(entry.path, entry.size)
                if (previewJob !== job) return@launch
                when (result) {
                    is FilesResult.Ok -> text = result.value
                    is FilesResult.Failed -> previewError = result.message
                }
            } else {
                val result = platform.loadImage(files, entry)
                if (previewJob !== job) return@launch
                when (result) {
                    is ImageLoad.Ok -> image = result.image
                    ImageLoad.TooLarge -> imageTooLarge = true
                    // The web's <img onError> copy.
                    is ImageLoad.Failed -> previewError = IMAGE_ERROR
                }
            }
            previewLoading = false
        }
        previewJob = job
    }

    fun clearSelection() {
        previewJob?.cancel()
        selected = null
        text = null
        image = null
        imageTooLarge = false
        previewError = ""
        previewLoading = false
        previewFullscreen = false
    }

    fun toggleFullscreen() {
        previewFullscreen = !previewFullscreen
    }

    // --- per-item actions sheet ---

    fun openItemActions(entry: WorkspaceFileEntry) {
        itemActions = entry
    }

    fun closeItemActions() {
        itemActions = null
    }

    /** An action from the sheet: the sheet closes first, then the action's own step opens. */
    fun pickAction(open: (WorkspaceFileEntry) -> Unit) {
        val entry = itemActions
        closeItemActions()
        if (entry != null) open(entry)
    }

    // --- New folder / New file / Rename ---

    fun openNamePrompt(mode: NamePromptMode, entry: WorkspaceFileEntry? = null) {
        namePromptError = ""
        namePrompt = NamePrompt(mode, entry, if (mode == NamePromptMode.Rename && entry != null) entry.name else "")
    }

    fun updateNamePrompt(value: String) {
        // The web's <input maxLength={200}>.
        namePrompt = namePrompt?.copy(value = value.take(NAME_MAX_LENGTH))
    }

    fun closeNamePrompt() {
        namePrompt = null
        namePromptError = ""
    }

    fun submitNamePrompt() {
        val prompt = namePrompt ?: return
        val value = prompt.value.trim()
        if (value.isEmpty() || submitting) return
        namePromptError = ""
        submit {
            val dir = currentDir
            val result = when (prompt.mode) {
                NamePromptMode.NewFolder -> files.mkdir(dir, value)
                NamePromptMode.NewFile -> files.touch(dir, value)
                NamePromptMode.Rename -> files.rename(prompt.entry?.path ?: return@submit, value)
            }
            when (result) {
                is FilesResult.Ok -> {
                    closeNamePrompt()
                    loadDirectory(dir)
                }
                is FilesResult.Failed -> namePromptError = result.message
            }
        }
    }

    // --- Delete ---

    fun openDeleteConfirm(entry: WorkspaceFileEntry) {
        deleteError = ""
        deleteTarget = entry
    }

    fun closeDeleteConfirm() {
        deleteTarget = null
        deleteError = ""
    }

    fun confirmDelete() {
        val target = deleteTarget ?: return
        if (submitting) return
        deleteError = ""
        submit {
            val dir = currentDir
            when (val result = files.delete(target.path)) {
                is FilesResult.Ok -> {
                    closeDeleteConfirm()
                    loadDirectory(dir)
                }
                is FilesResult.Failed -> deleteError = result.message
            }
        }
    }

    // --- Move to… / Copy to… ---

    fun openDestPicker(entry: WorkspaceFileEntry, mode: DestinationMode) {
        val start = currentDir
        destPicker = DestinationPicker(entry, mode, start)
        loadDestDirectory(start)
    }

    fun loadDestDirectory(path: String) {
        destPicker = destPicker?.copy(loading = true, error = "") ?: return
        destJob?.cancel()
        var job: Job? = null
        job = scope.launch {
            val result = files.list(path)
            if (destJob !== job) return@launch
            destPicker = destPicker?.let { prev ->
                when (result) {
                    is FilesResult.Ok -> prev.copy(path = path, listing = result.value, loading = false)
                    is FilesResult.Failed -> prev.copy(loading = false, error = result.message)
                }
            }
        }
        destJob = job
    }

    fun closeDestPicker() {
        destJob?.cancel()
        destPicker = null
    }

    fun confirmDestPicker() {
        val picker = destPicker ?: return
        if (submitting) return
        submit {
            val dir = currentDir
            val result = when (picker.mode) {
                DestinationMode.Move -> files.move(picker.entry.path, picker.path)
                DestinationMode.Copy -> files.copy(picker.entry.path, picker.path)
            }
            when (result) {
                is FilesResult.Ok -> {
                    closeDestPicker()
                    loadDirectory(dir)
                }
                is FilesResult.Failed -> destPicker = destPicker?.copy(error = result.message)
            }
        }
    }

    // --- Upload (web handleUploadFiles) ---

    /** One PUT per document, in order; the last failure is shown; then the folder re-lists. */
    fun upload(picked: List<PickedUpload>) {
        if (picked.isEmpty()) return
        mutationError = ""
        notice = ""
        scope.launch {
            val dir = currentDir
            for (item in picked) {
                uploading = item.name
                val result = files.upload(dir, item.name, item.source)
                if (result is FilesResult.Failed) mutationError = result.message
            }
            uploading = null
            val lastError = mutationError
            loadDirectory(dir)
            // loadDirectory clears the banner (web: setMutationError("") in loadDirectory), so on
            // the web the upload error only flashes; keep it, it is the only word the user gets.
            mutationError = lastError
        }
    }

    // --- Native exports ---

    fun saveTo(entry: WorkspaceFileEntry, target: Uri) {
        mutationError = ""
        notice = ""
        scope.launch {
            when (val result = platform.saveTo(files, entry, target)) {
                is FilesResult.Ok -> notice = "Saved “${entry.name}”."
                is FilesResult.Failed -> mutationError = result.message
            }
        }
    }

    fun share(entry: WorkspaceFileEntry) {
        mutationError = ""
        notice = ""
        scope.launch {
            when (val result = platform.shareCopy(files, entry)) {
                is FilesResult.Ok -> pendingShare = result.value
                is FilesResult.Failed -> mutationError = result.message
            }
        }
    }

    fun shareHandled() {
        pendingShare = null
    }

    private fun submit(block: suspend () -> Unit) {
        submitting = true
        scope.launch {
            try {
                block()
            } finally {
                submitting = false
            }
        }
    }

    companion object {
        const val IMAGE_ERROR = "This image could not be displayed."
        const val NAME_MAX_LENGTH = 200
        private val PARENT_TAIL = Regex("/+[^/]+/?$")
    }
}
