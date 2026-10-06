package com.tether.app.ui.files

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.tether.app.client.FilesCopy
import com.tether.app.client.FilesResult
import com.tether.app.client.LabelText
import com.tether.app.client.TextCut
import com.tether.app.client.UploadSource
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFileListing
import com.tether.app.client.WorkspaceFiles
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** workspace-file-browser.tsx NamePromptMode. */
enum class NamePromptMode { NewFolder, NewFile, Rename }

/**
 * ta-28i r2: [hiddenRemoved] = the Rename field was pre-filled with the name WITHOUT its hidden
 * characters ([LabelText.withoutHidden]): the field then says so, and renaming saves it without them.
 */
data class NamePrompt(val mode: NamePromptMode, val entry: WorkspaceFileEntry? = null, val value: String = "", val hiddenRemoved: Boolean = false)

/**
 * A neutral outcome line. ta-28i r2: the file [name] in it is drawn by the one-line code rule
 * between our own words [before] and [after]; [text] is the plain sentence.
 */
data class BrowserNotice(val before: String, val name: String = "", val after: String = "") {
    val text: String get() = before + name + after
}

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

/**
 * One document picked for upload: the provider's display name (checked by [UploadNames] before
 * anything is sent) and its byte stream. [onFinished] runs once the upload is over for it, whatever
 * way it ended (sent, failed, refused, cancelled): a scratch photo deletes itself there.
 */
class PickedUpload(val displayName: String?, val source: UploadSource, val onFinished: () -> Unit = {})

/** A downloaded copy ready to hand to the system share sheet; [id] names it to the platform. */
data class ShareReady(val uri: Uri, val mimeType: String, val name: String, val id: String)

/** [BrowserPlatform.sweep]: only what is past its window, or everything (sign-out). */
enum class SweepMode { Expired, All }

sealed interface ImageLoad {
    data class Ok(val image: ImageBitmap) : ImageLoad
    data class Failed(val message: String) : ImageLoad
}

/** What loading an SVG for the preview came to. There is no size limit: the web's `<img>` has none. */
sealed interface SvgLoad {
    data class Ok(val svg: ParsedSvg) : SvgLoad
    data class Failed(val message: String) : SvgLoad
}

/**
 * The platform side of the browser, behind a seam so the state machine runs on the JVM:
 * decoding a preview image, saving to a document the user picked, making a shareable copy, and
 * sweeping the scratch cache. [AndroidBrowserPlatform] is the real one.
 */
interface BrowserPlatform {
    suspend fun loadImage(files: WorkspaceFiles, entry: WorkspaceFileEntry): ImageLoad

    /** An SVG file, parsed script-free with nothing fetched ([SvgImages]); drawn later at the size it is shown at. */
    suspend fun loadSvg(files: WorkspaceFiles, entry: WorkspaceFileEntry): SvgLoad

    /**
     * A video, opened for playback at once ([VideoPlayer.phase] says how far it got): it streams by
     * Range reads, plays nothing until asked, and has no size cap. [onFailed] is called (on the
     * main thread) when it cannot be played.
     */
    fun openVideo(files: WorkspaceFiles, entry: WorkspaceFileEntry, onFailed: () -> Unit): VideoPlayer
    suspend fun saveTo(files: WorkspaceFiles, entry: WorkspaceFileEntry, target: Uri): FilesResult<Long>
    suspend fun shareCopy(files: WorkspaceFiles, entry: WorkspaceFileEntry): FilesResult<ShareReady>

    /** The share sheet took [share]: it now lives out its window. */
    fun claimShare(share: ShareReady)

    /** [share] will never reach a share sheet: delete it now. */
    fun discardShare(share: ShareReady)

    /** Delete every copy made but never claimed (a result lost to a cancellation included). */
    fun discardUnclaimedShares()

    /** Sweep the scratch cache, off the main thread. */
    fun sweep(mode: SweepMode)
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
    parent: CoroutineScope,
    /** Where an unexpected failure is reported: its exception CLASS name only, never a message (it may carry a path or content). */
    private val log: (String) -> Unit = { line -> runCatching { Log.w(LOG_TAG, line) } },
) {
    /**
     * The browser's jobs: a child of [parent] that one failure cannot take down, with a handler so
     * nothing unexpected (a provider's SecurityException, a decoder's OOM…) ever reaches the thread's
     * uncaught-exception handler and kills the app. It lands as the web's generic copy instead.
     */
    private val scope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineExceptionHandler { _, error ->
            log("file browser job failed: ${error.javaClass.name}")
            onUnexpectedFailure()
        },
    )

    private fun onUnexpectedFailure() {
        loading = false
        previewLoading = false
        uploading = null
        submitting = false
        mutationError = FilesCopy.ACTION_FALLBACK
    }

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

    /** The selected SVG, parsed; the preview draws it at the size it is shown at. */
    var svg by mutableStateOf<ParsedSvg?>(null)
        private set

    /** The selected video's player: owned here, released on every way out of the selection. */
    var video by mutableStateOf<VideoPlayer?>(null)
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
    var notice by mutableStateOf<BrowserNotice?>(null)
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
        // By age only: a copy another app may still be reading stays for its window.
        if (!isOpen) platform.sweep(SweepMode.Expired)
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

    /**
     * The dialog closed: abort requests, leave fullscreen, let go of the preview (a bitmap can be
     * tens of MB), drop any shared copy that never reached a share sheet, sweep what has expired.
     */
    fun close() {
        listingJob?.cancel()
        previewJob?.cancel()
        destJob?.cancel()
        isOpen = false
        clearSelection()
        itemActions = null
        namePrompt = null
        deleteTarget = null
        destPicker = null
        pendingShare?.let(platform::discardShare)
        pendingShare = null
        platform.discardUnclaimedShares()
        platform.sweep(SweepMode.Expired)
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
        svg = null
        releaseVideo()
        previewError = ""
        previewLoading = false
        previewFullscreen = false
        mutationError = ""
        notice = null
        launchLatest({ listingJob = it }) { self ->
            val result = files.list(path)
            if (listingJob !== self) return@launchLatest
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
    }

    private fun selectPending(listing: WorkspaceFileListing) {
        val target = pendingSelectPath ?: return
        pendingSelectPath = null
        val match = listing.entries.firstOrNull { it.path == target }
        if (match != null && !match.isDirectory) selectFile(match)
    }

    /**
     * The session this browser belonged to is over (sign-out, another server): stop every job,
     * in-flight saves and uploads included, and drop the state. The owner makes a fresh browser.
     */
    fun dispose() {
        close()
        scope.cancel()
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
        releaseVideo()
        selected = entry
        text = null
        image = null
        svg = null
        previewError = ""
        val kind = FileKinds.previewKind(entry)
        if (kind == PreviewKind.Video) {
            // The web's <video preload="metadata">: opened, never started. No size check (it has none).
            previewLoading = false
            val token = Any()
            videoToken = token
            video = platform.openVideo(files, entry) {
                // Only the video still on screen speaks (a late failure of a replaced one is nothing).
                if (videoToken === token) previewError = VIDEO_ERROR
            }
            return
        }
        val nativeImage = FileKinds.nativeImage(entry.name)
        val textFits = kind == PreviewKind.Text && entry.size <= WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES
        if (!textFits && !nativeImage) {
            previewLoading = false
            return
        }
        previewLoading = true
        launchLatest({ previewJob = it }) { self ->
            if (textFits) {
                val result = files.readText(entry.path, entry.size)
                if (previewJob !== self) return@launchLatest
                when (result) {
                    is FilesResult.Ok -> text = result.value
                    is FilesResult.Failed -> previewError = result.message
                }
            } else if (FileKinds.isSvg(entry.name)) {
                val result = platform.loadSvg(files, entry)
                if (previewJob !== self) return@launchLatest
                when (result) {
                    is SvgLoad.Ok -> svg = result.svg
                    // The web's <img onError> copy.
                    is SvgLoad.Failed -> previewError = IMAGE_ERROR
                }
            } else {
                val result = platform.loadImage(files, entry)
                if (previewJob !== self) return@launchLatest
                when (result) {
                    is ImageLoad.Ok -> image = result.image
                    // The web's <img onError> copy.
                    is ImageLoad.Failed -> previewError = IMAGE_ERROR
                }
            }
            previewLoading = false
        }
    }

    /** The SVG [shown] could not be drawn (memory, a hostile file): the web's <img onError> copy, if it is still the one on screen. */
    fun svgDrawFailed(shown: ParsedSvg) {
        if (svg === shown) previewError = IMAGE_ERROR
    }

    private var videoToken: Any? = null

    /** Stops and frees the selected video's player (and its connection), whatever way the selection ends. */
    private fun releaseVideo() {
        videoToken = null
        val player = video ?: return
        video = null
        player.release()
    }

    fun clearSelection() {
        previewJob?.cancel()
        releaseVideo()
        selected = null
        text = null
        image = null
        svg = null
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
        // r2: a Rename field shows what it holds: the name without its hidden characters (and says so).
        val shown = if (mode == NamePromptMode.Rename && entry != null) LabelText.withoutHidden(entry.name) else ""
        namePrompt = NamePrompt(mode, entry, shown, hiddenRemoved = mode == NamePromptMode.Rename && entry != null && shown != entry.name)
    }

    fun updateNamePrompt(value: String) {
        // The web's <input maxLength={200}>.
        // r2: cut at a character-cluster boundary, never half a surrogate pair or an accent.
        namePrompt = namePrompt?.copy(value = TextCut.cut(value, NAME_MAX_LENGTH))
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
        launchLatest({ destJob = it }) { self ->
            val result = files.list(path)
            if (destJob !== self) return@launchLatest
            destPicker = destPicker?.let { prev ->
                when (result) {
                    is FilesResult.Ok -> prev.copy(path = path, listing = result.value, loading = false)
                    is FilesResult.Failed -> prev.copy(loading = false, error = result.message)
                }
            }
        }
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

    /**
     * One PUT per document, in order; the last failure is shown; then the folder re-lists. A name
     * a browser's File.name could never be (a control character) is refused without a request.
     */
    fun upload(picked: List<PickedUpload>, destination: String? = null) {
        if (picked.isEmpty()) return
        mutationError = ""
        notice = null
        val job = scope.launch {
            // The folder the Upload key was pressed in (kept across a configuration change by the host).
            val dir = destination ?: currentDir
            for (item in picked) {
                val name = UploadNames.fromDisplayName(item.displayName)
                if (name == null) {
                    mutationError = UploadNames.INVALID
                    continue
                }
                uploading = name
                val result = files.upload(dir, name, item.source)
                if (result is FilesResult.Failed) mutationError = result.message
            }
            uploading = null
            val lastError = mutationError
            loadDirectory(dir)
            // loadDirectory clears the banner (web: setMutationError("") in loadDirectory), so on
            // the web the upload error only flashes; keep it, it is the only word the user gets.
            mutationError = lastError
        }
        // Sent, failed, refused or cancelled (sign-out, a cleared owner, a scope already gone):
        // scratch copies go, even when the body never ran.
        job.invokeOnCompletion { picked.forEach { item -> runCatching { item.onFinished() } } }
    }

    /** A word for the mutation banner from the host (the camera could not start). */
    fun reportError(message: String) {
        mutationError = message
        notice = null
    }

    // --- Native exports ---

    fun saveTo(entry: WorkspaceFileEntry, target: Uri) {
        mutationError = ""
        notice = null
        scope.launch {
            when (val result = platform.saveTo(files, entry, target)) {
                is FilesResult.Ok -> notice = BrowserNotice("Saved “", entry.name, "”.")
                is FilesResult.Failed -> mutationError = result.message
            }
        }
    }

    fun share(entry: WorkspaceFileEntry) {
        mutationError = ""
        notice = null
        scope.launch {
            when (val result = platform.shareCopy(files, entry)) {
                // Closed meanwhile: no sheet will open, so the copy goes now.
                is FilesResult.Ok -> if (isOpen) pendingShare = result.value else platform.discardShare(result.value)
                is FilesResult.Failed -> mutationError = result.message
            }
        }
    }

    /** The host tried the share sheet: [started] claims the copy for its window, else it goes. */
    fun shareHandled(started: Boolean) {
        val share = pendingShare ?: return
        pendingShare = null
        if (started) platform.claimShare(share) else platform.discardShare(share)
    }

    /**
     * A job that a newer one of its kind supersedes: [block] gets the job itself, to compare with
     * the one now current. It is created unstarted and started only once [store] has kept it,
     * because the scope is Dispatchers.Main.immediate: from the main thread the block runs at once,
     * and when a result is already in hand (a fast server, a preempted main thread) nothing suspends
     * it, so it can finish before launch returns — a job stored only after launch would take itself
     * for superseded and drop its own result (ta-g04).
     */
    private fun launchLatest(store: (Job) -> Unit, block: suspend CoroutineScope.(self: Job) -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block(coroutineContext.job) }
        store(job)
        job.start()
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

        /** workspace-file-browser.tsx's `<video onError>` copy. */
        const val VIDEO_ERROR = "This video could not be played."
        const val LOG_TAG = "TetherFiles"
        const val NAME_MAX_LENGTH = 200
        private val PARENT_TAIL = Regex("/+[^/]+/?$")
    }
}
