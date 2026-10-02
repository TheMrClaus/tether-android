package com.tether.app.ui.draft

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.AUTO_HINT
import com.tether.app.client.AUTO_LABEL
import com.tether.app.client.ControlOption
import com.tether.app.client.DraftSessionOptions
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.ui.chat.ControlOptionRow
import com.tether.app.ui.chat.ControlPill
import com.tether.app.ui.chat.ControlSelect
import com.tether.app.ui.chat.HubRow
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * ta-xki (T8.1 slice 4): the new-session composer's Effort and Mode — components/draft-composer.tsx
 * (887c222 ~360-467) — drawn from [com.tether.app.client.DraftSessionOptionsModel].
 *
 * From the web's 64rem breakpoint ([LIVE_ROW_MIN_WIDTH_DP]) the live row (`.chat-mode-row-live`,
 * "Session options") holds the Model chip, "Effort" and its select (only when the model offers a
 * variant), "Mode" and its select (the danger style on Claude's Auto and Codex's Full access), and
 * opencode's Auto chip. Below it, as on the web, the row gives way to the SessionSettingsSheet
 * trigger row (catalog kind): the model chip, which opens the sheet straight on the model browser,
 * and the sliders chip, which opens its hub (Model, Effort, Mode, and a search across every model).
 *
 * Elevated modes are ordinary choices (owner 2026-10-02): no confirmation and no arming. A pick only
 * changes the draft (nothing goes to the server until Send), and the pill or chip says the posture in
 * words. App addition: on a phone the sliders chip names an elevated mode ("Auto", "Full access") with
 * the warning ink; the web names only opencode's Auto there.
 */

/** Test tags of the Effort / Mode controls and the phone settings sheet. */
object DraftOptionsTags {
    const val Effort = "draft-effort"
    const val Mode = "draft-mode"
    const val Auto = "draft-auto"
    const val TriggerRow = "draft-settings-trigger-row"
    const val SettingsChip = "draft-settings-chip"
    const val Sheet = "draft-settings-sheet"
    const val SheetBack = "draft-settings-back"
    const val SheetClose = "draft-settings-close"
    const val SheetSearch = "draft-settings-search"

    /** A hub row ([HubRow]'s own tag). */
    fun hubRow(label: String) = "sheet-row-$label"

    /** A select or sheet option row ([ControlOptionRow]'s own tag). */
    fun option(value: String) = "control-option-$value"

    /** The Mode view's Auto row (opencode). */
    val AutoRow = option(AUTO_ROW_VALUE)
}

/** session-settings-sheet.tsx: the Auto row's value inside the Mode list. */
internal const val AUTO_ROW_VALUE = "__auto__"

/**
 * globals.css NARROW_VIEWPORT (63.99rem): from 64rem the live row shows; below, the settings sheet
 * trigger (T7.2's in-session row keys off the same width).
 */
const val LIVE_ROW_MIN_WIDTH_DP = 1024

// -------------------------------------------------------------------------------------------------
// The live row (64rem and up)
// -------------------------------------------------------------------------------------------------

/** draft-composer.tsx `.chat-mode-row.chat-mode-row-live`: wraps (flex-wrap), each name bound to its pill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DraftLiveRow(inputs: DraftSheetInputs, actions: DraftSheetActions) {
    val t = LocalTetherTokens.current
    val options = inputs.options
    FlowRow(
        Modifier.fillMaxWidth().semantics { contentDescription = "Session options" }.testTag(DraftComposerTags.LiveRow),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        ModelSelectorChip(inputs.browser, inputs.browserOpen, actions.onModelChip, Modifier.align(Alignment.CenterVertically))
        options.effort?.let { effort ->
            Named("Effort", Modifier.align(Alignment.CenterVertically)) {
                ControlSelect(
                    control = effort,
                    name = "Reasoning effort",
                    enabled = true,
                    lockCopy = null,
                    onSelect = actions.onSelectEffort,
                    emptyLabel = EFFORT_DEFAULT,
                    testTag = DraftOptionsTags.Effort,
                )
            }
        }
        options.mode?.let { mode ->
            Named("Mode", Modifier.align(Alignment.CenterVertically)) {
                ControlSelect(
                    control = mode,
                    name = options.modeAriaLabel,
                    enabled = true,
                    lockCopy = null,
                    onSelect = actions.onSelectMode,
                    danger = options.modeDanger,
                    testTag = DraftOptionsTags.Mode,
                )
            }
        }
        options.auto?.let { auto -> DraftAutoChip(auto.on, actions.onToggleAuto, Modifier.align(Alignment.CenterVertically)) }
    }
}

/** What the untouched effort (the engine's default) reads (session-settings-sheet.tsx `|| "Default"`). */
internal const val EFFORT_DEFAULT = "Default"

/** `.chat-mode-row label` beside its pill: 0.72rem / 650 `--muted`, the gap tightened by space-xs. */
@Composable
private fun Named(name: String, modifier: Modifier = Modifier, pill: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
        // The pill carries the name for TalkBack ("Reasoning effort: High"); the visible word is decorative.
        Text(
            name,
            color = t.muted,
            style = LocalTetherTypography.current.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(650)),
            modifier = Modifier.clearAndSetSemantics { },
        )
        pill()
    }
}

/**
 * draft-composer.tsx `.draft-auto-chip` (opencode): Zap + "Auto"; on = `is-danger` and the state in
 * words ("On"), so the posture is never colour alone. A tap flips it (toggleAuto).
 */
@Composable
internal fun DraftAutoChip(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    ControlPill(
        label = AUTO_LABEL,
        enabled = true,
        contentDescription = "Auto mode",
        onClick = onToggle,
        modifier = modifier,
        icon = TetherIcons.Zap,
        danger = on,
        chevron = false,
        role = Role.Switch,
        stateDescription = if (on) "On — runs without asking; tap to turn off" else "Off — tap to run without asking, destructive commands included",
        testTag = DraftOptionsTags.Auto,
    )
}

// -------------------------------------------------------------------------------------------------
// The trigger row (below 64rem)
// -------------------------------------------------------------------------------------------------

/**
 * draft-composer.tsx `.settings-sheet-trigger-row` (SessionSettingsSheet, catalog kind, separate
 * chips): the model chip (the picked row's mark and model, no caret) opens the sheet on the model
 * browser; the sliders chip, drawn when there is an Effort or a Mode, opens its hub.
 */
@Composable
internal fun DraftSettingsTriggerRow(inputs: DraftSheetInputs, actions: DraftSheetActions) {
    val t = LocalTetherTokens.current
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Session options" }.testTag(DraftOptionsTags.TriggerRow),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        ModelSelectorChip(inputs.browser, inputs.browserOpen, actions.onModelChip, Modifier.weight(1f, fill = false), chevron = false)
        if (inputs.options.hasSettings) DraftSettingsChip(inputs.options.elevatedLabel, actions.onOpenSettings)
    }
}

/**
 * `.settings-sheet-trigger.settings-sheet-trigger-settings`: the sliders glyph alone, or with the
 * elevated posture in words and the warning ink (`is-danger`).
 */
@Composable
internal fun DraftSettingsChip(elevated: String?, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = if (elevated != null) t.warning else t.muted
    val name = if (elevated != null) "Session settings, $elevated is on" else "Session settings: effort, mode"
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = name
                onClick(name) { onClick(); true }
                testTagProperty = DraftOptionsTags.SettingsChip
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    translationY = if (pressed) 1.dp.toPx() else 0f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .height(36.dp)
                .cssSurface(RoundedCornerShape(999.dp), t.graphiteRaised, null, emptyList())
                .padding(horizontal = 8.8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Icon(TetherIcons.SlidersHorizontal, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
            if (elevated != null) {
                Text(elevated, color = ink, style = type.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(600)), maxLines = 1)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// The settings sheet (below 64rem)
// -------------------------------------------------------------------------------------------------

/** session-settings-sheet.tsx SheetView, the draft's subset (no Fast / Approval / Auto-continue). */
enum class DraftSettingsView { Root, Model, Effort, Mode }

/**
 * The sheet's own state (session-settings-sheet.tsx useStates): open, the view, the view it was opened
 * at, the hub search, and the embedded model browser's state. Held by the host for one server origin.
 */
@Stable
class DraftSettingsState(
    open: Boolean = false,
    view: DraftSettingsView = DraftSettingsView.Root,
    entry: DraftSettingsView = view,
    search: String = "",
    val browser: ModelBrowserState = ModelBrowserState(),
) {
    var open by mutableStateOf(open)
    var view by mutableStateOf(view)
    var entry by mutableStateOf(entry)
    var search by mutableStateOf(search)

    /** open(at): the view and its entry, the search cleared, the browser on its initial view. */
    fun openAt(at: DraftSettingsView, entries: List<ProviderCatalogEntry>, selectedKey: String) {
        view = at
        entry = at
        search = ""
        browser.openOn(entries, selectedKey)
        open = true
    }

    fun close() {
        open = false
    }

    fun backToRoot() {
        view = DraftSettingsView.Root
    }

    /** selectModel: from the view it was opened at, the sheet closes; otherwise back to the hub. */
    fun afterModelPick() {
        search = ""
        if (entry == DraftSettingsView.Model) close() else backToRoot()
    }
}

/** What the sheet's controls do (the model picks go through [browser]'s onSelect). */
class DraftSettingsActions(
    val browser: ModelBrowserActions = ModelBrowserActions(),
    val onSelectEffort: (String) -> Unit = {},
    val onSelectMode: (String) -> Unit = {},
    val onToggleAuto: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The sheet in place (the goldens shoot it inline): over the scrim, a tap there closes it. On a phone
 * a bottom sheet (its handle, the top corners rounded), lifted by the keyboard, at most the height
 * less 1.5rem; otherwise a centred card of `min(460px, 100vw - 32px)` (Studio). The header holds Back
 * (in a sub-view), the title and Close, and on the hub the model search.
 */
@Composable
fun DraftSettingsFrame(
    inputs: DraftSheetInputs,
    state: DraftSettingsState,
    actions: DraftSettingsActions,
    layout: TetherLayoutClass,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val narrow = layout == TetherLayoutClass.Phone
    val keyboard = LocalKeyboardInset.current.current()
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val options = inputs.options
    val autoOn = options.auto?.on == true
    // A model pick is the browser's onSelect, then the sheet's own step (close, or back to the hub).
    val browserActions = ModelBrowserActions(
        onSelect = { key, model ->
            actions.browser.onSelect(key, model)
            state.afterModelPick()
        },
        onRetry = actions.browser.onRetry,
        onAddModel = actions.browser.onAddModel,
        onRemoveModel = actions.browser.onRemoveModel,
        onClose = actions.onClose,
    )
    // Back (Escape) steps out of a sub-view the operator came to from the hub; at the view the sheet
    // opened on (or the hub) it reaches the dialog and closes the sheet.
    BackHandler(enabled = state.view != DraftSettingsView.Root && state.view != state.entry) { state.backToRoot() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(dialogScrim(t))
            .pointerInput(Unit) { detectTapGestures { actions.onClose() } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(bottom = keyboard),
        contentAlignment = if (narrow) Alignment.BottomCenter else Alignment.Center,
    ) {
        val shape = if (narrow) RoundedCornerShape(topStart = t.radiusLg, topEnd = t.radiusLg) else RoundedCornerShape(t.radiusLg)
        // Studio: `max-height: calc(100dvh - 24px)` on a phone, `- 48px` otherwise.
        val maxSheet = (maxHeight - if (narrow) 24.dp else 48.dp).coerceAtLeast(0.dp)
        val case = if (narrow) Modifier.fillMaxWidth() else Modifier.width(minOf(460.dp, maxWidth - 32.dp).coerceAtLeast(0.dp))
        val title = when (state.view) {
            DraftSettingsView.Root -> "Session settings"
            DraftSettingsView.Model -> "Model"
            DraftSettingsView.Effort -> "Effort"
            DraftSettingsView.Mode -> "Mode"
        }
        Column(
            case
                .heightIn(max = maxSheet)
                .testTag(DraftOptionsTags.Sheet)
                .semantics { paneTitle = title }
                .cssSurface(shape, t.graphite, null, StudioDialog.shadows)
                .clip(shape)
                // The sheet swallows taps (only the scrim, Close and Back close it).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(bottom = if (narrow && keyboard == 0.dp) navBottom else 0.dp),
        ) {
            if (narrow) {
                Box(
                    Modifier
                        .padding(top = t.css.spaceSm)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .background(t.lineStrong, RoundedCornerShape(percent = 50))
                        .clearAndSetSemantics { },
                )
            }
            SheetHeader(state, title, actions.onClose)
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            when (state.view) {
                DraftSettingsView.Root -> SheetBody {
                    if (state.search.isNotBlank()) {
                        ModelSearchResults(inputs.browser, state.search, browserActions)
                    } else {
                        val modelLabel = modelSelectorLabel(inputs.browser.entries, inputs.browser.selectedKey, inputs.browser.selectedModel)
                        HubRow(TetherIcons.Cpu, "Model", modelLabel.ifEmpty { "Select model" }) { state.view = DraftSettingsView.Model }
                        options.effort?.let { effort ->
                            HubRow(TetherIcons.Brain, "Effort", effort.current?.label ?: EFFORT_DEFAULT) { state.view = DraftSettingsView.Effort }
                        }
                        options.mode?.let { mode ->
                            val value = if (autoOn) AUTO_LABEL else mode.current?.label.orEmpty()
                            HubRow(TetherIcons.Shield, "Mode", value, danger = autoOn || options.modeDanger) { state.view = DraftSettingsView.Mode }
                        }
                    }
                }
                DraftSettingsView.Model -> ModelBrowserPanel(
                    inputs.browser,
                    state.browser,
                    browserActions,
                    Modifier.fillMaxWidth().heightIn(min = minOf(220.dp, maxSheet)).weight(1f, fill = false),
                    framed = false,
                )
                DraftSettingsView.Effort -> SheetBody {
                    options.effort?.let { effort ->
                        effort.options.forEach { option ->
                            ControlOptionRow(option, selected = option.value == effort.value, armedRow = false, divider = false) {
                                if (option.value != effort.value) actions.onSelectEffort(option.value)
                                state.backToRoot()
                            }
                        }
                    }
                }
                DraftSettingsView.Mode -> SheetBody {
                    options.mode?.let { mode ->
                        mode.options.forEach { option ->
                            ControlOptionRow(option, selected = !autoOn && option.value == mode.value, armedRow = false, divider = false) {
                                if (autoOn || option.value != mode.value) actions.onSelectMode(option.value)
                                state.backToRoot()
                            }
                        }
                        options.auto?.let { auto ->
                            ControlOptionRow(ControlOption(AUTO_ROW_VALUE, AUTO_LABEL, AUTO_HINT, danger = true), selected = auto.on, armedRow = false, divider = false) {
                                if (!auto.on) actions.onToggleAuto()
                                state.backToRoot()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** `.settings-sheet-header` (Studio: 22px padding, a 16px gap): Back, the title, Close; the hub's search. */
@Composable
private fun SheetHeader(state: DraftSettingsState, title: String, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.view != DraftSettingsView.Root) {
                TetherKey(
                    onClick = state::backToRoot,
                    classes = KeyClasses.IconButton,
                    icon = TetherIcons.ChevronLeft,
                    iconSize = 18.dp,
                    contentDescription = "Back to session settings",
                    modifier = Modifier.testTag(DraftOptionsTags.SheetBack),
                )
            }
            Text(
                title,
                color = t.white,
                // Studio `.settings-sheet-header-top h2`: 22px / 700, -0.025em, 1.3.
                style = type.body.copy(fontFamily = type.ui, fontSize = 22.sp, fontWeight = FontWeight(700), letterSpacing = (-0.025).em, lineHeight = 1.3.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TetherKey(
                onClick = onClose,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 18.dp,
                contentDescription = "Close",
                modifier = Modifier.testTag(DraftOptionsTags.SheetClose),
            )
        }
        if (state.view == DraftSettingsView.Root) SheetSearch(state.search) { state.search = it }
    }
}

/** `.settings-sheet-search` (Studio: 48px, radius 8, `--mineral`, 16px text): the glass and the box. */
@Composable
private fun SheetSearch(value: String, onValue: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = type.body.copy(fontSize = 16.sp, color = t.ink)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .cssSurface(RoundedCornerShape(8.dp), t.mineral, CssBorder(1.dp, if (focused) t.violetStrong else t.line), emptyList())
            .padding(horizontal = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.Search, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(t.violet),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Search models" }.testTag(DraftOptionsTags.SheetSearch),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search models…", style = style.copy(color = t.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/** `.settings-sheet-body` (Studio: 16px padding, an 8px gap), scrolling. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SheetBody(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
