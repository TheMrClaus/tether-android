package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.LabelText
import com.tether.app.client.ModelList
import com.tether.app.client.ModelOp
import com.tether.app.client.Profile
import com.tether.app.client.ProfileEdit
import com.tether.app.client.ProfileLimits
import com.tether.app.client.ProfileModel
import com.tether.app.client.ProvidersPatch
import com.tether.app.client.SecretText
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.ProviderLogoDefaults
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** Tags of the Custom providers editor's parts. */
object ProfileTags {
    const val Section = "profiles-section"
    const val Loading = "profiles-loading"
    const val Empty = "profiles-empty"
    const val ReadOnly = "profiles-read-only"
    const val Add = "profiles-add"
    fun card(id: String) = "profile-card:$id"
    fun switch(id: String) = "profile-switch:$id"
    fun subtitle(id: String) = "profile-subtitle:$id"
    fun row(id: String, what: String) = "profile-row:$id:$what"
    fun field(id: String, what: String) = "profile-field:$id:$what"
    fun unsaved(id: String, what: String) = "profile-unsaved:$id:$what"
    fun remove(id: String) = "profile-remove:$id"
    fun envKey(id: String, key: String) = "profile-env-key:$id:$key"
    fun envMasked(id: String, key: String) = "profile-env-masked:$id:$key"
    fun envInput(id: String, key: String) = "profile-env-input:$id:$key"
    fun envReveal(id: String, key: String) = "profile-env-reveal:$id:$key"
    fun envRemove(id: String, key: String) = "profile-env-remove:$id:$key"
    fun envNewName(id: String) = "profile-env-new-name:$id"
    fun envNewMasked(id: String) = "profile-env-new-masked:$id"
    fun envNewInput(id: String) = "profile-env-new-input:$id"
    fun envNewReveal(id: String) = "profile-env-new-reveal:$id"
    fun envAdd(id: String) = "profile-env-add:$id"
    fun modelId(id: String, list: ModelList, index: Int) = "profile-model-id:$id:${list.key}:$index"
    fun modelLabel(id: String, list: ModelList, index: Int) = "profile-model-label:$id:${list.key}:$index"
    fun modelDefault(id: String, list: ModelList, index: Int) = "profile-model-default:$id:${list.key}:$index"
    fun modelRemove(id: String, list: ModelList, index: Int) = "profile-model-remove:$id:${list.key}:$index"
    fun modelAdd(id: String, list: ModelList) = "profile-model-add:$id:${list.key}"
    fun draftId(id: String, list: ModelList) = "profile-model-draft-id:$id:${list.key}"
    fun draftLabel(id: String, list: ModelList) = "profile-model-draft-label:$id:${list.key}"
    fun draftDiscard(id: String, list: ModelList) = "profile-model-draft-discard:$id:${list.key}"
    const val ConfirmSheet = "profile-confirm"
    const val ConfirmNow = "profile-confirm-now"
    const val ConfirmNew = "profile-confirm-new"
    const val ConfirmNote = "profile-confirm-note"
    const val Confirm = "profile-confirm-change"
    const val Cancel = "profile-confirm-cancel"

    const val LABEL = "label"
    const val ID = "id"
    const val EXTENDS = "extends"
    const val COMMAND = "command"
    const val HOME = "home"
    const val ENV = "env"
    const val DROP_ENV = "dropEnv"
    const val TOOLS = "disallowedTools"
    const val ORDER = "order"
    const val VERIFIED = "verifiedThrough"
}

/**
 * Custom providers (settings-dialog.tsx 887c222 :621-1000 `ProfilesEditor`, drawn at :2237 under
 * Claude accounts), for ONE server (the panel keys it on [ProvidersBinding.origin], so another
 * server's editor starts from nothing: every env value masked, no half-typed field, no pending
 * confirmation).
 *
 * Writes, per the owner's rules (2026-10-01):
 * - every write is the WHOLE list (`set-providers` replaces it), built when it is sent from the
 *   client's newest list with one edit applied ([ProvidersBinding.send]). Each field is filled from
 *   its own server value and refilled when that value changes (the web's `key={value}` remount),
 *   so a concurrent edit elsewhere is shown, and never undone by a write from here;
 * - a profile's command and home set what the server RUNS: a field never writes on its own (not
 *   on a focus loss, a close, a tab change or a recreation). Done only opens [ProfileConfirmDialog],
 *   which shows the new value and the current one by the exact rule; its Change key builds the
 *   write at that moment from the newest list. Cancel, Back and a tap outside send nothing;
 * - env VALUES are secrets: masked by default (a fixed mask, the value not in the composition),
 *   revealed per row by a tap, re-masked on close, tab change, server switch, rotation and ON_STOP;
 *   the revealed field uses the password keyboard, offers no copy or cut, and sends only on Done;
 * - everything else follows the web (it writes as the web's blur does), but a configuration change
 *   never commits anything.
 */
@Composable
internal fun ProfilesSection(binding: ProvidersBinding, narrow: Boolean, last: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val list = binding.list?.takeIf { binding.origin != null }
    var pending by remember { mutableStateOf<ProfileRunsReview?>(null) }
    val caption = buildAnnotatedString {
        val code = SpanStyle(fontFamily = type.mono)
        append(ProfileRows.CAPTION_1)
        withStyle(code) { append("id") }
        append(ProfileRows.CAPTION_2)
        withStyle(code) { append("extends") }
        append(ProfileRows.CAPTION_3)
        withStyle(code) { append("env") }
        append(ProfileRows.CAPTION_4)
        withStyle(code) { append("providers.json") }
        append(ProfileRows.CAPTION_5)
    }
    SettingsSection(ProfileRows.TITLE, caption, narrow, modifier = Modifier.testTag(ProfileTags.Section), last = last) {
        if (list == null) {
            RowRule()
            Text(
                ProfileRows.LOADING,
                color = t.muted,
                style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                modifier = Modifier.testTag(ProfileTags.Loading).padding(vertical = 17.dp),
            )
            return@SettingsSection
        }
        val editable = list.writable
        if (!editable) ComingSoonNote(ProfileRows.READ_ONLY, modifier = Modifier.padding(bottom = 16.dp).testTag(ProfileTags.ReadOnly))
        if (list.profiles.isEmpty()) {
            // `.telemetry-empty`: the muted line.
            Text(
                ProfileRows.EMPTY,
                color = t.muted,
                style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f),
                modifier = Modifier.testTag(ProfileTags.Empty).padding(bottom = 16.dp),
            )
        }
        list.profiles.forEachIndexed { index, profile ->
            key(index, profile.id) { ProfileCard(profile, binding, editable, narrow, onReview = { pending = it }) }
        }
        if (editable) {
            TetherKey(
                onClick = { binding.send(ProfileEdit.Add) },
                classes = KeyClasses.ButtonSecondary,
                label = ProfileRows.ADD,
                icon = TetherIcons.Plus,
                iconSize = 14.dp,
                modifier = Modifier.testTag(ProfileTags.Add),
            )
        }
    }
    PendingProfileRuns(pending, binding) { pending = null }
}

/** `.engine-card` (:642-834): the head (glyph, name, engine and command, the switch), then the body's rows. */
@Composable
private fun ProfileCard(p: Profile, binding: ProvidersBinding, editable: Boolean, narrow: Boolean, onReview: (ProfileRunsReview) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val latest by rememberUpdatedState(binding)
    Column(
        Modifier
            .testTag(ProfileTags.card(p.id))
            .padding(bottom = 20.dp)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line), emptyList())
            .padding(if (narrow) 16.dp else 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(ProviderLogoDefaults.GlyphSize).cssSurface(RoundedCornerShape(7.2.dp), t.graphiteRaised, null, emptyList()), contentAlignment = Alignment.Center) {
                ProviderLogo(null, fallback = ProfileRows.glyph(p.extends), color = ProviderLogoDefaults.color(p.extends))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(ProfileRows.name(p), color = t.ink, style = settingsText(type.ui, 15f, 700, lineHeight = 1.5f))
                // :652 `extends · command.join(" ")`: the command is server text, drawn by the code rule.
                val command = p.command.orEmpty()
                val subtitle = if (command.isEmpty()) AnnotatedString(p.extends) else buildAnnotatedString {
                    append(p.extends)
                    append(" · ")
                    append(codeLabel(command.joinToString(" ")))
                }
                Text(subtitle, color = t.muted, maxLines = 2, style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f), modifier = Modifier.testTag(ProfileTags.subtitle(p.id)))
            }
            Box(
                Modifier
                    .testTag(ProfileTags.switch(p.id))
                    .alpha(if (editable) 1f else 0.45f)
                    .toggleable(
                        value = p.enabled,
                        enabled = editable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Switch,
                        onValueChange = { latest.send(ProfileEdit.Enabled(p.id)) },
                    )
                    .semantics { contentDescription = ProfileRows.switchLabel(p) }
                    .sizeIn(minWidth = 48.dp, minHeight = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                SettingsSwitchTrack(p.enabled)
            }
        }
        Spacer(Modifier.height(t.css.spaceSm + 12.dp))
        RowRule()
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
            ProfileTextRow(p, ProfileTags.LABEL, ProfileRows.LABEL, AnnotatedString(ProfileRows.LABEL_CAPTION), p.label, editable, narrow, first = true) {
                latest.send(ProfileEdit.Label(p.id, it))
            }
            ProfileTextRow(p, ProfileTags.ID, ProfileRows.ID, AnnotatedString(ProfileRows.ID_CAPTION), p.id, editable, narrow) {
                latest.send(ProfileEdit.Rename(p.id, it))
            }
            SettingsRow(
                narrow = narrow,
                modifier = Modifier.testTag(ProfileTags.row(p.id, ProfileTags.EXTENDS)),
                text = { m -> SettingsRowText(ProfileRows.EXTENDS, AnnotatedString(ProfileRows.EXTENDS_CAPTION), m) },
                control = { m ->
                    Box(m) {
                        TetherSelect(
                            options = ProfileLimits.EXTENDS.map { TetherSelectOption(it, it) },
                            selectedValue = p.extends,
                            onSelect = { latest.send(ProfileEdit.Extends(p.id, it.value)) },
                            enabled = editable,
                            placeholder = LabelText.visibleValue(p.extends),
                            contentDescription = ProfileRows.field(p, ProfileTags.EXTENDS),
                            modifier = (if (narrow) Modifier.fillMaxWidth(0.52f) else Modifier).testTag(ProfileTags.field(p.id, ProfileTags.EXTENDS)),
                        )
                    }
                },
            )
            if (p.extends != "claude") {
                val caption = buildAnnotatedString {
                    append(ProfileRows.COMMAND_CAPTION_1)
                    withStyle(SpanStyle(fontFamily = type.mono)) { append(ProfileRows.COMMAND_EXAMPLE) }
                    append(ProfileRows.COMMAND_CAPTION_2)
                }
                ProfileRunsRow(p, home = false, editable, narrow, onReview, ProfileRows.COMMAND, caption, ProfileRows.COMMAND_EXAMPLE)
            }
            val homeCaption = when {
                p.extends == "acp" -> AnnotatedString(ProfileRows.HOME_ACP)
                !p.homeDir.isNullOrEmpty() -> codeLabel(p.homeDir!!)
                else -> AnnotatedString(ProfileRows.HOME_OPTIONAL)
            }
            ProfileRunsRow(p, home = true, editable, narrow, onReview, ProfileRows.HOME, homeCaption, ProfileRows.HOME_PLACEHOLDER)
            SettingsRow(
                narrow = true,
                modifier = Modifier.testTag(ProfileTags.row(p.id, ProfileTags.ENV)),
                text = { m -> SettingsRowText(ProfileRows.ENV, AnnotatedString(ProfileRows.ENV_CAPTION), m) },
                control = { m -> EnvEditor(p, binding, editable, narrow, m) },
            )
            val dropCaption = buildAnnotatedString {
                append(ProfileRows.DROP_ENV_CAPTION_1)
                withStyle(SpanStyle(fontFamily = type.mono)) { append(ProfileRows.DROP_ENV_EXAMPLE) }
                append(ProfileRows.DROP_ENV_CAPTION_2)
            }
            ProfileTextRow(p, ProfileTags.DROP_ENV, ProfileRows.DROP_ENV, dropCaption, p.dropEnv.joinToString(","), editable, narrow, placeholder = ProfileRows.DROP_ENV_EXAMPLE, label = "drop env prefixes") {
                latest.send(ProfileEdit.DropEnv(p.id, it))
            }
            ProfileTextRow(
                p, ProfileTags.TOOLS, ProfileRows.TOOLS, AnnotatedString(ProfileRows.toolsCaption(p.extends)), p.disallowedTools.joinToString(","),
                editable && p.extends == "claude", narrow, placeholder = ProfileRows.TOOLS_PLACEHOLDER, label = "disallowed tools",
            ) {
                latest.send(ProfileEdit.DisallowedTools(p.id, it))
            }
            ProfileTextRow(p, ProfileTags.ORDER, ProfileRows.ORDER, AnnotatedString(ProfileRows.ORDER_CAPTION), p.order?.toString().orEmpty(), editable, narrow, digits = true) {
                latest.send(ProfileEdit.Order(p.id, it))
            }
            if (p.extends == "acp") {
                val verified = buildAnnotatedString {
                    append(ProfileRows.VERIFIED_CAPTION_1)
                    withStyle(SpanStyle(fontFamily = type.mono)) { append(ProfileRows.VERIFIED_CAPTION_CODE) }
                    append(ProfileRows.VERIFIED_CAPTION_2)
                }
                ProfileTextRow(p, ProfileTags.VERIFIED, ProfileRows.VERIFIED, verified, p.verifiedThrough.orEmpty(), editable, narrow, placeholder = ProfileRows.VERIFIED_PLACEHOLDER, label = "verified through") {
                    latest.send(ProfileEdit.VerifiedThrough(p.id, it))
                }
            }
            for (list in ModelList.entries) {
                SettingsRow(
                    narrow = true,
                    modifier = Modifier.testTag(ProfileTags.row(p.id, list.key)),
                    text = { m ->
                        val (title, caption) = if (list == ModelList.Models) ProfileRows.MODELS to ProfileRows.MODELS_CAPTION else ProfileRows.ADDITIONAL to ProfileRows.ADDITIONAL_CAPTION
                        SettingsRowText(title, AnnotatedString(caption), m)
                    },
                    control = { m -> ModelListEditor(p, list, binding, editable, narrow, m) },
                )
            }
            if (editable) {
                TetherKey(
                    onClick = { latest.send(ProfileEdit.Remove(p.id)) },
                    classes = KeyClasses.ButtonSecondary,
                    label = ProfileRows.REMOVE,
                    contentDescription = "${ProfileRows.REMOVE} ${ProfileRows.name(p)}",
                    modifier = Modifier.padding(top = 12.dp).testTag(ProfileTags.remove(p.id)),
                )
            }
        }
    }
}

/**
 * One text row of a card: the field holds the server's value cleaned of hidden characters (the
 * edit-field rule) and is refilled when that value changes; it commits as the web's blur does
 * (Done, a focus loss, leaving the screen), never on a configuration change ([CommitField]).
 * [digits]: the order field (whole numbers only; any other edit leaves the field unchanged).
 */
@Composable
private fun ProfileTextRow(
    p: Profile,
    what: String,
    title: String,
    caption: AnnotatedString,
    value: String,
    enabled: Boolean,
    narrow: Boolean,
    first: Boolean = false,
    placeholder: String = "",
    label: String = what,
    digits: Boolean = false,
    onCommit: (String) -> Unit,
) {
    val shown = remember(value) { LabelText.withoutHidden(value) }
    SettingsRow(
        narrow = narrow,
        rule = !first,
        modifier = Modifier.testTag(ProfileTags.row(p.id, what)),
        text = { m -> SettingsRowText(title, caption, m) },
        control = { m ->
            CommitField(
                shown = shown,
                label = ProfileRows.field(p, label),
                tag = ProfileTags.field(p.id, what),
                enabled = enabled,
                narrow = narrow,
                placeholder = placeholder,
                keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text,
                accept = if (digits) { typed -> typed.length <= 7 && typed.all { it in '0'..'9' } } else { _ -> true },
                onCommit = onCommit,
                modifier = m.serverFieldWidth(narrow),
            )
        },
    )
}

/**
 * The command or home row (what the profile RUNS): like the engine rows (EnginesSection.kt), the
 * field never writes; Done hands its review to [onReview] (the confirmation). While it holds an
 * edit Done would review, its caption says it is not saved yet.
 */
@Composable
private fun ProfileRunsRow(
    p: Profile,
    home: Boolean,
    editable: Boolean,
    narrow: Boolean,
    onReview: (ProfileRunsReview) -> Unit,
    title: String,
    caption: AnnotatedString,
    placeholder: String,
) {
    val t = LocalTetherTokens.current
    val what = if (home) ProfileTags.HOME else ProfileTags.COMMAND
    val raw = if (home) p.homeDir.orEmpty() else ProfileRows.commandText(p)
    val shown = remember(raw) { LabelText.withoutHidden(raw) }
    var text by remember(shown) { mutableStateOf(shown) }
    val latestProfile by rememberUpdatedState(p)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    fun review(profile: Profile, typed: String) = if (home) ProfileRows.reviewHome(profile, typed, shown) else ProfileRows.reviewCommand(profile, typed, shown)
    val edited = editable && review(p, text) != null
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(ProfileTags.row(p.id, what)),
        text = { m ->
            val shownCaption = if (edited) AnnotatedString(EngineRows.UNSAVED) else caption
            SettingsRowText(title, shownCaption, m.then(if (edited) Modifier.testTag(ProfileTags.unsaved(p.id, what)) else Modifier))
        },
        control = { m ->
            val style = serverFieldStyle(narrow)
            BasicTextField(
                value = text,
                // Never past the server's limits (it would refuse the whole list).
                onValueChange = { next ->
                    val fits = if (home) next.length <= ProfileLimits.HOME else ProvidersPatch.commandFits(ProvidersPatch.commandParts(next))
                    if (fits) text = next
                },
                enabled = editable,
                singleLine = true,
                textStyle = style.copy(color = t.ink),
                cursorBrush = SolidColor(t.violet),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    review(latestProfile, text)?.let(onReview)
                    focusManager.clearFocus()
                }),
                modifier = m
                    .serverFieldWidth(narrow)
                    .testTag(ProfileTags.field(p.id, what))
                    .semantics { contentDescription = ProfileRows.field(p, what) }
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner -> ServerFieldBox(editable, focused, style, if (text.isEmpty()) AnnotatedString(placeholder) else null, inner) },
            )
        },
    )
}

/**
 * The pending command or home, confirmed or dropped (slice 4's machinery): a tap on Change only
 * ASKS; the write is built in the frame after it ([SideEffect]) from the newest list
 * ([ProvidersBinding.sendConfirmed]), once per confirmation (a double tap included). The
 * confirmation goes, sending nothing, when the list goes (signed out, another server), stops being
 * writable, loses the profile, or what the profile runs changes from what it showed as "Now".
 */
@Composable
private fun PendingProfileRuns(pending: ProfileRunsReview?, binding: ProvidersBinding, onDone: () -> Unit) {
    val r = pending ?: return
    val profile = binding.list?.takeIf { binding.origin != null && it.writable }?.profile(r.profileId)
    if (profile == null || ProfileRows.runsNow(profile, r.home) != r.now) return SideEffect { onDone() }
    var asked by remember(r) { mutableStateOf(false) }
    val fired = remember(r) { booleanArrayOf(false) }
    if (asked) {
        SideEffect {
            if (!fired[0]) {
                fired[0] = true
                // Built NOW, from the newest list; what the profile runs must still be what was shown.
                val newest = binding.latest()?.profile(r.profileId)
                if (newest != null && ProfileRows.runsNow(newest, r.home) == r.now) binding.sendConfirmed(r.edit)
            }
            onDone()
        }
        return
    }
    ProfileConfirmDialog(r, onConfirm = { asked = true }, onCancel = onDone)
}

/**
 * The confirmation for what a profile runs: the value that will be sent, then the current one, each
 * part on its own line and drawn by the exact rule ([ConfirmValueField]), so what is confirmed is
 * what is sent; the note when typing was rewritten (trimmed, or split at spaces). Change ignores
 * taps for [CONFIRM_ARM_MS] after the dialog appears ([ArmedConfirmKey]).
 */
@Composable
internal fun ProfileConfirmDialog(r: ProfileRunsReview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    TetherDialog(
        onDismiss = onCancel,
        title = ProfileRows.confirmTitle(r),
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(ProfileTags.Cancel))
            ArmedConfirmKey(ProfileRows.confirmAction(r), ProfileTags.Confirm, onConfirm)
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(ProfileTags.ConfirmSheet), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TetherDialogText(ProfileRows.confirmBody(r))
            ConfirmValueField(EngineRows.CONFIRM_NEW, r.parts, ProfileRows.emptyValue(r), ProfileTags.ConfirmNew)
            if (r.normalized) {
                Text(
                    ProfileRows.normalizedNote(r),
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                    modifier = Modifier.testTag(ProfileTags.ConfirmNote),
                )
            }
            ConfirmValueField(EngineRows.CONFIRM_NOW, r.now, ProfileRows.emptyValue(r), ProfileTags.ConfirmNow)
        }
    }
}

// ---- the env editor (:349-440) ---------------------------------------------------------------------

/** EnvEditor: one entry per key (its name, its masked value, reveal and remove), then the add row. */
@Composable
private fun EnvEditor(p: Profile, binding: ProvidersBinding, editable: Boolean, narrow: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        p.envKeys.forEach { envKey -> key(envKey) { EnvEntry(p, envKey, binding, editable, narrow) } }
        if (editable) EnvAddRow(p, binding, narrow)
    }
}

/**
 * One env entry. Its KEY is not secret: an ordinary field, renamed as the web's blur does. Its
 * VALUE is: masked ([MaskedWell]) until its own Reveal; revealed, the field holds it as it is
 * (password keyboard, no copy or cut) and sends only on Done ([CommitField] with neither blur nor
 * leave commits).
 */
@Composable
private fun EnvEntry(p: Profile, envKey: String, binding: ProvidersBinding, editable: Boolean, narrow: Boolean) {
    val latest by rememberUpdatedState(binding)
    val secret = p.envValue(envKey)
    var revealed by rememberMaskedReveal()
    val valueLabel = ProfileRows.valueLabel(envKey)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownKey = remember(envKey) { LabelText.withoutHidden(envKey) }
            CommitField(
                shown = shownKey,
                label = ProfileRows.ENV_NAME,
                tag = ProfileTags.envKey(p.id, envKey),
                enabled = editable,
                narrow = narrow,
                placeholder = "",
                onCommit = { latest.send(ProfileEdit.EnvKey(p.id, envKey, it)) },
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                TetherKey(
                    onClick = { latest.send(ProfileEdit.EnvRemove(p.id, envKey)) },
                    classes = KeyClasses.IconButton,
                    icon = TetherIcons.X,
                    iconSize = 14.dp,
                    contentDescription = ProfileRows.removeLabel(envKey),
                    modifier = Modifier.testTag(ProfileTags.envRemove(p.id, envKey)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (revealed && secret != null) {
                // Only here is the plaintext read: the revealed field, filled with it as it is.
                val shown = secret.reveal()
                CommitField(
                    shown = shown,
                    label = valueLabel,
                    tag = ProfileTags.envInput(p.id, envKey),
                    enabled = editable,
                    narrow = narrow,
                    placeholder = "",
                    keyboardType = KeyboardType.Password,
                    accept = { it.length <= ProfileLimits.ENV_VALUE },
                    commitOnBlur = false,
                    commitOnLeave = false,
                    noCopy = true,
                    onCommit = { latest.send(ProfileEdit.EnvValue(p.id, envKey, SecretText(it))) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                MaskedWell(
                    tag = ProfileTags.envMasked(p.id, envKey),
                    description = ServerRowCopy.maskedDescription(valueLabel, secret?.isEmpty != false),
                    hasValue = secret?.isEmpty == false,
                    placeholder = "",
                    narrow = narrow,
                    modifier = Modifier.weight(1f),
                )
            }
            if (secret != null) {
                TetherKey(
                    onClick = { revealed = !revealed },
                    classes = KeyClasses.IconButton,
                    icon = if (revealed) TetherIcons.EyeOff else TetherIcons.Eye,
                    iconSize = 14.dp,
                    contentDescription = if (revealed) ServerRowCopy.hide(valueLabel) else ServerRowCopy.reveal(valueLabel),
                    modifier = Modifier.testTag(ProfileTags.envReveal(p.id, envKey)),
                )
            }
        }
    }
}

/**
 * The add row (:415-437): NAME, the value, and Add (or Done in either field). The value is a secret
 * too: masked until revealed (typing needs the reveal, so a masked-looking field never holds
 * plaintext in the semantics tree), password keyboard, no copy or cut. Nothing is sent until Add.
 */
@Composable
private fun EnvAddRow(p: Profile, binding: ProvidersBinding, narrow: Boolean) {
    val latest by rememberUpdatedState(binding)
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var revealed by rememberMaskedReveal()
    val add: () -> Unit = {
        // The web clears the draft after its Add; here only once it was sent (a refused write keeps it).
        if (com.tether.app.client.jsTrim(name).isNotEmpty() && latest.send(ProfileEdit.EnvAdd(p.id, name, SecretText(value)))) {
            name = ""
            value = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DraftField(name, { name = it }, ProfileRows.ENV_NEW_NAME, ProfileTags.envNewName(p.id), ProfileRows.ENV_NAME_PLACEHOLDER, narrow, onDone = add, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (revealed) {
                DraftField(
                    value, { if (it.length <= ProfileLimits.ENV_VALUE) value = it }, ProfileRows.ENV_NEW_VALUE, ProfileTags.envNewInput(p.id), ProfileRows.ENV_VALUE_PLACEHOLDER, narrow,
                    onDone = add, secret = true, modifier = Modifier.weight(1f),
                )
            } else {
                MaskedWell(
                    tag = ProfileTags.envNewMasked(p.id),
                    description = ServerRowCopy.maskedDescription(ProfileRows.ENV_NEW_VALUE, value.isEmpty()),
                    hasValue = value.isNotEmpty(),
                    placeholder = ProfileRows.ENV_VALUE_PLACEHOLDER,
                    narrow = narrow,
                    modifier = Modifier.weight(1f),
                )
            }
            TetherKey(
                onClick = { revealed = !revealed },
                classes = KeyClasses.IconButton,
                icon = if (revealed) TetherIcons.EyeOff else TetherIcons.Eye,
                iconSize = 14.dp,
                contentDescription = if (revealed) ServerRowCopy.hide(ProfileRows.ENV_NEW_VALUE) else ServerRowCopy.reveal(ProfileRows.ENV_NEW_VALUE),
                modifier = Modifier.testTag(ProfileTags.envNewReveal(p.id)),
            )
        }
        TetherKey(
            onClick = add,
            classes = KeyClasses.ButtonSecondary,
            label = ProfileRows.ADD_ENV,
            enabled = name.isNotBlank(),
            modifier = Modifier.testTag(ProfileTags.envAdd(p.id)),
        )
    }
}

/** A draft field (never a server value): sent only by its row's action or Done. [secret]: the password keyboard, no copy or cut. */
@Composable
private fun DraftField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    placeholder: String,
    narrow: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    val style = serverFieldStyle(narrow)
    NoCopyScope(secret) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = modifier.testTag(tag).semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused },
            decorationBox = { inner -> ServerFieldBox(true, focused, style, if (value.isEmpty()) AnnotatedString(placeholder) else null, inner) },
        )
    }
}

// ---- the model lists (:442-617, issue #107) ------------------------------------------------------

/** The draft row the operator is naming: [initialId] is the placeholder it started at. Never saved state. */
private data class ModelDraft(val initialId: String, val id: TextFieldValue, val label: String)

/**
 * ModelListEditor: one row per committed model (id, label, Default, remove), the single draft row,
 * and "Add model". Issue #107: "Add model" opens a LOCAL draft at a placeholder id; it joins the
 * list only once a real id is typed (and only then is anything sent), and is dropped otherwise,
 * when focus leaves its fields, on Done, or when the editor leaves the screen; never on a
 * configuration change.
 */
@Composable
private fun ModelListEditor(p: Profile, list: ModelList, binding: ProvidersBinding, editable: Boolean, narrow: Boolean, modifier: Modifier) {
    val latest by rememberUpdatedState(binding)
    val rows = p.models(list)
    var draft by remember { mutableStateOf<ModelDraft?>(null) }
    val activity = LocalContext.current.findActivity()
    val finalize: () -> Unit = {
        val d = draft
        if (d != null) {
            draft = null
            latest.send(ProfileEdit.Model(p.id, list, ModelOp.Add(d.initialId, d.id.text, d.label)))
        }
    }
    val defaultRow = rows.indexOfFirst { it.isDefault }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEachIndexed { index, row ->
            key(index, row.id) { ModelRowView(p, list, index, row, index == defaultRow, binding, editable, narrow) }
        }
        draft?.let { d ->
            DraftModelRow(
                p, list, d, narrow,
                onChange = { draft = it },
                onFinalize = finalize,
                onDiscard = { draft = null },
                onLeave = { if (activity?.isChangingConfigurations != true) finalize() },
            )
        }
        if (editable) {
            TetherKey(
                onClick = { if (draft == null) draft = ProvidersPatch.draftModelId(rows).let { ModelDraft(it, TextFieldValue(it, TextRange(0, it.length)), "") } },
                classes = KeyClasses.ButtonSecondary,
                label = ProfileRows.ADD_MODEL,
                icon = TetherIcons.Plus,
                iconSize = 14.dp,
                modifier = Modifier.testTag(ProfileTags.modelAdd(p.id, list)),
            )
        }
    }
}

@Composable
private fun ModelRowView(p: Profile, list: ModelList, index: Int, row: ProfileModel, isDefault: Boolean, binding: ProvidersBinding, editable: Boolean, narrow: Boolean) {
    val latest by rememberUpdatedState(binding)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownId = remember(row.id) { LabelText.withoutHidden(row.id) }
            CommitField(
                shown = shownId,
                label = ProfileRows.MODEL_ID,
                tag = ProfileTags.modelId(p.id, list, index),
                enabled = editable,
                narrow = narrow,
                placeholder = "",
                onCommit = { latest.send(ProfileEdit.Model(p.id, list, ModelOp.SetId(index, row.id, it))) },
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                TetherKey(
                    onClick = { latest.send(ProfileEdit.Model(p.id, list, ModelOp.Remove(index, row.id))) },
                    classes = KeyClasses.IconButton,
                    icon = TetherIcons.X,
                    iconSize = 14.dp,
                    contentDescription = ProfileRows.removeLabel(row.id),
                    modifier = Modifier.testTag(ProfileTags.modelRemove(p.id, list, index)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownLabel = remember(row.label) { LabelText.withoutHidden(row.label.orEmpty()) }
            CommitField(
                shown = shownLabel,
                label = ProfileRows.labelFor(row.id),
                tag = ProfileTags.modelLabel(p.id, list, index),
                enabled = editable,
                narrow = narrow,
                placeholder = ProfileRows.MODEL_LABEL_PLACEHOLDER,
                onCommit = { latest.send(ProfileEdit.Model(p.id, list, ModelOp.SetLabel(index, row.id, it))) },
                modifier = Modifier.weight(1f),
            )
            DefaultRadio(
                selected = isDefault,
                enabled = editable,
                description = ProfileRows.defaultLabel(row.id),
                tag = ProfileTags.modelDefault(p.id, list, index),
                onSelect = { latest.send(ProfileEdit.Model(p.id, list, ModelOp.SetDefault(index, row.id))) },
            )
        }
    }
}

/** The draft row (:563-612): its id field focused with the placeholder selected, its label, a disabled Default, discard. */
@Composable
private fun DraftModelRow(
    p: Profile,
    list: ModelList,
    d: ModelDraft,
    narrow: Boolean,
    onChange: (ModelDraft) -> Unit,
    onFinalize: () -> Unit,
    onDiscard: () -> Unit,
    onLeave: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val style = serverFieldStyle(narrow)
    val idFocus = remember { FocusRequester() }
    var idFocused by remember { mutableStateOf(false) }
    var labelFocused by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    val leave by rememberUpdatedState(onLeave)
    val finalize by rememberUpdatedState(onFinalize)
    LaunchedEffect(Unit) { runCatching { idFocus.requestFocus() } }
    // Focus left BOTH draft fields (moving between them keeps the draft): it is finalized.
    val anyFocused = idFocused || labelFocused
    LaunchedEffect(anyFocused) {
        if (anyFocused) wasFocused = true else if (wasFocused) finalize()
    }
    DisposableEffect(Unit) { onDispose { leave() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = d.id,
                onValueChange = { if (it.text.length <= ProfileLimits.MODEL_ID) onChange(d.copy(id = it)) },
                singleLine = true,
                textStyle = style.copy(color = t.ink),
                cursorBrush = SolidColor(t.violet),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onFinalize() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(idFocus)
                    .testTag(ProfileTags.draftId(p.id, list))
                    .semantics { contentDescription = ProfileRows.MODEL_ID }
                    .onFocusChanged { idFocused = it.isFocused },
                decorationBox = { inner -> ServerFieldBox(true, idFocused, style, null, inner) },
            )
            TetherKey(
                onClick = onDiscard,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 14.dp,
                contentDescription = ProfileRows.MODEL_DISCARD,
                modifier = Modifier.testTag(ProfileTags.draftDiscard(p.id, list)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = d.label,
                onValueChange = { if (it.length <= ProfileLimits.MODEL_LABEL) onChange(d.copy(label = it)) },
                singleLine = true,
                textStyle = style.copy(color = t.ink),
                cursorBrush = SolidColor(t.violet),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onFinalize() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag(ProfileTags.draftLabel(p.id, list))
                    .semantics { contentDescription = ProfileRows.MODEL_NEW_LABEL }
                    .onFocusChanged { labelFocused = it.isFocused },
                decorationBox = { inner -> ServerFieldBox(true, labelFocused, style, if (d.label.isEmpty()) AnnotatedString(ProfileRows.MODEL_LABEL_PLACEHOLDER) else null, inner) },
            )
            DefaultRadio(selected = false, enabled = false, description = ProfileRows.MODEL_NEW_DEFAULT, tag = "", onSelect = {})
        }
    }
}

/** `.settings-model-default` (:537-548): a radio and "Default"; at most one row of a list is the default. */
@Composable
private fun DefaultRadio(selected: Boolean, enabled: Boolean, description: String, tag: String, onSelect: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag))
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 44.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(16.dp).border(1.dp, if (selected) t.violetStrong else t.lineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(t.violetStrong, CircleShape))
        }
        Text(ProfileRows.DEFAULT, color = t.ink, style = settingsText(type.ui, 13f, 500, lineHeight = 1.5f))
    }
}
