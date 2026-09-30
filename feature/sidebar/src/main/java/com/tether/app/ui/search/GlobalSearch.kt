package com.tether.app.ui.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.tether.app.client.LabelText
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.SideEffect
import com.tether.app.client.GlobalSearchResults
import com.tether.app.protocol.SearchHit
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.helpers.Format
import com.tether.app.ui.GlobalSearchForm
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherSelectMenu
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.sidebar.ProviderCap
import com.tether.app.ui.sidebar.SmallIcon
import com.tether.app.ui.sidebar.css
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.protocol.helpers.SessionSidebar as Web

/**
 * T5.3: the cross-harness global search modal — a line-for-line port of components/global-search.tsx
 * (issue #12) with its globals.css 5757-5905 / 11810 and studio.css 772-789, 1008-1016 rules.
 * One query box, per-harness + time-range + scope filters, and a results list; a result opens
 * its conversation scrolled to the first match (the host wires that, [GlobalSearchHost]).
 */

/** global-search.tsx:39-44 — the date-range filter; `null` = any time. */
enum class TimeWindow(val id: String, val label: String, val ms: Long?) {
    Any("any", "Any time", null),
    Day("1d", "Past day", 24L * 60 * 60 * 1000),
    Week("7d", "Past week", 7L * 24 * 60 * 60 * 1000),
    Month("30d", "Past month", 30L * 24 * 60 * 60 * 1000),
    ;

    companion object {
        fun of(id: String): TimeWindow = entries.firstOrNull { it.id == id } ?: Any
    }
}

object GlobalSearchCopy {
    const val Placeholder = "Search across every session and harness…"
    const val Hint = "Type at least two characters to search titles and full conversation content across all harnesses."
    const val Searching = "Searching…"
    const val ScopeLabel = "This workspace only"

    fun noMatches(trimmed: String): String = "No conversations matched “$trimmed”."

    fun conversations(count: Int): String = if (count == 1) "1 conversation" else "$count conversations"
}

object GlobalSearchTags {
    const val Dialog = "global-search"
    const val Input = "global-search-input"
    const val Close = "global-search-close"
    const val Spinner = "global-search-spinner"
    const val Meta = "global-search-meta"
    const val Hint = "global-search-hint"
    const val TimeWindow = "global-search-time"
    const val Scope = "global-search-scope"
    fun chip(provider: String) = "global-search-chip:$provider"
    fun hit(historyId: String) = "global-search-hit:$historyId"
}

/** The pure derivations of global-search.tsx:101-111. */
object GlobalSearchModel {
    /**
     * `showResults`: the incoming results are trusted only when they answer the query typed now
     * (an in-flight response for an older query would otherwise flash stale rows).
     */
    fun showResults(trimmed: String, results: GlobalSearchResults): Boolean = trimmed.length >= 2 && results.query == trimmed

    /** `hitCountLabel`. */
    fun meta(trimmed: String, results: GlobalSearchResults): String {
        if (trimmed.length < 2) return ""
        val show = showResults(trimmed, results)
        if (results.pending && !show) return GlobalSearchCopy.Searching
        if (!show) return ""
        return GlobalSearchCopy.conversations(results.hits.size)
    }

    /**
     * global-search.tsx:79-96 — what the debounced search sends: the trimmed text, the chips
     * (absent when none), `since` stamped at fire time from [now], the current workspace only
     * when scoped and known.
     */
    fun params(form: GlobalSearchForm, currentWorkspace: String, now: Long): com.tether.app.client.GlobalSearchParams {
        val window = TimeWindow.of(form.timeWindow).ms
        return com.tether.app.client.GlobalSearchParams(
            query = jsTrim(form.text),
            providers = form.providers.takeIf { it.isNotEmpty() },
            since = window?.let { now - it },
            cwd = if (form.scopeToWorkspace && currentWorkspace.isNotEmpty()) currentWorkspace else null,
        )
    }

    /** `toggleProvider`: a tap adds the harness at the end, or removes it. */
    fun toggle(providers: List<String>, id: String): List<String> = if (id in providers) providers - id else providers + id

    /** global-search.tsx:199 `compactPath(hit.cwd, workspaceRoot) || projectName(hit.cwd)`. */
    fun hitPath(cwd: String, workspaceRoot: String): String = Format.compactPath(cwd, workspaceRoot).ifEmpty { Format.projectName(cwd) }
}

/** global-search.tsx:90 — the debounce before a query or filter change is searched. */
const val GLOBAL_SEARCH_DEBOUNCE_MS = 220L

/** Studio's `@media (max-width: 640px)` block (studio.css 953, 1008-1016). */
private val StudioNarrow = 640.dp

/**
 * The modal in its own window: the overlay + panel of [GlobalSearchFrame]. Back is the web's
 * Escape; a tap on the backdrop closes it, as the overlay's `onMouseDown` does.
 */
@Composable
fun GlobalSearchDialog(
    form: GlobalSearchForm,
    onFormChange: (GlobalSearchForm) -> Unit,
    results: GlobalSearchResults,
    workspaceRoot: String,
    now: Long,
    onClose: () -> Unit,
    onOpenHit: (SearchHit, String) -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        // The overlay paints the skin's backdrop; the platform's own dim would darken it twice.
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        GlobalSearchFrame(
            form = form,
            onFormChange = onFormChange,
            results = results,
            workspaceRoot = workspaceRoot,
            now = now,
            onClose = onClose,
            onOpenHit = onOpenHit,
            autoFocus = true,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

/**
 * `.global-search-overlay > .global-search-panel` inline (what the goldens shoot). [autoFocus]
 * puts the caret in the query box and raises the keyboard, as the web focuses it on open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchFrame(
    form: GlobalSearchForm,
    onFormChange: (GlobalSearchForm) -> Unit,
    results: GlobalSearchResults,
    workspaceRoot: String,
    now: Long,
    onClose: () -> Unit,
    onOpenHit: (SearchHit, String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val scrim = StudioDialog.scrim
    val trimmed = jsTrim(form.text)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(scrim)
            // The overlay's own onMouseDown: a tap on the backdrop closes. A tap detector, not a
            // clickable — a clickable would merge the whole modal into one TalkBack node.
            .pointerInput(onClose) { detectTapGestures { onClose() } },
    ) {
        val narrow = maxWidth <= StudioNarrow
        val pad = when {
            narrow -> androidx.compose.foundation.layout.PaddingValues(12.dp)
            else -> androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = minOf(maxHeight * 0.12f, 96.dp), bottom = 24.dp)
        }
        val viewport = maxHeight
        Box(modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            val shape = RoundedCornerShape((if (narrow) 14.dp else 16.dp))
            val maxPanel = when {
                narrow -> viewport - 24.dp
                else -> viewport - 120.dp
            }
            Column(
                Modifier
                    .testTag(GlobalSearchTags.Dialog)
                    .semantics { paneTitle = "Search all conversations" }
                    .widthIn(max = 800.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxPanel)
                    .cssSurface(
                        shape,
                        t.graphite,
                        null,
                        listOf(softShadow(24.dp, 80.dp, Color(16, 30, 58).copy(alpha = 0.2f))),
                    )
                    .clip(shape)
                    // The panel swallows its own taps: only the backdrop closes (`target === currentTarget`).
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                SearchHead(form, onFormChange, results.pending, narrow, autoFocus, onClose)
                Filters(form, onFormChange, narrow)
                Meta(GlobalSearchModel.meta(trimmed, results))
                Results(trimmed, results, workspaceRoot, now, narrow, onOpenHit)
            }
        }
    }
}

/** `.global-search-head`: Search glyph, the query box, the spinner while pending, Close. */
@Composable
private fun SearchHead(
    form: GlobalSearchForm,
    onFormChange: (GlobalSearchForm) -> Unit,
    pending: Boolean,
    narrow: Boolean,
    autoFocus: Boolean,
    onClose: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) {
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(Modifier.heightIn(min = 76.dp))
            .padding(
                horizontal = (if (narrow) 16.dp else 24.dp),
                vertical = (if (narrow) 12.dp else 16.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SmallIcon(TetherIcons.Search, t.muted, 16.dp)
        val style = css(type.ui, if (narrow) 1f else 1.125f, 500)
        BasicTextField(
            value = form.text,
            onValueChange = { onFormChange(form.copy(text = it)) },
            singleLine = true,
            textStyle = style.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            // The web has no Enter action; the IME's Search key just puts the keyboard away.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .focusRequester(focus)
                .testTag(GlobalSearchTags.Input)
                .semantics { contentDescription = "Search query" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (form.text.isEmpty()) Text(GlobalSearchCopy.Placeholder, style = style, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (pending) Spinner()
        val size = 44.dp
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClose)
                .size(size)
                .testTag(GlobalSearchTags.Close)
                .semantics { contentDescription = "Close search" },
            contentAlignment = Alignment.Center,
        ) {
            SmallIcon(TetherIcons.X, t.muted, 16.dp)
        }
    }
}

/** `.global-search-spinner`: the Loader glyph in violet, one turn per 720ms (`spin`). */
@Composable
private fun Spinner() {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val angle = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "globalSearchSpin")
        val value by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(720, easing = LinearEasing)), label = "globalSearchSpinAngle")
        value
    }
    Icon(
        TetherIcons.Loader,
        contentDescription = "Searching",
        tint = t.violet,
        modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = angle }.testTag(GlobalSearchTags.Spinner),
    )
}

/** `.global-search-filters`: harness chips on the left, the time range and scope on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(form: GlobalSearchForm, onFormChange: (GlobalSearchForm) -> Unit, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val gap = 12.dp
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .then(Modifier.background(t.graphite))
                .padding(
                    horizontal = (if (narrow) 16.dp else 24.dp),
                    vertical = (if (narrow) 12.dp else 16.dp),
                ),
            // `justify-content: space-between` + `flex-wrap`: the right group sits at the end of
            // the chips' line when it fits, else starts a line of its own.
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(gap),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // `.global-search-harnesses` (role=group "Filter by harness"): its own wrapping row.
            FlowRow(
                Modifier.semantics { contentDescription = "Filter by harness" },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Web.SIDEBAR_HARNESSES.forEach { (id, label) ->
                    HarnessChip(id, label, id in form.providers, narrow) {
                        onFormChange(form.copy(providers = GlobalSearchModel.toggle(form.providers, id)))
                    }
                }
            }
            // `.global-search-filter-right`: one line (Studio lets it wrap at <=640px).
            val select: @Composable () -> Unit = { TimeSelect(TimeWindow.of(form.timeWindow)) { onFormChange(form.copy(timeWindow = it.id)) } }
            val scope: @Composable () -> Unit = { ScopeToggle(form.scopeToWorkspace) { onFormChange(form.copy(scopeToWorkspace = it)) } }
            if (narrow) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) { select(); scope() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm), verticalAlignment = Alignment.CenterVertically) { select(); scope() }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** `.global-search-chip` (+ `.is-on`, `aria-pressed`): the provider cap and the harness name. */
@Composable
private fun HarnessChip(id: String, label: String, on: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(7.dp)
    val border = CssBorder(1.dp, if (on) Color.Transparent else t.line)
    val ink = when {
        on -> t.violetStrong
        on -> t.ink
        else -> t.muted
    }
    Row(
        Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                toggleableState = if (on) ToggleableState.On else ToggleableState.Off
                stateDescription = if (on) "On" else "Off"
                selected = on
            }
            .then(Modifier.heightIn(min = if (narrow) 44.dp else 36.dp))
            .cssSurface(shape, if (on) t.violetWash else Color.Transparent, border)
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 6.dp,
                bottom = 6.dp,
            )
            .testTag(GlobalSearchTags.chip(id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ProviderCap(id, 32.dp, inRow = false)
        Text(label, style = css(type.ui, 0.75f, 400), color = ink, maxLines = 1)
    }
}

/**
 * `.global-search-select`: a native `<select>` on the web (padding 4px 8px, 1px `--line`,
 * `--radius-sm`, `--graphite`, ink 0.8rem; Studio 40px tall, 10px inline, 7px, 12px). Here the
 * same box with the dropdown's chevron, opening the house select menu.
 */
@Composable
private fun TimeSelect(value: TimeWindow, onSelect: (TimeWindow) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .semantics {
                    contentDescription = "Time range"
                    stateDescription = value.label
                }
                .then(Modifier.heightIn(min = 40.dp))
                .cssSurface(shape, t.graphite, CssBorder(1.dp, t.line))
                .padding(horizontal = 10.dp, vertical = 0.dp)
                .testTag(GlobalSearchTags.TimeWindow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value.label, style = css(type.ui, 0.75f, 400), color = t.ink, maxLines = 1)
            SmallIcon(TetherIcons.ChevronDown, t.muted, 13.dp)
        }
        if (expanded) {
            val provider = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                        val below = windowSize.height - anchorBounds.bottom
                        val up = below < popupContentSize.height && anchorBounds.top > below
                        val y = if (up) anchorBounds.top - popupContentSize.height else anchorBounds.bottom
                        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        return IntOffset(x, y)
                    }
                }
            }
            Popup(popupPositionProvider = provider, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                TetherSelectMenu(
                    options = TimeWindow.entries.map { TetherSelectOption(it.id, it.label) },
                    selectedValue = value.id,
                    onSelect = { option ->
                        expanded = false
                        onSelect(TimeWindow.of(option.value))
                    },
                )
            }
        }
    }
}

/**
 * `.global-search-scope`: a checkbox (the material layer's `accent-color: --violet-strong`) and
 * "This workspace only", 0.8rem muted, ink when on (Studio 44px tall, 12px).
 */
@Composable
private fun ScopeToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .clickable(role = Role.Checkbox) { onChange(!on) }
            .semantics { toggleableState = if (on) ToggleableState.On else ToggleableState.Off }
            .heightIn(min = 44.dp)
            .testTag(GlobalSearchTags.Scope),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val box = RoundedCornerShape(2.dp)
        Box(
            Modifier
                .size(13.dp)
                .cssSurface(box, if (on) t.violetStrong else t.graphite, if (on) null else CssBorder(1.dp, t.muted)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(TetherIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
        }
        Text(GlobalSearchCopy.ScopeLabel, style = css(type.ui, 0.75f, 400), color = if (on) t.ink else t.muted, maxLines = 1)
    }
}

/** `.global-search-meta` (aria-less on the web; announced politely here). */
@Composable
private fun Meta(label: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = css(type.ui, 0.75f, 400)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .heightIn(min = 19.2.dp) // min-height 1.6em of 0.75rem
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(GlobalSearchTags.Meta),
    ) {
        if (label.isNotEmpty()) Text(label, style = style, color = t.muted)
    }
}

/** `.global-search-results`: the hint, the no-match line, or the hits. */
@Composable
private fun Results(
    trimmed: String,
    results: GlobalSearchResults,
    workspaceRoot: String,
    now: Long,
    narrow: Boolean,
    onOpenHit: (SearchHit, String) -> Unit,
) {
    val t = LocalTetherTokens.current
    val show = GlobalSearchModel.showResults(trimmed, results)
    when {
        trimmed.length < 2 -> Hint(GlobalSearchCopy.Hint)
        show && results.hits.isEmpty() && !results.pending -> Hint(GlobalSearchCopy.noMatches(trimmed))
        else -> LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = 12.dp,
            ),
        ) {
            // global-search.tsx:185-212 — whatever results are held (the previous query's while a
            // new one is pending, as on the web), keyed by historyId.
            items(results.hits, key = { it.historyId }) { hit -> Hit(hit, workspaceRoot, now, narrow) { onOpenHit(hit, trimmed) } }
        }
    }
}

@Composable
private fun Hint(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = css(type.ui, 0.875f, 400, lineHeight = 1.65f),
        color = t.muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag(GlobalSearchTags.Hint),
    )
}

/** `.global-search-hit`: provider cap, title, relative time; the path; the snippet (2 lines). */
@Composable
private fun Hit(hit: SearchHit, workspaceRoot: String, now: Long, narrow: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val time = Format.relativeTime(hit.updatedAt.toDouble(), now.toDouble())
    val path = GlobalSearchModel.hitPath(hit.cwd, workspaceRoot)
    // ta-28i: the title and snippet by the label rule (in their content's direction), the path as code (LTR).
    val name = LabelText.title(hit.name)
    val snippet = LabelText.hint(hit.snippet)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(name)
                    append(", ").append(time)
                    append(", ").append(SafeText.line(path))
                    if (snippet.isNotEmpty()) append(", ").append(snippet)
                    if (hit.matchCount > 1) append(", ${hit.matchCount} matches")
                }
            }
            .padding(horizontal = 14.dp, vertical = 18.dp)
            .testTag(GlobalSearchTags.hit(hit.historyId)),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderCap(hit.provider, 32.dp, inRow = false)
            Text(
                name,
                style = (css(type.ui, 0.875f, 500, lineHeight = 1.5f)).copy(textDirection = proseDirection),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = css(type.ui, if (narrow) 0.6875f else 0.72f, 400), color = t.muted, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(2.dp)) // margin-top 5px (the column gap is 3px)
        Text(codeLabel(path), style = css(type.ui, 0.6875f, 400), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (snippet.isNotEmpty()) {
            Spacer(Modifier.height(4.dp)) // margin-top 7px
            Text(
                buildAnnotatedString {
                    append(snippet)
                    if (hit.matchCount > 1) withStyle(SpanStyle(color = t.muted)) { append(" · ${hit.matchCount} matches") }
                },
                style = (css(type.ui, 0.8125f, 400, lineHeight = 1.65f)).copy(textDirection = proseDirection),
                color = t.slate,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
