package com.tether.app.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tether.app.client.LabelText
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsPatch
import com.tether.app.client.ServerSettingsView
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.delay

/** Tags of the server-settings rows (one per wire key). */
object ServerSettingsTags {
    fun row(s: ServerSetting) = "server-setting:${s.key}"
    fun input(s: ServerSetting) = "server-setting-input:${s.key}"
    fun masked(s: ServerSetting) = "server-setting-masked:${s.key}"
    fun reveal(s: ServerSetting) = "server-setting-reveal:${s.key}"
    fun add(s: ServerSetting) = "server-setting-add:${s.key}"
    fun entry(s: ServerSetting, path: String) = "server-setting-entry:${s.key}:$path"
    fun remove(s: ServerSetting, path: String) = "server-setting-remove:${s.key}:$path"
    fun section(id: String) = "server-settings-section:$id"
    const val Loading = "server-settings-loading"
    const val GitHub = "server-settings-github"
    const val CliWarning = "claude-cli-warning"
    const val CliPicker = "claude-cli-picker"
    const val CliForced = "claude-cli-forced"
    const val CliConfirmSheet = "claude-cli-confirm"
    const val CliConfirmNow = "claude-cli-confirm-now"
    const val CliConfirmNew = "claude-cli-confirm-new"
    const val CliConfirm = "claude-cli-confirm-switch"
    const val CliCancel = "claude-cli-confirm-cancel"
}

/** A row's caption: "Set by environment" when forced (settings-dialog.tsx:145), else its description. */
private fun caption(forced: Boolean, description: String) = AnnotatedString(if (forced) ServerRowCopy.SET_BY_ENV else description)

/**
 * `.settings-server-input` (studio.css 594-609, 975-979): a 44dp field on the case's graphite with a
 * 1px `--line-strong` edge at 8dp, mono 13 (16 on a phone), the focus edge `--violet-strong`;
 * disabled at 0.55. The value is committed the web's way (ServerTextRow, settings-dialog.tsx:129):
 * on Done (Enter), when the field loses focus ([commitOnBlur]), and when it leaves the screen with
 * an edit in it ([commitOnLeave]: the web's blur on close). [shown] is what the field was filled
 * with; a server reply with a new value refills it (the web's `key={rawValue}` remount).
 *
 * r2: a configuration change (rotation, theme, locale: the activity is recreated) is NOT a close
 * or a blur: while the activity [isChangingConfigurations][android.app.Activity.isChangingConfigurations]
 * neither the focus loss nor the disposal it causes commits anything, so a half-typed value is
 * never sent. [accept]: an edit that fails it leaves the field as it was (nothing is rewritten).
 * [noCopy]: copy and cut put nothing on the clipboard and are not offered (a revealed secret).
 */
@Composable
internal fun CommitField(
    shown: String,
    label: String,
    tag: String,
    enabled: Boolean,
    narrow: Boolean,
    placeholder: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    accept: (String) -> Boolean = { true },
    commitOnBlur: Boolean = true,
    commitOnLeave: Boolean = true,
    noCopy: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val focusManager = LocalFocusManager.current
    val activity = LocalContext.current.findActivity()
    val recreating = { activity?.isChangingConfigurations == true }
    var text by remember(shown) { mutableStateOf(shown) }
    // The last value handed to [onCommit] for this server value: Done then the focus loss it
    // causes is one write, not two.
    var sent by remember(shown) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val commit: () -> Unit = {
        if (text != shown && text != sent) {
            sent = text
            onCommit(text)
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    val leaves by rememberUpdatedState(commitOnLeave)
    DisposableEffect(Unit) { onDispose { if (leaves && !recreating()) latestCommit() } }
    val style = settingsText(type.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    NoCopyScope(noCopy) {
    BasicTextField(
        value = text,
        onValueChange = { if (accept(it)) text = it },
        enabled = enabled,
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            // A secret or a path is never learned or corrected by the keyboard.
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focusManager.clearFocus()
        }),
        modifier = modifier
            .testTag(tag)
            .semantics { contentDescription = label }
            .onFocusChanged { f ->
                if (focused && !f.isFocused && commitOnBlur && !recreating()) commit()
                focused = f.isFocused
            },
        decorationBox = { inner ->
            ServerFieldBox(enabled, focused, style, if (text.isEmpty() && placeholder.isNotEmpty()) AnnotatedString(placeholder) else null, inner)
        },
    )
    }
}

/** ta-dh1: `.settings-server-input`'s box (shared by [CommitField] and the engine fields): the edge, the fill, the placeholder. */
@Composable
internal fun ServerFieldBox(enabled: Boolean, focused: Boolean, style: androidx.compose.ui.text.TextStyle, placeholder: AnnotatedString?, inner: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.55f)
            .heightIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (placeholder != null) Text(placeholder, style = style, color = t.faint, maxLines = 1)
        inner()
    }
}

/** ta-dh1: the server field's text style (mono 13, 16 on a phone). */
@Composable
internal fun serverFieldStyle(narrow: Boolean) = settingsText(LocalTetherTypography.current.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)

/** ta-dh1: [serverInputWidth] for the engine rows. */
internal fun Modifier.serverFieldWidth(narrow: Boolean): Modifier = serverInputWidth(narrow)

/** The activity hosting [this] context (a dialog's themed wrapper included), or null. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * r2: with [on], the field's copy and cut never reach the clipboard (its writes are dropped; paste
 * still reads it) and the text toolbar offers neither. (A cut asked for another way, an
 * accessibility action or a hardware key, then only deletes the selection.)
 */
// AndroidClipboard is marked @VisibleForTesting, but the text field requires it (a plain Clipboard
// throws in its paste check), so the guard implements it.
@SuppressLint("VisibleForTests")
@Composable
internal fun NoCopyScope(on: Boolean, content: @Composable () -> Unit) {
    if (!on) return content()
    val clipboard = LocalClipboard.current
    val toolbar = LocalTextToolbar.current
    // The text field reads the platform manager for paste (AndroidClipboard); only writes are dropped.
    val guarded = remember(clipboard) { (clipboard as? AndroidClipboard)?.let(::NoCopyClipboard) ?: clipboard }
    val menu = remember(toolbar) { NoCopyToolbar(toolbar) }
    CompositionLocalProvider(LocalClipboard provides guarded, LocalTextToolbar provides menu, content = content)
}

@SuppressLint("VisibleForTests")
private class NoCopyClipboard(private val delegate: AndroidClipboard) : AndroidClipboard {
    override val clipboardManager: android.content.ClipboardManager get() = delegate.clipboardManager
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}

internal class NoCopyToolbar(private val delegate: TextToolbar) : TextToolbar by delegate {
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = delegate.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) = delegate.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)
}

/** The control's width: the row's on a phone, the web's 288dp cap beside the text. */
private fun Modifier.serverInputWidth(narrow: Boolean): Modifier = if (narrow) fillMaxWidth() else widthIn(max = 288.dp).fillMaxWidth()

/** ServerTextRow (settings-dialog.tsx:114-162), not a secret. The field holds the value cleaned of hidden characters (the edit-field rule). */
@Composable
internal fun ServerTextRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val forced = view.forced(s)
    val shown = remember(view.text(s)) { LabelText.withoutHidden(view.text(s)) }
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
        text = { m -> SettingsRowText(row.label, caption(forced, row.description), m, tip = row.tip, locked = forced) },
        control = { m ->
            CommitField(
                shown = shown,
                label = row.label,
                tag = ServerSettingsTags.input(s),
                enabled = !forced,
                narrow = narrow,
                placeholder = row.placeholder,
                onCommit = { binding.send(ServerSettingsPatch.text(view, s, it, shown)) },
                modifier = m.serverInputWidth(narrow),
            )
        },
    )
}

/**
 * ServerTextRow with `password` (settings-dialog.tsx:144-158) for `password` / `proxyToken`, which
 * the server sends in plaintext. ta-t7l:
 * - masked by default: the value is NOT in the composition then. The masked well draws a fixed
 *   mask (never the length) and is one node named "<label>, hidden" (or "not set"), so the semantics
 *   tree holds no part of the secret;
 * - Reveal swaps in the editable field (plain text, the web's `type="text"` on reveal); to change
 *   the secret on the phone, reveal it first (the web lets a masked field be typed into);
 * - the reveal flag is plain `remember`: never saved state, so a rotation, closing Settings or
 *   leaving the tab masks it again; the panel is keyed on the server's origin, so another server
 *   starts masked; r2: the app going to the background (ON_STOP) masks it too, so the Recents
 *   snapshot never holds it (the dialog's window is also FLAG_SECURE);
 * - r2: a secret is sent ONLY by Done on the keyboard: never on a focus loss, a close, a tab change
 *   or a recreation, so a half-typed password is never applied. Copy and cut are off.
 * - forced by env: masked, no Reveal (the web hides its eye then).
 */
@Composable
internal fun ServerSecretRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val forced = view.forced(s)
    val secret = view.secret(s)
    var revealed by rememberMaskedReveal()
    val open = revealed && !forced
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
        text = { m -> SettingsRowText(row.label, caption(forced, row.description), m, tip = row.tip, locked = forced) },
        control = { m ->
            Row(m.serverInputWidth(narrow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (open) {
                    // Only here is the plaintext read: the revealed field, filled with it as it is.
                    val shown = secret.reveal()
                    CommitField(
                        shown = shown,
                        label = row.label,
                        tag = ServerSettingsTags.input(s),
                        enabled = true,
                        narrow = narrow,
                        placeholder = row.placeholder,
                        keyboardType = KeyboardType.Password,
                        commitOnBlur = false,
                        commitOnLeave = false,
                        noCopy = true,
                        onCommit = { binding.send(ServerSettingsPatch.text(view, s, it, shown)) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    MaskedWell(
                        tag = ServerSettingsTags.masked(s),
                        description = ServerRowCopy.maskedDescription(row.label, secret.isEmpty),
                        hasValue = !secret.isEmpty,
                        placeholder = row.placeholder,
                        narrow = narrow,
                        dimmed = forced,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!forced) {
                    TetherKey(
                        onClick = { revealed = !revealed },
                        classes = KeyClasses.IconButton,
                        icon = if (open) TetherIcons.EyeOff else TetherIcons.Eye,
                        iconSize = 14.dp,
                        contentDescription = if (open) ServerRowCopy.hide(row.label) else ServerRowCopy.reveal(row.label),
                        modifier = Modifier.testTag(ServerSettingsTags.reveal(s)),
                    )
                }
            }
        },
    )
}

/**
 * ta-q6p (slice 3's reveal, shared): whether a secret is revealed. Plain `remember` (never saved
 * state, so a rotation, a close or leaving the tab masks it again), and the app going to the
 * background (ON_STOP) masks it too, so the Recents snapshot never holds it.
 */
@Composable
internal fun rememberMaskedReveal(): androidx.compose.runtime.MutableState<Boolean> {
    val revealed = remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) revealed.value = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return revealed
}

/**
 * ta-q6p (slice 3's masked well, shared): a secret while masked. The value is NOT in the
 * composition: the well draws a fixed mask (never the length) when [hasValue], else the
 * [placeholder], and is one node named [description], so the semantics tree holds no part of it.
 */
@Composable
internal fun MaskedWell(
    tag: String,
    description: String,
    hasValue: Boolean,
    placeholder: String,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = settingsText(type.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    Box(
        modifier
            .testTag(tag)
            .clearAndSetSemantics { contentDescription = description }
            .alpha(if (dimmed) 0.55f else 1f)
            .heightIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, t.lineStrong), emptyList())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            hasValue -> Text(ServerRowCopy.MASK, style = style, color = t.ink, maxLines = 1)
            placeholder.isNotEmpty() -> Text(placeholder, style = style, color = t.faint, maxLines = 1)
        }
    }
}

/** ServerNumberRow (settings-dialog.tsx:164-211): digits only (the number keypad, and any other edit refused), committed like the text row. */
@Composable
internal fun ServerNumberRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val forced = view.forced(s)
    val shown = view.number(s)?.toString().orEmpty()
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
        text = { m -> SettingsRowText(row.label, caption(forced, row.description), m, tip = row.tip, locked = forced) },
        control = { m ->
            // `.settings-server-number`: at most 144dp, on a phone too (studio.css 979). The Box takes
            // the row's width so the cap holds under it.
            Box(m) {
                CommitField(
                    shown = shown,
                    label = row.label,
                    tag = ServerSettingsTags.input(s),
                    enabled = !forced,
                    narrow = narrow,
                    placeholder = row.placeholder,
                    keyboardType = KeyboardType.Number,
                    // r2: an edit that is not all digits (a paste of "6e4" or "-5") is refused as a
                    // whole, never rewritten into another number that would then be sent.
                    accept = { typed -> typed.length <= MAX_DIGITS && typed.all { it in '0'..'9' } },
                    onCommit = { binding.send(ServerSettingsPatch.number(view, s, it)) },
                    modifier = Modifier.widthIn(max = 144.dp).fillMaxWidth(),
                )
            }
        },
    )
}

/** A safe integer's digits (JavaScript's `Number` is exact to 2^53). */
private const val MAX_DIGITS = 15

/** ServerToggleRow (settings-dialog.tsx:213-239). */
@Composable
internal fun ServerToggleRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val forced = view.forced(s)
    SettingsToggleRow(
        title = row.label,
        caption = if (forced) ServerRowCopy.SET_BY_ENV else row.description,
        tip = row.tip,
        checked = view.toggle(s),
        onToggle = { binding.send(ServerSettingsPatch.toggle(view, s)) },
        narrow = narrow,
        enabled = !forced,
        locked = forced,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
    )
}

/**
 * ServerSelectRow (settings-dialog.tsx:249-280). A value the options do not name is shown as it is
 * (the value rule) rather than as nothing. On a phone the select is at most 52% of the row
 * (studio.css 974).
 */
@Composable
internal fun ServerSelectRow(
    row: ServerRow,
    options: List<ServerChoice>,
    view: ServerSettingsView,
    binding: ServerSettingsBinding,
    narrow: Boolean,
    description: String = row.description,
    tip: String = row.tip,
    label: String = row.label,
) {
    val s = row.setting
    val forced = view.forced(s)
    val current = view.choice(s)
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
        text = { m -> SettingsRowText(label, caption(forced, description), m, tip = tip, locked = forced) },
        control = { m ->
            Box(m) {
                TetherSelect(
                    options = options.map { TetherSelectOption(it.value, it.label, danger = it.danger) },
                    selectedValue = current,
                    onSelect = { binding.send(ServerSettingsPatch.choice(view, s, it.value)) },
                    enabled = !forced,
                    placeholder = LabelText.visibleValue(current),
                    contentDescription = label,
                    modifier = (if (narrow) Modifier.fillMaxWidth(0.52f) else Modifier).testTag(ServerSettingsTags.input(s)),
                )
            }
        },
    )
}

/**
 * ServerRootsRow (settings-dialog.tsx:286-357): each path (by the path rule) with its remove key,
 * then the add field and Add. The whole list is written each time. Forced: the list only.
 */
@Composable
internal fun ServerRootsRow(row: AdvancedRows.RootsRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val s = row.setting
    val forced = view.forced(s)
    val roots = view.paths(s)
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
        text = { m -> SettingsRowText(row.title, caption(forced, row.description(roots.size)), m, tip = row.tip, locked = forced) },
        control = { m ->
            Column(m.serverInputWidth(narrow), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                roots.forEach { path ->
                    Row(
                        Modifier
                            .testTag(ServerSettingsTags.entry(s, path))
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .cssSurface(RoundedCornerShape(8.dp), t.mineral, null, emptyList())
                            .padding(start = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            codeLabel(path),
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = settingsText(type.mono, 12f, 400, lineHeight = 1.5f),
                            modifier = Modifier.weight(1f),
                        )
                        if (!forced) {
                            TetherKey(
                                onClick = { binding.send(ServerSettingsPatch.removePath(view, s, path)) },
                                classes = KeyClasses.IconButton,
                                icon = TetherIcons.X,
                                iconSize = 14.dp,
                                contentDescription = "Remove ${LabelText.visibleValue(path)}",
                                modifier = Modifier.testTag(ServerSettingsTags.remove(s, path)),
                            )
                        }
                    }
                }
                if (!forced) {
                    var typed by remember(roots) { mutableStateOf("") }
                    val add = {
                        binding.send(ServerSettingsPatch.addPath(view, s, typed))
                        typed = ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddPathField(typed, { typed = it }, row.addLabel, ServerSettingsTags.input(s), narrow, onDone = add, modifier = Modifier.weight(1f))
                        TetherKey(
                            onClick = add,
                            classes = KeyClasses.ButtonSecondary,
                            label = "Add",
                            contentDescription = row.addLabel,
                            modifier = Modifier.testTag(ServerSettingsTags.add(s)),
                        )
                    }
                }
            }
        },
    )
}

/** The roots row's add field: a draft, sent only by Add or Done (never on blur, as on the web). */
@Composable
private fun AddPathField(value: String, onChange: (String) -> Unit, label: String, tag: String, narrow: Boolean, onDone: () -> Unit, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var focused by remember { mutableStateOf(false) }
    val style = settingsText(type.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.testTag(tag).semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(AdvancedRows.ROOT_PLACEHOLDER, style = style, color = t.faint, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * The Claude CLI section (settings-dialog.tsx:2426-2449): `.settings-warning` (Studio: the attention
 * wash, studio.css 612), then the env-forced row, or the version picker (disabled until the server's
 * `advanced-settings` reply). Its writes are `set-advanced-settings`.
 *
 * r2: the picker chooses which Claude binary the server RUNS, so (owner decision, 2026-10-01) a
 * pick only opens [ClaudeCliConfirmDialog], which shows the current and the new CLI; Switch sends
 * the write, built at that moment from the latest frame (an env override that landed meanwhile
 * still sends nothing). Cancel, Back or a tap outside sends nothing.
 */
@Composable
internal fun ClaudeCliSection(binding: ServerSettingsBinding, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val advanced = binding.advanced
    var pending by remember { mutableStateOf<ServerChoice?>(null) }
    SettingsSection(ClaudeCliCopy.TITLE, null, narrow, modifier = Modifier.testTag(ServerSettingsTags.section("cli")), last = true) {
        val warning = buildAnnotatedString {
            val strong = SpanStyle(fontWeight = FontWeight(630), color = t.ink)
            append("Which Claude CLI this app runs. ")
            withStyle(strong) { append("Auto") }
            append(" uses the newest version you have installed on this host (so Tether tracks your own CLI); it falls back to the SDK-bundled CLI when none is installed. Pick a specific version or ")
            withStyle(strong) { append("Bundled") }
            append(" only if you need to — a CLI that doesn't match the built-in SDK can make turns fail, or ")
            withStyle(strong) { append("silently disable the approval prompts") }
            append(". If anything misbehaves, switch back to Auto.")
        }
        Row(
            Modifier
                .testTag(ServerSettingsTags.CliWarning)
                .padding(bottom = 16.dp)
                .fillMaxWidth()
                .cssSurface(RoundedCornerShape(8.dp), t.attentionBg, null, emptyList())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 3.dp).size(15.dp))
            Text(warning, color = t.attentionInk, style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f))
        }
        if (advanced?.envForced == true) {
            SettingsRow(
                narrow = narrow,
                modifier = Modifier.testTag(ServerSettingsTags.CliForced),
                text = { m ->
                    val path = advanced.envPath.orEmpty()
                    val caption = buildAnnotatedString {
                        append(ClaudeCliCopy.FORCED_LEAD)
                        append(codeLabel(path))
                    }
                    SettingsRowText(ClaudeCliCopy.FORCED_TITLE, caption, m, tip = ClaudeCliCopy.FORCED_TIP)
                },
            )
        } else {
            SettingsRow(
                narrow = narrow,
                text = { m -> SettingsRowText(ClaudeCliCopy.PICKER_TITLE, AnnotatedString(ClaudeCliCopy.caption(advanced)), m, tip = ClaudeCliCopy.PICKER_TIP) },
                control = { m ->
                    Box(m) {
                        TetherSelect(
                            options = ClaudeCliCopy.options(advanced).map { TetherSelectOption(it.value, it.label) },
                            selectedValue = advanced?.claudeCliVersion.orEmpty(),
                            onSelect = { choice ->
                                val a = advanced
                                if (a != null && ServerSettingsPatch.cliVersion(a, choice.value) != null) {
                                    pending = ClaudeCliCopy.options(a).firstOrNull { it.value == choice.value }
                                }
                            },
                            enabled = advanced != null,
                            placeholder = LabelText.visibleValue(advanced?.claudeCliVersion),
                            contentDescription = ClaudeCliCopy.PICKER_TITLE,
                            modifier = (if (narrow) Modifier.fillMaxWidth(0.52f) else Modifier).testTag(ServerSettingsTags.CliPicker),
                        )
                    }
                },
            )
        }
    }
    PendingCliPick(pending, binding) { pending = null }
}

/**
 * The section's pending pick, confirmed or dropped. ta-dh1 r2: as the engine confirmation, a tap
 * on Switch only asks; the write is built in the next frame from that frame's binding (an env
 * override that landed with the tap sends nothing), once per confirmation.
 */
@Composable
private fun PendingCliPick(pending: ServerChoice?, binding: ServerSettingsBinding, onDone: () -> Unit) {
    val choice = pending ?: return
    // The frame went away (signed out, another server): the pick goes with it.
    val advanced = binding.advanced ?: return SideEffect { onDone() }
    var asked by remember(choice) { mutableStateOf(false) }
    val fired = remember(choice) { booleanArrayOf(false) }
    if (asked) {
        SideEffect {
            if (!fired[0]) {
                fired[0] = true
                binding.sendCli(ServerSettingsPatch.cliVersion(advanced, choice.value))
            }
            onDone()
        }
        return
    }
    val current = ClaudeCliCopy.options(advanced).firstOrNull { it.value == advanced.claudeCliVersion.orEmpty() }?.label
        ?: LabelText.visibleValue(advanced.claudeCliVersion)
    ClaudeCliConfirmDialog(
        current = current,
        next = choice.label,
        onConfirm = { asked = true },
        onCancel = onDone,
    )
}

/**
 * ta-dh1 r2 (security F2): how long a confirmation's confirm key ignores taps after it appears
 * (longer than its fade-in), so a double tap on what opened it cannot confirm a change unread. The
 * wait runs on the composition's clock (a test's or golden's hand-driven one); [LocalConfirmArmMs]
 * sets it.
 */
const val CONFIRM_ARM_MS: Long = 450L

val LocalConfirmArmMs = staticCompositionLocalOf { CONFIRM_ARM_MS }

/** The confirm key of a confirmation: drawn at rest at once, but a tap counts only once armed. */
@Composable
internal fun ArmedConfirmKey(label: String, tag: String, onConfirm: () -> Unit) {
    val ms = LocalConfirmArmMs.current
    var armed by remember { mutableStateOf(ms <= 0L) }
    LaunchedEffect(ms) {
        if (ms > 0L) {
            delay(ms)
            armed = true
        }
    }
    TetherKey(onClick = { if (armed) onConfirm() }, classes = KeyClasses.ButtonPrimary, label = label, modifier = Modifier.testTag(tag))
}

/**
 * r2: the Claude CLI switch's confirmation: what runs now and what will run (the picker's own
 * labels, so Auto names what it resolves to), the web's warning in short, Cancel and Switch.
 */
@Composable
internal fun ClaudeCliConfirmDialog(current: String, next: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    TetherDialog(
        onDismiss = onCancel,
        title = ClaudeCliCopy.CONFIRM_TITLE,
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(ServerSettingsTags.CliCancel))
            ArmedConfirmKey(ClaudeCliCopy.CONFIRM_ACTION, ServerSettingsTags.CliConfirm, onConfirm)
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(ServerSettingsTags.CliConfirmSheet), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TetherDialogText(ClaudeCliCopy.CONFIRM_BODY)
            CliField(ClaudeCliCopy.CONFIRM_NOW, current, ServerSettingsTags.CliConfirmNow)
            CliField(ClaudeCliCopy.CONFIRM_NEW, next, ServerSettingsTags.CliConfirmNew)
        }
    }
}

@Composable
private fun CliField(label: String, value: String, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = t.muted, style = settingsText(type.ui, 12f, 600, lineHeight = 1.5f))
        Text(
            value,
            color = t.ink,
            style = settingsText(type.mono, 13f, 400, lineHeight = 1.5f),
            modifier = Modifier
                .testTag(tag)
                .fillMaxWidth()
                .cssSurface(RoundedCornerShape(8.dp), t.mineral, null, emptyList())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** Before the first `server-settings` reply (the web draws nothing then; the app says it is waiting). */
@Composable
internal fun ServerSettingsLoading() {
    RowRule()
    Text(
        AdvancedRows.LOADING,
        color = LocalTetherTokens.current.muted,
        style = settingsText(LocalTetherTypography.current.ui, 12f, 400, lineHeight = 1.6f),
        modifier = Modifier.testTag(ServerSettingsTags.Loading).padding(vertical = 17.dp),
    )
}
