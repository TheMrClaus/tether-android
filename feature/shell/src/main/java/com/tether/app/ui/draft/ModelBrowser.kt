package com.tether.app.ui.draft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.CustomModelId
import com.tether.app.client.LabelText
import com.tether.app.client.NewSessionRow
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.protocol.helpers.JsCollator
import com.tether.app.protocol.helpers.ModelBrowserView
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.NewSessionPickerBody
import com.tether.app.ui.chat.ControlPill
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.originalWords
import com.tether.app.ui.icons.ProviderLogoDefaults
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * ta-2uq (T8.1 slice 3): the new-session composer's ModelSelector and its model browser —
 * components/model-browser.tsx (887c222): the chip (glyph, the picked model's label, caret) and the
 * two-view browser it opens: "all" (the provider rows, or a search across every model) and one
 * provider's view (its models with search and Back, its loading / error / Retry states, and the cog's
 * settings panel: Discovered and Custom models, Add a model by id, Refresh).
 *
 * The browser draws what the draft engine lists ([com.tether.app.client.DraftComposerState.entries]:
 * the current socket's catalog, else the base providers, with this server's custom ids merged in), so
 * a server push redraws it while it is open, and a catalog from another socket or server is never
 * shown. The search and ranking are the web's (lib/model-browser-view.mjs, [ModelBrowserView]).
 *
 * App differences, each for touch or safety: the browser is a panel docked above the keyboard on a
 * phone (the web's `.is-keyboard-open` placement) and a centred card on a tablet, not a popover
 * hanging off the chip; the "all" view's provider rows are ta-895's (a profile row shows its id, rows
 * labelled alike carry a tag, a row the client refuses is disabled); every server or stored string is
 * drawn by the text rules (labels [LabelText], ids [codeLabel]); the Add field says why an id is
 * refused ([CustomModelId]); the chip names a profile row as well as its model.
 */

/** Test tags of the chip and the browser (the web's data-testid names where it has one). */
object ModelBrowserTags {
    const val LiveRow = "draft-live-row"
    const val Chip = "model-selector-chip"
    const val Browser = "model-browser"
    const val SearchAll = "model-search-all-input"
    const val Search = "model-search-input"
    const val Back = "model-browser-back"
    const val Empty = "model-browser-empty"
    const val Stale = "model-browser-stale"
    const val AddProblem = "model-add-problem"
    fun row(entryKey: String, modelId: String) = "model-row-$entryKey-${modelId.ifEmpty { "default" }}"
    fun cog(key: String) = "model-settings-$key"
    fun settingsPanel(key: String) = "model-settings-panel-$key"
    fun error(key: String) = "model-provider-error-$key"
    fun retry(key: String) = "model-retry-$key"
    fun addInput(key: String) = "model-add-input-$key"
    fun addSubmit(key: String) = "model-add-submit-$key"
    fun refresh(key: String) = "model-refresh-$key"
    fun remove(key: String, modelId: String) = "model-remove-$key-$modelId"
    fun settingsModel(key: String, modelId: String) = "model-settings-row-$key-$modelId"
}

/** lib/model-browser-view.mjs ModelBrowserView: the root list, or one provider's view. */
sealed interface BrowserView {
    data object All : BrowserView

    data class Provider(val entryKey: String) : BrowserView
}

/**
 * The browser's own state (model-browser.tsx ModelSelector + ProviderView + ProviderSettingsPanel
 * useStates): open, which view, the search box, the cog panel and the Add field. Held by the sheet's
 * host for one server origin; the goldens seed it directly.
 */
@Stable
class ModelBrowserState(
    open: Boolean = false,
    view: BrowserView = BrowserView.All,
    search: String = "",
    settingsOpen: Boolean = false,
    addDraft: String = "",
) {
    var open by mutableStateOf(open)
    var view by mutableStateOf(view)
    var search by mutableStateOf(search)
    var settingsOpen by mutableStateOf(settingsOpen)
    var addDraft by mutableStateOf(addDraft)

    companion object {
        /** ta-coik.20: the browser's typed search and Add field (and where it is) survive a rotation. */
        val Saver: androidx.compose.runtime.saveable.Saver<ModelBrowserState, Any> = androidx.compose.runtime.saveable.listSaver(
            save = { b ->
                listOf(b.open, (b.view as? BrowserView.Provider)?.entryKey.orEmpty(), b.view is BrowserView.Provider, b.search, b.settingsOpen, b.addDraft)
            },
            restore = { v ->
                ModelBrowserState(
                    open = v[0] as Boolean,
                    view = if (v[2] as Boolean) BrowserView.Provider(v[1] as String) else BrowserView.All,
                    search = v[3] as String,
                    settingsOpen = v[4] as Boolean,
                    addDraft = v[5] as String,
                )
            },
        )
    }

    /** model-browser.tsx openBrowser: the initial view (sole provider → it; else the picked one; else all), search cleared. */
    fun openOn(entries: List<ProviderCatalogEntry>, selectedKey: String) {
        val initial = ModelBrowserView.resolveInitialModelBrowserView(entriesJs(entries), JsStr(selectedKey))
        view = if (initial["kind"] == JsStr("provider")) BrowserView.Provider((initial["entryKey"] as? JsStr)?.value.orEmpty()) else BrowserView.All
        search = ""
        settingsOpen = false
        addDraft = ""
        open = true
    }

    fun close() {
        open = false
    }

    /** backToAll. */
    fun back() {
        view = BrowserView.All
        search = ""
        settingsOpen = false
    }

    /** drillDown: the cog panel closes when the viewed provider changes. */
    fun drill(entryKey: String) {
        if (view != BrowserView.Provider(entryKey)) {
            settingsOpen = false
            addDraft = ""
        }
        view = BrowserView.Provider(entryKey)
        search = ""
    }
}

/** What the browser draws (everything it reads; the goldens seed it). */
@Immutable
class ModelBrowserInputs(
    /** The draft's rows with this server's custom ids merged (use-draft-composer.ts `entries`). */
    val entries: List<ProviderCatalogEntry>,
    /** ta-895's rows for the same list (the "all" view's provider rows). */
    val rows: List<NewSessionRow>,
    val providers: List<ProviderInfo>,
    /** This socket's catalog is not in yet (the base providers stand in). */
    val catalogPending: Boolean,
    val selectedKey: String,
    val selectedModel: String,
    /** This server's custom ids per row key (valid ones only). */
    val customModels: Map<String, List<String>>,
    /** The clock reading for "Updated …" (display only; the goldens fix it). */
    val now: Long,
    /** `localeCompare` for the ranking's ties. */
    val collator: JsCollator,
)

/** What the browser's controls do. */
class ModelBrowserActions(
    val onSelect: (entryKey: String, modelId: String) -> Unit = { _, _ -> },
    val onRetry: (entryKey: String) -> Unit = {},
    /** True when the id was added (the field then clears). */
    val onAddModel: (entryKey: String, modelId: String) -> Boolean = { _, _ -> false },
    val onRemoveModel: (entryKey: String, modelId: String) -> Unit = { _, _ -> },
    val onClose: () -> Unit = {},
)

// -------------------------------------------------------------------------------------------------
// The web's view logic over the typed rows
// -------------------------------------------------------------------------------------------------

/** A catalog row as lib/model-browser-view.mjs reads one. */
internal fun ProviderCatalogEntry.toBrowserJs(): JsObj = JsObj.of(
    "key" to JsStr(key),
    "provider" to JsStr(provider),
    "status" to JsStr(status),
    "label" to label?.let(::JsStr),
    "models" to JsArr.of(
        models.map { m ->
            JsObj.of(
                "value" to JsStr(m.value),
                "displayName" to JsStr(m.displayName),
                "description" to m.description?.let(::JsStr),
                "resolvedModel" to m.resolvedModel?.let(::JsStr),
            )
        },
    ),
)

internal fun entriesJs(entries: List<ProviderCatalogEntry>): JsArr = JsArr.of(entries.map { it.toBrowserJs() })

/** lib/model-browser-view.mjs ModelBrowserRow, typed (raw values; cleaned where drawn). */
@Immutable
internal data class BrowserRow(
    val key: String,
    val entryKey: String,
    val provider: String,
    val providerLabel: String,
    val modelId: String,
    val modelLabel: String,
    val description: String?,
    val isDefault: Boolean,
)

private fun JsValue.str(name: String): String? = (this as? JsObj)?.get(name)?.let { (it as? JsStr)?.value }

internal fun browserRows(rows: JsArr): List<BrowserRow> = rows.mapNotNull { r ->
    val o = r as? JsObj ?: return@mapNotNull null
    BrowserRow(
        key = o.str("key").orEmpty(),
        entryKey = o.str("entryKey").orEmpty(),
        provider = o.str("provider").orEmpty(),
        providerLabel = o.str("providerLabel").orEmpty(),
        modelId = o.str("modelId").orEmpty(),
        modelLabel = o.str("modelLabel").orEmpty(),
        description = o.str("description"),
        isDefault = o["isDefault"] == JsBool.TRUE,
    )
}

/** normalizeQuery. */
internal fun normalizeQuery(value: String): String = value.trim().lowercase()

/** A provider's ready rows, filtered and ranked by [query] (normalized). */
internal fun providerRows(entry: ProviderCatalogEntry, query: String, collator: JsCollator): List<BrowserRow> =
    browserRows(ModelBrowserView.filterAndRankModelRows(ModelBrowserView.getProviderModelRows(entry.toBrowserJs()), JsStr(query), collator))

/** model-browser.tsx formatUpdatedAgo: "just now", "12s ago", "3m ago", "2h ago", "4d ago"; "" with no stamp. */
fun formatUpdatedAgo(fetchedAt: Long?, now: Long): String {
    if (fetchedAt == null || fetchedAt == 0L) return ""
    val diff = now - fetchedAt
    if (diff < 5_000) return "just now"
    val sec = diff / 1_000
    if (sec < 60) return "${sec}s ago"
    val min = sec / 60
    if (min < 60) return "${min}m ago"
    val hr = min / 60
    if (hr < 24) return "${hr}h ago"
    return "${hr / 24}d ago"
}

/** A label from the server or storage, by the label rule; one that cleans to nothing is spelled out. */
internal fun shownLabel(raw: String?, fallback: String): String {
    LabelText.label(raw).takeIf { it.isNotEmpty() }?.let { return it }
    if (!raw.isNullOrEmpty()) return LabelText.visibleValue(raw)
    return LabelText.label(fallback).ifEmpty { LabelText.visibleValue(fallback) }
}

/** `entry.label ?? entry.provider`, drawn. */
internal fun entryTitle(entry: ProviderCatalogEntry): String = shownLabel(entry.label, entry.provider)

/**
 * The chip's words: lib/model-browser-view.mjs resolveSelectedModelLabel, drawn by the label rule
 * ("" when nothing is picked: the chip then reads "Select model"). App addition: a profile row's
 * label goes first, so two accounts on the same model never read the same.
 */
fun modelSelectorLabel(entries: List<ProviderCatalogEntry>, selectedKey: String, selectedModel: String): String {
    val raw = (ModelBrowserView.resolveSelectedModelLabel(entriesJs(entries), JsStr(selectedKey), JsStr(selectedModel)) as? JsStr)?.value.orEmpty()
    if (raw.isEmpty()) return ""
    val model = shownLabel(raw, selectedModel)
    val entry = entries.firstOrNull { it.key == selectedKey } ?: return model
    return if (entry.profileId != null) "${entryTitle(entry)} · $model" else model
}

// -------------------------------------------------------------------------------------------------
// The chip
// -------------------------------------------------------------------------------------------------

/** model-browser.tsx ModelSelector's chip: the picked row's glyph, the model's label (or "Select model"), the caret. */
@Composable
fun ModelSelectorChip(
    inputs: ModelBrowserInputs,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** ta-xki: false on a phone, where the chip is the settings sheet's model trigger (no caret, as the web's). */
    chevron: Boolean = true,
) {
    val selected = inputs.entries.firstOrNull { it.key == inputs.selectedKey }
    val label = modelSelectorLabel(inputs.entries, inputs.selectedKey, inputs.selectedModel).ifEmpty { "Select model" }
    ControlPill(
        label = label,
        enabled = true,
        contentDescription = "Choose provider and model, $label",
        onClick = onClick,
        modifier = modifier,
        glyphProvider = selected?.provider,
        glyphTile = true,
        active = open,
        chevron = chevron,
        maxWidth = 260.dp,
        stateDescription = if (open) "Expanded" else "Collapsed",
        testTag = ModelBrowserTags.Chip,
    )
}

// -------------------------------------------------------------------------------------------------
// The browser
// -------------------------------------------------------------------------------------------------

/**
 * The browser in place, over a backdrop that closes it on a tap (the web closes on any press outside
 * the widget). [TetherLayoutClass.Phone]: a card docked above the keyboard, `space-sm` from the edges,
 * at most 400dp (or what the keyboard leaves) tall. [TetherLayoutClass.Expanded]: a centred card of
 * `min(28rem, 100vw - space-lg)`, 220-400dp tall.
 */
@Composable
fun ModelBrowserFrame(
    inputs: ModelBrowserInputs,
    state: ModelBrowserState,
    actions: ModelBrowserActions,
    layout: TetherLayoutClass,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val narrow = layout == TetherLayoutClass.Phone
    val keyboard = LocalKeyboardInset.current.current()
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { actions.onClose() } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(bottom = if (keyboard > 0.dp) keyboard else navBottom),
        contentAlignment = if (narrow) Alignment.BottomCenter else Alignment.Center,
    ) {
        val room = (maxHeight - t.css.spaceLg).coerceAtLeast(0.dp)
        val maxPanel = minOf(400.dp, room)
        val minPanel = minOf(220.dp, maxPanel)
        val case = if (narrow) {
            Modifier.padding(t.css.spaceSm).fillMaxWidth()
        } else {
            Modifier.width(minOf(448.dp, maxWidth - t.css.spaceLg).coerceAtLeast(0.dp))
        }
        ModelBrowserPanel(inputs, state, actions, case.heightIn(min = minPanel, max = maxPanel))
    }
}

/** model-browser.tsx ModelBrowser: the panel (role dialog, "Model browser"). */
@Composable
fun ModelBrowserPanel(
    inputs: ModelBrowserInputs,
    state: ModelBrowserState,
    actions: ModelBrowserActions,
    modifier: Modifier = Modifier,
    /**
     * ta-xki: false inside the phone's settings sheet (`.settings-sheet .model-browser`: no border,
     * radius, shadow or face of its own; the sheet is the floating surface).
     */
    framed: Boolean = true,
) {
    val t = LocalTetherTokens.current
    // Studio: `:root .model-browser { border-radius: var(--radius-lg) }`.
    val shape = RoundedCornerShape(t.radiusLg)
    Column(
        modifier
            .then(if (framed) Modifier.cssSurface(shape, t.graphite, CssBorder(1.dp, t.lineStrong), t.css.shadowMenu).clip(shape) else Modifier)
            .semantics { paneTitle = "Model browser" }
            .testTag(ModelBrowserTags.Browser)
            // The panel swallows taps (only the backdrop closes it); a gesture sink, not a clickable.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        when (val view = state.view) {
            BrowserView.All -> AllView(inputs, state, actions)
            is BrowserView.Provider -> ProviderView(inputs, state, actions, inputs.entries.firstOrNull { it.key == view.entryKey })
        }
    }
}

/** `.model-browser-header` (Studio: space-md padding on graphite-raised), its hairline under it. */
@Composable
private fun Header(content: @Composable RowScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.graphiteRaised)
            .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        content = content,
    )
}

/** `.model-browser-title`: 0.72rem / 700, tracked, uppercase, at most 45% of the header. */
@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Text(
        text.uppercase(),
        color = t.white,
        style = LocalTetherTypography.current.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(700), letterSpacing = 0.04.em),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() }.originalWords(text),
    )
}

/** `.model-browser-search`: the glass, a text box in a mineral-deep pill. */
@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, placeholder: String, label: String, tag: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(t.radiusSm)
    val style = type.body.copy(fontSize = 12.48.sp, color = t.ink)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .cssSurface(shape, t.mineralDeep, CssBorder(1.dp, if (focused) t.violetStrong else t.line), emptyList())
            .padding(horizontal = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
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
            modifier = Modifier.weight(1f).semantics { contentDescription = label }.testTag(tag),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = t.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/** The "all" view: "Models" with the search across every model, then the provider rows or the results. */
@Composable
private fun ColumnScope.AllView(inputs: ModelBrowserInputs, state: ModelBrowserState, actions: ModelBrowserActions) {
    val t = LocalTetherTokens.current
    Header {
        Title("Models", Modifier.widthIn45())
        SearchField(state.search, { state.search = it }, "Search all models…", "Search all models", ModelBrowserTags.SearchAll, Modifier.weight(1f))
    }
    val query = normalizeQuery(state.search)
    val all = ModelBrowserView.resolveModelBrowserAllView(entriesJs(inputs.entries), JsStr(query), inputs.collator)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = t.css.spaceXs)) {
        when ((all["kind"] as? JsStr)?.value) {
            "browse" -> if (inputs.rows.isEmpty() && !inputs.catalogPending) {
                EmptyState(TetherIcons.Search, "No models available")
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = t.css.spaceSm)) {
                    NewSessionPickerBody(
                        rows = inputs.rows,
                        providers = inputs.providers,
                        catalogPending = inputs.catalogPending,
                        notice = null,
                        selectedKey = inputs.selectedKey.ifEmpty { null },
                        pickLabel = { "Show the models of $it" },
                        onPick = { row -> state.drill(row.choice.key) },
                    )
                }
            }
            "noSearchMatches" -> EmptyState(TetherIcons.Search, "No matches")
            else -> ModelRowList(browserRows(all["rows"] as? JsArr ?: JsArr.EMPTY), inputs, actions, showProviderLabel = true)
        }
    }
}

/**
 * ta-xki: session-settings-sheet.tsx's hub search (`view === "root" && search.trim()`): the "all"
 * view's results for [query] alone, every provider's matching models (or "No matches"). Nothing while
 * the query is blank (the hub's rows show instead).
 */
@Composable
internal fun ModelSearchResults(inputs: ModelBrowserInputs, query: String, actions: ModelBrowserActions) {
    val q = normalizeQuery(query)
    if (q.isEmpty()) return
    val all = ModelBrowserView.resolveModelBrowserAllView(entriesJs(inputs.entries), JsStr(q), inputs.collator)
    when ((all["kind"] as? JsStr)?.value) {
        "browse" -> Unit
        "noSearchMatches" -> EmptyState(TetherIcons.Search, "No matches")
        else -> ModelRowList(browserRows(all["rows"] as? JsArr ?: JsArr.EMPTY), inputs, actions, showProviderLabel = true)
    }
}

/** model-browser.tsx ProviderView. */
@Composable
private fun ColumnScope.ProviderView(inputs: ModelBrowserInputs, state: ModelBrowserState, actions: ModelBrowserActions, entry: ProviderCatalogEntry?) {
    val t = LocalTetherTokens.current
    if (entry == null) {
        EmptyState(TetherIcons.Search, "No matches")
        return
    }
    Header {
        // Back only when there is somewhere to go back to (entries.length > 1).
        if (inputs.entries.size > 1) {
            TetherKey(
                onClick = state::back,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.ChevronLeft,
                iconSize = 14.dp,
                contentDescription = "Back to all providers",
                modifier = Modifier.testTag(ModelBrowserTags.Back),
                contentPadding = 0.dp,
            )
        }
        BrowserGlyph(entry.provider, inputs.providers, header = true)
        Title(entryTitle(entry), Modifier.widthIn45())
        SearchField(
            state.search,
            { state.search = it },
            if (state.settingsOpen) "Search this provider…" else "Search models…",
            "Search models",
            ModelBrowserTags.Search,
            Modifier.weight(1f),
        )
        TetherKey(
            onClick = { state.settingsOpen = !state.settingsOpen },
            classes = KeyClasses.IconButton,
            icon = TetherIcons.Settings,
            iconSize = 14.dp,
            selected = state.settingsOpen,
            contentDescription = if (state.settingsOpen) "Hide provider models and settings" else "Provider models and settings",
            modifier = Modifier.testTag(ModelBrowserTags.cog(entry.key)),
            contentPadding = 0.dp,
        )
    }
    val query = normalizeQuery(state.search)
    when {
        state.settingsOpen -> ProviderSettingsPanel(inputs, state, actions, entry, query)
        entry.status == "loading" -> EmptyState(TetherIcons.Loader, "Loading…")
        entry.status == "error" || entry.status == "unavailable" -> Column(
            Modifier
                .fillMaxWidth()
                
                .verticalScroll(rememberScrollState())
                .padding(t.css.spaceMd)
                .testTag(ModelBrowserTags.error(entry.key)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.faint, modifier = Modifier.size(16.dp))
            val words = if (entry.status == "unavailable") "This provider is not available." else LabelText.error(entry.error).ifEmpty { "Failed to load models." }
            Text(
                words,
                color = t.muted,
                style = LocalTetherTypography.current.body.copy(fontSize = 12.48.sp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (entry.status == "error") {
                TetherKey(
                    onClick = { actions.onRetry(entry.key) },
                    classes = KeyClasses.ButtonSecondary,
                    label = "Retry",
                    contentDescription = "Retry loading the models of ${entryTitle(entry)}",
                    modifier = Modifier.testTag(ModelBrowserTags.retry(entry.key)),
                )
            }
            if (entry.status == "error" && entry.models.isNotEmpty()) {
                // `.model-browser-stale`: the last-known-good list (the web draws the provider's ready rows here).
                Column(Modifier.fillMaxWidth().testTag(ModelBrowserTags.Stale), verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                    Text(
                        "SHOWING LAST-KNOWN-GOOD MODELS",
                        color = t.muted,
                        style = LocalTetherTypography.current.body.copy(fontSize = 11.52.sp, letterSpacing = 0.02.em),
                    )
                    ModelRowList(providerRows(entry, query, inputs.collator), inputs, actions)
                }
            }
        }
        else -> {
            val rows = providerRows(entry, query, inputs.collator)
            if (rows.isEmpty()) {
                EmptyState(TetherIcons.Search, "No matches")
            } else {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = t.css.spaceXs)) {
                    ModelRowList(rows, inputs, actions)
                }
            }
        }
    }
}

/**
 * model-browser.tsx ProviderSettingsPanel (issue #45): the provider's Discovered and Custom models
 * (each custom one removable), a note when there are none, and the footer: Add a model by id, and
 * "Updated …" with Refresh.
 */
@Composable
private fun ColumnScope.ProviderSettingsPanel(
    inputs: ModelBrowserInputs,
    state: ModelBrowserState,
    actions: ModelBrowserActions,
    entry: ProviderCatalogEntry,
    query: String,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val customIds = inputs.customModels[entry.key].orEmpty().toSet()
    val allRows = browserRows(ModelBrowserView.getProviderModelRows(entry.toBrowserJs()))
    fun ranked(rows: List<BrowserRow>) = browserRows(
        ModelBrowserView.filterAndRankModelRows(JsArr.of(rows.map { it.toJs() }), JsStr(query), inputs.collator),
    )
    val discovered = ranked(allRows.filter { it.modelId !in customIds })
    val custom = ranked(allRows.filter { it.modelId in customIds })
    val loading = entry.status == "loading"
    val errored = entry.status == "error" || entry.status == "unavailable"
    val empty = discovered.isEmpty() && custom.isEmpty()
    val updated = formatUpdatedAgo(entry.fetchedAt, inputs.now)

    Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag(ModelBrowserTags.settingsPanel(entry.key))) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = t.css.spaceXs, bottom = t.css.spaceSm)) {
            when {
                loading && empty -> EmptyState(TetherIcons.Loader, "Loading…")
                errored && empty -> SettingsNote(
                    TetherIcons.TriangleAlert,
                    if (entry.status == "unavailable") "This provider is not available." else LabelText.error(entry.error).ifEmpty { "Failed to load models." },
                )
                else -> {
                    if (discovered.isNotEmpty()) {
                        SectionHead("Discovered", discovered.size)
                        discovered.forEach { SettingsModelRow(entry.key, it, onDelete = null) }
                    }
                    if (custom.isNotEmpty()) {
                        SectionHead("Custom", custom.size)
                        custom.forEach { row -> SettingsModelRow(entry.key, row, onDelete = { actions.onRemoveModel(entry.key, row.modelId) }) }
                    }
                    if (empty) SettingsNote(null, if (query.isNotEmpty()) "No matches" else "No models discovered yet — Refresh or add one below.")
                }
            }
        }
        // `.model-browser-footer`.
        Column(
            Modifier
                .fillMaxWidth()
                .background(t.graphite)
                .drawBehind { drawRect(t.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            val trimmed = CustomModelId.normalize(state.addDraft)
            val alreadyExists = entry.models.any { it.value == trimmed }
            // model-browser.tsx:530-532: trimmed, not empty, not already offered.
            val canAdd = trimmed.isNotEmpty() && !alreadyExists
            val add = {
                if (canAdd && actions.onAddModel(entry.key, trimmed)) state.addDraft = ""
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                AddField(state.addDraft, { state.addDraft = it }, entry.key, onDone = add, modifier = Modifier.weight(1f))
                TetherKey(
                    onClick = add,
                    classes = KeyClasses.ButtonSecondary,
                    icon = TetherIcons.Plus,
                    iconSize = 14.dp,
                    enabled = canAdd,
                    contentDescription = "Add model",
                    modifier = Modifier.size(44.dp).testTag(ModelBrowserTags.addSubmit(entry.key)),
                    contentPadding = 0.dp,
                )
            }
            // App addition: why an id cannot be added (the web only disables +).
            val why = if (alreadyExists) "This provider already offers that model." else null
            if (why != null) {
                Text(
                    why,
                    color = t.muted,
                    style = type.body.copy(fontSize = 10.88.sp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ModelBrowserTags.AddProblem),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (updated.isNotEmpty()) "Updated $updated" else "", color = t.faint, style = type.body.copy(fontSize = 10.88.sp))
                TetherKey(
                    onClick = { actions.onRetry(entry.key) },
                    classes = KeyClasses.ButtonSecondary,
                    label = if (loading) "Refreshing…" else "Refresh",
                    icon = if (loading) TetherIcons.Loader else TetherIcons.RotateCw,
                    iconSize = 13.dp,
                    enabled = !loading,
                    contentDescription = if (loading) "Refreshing the models of ${entryTitle(entry)}" else "Refresh the models of ${entryTitle(entry)}",
                    modifier = Modifier.testTag(ModelBrowserTags.refresh(entry.key)),
                )
            }
        }
    }
}

private fun BrowserRow.toJs(): JsObj = JsObj.of(
    "key" to JsStr(key),
    "entryKey" to JsStr(entryKey),
    "provider" to JsStr(provider),
    "providerLabel" to JsStr(providerLabel),
    "modelId" to JsStr(modelId),
    "modelLabel" to JsStr(modelLabel),
    "description" to description?.let(::JsStr),
    "isDefault" to JsBool.of(isDefault),
)

/** `.model-browser-add input`: "Add a model by id…" (no capitalisation, no autocorrect; Done adds). */
@Composable
private fun AddField(value: String, onValue: (String) -> Unit, key: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = LocalTetherTypography.current.body.copy(fontSize = 12.16.sp, color = t.ink)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(t.violet),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .heightIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(999.dp), t.mineralDeep, CssBorder(1.dp, if (focused) t.violet else t.line), emptyList())
            .semantics { contentDescription = "Add a model by id" }
            .testTag(ModelBrowserTags.addInput(key)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = t.css.spaceSm, vertical = 12.dp)) {
                if (value.isEmpty()) Text("Add a model by id…", style = style.copy(color = t.faint), maxLines = 1)
                inner()
            }
        },
    )
}

/** `.model-browser-section-head`: the title and its count. */
@Composable
private fun SectionHead(title: String, count: Int) {
    val t = LocalTetherTokens.current
    val style = LocalTetherTypography.current.body.copy(fontSize = 10.24.sp, fontWeight = FontWeight(700), letterSpacing = 0.06.em)
    Row(
        Modifier.fillMaxWidth().padding(top = t.css.spaceXs).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title.uppercase(), color = t.faint, style = style, modifier = Modifier.originalWords(title))
        Text(count.toString(), color = t.faint, style = style)
    }
}

/** `.model-browser-model`: the label, the id (code), a description that is not the id, and Remove for a custom one. */
@Composable
private fun SettingsModelRow(entryKey: String, row: BrowserRow, onDelete: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val label = shownLabel(row.modelLabel, row.modelId.ifEmpty { "Default" })
    val description = row.description?.takeIf { it.isNotEmpty() && it != row.modelId }?.let { LabelText.hint(it) }?.takeIf { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs)
            .testTag(ModelBrowserTags.settingsModel(entryKey, row.modelId)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(label, color = t.white, style = type.body.copy(fontSize = 12.8.sp, fontWeight = FontWeight(600)), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.modelId.isNotEmpty()) {
                Text(codeLabel(row.modelId), color = t.faint, style = type.body.copy(fontSize = 10.88.sp, fontFamily = JetBrainsMono), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (description != null) Text(description, color = t.faint, style = type.body.copy(fontSize = 10.88.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDelete != null) {
            TetherKey(
                onClick = onDelete,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.Trash2,
                iconSize = 13.dp,
                contentDescription = "Remove $label",
                modifier = Modifier.testTag(ModelBrowserTags.remove(entryKey, row.modelId)),
                contentPadding = 0.dp,
            )
        }
    }
}

/** `.model-browser-settings-note`. */
@Composable
private fun SettingsNote(icon: ImageVector?, text: String) {
    val t = LocalTetherTokens.current
    Row(Modifier.fillMaxWidth().padding(t.css.spaceMd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
        Text(text, color = t.muted, style = LocalTetherTypography.current.body.copy(fontSize = 12.16.sp))
    }
}

/** ModelRowList: one button per model row (glyph, label, description, the check on the picked one). */
@Composable
private fun ModelRowList(rows: List<BrowserRow>, inputs: ModelBrowserInputs, actions: ModelBrowserActions, showProviderLabel: Boolean = false) {
    rows.forEachIndexed { i, row -> ModelRow(row, inputs, actions, showProviderLabel, last = i == rows.lastIndex) }
}

@Composable
private fun ModelRow(row: BrowserRow, inputs: ModelBrowserInputs, actions: ModelBrowserActions, showProviderLabel: Boolean, last: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val selected = row.entryKey == inputs.selectedKey && row.modelId == inputs.selectedModel
    val label = shownLabel(row.modelLabel, row.modelId.ifEmpty { "Default" })
    val description = row.description?.takeIf { it.isNotEmpty() }?.let {
        val providerLabel = shownLabel(row.providerLabel, row.provider)
        val desc = LabelText.hint(it)
        if (showProviderLabel) (if (desc.isNotEmpty()) "$providerLabel · $desc" else providerLabel) else desc
    }?.takeIf { it.isNotEmpty() }
    val pick = { actions.onSelect(row.entryKey, row.modelId) }
    val spoken = listOfNotNull(label, description).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) t.violetWash else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = pick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = spoken
                this.selected = selected
                onClick("Use this model") { pick(); true }
                testTagProperty = ModelBrowserTags.row(row.entryKey, row.modelId)
            }
            .then(if (last) Modifier else Modifier.drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) })
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        BrowserGlyph(row.provider, inputs.providers)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
            Text(label, color = t.white, style = type.body.copy(fontSize = 13.12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) Text(description, color = t.faint, style = type.body.copy(fontSize = 11.2.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(TetherIcons.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
    }
}

/**
 * The engine's mark, else its glyph letter, in Studio's `.provider-glyph` (studio.css:340: no border,
 * a 0.45rem corner). A row's is `:root .model-browser-row .provider-glyph` (studio.css:692: 30px on
 * `--mineral`, the letter 0.62rem from globals.css 9068); the [header]'s is the bare glyph (2rem on
 * `--graphite-raised`, the letter 0.8rem, globals.css 856). The mark is 58% of the tile (globals.css 879).
 */
@Composable
private fun BrowserGlyph(provider: String, providers: List<ProviderInfo>, header: Boolean = false) {
    val t = LocalTetherTokens.current
    val glyph = providers.firstOrNull { it.id == provider }?.glyph?.let { LabelText.label(it) }?.takeIf { it.isNotEmpty() }
    val side = if (header) 32.dp else 30.dp
    // A verified mark gives the tile its brand colours (globals.css 11204-11224).
    ProviderTile(
        provider,
        Modifier.size(side),
        fallback = glyph ?: provider.take(1).uppercase(),
        shape = RoundedCornerShape(7.2.dp),
        background = if (header) t.graphiteRaised else t.mineral,
        // `.provider-glyph` is `--white`, `.provider-claude/-codex/-opencode` `--ink` (globals.css 856-866).
        color = ProviderLogoDefaults.color(provider),
        markSize = side * 0.58f,
        letterSize = if (header) 12.8.sp else 9.92.sp,
    )
}

/** `.model-browser-empty`: a glyph over the words, centred, at least 8rem tall. */
@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(t.css.spaceMd).testTag(ModelBrowserTags.Empty),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = t.faint, modifier = Modifier.size(16.dp))
        Text(text, color = t.muted, style = LocalTetherTypography.current.body.copy(fontSize = 12.48.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** `.model-browser-title { max-width: 45% }`, as a cap on a phone-width header. */
private fun Modifier.widthIn45(): Modifier = this.widthIn(max = 160.dp)
