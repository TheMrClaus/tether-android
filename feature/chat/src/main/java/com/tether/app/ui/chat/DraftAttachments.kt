package com.tether.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.tether.app.client.DraftComposerModel
import com.tether.app.protocol.helpers.AttachmentDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * ta-abm (T8.1 slice 2): the new-session composer's attachments (draft-composer.tsx addFiles: the
 * same intake, caps and flashes as the in-session composer's, so a first turn's attachment reaches
 * the engine the same way whichever composer staged it). They are the draft's own
 * ([DraftComposerModel]'s staged set: memory only, kept across a close and reopen of the sheet and a
 * rotation, dropped with the draft's server), never a session's.
 */

/**
 * Stages picks into [model]'s draft. One batch at a time; a batch whose draft set was dropped
 * while it was being read (sent, given up on, the server switched) is discarded, as is one that
 * finishes while a create holds the draft.
 */
class DraftAttachmentStager(
    private val model: DraftComposerModel,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: ReadLimits = ReadLimits.DEFAULT,
) {
    private val mutex = Mutex()

    /** Reads [sources] and stages what passes the caps; returns the flashes (the last is shown). */
    suspend fun stage(sources: List<AttachmentSource>): List<String> = mutex.withLock {
        if (sources.isEmpty() || model.state.value.creating) return@withLock emptyList()
        val generation = model.attachmentGeneration
        val existing = model.state.value.staged
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val result = withContext(io) {
            AttachmentIntake.intake(sources, existing, model::newAttachmentId, active = { job?.isActive != false }, limits = limits)
        }
        model.addAttachments(result.added, generation)
        result.flashes
    }
}

/** What the draft sheet's paperclip opens: the T7.4 sheet's rows (and ta-coik.3's camera), wired to the pickers. */
class DraftAttachmentPickers internal constructor(
    val pickImages: () -> Unit,
    val takePhoto: () -> Unit,
    val pasteImage: () -> Unit,
    val pickFiles: () -> Unit,
)

/**
 * The T7.4 pickers for the draft (the Photo Picker, the clipboard's pictures, the document picker),
 * exactly as the in-session composer registers them: only another app's content:// provider is read,
 * and what comes back goes to [onSources]; [onFlash] gets the words for a clipboard that has no
 * picture or cannot be read.
 */
@Composable
fun rememberDraftAttachmentPickers(onSources: (List<AttachmentSource>) -> Unit, onFlash: (String) -> Unit): DraftAttachmentPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestSources by rememberUpdatedState(onSources)
    val latestFlash by rememberUpdatedState(onFlash)
    val uriPolicy = remember(context) { AttachmentUriPolicy.of(context) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentDraft.MAX_ATTACHMENTS)) { uris ->
        latestSources(uris.map { ContentUriSource(context.contentResolver, it, uriPolicy) })
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        latestSources(uris.orEmpty().map { ContentUriSource(context.contentResolver, it, uriPolicy) })
    }
    val takePhoto = rememberCameraCapture(onSources = { latestSources(it) }, onFlash = { latestFlash(it) })
    return remember(context, imagePicker, filePicker, takePhoto) {
        DraftAttachmentPickers(
            pickImages = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            takePhoto = takePhoto,
            pasteImage = paste@{
                val clip = try {
                    context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                } catch (_: RuntimeException) {
                    latestFlash(AttachmentCopy.CLIPBOARD_UNREADABLE)
                    return@paste
                }
                scope.launch {
                    val sources = try {
                        withContext(Dispatchers.IO) { ClipboardImages.sources(clip, context.contentResolver, uriPolicy) }
                    } catch (_: RuntimeException) {
                        latestFlash(AttachmentCopy.CLIPBOARD_UNREADABLE)
                        return@launch
                    }
                    if (sources.isEmpty()) latestFlash(AttachmentCopy.NO_CLIPBOARD_IMAGE) else latestSources(sources)
                }
            },
            pickFiles = { filePicker.launch(arrayOf("*/*")) },
        )
    }
}
