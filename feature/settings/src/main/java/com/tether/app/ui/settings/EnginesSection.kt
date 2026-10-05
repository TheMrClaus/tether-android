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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.EngineCard
import com.tether.app.client.LabelText
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsPatch
import com.tether.app.client.ServerSettingsView
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.ProviderLogoDefaults
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
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
}

/**
 * The Engines section and the engine cards (settings-dialog.tsx 90fbb9f :2107-2236), for ONE server
 * (the panel keys this on [ServerSettingsBinding.origin], so another server's cards start from
 * nothing: no half-typed field).
 *
 * Writes as on the web (ta-coik.5, owner rule 2026-10-03: no app-only confirmation):
 * - an engine's switch (`headlessModes`) writes at once;
 * - its home, command and launch command write as the web's field does on blur (:2174, :2209,
 *   :2226): on Done, when the field loses focus, and when it leaves the screen with an edit in it
 *   ([CommitField]); never on a configuration change;
 * - "Use detected" (:2191-2194) writes the detected home at once.
 */
@Composable
internal fun EngineCards(binding: ServerSettingsBinding, narrow: Boolean) {
    // No server, nothing drawn (the web draws the tab only once `serverSettings` arrives).
    val view = binding.settings?.takeIf { binding.origin != null }
    if (view == null) {
        SettingsSection(EngineRows.TITLE, AnnotatedString(EngineRows.CAPTION), narrow, modifier = Modifier.testTag(EngineTags.Section)) { ServerSettingsLoading() }
        return
    }
    SettingsSection(EngineRows.TITLE, AnnotatedString(EngineRows.CAPTION), narrow, modifier = Modifier.testTag(EngineTags.Section)) {
        ScanAgain(binding, view)
    }
    EngineCard.entries.forEach { engine ->
        EngineCardView(engine, view, binding, narrow)
    }
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
private fun EngineCardView(engine: EngineCard, view: ServerSettingsView, binding: ServerSettingsBinding, narrow: Boolean) {
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
            // A verified mark takes its brand tile instead (globals.css 11204-11224).
            ProviderTile(
                engine.id,
                Modifier.size(ProviderLogoDefaults.GlyphSize),
                fallback = engine.glyph,
                shape = RoundedCornerShape(7.2.dp),
                background = t.graphiteRaised,
                color = ProviderLogoDefaults.color(engine.id),
            )
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
                engine, engine.home, view, binding, narrow,
                title = EngineRows.HOME,
                caption = AnnotatedString(EngineRows.homeCaption(view, engine)),
                placeholder = det?.configDir ?: EngineRows.HOME_PLACEHOLDER,
                first = true,
            )
            // :2191 `noHome && det?.configDir`: env-forced or not, as on the web.
            val detected = ServerSettingsPatch.detectedHome(view, engine)
            if (detected != null) {
                SettingsRow(
                    narrow = narrow,
                    text = { m ->
                        val caption = buildAnnotatedString { withStyle(SpanStyle(color = t.running)) { append(codeLabel(detected)) } }
                        SettingsRowText(EngineRows.DETECTED_HOME, caption, m)
                    },
                    control = { m ->
                        TetherKey(
                            onClick = { latest.settings?.takeIf { latest.origin != null }?.let { latest.send(ServerSettingsPatch.useDetected(it, engine)) } },
                            classes = KeyClasses.ButtonSecondary,
                            label = EngineRows.USE_DETECTED,
                            modifier = m.testTag(EngineTags.useDetected(engine)),
                        )
                    },
                )
            }
            EngineValueRow(
                engine, engine.command, view, binding, narrow,
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
                EngineValueRow(engine, launch, view, binding, narrow, title = EngineRows.LAUNCH, caption = caption, placeholder = EngineRows.LAUNCH_EXAMPLE)
            }
        }
    }
}

/**
 * One home / command / launch command row: the field holds the server's value cleaned of hidden
 * characters (the edit-field rule) and is filled again when the server's value changes (the web's
 * `key={value}` remount). It writes as the web's blur does ([CommitField]:
 * [ServerSettingsPatch.engineValue]); a refused send says so under the field.
 */
@Composable
private fun EngineValueRow(
    engine: EngineCard,
    setting: ServerSetting,
    view: ServerSettingsView,
    binding: ServerSettingsBinding,
    narrow: Boolean,
    title: String,
    caption: AnnotatedString,
    placeholder: String,
    first: Boolean = false,
) {
    val forced = view.forced(setting)
    val raw = view.text(setting)
    val shown = remember(raw) { LabelText.withoutHidden(raw) }
    SettingsRow(
        narrow = narrow,
        rule = !first,
        modifier = Modifier.testTag(ServerSettingsTags.row(setting)),
        text = { m -> SettingsRowText(title, caption, m, locked = forced) },
        control = { m ->
            CommitField(
                shown = shown,
                label = EngineRows.fieldLabel(engine, setting),
                tag = ServerSettingsTags.input(setting),
                enabled = !forced,
                narrow = narrow,
                placeholder = "",
                // The placeholder is server text (a detected path): drawn by the code rule.
                styledPlaceholder = codeLabel(placeholder),
                // Never past the server's limit (it would refuse the write).
                accept = { ServerSettingsPatch.fits(setting, it) },
                onCommit = { serverCommit(ServerSettingsPatch.engineValue(view, setting, it, shown), binding::send) },
                modifier = m.serverFieldWidth(narrow),
            )
        },
    )
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
