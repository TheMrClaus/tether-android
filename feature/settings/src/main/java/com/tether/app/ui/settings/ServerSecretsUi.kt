package com.tether.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsView
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.Spinner
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.state.rememberRetained
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Tags of the write-only credential rows and their dialog. */
object ServerSecretTags {
    fun opener(s: ServerSetting) = "server-secret-open:${s.key}"
    fun clear(s: ServerSetting) = "server-secret-clear:${s.key}"
    fun saved(s: ServerSetting) = "server-secret-saved:${s.key}"
    fun tag(s: ServerSetting) = "server-secret-tag:${s.key}"
    const val Dialog = "server-secret-dialog"
    fun field(f: SecretField) = "server-secret-field:${f.name.lowercase()}"
    const val Mismatch = "server-secret-mismatch"
    const val Error = "server-secret-error"
    const val Cancel = "server-secret-cancel"
    const val Submit = "server-secret-submit"
}

/** The credential state for one signed-in server, kept through a rotation in memory only and dropped when Settings closes. */
@Composable
fun rememberServerSecretsState(origin: String?): ServerSecretsState = rememberRetained(origin) { ServerSecretsState() }.value

/**
 * One Authentication row (components/server-secret-row.tsx): a WRITE-ONLY status row. Title, env lock, tip and status tag;
 * a one-line caption; the opener key(s) when [SecretRowFacts.canAct]. The opener opens the confirm dialog
 * ([ServerSecretDialog]); a success is a status line under the row, until Settings closes.
 */
@Composable
internal fun ServerSecretRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val s = row.setting
    val facts = SecretRowFacts.of(view, s)
    val secrets = binding.secrets
    val saved = secrets.saved[s]
    val caption = ServerSecretCopy.caption(s, facts.forced, facts.configured, saved)
    val openMode = if (s == ServerSetting.Password) SecretMode.PasswordSet else SecretMode.TokenSet
    val opener = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    val dialog = secrets.dialog?.takeIf { it.mode.setting == s }
    LaunchedEffect(dialog != null) {
        // The dialog closing hands focus back to the key that opened it (the web's `openerRef.focus()`).
        if (wasOpen && dialog == null) runCatching { opener.requestFocus() }
        wasOpen = dialog != null
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            modifier = Modifier.testTag(ServerSettingsTags.row(s)),
            text = { m ->
                SettingsRowText(
                    row.label,
                    AnnotatedString(caption),
                    m,
                    tip = row.tip,
                    locked = facts.forced,
                    tag = ServerSecretCopy.tag(facts.forced, facts.configured),
                )
            },
            control = if (facts.canAct) {
                { m -> SecretKeys(s, openMode, configuredToken = s == ServerSetting.ProxyToken && facts.configured, secrets, opener, narrow, m) }
            } else {
                null
            },
        )
        if (saved != null && (s == ServerSetting.Password && saved == SecretSaved.PasswordChanged || s == ServerSetting.ProxyToken && saved != SecretSaved.PasswordChanged)) {
            SavedLine(ServerSecretCopy.savedLine(saved), Modifier.testTag(ServerSecretTags.saved(s)))
        }
    }
    if (dialog != null) ServerSecretDialog(dialog, secrets, binding)
}

/** The plain status line under a row: `muted`, 13/1.6, 16dp above and below, no glyph; a polite live region (`<p role="status">`). */
@Composable
private fun SavedLine(text: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        color = t.muted,
        style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(vertical = 16.dp),
    )
}

/**
 * The opener key (KeyRound, `Set new password…` / `Set new token…`) and, for a configured token, `Clear`. At 560dp and
 * under the keys share the row equally (8dp apart) and each takes its own line when a legend does not fit its share;
 * wider, they sit at their own width, left-aligned (stacked) or right of the text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecretKeys(
    s: ServerSetting,
    mode: SecretMode,
    configuredToken: Boolean,
    secrets: ServerSecretsState,
    opener: FocusRequester,
    narrow: Boolean,
    modifier: Modifier,
) {
    val stack = LocalSettingsRowsStack.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val share = if (stack) Modifier.weight(1f) else Modifier
        TetherKey(
            onClick = { secrets.open(mode) },
            classes = KeyClasses.ButtonSecondary,
            label = if (s == ServerSetting.Password) ServerSecretCopy.OPEN_PASSWORD else ServerSecretCopy.OPEN_TOKEN,
            icon = TetherIcons.KeyRound,
            iconSize = 14.dp,
            modifier = share.focusRequester(opener).testTag(ServerSecretTags.opener(s)),
        )
        if (configuredToken) {
            TetherKey(
                onClick = { secrets.open(SecretMode.TokenClear) },
                classes = KeyClasses.ButtonSecondary,
                label = ServerSecretCopy.CLEAR,
                modifier = share.testTag(ServerSecretTags.clear(s)),
            )
        }
    }
}

/**
 * The credential confirm dialog (server-secret-row.tsx:179-238) in the app's Studio confirm chrome. Always asks for the
 * Current password, then the new value(s); Save is the web's `canSubmit`. Nothing is checked client-side beyond
 * non-empty and matching: the server judges, and its own message is shown as sent. The scrim does not close it (the
 * web's `<dialog>` backdrop does not); Back does, and wipes everything typed.
 */
@Composable
internal fun ServerSecretDialog(d: SecretDialogState, secrets: ServerSecretsState, binding: ServerSettingsBinding) {
    val t = LocalTetherTokens.current
    val mode = d.mode
    val requesters = remember { SecretField.entries.associateWith { FocusRequester() } }
    LaunchedEffect(d.focus) { d.focus?.let { runCatching { requesters.getValue(it.field).requestFocus() } } }
    val submit = { if (d.canSubmit) binding.submitSecret() }
    TetherDialog(
        onDismiss = { secrets.close() },
        title = mode.title,
        dismissOnScrimTap = false,
        footerContent = {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TetherKey(
                    onClick = { secrets.close() },
                    classes = KeyClasses.ButtonSecondary,
                    label = "Cancel",
                    enabled = !d.sending,
                    modifier = Modifier.testTag(ServerSecretTags.Cancel),
                )
                val legend = if (d.sending) ServerSecretCopy.SAVING else mode.submitLabel
                TetherKey(
                    onClick = submit,
                    classes = if (mode.clear) KeyClasses.ButtonDanger else KeyClasses.ButtonPrimary,
                    label = legend,
                    enabled = d.canSubmit,
                    leading = if (d.sending) ({ Spinner(14.dp, t.white) }) else null,
                    contentArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.testTag(ServerSecretTags.Submit),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(ServerSecretTags.Dialog)) {
            TetherDialogText(mode.body)
            val clearOnly = mode == SecretMode.TokenClear
            SecretInput(
                label = ServerSecretCopy.CURRENT,
                value = d.current,
                onChange = { d.current = it },
                readOnly = d.sending,
                field = SecretField.Current,
                requester = requesters.getValue(SecretField.Current),
                ime = if (clearOnly) ImeAction.Done else ImeAction.Next,
                onDone = submit,
                contentType = ContentType.Password,
            )
            mode.newLabel?.let { label ->
                SecretInput(
                    label = label,
                    value = d.next,
                    onChange = { d.next = it },
                    readOnly = d.sending,
                    field = SecretField.New,
                    requester = requesters.getValue(SecretField.New),
                    ime = if (mode.confirms) ImeAction.Next else ImeAction.Done,
                    onDone = submit,
                    contentType = if (mode.confirms) ContentType.NewPassword else null,
                    note = mode.newNote,
                )
            }
            if (mode.confirms) {
                SecretInput(
                    label = ServerSecretCopy.CONFIRM,
                    value = d.confirm,
                    onChange = { d.confirm = it },
                    readOnly = d.sending,
                    field = SecretField.Confirm,
                    requester = requesters.getValue(SecretField.Confirm),
                    ime = ImeAction.Done,
                    onDone = submit,
                    contentType = ContentType.NewPassword,
                )
                // Plain text, no live region (the web's `<p>`): a mismatch is not announced.
                if (d.mismatch) {
                    Text(
                        ServerSecretCopy.MISMATCH,
                        color = t.warning,
                        style = settingsText(LocalTetherTypography.current.ui, 12f, 600, lineHeight = 1.5f),
                        modifier = Modifier.padding(top = 6.dp).testTag(ServerSecretTags.Mismatch),
                    )
                }
            }
            d.error?.let { ErrorBox(it) }
        }
    }
}

/** `.settings-warning` (`role="alert"`): the server's words (or the client's no-reply copy) in the attention box; icon decorative. */
@Composable
private fun ErrorBox(message: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .padding(top = 12.dp)
            .testTag(ServerSecretTags.Error)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .cssSurface(RoundedCornerShape(8.dp), t.attentionBg, null, emptyList())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 3.dp).size(15.dp))
        Text(message, color = t.attentionInk, style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f))
    }
}

/**
 * One dialog field (`.confirm-dialog input`): the label above (13/650 ink), a 44dp field (graphite, 1dp `line-strong`,
 * 8dp; `violet-strong` focused), masked like a `type="password"` input with no reveal and no copy or cut (paste and
 * autofill stay). The label is drawn inside the field's decorationBox so it merges into the field's own node (its
 * name); no contentDescription masks the value. [readOnly] while sending (not disabled, not dimmed).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SecretInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    readOnly: Boolean,
    field: SecretField,
    requester: FocusRequester,
    ime: ImeAction,
    onDone: () -> Unit,
    contentType: ContentType?,
    note: String? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val style = settingsText(type.ui, 16f, 400, lineHeight = 1.4f)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        NoCopyScope(true) { guard ->
            SensitiveClipScope(false) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    readOnly = readOnly,
                    singleLine = true,
                    textStyle = style.copy(color = t.ink),
                    cursorBrush = SolidColor(t.violet),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ime),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        onDone = { onDone() },
                    ),
                    modifier = guard
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .testTag(ServerSecretTags.field(field))
                        .semantics {
                            password()
                            if (contentType != null) this.contentType = contentType
                        }
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, color = t.ink, style = settingsText(type.ui, 13f, 650, lineHeight = 1.4f))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { inner() }
                        }
                    },
                )
            }
        }
        if (note != null) {
            Text(note, color = t.faint, style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f), modifier = Modifier.padding(top = 6.dp))
        }
    }
}
