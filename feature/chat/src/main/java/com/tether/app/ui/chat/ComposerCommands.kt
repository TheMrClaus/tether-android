package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.BackgroundCommandResult
import com.tether.app.client.ControlOption
import com.tether.app.client.LabelText
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.RunCommandResult
import com.tether.app.client.SelectControl
import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import com.tether.app.ui.util.providerGlyph

/*
 * T7.3: the composer's command surfaces (tether components/chat-view.tsx v128): the `!` command mode
 * (:2158-2164, 3080-3102, 3834-3843, 4479-4499), the foreground command's Background / Stop keys
 * (:4462-4476), the slash menu (:3844-3864) and the `@` Agents picker with its delegate chip
 * (:2712-2834, 3865-3905, 4077-4143). CSS: globals.css 6750-6799, 7765-7966, 11353-11360.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * T7.3: what the composer's commands may do for one session. [onRun] and [onBackground] are the
 * ONLY ways a `run-command` / `background-command` reaches the wire; the composer calls them from an
 * armed key's tap, an explicit submit (Enter, the keyboard's Send) or Ctrl+B, and nowhere else. The
 * client re-checks everything under its lock, against the server origin this was drawn for.
 */
@Immutable
class ComposerCommandActions(
    /** `capabilities.commandRunner` of the session's provider, as the server's `ready` said. */
    val commandMode: Boolean,
    val onRun: (command: String, background: Boolean) -> RunCommandResult,
    /** Background the foreground command of the turn the key was drawn for. */
    val onBackground: (turnId: String) -> BackgroundCommandResult,
    /** The server origin the composer was drawn for (a pending mention does not outlive it). */
    val origin: String? = null,
    /** T7.3 `@`: the agents the catalog offers this session ([com.tether.app.client.CommandGuard.delegateAgents]). */
    val agents: List<ProviderCatalogEntry> = emptyList(),
    /** A read: ask for the catalog (the picker opened before one arrived). */
    val onRequestAgents: () -> Unit = {},
    /** A delegated idle send; false = nothing was recorded (the draft and the chip stay). */
    val onSendDelegated: (text: String, attachments: List<com.tether.app.protocol.Attachment>, mention: DelegateMention) -> Boolean = { _, _, _ -> false },
) {
    companion object {
        val Unavailable = ComposerCommandActions(false, { _, _ -> RunCommandResult.NotConnected }, { BackgroundCommandResult.NotConnected })
    }
}

/** What the operator is told when a command tap sent nothing (null: sent, or the client already said why). */
internal fun runRefusalCopy(result: RunCommandResult): String? = when (result) {
    RunCommandResult.Sent, RunCommandResult.NotConnected -> null
    RunCommandResult.NotLive -> "Catching up — the command was not run. Try again in a moment."
    RunCommandResult.Locked -> "This session can’t run commands from here."
    RunCommandResult.NotOffered -> "Command mode isn’t offered for this session — the command was not run."
    RunCommandResult.Invalid -> "Type a command after “!” to run it."
    RunCommandResult.Busy -> "Wait for the current turn to finish, or use “Send to background”."
}

internal fun backgroundRefusalCopy(result: BackgroundCommandResult): String? = when (result) {
    BackgroundCommandResult.Sent, BackgroundCommandResult.NotConnected -> null
    BackgroundCommandResult.NotLive -> "Catching up — the command was not moved to the background. Try again in a moment."
    BackgroundCommandResult.Locked -> "This session can’t be changed from here."
    BackgroundCommandResult.NotRunning -> "That command already finished — nothing was moved to the background."
}

/** chat-view.tsx:3841. */
internal const val COMMAND_MODE_FLAG = "Command mode — runs in the session directory; the agent watches and comments on the output."

internal const val COMMAND_FLAG_TAG = "composer-command-flag"
internal const val RUN_KEY_TAG = "composer-run-command"
internal const val RUN_BACKGROUND_KEY_TAG = "composer-run-background"
internal const val BACKGROUND_KEY_TAG = "composer-background-command"
internal const val MENTION_MENU_TAG = "composer-mention-menu"
internal const val DELEGATE_BAR_TAG = "composer-delegate-bar"

/**
 * `.chat-command-flag` (role=status): the composer visibly becomes a command runner the instant the
 * draft starts with `!` — the Terminal glyph and the words carry the state, the red edge only
 * reinforces it.
 */
@Composable
internal fun CommandModeFlag() {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Row(
        Modifier
            .fillMaxWidth()
            .cssSurface(shape, background = t.dangerWash)
            .border(1.dp, t.dangerEdge, shape)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(COMMAND_FLAG_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.Terminal, contentDescription = null, tint = t.danger, modifier = Modifier.size(13.dp))
        Text(COMMAND_MODE_FLAG, style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.72f), fontWeight = FontWeight(600)), color = t.danger)
    }
}

/**
 * `.chat-slash-menu` (globals.css 7765-7820): `--graphite` under `1px --line-strong` (`--key-side` in
 * the material layer), `--radius-md`, `--shadow-menu-up`, at most 15rem, rows divided by `--line`.
 * A row: `/name` (650, `--white`) with its argument hint (`--faint`), the description (`--muted`,
 * one line) and the tag — "Tether" (violet wash) or "terminal only" (`--mineral`, the unsupported row
 * at 0.72). The first row is the active one (the web's keyboard index starts there; Enter / Tab take
 * it). T7.3: every server string is cleaned and bounded ([LabelText]); nothing is drawn raw.
 */
@Composable
fun SlashCommandMenu(
    matches: List<SessionCommandOption>,
    onAccept: (SessionCommandOption) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.keySide), t.css.shadowMenuUp)
            .padding(1.dp)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Slash commands" }
            .testTag("composer-slash-menu"),
    ) {
        matches.forEachIndexed { index, command ->
            val name = LabelText.label(command.name).ifEmpty { LabelText.visibleValue(command.name) }
            val hint = LabelText.label(command.argumentHint)
            val description = LabelText.hint(command.description)
            val tag = if (command.supported) "Tether" else "terminal only"
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.background(t.violetWash) else Modifier)
                    .clickable(role = Role.Button, onClickLabel = "Use /$name") { onAccept(command) }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = listOf("/$name" + (if (hint.isNotEmpty()) " $hint" else ""), description, tag).filter { it.isNotEmpty() }.joinToString(", ")
                        onClick("Use /$name") { onAccept(command); true }
                    }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
                    .alpha(if (command.supported) 1f else 0.72f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                Row(Modifier.widthIn(min = 112.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("/$name", style = type.body.copy(fontSize = rem(0.82f), fontWeight = FontWeight(650)), color = t.white, maxLines = 1)
                    if (hint.isNotEmpty()) Text(" $hint", style = type.body.copy(fontSize = rem(0.82f)), color = t.faint, maxLines = 1)
                }
                Text(description, style = type.body.copy(fontSize = rem(0.82f)), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MenuTag(tag, violet = command.supported, icon = if (command.supported) null else TetherIcons.Terminal)
            }
            if (index < matches.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }
    }
}

/** `.chat-slash-tag` / `.chat-mention-tag`: 1px 6px, `--radius-sm`, 0.66rem/650. */
@Composable
private fun MenuTag(text: String, violet: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val ink = if (violet) t.violet else t.faint
    Row(
        Modifier
            .background(if (violet) t.violetWash else t.mineral, RoundedCornerShape(t.radiusSm))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
        Text(text, style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650)), color = ink, maxLines = 1)
    }
}

/** chat-view.tsx:2718 — the `@` token at a word boundary, running to the end of the draft. */
private val AT_TOKEN = Regex("(?:^|\\s)@([^\\s@]*)$")

/** The query after the trailing `@` token, or null when the draft does not end in one. */
internal fun atQueryOf(draft: String): String? = AT_TOKEN.find(draft)?.groupValues?.get(1)

/** chat-view.tsx:2784 / 2803: the draft without the trailing `@query` token that opened the picker. */
internal fun stripAtToken(draft: String): String = draft.replace(AT_TOKEN, "").trimEnd()

/** chat-view.tsx:2747-2754: [agents] matching [query] by label or provider id. */
internal fun matchAgents(agents: List<ProviderCatalogEntry>, query: String): List<ProviderCatalogEntry> {
    val q = query.lowercase()
    return agents.filter { q.isEmpty() || (it.label ?: it.provider).lowercase().contains(q) || it.provider.lowercase().contains(q) }
}

/** The agent's name as drawn: its label (cleaned), else its provider id. */
internal fun agentName(entry: ProviderCatalogEntry): String =
    LabelText.label(entry.label).ifEmpty { LabelText.visibleValue(entry.provider) }

/**
 * chat-view.tsx:2793-2804 beginDelegate: the picked agent's mention. Mode starts at "review" (the
 * safer default — a plan-mode child that only reports). The catalog's default model is pre-selected
 * only when the entry lists it (so the mention is always one the catalog offers).
 */
internal fun mentionFor(entry: ProviderCatalogEntry): DelegateMention {
    val model = entry.defaultModel?.takeIf { d -> d.isNotEmpty() && entry.models.any { it.value == d } }
    return DelegateMention(provider = entry.provider, mode = "review", model = model)
}

/**
 * `.chat-mention-menu` with its **Agents** section (the Sessions section is the takeover flow, not
 * in this task): the section label, then per agent the provider mark, the name (650, `--white`), the
 * status line (never colour alone: "loading models…", "model list unavailable", the default model,
 * "N models" / "default model") and the violet "delegate" tag. The first row is the active one.
 */
@Composable
internal fun MentionMenu(agents: List<ProviderCatalogEntry>, onPick: (ProviderCatalogEntry) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 288.dp)
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.keySide), t.css.shadowMenuUp)
            .padding(1.dp)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Mention a session or an agent" }
            .testTag(MENTION_MENU_TAG),
    ) {
        Text(
            "AGENTS",
            style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650), letterSpacing = 0.04.em),
            color = t.faint,
            modifier = Modifier.padding(start = t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceSm, bottom = t.css.spaceXs).clearAndSetSemantics { contentDescription = "Agents" },
        )
        agents.forEachIndexed { index, entry ->
            val name = agentName(entry)
            val status = agentStatus(entry)
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.background(t.violetWash) else Modifier)
                    .clickable(role = Role.Button, onClickLabel = "Delegate the next message to $name") { onPick(entry) }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = "$name, $status, delegate"
                        onClick("Delegate the next message to $name") { onPick(entry); true }
                        testTag = "mention-agent-${entry.key}"
                    }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    ProviderLogo(entry.provider, fallback = providerGlyph(entry.provider), color = t.muted, markSize = 16.dp, letterSize = 11.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = type.body.copy(fontSize = rem(0.82f), fontWeight = FontWeight(650)), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = type.body.copy(fontSize = rem(0.72f)), color = if (entry.status == "ready" && modelName(entry) != null) t.faint else t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MenuTag("delegate", violet = true)
            }
        }
    }
}

private fun modelName(entry: ProviderCatalogEntry): String? {
    val d = entry.defaultModel?.takeIf { it.isNotEmpty() } ?: return null
    val listed = entry.models.firstOrNull { it.value == d }?.displayName
    return LabelText.label(listed).ifEmpty { LabelText.label(d).ifEmpty { LabelText.visibleValue(d) } }
}

/** chat-view.tsx:3892-3898: the agent's status line, in words. */
internal fun agentStatus(entry: ProviderCatalogEntry): String = when (entry.status) {
    "loading" -> "loading models…"
    "error" -> "model list unavailable"
    else -> modelName(entry) ?: if (entry.models.isNotEmpty()) "${entry.models.size} models" else "default model"
}

/**
 * `.chat-delegate-bar` (chat-view.tsx:4077-4143): the chip — provider mark, `@name`, the model,
 * the mode (uppercase, `--faint`), a 44dp X — then the delegate's Model, Effort and Mode selects,
 * read from the picked agent's live catalog row (hidden when the row is gone: the chip stays, and
 * the client refuses a mention the catalog no longer offers). Every server string is cleaned.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DelegateBar(
    mention: DelegateMention,
    entry: ProviderCatalogEntry?,
    onChange: (DelegateMention) -> Unit,
    onRemove: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val name = entry?.let(::agentName) ?: LabelText.visibleValue(mention.provider)
    val modelLabel = mention.model?.let { m ->
        LabelText.label(entry?.models?.firstOrNull { it.value == m }?.displayName).ifEmpty { LabelText.label(m).ifEmpty { LabelText.visibleValue(m) } }
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusMd), t.mineralDeep, CssBorder(1.dp, t.lineStrong))
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs)
            .semantics { contentDescription = "Delegating to an agent" }
            .testTag(DELEGATE_BAR_TAG),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .cssSurface(RoundedCornerShape(t.radiusMd), t.mineral, CssBorder(1.dp, t.line))
                    .padding(start = t.css.spaceSm, end = 2.dp, top = 2.dp, bottom = 2.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Delegate to $name" + (modelLabel?.let { ", $it" } ?: "") + ", ${mention.mode}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    ProviderLogo(mention.provider, fallback = providerGlyph(mention.provider), color = t.muted, markSize = 16.dp, letterSize = 11.sp)
                }
                Text("@$name", style = type.body.copy(fontSize = rem(0.8f), fontWeight = FontWeight(650)), color = t.ink, maxLines = 1)
                if (modelLabel != null) {
                    Text(modelLabel, style = type.body.copy(fontSize = rem(0.72f)), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                }
                Text(mention.mode.uppercase(), style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650), letterSpacing = 0.04.em), color = t.faint)
                Box(
                    Modifier
                        .size(36.dp)
                        .clickable(role = Role.Button, onClickLabel = "Remove", onClick = onRemove)
                        .semantics { contentDescription = "Remove the delegate mention" }
                        .testTag("delegate-remove"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (entry != null) {
            val models = delegateModelControl(entry, mention)
            if (models.options.size > 1) {
                ControlSelect(models, name = "Delegate model", enabled = true, lockCopy = null, testTag = "delegate-model", onSelect = { value ->
                    onChange(mention.copy(model = value.ifEmpty { null }, reasoningEffort = null))
                })
            }
            val efforts = delegateEffortControl(entry, mention)
            if (efforts.options.size > 1) {
                ControlSelect(efforts, name = "Delegate reasoning effort", enabled = true, lockCopy = null, testTag = "delegate-effort", onSelect = { value ->
                    onChange(mention.copy(reasoningEffort = value.ifEmpty { null }))
                })
            }
        }
        ControlSelect(delegateModeControl(mention), name = "Delegate mode", enabled = true, lockCopy = null, testTag = "delegate-mode", onSelect = { value ->
            onChange(mention.copy(mode = if (value == "build") "build" else "review"))
        })
    }
}

/** chat-view.tsx:2817-2823: "Default" then the agent's models (cleaned labels, raw values). */
internal fun delegateModelControl(entry: ProviderCatalogEntry, mention: DelegateMention): SelectControl =
    SelectControl(
        value = mention.model ?: "",
        options = listOf(ControlOption("", "Default")) + entry.models.map { m ->
            ControlOption(m.value, LabelText.label(m.displayName).ifEmpty { LabelText.visibleValue(m.value) })
        },
    )

/** chat-view.tsx:2827-2831: "Default" then the picked model's variants; none without a model. */
internal fun delegateEffortControl(entry: ProviderCatalogEntry, mention: DelegateMention): SelectControl {
    val model = mention.model?.let { m -> entry.models.firstOrNull { it.value == m } }
    val variants = model?.variants.orEmpty()
    return SelectControl(
        value = mention.reasoningEffort ?: "",
        options = if (model == null) emptyList() else listOf(ControlOption("", "Default")) + variants.map { v ->
            ControlOption(v.value, LabelText.label(v.label).ifEmpty { LabelText.visibleValue(v.value) })
        },
    )
}

/** chat-view.tsx:4137-4139: review = the delegate reports findings (plan mode); build = it may edit. */
internal fun delegateModeControl(mention: DelegateMention): SelectControl = SelectControl(
    value = mention.mode,
    options = listOf(
        ControlOption("review", "review", description = "The delegate reports findings (plan mode)."),
        ControlOption("build", "build", description = "The delegate may edit, in this session’s own mode."),
    ),
)
