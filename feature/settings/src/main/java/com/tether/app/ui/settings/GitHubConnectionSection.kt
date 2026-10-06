package com.tether.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.ClaudeLoginLink
import com.tether.app.client.GitHubDevicePoll
import com.tether.app.client.GitHubDeviceStatus
import com.tether.app.client.LabelText
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendSafe
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Tags of the GitHub connection section. */
object GitHubTags {
    const val Loading = "github-loading"
    const val Status = "github-status"
    const val Retry = "github-retry"
    const val Recheck = "github-recheck"
    const val Remove = "github-remove-token"
    const val ActionError = "github-action-error"
    const val Start = "github-start-device"
    const val CodeRow = "github-code-row"
    const val Code = "github-code"
    const val Open = "github-open"
    const val OpenNote = "github-open-note"
    const val OpenHost = "github-open-host"
    const val LinkRefused = "github-link-refused"
    const val Cancel = "github-cancel-device"
    const val DeviceError = "github-device-error"
    const val Pat = "github-pat"
    const val TokenCaption = "github-token-caption"
    const val Token = "github-token"
    const val Verify = "github-verify"
    const val TokenError = "github-token-error"
}

/**
 * What Advanced needs for the GitHub connection: the section's [controller] (held by
 * [GitHubConnectionViewModel] in the app; built from a seed by the goldens) and where the device
 * page is opened ([opener], the phone's browser). A null [controller] (previews) draws the heading only.
 */
data class GitHubBinding(
    val controller: GitHubConnectionController?,
    val opener: LoginLinkOpener = LoginLinkOpener.None,
) {
    companion object {
        val None = GitHubBinding(null)
    }
}

/**
 * settings-dialog.tsx 90fbb9f :1082-1166 `GitHubConnectionSection`, in the web's order: the Status row
 * (or "Loading GitHub status…", or the read's error with Retry) with Remove Tether token / Re-check;
 * the action's error; then, while not connected, Connect in the app (Start device flow), the one-time
 * code with its open link and Cancel, the device flow's error, Personal access token, and the Token
 * row (the web's password input with Verify & save) with its error.
 */
@Composable
internal fun GitHubConnectionSection(binding: GitHubBinding, narrow: Boolean) {
    val type = LocalTetherTypography.current
    val caption = remember(type) {
        // AdvancedRows.GITHUB_CAPTION with its `<code>gh</code>` (:1085) in the mono face.
        val (before, after) = AdvancedRows.GITHUB_CAPTION.split(" gh ", limit = 2).let { it[0] to it[1] }
        buildAnnotatedString {
            append("$before ")
            withStyle(SpanStyle(fontFamily = type.mono)) { append("gh") }
            append(" $after")
        }
    }
    SettingsSection(AdvancedRows.GITHUB, caption, narrow, modifier = Modifier.testTag(ServerSettingsTags.GitHub)) {
        val c = binding.controller ?: return@SettingsSection
        GitHubRows(c, narrow, binding.opener)
    }
}

@Composable
private fun GitHubRows(c: GitHubConnectionController, narrow: Boolean, opener: LoginLinkOpener) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val status = c.status
    val error = c.error
    when {
        c.loading -> SettingsRow(narrow = narrow, text = { m ->
            androidx.compose.material3.Text(
                GitHubCopy.LOADING,
                color = t.muted,
                style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                modifier = m.testTag(GitHubTags.Loading),
            )
        })
        error != null -> SettingsRow(
            narrow = narrow,
            text = { m -> SettingsRowText(GitHubCopy.STATUS, AnnotatedString(error), m.testTag(GitHubTags.Status).semantics { liveRegion = LiveRegionMode.Polite }) },
            control = { m ->
                TetherKey(onClick = c::loadStatus, classes = KeyClasses.ButtonSecondary, label = GitHubCopy.RETRY, icon = TetherIcons.RefreshCw, iconSize = 14.dp, modifier = m.testTag(GitHubTags.Retry))
            },
        )
        status != null -> {
            // :1105-1113: Remove Tether token for a managed token, Re-check while not connected, else nothing.
            val removeKey: @Composable (Modifier) -> Unit = { m ->
                TetherKey(
                    onClick = { c.disconnect() },
                    enabled = !c.busy,
                    classes = KeyClasses.ButtonSecondary,
                    label = if (c.busy) GitHubCopy.REMOVING else GitHubCopy.REMOVE_TOKEN,
                    modifier = m.testTag(GitHubTags.Remove),
                )
            }
            val recheckKey: @Composable (Modifier) -> Unit = { m ->
                TetherKey(onClick = c::loadStatus, enabled = !c.busy, classes = KeyClasses.ButtonSecondary, label = GitHubCopy.RECHECK, icon = TetherIcons.RefreshCw, iconSize = 14.dp, modifier = m.testTag(GitHubTags.Recheck))
            }
            SettingsRow(
                narrow = narrow,
                text = { m -> SettingsRowText(GitHubCopy.STATUS, AnnotatedString(GitHubCopy.statusLine(status)), m.testTag(GitHubTags.Status).semantics { liveRegion = LiveRegionMode.Polite }) },
                control = when {
                    c.connected && status.managedToken -> removeKey
                    !c.connected -> recheckKey
                    else -> null
                },
            )
        }
    }
    c.actionError?.let { Note(it, GitHubTags.ActionError) }

    if (c.connected) return
    SettingsRow(
        narrow = narrow,
        text = { m -> SettingsRowText(GitHubCopy.CONNECT_TITLE, AnnotatedString(GitHubCopy.CONNECT_CAPTION), m) },
        control = { m ->
            TetherKey(
                onClick = { c.startDevice() },
                enabled = !c.busy && !c.deviceFlow,
                classes = KeyClasses.ButtonSecondary,
                label = if (c.deviceFlow) GitHubCopy.WAITING else GitHubCopy.START,
                modifier = m.testTag(GitHubTags.Start),
            )
        },
    )
    val poll = c.poll
    if (c.deviceFlow && poll != null) CodeRow(poll, narrow, opener, c::cancelDevice)
    if (poll?.status == GitHubDeviceStatus.Error) Note(poll.error?.let(LabelText::error)?.takeIf { it.isNotEmpty() } ?: GitHubCopy.DEVICE_FAILED, GitHubTags.DeviceError)

    val patCaption = remember(type) {
        buildAnnotatedString {
            val code = SpanStyle(fontFamily = type.mono)
            append("Stored server-side at ")
            withStyle(code) { append("state/github-token") }
            append(" (0600). Requires ")
            withStyle(code) { append("repo") }
            append(", ")
            withStyle(code) { append("read:org") }
            append(", ")
            withStyle(code) { append("gist") }
            append(" scopes.")
        }
    }
    SettingsRow(narrow = narrow, text = { m -> SettingsRowText(GitHubCopy.PAT_TITLE, patCaption, m.testTag(GitHubTags.Pat)) })
    SettingsRow(
        narrow = narrow,
        text = { m ->
            SettingsRowText(
                GitHubCopy.TOKEN_TITLE,
                AnnotatedString(if (c.tokenSaved) GitHubCopy.TOKEN_SAVED else GitHubCopy.TOKEN_HINT),
                m.testTag(GitHubTags.TokenCaption).semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        control = { m ->
            // `.settings-server-input-wrap` (studio.css :593, :611, :977): the field and its key, 8dp apart.
            Row(m.serverFieldWidth(narrow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenField(c.tokenInput, c::editToken, narrow, Modifier.weight(1f))
                TetherKey(
                    onClick = { c.saveToken() },
                    enabled = !c.busy && c.tokenInput.isNotBlank(),
                    classes = KeyClasses.ButtonSecondary,
                    label = if (c.busy) GitHubCopy.VERIFYING else GitHubCopy.VERIFY,
                    modifier = Modifier.testTag(GitHubTags.Verify),
                )
            }
        },
    )
    c.tokenError?.let { Note(it, GitHubTags.TokenError) }
}

/**
 * :1126-1139, the One-time code row: "Enter <code> at <address>." (or the wait for gh), the web's
 * `open` link (here a key, opened in the phone's browser as the web's `target="_blank"` link opens it:
 * [LoginLinkOpener], the shared Chrome rules) and Cancel. The sentence is selectable, as the page's
 * text is, so the code can be copied to the browser on this same phone.
 *
 * r2 (security review): the address in the sentence is the one Open launches (the parsed link's
 * canonical URL), and its host is drawn beside Open (the Claude login row's rule, its r2 P3-1): more
 * to read, no gate. An address a browser will not open (the shared link rule) has no Open: the row
 * says so (the Claude login row's LINK_REFUSED). r3: an address of any length is kept whole and opened.
 */
@Composable
private fun CodeRow(poll: GitHubDevicePoll, narrow: Boolean, opener: LoginLinkOpener, onCancel: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val code = poll.deviceCode
    // `poll.verificationUri || "https://github.com/login/device"`, whole, of any length (r3).
    val address = poll.verificationUri ?: GitHubCopy.DEFAULT_DEVICE_URL
    // The web's `<a href>` opens any address the server sends (:1134); the shared link rule keeps out only
    // what a browser never opens from a page.
    val link = remember(address) { ClaudeLoginLink.parse(address) }
    var unopened by remember(address) { mutableStateOf<String?>(null) }
    val sentence = remember(code, link, t, type) {
        buildAnnotatedString {
            if (code != null) {
                append("Enter ")
                withStyle(SpanStyle(fontFamily = type.mono)) { appendSafe(code, SafeText.Rule.Line, tokenStyle(t)) }
                if (link != null) {
                    append(" at ")
                    appendSafe(link.url, SafeText.Rule.Line, tokenStyle(t))
                }
                append(".")
            } else {
                append(GitHubCopy.WAITING_CODE)
            }
        }
    }
    Column(Modifier.testTag(GitHubTags.CodeRow)) {
        SettingsRow(
            narrow = narrow,
            text = { m ->
                SelectionContainer(m) {
                    SettingsRowText(GitHubCopy.CODE_TITLE, sentence, Modifier.testTag(GitHubTags.Code).semantics { liveRegion = LiveRegionMode.Polite })
                }
            },
            control = { m ->
                Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (link != null) {
                        if (link.web) {
                            androidx.compose.material3.Text(
                                link.shownHost,
                                color = t.muted,
                                maxLines = 2,
                                style = settingsText(type.mono, 12f, 400, lineHeight = 1.5f),
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp).testTag(GitHubTags.OpenHost),
                            )
                        }
                        TetherKey(
                            onClick = {
                                unopened = when {
                                    opener.open(link) -> null
                                    link.web -> GitHubCopy.LINK_UNOPENED
                                    else -> GitHubCopy.LINK_UNOPENED_APP
                                }
                            },
                            classes = KeyClasses.ButtonSecondary,
                            label = GitHubCopy.OPEN,
                            icon = TetherIcons.ExternalLink,
                            iconSize = 12.dp,
                            contentDescription = if (link.web) "Open the GitHub device page in the browser (${link.shownHost})" else "Open the GitHub device page",
                            modifier = Modifier.testTag(GitHubTags.Open),
                        )
                    }
                    TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = GitHubCopy.CANCEL, contentDescription = "Cancel the GitHub device flow", modifier = Modifier.testTag(GitHubTags.Cancel))
                }
            },
        )
        if (link == null) NoteLine(AccountsLine(GitHubCopy.LINK_REFUSED, error = true), GitHubTags.LinkRefused)
        unopened?.let { NoteLine(AccountsLine(it, error = true), GitHubTags.OpenNote) }
    }
}

/**
 * :1148-1157, `<input type="password" autoComplete="off" spellCheck={false} placeholder="ghp_…">`:
 * masked, typed (and pasted) into directly; as a browser does for a password field, no copy or cut
 * ([NoCopyScope], ta-coik.5's rule), and no reveal (the web's input has none). The keyboard learns and
 * corrects nothing. Enter does nothing more than close the keyboard (the web's input is in no form).
 * The text is the controller's, in memory only (the window is not secure, as the web's page is not, ta-coik.65).
 */
@Composable
private fun TokenField(value: String, onChange: (String) -> Unit, narrow: Boolean, modifier: Modifier) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    val style = serverFieldStyle(narrow)
    NoCopyScope(true) { guard ->
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = guard
                .then(modifier)
                .testTag(GitHubTags.Token)
                .semantics {
                    contentDescription = GitHubCopy.TOKEN_FIELD
                    password()
                }
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                ServerFieldBox(true, focused, style, if (value.isEmpty()) AnnotatedString(GitHubCopy.TOKEN_PLACEHOLDER) else null, inner)
            },
        )
    }
}

/** `<p className="settings-row settings-field-note is-warning">` (:1116, :1140, :1163): a row with the warning line. */
@Composable
private fun Note(text: String, tag: String) {
    Column {
        RowRule()
        NoteLine(AccountsLine(text, error = true), tag)
    }
}
