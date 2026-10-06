package com.tether.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.BrowseStatus
import com.tether.app.client.WorkspaceSelectStatus
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/*
 * T8.2: components/folder-picker-dialog.tsx (tether 90fbb9f), the one picker every surface opens:
 * the sidebar's "Add workspace" and the Studio welcome's "Open workspace" (dashboard.tsx :1849, with
 * the browse and workspace-activation statuses), the draft composer's "Browse for another folder…"
 * (draft-composer.tsx :916-925) and Settings' home "Browse folders" (settings-dialog.tsx :2457-2480).
 * It lives here, in feature/settings, because the sidebar and the shell both depend on this module.
 * The header's `.section-label` is not drawn: Studio hides it (studio.css :549).
 */

/** The picker's words, as folder-picker-dialog.tsx 90fbb9f writes them. */
object FolderPickerCopy {
    const val DEFAULT_TITLE = "Choose a folder"
    const val CREATE_TOGGLE = "Create a new folder"
    const val NEW_FOLDER_NAME = "New folder name"
    const val CREATE = "Create"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val PARENT = "Parent folder"
    const val PARENT_DETAIL = "Go up one level"
    const val EMPTY = "No folders are available here."
    const val LOAD_ERROR = "Couldn’t load this folder — the secure link is reconnecting. It will load automatically."
    const val OPEN_ERROR = "Couldn’t open this workspace yet — the secure link is reconnecting. It will open automatically."
    const val USE = "Use this folder"
    const val OPENING = "Opening…"

    /** :87 `maxLength={200}`. */
    const val NAME_MAX_LENGTH = 200
}

/** Test tags of the picker's controls. */
object FolderPickerTags {
    const val Dialog = "folder-picker"
    const val CreateToggle = "folder-picker-create-toggle"
    const val NewFolderName = "folder-picker-new-name"
    const val Create = "folder-picker-create"
    const val CreateCancel = "folder-picker-create-cancel"
    const val Close = "folder-picker-close"
    const val Current = "folder-picker-current"
    const val LoadError = "folder-picker-load-error"
    const val OpenError = "folder-picker-open-error"
    const val Cancel = "folder-picker-cancel"
    const val Use = "folder-picker-use"
}

/**
 * The modal picker. [current] is the folder shown before the first listing arrives (the web's
 * `root`); navigation is unrestricted. [onCreateFolder] (null hides the button, :76-81) creates a
 * folder in the one being browsed. [browseStatus] `error` shows "couldn't load this folder" (:101-106).
 * [selectStatus] is the workspace-activation intent: after "Use this folder" the picker stays open
 * while it names the chosen folder — `opening` (the key disabled, "Opening…") or `stalled` ("couldn't
 * open — retrying", :116-135) — and is dismissed once it no longer does (:46-53). Without one it is
 * dismissed at once, as the web's is when nothing is passed.
 */
@Composable
fun FolderPickerDialog(
    directories: DirectoryListing?,
    current: String?,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
    title: String = FolderPickerCopy.DEFAULT_TITLE,
    onCreateFolder: ((cwd: String, name: String) -> Unit)? = null,
    browseStatus: BrowseStatus? = null,
    selectStatus: StateFlow<WorkspaceSelectStatus?>? = null,
    /**
     * What a failed listing says. The default is the console's (the secure link is reconnecting); the
     * setup wizard has no link and shows its own sentence (page.tsx FolderBrowser: the server's error).
     */
    loadErrorText: String = FolderPickerCopy.LOAD_ERROR,
) {
    val state = rememberFolderPickerState()
    val select = selectStatus?.collectAsState()?.value
    // :46-53: dismissed once the selection is confirmed (the intent cleared) or superseded. Read from
    // the flow itself, so the value written by the pick is the one judged.
    val awaitingCwd = state.awaitingCwd
    LaunchedEffect(awaitingCwd) {
        if (awaitingCwd == null) return@LaunchedEffect
        selectStatus?.first { it == null || it.cwd != awaitingCwd }
        onDismiss()
    }
    TetherDialog(onDismiss = onDismiss, footer = { FolderPickerFooter(state, directories, current, select, onDismiss, onChoose) }) {
        FolderPickerBody(state, directories, current, title, onDismiss, onBrowse, onCreateFolder, browseStatus, select, loadErrorText)
    }
}

/** The picker drawn in place over the skin's scrim (the goldens; the modal hosts the same surface). */
@Composable
internal fun FolderPickerFrame(
    directories: DirectoryListing?,
    current: String?,
    title: String = FolderPickerCopy.DEFAULT_TITLE,
    onCreateFolder: ((cwd: String, name: String) -> Unit)? = { _, _ -> },
    browseStatus: BrowseStatus? = null,
    select: WorkspaceSelectStatus? = null,
    state: FolderPickerState = rememberFolderPickerState(),
) {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        TetherDialogSurface(footer = { FolderPickerFooter(state, directories, current, select, {}, {}) }) {
            FolderPickerBody(state, directories, current, title, {}, {}, onCreateFolder, browseStatus, select)
        }
    }
}

/** The picker's own state (:34-40): the new-folder form and the folder awaiting confirmation. */
class FolderPickerState(newFolderOpen: Boolean = false, newFolderName: String = "", awaitingCwd: String? = null) {
    var newFolderOpen by mutableStateOf(newFolderOpen)
    var newFolderName by mutableStateOf(newFolderName)
    var awaitingCwd by mutableStateOf(awaitingCwd)

    companion object {
        /** ta-coik.20: the typed new-folder name (and the form's openness) survive a rotation. */
        val Saver: androidx.compose.runtime.saveable.Saver<FolderPickerState, Any> = androidx.compose.runtime.saveable.listSaver(
            save = { s -> listOf(s.newFolderOpen, s.newFolderName, s.awaitingCwd.orEmpty(), s.awaitingCwd != null) },
            restore = { v -> FolderPickerState(v[0] as Boolean, v[1] as String, if (v[3] as Boolean) v[2] as String else null) },
        )
    }
}

@Composable
internal fun rememberFolderPickerState(): FolderPickerState =
    androidx.compose.runtime.saveable.rememberSaveable(saver = FolderPickerState.Saver) { FolderPickerState() }

private fun pickerCurrent(directories: DirectoryListing?, current: String?): String? =
    directories?.current?.takeIf { it.isNotEmpty() } ?: current

/** :69 — the selection status, only while it names the folder the operator chose. */
private fun awaiting(state: FolderPickerState, select: WorkspaceSelectStatus?): WorkspaceSelectStatus? =
    select?.takeIf { state.awaitingCwd != null && it.cwd == state.awaitingCwd }

@Composable
private fun ColumnScope.FolderPickerBody(
    state: FolderPickerState,
    directories: DirectoryListing?,
    current: String?,
    title: String,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onCreateFolder: ((cwd: String, name: String) -> Unit)?,
    browseStatus: BrowseStatus?,
    select: WorkspaceSelectStatus?,
    loadErrorText: String = FolderPickerCopy.LOAD_ERROR,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shown = pickerCurrent(directories, current)
    Column(Modifier.fillMaxWidth().testTag(FolderPickerTags.Dialog)) {
        // :73-83 header: the title, then "Create a new folder" (when offered) and Close.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            Text(
                title,
                color = t.white,
                style = type.body.copy(fontSize = 22.sp, fontWeight = FontWeight(700), letterSpacing = (-0.025).em, lineHeight = 1.3.em),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (onCreateFolder != null) {
                TetherKey(
                    onClick = {
                        state.newFolderOpen = !state.newFolderOpen
                        state.newFolderName = ""
                    },
                    classes = KeyClasses.IconButton,
                    icon = TetherIcons.FolderPlus,
                    iconSize = 19.dp,
                    contentDescription = FolderPickerCopy.CREATE_TOGGLE,
                    selected = state.newFolderOpen,
                    modifier = Modifier.testTag(FolderPickerTags.CreateToggle),
                )
            }
            TetherKey(
                onClick = onDismiss,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 19.dp,
                contentDescription = FolderPickerCopy.CLOSE,
                modifier = Modifier.testTag(FolderPickerTags.Close),
            )
        }
        if (state.newFolderOpen && onCreateFolder != null) NewFolderForm(state, shown, onCreateFolder)
        // :98 `.folder-current` (Studio: mineral, 8px radius, 12px).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .background(t.mineral, RoundedCornerShape(8.dp))
                .padding(14.dp)
                .testTag(FolderPickerTags.Current),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.FolderOpen, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp))
            // ta-28i: folder names and paths from the server are code (every control a token), LTR.
            Text(
                shown?.let { codeLabel(it) } ?: AnnotatedString("—"),
                color = t.muted,
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // :101-106: a lost `browse` reply — the picker is NOT showing the folder that was tapped.
        if (browseStatus?.phase == BrowseStatus.Phase.Error) FolderStatusError(loadErrorText, FolderPickerTags.LoadError)
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            directories?.parent?.let { parent ->
                FolderRow(
                    icon = { Icon(TetherIcons.ArrowLeft, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp)) },
                    name = FolderPickerCopy.PARENT,
                    detail = FolderPickerCopy.PARENT_DETAIL,
                    onClick = { onBrowse(parent) },
                )
            }
            directories?.entries?.forEach { entry ->
                FolderRow(
                    icon = { Icon(TetherIcons.Folder, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp)) },
                    name = entry.name,
                    detail = entry.path,
                    onClick = { onBrowse(entry.path) },
                )
            }
            if (directories != null && directories.entries.isEmpty() && directories.parent == null) {
                Text(
                    FolderPickerCopy.EMPTY,
                    color = t.muted,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 12.8.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        // :116-121: `stalled` — the persistent "couldn't open — retrying" that recovers on its own.
        if (awaiting(state, select)?.phase == WorkspaceSelectStatus.Phase.Stalled) {
            FolderStatusError(FolderPickerCopy.OPEN_ERROR, FolderPickerTags.OpenError)
        }
    }
}

/** :84-97 `.folder-new-folder`: the name (200 at most), Create (only with a name), Cancel. */
@Composable
private fun NewFolderForm(state: FolderPickerState, cwd: String?, onCreateFolder: (cwd: String, name: String) -> Unit) {
    val t = LocalTetherTokens.current
    val focus = remember { FocusRequester() }
    val name = state.newFolderName.trim()
    // :58-66 submitNewFolder: a blank name is no submission; the form closes once it is sent.
    val submit = {
        if (name.isNotEmpty() && cwd != null) {
            onCreateFolder(cwd, name)
            state.newFolderName = ""
            state.newFolderOpen = false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        TetherInputWell(
            value = state.newFolderName,
            onValueChange = { state.newFolderName = it.take(FolderPickerCopy.NAME_MAX_LENGTH) },
            placeholder = FolderPickerCopy.NEW_FOLDER_NAME,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .semantics { contentDescription = FolderPickerCopy.NEW_FOLDER_NAME }
                .testTag(FolderPickerTags.NewFolderName),
        )
        TetherKey(
            onClick = submit,
            classes = KeyClasses.ButtonPrimary,
            label = FolderPickerCopy.CREATE,
            enabled = name.isNotEmpty(),
            modifier = Modifier.testTag(FolderPickerTags.Create),
        )
        TetherKey(
            onClick = { state.newFolderOpen = false },
            classes = KeyClasses.ButtonSecondary,
            label = FolderPickerCopy.CANCEL,
            modifier = Modifier.testTag(FolderPickerTags.CreateCancel),
        )
    }
    // :88 `autoFocus`.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** `.folder-status.folder-status-error` (role=alert): danger edge and wash, 8px radius, 13px. */
@Composable
private fun FolderStatusError(text: String, tag: String) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .border(1.dp, t.dangerEdge, shape)
            .background(t.dangerWash, shape)
            .padding(t.css.spaceMd)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(16.dp))
        Text(text, color = t.danger, fontFamily = Manrope, fontSize = 13.sp, lineHeight = 1.6.em)
    }
}

/** :122-135: Cancel (Close while awaiting) and "Use this folder" (disabled, "Opening…", while opening). */
@Composable
private fun RowScope.FolderPickerFooter(
    state: FolderPickerState,
    directories: DirectoryListing?,
    current: String?,
    select: WorkspaceSelectStatus?,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val awaiting = awaiting(state, select)
    val opening = awaiting?.phase == WorkspaceSelectStatus.Phase.Opening
    TetherKey(
        onClick = onDismiss,
        classes = KeyClasses.ButtonSecondary,
        label = if (awaiting != null) FolderPickerCopy.CLOSE else FolderPickerCopy.CANCEL,
        modifier = Modifier.testTag(FolderPickerTags.Cancel),
    )
    TetherKey(
        onClick = {
            pickerCurrent(directories, current)?.let { cwd ->
                onChoose(cwd)
                state.awaitingCwd = cwd
            }
        },
        classes = KeyClasses.ButtonPrimary,
        label = if (opening) FolderPickerCopy.OPENING else FolderPickerCopy.USE,
        icon = if (opening) TetherIcons.Loader else TetherIcons.Check,
        iconSize = 17.dp,
        enabled = !opening,
        modifier = Modifier.testTag(FolderPickerTags.Use),
    )
}

/** One tappable row in the folder picker (`.folder-list > button`): icon + name over truncated path. */
@Composable
fun FolderRow(
    icon: @Composable () -> Unit,
    name: String,
    detail: String,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = maxOf(TetherDimens.touchTargetDp, 62.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(
                codeLabel(name),
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = FontWeight(600),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                codeLabel(detail),
                color = t.faint,
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
