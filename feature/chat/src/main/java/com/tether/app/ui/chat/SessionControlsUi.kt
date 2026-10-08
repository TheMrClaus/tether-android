package com.tether.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tether.app.client.CodexSnapshot
import com.tether.app.client.ComposerControls
import com.tether.app.client.ControlOption
import com.tether.app.client.ControlResult
import com.tether.app.client.OpencodeSnapshot
import com.tether.app.client.ProviderControlsState
import com.tether.app.client.SelectControl
import com.tether.app.client.SessionControl
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.DisabledOpacity
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSheet
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.icons.ProviderInlineGlyph
import com.tether.app.ui.icons.ProviderLogoDefaults
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.providerGlyph
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * T7.2: what the composer's session controls may do for one session. [onControl] is the ONLY way a
 * control reaches the wire, and the controls call it from a tap or accessibility action and nowhere
 * else; the client re-checks everything ([com.tether.app.client.SessionControlsGuard]) under its lock.
 */
@Immutable
class SessionControlActions(
    val sessionId: String?,
    /** The live socket's server origin the row was drawn for (null: none). */
    val origin: String?,
    val lock: ConsentLock?,
    val onControl: (SessionControl) -> ControlResult,
    val codex: ProviderControlsState<CodexSnapshot>? = null,
    val opencode: ProviderControlsState<OpencodeSnapshot>? = null,
    /** Reads (never operator actions): ask for the provider catalogs again. */
    val onRequestCodex: () -> Unit = {},
    val onRequestOpencode: () -> Unit = {},
) {
    companion object {
        val Unavailable = SessionControlActions(null, null, ConsentLock.Offline, { ControlResult.NotConnected })
    }
}

/** Why a control cannot be used right now, in words (status is never colour alone). */
internal fun controlLockCopy(lock: ConsentLock?): String? = when (lock) {
    null -> null
    ConsentLock.Offline -> "Connect to change session settings."
    ConsentLock.ReadOnly -> "Read-only: Tether isn’t driving this conversation."
    ConsentLock.HandedOff -> "This session was handed off."
}

/** What the operator is told when a tap sent nothing. */
internal fun controlRefusalCopy(result: ControlResult): String? = when (result) {
    ControlResult.Sent -> null
    ControlResult.NotConnected -> "Not connected — the setting was not changed."
    // ta-coik.24: the client's NotLive means only "drawn for another server" (RealTetherClient
    // sessionControl); the session's liveness gates nothing, as on the web (use-tether.ts 90fbb9f :337-344).
    ControlResult.NotLive -> OTHER_SERVER_NOT_SENT
    ControlResult.Locked -> "This session can’t be changed from here."
    ControlResult.NotOffered -> "That option is no longer offered — the setting was not changed."
}

/** v95 fast-mode reason copy (session-settings-sheet.tsx:39-55), never the raw enum string. */
internal fun fastModeReasonCopy(reason: String?): String? {
    if (reason.isNullOrEmpty()) return null
    return FAST_MODE_REASON_COPY[reason] ?: "Not available right now."
}

private val FAST_MODE_REASON_COPY = mapOf(
    "free" to "Not included on your current plan.",
    "preference" to "Turned off in your account preferences.",
    "extra_usage_disabled" to "Requires extra usage to be enabled on your account.",
    "network_error" to "Couldn't reach the network just now — try again shortly.",
    "unknown" to "Not available right now.",
    "not_first_party" to "Not available through this connection.",
    "disabled_by_env" to "Disabled for this deployment.",
    "model_not_allowed" to "Not available on this model.",
    "sdk_opt_in_required" to "Not available in this session.",
    "pending" to "Turning on…",
)

internal const val FAST_MODE_COST_HINT = "Responds faster, but uses more of your usage allowance per turn."

/**
 * ta-coik.9: a session control acts on its FIRST tap, as on the web (chat-view.tsx:4352-4413, the
 * Mode select, the Auto chip and the Auto-continue checkbox; codex-controls.tsx /
 * opencode-serve-controls.tsx, buttons disabled only while busy): there is no arm delay. The one
 * guard kept is the stale-tap guard: [content] is keyed by [identity], so a press that began on a
 * control which changed under the finger (another option now at that row, the toggle's other
 * state, a new catalog revision) goes away with the old node instead of landing on the new one.
 * Touches through an overlay are refused, as before.
 */
@Composable
internal fun StaleTapGuard(identity: Any, content: @Composable (Modifier) -> Unit) {
    key(identity) { content(Modifier.refuseObscuredTouches()) }
}

/**
 * The operator's handlers for the row and the sheet (Composer builds them; each ends in
 * [SessionControlActions.onControl]).
 */
internal class ControlHandlers(
    val chooseModel: (String) -> Unit,
    val chooseEffort: (String) -> Unit,
    val chooseMode: (String) -> Unit,
    val toggleAuto: () -> Unit,
    val setFast: (Boolean) -> Unit,
    /** Codex v2 "Auto approve" (the sheet's approval row). */
    val setAutoApprove: (Boolean) -> Unit,
    /** Opens the provider-controls panel (Codex v2 / opencode-serve v2), or null. */
    val openProviderControls: (() -> Unit)?,
    /** Re-reads the provider catalogs (a read, never an action) as the panel opens in the sheet. */
    val requestProviderControls: () -> Unit = {},
    /** Opens the session sheet at a view (the wide row's Fast key, round 2 I6). */
    val openSheet: (SheetView) -> Unit = {},
    /** T6.6: set-auto-continue-on-limit (the flip of the drawn value; the Composer checks it). */
    val setAutoContinue: (Boolean) -> Unit = {},
    /**
     * ta-coik.55: pin / unpin a legacy model on this server (chat-view.tsx 90fbb9f :2281-2286
     * toggleModelPin), drawn by the wide row's Model menu only (the web's sheet has no pin key).
     */
    val toggleModelPin: ((String) -> Unit)? = null,
)

// ---------------------------------------------------------------------------------------------
// The wide row (the web's `.chat-mode-row-live`, shown from 64rem)
// ---------------------------------------------------------------------------------------------

/**
 * `.chat-mode-row.chat-mode-row-live` (chat-view.tsx:4203-4373): the Model / Effort / Mode pills,
 * each named by its icon (Cpu, Brain, Shield — the word is the pill's accessible name), the Auto
 * toggle where Auto is not a Mode row, a Provider controls key (Android: the web keeps those panels
 * in Settings) and the flex-1 hint. Every control acts on the first tap ([StaleTapGuard]).
 */
@Composable
internal fun ComposerOptionsRow(
    controls: ComposerControls,
    provider: String,
    lock: ConsentLock?,
    handlers: ControlHandlers,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val enabled = lock == null
    Row(
        modifier.fillMaxWidth().padding(bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        controls.model?.let { model ->
            RowIcon(TetherIcons.Cpu)
            ControlSelect(
                control = model,
                name = "Model",
                enabled = enabled && model.enabled,
                lockCopy = controlLockCopy(lock),
                glyphProvider = provider,
                maxWidth = 176.dp,
                onSelect = handlers.chooseModel,
                testTag = "control-model",
                onPin = handlers.toggleModelPin,
            )
        }
        controls.effort?.let { effort ->
            RowIcon(TetherIcons.Brain)
            ControlSelect(
                control = effort,
                name = "Reasoning effort",
                enabled = enabled && effort.enabled,
                lockCopy = controlLockCopy(lock),
                onSelect = handlers.chooseEffort,
                testTag = "control-effort",
            )
        }
        controls.mode?.let { mode ->
            RowIcon(TetherIcons.Shield)
            ControlSelect(
                control = mode,
                name = controls.modeAriaLabel,
                enabled = enabled && mode.enabled,
                lockCopy = controlLockCopy(lock),
                danger = mode.current?.danger == true,
                armedRows = true,
                onSelect = handlers.chooseMode,
                testTag = "control-mode",
            )
        }
        controls.auto?.let { auto ->
            AutoChip(on = auto.on, enabled = enabled, lockCopy = controlLockCopy(lock), onToggle = handlers.toggleAuto)
        }
        // Round 2 (I6): the web's desktop row has no Fast control at all; from 64rem the app offers it
        // as a key that opens the sheet's Fast list (the only place the web sets it).
        controls.fastMode?.let { fast ->
            val value = when (fast.state) {
                "on" -> "On"
                "cooldown" -> "Cooldown"
                else -> "Off"
            }
            ControlPill(
                label = "Fast: $value",
                icon = TetherIcons.Zap,
                enabled = enabled,
                active = fast.state == "on",
                contentDescription = "Fast mode: $value",
                stateDescription = controlLockCopy(lock),
                onClick = { handlers.openSheet(SheetView.Fast) },
                testTag = "control-fast",
            )
        }
        // T6.6 (chat-view.tsx:4331-4348): the Auto-continue checkbox; Check when on, Clock when off.
        controls.autoContinue?.let { ac ->
            // ta-coik.9: the first tap acts (chat-view.tsx:4401-4405); a press on the other state is dropped.
            StaleTapGuard("auto-continue" to ac.on) { guard ->
                ControlPill(
                    label = "Auto-continue",
                    icon = if (ac.on) TetherIcons.Check else TetherIcons.Clock,
                    enabled = enabled,
                    modifier = guard,
                    active = ac.on,
                    chevron = false,
                    role = Role.Checkbox,
                    contentDescription = "Auto-continue when the limit resets",
                    stateDescription = controlLockCopy(lock) ?: if (ac.on) "On" else "Off",
                    onClick = { handlers.setAutoContinue(!ac.on) },
                    testTag = "control-auto-continue",
                )
            }
        }
        handlers.openProviderControls?.let { open ->
            ControlPill(
                label = "Provider controls",
                icon = TetherIcons.SlidersHorizontal,
                enabled = true,
                contentDescription = "Provider controls",
                onClick = open,
                chevron = false,
            )
        }
        Text(
            controls.hint,
            style = type.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(if (controls.hintDanger) 600 else 400)),
            color = if (controls.hintDanger) t.warning else t.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).widthIn(min = 0.dp),
        )
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    val t = LocalTetherTokens.current
    // Decorative: the pill beside it carries the name (ModeRowIconLabel's visually-hidden word).
    Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp).padding(end = 0.dp))
}

/**
 * `:root .chat-mode-select` (globals.css 8952-8960, 11309): a raised dropdown cap — `--key-face`
 * over `1px --key-side`, lit top + `--shadow-key-sm`, a 999px pill, 1.9rem tall, 0.7rem/600 white;
 * `is-danger`: amber edge, `--warning` legend. Studio (studio.css:392): `--graphite-raised`, no
 * edge or shadow, 2.25rem, 0.72rem. The touch target is 44dp tall around the visual cap.
 */
@Composable
fun ControlPill(
    label: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    glyphProvider: String? = null,
    /**
     * ta-coik.45: how [glyphProvider] is drawn. False: the chat Model select's `.chat-mode-glyph`
     * (an inline 14px glyph, the mark tinted by its brand). True: the model browser chip's
     * `.provider-glyph.draft-chip-glyph` (a 1.1rem tile, the mark at 58%, the brand tile when verified).
     */
    glyphTile: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    chevron: Boolean = true,
    maxWidth: Dp = Dp.Unspecified,
    stateDescription: String? = null,
    role: Role = Role.DropdownList,
    testTag: String? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    val ink = when {
        danger -> t.warning
        active -> t.violetDeep
        else -> t.white
    }
    val edge = null
    Box(
        modifier
            .heightIn(min = 44.dp)
            .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            .semantics(mergeDescendants = true) {}
            .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            .clearAndSetSemantics {
                this.role = role
                this.contentDescription = contentDescription
                stateDescription?.let { this.stateDescription = it }
                if (!enabled) disabled() else onClick(label) { onClick(); true }
                testTag?.let { this.testTag = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    alpha = if (enabled) 1f else DisabledOpacity
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    translationY = if (pressed && enabled) 1.dp.toPx() else 0f
                }
                .height(36.dp)
                .cssSurface(
                    shape,
                    t.graphiteRaised,
                    edge,
                    emptyList(),
                )
                .padding(horizontal = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            if (glyphProvider != null && glyphTile) {
                // `.draft-chip-glyph` (globals.css 8931-8937) on Studio's `.provider-glyph` (studio.css 340).
                ProviderTile(
                    glyphProvider,
                    Modifier.size(17.6.dp),
                    fallback = providerGlyph(glyphProvider),
                    shape = RoundedCornerShape(7.2.dp),
                    background = t.graphiteRaised,
                    color = ink,
                    markSize = ProviderLogoDefaults.markSize(17.6.dp),
                    letterSize = 8.8.sp,
                )
            } else if (glyphProvider != null) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    ProviderInlineGlyph(glyphProvider, fallback = providerGlyph(glyphProvider), color = ink, markSize = 14.dp, letterSize = 9.6.sp)
                }
            }
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
            Text(
                label,
                style = type.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(600)),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (chevron) Icon(TetherIcons.ChevronDown, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
        }
    }
}

/**
 * A [ControlPill] that opens its rows in a drop-up menu (TetherSelect `dropUp`). ta-xki: public for the
 * new-session composer's live row (feature/shell); [emptyLabel] is what the pill says while its value
 * matches no row (the draft's untouched effort: "Default"), instead of the first row's label.
 */
@Composable
fun ControlSelect(
    control: SelectControl,
    name: String,
    enabled: Boolean,
    lockCopy: String?,
    onSelect: (String) -> Unit,
    glyphProvider: String? = null,
    danger: Boolean = false,
    armedRows: Boolean = false,
    maxWidth: Dp = Dp.Unspecified,
    testTag: String,
    emptyLabel: String? = null,
    // ta-23f: TetherSelect `triggerIcon` (the worktree select's Split glyph) and the chip's `is-active`.
    icon: ImageVector? = null,
    active: Boolean = false,
    /** ta-coik.55: the pin / unpin key on `pinned` / `pinnable` rows (TetherSelect onPinAction); keeps the menu open. */
    onPin: ((String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var opensUp by remember { mutableStateOf(true) }
    val t = LocalTetherTokens.current
    val gap = with(LocalDensity.current) { t.css.spaceXs.roundToPx() }
    val label = control.current?.label ?: emptyLabel ?: control.label.ifEmpty { control.options.firstOrNull()?.label ?: "" }
    val maxPill = if (maxWidth != Dp.Unspecified) maxWidth else (LocalConfiguration.current.screenWidthDp * 0.3f).dp
    Box {
        ControlPill(
            label = label,
            enabled = enabled,
            contentDescription = "$name: $label",
            stateDescription = lockCopy ?: if (open) "Expanded" else "Collapsed",
            onClick = { open = !open },
            glyphProvider = glyphProvider,
            danger = danger,
            maxWidth = maxPill,
            testTag = testTag,
            icon = icon,
            active = active,
        )
        if (open) {
            val provider = remember(gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                        // dropUp: above the pill unless there is no room there.
                        val up = anchorBounds.top >= popupContentSize.height + gap || anchorBounds.top > windowSize.height - anchorBounds.bottom
                        opensUp = up
                        val y = if (up) anchorBounds.top - gap - popupContentSize.height else anchorBounds.bottom + gap
                        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        return IntOffset(x, y.coerceAtLeast(0))
                    }
                }
            }
            Popup(popupPositionProvider = provider, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                ControlMenu(
                    control = control,
                    armedRows = armedRows,
                    opensUp = opensUp,
                    onSelect = { value ->
                        open = false
                        onSelect(value)
                    },
                    onPin = onPin,
                )
            }
        }
    }
}

/**
 * The open menu (`.tether-select-menu`, globals.css 8174-8246): `--graphite`, `1px --key-side`,
 * `--radius-md`, `--shadow-menu-up`; ≥44dp rows; the "Legacy models" group as a labelled section
 * with its staleness note (the phone sheet's form of the web's flyout).
 */
@Composable
internal fun ControlMenu(control: SelectControl, armedRows: Boolean, opensUp: Boolean, onSelect: (String) -> Unit, onPin: ((String) -> Unit)? = null) {
    val t = LocalTetherTokens.current
    val screen = LocalConfiguration.current
    Column(
        Modifier
            .semantics { role = Role.DropdownList }
            .widthIn(min = 176.dp, max = minOf(448.dp, screen.screenWidthDp.dp - t.css.spaceLg))
            .width(IntrinsicSize.Max)
            .heightIn(max = minOf(416.dp, (screen.screenHeightDp * 0.6f).dp))
            .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, CssBorder(1.dp, t.keySide), if (opensUp) t.css.shadowMenuUp else t.css.shadowMenu)
            .padding(1.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ControlOptionList(control, armedRows, onSelect, divided = true, onPin = onPin)
    }
}

/** The rows of [control] (and its legacy group), shared by the menu and the sheet. */
@Composable
internal fun ControlOptionList(
    control: SelectControl,
    armedRows: Boolean,
    onSelect: (String) -> Unit,
    divided: Boolean,
    selectedOverride: String? = control.value,
    onPin: ((String) -> Unit)? = null,
) {
    // ta-coik.55: chat-view.tsx 90fbb9f :2307 — only rows marked pinned / pinnable carry the pin action.
    fun pinOf(option: ControlOption): (() -> Unit)? = onPin?.takeIf { option.pinned || option.pinnable }?.let { pin -> { pin(option.value) } }
    control.options.forEachIndexed { index, option ->
        ControlOptionRow(option, option.value == selectedOverride, armedRows, divider = divided && (index < control.options.lastIndex || control.legacy.isNotEmpty()), onPin = pinOf(option)) { onSelect(option.value) }
    }
    if (control.legacy.isNotEmpty()) {
        SectionLabel("Legacy models")
        control.legacyNote?.let { SheetHint(it) }
        control.legacy.forEachIndexed { index, option ->
            ControlOptionRow(option, option.value == selectedOverride, armedRows, divider = divided && index < control.legacy.lastIndex, onPin = pinOf(option)) { onSelect(option.value) }
        }
    }
}

/**
 * One row (`.tether-select-option`): ≥44dp, the label (white/650 when selected, `--warning` when
 * danger), its description in `--muted`, a provider tag, and a leading-edge-free violet Check on
 * the selected row (colour never carries the state alone: the check and the selected state do).
 * [armedRow] (kept name): the row carries the [StaleTapGuard]; it has no arm delay (ta-coik.9).
 */
@Composable
fun ControlOptionRow(option: ControlOption, selected: Boolean, armedRow: Boolean, divider: Boolean, onPin: (() -> Unit)? = null, onClick: () -> Unit) {
    // ta-coik.9: a guarded row acts on the first tap; a press on the option that was at this row before is dropped.
    if (armedRow) StaleTapGuard(option.value) { guard -> OptionRowBody(option, selected, divider, guard, onPin, onClick) }
    else OptionRowBody(option, selected, divider, Modifier, onPin, onClick)
}

@Composable
private fun OptionRowBody(option: ControlOption, selected: Boolean, divider: Boolean, guard: Modifier, onPin: (() -> Unit)?, onClick: () -> Unit) {
    if (onPin != null) PinnableOptionRowBody(option, selected, divider, guard, onPin, onClick) else PlainOptionRowBody(option, selected, divider, guard, onClick)
}

@Composable
private fun PlainOptionRowBody(option: ControlOption, selected: Boolean, divider: Boolean, guard: Modifier, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val usable = !option.disabled
    val labelColor = when {
        option.danger -> t.warning
        selected -> t.white
        else -> t.ink
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(guard)
            .clickable(interaction, indication = null, enabled = usable, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                this.selected = selected
                contentDescription = listOfNotNull(option.label, option.tag, option.description).joinToString(", ")
                if (!usable) disabled() else onClick(option.label) { onClick(); true }
                testTag = "control-option-${option.value}"
            }
            .graphicsLayer {
                alpha = if (option.disabled) DisabledOpacity else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(if (pressed && usable) Modifier.background(t.violetWash) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        ) {
            OptionCopy(option, selected, labelColor, Modifier.weight(1f, fill = false))
            if (selected) Icon(TetherIcons.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** `.tether-select-option-copy`: the label (with its provider tag) over the description. */
@Composable
private fun OptionCopy(option: ControlOption, selected: Boolean, labelColor: Color, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(option.label, style = type.body.copy(fontSize = 13.12.sp, lineHeight = 1.35.em, fontWeight = FontWeight(if (selected) 650 else 400)), color = labelColor)
            if (option.tag != null) {
                Text(
                    option.tag!!,
                    style = type.body.copy(fontSize = 10.4.sp, fontWeight = FontWeight(500)),
                    color = t.muted,
                    modifier = Modifier
                        .padding(start = 5.2.dp)
                        .cssSurface(RoundedCornerShape(3.dp), border = CssBorder(1.dp, t.line))
                        .padding(horizontal = 4.16.dp, vertical = 0.52.dp),
                )
            }
        }
        option.description?.let { Text(it, style = type.body.copy(fontSize = 11.2.sp, lineHeight = 1.45.em), color = t.muted) }
    }
}

/**
 * ta-coik.55: a `pinned` / `pinnable` model row (tether-select.tsx 90fbb9f :271-310): the row's own
 * select area, then `.tether-select-option-actions` — the pin key and the selected Check. The pin key
 * is its own button beside the row's (the web's `stopPropagation`): it never selects the model and
 * never closes the menu; the list re-groups as the server's pinned set changes.
 */
@Composable
private fun PinnableOptionRowBody(option: ControlOption, selected: Boolean, divider: Boolean, guard: Modifier, onPin: () -> Unit, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val usable = !option.disabled
    val labelColor = when {
        option.danger -> t.warning
        selected -> t.white
        else -> t.ink
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (option.disabled) DisabledOpacity else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(if (pressed && usable) Modifier.background(t.violetWash) else Modifier),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .then(guard)
                    .clickable(interaction, indication = null, enabled = usable, role = Role.Button, onClick = onClick)
                    .clearAndSetSemantics {
                        role = Role.Button
                        this.selected = selected
                        contentDescription = listOfNotNull(option.label, option.tag, option.description).joinToString(", ")
                        if (!usable) disabled() else onClick(option.label) { onClick(); true }
                        testTag = "control-option-${option.value}"
                    }
                    .heightIn(min = 44.dp)
                    .padding(start = t.css.spaceMd, top = t.css.spaceMd, bottom = t.css.spaceMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptionCopy(option, selected, labelColor, Modifier.weight(1f, fill = false))
            }
            Row(
                Modifier.padding(end = t.css.spaceMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                ModelPinKey(option, onPin)
                if (selected) Icon(TetherIcons.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/**
 * `.tether-select-pin` (globals.css 90fbb9f :7678-7711, :7976-7995): a 1.625rem key — `--key-face`,
 * `1px --key-side`, `--radius-sm`, a 13px Pin (PinOff while pinned) in `--ink`; pressed takes
 * `--key-face-hover` and `--white`. Always visible on touch (the web hides the unpin key only on a
 * fine pointer until hover). Named as the web names it ("Pin <label>" / "Unpin <label>"); a 48dp
 * touch target around the key.
 */
@Composable
private fun ModelPinKey(option: ControlOption, onPin: () -> Unit) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val name = if (option.pinned) "Unpin ${option.label}" else "Pin ${option.label}"
    Box(
        Modifier
            .size(48.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onPin)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = name
                onClick(name) { onPin(); true }
                testTag = "control-pin-${option.value}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .cssSurface(RoundedCornerShape(t.radiusSm), if (pressed) t.keyFaceHover else t.keyFace, CssBorder(1.dp, t.keySide)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (option.pinned) TetherIcons.PinOff else TetherIcons.Pin,
                contentDescription = null,
                tint = if (pressed) t.white else t.ink,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * issue #48's standalone Auto toggle (`.chat-mode-select.draft-auto-chip`): Zap + "Auto"; on =
 * `is-danger` AND the state spoken ("On"), so the posture is never colour alone. ta-coik.9: the
 * first tap toggles (chat-view.tsx:4384-4387); a press on the other state is dropped ([StaleTapGuard]).
 */
@Composable
private fun AutoChip(on: Boolean, enabled: Boolean, lockCopy: String?, onToggle: () -> Unit) {
    StaleTapGuard("auto" to on) { guard ->
        ControlPill(
            label = "Auto",
            icon = TetherIcons.Zap,
            enabled = enabled,
            danger = on,
            chevron = false,
            role = Role.Switch,
            contentDescription = "Auto mode",
            stateDescription = lockCopy ?: if (on) "On — runs everything without asking" else "Off",
            onClick = onToggle,
            modifier = guard,
            testTag = "control-auto",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The phone key + the session settings sheet (below 64rem)
// ---------------------------------------------------------------------------------------------

/**
 * `.settings-sheet-trigger.settings-sheet-trigger-combined` in the phone toolbar (globals.css
 * 11936-11938): a flexible key — provider mark, the model name, "Auto" in `--warning` while Auto is
 * on (and a `--warning` edge), the sliders glyph. Opens the sheet at its hub, or straight at the
 * model list when there is nothing else to set.
 */
@Composable
internal fun SessionSettingsTrigger(
    label: String,
    provider: String,
    autoOn: Boolean,
    /** Round 2 (M1): the stored mode is unknown to the app — the warning edge and the word "Unknown". */
    unknownMode: Boolean = false,
    lock: ConsentLock?,
    hasOtherSettings: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * ta-8h5k (M1): the 48rem-63.99rem key (globals.css 6743-6785 + studio.css:400): its own row above the footer,
     * a 36 dp pill (radius 999, padding 0 8, gap 4) at its content width. The phone key stays the 44 dp flexible one.
     */
    pill: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = if (pill) RoundedCornerShape(percent = 50) else RoundedCornerShape(10.dp)
    val warn = autoOn || unknownMode
    val name = if (hasOtherSettings) {
        "Session settings: $label${if (autoOn) ", Auto approve on" else ""}${if (unknownMode) ", unknown mode" else ""}"
    } else {
        "Choose provider and model"
    }
    Row(
        modifier
            .heightIn(min = if (pill) 36.dp else 44.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = name
                controlLockCopy(lock)?.let { stateDescription = it }
                onClick(name) { onOpen(); true }
                testTag = "session-settings-trigger"
            }
            .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
            .cssSurface(
                shape,
                t.graphiteRaised,
                null,
                emptyList(),
            )
            .padding(horizontal = if (pill) 8.dp else t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (pill) 4.dp else t.css.spaceXs),
    ) {
        // `.provider-glyph.settings-sheet-trigger-glyph` (globals.css 6744-6754, studio.css 340): a 14px
        // tile, the mark at 58%; a verified mark takes its brand tile (globals.css 11204-11224).
        ProviderTile(
            provider,
            Modifier.size(14.dp),
            fallback = providerGlyph(provider),
            shape = RoundedCornerShape(7.2.dp),
            background = t.graphiteRaised,
            color = t.white,
            markSize = ProviderLogoDefaults.markSize(14.dp),
            letterSize = 9.6.sp,
        )
        Text(
            label,
            style = type.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(600)),
            color = t.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (pill) Modifier.weight(1f, fill = false) else Modifier.weight(1f),
        )
        if (autoOn) Text("Auto", style = type.body.copy(fontSize = 9.92.sp, fontWeight = FontWeight(600)), color = t.warning)
        if (unknownMode) Text("Unknown", style = type.body.copy(fontSize = 9.92.sp, fontWeight = FontWeight(600)), color = t.warning)
        Icon(TetherIcons.SlidersHorizontal, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
    }
}

/** The sheet's views (session-settings-sheet.tsx SheetView, plus Android's provider-controls view). */
enum class SheetView { Root, Model, Effort, Mode, Fast, Approval, Provider, AutoContinue }

/**
 * `SessionSettingsSheet` (components/session-settings-sheet.tsx) for a live session: a hub of rows
 * (Model, Effort, Mode, Auto approve, Fast, and — Android — Provider controls), each opening its own
 * list; a search over the models on the hub. Back (and the system back) steps out of a sub-view
 * first; a pick from a direct entry closes the sheet, from the hub it returns there.
 */
@Composable
internal fun SessionSettingsSheet(
    entry: SheetView,
    controls: ComposerControls,
    lock: ConsentLock?,
    handlers: ControlHandlers,
    providerPanel: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
) {
    var view by rememberSaveable(entry, stateSaver = SheetViewRequiredSaver) { mutableStateOf(entry) }
    var search by rememberSaveable(entry) { mutableStateOf("") }
    val canStepBack = view != SheetView.Root && view != entry
    fun done() = if (view == entry) onDismiss() else { view = SheetView.Root }
    val title = when (view) {
        SheetView.Root -> "Session settings"
        SheetView.Model -> "Model"
        SheetView.Effort -> "Effort"
        SheetView.Mode -> "Mode"
        SheetView.Fast -> "Fast mode"
        SheetView.Approval -> "Auto approve"
        SheetView.Provider -> "Provider controls"
        SheetView.AutoContinue -> "Auto-continue"
    }
    val enabled = lock == null
    TetherSheet(
        onDismiss = onDismiss,
        title = title,
        onBack = if (view != SheetView.Root) ({ if (canStepBack) view = SheetView.Root else onDismiss() }) else null,
        backLabel = "Back to session settings",
    ) {
        BackHandler(enabled = canStepBack) { view = SheetView.Root }
        Column(Modifier.fillMaxWidth().padding(t().css.spaceXs), verticalArrangement = Arrangement.spacedBy(t().css.spaceXs)) {
            controlLockCopy(lock)?.let { SheetHint(it, warning = true) }
            when (view) {
                SheetView.Root -> {
                    val model = controls.model
                    if (model != null && !controls.codexV2) {
                        TetherInputWell(value = search, onValueChange = { search = it }, placeholder = "Search models…", singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (search.isNotBlank() && model != null) {
                        val q = search.trim().lowercase()
                        val matches = (model.options + model.legacy).filter { it.label.lowercase().contains(q) || it.value.lowercase().contains(q) }
                        if (matches.isEmpty()) SheetHint("No matches") else matches.forEach { option ->
                            ControlOptionRow(option, option.value == model.value, armedRow = false, divider = false) {
                                if (enabled) handlers.chooseModel(option.value)
                                search = ""
                            }
                        }
                    } else {
                        model?.let { HubRow(TetherIcons.Cpu, "Model", it.label.ifEmpty { "Select model" }) { view = SheetView.Model } }
                        controls.effort?.let { HubRow(TetherIcons.Brain, "Effort", it.label.ifEmpty { "Default" }) { view = SheetView.Effort } }
                        controls.mode?.let { mode ->
                            val autoOn = controls.auto?.on == true && !controls.codexV2
                            HubRow(TetherIcons.Shield, "Mode", if (autoOn) "Auto" else mode.label, danger = autoOn || mode.current?.danger == true) { view = SheetView.Mode }
                        }
                        if (controls.codexV2 && controls.auto != null) {
                            HubRow(TetherIcons.Shield, "Auto approve", if (controls.auto!!.on) "On" else "Off", danger = controls.auto!!.on) { view = SheetView.Approval }
                        }
                        controls.fastMode?.let { fast ->
                            val value = when (fast.state) {
                                "on" -> "On"
                                "cooldown" -> "Cooldown"
                                else -> fastModeReasonCopy(fast.disabledReason) ?: "Off"
                            }
                            HubRow(TetherIcons.Zap, "Fast", value) { view = SheetView.Fast }
                        }
                        controls.autoContinue?.let { ac ->
                            HubRow(TetherIcons.Clock, "Auto-continue", if (ac.on) "On" else "Off") { view = SheetView.AutoContinue }
                        }
                        if (providerPanel != null) {
                            HubRow(TetherIcons.SlidersHorizontal, "Provider controls", "") {
                                handlers.requestProviderControls()
                                view = SheetView.Provider
                            }
                        }
                    }
                }
                SheetView.Model -> controls.model?.let { model ->
                    ControlOptionList(model, armedRows = false, divided = false, onSelect = { value ->
                        if (enabled) handlers.chooseModel(value)
                        done()
                    })
                }
                SheetView.Effort -> controls.effort?.let { effort ->
                    ControlOptionList(effort, armedRows = false, divided = false, onSelect = { value ->
                        if (enabled && value != effort.value) handlers.chooseEffort(value)
                        done()
                    })
                }
                SheetView.Mode -> controls.mode?.let { mode ->
                    val autoOn = controls.auto?.on == true && !controls.codexV2
                    ControlOptionList(mode, armedRows = true, divided = false, selectedOverride = if (autoOn) null else mode.value, onSelect = { value ->
                        if (enabled && (autoOn || value != mode.value)) handlers.chooseMode(value)
                        done()
                    })
                    val auto = controls.auto
                    if (auto != null && !controls.codexV2) {
                        ControlOptionRow(
                            ControlOption("__auto__", "Auto", auto.hint, danger = true),
                            selected = auto.on,
                            armedRow = true,
                            divider = false,
                        ) {
                            if (enabled && !auto.on) handlers.toggleAuto()
                            done()
                        }
                    }
                }
                SheetView.Approval -> controls.auto?.let { auto ->
                    SheetHint("When on, the agent runs without asking, including destructive commands. Your collaboration mode stays the same.")
                    listOf(false, true).forEach { on ->
                        ControlOptionRow(ControlOption(on.toString(), if (on) "On" else "Off", danger = on), selected = auto.on == on, armedRow = true, divider = false) {
                            if (enabled && auto.on != on) handlers.setAutoApprove(on)
                            done()
                        }
                    }
                }
                SheetView.Fast -> controls.fastMode?.let { fast ->
                    SheetHint(FAST_MODE_COST_HINT)
                    if (fast.state == "cooldown") SheetHint("Cooling down after a rate limit — your preference resumes automatically once it clears.", warning = true)
                    ControlOptionRow(
                        ControlOption("off", "Off", if (fast.state == "off") fastModeReasonCopy(fast.disabledReason) else null),
                        selected = fast.state == "off", armedRow = true, divider = false,
                    ) {
                        if (enabled && fast.state != "off") handlers.setFast(false)
                        done()
                    }
                    ControlOptionRow(ControlOption("on", "On"), selected = fast.state == "on", armedRow = true, divider = false) {
                        if (enabled && fast.state != "on") handlers.setFast(true)
                        done()
                    }
                }
                SheetView.Provider -> providerPanel?.invoke()
                // session-settings-sheet.tsx:632-658: an Off / On list, like Fast.
                SheetView.AutoContinue -> controls.autoContinue?.let { ac ->
                    SheetHint("When on, a rate/usage limit hit in this session schedules its own continuation for right after the reset instead of just showing the prompt.")
                    listOf(false, true).forEach { on ->
                        // r3: a locked session (offline, or a copy that is not live) draws both rows inert.
                        ControlOptionRow(ControlOption(on.toString(), if (on) "On" else "Off", disabled = !enabled), selected = ac.on == on, armedRow = true, divider = false) {
                            if (enabled && ac.on != on) handlers.setAutoContinue(on)
                            done()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun t() = LocalTetherTokens.current

/**
 * `.settings-sheet-row`: a raised pill row (≥44dp, `--radius-md`, `--graphite-raised`, 1px
 * `--line`, `--edge-highlight`): muted icon, the label, the value in `--muted`, a faint chevron.
 * `is-danger` swaps the edge to `--warning`; the value says "Auto"/"On" in words.
 */
@Composable
fun HubRow(icon: ImageVector, label: String, value: String, danger: Boolean = false, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = if (value.isEmpty()) label else "$label: $value"
                onClick(label) { onClick(); true }
                testTag = "sheet-row-$label"
            }
            .cssSurface(
                RoundedCornerShape(8.dp),
                Color.Transparent,
                null,
                emptyList(),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp)) }
        Text(label, style = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight(600)), color = t.white, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value,
                style = type.body.copy(fontSize = 12.sp, fontWeight = FontWeight(500)),
                color = if (danger) t.warning else t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(16.dp))
    }
}

/** `.settings-sheet-section-label`: 0.68rem/650 `--faint`, uppercase (the words stay the name). */
@Composable
internal fun SectionLabel(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text.uppercase(),
        style = type.body.copy(fontSize = 10.88.sp, fontWeight = FontWeight(650), letterSpacing = 0.04.em),
        color = t.faint,
        modifier = Modifier.padding(top = t.css.spaceXs, start = t.css.spaceSm).clearAndSetSemantics { contentDescription = text },
    )
}

/** `.settings-sheet-hint` (0.8rem `--muted`; `is-warning` `--warning`). */
@Composable
internal fun SheetHint(text: String, warning: Boolean = false) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = type.body.copy(fontSize = 12.8.sp, lineHeight = 1.4.em),
        color = if (warning) t.warning else t.muted,
        modifier = Modifier.padding(horizontal = t.css.spaceSm, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Rows above the well for sessions Tether does not set
// ---------------------------------------------------------------------------------------------

/** chat-view.tsx:3709-3717: a legacy Codex thread has no control channel; say so. */
@Composable
internal fun LegacyCodexHintRow(provider: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
        Box(Modifier.size(14.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            ProviderInlineGlyph(provider, fallback = providerGlyph(provider), color = t.faint, markSize = 14.dp, letterSize = 9.6.sp)
        }
        Text(
            "Model, effort, and mode are set outside Tether for this legacy Codex thread — a session on the app server exposes them here.",
            style = type.body.copy(fontSize = 11.52.sp),
            color = t.faint,
        )
    }
}

/** chat-view.tsx:3724-3776: what the latest native turn used, shown but never editable. */
@Composable
internal fun RestoredSettingsRow(provider: String, restored: com.tether.app.client.RestoredSettings) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = false) { contentDescription = "Restored native session settings" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        restored.model?.let {
            RowIcon(TetherIcons.Cpu)
            ControlPill(it, enabled = false, contentDescription = "Restored model: $it", onClick = {}, glyphProvider = provider, maxWidth = 176.dp)
        }
        restored.effort?.let {
            RowIcon(TetherIcons.Brain)
            ControlPill(it, enabled = false, contentDescription = "Restored reasoning effort: $it", onClick = {})
        }
        restored.mode?.let {
            RowIcon(TetherIcons.Shield)
            ControlPill(it, enabled = false, contentDescription = "Restored permission mode: $it", onClick = {})
        }
        Text("Restored from the latest native turn", style = type.body.copy(fontSize = 11.52.sp), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
