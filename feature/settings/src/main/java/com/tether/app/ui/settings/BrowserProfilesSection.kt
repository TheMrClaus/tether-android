package com.tether.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Tags of the browser profiles block. */
object BrowserProfilesTags {
    const val Block = "browser-profiles"
    const val Empty = "browser-profiles-empty"
    const val LoadError = "browser-profiles-load-error"
    const val Retry = "browser-profiles-retry"
    const val CreateField = "browser-profile-create-field"
    const val CreateKey = "browser-profile-create"
    const val CreateError = "browser-profile-create-error"
    fun row(id: String) = "browser-profile-row:$id"
    fun use(id: String) = "browser-profile-use:$id"
    fun rename(id: String) = "browser-profile-rename:$id"
    fun delete(id: String) = "browser-profile-delete:$id"
    fun renameField(id: String) = "browser-profile-rename-field:$id"
    fun save(id: String) = "browser-profile-save:$id"
    fun cancel(id: String) = "browser-profile-cancel:$id"
    fun confirm(id: String) = "browser-profile-confirm:$id"
    fun confirmDelete(id: String) = "browser-profile-confirm-delete:$id"
    fun keep(id: String) = "browser-profile-keep:$id"
    fun error(id: String) = "browser-profile-error:$id"
}

/**
 * components/browser-profiles-settings.tsx: the named browser profiles, inside Settings > Browser after the runtime rows. A row per
 * profile (name, "In use by <holder>" or "Not in use", Rename and Delete with a confirm), the empty sentence, a load error with Try
 * again, and the New profile form. Every refusal shows the server's own sentence. The list re-reads quietly when the app comes back.
 */
@Composable
internal fun BrowserProfilesBlock(c: BrowserProfilesController, narrow: Boolean) {
    LaunchedEffect(c) { c.shown() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(c, owner) {
        // Adding an observer to a resumed screen replays ON_RESUME; the first read is shown()'s, so only a resume after a pause re-reads.
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            else if (event == Lifecycle.Event.ON_RESUME && paused) { paused = false; c.refreshQuietly() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().testTag(BrowserProfilesTags.Block)) {
        SettingsRow(kind = RowKind.Server, narrow = narrow, text = { m -> SettingsRowText(BrowserProfilesCopy.TITLE, AnnotatedString(BrowserProfilesCopy.CAPTION), m) })
        if (c.loadError.isNotEmpty()) {
            SettingsRow(
                kind = RowKind.Server,
                narrow = narrow,
                text = { m -> CaptionText(c.loadError, m.testTag(BrowserProfilesTags.LoadError).semantics { liveRegion = LiveRegionMode.Assertive }) },
                control = { m ->
                    TetherKey(onClick = c::refresh, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.TRY_AGAIN, modifier = m.testTag(BrowserProfilesTags.Retry))
                },
            )
        }
        val list = c.profiles
        if (list != null && list.isEmpty() && c.loadError.isEmpty()) {
            SettingsRow(kind = RowKind.Server, narrow = narrow, text = { m -> CaptionText(BrowserProfilesCopy.EMPTY, m.testTag(BrowserProfilesTags.Empty)) })
        }
        for (profile in list.orEmpty()) {
            androidx.compose.runtime.key(profile.id) { ProfileRow(profile, c, narrow) }
        }
        CreateRow(c, narrow)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileRow(profile: com.tether.app.protocol.BrowserProfileInfo, c: BrowserProfilesController, narrow: Boolean) {
    val id = profile.id
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val editing = c.editing?.takeIf { it.id == id }
    val confirming = c.confirmingId == id
    val err = c.rowError?.takeIf { it.id == id }?.text
    Column(Modifier.fillMaxWidth().testTag(BrowserProfilesTags.row(id))) {
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m ->
                if (editing != null) {
                    ProfileField(
                        value = editing.name,
                        onChange = c::editRenameName,
                        placeholder = "",
                        description = BrowserProfilesCopy.renameLabel(profile.name),
                        tag = BrowserProfilesTags.renameField(id),
                        narrow = narrow,
                        focusOnStart = true,
                        onDone = c::rename,
                        modifier = m,
                    )
                } else {
                    SettingsRowText(profile.name, AnnotatedString(BrowserProfilesCopy.use(profile)), m.testTag(BrowserProfilesTags.use(id)))
                }
            },
            control = { m ->
                KeyGroup(narrow, m) { k ->
                    if (editing != null) {
                        TetherKey(onClick = c::rename, enabled = !c.busy, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.SAVE, modifier = k.testTag(BrowserProfilesTags.save(id)))
                        TetherKey(onClick = c::cancelRename, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.CANCEL, modifier = k.testTag(BrowserProfilesTags.cancel(id)))
                    } else {
                        TetherKey(onClick = { c.startRename(profile) }, enabled = !c.busy, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.RENAME, contentDescription = "${BrowserProfilesCopy.RENAME} ${profile.name}", modifier = k.testTag(BrowserProfilesTags.rename(id)))
                        TetherKey(onClick = { c.askDelete(id) }, enabled = !c.busy, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.DELETE, contentDescription = "${BrowserProfilesCopy.DELETE} ${profile.name}", modifier = k.testTag(BrowserProfilesTags.delete(id)))
                    }
                }
            },
        )
        if (confirming) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).testTag(BrowserProfilesTags.confirm(id)).semantics { contentDescription = BrowserProfilesCopy.deleteLabel(profile.name) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(BrowserProfilesCopy.deleteConfirm(profile.name), color = t.muted, style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TetherKey(onClick = { c.delete(id) }, enabled = !c.busy, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.DELETE_PROFILE, modifier = Modifier.testTag(BrowserProfilesTags.confirmDelete(id)))
                    TetherKey(onClick = c::keepIt, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.KEEP_IT, modifier = Modifier.testTag(BrowserProfilesTags.keep(id)))
                }
            }
        }
        if (err != null) {
            Text(
                err,
                color = t.warning,
                style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                modifier = Modifier.padding(top = 8.dp).testTag(BrowserProfilesTags.error(id)).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun CreateRow(c: BrowserProfilesController, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(BrowserProfilesCopy.NEW_PROFILE, color = t.ink, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f))
                    ProfileField(
                        value = c.createName,
                        onChange = c::editCreateName,
                        placeholder = BrowserProfilesCopy.NAME_PLACEHOLDER,
                        description = BrowserProfilesCopy.NEW_NAME_LABEL,
                        tag = BrowserProfilesTags.CreateField,
                        narrow = narrow,
                        focusOnStart = false,
                        onDone = c::create,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            control = { m ->
                TetherKey(onClick = c::create, enabled = !c.busy, classes = KeyClasses.ButtonSecondary, label = BrowserProfilesCopy.CREATE, modifier = m.testTag(BrowserProfilesTags.CreateKey))
            },
        )
        if (c.createError.isNotEmpty()) {
            Text(
                c.createError,
                color = t.warning,
                style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                modifier = Modifier.padding(bottom = 12.dp).testTag(BrowserProfilesTags.CreateError).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/** The row's keys: stacked and full width on a phone (the web's narrow rows), side by side otherwise. */
@Composable
private fun KeyGroup(narrow: Boolean, modifier: Modifier, keys: @Composable (Modifier) -> Unit) {
    if (narrow) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { keys(Modifier.fillMaxWidth()) }
    } else {
        androidx.compose.foundation.layout.Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys(Modifier) }
    }
}

/** A `<small>` line: 12sp, muted. */
@Composable
private fun CaptionText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalTetherTokens.current.muted, style = settingsText(LocalTetherTypography.current.ui, 12f, 400, lineHeight = 1.6f), modifier = modifier)
}

/** `.settings-server-input` for a profile name: the web's plain field, 60 characters at most (the input's `maxLength`). */
@Composable
private fun ProfileField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    description: String,
    tag: String,
    narrow: Boolean,
    focusOnStart: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (focusOnStart) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = serverFieldStyle(narrow)
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(BrowserProfilesCopy.NAME_LIMIT)) },
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .testTag(tag)
            .semantics { contentDescription = description }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner -> ServerFieldBox(true, focused, style, if (value.isEmpty() && placeholder.isNotEmpty()) AnnotatedString(placeholder) else null, inner) },
    )
}
