package com.tether.app.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tether.app.client.WorkspaceFileEntry
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.app.client.TetherClient
import com.tether.app.client.WorkspaceFiles
import kotlinx.coroutines.flow.Flow
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens

/**
 * The browser for the signed-in shell, on the client's `/api/files` routes. It lives in a
 * [FileBrowserViewModel], so a configuration change (rotation) keeps it — open, in its folder,
 * with any save or upload still running — and its teardown follows the client's signed-in
 * identity, not composition: sign-out or another server empties the scratch cache, shared copies
 * included, so nothing a session downloaded outlives it on the device.
 */
@Composable
fun rememberFileBrowserState(client: TetherClient): FileBrowserState =
    rememberFileBrowserState(client.files, remember(client) { FileBrowserViewModel.identityOf(client) })

/** [rememberFileBrowserState] on [files], torn down when [identity] changes from its first signed-in value. */
@Composable
fun rememberFileBrowserState(files: WorkspaceFiles, identity: Flow<String?>): FileBrowserState {
    val context = LocalContext.current.applicationContext
    val model = viewModel(key = VIEW_MODEL_KEY) { FileBrowserViewModel(files, AndroidBrowserPlatform(context), identity) }
    return model.state
}

private const val VIEW_MODEL_KEY = "workspace-file-browser"

/**
 * components/workspace-file-browser.tsx as a native modal: shown while [FileBrowserState.isOpen]
 * (open it with [FileBrowserState.open]). Uploads come from the system document picker or the
 * system camera (the Upload key's chooser) and stream to the server; Save to device goes through the system "create document" picker; Share
 * hands one downloaded copy to the system share sheet through a read-only URI grant.
 */
@Composable
fun WorkspaceFileBrowser(state: FileBrowserState) {
    val context = LocalContext.current
    val resolver = context.contentResolver

    // Registered whether or not the browser is showing: a picker result can arrive in a new
    // activity (rotation while the system picker is up), where the browser starts closed.
    // The folder the Upload key was pressed in, kept like the pending save target.
    var uploadInto by rememberSaveable { mutableStateOf<String?>(null) }
    val pickUploads = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val destination = uploadInto
        uploadInto = null
        state.upload(
            uris.map { uri ->
                val (name, size) = ContentUploadSource.describe(resolver, uri)
                // UploadNames checks the display name in the state before anything is sent.
                PickedUpload(name, ContentUploadSource(resolver, uri, size))
            },
            destination,
        )
    }
    // ta-coik.67: the system camera, beside the picker (the web's file input offers both on Android).
    val takePhoto = rememberUploadCapture(
        onPhoto = { photo -> state.upload(listOf(photo), uploadInto.also { uploadInto = null }) },
        onUnavailable = { words ->
            uploadInto = null
            state.reportError(words)
        },
    )
    // The Upload key's chooser is open (kept across a rotation, like the picker's folder).
    var choosingUpload by rememberSaveable { mutableStateOf(false) }
    var saving by rememberPendingSave()
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = saving
        saving = null
        if (uri != null && entry != null) state.saveTo(entry, uri)
    }

    if (!state.isOpen) return

    state.pendingShare?.let { share ->
        LaunchedEffect(share) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = share.mimeType
                putExtra(Intent.EXTRA_STREAM, share.uri)
                clipData = ClipData.newRawUri(share.name, share.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val started = try {
                context.startActivity(Intent.createChooser(send, null))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                // A chooser the platform refuses to start for us: the copy goes, the app stays.
                false
            }
            // Started: the copy lives out its window for the receiving app. Not: it goes now.
            state.shareHandled(started)
        }
    }

    Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NoWindowDim()
        val progress = rememberDialogIn()
        FileBrowserFrame(
            state = state,
            onClose = state::close,
            onUpload = {
                uploadInto = state.currentDir
                choosingUpload = true
            },
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            },
        )

        if (choosingUpload) {
            SubDialog({ choosingUpload = false }) {
                UploadChooserContent(
                    onChooseFiles = {
                        choosingUpload = false
                        pickUploads.launch(arrayOf("*/*"))
                    },
                    onTakePhoto = {
                        choosingUpload = false
                        takePhoto()
                    },
                    onCancel = { choosingUpload = false },
                )
            }
        }
        state.itemActions?.let { entry ->
            SubDialog(state::closeItemActions) {
                ItemActionsContent(
                    entry = entry,
                    onRename = { state.pickAction { state.openNamePrompt(NamePromptMode.Rename, it) } },
                    onCopy = { state.pickAction { state.openDestPicker(it, DestinationMode.Copy) } },
                    onMove = { state.pickAction { state.openDestPicker(it, DestinationMode.Move) } },
                    onSave = if (entry.isDirectory) null else {
                        {
                            state.pickAction {
                                saving = it
                                createDocument.launch(LocalNames.safe(it.name))
                            }
                        }
                    },
                    onShare = if (entry.isDirectory) null else { { state.pickAction(state::share) } },
                    onDelete = { state.pickAction(state::openDeleteConfirm) },
                    onCancel = state::closeItemActions,
                )
            }
        }
        state.namePrompt?.let { prompt ->
            SubDialog(state::closeNamePrompt) {
                NamePromptContent(
                    prompt = prompt,
                    error = state.namePromptError,
                    submitting = state.submitting,
                    onValueChange = state::updateNamePrompt,
                    onSubmit = state::submitNamePrompt,
                    onCancel = state::closeNamePrompt,
                )
            }
        }
        state.deleteTarget?.let { target ->
            SubDialog(state::closeDeleteConfirm) {
                DeleteConfirmContent(
                    entry = target,
                    error = state.deleteError,
                    submitting = state.submitting,
                    onConfirm = state::confirmDelete,
                    onCancel = state::closeDeleteConfirm,
                )
            }
        }
        state.destPicker?.let { picker ->
            Dialog(onDismissRequest = state::closeDestPicker, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                NoWindowDim()
                DestinationPickerFrame(
                    picker = picker,
                    submitting = state.submitting,
                    onBrowse = state::loadDestDirectory,
                    onConfirm = state::confirmDestPicker,
                    onClose = state::closeDestPicker,
                )
            }
        }
    }
}

/** The entry a "create document" picker is choosing a target for, kept across a configuration change. */
@Composable
fun rememberPendingSave(): MutableState<WorkspaceFileEntry?> = rememberSaveable(stateSaver = PendingSaveSaver) { mutableStateOf(null) }

private val PendingSaveSaver: Saver<WorkspaceFileEntry?, Any> = listSaver(
    save = { entry -> if (entry == null) emptyList() else listOf(entry.name, entry.path, entry.size, entry.mtime, entry.isDirectory) },
    restore = { values ->
        if (values.size != 5) null else WorkspaceFileEntry(values[0] as String, values[1] as String, values[2] as Long, values[3] as Double, values[4] as Boolean)
    },
)

/** A `.confirm-dialog` over the browser: its own window, the skin's scrim, centred. */
@Composable
private fun SubDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NoWindowDim()
        // A nested <dialog> draws its own ::backdrop over the browser's, as the web's does.
        Box(
            Modifier
                .fillMaxSize()
                .background(dialogScrim(t))
                .tapToDismiss(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.swallowTaps()) { content() }
        }
    }
}

/** Our scrim replaces the platform's black window dim, so the backdrop is the skin's colour. */
@Composable
private fun NoWindowDim() {
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
}

/** `dialog-in`: 0 -> 1 over `--duration` with `--ease-out`; at once under reduced motion. */
@Composable
private fun rememberDialogIn(): Animatable<Float, *> {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) progress.animateTo(1f, tween(t.css.duration, easing = t.css.easeOut.toEasing()))
    }
    return progress
}
