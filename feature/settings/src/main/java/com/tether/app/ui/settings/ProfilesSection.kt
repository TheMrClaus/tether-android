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
import com.tether.app.client.ProvidersWriteStatus
import com.tether.app.client.jsTrim
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.tether.app.ui.icons.ProviderTile
import kotlinx.coroutines.delay
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
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
    const val Add = "profiles-add"
    fun card(id: String) = "profile-card:$id"
    fun glyph(id: String) = "profile-glyph:$id"
    fun switch(id: String) = "profile-switch:$id"
    fun subtitle(id: String) = "profile-subtitle:$id"
    fun row(id: String, what: String) = "profile-row:$id:$what"
    fun field(id: String, what: String) = "profile-field:$id:$what"
    fun remove(id: String) = "profile-remove:$id"
    fun envKey(id: String, key: String) = "profile-env-key:$id:$key"
    fun envInput(id: String, key: String) = "profile-env-input:$id:$key"
    fun envRemove(id: String, key: String) = "profile-env-remove:$id:$key"
    fun envNewName(id: String) = "profile-env-new-name:$id"
    fun envNewInput(id: String) = "profile-env-new-input:$id"
    fun envAdd(id: String) = "profile-env-add:$id"
    fun modelId(id: String, list: ModelList, index: Int) = "profile-model-id:$id:${list.key}:$index"
    fun modelLabel(id: String, list: ModelList, index: Int) = "profile-model-label:$id:${list.key}:$index"
    fun modelDefault(id: String, list: ModelList, index: Int) = "profile-model-default:$id:${list.key}:$index"
    fun modelRemove(id: String, list: ModelList, index: Int) = "profile-model-remove:$id:${list.key}:$index"
    fun modelAdd(id: String, list: ModelList) = "profile-model-add:$id:${list.key}"
    fun draftId(id: String, list: ModelList) = "profile-model-draft-id:$id:${list.key}"
    fun draftLabel(id: String, list: ModelList) = "profile-model-draft-label:$id:${list.key}"
    fun draftDiscard(id: String, list: ModelList) = "profile-model-draft-discard:$id:${list.key}"
    const val Notice = "profiles-notice"
    fun notice(id: String) = "profile-notice:$id"
    fun envNewNote(id: String) = "profile-env-new-note:$id"

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
 * What the editor's rows act through (r2): the binding and the reporter of what became of a write
 * (a refusal is never silent, security F4).
 */
internal class ProfileActions(
    val binding: ProvidersBinding,
    /** What became of a write on profile id; `built` is the list it was built from (epoch, generation). */
    private val report: (id: String, result: ProvidersSend, quiet: Boolean, built: Pair<Long, Long>?) -> Unit,
) {
    /** An edit of profile [id]; a refusal shows on its card unless [quiet] (a field says it itself). */
    fun send(id: String, edit: ProfileEdit, quiet: Boolean = false): ProvidersSend {
        val built = binding.latest()?.let { it.epoch to it.generation }
        return binding.send(edit).also { report(id, it, quiet, built) }
    }
}

/**
 * Custom providers (settings-dialog.tsx 887c222 :621-1000 `ProfilesEditor`, drawn at :2237 under
 * Claude accounts), for ONE server (the panel keys it on [ProvidersBinding.origin], so another
 * server's editor starts from nothing: no half-typed field).
 *
 * Writes as the web's (ta-coik.5, owner rule 2026-10-03: no app-only confirmation or gate):
 * - every write is the WHOLE list (`set-providers` replaces it), built when it is sent from the
 *   client's newest list with one edit applied ([ProvidersBinding.send]). Each field is filled from
 *   its own server value and refilled when that value changes (the web's `key={value}` remount),
 *   so a concurrent edit elsewhere is shown, and never undone by a write from here. ta-coik.17 r2:
 *   a write while the last one still waits for its answer is queued by the client and sent, on
 *   the server's newest list, once that one is answered (never refused), and a list from before a
 *   reconnect is never written back;
 * - every field writes as the web's blur does (Done, a focus loss, leaving the screen; never a
 *   configuration change): the command, home and engine included; the env editor's names and
 *   values are plain fields as on the web (a copy of a value is marked sensitive);
 * - ta-coik.17: any list is editable and every field takes what the web's takes (no read-only
 *   list, no length cap): a value the server refuses comes back as its error, shown as every
 *   server error is (the app's toast, the web's setError);
 * - r2 (security F4): a write that is refused says so (on the field, or on the card), and one the
 *   server never confirms within [com.tether.app.client.ProvidersInFlight.TIMEOUT_MS] says so too.
 */
@Composable
internal fun ProfilesSection(binding: ProvidersBinding, narrow: Boolean, last: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val list = binding.list?.takeIf { binding.origin != null }
    var notices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // The list the last sent write was built from (epoch, generation): unconfirmed if it is still the newest after the timeout.
    var lastSent by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val actions = remember(binding, list?.epoch) {
        ProfileActions(
            binding,
            report = { id, result, quiet, built ->
                when (result) {
                    ProvidersSend.Sent -> {
                        notices = notices - id - ""
                        lastSent = built
                    }
                    is ProvidersSend.Refused -> if (!quiet) notices = notices + (id to ProfileRows.notSaved(result.reason))
                    ProvidersSend.NoChange -> Unit
                }
            },
        )
    }
    // r4: the client's guard says what became of the last write (one source of truth).
    LaunchedEffect(lastSent) {
        lastSent ?: return@LaunchedEffect
        delay(com.tether.app.client.ProvidersInFlight.TIMEOUT_MS)
        if (binding.writer.status() is ProvidersWriteStatus.Waiting) notices = notices + ("" to ProfileRows.UNCONFIRMED)
    }
    // Each new list may end the wait: saved (the notice goes) or not saved (it says so).
    LaunchedEffect(list?.epoch, list?.generation) {
        if (lastSent == null) return@LaunchedEffect
        when (val st = binding.writer.status()) {
            is ProvidersWriteStatus.Done -> notices = when (st.outcome) {
                ProvidersWriteStatus.Outcome.NotSaved -> notices + ("" to ProfileRows.LAST_NOT_SAVED)
                else -> notices - ""
            }
            else -> Unit
        }
    }
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
        notices[""]?.let { NoticeLine(it, ProfileTags.Notice, Modifier.padding(bottom = 16.dp)) }
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
            key(index, profile.id) { ProfileCard(profile, actions, narrow, notices[profile.id]) }
        }
        TetherKey(
            onClick = { actions.send("", ProfileEdit.Add) },
            classes = KeyClasses.ButtonSecondary,
            label = ProfileRows.ADD,
            icon = TetherIcons.Plus,
            iconSize = 14.dp,
            modifier = Modifier.testTag(ProfileTags.Add),
        )
    }
}

/** A refusal or an unconfirmed write, said in the attention ink and announced. */
@Composable
private fun NoticeLine(text: String, tag: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        color = t.attentionInk,
        style = settingsText(type.ui, 12f, 500, lineHeight = 1.5f),
        modifier = modifier
            .testTag(tag)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(8.dp), t.attentionBg, null, emptyList())
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** `.engine-card` (:642-834): the head (glyph, name, engine and command, the switch), then the body's rows. */
@Composable
private fun ProfileCard(p: Profile, actions: ProfileActions, narrow: Boolean, notice: String?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val latest by rememberUpdatedState(actions)
    Column(
        Modifier
            .testTag(ProfileTags.card(p.id))
            .padding(bottom = 20.dp)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line), emptyList())
            .padding(if (narrow) 16.dp else 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The harness the profile extends: its mark and brand tile (globals.css 11204-11224) where
            // verified, else the web's letter (:645) on the raised tile.
            ProviderTile(
                p.extends,
                Modifier.size(ProviderLogoDefaults.GlyphSize).testTag(ProfileTags.glyph(p.id)),
                fallback = ProfileRows.glyph(p.extends),
                shape = RoundedCornerShape(7.2.dp),
                background = t.graphiteRaised,
                color = ProviderLogoDefaults.color(p.extends),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(ProfileRows.name(p), color = t.ink, style = settingsText(type.ui, 15f, 700, lineHeight = 1.5f))
                // :652 `extends · command.join(" ")`: the command is server text, drawn by the code rule.
                val command = p.command.orEmpty()
                val subtitle = if (command.isEmpty()) AnnotatedString(p.extends) else buildAnnotatedString {
                    append(p.extends)
                    append(" · ")
                    // Mono with no ligatures, so `--flag` is never drawn as a dash.
                    withStyle(SpanStyle(fontFamily = type.mono, fontFeatureSettings = "liga 0, calt 0")) { append(codeLabel(command.joinToString(" "))) }
                }
                Text(subtitle, color = t.muted, maxLines = 2, style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f), modifier = Modifier.testTag(ProfileTags.subtitle(p.id)))
            }
            Box(
                Modifier
                    .testTag(ProfileTags.switch(p.id))
                    .toggleable(
                        value = p.enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Switch,
                        // r2 (security F8): the value asked for, not a toggle of whatever is newest.
                        onValueChange = { on -> latest.send(p.id, ProfileEdit.Enabled(p.id, on)) },
                    )
                    .semantics { contentDescription = ProfileRows.switchLabel(p) }
                    .sizeIn(minWidth = 48.dp, minHeight = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                SettingsSwitchTrack(p.enabled)
            }
        }
        if (notice != null) NoticeLine(notice, ProfileTags.notice(p.id), Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(t.css.spaceSm + 12.dp))
        RowRule()
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
            ProfileTextRow(p, ProfileTags.LABEL, ProfileRows.LABEL, AnnotatedString(ProfileRows.LABEL_CAPTION), p.label, true, narrow, first = true) {
                ProfileRows.outcome(latest.send(p.id, ProfileEdit.Label(p.id, it), quiet = true))
            }
            ProfileTextRow(p, ProfileTags.ID, ProfileRows.ID, AnnotatedString(ProfileRows.ID_CAPTION), p.id, true, narrow) {
                ProfileRows.outcome(latest.send(p.id, ProfileEdit.Rename(p.id, it), quiet = true))
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
                            // :696: another engine is written at once.
                            onSelect = { choice -> latest.send(p.id, ProfileEdit.Extends(p.id, choice.value)) },
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
                // :713-717: split at whitespace.
                ProfileTextRow(p, ProfileTags.COMMAND, ProfileRows.COMMAND, caption, ProfileRows.commandText(p), true, narrow, placeholder = ProfileRows.COMMAND_EXAMPLE) {
                    ProfileRows.outcome(latest.send(p.id, ProfileEdit.Command(p.id, it), quiet = true))
                }
            }
            val homeCaption = when {
                p.extends == "acp" -> AnnotatedString(ProfileRows.HOME_ACP)
                !p.homeDir.isNullOrEmpty() -> codeLabel(p.homeDir!!)
                else -> AnnotatedString(ProfileRows.HOME_OPTIONAL)
            }
            // T8.2 (settings-dialog.tsx 90fbb9f :740-750, :2240-2245, :2470-2472): "Browse folders"
            // fills the home with the folder chosen, written at once.
            val picker = LocalHomeFolderPicker.current
            ProfileTextRow(
                p, ProfileTags.HOME, ProfileRows.HOME, homeCaption, p.homeDir.orEmpty(), true, narrow, placeholder = ProfileRows.HOME_PLACEHOLDER,
                trailing = {
                    TetherKey(
                        onClick = {
                            val home = p.homeDir.orEmpty()
                            picker.open(home, home) { path -> latest.send(p.id, ProfileEdit.Home(p.id, path)) }
                        },
                        classes = KeyClasses.IconButton,
                        icon = TetherIcons.FolderOpen,
                        iconSize = 14.dp,
                        contentDescription = HomeFolderCopy.browseLabel(ProfileRows.name(p)),
                        modifier = Modifier.testTag(HomeFolderTags.profile(p.id)),
                    )
                },
            ) {
                ProfileRows.outcome(latest.send(p.id, ProfileEdit.Home(p.id, it), quiet = true))
            }
            SettingsRow(
                narrow = true,
                modifier = Modifier.testTag(ProfileTags.row(p.id, ProfileTags.ENV)),
                text = { m -> SettingsRowText(ProfileRows.ENV, AnnotatedString(ProfileRows.ENV_CAPTION), m) },
                control = { m -> EnvEditor(p, actions, narrow, m) },
            )
            val dropCaption = buildAnnotatedString {
                append(ProfileRows.DROP_ENV_CAPTION_1)
                withStyle(SpanStyle(fontFamily = type.mono)) { append(ProfileRows.DROP_ENV_EXAMPLE) }
                append(ProfileRows.DROP_ENV_CAPTION_2)
            }
            ProfileTextRow(p, ProfileTags.DROP_ENV, ProfileRows.DROP_ENV, dropCaption, p.dropEnv.joinToString(","), true, narrow, placeholder = ProfileRows.DROP_ENV_EXAMPLE, label = "drop env prefixes") {
                ProfileRows.outcome(latest.send(p.id, ProfileEdit.DropEnv(p.id, it), quiet = true))
            }
            ProfileTextRow(
                p, ProfileTags.TOOLS, ProfileRows.TOOLS, AnnotatedString(ProfileRows.toolsCaption(p.extends)), p.disallowedTools.joinToString(","),
                p.extends == "claude", narrow, placeholder = ProfileRows.TOOLS_PLACEHOLDER, label = "disallowed tools",
            ) {
                ProfileRows.outcome(latest.send(p.id, ProfileEdit.DisallowedTools(p.id, it), quiet = true))
            }
            ProfileTextRow(p, ProfileTags.ORDER, ProfileRows.ORDER, AnnotatedString(ProfileRows.ORDER_CAPTION), p.order?.toString().orEmpty(), true, narrow, digits = true) {
                ProfileRows.outcome(latest.send(p.id, ProfileEdit.Order(p.id, it), quiet = true))
            }
            if (p.extends == "acp") {
                val verified = buildAnnotatedString {
                    append(ProfileRows.VERIFIED_CAPTION_1)
                    withStyle(SpanStyle(fontFamily = type.mono)) { append(ProfileRows.VERIFIED_CAPTION_CODE) }
                    append(ProfileRows.VERIFIED_CAPTION_2)
                }
                ProfileTextRow(p, ProfileTags.VERIFIED, ProfileRows.VERIFIED, verified, p.verifiedThrough.orEmpty(), true, narrow, placeholder = ProfileRows.VERIFIED_PLACEHOLDER, label = "verified through") {
                    ProfileRows.outcome(latest.send(p.id, ProfileEdit.VerifiedThrough(p.id, it), quiet = true))
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
                    control = { m -> ModelListEditor(p, list, actions, narrow, m) },
                )
            }
            TetherKey(
                onClick = { latest.send(p.id, ProfileEdit.Remove(p.id)) },
                classes = KeyClasses.ButtonSecondary,
                label = ProfileRows.REMOVE,
                contentDescription = "${ProfileRows.REMOVE} ${ProfileRows.name(p)}",
                modifier = Modifier.padding(top = 12.dp).testTag(ProfileTags.remove(p.id)),
            )
        }
    }
}

/**
 * One text row of a card: the field holds the server's value cleaned of hidden characters (the
 * edit-field rule) and is refilled when that value changes; it commits as the web's blur does
 * (Done, a focus loss, leaving the screen), never on a configuration change ([CommitField]).
 * [digits]: the order field, a number field as the web's `type="number"` (only what a browser's
 * number input lets you type: digits, `+ - . e E`); what it sends is [ProvidersPatch.orderNumber]'s.
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
    /** T8.2: a key beside the field (the home's "Browse folders", `.settings-server-input-wrap`). */
    trailing: (@Composable () -> Unit)? = null,
    onCommit: (String) -> CommitOutcome,
) {
    val shown = remember(value) { LabelText.withoutHidden(value) }
    SettingsRow(
        narrow = narrow,
        rule = !first,
        modifier = Modifier.testTag(ProfileTags.row(p.id, what)),
        text = { m -> SettingsRowText(title, caption, m) },
        control = { m ->
            @Composable
            fun field(modifier: Modifier) = CommitField(
                shown = shown,
                label = ProfileRows.field(p, label),
                tag = ProfileTags.field(p.id, what),
                enabled = enabled,
                narrow = narrow,
                placeholder = placeholder,
                keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text,
                accept = if (digits) { typed -> typed.all { it in NUMBER_KEYS } } else { _ -> true },
                onCommit = onCommit,
                modifier = modifier,
            )
            if (trailing == null) {
                field(m.serverFieldWidth(narrow))
            } else {
                Row(m.serverFieldWidth(narrow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(Modifier.weight(1f))
                    trailing()
                }
            }
        },
    )
}

/** What a browser's number input accepts typed (the order field). */
private const val NUMBER_KEYS = "0123456789+-.eE"

// ---- the env editor (:349-440) ---------------------------------------------------------------------

/** EnvEditor: one entry per key (its name, its value and remove), then the add row. */
@Composable
private fun EnvEditor(p: Profile, actions: ProfileActions, narrow: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        p.envKeys.forEach { envKey -> key(envKey) { EnvEntry(p, envKey, actions, narrow) } }
        EnvAddRow(p, actions, narrow)
    }
}

/**
 * One env entry (:376-415): its name and its value are plain fields, as the web's: a name's blur
 * renames it (onto a name the profile has: that one is overwritten, as on the web), a value's blur
 * writes it, and the key removes it, all at once. A copy of the value is marked sensitive.
 */
@Composable
private fun EnvEntry(p: Profile, envKey: String, actions: ProfileActions, narrow: Boolean) {
    val latest by rememberUpdatedState(actions)
    val valueLabel = ProfileRows.valueLabel(envKey)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownKey = remember(envKey) { LabelText.withoutHidden(envKey) }
            CommitField(
                shown = shownKey,
                label = ProfileRows.ENV_NAME,
                tag = ProfileTags.envKey(p.id, envKey),
                enabled = true,
                narrow = narrow,
                placeholder = "",
                onCommit = { typed -> ProfileRows.outcome(latest.send(p.id, ProfileEdit.EnvKey(p.id, envKey, typed), quiet = true)) },
                modifier = Modifier.weight(1f),
            )
            TetherKey(
                onClick = { latest.send(p.id, ProfileEdit.EnvRemove(p.id, envKey)) },
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 14.dp,
                contentDescription = ProfileRows.removeLabel(envKey),
                modifier = Modifier.testTag(ProfileTags.envRemove(p.id, envKey)),
            )
        }
        // The web's `defaultValue={value}`: the value as it is.
        val shown = p.envValue(envKey)?.reveal().orEmpty()
        CommitField(
            shown = shown,
            label = valueLabel,
            tag = ProfileTags.envInput(p.id, envKey),
            enabled = true,
            narrow = narrow,
            placeholder = "",
            keyboardType = KeyboardType.Password,
            sensitive = true,
            onCommit = { v -> ProfileRows.outcome(latest.send(p.id, ProfileEdit.EnvValue(p.id, envKey, SecretText(v)), quiet = true)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The add row (:417-437): NAME, the value, and Add (or Done in either field), as the web's: plain
 * fields, nothing sent until Add, a name the profile has overwritten as on the web. The draft
 * clears once sent (a refused write keeps it and says why). A copy of the value is marked sensitive.
 */
@Composable
private fun EnvAddRow(p: Profile, actions: ProfileActions, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val latest by rememberUpdatedState(actions)
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val add: () -> Unit = {
        if (jsTrim(name).isNotEmpty()) {
            when (val outcome = ProfileRows.outcome(latest.send(p.id, ProfileEdit.EnvAdd(p.id, name, SecretText(value)), quiet = true))) {
                CommitOutcome.Sent, CommitOutcome.Nothing -> {
                    name = ""
                    value = ""
                    note = null
                }
                is CommitOutcome.Refused -> note = outcome.message
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DraftField(name, { name = it; note = null }, ProfileRows.ENV_NEW_NAME, ProfileTags.envNewName(p.id), ProfileRows.ENV_NAME_PLACEHOLDER, narrow, onDone = add, modifier = Modifier.fillMaxWidth())
        DraftField(
            value, { value = it }, ProfileRows.ENV_NEW_VALUE, ProfileTags.envNewInput(p.id), ProfileRows.ENV_VALUE_PLACEHOLDER, narrow,
            onDone = add, secret = true, modifier = Modifier.fillMaxWidth(),
        )
        note?.let {
            Text(
                it,
                color = t.attentionInk,
                style = settingsText(type.ui, 12f, 500, lineHeight = 1.5f),
                modifier = Modifier.testTag(ProfileTags.envNewNote(p.id)).semantics { liveRegion = LiveRegionMode.Polite },
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

/** A draft field (never a server value): sent only by its row's action or Done. [secret]: the password keyboard (nothing learned), copies marked sensitive. */
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
    SensitiveClipScope(secret) {
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
private fun ModelListEditor(p: Profile, list: ModelList, actions: ProfileActions, narrow: Boolean, modifier: Modifier) {
    val latest by rememberUpdatedState(actions)
    val rows = p.models(list)
    var draft by remember { mutableStateOf<ModelDraft?>(null) }
    val activity = LocalContext.current.findActivity()
    val finalize: () -> Unit = {
        val d = draft
        if (d != null) {
            draft = null
            latest.send(p.id, ProfileEdit.Model(p.id, list, ModelOp.Add(d.initialId, d.id.text, d.label)))
        }
    }
    val defaultRow = rows.indexOfFirst { it.isDefault }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEachIndexed { index, row ->
            key(index, row.id) { ModelRowView(p, list, index, row, index == defaultRow, actions, narrow) }
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

@Composable
private fun ModelRowView(p: Profile, list: ModelList, index: Int, row: ProfileModel, isDefault: Boolean, actions: ProfileActions, narrow: Boolean) {
    val latest by rememberUpdatedState(actions)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownId = remember(row.id) { LabelText.withoutHidden(row.id) }
            CommitField(
                shown = shownId,
                label = ProfileRows.MODEL_ID,
                tag = ProfileTags.modelId(p.id, list, index),
                enabled = true,
                narrow = narrow,
                placeholder = "",
                onCommit = { ProfileRows.outcome(latest.send(p.id, ProfileEdit.Model(p.id, list, ModelOp.SetId(index, row.id, it)), quiet = true)) },
                modifier = Modifier.weight(1f),
            )
            TetherKey(
                onClick = { latest.send(p.id, ProfileEdit.Model(p.id, list, ModelOp.Remove(index, row.id))) },
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 14.dp,
                contentDescription = ProfileRows.removeLabel(row.id),
                modifier = Modifier.testTag(ProfileTags.modelRemove(p.id, list, index)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownLabel = remember(row.label) { LabelText.withoutHidden(row.label.orEmpty()) }
            CommitField(
                shown = shownLabel,
                label = ProfileRows.labelFor(row.id),
                tag = ProfileTags.modelLabel(p.id, list, index),
                enabled = true,
                narrow = narrow,
                placeholder = ProfileRows.MODEL_LABEL_PLACEHOLDER,
                onCommit = { ProfileRows.outcome(latest.send(p.id, ProfileEdit.Model(p.id, list, ModelOp.SetLabel(index, row.id, it)), quiet = true)) },
                modifier = Modifier.weight(1f),
            )
            DefaultRadio(
                selected = isDefault,
                enabled = true,
                description = ProfileRows.defaultLabel(row.id),
                tag = ProfileTags.modelDefault(p.id, list, index),
                onSelect = { latest.send(p.id, ProfileEdit.Model(p.id, list, ModelOp.SetDefault(index, row.id))) },
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
                onValueChange = { onChange(d.copy(id = it)) },
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
                onValueChange = { onChange(d.copy(label = it)) },
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
