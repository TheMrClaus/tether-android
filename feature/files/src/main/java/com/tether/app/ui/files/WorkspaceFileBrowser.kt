package com.tether.app.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.tether.app.client.WorkspaceFiles
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens

/**
 * The browser's state for the signed-in shell, on [files] (the client's `/api/files` routes).
 * Leaving composition (sign-out, a server switch) empties the scratch cache, shared copies
 * included: nothing a session downloaded outlives it on the device.
 */
@Composable
fun rememberFileBrowserState(files: WorkspaceFiles): FileBrowserState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val platform = remember(context) { AndroidBrowserPlatform(context) }
    val state = remember(files, platform) { FileBrowserState(files, platform, scope) }
    DisposableEffect(state) {
        onDispose {
            state.close()
            platform.sweep(keepRecentShares = false)
        }
    }
    return state
}

/**
 * components/workspace-file-browser.tsx as a native modal: shown while [FileBrowserState.isOpen]
 * (open it with [FileBrowserState.open]). Uploads come from the system document picker and
 * stream to the server; Save to device goes through the system "create document" picker; Share
 * hands one downloaded copy to the system share sheet through a read-only URI grant.
 */
@Composable
fun WorkspaceFileBrowser(state: FileBrowserState) {
    if (!state.isOpen) return
    val context = LocalContext.current
    val resolver = context.contentResolver

    val pickUploads = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        state.upload(
            uris.map { uri ->
                val (name, size) = ContentUploadSource.describe(resolver, uri)
                // The provider's display name is sent as the new file's name; the server validates it.
                PickedUpload(name?.takeIf { it.isNotBlank() } ?: "upload", ContentUploadSource(resolver, uri, size))
            },
        )
    }
    var saving by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = saving
        saving = null
        if (uri != null && entry != null) state.saveTo(entry, uri)
    }

    state.pendingShare?.let { share ->
        LaunchedEffect(share) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = share.mimeType
                putExtra(Intent.EXTRA_STREAM, share.uri)
                clipData = ClipData.newRawUri(share.name, share.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, null))
            } catch (_: ActivityNotFoundException) {
                // Nothing can receive it; the copy is swept with the rest of the cache.
            }
            state.shareHandled()
        }
    }

    Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NoWindowDim()
        val progress = rememberDialogIn()
        FileBrowserFrame(
            state = state,
            onClose = state::close,
            onUpload = { pickUploads.launch(arrayOf("*/*")) },
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            },
        )

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
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})) { content() }
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
