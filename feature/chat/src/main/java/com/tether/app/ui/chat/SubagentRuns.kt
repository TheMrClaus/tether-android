package com.tether.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.helpers.Elapsed
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography

/*
 * T6.4: the sub-agent run surfaces (components/subagent-runs.tsx at PARITY_BASE): the tab strip,
 * the per-run identity chips, the collapsible Subagents roster and one run's panel — a lazy list,
 * one row per step, so a run with thousands of steps composes only what is on screen and a delta
 * to one step recomposes only that step. Styles: globals.css 5503-5682, 5906-6082, 8468-8469.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/** `StatusIcon`: spinner (running), alert (error), check (done). Decorative: the text says it. */
@Composable
private fun RunStatusIcon(status: String, size: Dp, tint: Color) {
    when (status) {
        RUN_RUNNING -> SpinningIcon(TetherIcons.Loader, tint = tint, size = size)
        RUN_ERROR -> Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        else -> Icon(TetherIcons.Check, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** `RunHarness`: the harness mark (verified logo, else its letter) in a 1.1rem box. Decorative here. */
@Composable
private fun RunHarness(run: SubagentRun, tint: Color) {
    if (run.provider.isNullOrEmpty() || harnessLabel(run) == null) return
    Box(Modifier.size(17.6.dp), contentAlignment = Alignment.Center) {
        ProviderLogo(run.provider, fallback = Format.providerGlyph(run.provider), color = tint, markSize = 15.dp, letterSize = 10.sp)
    }
}

/** A CSS `border: 1px dashed` on a rounded box (the muted chip, the empty / thread-note boxes). */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
    )
}

/** A 1px [color] rule along the bottom edge (a CSS `border-bottom`). */
private fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    drawRect(color, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
}

// --- The tab strip ------------------------------------------------------------------------------

/**
 * `SubagentTabs`: tab 0 is the whole session transcript, each following tab one run (depth-first,
 * a nested run right after its spawner, marked by the nest glyph and a dashed left rule).
 * Selection is by runId (null = the transcript). Violet marks the selected tab only; status is
 * icon + text, never colour alone. Each tab is at least 44dp tall and is announced as a tab with
 * the web's full `title` ("Survey the tests · general-purpose · claude sub-agent · done").
 */
@Composable
fun SubagentTabs(
    runs: List<SubagentRun>,
    activeRunId: String?,
    onSelect: (String?) -> Unit,
) {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val runningCount = runs.count { it.status == RUN_RUNNING }
    val runsById = remember(runs) { runs.associateBy { it.runId } }
    val listState = rememberLazyListState()
    // Keep the selected tab in view when it changes off-screen.
    LaunchedEffect(activeRunId) {
        val index = activeRunId?.let { id -> runs.indexOfFirst { it.runId == id } + 1 } ?: 0
        if (index >= 0) listState.animateScrollToItem(index)
    }
    val gap = t.css.spaceXs
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(chatWellColor(t))
            // The strip's `border-bottom`; the selected tab paints over it (it joins the pane below).
            .bottomRule(t.line)
            .semantics { contentDescription = "Session and sub-agent runs" }
            .testTag("subrun-tabs"),
        contentPadding = PaddingValues(start = if (phone) gap else t.css.spaceSm, end = if (phone) gap else t.css.spaceSm, top = gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        item(key = "session") {
            val selected = activeRunId == null
            RunTab(
                selected = selected,
                isError = false,
                nested = false,
                description = if (runningCount > 0 && !selected) "Session · $runningCount sub-agent${if (runningCount == 1) "" else "s"} running" else "Session",
                onClick = { onSelect(null) },
                tag = "subrun-tab-session",
            ) { ink, meta ->
                TabLabel("Session", ink)
                if (runningCount > 0 && !selected) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.2.dp)) {
                        SpinningIcon(TetherIcons.Loader, tint = meta, size = 11.dp)
                        TabMeta("$runningCount", meta)
                    }
                }
            }
        }
        items(runs, key = { it.runId }) { run ->
            val selected = run.runId == activeRunId
            val isError = run.status == RUN_ERROR
            RunTab(
                selected = selected,
                isError = isError,
                nested = run.depth > 1,
                description = (if (run.depth > 1) "Nested run: " else "") + runTabTitle(run, runsById),
                onClick = { onSelect(run.runId) },
                tag = "subrun-tab-${run.runId}",
            ) { ink, meta ->
                if (run.depth > 1) Icon(TetherIcons.CornerDownRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
                RunStatusIcon(run.status, 12.dp, if (isError) t.danger else ink)
                RunHarness(run, if (selected) t.ink else t.muted)
                TabLabel(run.title, ink, Modifier.weight(1f, fill = false))
                TabMeta(runTabMeta(run), meta)
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, ink: Color, modifier: Modifier = Modifier) {
    val type = LocalTetherTypography.current
    val size = if (currentLayoutClass() == TetherLayoutClass.Phone) rem(0.78f) else rem(0.8f)
    Text(text, style = TextStyle(fontFamily = type.ui, fontSize = size), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun TabMeta(text: String, color: Color) {
    val type = LocalTetherTypography.current
    Text(text, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)), color = color, maxLines = 1)
}

/**
 * `.subrun-tab`: muted text, transparent; selected = white on `--graphite-raised` under a `--line`
 * border (none below), with the 2px violet top edge, painted over the strip's bottom rule.
 */
@Composable
private fun RunTab(
    selected: Boolean,
    isError: Boolean,
    nested: Boolean,
    description: String,
    onClick: () -> Unit,
    tag: String,
    content: @Composable RowScope.(ink: Color, meta: Color) -> Unit,
) {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val radius = t.radiusSm
    val line = t.line
    val violet = t.violetStrong
    val raised = t.graphiteRaised
    val ink = if (selected) t.white else t.muted
    val meta = if (selected) t.muted else t.faint
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(max = if (phone) 208.dp else 288.dp)
            .drawBehind {
                val r = radius.toPx()
                if (selected) {
                    // Background + top/side border; the bottom edge stays open (covers the strip rule).
                    drawRoundRect(raised, cornerRadius = CornerRadius(r), size = Size(size.width, size.height + r))
                    drawRoundRect(line, cornerRadius = CornerRadius(r), size = Size(size.width, size.height + r), style = Stroke(1.dp.toPx()))
                    drawRect(raised, topLeft = Offset(1.dp.toPx(), size.height - 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), 1.dp.toPx()))
                    drawRect(violet, topLeft = Offset(r / 2, 0f), size = Size(size.width - r, 2.dp.toPx()))
                }
                if (nested) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    drawLine(line, Offset(0.5f, 0f), Offset(0.5f, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                }
            }
            .clip(RoundedCornerShape(topStart = radius, topEnd = radius))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
                if (isError) stateDescription = "error"
                testTag = tag
            }
            .padding(start = if (nested || phone) t.css.spaceSm else t.css.spaceMd, end = if (phone) t.css.spaceSm else t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.4.dp),
    ) {
        content(ink, meta)
    }
}

// --- The chips -----------------------------------------------------------------------------------

/** `.subrun-chip`: mono 0.68rem muted, `--line` border, `--radius-sm`, padded 0.15rem 0.45rem. */
@Composable
private fun SubrunChip(
    text: String,
    muted: Boolean = false,
    border: Color? = null,
    ink: Color? = null,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Row(
        Modifier
            .then(if (muted) Modifier.dashedBorder(t.line, t.radiusSm) else Modifier.border(1.dp, border ?: t.line, shape))
            .padding(horizontal = 7.2.dp, vertical = 2.4.dp)
            .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Text(text, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)), color = ink ?: t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * `SubagentRunStats`: the identity/usage chips of one run. Token counts only, no cost; every
 * absent value is omitted, and an unmeasured run says so in words ("usage not captured", its reason
 * as the accessible description) rather than a zero.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubagentRunStats(run: SubagentRun) {
    val served = run.usage?.let { (it["model"] as? JsStr)?.value }
    val requested = run.requestedModel
    val showRequested = !requested.isNullOrEmpty() && requested != served
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.8.dp), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
        run.childProvider?.takeIf { it.isNotEmpty() }?.let { SubrunChip(it) }
        run.delegateMode?.takeIf { it.isNotEmpty() }?.let { SubrunChip(it) }
        served?.takeIf { it.isNotEmpty() }?.let { SubrunChip(it, description = "Served by $it") }
        if (showRequested) SubrunChip("asked $requested")
        run.requestedEffort?.takeIf { it.isNotEmpty() }?.let { SubrunChip("$it effort") }
        val tokens = run.totalTokens
        if (tokens != null) {
            SubrunChip("${Format.compactNumber(tokens)} tok")
        } else {
            SubrunChip("usage not captured", muted = true, description = "usage not captured. ${usageGapReason(run)}")
        }
    }
}

// --- The roster ----------------------------------------------------------------------------------

/**
 * `SubagentRoster`: the collapsible "Subagents N" summary at the top of the Session tab, closed by
 * default; each row selects its run's tab. `.subrun-roster-transcript` adds `space-lg` below it.
 */
@Composable
fun SubagentRoster(
    runs: List<SubagentRun>,
    activeRunId: String?,
    onSelect: (String?) -> Unit,
) {
    val summary = remember(runs) { subagentRosterSummary(runs) }
    if (summary.total == 0) return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(t.radiusMd)
    val line = t.line
    val summaryText = rosterSummaryText(summary) { Format.compactNumber(it) }
    Column(Modifier.fillMaxWidth().padding(bottom = t.css.spaceLg)) {
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, t.line, shape)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Subagents, ${summary.total}" + if (summaryText.isNotEmpty()) ", $summaryText" else ""
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .then(if (expanded) Modifier.bottomRule(line) else Modifier)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = t.css.spaceMd)
                    .testTag("subrun-roster"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                Icon(TetherIcons.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
                Text("Subagents", style = TextStyle(fontFamily = type.ui, fontSize = rem(0.78f), fontWeight = FontWeight(600)), color = t.ink)
                Text(
                    "${summary.total}",
                    style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)),
                    color = t.muted,
                    modifier = Modifier.border(1.dp, t.line, RoundedCornerShape(t.radiusSm)).padding(horizontal = 6.4.dp, vertical = 0.8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(summaryText, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                if (summary.partial && summary.measured > 0) {
                    Text(
                        "Totals cover ${summary.measured} of ${summary.total} runs — the rest have no captured usage.",
                        style = TextStyle(fontFamily = type.ui, fontSize = rem(0.72f)),
                        color = t.muted,
                        modifier = Modifier.fillMaxWidth().bottomRule(line).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                    )
                }
                runs.forEachIndexed { i, run -> RosterRow(run, run.runId == activeRunId, first = i == 0, onSelect = onSelect) }
            }
        }
    }
}

@Composable
private fun RosterRow(run: SubagentRun, active: Boolean, first: Boolean, onSelect: (String?) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    val violet = t.violetStrong
    val isError = run.status == RUN_ERROR
    val ink = if (active) t.ink else t.muted
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.background(t.violetWash) else Modifier)
            .drawBehind {
                if (!first) drawRect(line, size = Size(size.width, 1.dp.toPx()))
                if (active) drawRect(violet, size = Size(2.dp.toPx(), size.height))
            }
            .clickable(role = Role.Button) { onSelect(run.runId) }
            .semantics(mergeDescendants = true) {
                contentDescription = (if (run.depth > 1) "Nested run: " else "") +
                    listOfNotNull(run.title, run.agentType, harnessLabel(run), runStatusText(run)).filter { it.isNotEmpty() }.joinToString(" · ")
                if (active) selected = true
            }
            .heightIn(min = 44.dp)
            .padding(start = if (run.depth > 1) t.css.spaceMd + t.css.spaceLg else t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceSm, bottom = t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(5.6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            if (run.depth > 1) Icon(TetherIcons.CornerDownRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
            RunStatusIcon(run.status, 12.dp, if (isError) t.danger else ink)
            RunHarness(run, ink)
            Text(
                run.title,
                style = TextStyle(fontFamily = type.ui, fontSize = rem(0.78f)),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            run.agentType?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)), color = t.muted, maxLines = 1)
            }
        }
        SubagentRunStats(run)
    }
}

// --- One run's panel ------------------------------------------------------------------------------

/** A request to land on one step of a run (a denial's origin link): [nonce] re-fires the same one. */
@Immutable
internal data class RunFocus(val runId: String, val toolId: String, val nonce: Int)

/** How long a focused step's outline flashes (`.subrun-entry-flash`, 2.2s). */
private const val FLASH_MS = 2_200

/**
 * One run tab: the run's panel — header, task, the step stream (one lazy row per step), the
 * result — then the ACTIVE turn's pending approval / question cards (a card the turn is stalled
 * on is never hidden behind a tab). A running run follows its newest step; a finished one parks
 * at the top. [focus] (a denial's origin link) brings its step to the middle and flashes it.
 */
@Composable
internal fun SubagentRunTab(
    run: SubagentRun,
    showThinking: Boolean,
    pending: List<ApprovalView>,
    pendingQuestions: List<QuestionRequestView>,
    answeredIds: Set<String>,
    focus: RunFocus? = null,
) {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val spacing = transcriptSpacing(t, phone)
    val listState = rememberLazyListState()
    val entries = remember(run.thread, showThinking) { subagentRunEntries(run, showThinking) }
    val rows = remember(run, entries) { panelRows(run, entries) }
    val cardCount = pending.size + pendingQuestions.size
    val lastIndex = rows.size + cardCount - 1
    val focusKey = focus?.takeIf { it.runId == run.runId }

    LaunchedEffect(run.runId, entries.size, cardCount, run.status, focusKey) {
        if (focusKey != null) return@LaunchedEffect
        if (run.status == RUN_RUNNING) listState.scrollToItem(lastIndex.coerceAtLeast(0), scrollOffset = Int.MAX_VALUE / 2) else listState.scrollToItem(0)
    }
    LaunchedEffect(focusKey) {
        val f = focusKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is PanelRow.Step && (it.entry["key"] as? JsStr)?.value == f.toolId }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        listState.scrollToItem(index, scrollOffset = -((viewport - item.size) / 2).coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(chatWellColor(t)).testTag("subrun-panel"),
        contentPadding = spacing.padding,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        items(rows, key = { it.key }, contentType = { row -> if (row is PanelRow.Step) "step/" + ((row.entry["kind"] as? JsStr)?.value ?: "") else row::class.java.simpleName }) { row ->
            val flashing = row is PanelRow.Step && focusKey != null && (row.entry["key"] as? JsStr)?.value == focusKey.toolId
            PanelRowView(row, showThinking, flashing, if (flashing) focusKey?.nonce else null)
        }
        items(pending, key = { "approval/${it.requestId}/${it.contentFp}" }) { ApprovalCard(it) }
        items(pendingQuestions, key = { "question/${it.requestId}/${it.contentFp}" }) { QuestionCard(it, answered = it.requestId in answeredIds) }
    }
}

/** One panel row. Skippable: an equal [row] (a step whose entry kept its instance) does not recompose. */
@Composable
private fun PanelRowView(row: PanelRow, showThinking: Boolean, flashing: Boolean, nonce: Int?) {
    val observer = LocalChatRowObserver.current
    if (observer != null) SideEffect { observer(row.key) }
    when (row) {
        is PanelRow.Head -> RunHead(row.run)
        is PanelRow.Prompt -> RunPrompt(row.runId, row.prompt)
        is PanelRow.ThreadNote -> ThreadNote(row.run)
        is PanelRow.Spawned -> SpawnedRunOutput(row.run)
        is PanelRow.Empty -> RunEmpty(row.running)
        is PanelRow.Step -> StepFlash(flashing, nonce) { RunStep(row.entry, showThinking) }
        is PanelRow.Result -> RunResult(row.run)
    }
}

/** The panel's rows, keyed stably (a step by its tool_use / entry key). */
@Immutable
internal sealed interface PanelRow {
    val key: String

    data class Head(val run: SubagentRun) : PanelRow {
        override val key: String get() = "run-head"
    }
    data class Prompt(val runId: String, val prompt: String) : PanelRow {
        override val key: String get() = "run-prompt"
    }
    data class ThreadNote(val run: SubagentRun) : PanelRow {
        override val key: String get() = "run-thread-note"
    }
    data class Spawned(val run: SubagentRun) : PanelRow {
        override val key: String get() = "run-spawned"
    }
    data class Empty(val running: Boolean) : PanelRow {
        override val key: String get() = "run-empty"
    }

    /** One step; [entry] keeps its identity across folds that did not touch it (so its row skips). */
    data class Step(val entry: JsObj, override val key: String) : PanelRow
    data class Result(val run: SubagentRun) : PanelRow {
        override val key: String get() = "run-result"
    }
}

/**
 * `SubagentRunPanel`'s sections in order: head, task, the thread note (a lifecycle-only child),
 * then the spawned child's output, or nothing more for a thread child, or the empty line, or one
 * row per step of [entries]; the result once the run is over.
 */
internal fun panelRows(run: SubagentRun, entries: List<JsObj>): List<PanelRow> {
    val rows = ArrayList<PanelRow>(entries.size + 4)
    rows.add(PanelRow.Head(run))
    run.prompt?.takeIf { it.isNotEmpty() }?.let { rows.add(PanelRow.Prompt(run.runId, it)) }
    if (run.source == RunSource.THREAD) rows.add(PanelRow.ThreadNote(run))
    when {
        run.spawned != null -> rows.add(PanelRow.Spawned(run))
        run.source == RunSource.THREAD -> Unit
        entries.isEmpty() -> rows.add(PanelRow.Empty(run.status == RUN_RUNNING))
        else -> entries.forEachIndexed { i, e -> rows.add(PanelRow.Step(e, "step/" + ((e["key"] as? JsStr)?.value ?: "#$i"))) }
    }
    if (run.status != RUN_RUNNING && (runResultText(run).isNotEmpty() || extractToolMedia(run.output).isNotEmpty())) rows.add(PanelRow.Result(run))
    return rows
}

/** `summarize(stripToolMedia(run.output), 4000)`. */
internal fun runResultText(run: SubagentRun): String = summarize(stripToolMedia(run.output), 4000)

/** The header's status chip: "running · 12s", "stopped · exit 143", "running (unconfirmed)". */
internal fun runHeadStatus(run: SubagentRun): String {
    val elapsed = Elapsed.elapsedLabel(run.elapsedSeconds?.let(::JsNum))
    return runStatusText(run) +
        (if (run.status == RUN_RUNNING && elapsed.isNotEmpty()) " · $elapsed" else "") +
        (run.spawned?.exitCode?.let { " · exit $it" } ?: "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunHead(run: SubagentRun) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier.fillMaxWidth().bottomRule(t.line).padding(bottom = t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            Icon(TetherIcons.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
            Text(
                run.title,
                style = TextStyle(fontFamily = type.ui, fontSize = rem(0.95f), fontWeight = FontWeight(600)),
                color = t.white,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs), verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
            run.agentType?.takeIf { it.isNotEmpty() }?.let { SubrunChip(it) }
            val (border, ink) = when (run.status) {
                RUN_RUNNING -> t.violetStrong to t.ink
                RUN_ERROR -> t.dangerEdge to t.danger
                else -> t.line to t.muted
            }
            val status = runHeadStatus(run)
            SubrunChip(
                status,
                border = border,
                ink = ink,
                description = status + if (run.unconfirmed) ". Launched in background — no completion recorded" else "",
                leading = { RunStatusIcon(run.status, 12.dp, ink) },
            )
            if (run.source != RunSource.THREAD && run.spawned == null) SubrunChip(stepsLabel(run.steps))
        }
        SubagentRunStats(run)
    }
}

/** `.subrun-prompt`: "Task given to this sub-agent", collapsed; its markdown once opened. */
@Composable
private fun RunPrompt(runId: String, prompt: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var open by rememberSaveable(runId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(t.radiusMd)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.tintXs).border(1.dp, t.line, shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .then(if (open) Modifier.bottomRule(t.line) else Modifier)
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Task given to this sub-agent", style = TextStyle(fontFamily = type.ui, fontSize = rem(0.76f)), color = t.muted)
        }
        if (open) {
            val blocks = remember(prompt) { parseMarkdown(prompt) }
            Box(Modifier.padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)) {
                MarkdownBody(blocks, type.chatBody.copy(fontSize = rem(0.82f)), t.ink)
            }
        }
    }
}

/** `.subrun-thread-note`: a lifecycle-only child says so instead of an empty "no activity" box. */
@Composable
private fun ThreadNote(run: SubagentRun) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier.fillMaxWidth().dashedBorder(t.line, t.radiusMd).padding(horizontal = t.css.spaceLg, vertical = t.css.spaceMd),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Text(
            "${run.provider?.takeIf { it.isNotEmpty() } ?: "The harness"} ran this sub-agent in its own thread. Only its lifecycle " +
                "is reported to this session — its steps and token usage stay on that thread.",
            style = TextStyle(fontFamily = type.ui, fontSize = rem(0.82f)),
            color = t.muted,
        )
        val meta = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f))
        run.agentPath?.takeIf { it.isNotEmpty() }?.let { Text("Agent path · $it", style = meta, color = t.faint) }
        run.agentThreadId?.takeIf { it.isNotEmpty() }?.let { Text("Thread · $it", style = meta, color = t.faint) }
        if (run.lifecycle.isNotEmpty()) Text("Lifecycle · ${run.lifecycle.joinToString(" → ")}", style = meta, color = t.faint)
    }
}

/** `.subrun-empty`: waiting for the first step, or nothing was recorded. */
@Composable
private fun RunEmpty(running: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier.fillMaxWidth().dashedBorder(t.line, t.radiusMd).padding(t.css.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        if (running) SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 14.dp) else Icon(TetherIcons.CircleStop, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text(
            if (running) "Waiting for this sub-agent’s first step…" else "No step-by-step activity was recorded for this run.",
            style = TextStyle(fontFamily = type.ui, fontSize = rem(0.82f)),
            color = t.muted,
        )
    }
}

/** `EntryView`: a message (markdown), a thinking card, or a full tool card (the pane's width). */
@Composable
private fun RunStep(entry: JsObj, showThinking: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    when ((entry["kind"] as? JsStr)?.value) {
        "message" -> (entry["text"] as? JsStr)?.value?.takeIf { it.isNotEmpty() }?.let { text ->
            val blocks = remember(text) { parseMarkdown(text) }
            MarkdownBody(blocks, type.chatBody.copy(fontSize = rem(0.86f), lineHeight = 1.55.em), t.ink)
        }
        "thinking" -> if (showThinking) {
            val text = (entry["text"] as? JsStr)?.value
            val key = (entry["key"] as? JsStr)?.value ?: ""
            if (!text.isNullOrEmpty()) ThinkingCard(remember(key, text) { TurnBlock(blockId = key, kind = "thinking", text = text) })
        }
        else -> ToolCard(entry, showThinking, fullWidth = true)
    }
}

/** `.subrun-entry-flash`: a focused step's violet outline, fading over 2.2s (held, then cleared, with reduced motion). */
@Composable
private fun StepFlash(active: Boolean, nonce: Int?, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active, nonce) {
        if (!active) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        alpha.snapTo(1f)
        if (reduced) {
            kotlinx.coroutines.delay(FLASH_MS.toLong())
            alpha.snapTo(0f)
        } else {
            alpha.animateTo(0f, tween(FLASH_MS))
        }
    }
    val violet = t.violetStrong
    val radius = t.radiusMd
    Box(
        Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            if (alpha.value > 0f) drawRoundRect(violet.copy(alpha = alpha.value), cornerRadius = CornerRadius(radius.toPx()), style = Stroke(2.dp.toPx()))
        },
    ) { content() }
}

/** `.subrun-result`: what the run handed back ("Result" / "Error returned to the parent"). */
@Composable
private fun RunResult(run: SubagentRun) {
    val isError = run.status == RUN_ERROR
    val media = remember(run.output) { extractToolMedia(run.output) }
    val text = remember(run.output) { runResultText(run) }
    ResultSection(
        heading = if (isError) "Error returned to the parent" else "Result returned to the parent",
        status = if (isError) RUN_ERROR else RUN_DONE,
    ) {
        ToolMediaRow(media)
        if (text.isNotEmpty()) ToolIoPre(text, output = true)
    }
}

@Composable
private fun ResultSection(heading: String, status: String, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, t.line, shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .bottomRule(t.line)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            RunStatusIcon(status, 13.dp, t.ink)
            Text(heading, style = TextStyle(fontFamily = type.ui, fontSize = rem(0.78f), fontWeight = FontWeight(600)), color = t.ink)
        }
        content()
    }
}

/**
 * `SpawnedRunOutput` (issue #173): a spawned agent-CLI child has no step thread. Its native id and
 * log file, the pictures it was handed / looked at (v122, T6.2's [SpawnedRunMedia]), then its
 * captured output (capped by the fold; "… (truncated — the full output is in the log file)"), or
 * the discovered run's note, or the waiting / no-output line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpawnedRunOutput(run: SubagentRun) {
    val spawned = run.spawned ?: return
    val t = LocalTetherTokens.current
    val discovered = spawned.origin == "discovered"
    ResultSection(
        heading = when {
            discovered -> "Linked native run"
            run.status == RUN_RUNNING -> "Live output"
            else -> "Output"
        },
        status = run.status,
    ) {
        Column(Modifier.fillMaxWidth().testTag("spawned-run-output")) {
            if (!spawned.nativeId.isNullOrEmpty() || !spawned.logFile.isNullOrEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                    horizontalArrangement = Arrangement.spacedBy(4.8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.8.dp),
                ) {
                    spawned.nativeId?.takeIf { it.isNotEmpty() }?.let { SubrunChip(it, description = "Native session id $it") }
                    spawned.logFile?.takeIf { it.isNotEmpty() }?.let { SubrunChip(it, description = "Full output log $it") }
                }
            }
            SpawnedRunMedia(spawned.media, "input", "Images handed to this agent", Modifier.padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs))
            SpawnedRunMedia(spawned.media, "viewed", "Images this agent looked at", Modifier.padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs))
            when {
                discovered -> SpawnedNote("Launched outside Tether with this session's marker. Its status follows the child's own completion record.", running = null)
                spawned.output.isNotEmpty() -> {
                    val text = if (spawned.outputTruncated) "${spawned.output}\n… (truncated — the full output is in the log file)" else spawned.output
                    ToolIoPre(text, output = true)
                }
                else -> SpawnedNote(if (run.status == RUN_RUNNING) "Waiting for this agent's first output…" else "This agent printed no output.", running = run.status == RUN_RUNNING)
            }
        }
    }
}

@Composable
private fun SpawnedNote(text: String, running: Boolean?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier.fillMaxWidth().padding(t.css.spaceSm).dashedBorder(t.line, t.radiusMd).padding(t.css.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        when (running) {
            true -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 14.dp)
            false -> Icon(TetherIcons.CircleStop, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            null -> Unit
        }
        Text(text, style = TextStyle(fontFamily = type.ui, fontSize = rem(0.82f)), color = t.muted)
    }
}
