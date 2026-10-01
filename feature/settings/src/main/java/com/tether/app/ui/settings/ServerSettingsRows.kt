package com.tether.app.ui.settings

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
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
}

/** A row's caption: "Set by environment" when forced (settings-dialog.tsx:145), else its description. */
private fun caption(forced: Boolean, description: String) = AnnotatedString(if (forced) ServerRowCopy.SET_BY_ENV else description)

/**
 * `.settings-server-input` (studio.css 594-609, 975-979): a 44dp field on the case's graphite with a
 * 1px `--line-strong` edge at 8dp, mono 13 (16 on a phone), the focus edge `--violet-strong`;
 * disabled at 0.55. The value is committed the web's way (ServerTextRow, settings-dialog.tsx:129):
 * on Done (Enter), when the field loses focus, and when it leaves the screen with an edit in it
 * (the web's blur on close). [shown] is what the field was filled with; a server reply with a new
 * value refills it (the web's `key={rawValue}` remount).
 */
@Composable
private fun CommitField(
    shown: String,
    label: String,
    tag: String,
    enabled: Boolean,
    narrow: Boolean,
    placeholder: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it },
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val focusManager = LocalFocusManager.current
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
    DisposableEffect(Unit) { onDispose { latestCommit() } }
    val style = settingsText(type.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    BasicTextField(
        value = text,
        onValueChange = { text = filter(it) },
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
                if (focused && !f.isFocused) commit()
                focused = f.isFocused
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .alpha(if (enabled) 1f else 0.55f)
                    .heightIn(min = 44.dp)
                    .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = t.faint, maxLines = 1)
                inner()
            }
        },
    )
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
 *   starts masked;
 * - forced by env: masked, no Reveal (the web hides its eye then).
 */
@Composable
internal fun ServerSecretRow(row: ServerRow, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
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
                        onCommit = { binding.send(ServerSettingsPatch.text(view, s, it, shown)) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val style = settingsText(type.mono, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
                    Box(
                        Modifier
                            .testTag(ServerSettingsTags.masked(s))
                            .clearAndSetSemantics { contentDescription = ServerRowCopy.maskedDescription(row.label, secret.isEmpty) }
                            .weight(1f)
                            .alpha(if (forced) 0.55f else 1f)
                            .heightIn(min = 44.dp)
                            .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, t.lineStrong), emptyList())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        when {
                            !secret.isEmpty -> Text(ServerRowCopy.MASK, style = style, color = t.ink, maxLines = 1)
                            row.placeholder.isNotEmpty() -> Text(row.placeholder, style = style, color = t.faint, maxLines = 1)
                        }
                    }
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

/** ServerNumberRow (settings-dialog.tsx:164-211): digits only (the number keypad), committed like the text row. */
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
                    filter = { typed -> typed.filter(Char::isDigit).take(MAX_DIGITS) },
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
