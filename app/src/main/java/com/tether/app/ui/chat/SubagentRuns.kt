package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wrench
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.reduce.RUN_DONE
import com.tether.app.protocol.reduce.RUN_ERROR
import com.tether.app.protocol.reduce.RUN_RUNNING
import com.tether.app.protocol.reduce.SubagentRun
import com.tether.app.protocol.reduce.SubagentRosterSummary
import com.tether.app.protocol.reduce.subagentRunEntries
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.compactNumber
import com.tether.app.ui.util.elapsedLabel
import com.tether.app.ui.util.estimatedUsd

private val STATUS_TEXT = mapOf(RUN_RUNNING to "running", RUN_ERROR to "error", RUN_DONE to "done")

@Composable
private fun RunStatusIcon(status: String, size: Dp) {
    val t = LocalTetherTokens.current
    when (status) {
        RUN_RUNNING -> SpinningIcon(Lucide.Loader, tint = t.muted, size = size)
        RUN_ERROR -> Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(size))
        else -> Icon(Lucide.Check, contentDescription = null, tint = t.faint, modifier = Modifier.size(size))
    }
}

/**
 * The tab strip: tab 0 is the whole session transcript; each following tab is
 * one sub-agent run. Selection by run.runId (null = transcript). Violet marks
 * the selected tab only; status is icon + text, never color alone.
 */
@Composable
fun SubagentTabs(
    runs: List<SubagentRun>,
    activeRunId: String?,
    onSelect: (String?) -> Unit,
) {
    val t = LocalTetherTokens.current
    val runningCount = runs.count { it.status == RUN_RUNNING }
    val listState = rememberLazyListState()

    // Keep the selected tab in view when it changes off-screen.
    LaunchedEffect(activeRunId) {
        val index = activeRunId?.let { id -> runs.indexOfFirst { it.runId == id } + 1 } ?: 0
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(t.graphite)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "session") {
            TabChip(selected = activeRunId == null, onClick = { onSelect(null) }) {
                Text(
                    "Session",
                    color = if (activeRunId == null) t.white else t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                )
                if (runningCount > 0 && activeRunId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SpinningIcon(Lucide.Loader, tint = t.muted, size = 11.dp)
                        Text("$runningCount", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 11.2.sp)
                    }
                }
            }
        }
        items(runs.size, key = { runs[it].runId }) { index ->
            val run = runs[index]
            val selected = run.runId == activeRunId
            TabChip(selected = selected, isError = run.status == RUN_ERROR, onClick = { onSelect(run.runId) }) {
                RunStatusIcon(run.status, 12.dp)
                Text(
                    run.title,
                    color = if (selected) t.white else t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (run.status == RUN_RUNNING) "running" else "${run.steps} step${if (run.steps == 1) "" else "s"}",
                    color = if (run.status == RUN_ERROR) t.danger else t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.2.sp,
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    selected: Boolean,
    onClick: () -> Unit,
    isError: Boolean = false,
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusSm)
    Row(
        Modifier
            .height(32.dp)
            .background(if (selected) t.violetWash else t.keyFace, shape)
            .border(
                1.dp,
                when {
                    selected -> t.violetStrong
                    isError -> t.dangerEdge
                    else -> t.keySide
                },
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** The identity/cost readings for one run, as a chip row. Absent = omitted. */
@Composable
fun SubagentRunStats(run: SubagentRun) {
    val served = run.usage?.model
    val showRequested = run.requestedModel != null && run.requestedModel != served
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        served?.let { SubrunChip(it) }
        if (showRequested) SubrunChip("asked ${run.requestedModel}")
        run.requestedEffort?.let { SubrunChip("$it effort") }
        val tokens = run.totalTokens
        if (tokens != null) {
            SubrunChip("${compactNumber(tokens)} tok")
        } else {
            SubrunChip("usage not captured", muted = true)
        }
        run.estimatedCostUSD?.let { SubrunChip("~${estimatedUsd(it)}") }
    }
}

@Composable
private fun SubrunChip(text: String, muted: Boolean = false) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = if (muted) t.faint else t.muted,
        fontFamily = JetBrainsMono,
        fontSize = 10.6.sp,
        maxLines = 1,
        modifier = Modifier
            .background(t.tintXs, RoundedCornerShape(999.dp))
            .border(1.dp, t.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * The collapsible Subagents roster: Session-tab-only headline that expands
 * into rows jumping to each run's tab.
 */
@Composable
fun SubagentRoster(
    runs: List<SubagentRun>,
    summary: SubagentRosterSummary,
    activeRunId: String?,
    onSelect: (String?) -> Unit,
) {
    if (summary.total == 0) return
    val t = LocalTetherTokens.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summaryParts = buildList {
        if (summary.running > 0) add("${summary.running} running")
        if (summary.errored > 0) add("${summary.errored} failed")
        summary.tokens?.let { add("${compactNumber(it)} tok") }
        summary.estimatedCostUSD?.let { add("~${estimatedUsd(it)}") }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimens.radiusMd))
            .background(t.mineralDeep)
            .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = TetherDimens.touchTargetDp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Lucide.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            Text("Subagents", color = t.ink, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 12.8.sp)
            Text("${summary.total}", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.strong, fontSize = 11.5.sp)
            Spacer(Modifier.weight(1f))
            Text(
                summaryParts.joinToString(" · "),
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = t.faint,
                modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            if (summary.partial && summary.measured > 0) {
                Text(
                    "Totals cover ${summary.measured} of ${summary.total} runs — the rest have no captured usage.",
                    color = t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.8.sp,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                )
            }
            runs.forEach { run ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(run.runId) }
                        .then(
                            if (run.runId == activeRunId) {
                                Modifier.border(1.dp, t.violetStrong)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RunStatusIcon(run.status, 12.dp)
                        Text(
                            run.title,
                            color = if (run.status == RUN_ERROR) t.danger else t.ink,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.label,
                            fontSize = 12.8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        run.agentType?.let {
                            Text(it, color = t.faint, fontFamily = JetBrainsMono, fontSize = 10.6.sp)
                        }
                    }
                    SubagentRunStats(run)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * One sub-agent run, expanded: what it was asked, every step it took, and the
 * result it handed back — full width, no collapse.
 */
@Composable
fun SubagentRunPanel(run: SubagentRun, showThinking: Boolean) {
    val t = LocalTetherTokens.current
    val entries = remember(run, showThinking) { subagentRunEntries(run, showThinking) }
    val elapsed = elapsedLabel(run.elapsedSeconds?.toLong())
    val result = remember(run.output) { toolOutputText(run.output, 4000) }
    var promptExpanded by rememberSaveable(run.runId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Header: title + identity/status chips + readings.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
                Text(
                    run.title,
                    color = t.white,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.heading,
                    fontSize = 15.2.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                run.agentType?.let { SubrunChip(it) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(t.tintXs, RoundedCornerShape(999.dp))
                        .border(1.dp, t.line, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    RunStatusIcon(run.status, 12.dp)
                    Text(
                        STATUS_TEXT[run.status].orEmpty() + if (run.status == RUN_RUNNING && elapsed.isNotEmpty()) " · $elapsed" else "",
                        color = if (run.status == RUN_ERROR) t.danger else t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 10.6.sp,
                    )
                }
                SubrunChip("${run.steps} step${if (run.steps == 1) "" else "s"}")
            }
            SubagentRunStats(run)
        }

        // The task, collapsed.
        if (run.prompt != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TetherDimens.radiusMd))
                    .background(t.tintXs)
                    .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { promptExpanded = !promptExpanded }
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Task given to this sub-agent",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 12.2.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Lucide.ChevronRight,
                        contentDescription = if (promptExpanded) "Collapse" else "Expand",
                        tint = t.faint,
                        modifier = Modifier.size(14.dp).rotate(if (promptExpanded) 90f else 0f),
                    )
                }
                if (promptExpanded) {
                    Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                        MarkdownText(text = run.prompt, color = t.ink, fontSize = 13.1.sp)
                    }
                }
            }
        }

        // The step stream.
        if (entries.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.status == RUN_RUNNING) {
                    SpinningIcon(Lucide.Loader, tint = t.muted, size = 14.dp)
                    Text(
                        "Waiting for this sub-agent’s first step…",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 13.1.sp,
                    )
                } else {
                    Icon(Lucide.CircleStop, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
                    Text(
                        "No step-by-step activity was recorded for this run.",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 13.1.sp,
                    )
                }
            }
        } else {
            entries.forEach { entry ->
                when (entry.kind) {
                    "message" -> AgentBubble(
                        TurnBlock(blockId = entry.key, kind = "message", text = entry.text, done = true),
                    )
                    "thinking" -> ThinkingCard(
                        TurnBlock(blockId = entry.key, kind = "thinking", text = entry.text, done = true),
                    )
                    else -> SubrunToolCard(entry)
                }
            }
        }

        // The result handed back to the parent.
        if (run.status != RUN_RUNNING && !result.isNullOrEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TetherDimens.radiusMd))
                    .background(t.mineralDeep)
                    .border(1.dp, if (run.isError) t.dangerEdge else t.line, RoundedCornerShape(TetherDimens.radiusMd)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (run.isError) {
                        Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(13.dp))
                    } else {
                        Icon(Lucide.Check, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
                    }
                    Text(
                        if (run.isError) "Error returned to the parent" else "Result returned to the parent",
                        color = if (run.isError) t.danger else t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 12.2.sp,
                    )
                }
                Text(
                    result,
                    color = t.ink,
                    fontFamily = JetBrainsMono,
                    fontSize = 12.2.sp,
                    lineHeight = 12.2.sp * 1.5f,
                    modifier = Modifier.fillMaxWidth().background(t.tintXs).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One nested tool call in a run's stream — the compact subrun-block form. */
@Composable
private fun SubrunToolCard(entry: SubagentEntry) {
    val t = LocalTetherTokens.current
    val running = entry.done != true
    val isError = entry.isError == true
    val summary = remember(entry.name, entry.input) { toolInputSummary(entry.name, entry.input) }
    val output = remember(entry.output) { toolOutputText(entry.output) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimens.radiusMd))
            .background(t.mineralDeep)
            .border(
                1.dp,
                when {
                    isError -> t.dangerEdge
                    running -> t.lineStrong
                    else -> t.line
                },
                RoundedCornerShape(TetherDimens.radiusMd),
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                running -> SpinningIcon(Lucide.Loader, tint = t.muted, size = 14.dp)
                isError -> Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(14.dp))
                else -> Icon(Lucide.Wrench, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            }
            Text(
                entry.name ?: "tool",
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontWeight = TetherWeights.label,
                fontSize = 12.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            val statusText = when {
                running -> {
                    val secs = entry.elapsedSeconds?.toLong()
                    if (secs != null) "running · ${elapsedLabel(secs)}" else "running"
                }
                isError -> "error"
                else -> "done"
            }
            Text(
                statusText,
                color = if (isError) t.danger else t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 11.5.sp,
                letterSpacing = 0.05.em,
            )
        }
        summary?.let {
            Text(
                it,
                color = t.muted,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
        if (!running && output != null) {
            Text(
                output,
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                lineHeight = 12.2.sp * 1.5f,
                modifier = Modifier.fillMaxWidth().background(t.tintXs).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}