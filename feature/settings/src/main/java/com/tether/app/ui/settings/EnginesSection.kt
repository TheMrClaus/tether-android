package com.tether.app.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.EngineCard
import com.tether.app.client.LabelText
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsPatch
import com.tether.app.client.ServerSettingsView
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.ProviderLogoDefaults
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.styledDisplay
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.delay

/** Tags of the Engines tab's controls. */
object EngineTags {
    const val Section = "server-settings-section:engines"
    const val Scan = "engines-scan"
    const val HostConfig = "server-settings-section:host-config"
    fun card(e: EngineCard) = "engine-card:${e.id}"
    fun status(e: EngineCard) = "engine-status:${e.id}"
    fun switch(e: EngineCard) = "engine-switch:${e.id}"
    fun useDetected(e: EngineCard) = "engine-use-detected:${e.id}"
    fun unsaved(s: ServerSetting) = "engine-unsaved:${s.key}"
    const val ConfirmSheet = "engine-confirm"
    const val ConfirmNow = "engine-confirm-now"
    const val ConfirmNew = "engine-confirm-new"
    const val ConfirmTrimmed = "engine-confirm-trimmed"
    const val Confirm = "engine-confirm-change"
    const val Cancel = "engine-confirm-cancel"
}

/**
 * The Engines section and the engine cards (settings-dialog.tsx 887c222 :2107-2236), for ONE server
 * (the panel keys this on [ServerSettingsBinding.origin], so another server's cards start from
 * nothing: no half-typed field, no pending confirmation).
 *
 * Writes, per the owner's rules (2026-10-01):
 * - an engine's switch (`headlessModes`) writes at once, as on the web;
 * - its home, command and launch command set what the server RUNS: a field never writes on its
 *   own (not on a focus loss, a close, a tab change or a recreation). Done (or "Use detected") only
 *   opens [EngineConfirmDialog], which shows the current and the new value by the code rule (every
 *   hidden or reordering character a visible token); its Change key builds the write at that moment
 *   from the latest frame, so a key the environment forced meanwhile sends nothing. Cancel, Back
 *   and a tap outside send nothing; the confirmation goes when the frame, the server or the key's
 *   freedom does. Nothing here is saved state, so a recreation drops every edit.
 */
@Composable
internal fun EngineCards(binding: ServerSettingsBinding, narrow: Boolean) {
    // No server, nothing drawn (the web draws the tab only once `serverSettings` arrives).
    val view = binding.settings?.takeIf { binding.origin != null }
    if (view == null) {
        SettingsSection(EngineRows.TITLE, AnnotatedString(EngineRows.CAPTION), narrow, modifier = Modifier.testTag(EngineTags.Section)) { ServerSettingsLoading() }
        return
    }
    var pending by remember { mutableStateOf<EngineEdit?>(null) }
    SettingsSection(EngineRows.TITLE, AnnotatedString(EngineRows.CAPTION), narrow, modifier = Modifier.testTag(EngineTags.Section)) {
        ScanAgain(binding, view)
    }
    EngineCard.entries.forEach { engine ->
        EngineCardView(engine, view, binding, narrow, onReview = { pending = it })
    }
    PendingEngineEdit(pending, binding) { pending = null }
}

/**
 * "Scan again" (:2122): `detect-engines`, disabled when the environment sets the engines (the web's
 * rule). r1: also busy (disabled, "Scanning…") from the send until the server's next
 * `server-settings` reply ([ServerSettingsBinding.replies]) or [EngineRows.SCAN_TIMEOUT_MS], so
 * one tap is one scan, a double tap in one frame included.
 */
@Composable
private fun ScanAgain(binding: ServerSettingsBinding, view: ServerSettingsView) {
    val t = LocalTetherTokens.current
    val latest by rememberUpdatedState(binding)
    var sentAt by remember { mutableStateOf<Long?>(null) }
    val busy = sentAt != null && sentAt == binding.replies
    LaunchedEffect(sentAt) {
        val at = sentAt ?: return@LaunchedEffect
        delay(EngineRows.SCAN_TIMEOUT_MS)
        if (sentAt == at) sentAt = null
    }
    TetherKey(
        onClick = {
            val b = latest
            val at = sentAt
            if (at == null || at != b.replies) {
                val replies = b.replies
                if (b.scan()) sentAt = replies
            }
        },
        classes = KeyClasses.ButtonSecondary,
        label = if (busy) EngineRows.SCANNING else EngineRows.SCAN,
        icon = TetherIcons.RefreshCw,
        iconSize = 14.dp,
        enabled = !view.forced(ServerSetting.HeadlessModes) && !busy,
        modifier = Modifier.padding(top = t.css.spaceSm).testTag(EngineTags.Scan),
    )
}

/** `.engine-card` (globals.css 3118-3134, studio.css 638-645, 984): the head, then the body's rows. */
@Composable
private fun EngineCardView(engine: EngineCard, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean, onReview: (EngineEdit) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val latest by rememberUpdatedState(binding)
    val det = view.detection(engine)
    val on = engine.id in view.headlessModes
    val homeForced = view.forced(engine.home)
    val needsHome = view.needsHome(engine)
    val switchable = !view.forced(ServerSetting.HeadlessModes) && !(needsHome && !homeForced)
    Column(
        Modifier
            .testTag(EngineTags.card(engine))
            .padding(start = if (narrow) 20.dp else 28.dp, end = if (narrow) 20.dp else 28.dp, bottom = 20.dp)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line), emptyList())
            .padding(if (narrow) 16.dp else 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // `.provider-glyph` under Studio (studio.css 340): the raised graphite tile, 0.45rem corners.
            Box(Modifier.size(ProviderLogoDefaults.GlyphSize).cssSurface(RoundedCornerShape(7.2.dp), t.graphiteRaised, null, emptyList()), contentAlignment = Alignment.Center) {
                ProviderLogo(engine.id, fallback = engine.glyph, color = ProviderLogoDefaults.color(engine.id))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(engine.label, color = t.ink, style = settingsText(type.ui, 15f, 700, lineHeight = 1.5f))
                Text(
                    EngineRows.status(det),
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                    modifier = Modifier.testTag(EngineTags.status(engine)),
                )
            }
            // `.settings-toggle.engine-toggle` (:2147-2160): `role="switch"`, named "Enable <engine>";
            // disabled at 0.45 when the environment sets the engines or a needed home is missing.
            Box(
                Modifier
                    .testTag(EngineTags.switch(engine))
                    .alpha(if (switchable) 1f else 0.45f)
                    .toggleable(
                        value = on,
                        enabled = switchable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Switch,
                        onValueChange = {
                            val b = latest
                            val v = b.settings?.takeIf { b.origin != null }
                            if (v != null) b.send(ServerSettingsPatch.headlessMode(v, engine))
                        },
                    )
                    .semantics { contentDescription = EngineRows.switchLabel(engine) }
                    .sizeIn(minWidth = 48.dp, minHeight = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                SettingsSwitchTrack(on)
            }
        }
        // `.engine-card-body`: a rule, then the rows (the first without its own rule).
        Spacer(Modifier.height(t.css.spaceSm + 12.dp))
        RowRule()
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
            EngineValueRow(
                engine, engine.home, view, narrow, onReview,
                title = EngineRows.HOME,
                caption = AnnotatedString(EngineRows.homeCaption(view, engine)),
                placeholder = det?.configDir ?: EngineRows.HOME_PLACEHOLDER,
                first = true,
            )
            val detected = EngineRows.useDetected(view, engine)
            if (detected != null) {
                SettingsRow(
                    narrow = narrow,
                    text = { m ->
                        val caption = buildAnnotatedString { withStyle(SpanStyle(color = t.running)) { append(codeLabel(detected.value)) } }
                        SettingsRowText(EngineRows.DETECTED_HOME, caption, m)
                    },
                    control = { m ->
                        TetherKey(
                            onClick = { latest.settings?.takeIf { latest.origin != null }?.let { EngineRows.useDetected(it, engine) }?.let(onReview) },
                            classes = KeyClasses.ButtonSecondary,
                            label = EngineRows.USE_DETECTED,
                            modifier = m.testTag(EngineTags.useDetected(engine)),
                        )
                    },
                )
            }
            EngineValueRow(
                engine, engine.command, view, narrow, onReview,
                title = EngineRows.COMMAND,
                caption = AnnotatedString(EngineRows.commandCaption(view, engine)),
                placeholder = det?.binPath ?: engine.id,
            )
            val launch = engine.launch
            if (launch != null) {
                val caption = if (view.forced(launch)) {
                    AnnotatedString(ServerRowCopy.SET_BY_ENV)
                } else {
                    buildAnnotatedString {
                        append("Full wrapper command — its last token is the claude binary, e.g. ")
                        withStyle(SpanStyle(fontFamily = type.mono)) { append(EngineRows.LAUNCH_EXAMPLE) }
                        append(". Applies to every spawned session.")
                    }
                }
                EngineValueRow(engine, launch, view, narrow, onReview, title = EngineRows.LAUNCH, caption = caption, placeholder = EngineRows.LAUNCH_EXAMPLE)
            }
        }
    }
}

/**
 * One home / command / launch command row: the field holds the server's value cleaned of hidden
 * characters (the edit-field rule, as slice 3's text rows) and is filled again when the server's
 * value changes (the web's `key={value}` remount). Its text is plain `remember`, never saved state.
 * Done hands [EngineRows.review]'s edit to [onReview] (the confirmation); nothing else sends.
 * While the field holds an edit not yet confirmed, its caption says so.
 */
@Composable
private fun EngineValueRow(
    engine: EngineCard,
    setting: ServerSetting,
    view: ServerSettingsView,
    narrow: Boolean,
    onReview: (EngineEdit) -> Unit,
    title: String,
    caption: AnnotatedString,
    placeholder: String,
    first: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val forced = view.forced(setting)
    val raw = view.text(setting)
    val shown = remember(raw) { LabelText.withoutHidden(raw) }
    var text by remember(shown) { mutableStateOf(shown) }
    val latestView by rememberUpdatedState(view)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val edited = !forced && text != shown
    SettingsRow(
        narrow = narrow,
        rule = !first,
        modifier = Modifier.testTag(ServerSettingsTags.row(setting)),
        text = { m ->
            val shownCaption = if (edited) AnnotatedString(EngineRows.UNSAVED) else caption
            SettingsRowText(title, shownCaption, m.then(if (edited) Modifier.testTag(EngineTags.unsaved(setting)) else Modifier), locked = forced)
        },
        control = { m ->
            val style = serverFieldStyle(narrow)
            BasicTextField(
                value = text,
                // Never past the server's limit (it would refuse the write).
                onValueChange = { if (ServerSettingsPatch.fits(setting, it)) text = it },
                enabled = !forced,
                singleLine = true,
                textStyle = style.copy(color = t.ink),
                cursorBrush = SolidColor(t.violet),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    EngineRows.review(latestView, engine, setting, text, shown)?.let(onReview)
                    focusManager.clearFocus()
                }),
                modifier = m
                    .serverFieldWidth(narrow)
                    .testTag(ServerSettingsTags.input(setting))
                    .semantics { contentDescription = EngineRows.fieldLabel(engine, setting) }
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    // The placeholder is server text (a detected path): drawn by the code rule.
                    ServerFieldBox(!forced, focused, style, if (text.isEmpty()) codeLabel(placeholder) else null, inner)
                },
            )
        },
    )
}

/** The pending edit, confirmed or dropped. */
@Composable
private fun PendingEngineEdit(pending: EngineEdit?, binding: ServerSettingsBinding, onDone: () -> Unit) {
    val edit = pending ?: return
    // The frame went away (signed out), or the environment now forces the key: the edit goes too.
    val view = binding.settings?.takeIf { binding.origin != null }
    if (view == null || view.forced(edit.setting)) return SideEffect { onDone() }
    val latest by rememberUpdatedState(binding)
    // One confirmation is one write, a double tap in one frame included.
    val fired = remember(edit) { booleanArrayOf(false) }
    EngineConfirmDialog(
        edit = edit,
        now = view.text(edit.setting),
        onConfirm = {
            if (!fired[0]) {
                fired[0] = true
                // Built NOW, from the latest frame: an env lock or the same value sends nothing.
                val b = latest
                val v = b.settings?.takeIf { b.origin != null }
                if (v != null) b.send(ServerSettingsPatch.engineValue(v, edit.setting, edit.value))
            }
            onDone()
        },
        onCancel = onDone,
    )
}

/**
 * The confirmation for what the server runs: the current value and the one that will be sent,
 * each drawn by the code rule (every bidi control and invisible character a visible ⟨U+…⟩ token,
 * an LTR paragraph, wrapped anywhere but inside a token), so what is confirmed is what is sent.
 */
@Composable
internal fun EngineConfirmDialog(edit: EngineEdit, now: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    TetherDialog(
        onDismiss = onCancel,
        title = EngineRows.confirmTitle(edit),
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(EngineTags.Cancel))
            TetherKey(onClick = onConfirm, classes = KeyClasses.ButtonPrimary, label = EngineRows.confirmAction(edit), modifier = Modifier.testTag(EngineTags.Confirm))
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(EngineTags.ConfirmSheet), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TetherDialogText(EngineRows.confirmBody(edit))
            EngineValueField(EngineRows.CONFIRM_NOW, now, EngineRows.emptyValue(edit.engine, edit.setting), EngineTags.ConfirmNow)
            EngineValueField(EngineRows.CONFIRM_NEW, edit.value, EngineRows.emptyValue(edit.engine, edit.setting), EngineTags.ConfirmNew)
            if (edit.trimmed) {
                Text(
                    EngineRows.TRIMMED,
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                    modifier = Modifier.testTag(EngineTags.ConfirmTrimmed),
                )
            }
        }
    }
}

@Composable
private fun EngineValueField(label: String, value: String, empty: String, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = t.muted, style = settingsText(type.ui, 12f, 600, lineHeight = 1.5f))
        val shown = if (value.isEmpty()) {
            AnnotatedString(empty)
        } else {
            remember(value, t) {
                AnnotatedString.Builder(value.length).apply {
                    withStyle(ParagraphStyle(textDirection = codeDirection)) {
                        append(styledDisplay(SafeText.breakAnywhere(SafeText.line(value)), tokenStyle(t)))
                    }
                }.toAnnotatedString()
            }
        }
        Text(
            shown,
            color = if (value.isEmpty()) t.muted else t.ink,
            style = settingsText(type.mono, 13f, 400, lineHeight = 1.5f),
            modifier = Modifier
                .testTag(tag)
                .fillMaxWidth()
                .cssSurface(RoundedCornerShape(8.dp), t.mineral, null, emptyList())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * Host config (settings-dialog.tsx:2245-2251): the note, then "Share host config" (a toggle that
 * writes at once, like the other server toggles; locked when the environment sets it).
 */
@Composable
internal fun HostConfigSection(binding: ServerSettingsBinding, narrow: Boolean) {
    val view = binding.settings?.takeIf { binding.origin != null } ?: return
    val type = LocalTetherTypography.current
    val note = buildAnnotatedString {
        append(HostConfigRows.NOTE_LEAD)
        withStyle(SpanStyle(fontFamily = type.mono)) { append(HostConfigRows.NOTE_CODE) }
        append(HostConfigRows.NOTE_TAIL)
    }
    SettingsSection(HostConfigRows.TITLE, note, narrow, modifier = Modifier.testTag(EngineTags.HostConfig), last = true) {
        ServerToggleRow(HostConfigRows.shareHostConfig, view, binding, narrow)
    }
}
