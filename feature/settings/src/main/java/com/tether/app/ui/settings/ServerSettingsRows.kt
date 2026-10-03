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
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.serialization.json.JsonObject
import com.tether.app.client.LabelText
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsPatch
import com.tether.app.client.ServerSettingsView
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Tags of the server-settings rows (one per wire key). */
object ServerSettingsTags {
    fun row(s: ServerSetting) = "server-setting:${s.key}"
    fun input(s: ServerSetting) = "server-setting-input:${s.key}"
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
 *
 * ta-coik.5, a secret as the web's `<input type="password">` holds it: [masked] draws each
 * character as a dot (typing into it works, as in the browser) and, as the browser does for a
 * password field, offers no copy or cut then ([NoCopyScope]); revealed, it is a plain field whose
 * copy and cut work. [sensitive]: whatever it puts on the clipboard is marked
 * `ClipDescription.EXTRA_IS_SENSITIVE`, so Android keeps it out of clipboard previews.
 *
 * ta-q6p r2 (security F4): [onCommit] says what became of the value ([CommitOutcome]). A refused
 * send is never silent: the field keeps the text, Done can send it again, and a short note under
 * the field says why.
 */
@Composable
internal fun CommitField(
    shown: String,
    label: String,
    tag: String,
    enabled: Boolean,
    narrow: Boolean,
    placeholder: String,
    onCommit: (String) -> CommitOutcome,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    accept: (String) -> Boolean = { true },
    commitOnBlur: Boolean = true,
    commitOnLeave: Boolean = true,
    masked: Boolean = false,
    sensitive: Boolean = false,
    /** A placeholder drawn as given (server text by the code rule); else [placeholder]. */
    styledPlaceholder: AnnotatedString? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val focusManager = LocalFocusManager.current
    val activity = LocalContext.current.findActivity()
    val recreating = { activity?.isChangingConfigurations == true }
    var text by remember(shown) { mutableStateOf(shown) }
    // The last value handed to [onCommit] for this server value: Done then the focus loss it
    // causes is one write, not two. r2: a refused value is not "sent", so Done can try again.
    var sent by remember(shown) { mutableStateOf<String?>(null) }
    var note by remember(shown) { mutableStateOf<String?>(null) }
    // The value last refused: a focus loss or leaving never retries it (only Done does).
    var refused by remember(shown) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val commit: (Boolean) -> Unit = { retry ->
        if (text != shown && text != sent && (retry || text != refused)) {
            when (val outcome = onCommit(text)) {
                CommitOutcome.Sent, CommitOutcome.Nothing -> {
                    sent = text
                    refused = null
                    note = null
                }
                is CommitOutcome.Refused -> {
                    sent = null
                    refused = text
                    note = outcome.message
                }
            }
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    val leaves by rememberUpdatedState(commitOnLeave)
    DisposableEffect(Unit) { onDispose { if (leaves && !recreating()) latestCommit(false) } }
    val style = settingsText(type.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    Column(modifier) {
        // ta-oqx: a masked secret's copy and cut are closed at their source ([NoCopyScope]), as a
        // browser closes them on a password field; ta-coik.5: a revealed one copies, marked sensitive.
        SensitiveClipScope(sensitive) {
        NoCopyScope(masked) { guard ->
            BasicTextField(
                value = text,
                onValueChange = {
                    if (accept(it)) {
                        if (it != text) note = null
                        text = it
                    }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = style.copy(color = t.ink),
                cursorBrush = SolidColor(t.violet),
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    // A secret or a path is never learned or corrected by the keyboard.
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    commit(true)
                    focusManager.clearFocus()
                }),
                modifier = guard
                    .fillMaxWidth()
                    .testTag(tag)
                    .semantics { contentDescription = label }
                    .onFocusChanged { f ->
                        if (focused && !f.isFocused && commitOnBlur && !recreating()) commit(false)
                        focused = f.isFocused
                    },
                decorationBox = { inner ->
                    val hint = styledPlaceholder ?: placeholder.takeIf { it.isNotEmpty() }?.let(::AnnotatedString)
                    ServerFieldBox(enabled, focused, style, if (text.isEmpty()) hint else null, inner)
                },
            )
        }
        }
        note?.let { n ->
            Text(
                n,
                color = t.attentionInk,
                style = settingsText(type.ui, 12f, 500, lineHeight = 1.5f),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .testTag(CommitFieldTags.note(tag))
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** ta-q6p r2: what became of a committed value. */
sealed interface CommitOutcome {
    /** Sent to the server. */
    data object Sent : CommitOutcome

    /** Nothing to send for it (the server's value already, or not a value the row writes). */
    data object Nothing : CommitOutcome

    /** Not sent, and why (shown under the field). */
    data class Refused(val message: String) : CommitOutcome

    companion object {
        const val NOT_CONNECTED = "Not saved: not connected to the server. Try again."
    }
}

/** ta-q6p r2: the tags of a [CommitField]'s parts. */
object CommitFieldTags {
    fun note(tag: String) = "commit-note:$tag"
}

/** A server-settings row's commit: nothing for no patch, sent, or refused (no live socket for the row's server). */
internal fun serverCommit(patch: JsonObject?, send: (JsonObject) -> Boolean): CommitOutcome = when {
    patch == null -> CommitOutcome.Nothing
    send(patch) -> CommitOutcome.Sent
    else -> CommitOutcome.Refused(CommitOutcome.NOT_CONNECTED)
}

/**
 * ta-dh1: `.settings-server-input`'s box (shared by [CommitField] and the engine fields): the edge, the fill, the placeholder.
 * T10.3: [alignTop] for a multi-line field (the Nodes credential's textarea): text from the top, the placeholder wrapping.
 */
@Composable
internal fun ServerFieldBox(
    enabled: Boolean,
    focused: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    placeholder: AnnotatedString?,
    inner: @Composable () -> Unit,
    alignTop: Boolean = false,
) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.55f)
            .heightIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = if (alignTop) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (placeholder != null) Text(placeholder, style = style, color = t.faint, maxLines = if (alignTop) Int.MAX_VALUE else 1)
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
 * A secret field's copy and cut, closed (ta-78a, ta-oqx). With [on], [content] gets the [guard]
 * modifier to put FIRST on its text field, and runs under a clipboard whose writes are dropped.
 *
 * Every way the text field (foundation 1.12, the String BasicTextField) copies or cuts is closed
 * where it starts, before anything is written or deleted:
 * - its text menu: the new context menu (on by default from 1.12, `ComposeFoundationFlags.
 *   isNewContextMenuEnabled`; it never reads [LocalTextToolbar]) keeps only Paste, Select all and
 *   Autofill ([NoCopyMenu]: Copy, Cut, Share-style process-text and smart-selection items are
 *   dropped, and so is any item a later version adds); the old [TextToolbar] path offers neither
 *   ([NoCopyToolbar]);
 * - hardware keys and an IME's cut or copy (sent to the field as KEYCODE_CUT / KEYCODE_COPY):
 *   consumed before the field sees them ([NoCopyKeys]);
 * - the accessibility Copy and Cut actions: replaced with ones that do nothing, labelled
 *   [NoCopyGuardCopy.ACTION_LABEL] so TalkBack says why.
 * These source guards are THE control. Behind them, a backstop only: the Compose clipboard the field
 * gets drops what is written through it ([noCopyClipboard]; reads, paste, pass). It cannot stop a
 * write that goes around that object (the platform ClipboardManager it must expose for the paste
 * check); foundation 1.12.1 makes none (its bytecode writes only via Clipboard.setClipEntry).
 *
 * ta-oqx N1: with cut stopped at its source nothing is deleted, so nothing has to be put back (the
 * old CutGuard, which restored text from what a refused write carried, is gone).
 * Re-check ALL of this on every Compose upgrade: the menu path (NoCopyGuardTest fails when the
 * field stops using the new context menu or TextToolbar changes), the key mapping, and that
 * foundation still writes only through Clipboard.setClipEntry (a bytecode grep for setPrimaryClip).
 */
@Composable
internal fun NoCopyScope(on: Boolean, content: @Composable (guard: Modifier) -> Unit) {
    if (!on) return content(Modifier)
    val clipboard = LocalClipboard.current
    val toolbar = LocalTextToolbar.current
    val guarded = remember(clipboard) { noCopyClipboard(clipboard) }
    val menu = remember(toolbar) { NoCopyToolbar(toolbar) }
    CompositionLocalProvider(LocalClipboard provides guarded, LocalTextToolbar provides menu) { content(NoCopyGuard) }
}

/**
 * ta-coik.5: a secret field whose copy works (the web's revealed input, a plain one holding a
 * credential): with [on], [content] runs under a clipboard that marks every clip it writes
 * `ClipDescription.EXTRA_IS_SENSITIVE` (Android then keeps it out of the clipboard preview and
 * overlay). Not a gate: the copy happens, on the first tap, as in the browser.
 */
@Composable
internal fun SensitiveClipScope(on: Boolean, content: @Composable () -> Unit) {
    if (!on) return content()
    val clipboard = LocalClipboard.current
    val marked = remember(clipboard) { sensitiveClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides marked) { content() }
}

/** [delegate], every written clip marked sensitive (an [AndroidClipboard] stays one: the field's paste check reads its manager). */
@SuppressLint("VisibleForTests")
internal fun sensitiveClipboard(delegate: Clipboard): Clipboard =
    if (delegate is AndroidClipboard) SensitiveAndroidClipboard(delegate) else SensitivePlainClipboard(delegate)

/** Marks [entry]'s clip sensitive (the platform key; minSdk has it). */
internal fun markSensitive(entry: ClipEntry?): ClipEntry? {
    val data = entry?.clipData ?: return entry
    val extras = data.description.extras ?: PersistableBundle()
    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    data.description.extras = extras
    return entry
}

@SuppressLint("VisibleForTests")
private class SensitiveAndroidClipboard(private val delegate: AndroidClipboard) : AndroidClipboard {
    override val clipboardManager: android.content.ClipboardManager get() = delegate.clipboardManager
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = delegate.setClipEntry(markSensitive(clipEntry))
}

private class SensitivePlainClipboard(private val delegate: Clipboard) : Clipboard {
    override val nativeClipboard: android.content.ClipboardManager get() = delegate.nativeClipboard
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = delegate.setClipEntry(markSensitive(clipEntry))
}

/**
 * The secret field's own guard ([NoCopyScope]). It must come before the field's other modifiers:
 * the menu filter and the key handler work from an ancestor of the field's own nodes, and the
 * outermost semantics win over the field's own Copy and Cut actions.
 */
internal val NoCopyGuard: Modifier = Modifier
    .filterTextContextMenuComponents { NoCopyMenu.keeps(it) }
    .onPreviewKeyEvent { NoCopyKeys.copiesOrCuts(it) }
    .semantics {
        // P4-4: still listed (an action cannot be removed), but named for what it is, and it does nothing.
        copyText(label = NoCopyGuardCopy.ACTION_LABEL) { false }
        cutText(label = NoCopyGuardCopy.ACTION_LABEL) { false }
    }

/** The words of the guard. */
internal object NoCopyGuardCopy {
    /** The name TalkBack gives a secret field's (inert) Copy and Cut actions. */
    const val ACTION_LABEL = "Copying is off for this field"
}

/** The new text menu's items a secret field keeps: none of them reads the text. */
internal object NoCopyMenu {
    private val kept = setOf(TextContextMenuKeys.PasteKey, TextContextMenuKeys.SelectAllKey, TextContextMenuKeys.AutofillKey)

    fun keeps(component: TextContextMenuComponent): Boolean = component.key in kept
}

/**
 * The keys the text field turns into COPY or CUT (foundation 1.12 KeyMapping: Ctrl+C, Ctrl+Insert,
 * Ctrl+X, KEYCODE_COPY, KEYCODE_CUT; Meta taken as Ctrl too). Shift+Insert (paste) and Shift+Delete
 * (a plain deletion there) stay.
 */
internal object NoCopyKeys {
    fun copiesOrCuts(e: KeyEvent): Boolean {
        val k = e.key
        if (k == Key.Copy || k == Key.Cut) return true
        val shortcut = e.isCtrlPressed || e.isMetaPressed
        return shortcut && (k == Key.C || k == Key.X || k == Key.Insert || k == Key.NumPadInsert)
    }
}

/**
 * The backstop clipboard of a secret field (the source guards in [NoCopyScope] are the control):
 * reads pass (paste); a write through THIS object is dropped, whether [delegate] is an
 * [AndroidClipboard] or not. An [AndroidClipboard] stays one, so it still exposes the platform
 * manager (the text field's paste check reads it): a write made straight to that manager is not
 * stopped here. Foundation 1.12.1 makes none; re-check that on every Compose upgrade.
 */
// AndroidClipboard is marked @VisibleForTesting, but the text field requires it (a plain Clipboard
// throws in its paste check), so the guard implements it.
@SuppressLint("VisibleForTests")
internal fun noCopyClipboard(delegate: Clipboard): Clipboard =
    if (delegate is AndroidClipboard) NoCopyAndroidClipboard(delegate) else NoCopyPlainClipboard(delegate)

@SuppressLint("VisibleForTests")
private class NoCopyAndroidClipboard(private val delegate: AndroidClipboard) : AndroidClipboard {
    override val clipboardManager: android.content.ClipboardManager get() = delegate.clipboardManager
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}

private class NoCopyPlainClipboard(private val delegate: Clipboard) : Clipboard {
    override val nativeClipboard: android.content.ClipboardManager get() = delegate.nativeClipboard
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}

/**
 * The old [TextToolbar] menu (the new context menu turned off) of a secret field: no Copy, no Cut.
 * Implemented member by member, never `by delegate`: a member a later Compose adds to [TextToolbar]
 * must not reach the platform toolbar unfiltered. NoCopyGuardTest fails when the interface changes.
 */
internal class NoCopyToolbar(private val delegate: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = delegate.status

    override fun hide() = delegate.hide()

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
                onCommit = { serverCommit(ServerSettingsPatch.text(view, s, it, shown), binding::send) },
                modifier = m.serverInputWidth(narrow),
            )
        },
    )
}

/**
 * ServerTextRow with `password` (settings-dialog.tsx 90fbb9f :114-160) for `password` /
 * `proxyToken`, which the server sends in plaintext. ta-coik.5, the web's behaviour:
 * - masked by default (`type="password"`): each character a dot, typed into directly; as in the
 *   browser, a masked field offers no copy or cut;
 * - Reveal (the web's eye, `aria-label` "Reveal" / "Hide") shows it as plain text; copy and cut
 *   work then, and a copy is marked sensitive ([SensitiveClipScope]);
 * - committed as the web's text row does: Done, a focus loss, or leaving with an edit in it (never
 *   on a configuration change);
 * - forced by env: the field is disabled and masked, with no Reveal (the web hides its eye then).
 * The reveal flag is plain `remember` (the panel is keyed on the server: another server starts
 * masked); the dialog's window is FLAG_SECURE.
 */
@Composable
internal fun ServerSecretRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val forced = view.forced(s)
    val secret = view.secret(s)
    var revealed by remember { mutableStateOf(false) }
    val open = revealed && !forced
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ServerSettingsTags.row(s)),
        text = { m -> SettingsRowText(row.label, caption(forced, row.description), m, tip = row.tip, locked = forced) },
        control = { m ->
            Row(m.serverInputWidth(narrow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The field is filled with the server's value as it is (the web's `defaultValue`).
                val shown = secret.reveal()
                CommitField(
                    shown = shown,
                    label = row.label,
                    tag = ServerSettingsTags.input(s),
                    enabled = !forced,
                    narrow = narrow,
                    placeholder = row.placeholder,
                    keyboardType = KeyboardType.Password,
                    masked = !open,
                    sensitive = true,
                    onCommit = { serverCommit(ServerSettingsPatch.text(view, s, it, shown), binding::send) },
                    modifier = Modifier.weight(1f),
                )
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
 * ServerNumberRow (settings-dialog.tsx:164-211), committed like the text row: the field takes the
 * characters a browser's number input takes (digits, `.`, `e`/`E`, `+`, `-`), and the commit reads
 * it as that input's value ([ServerSettingsPatch.number]: "6e4" is 60000).
 */
@Composable
internal fun ServerNumberRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val forced = view.forced(s)
    val shown = view.numberText(s)
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
                    keyboardType = KeyboardType.Decimal,
                    accept = { typed -> typed.all { it in NUMBER_CHARS } },
                    onCommit = { serverCommit(ServerSettingsPatch.number(view, s, it), binding::send) },
                    modifier = Modifier.widthIn(max = 144.dp).fillMaxWidth(),
                )
            }
        },
    )
}

/** The characters an `<input type="number">` lets the operator type. */
private const val NUMBER_CHARS = "0123456789.eE+-"

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
 * `advanced-settings` reply). Its writes are `set-advanced-settings`: a pick is sent at once, as
 * the web's `onSetCliVersion` does (:2437; ta-coik.5: no app-only confirmation).
 */
@Composable
internal fun ClaudeCliSection(binding: ServerSettingsBinding, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val advanced = binding.advanced
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
                            onSelect = { choice -> advanced?.let { binding.sendCli(ServerSettingsPatch.cliVersion(it, choice.value)) } },
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
