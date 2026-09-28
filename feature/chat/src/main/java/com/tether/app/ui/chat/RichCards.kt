package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.components.TetherExpandablePre
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import java.util.Locale

/*
 * T6.2: the engine rich cards — components/codex-rich-renderers.tsx (command, file change, MCP,
 * collaboration, sub-agent activity, plan, turn diff, review) and
 * components/opencode-rich-renderers.tsx (task) — styled from their CSS modules
 * (codex-rich-renderers.module.css, opencode-rich-renderers.module.css: the same shapes). The
 * Studio skin has no overrides for these modules. Codex notices / MCP health are T6.6.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * `.card`: `--mineral-deep` under a `--line` border (`--danger-edge` failed), `--radius-md`,
 * `--chat-card-width` (100% below 42rem).
 */
@Composable
internal fun RichCard(failed: Boolean, label: String, modifier: Modifier = Modifier, nested: Boolean = false, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .maxWidthFraction(cardFraction(nested))
                .fillMaxWidth()
                .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, if (failed) t.dangerEdge else t.line))
                .padding(1.dp)
                .clip(RoundedCornerShape(t.radiusMd - 1.dp))
                .semantics { contentDescription = label }
                .testTag("rich-card"),
        ) { content() }
    }
}

/** `StatusIcon`: spinner running, alert failed, check otherwise. */
@Composable
private fun RichStatusIcon(running: Boolean, failed: Boolean) {
    val t = LocalTetherTokens.current
    when {
        running -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 14.dp)
        failed -> HeadIcon(TetherIcons.TriangleAlert, t.muted)
        else -> HeadIcon(TetherIcons.Check, t.muted)
    }
}

/**
 * `.head`: min 2.75rem, padded `space-sm space-md`, mono 0.78rem muted glyphs, the title in ink
 * 650, the status pushed right (0.68rem/650/0.04em uppercase, muted; `--danger` when failed).
 */
@Composable
private fun RichHead(title: String, status: String?, failed: Boolean, modifier: Modifier = Modifier, icons: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        icons()
        Text(title, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.78f), fontWeight = FontWeight(650)), color = t.ink, modifier = Modifier.weight(1f))
        if (status != null) {
            Text(
                status.uppercase(Locale.ROOT),
                style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f), fontWeight = FontWeight(650), letterSpacing = 0.04.em),
                color = if (failed) t.danger else t.muted,
                modifier = Modifier.semantics { contentDescription = status },
            )
        }
    }
}

/** `.copy` / `.result` / `.empty` / `.identifiers` / `.error`: 0.82rem/1.5 pre-wrap under a rule. */
@Composable
private fun RichCopy(text: String, color: Color? = null, background: Color = Color.Transparent, label: String? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .topRule(t.line)
            .padding(top = 1.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
    ) {
        if (label != null) {
            // `.label`: block, 0.66rem/700/0.06em uppercase muted, `space-xs` below.
            Text(
                label.uppercase(Locale.ROOT),
                style = TextStyle(fontFamily = type.ui, fontSize = rem(0.66f), fontWeight = FontWeight(700), letterSpacing = 0.06.em),
                color = t.muted,
                modifier = Modifier.padding(bottom = t.css.spaceXs).semantics { contentDescription = label },
            )
        }
        Text(remember(text) { capHead(text) }, style = type.body.copy(fontSize = rem(0.82f), lineHeight = 1.5.em), color = color ?: t.ink)
    }
}

/** `.meta` / `.attribution`: a wrapping row, mono 0.68rem muted, padded `space-xs space-md`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RichMeta(items: List<Pair<androidx.compose.ui.graphics.vector.ImageVector?, String>>, label: String? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(
        Modifier
            .fillMaxWidth()
            .topRule(t.line)
            .padding(top = 1.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        items.forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                if (icon != null) HeadIcon(icon, t.muted, 12.dp)
                Text(text, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)), color = t.muted)
            }
        }
    }
}

/** `.command` / `.input` / `.output`: mono 0.76rem/1.55 ink pre-wrap under a rule, clamped. */
@Composable
private fun RichPre(text: String, background: Color, contentDescription: String? = null, live: Boolean = false) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    val shown = remember(text, live) { preText(if (live) capTail(text) else capHead(text)) }
    TetherExpandablePre(
        text = shown,
        style = type.codeBlock.copy(fontSize = rem(0.76f), lineHeight = 1.55.em),
        color = t.ink,
        clamp = toolClamp(),
        contentDescription = contentDescription,
        textModifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(background)
                drawRect(line, size = Size(size.width, 1.dp.toPx()))
            }
            .padding(top = 1.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
    )
}

// --- Codex tool cards -----------------------------------------------------------------------------

/** `CodexRichToolCard`: the card for [codexRichToolKind], or false when the block has none. */
@Composable
internal fun CodexRichToolCard(block: JsObj, nested: Boolean = false): Boolean {
    when (codexRichToolKind(block)) {
        "command_execution" -> CodexCommandCard(block, nested)
        "file_change" -> CodexFileChangeCard(block, nested)
        "mcp" -> CodexMcpCallCard(block, nested)
        "collaboration" -> CodexCollaborationCard(block, nested)
        "subagent_activity" -> CodexSubagentActivityCard(block, nested)
        else -> return false
    }
    return true
}

@Composable
private fun CodexCommandCard(block: JsObj, nested: Boolean) {
    val t = LocalTetherTokens.current
    val command = remember(block) { commandView(block) }
    val duration = formatDuration(command.durationMs)
    val meta = listOfNotNull(command.cwd, command.source, duration)
    RichCard(command.failed, "Codex command", nested = nested) {
        RichHead("Command", richStatusText(command.running, command.failed, command.status), command.failed) {
            RichStatusIcon(command.running, command.failed)
            HeadIcon(TetherIcons.Terminal, t.muted)
        }
        RichPre(command.command, t.graphite)
        if (meta.isNotEmpty()) RichMeta(meta.map { null to it })
        if (command.output.isNotEmpty() || command.running) {
            RichPre(command.output.ifEmpty { "Waiting for output…" }, t.tintXs, contentDescription = "Command output", live = command.running)
        }
        command.exitCode?.let { code -> ExitLine("Exit code $code") }
    }
}

/** `.exit`: mono 0.68rem muted, padded `space-xs space-md`, under a rule. */
@Composable
private fun ExitLine(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)),
        color = t.muted,
        modifier = Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs),
    )
}

@Composable
private fun CodexFileChangeCard(block: JsObj, nested: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val view = remember(block) { fileChangeView(block) }
    val plan = remember(view) { planDiffCard(view.changes.map { it.diff }, headerCost = 1) }
    RichCard(view.failed, "Codex file changes", nested = nested) {
        RichHead("File changes", richStatusText(view.running, view.failed, view.status), view.failed) {
            RichStatusIcon(view.running, view.failed)
            HeadIcon(TetherIcons.FileDiff, t.muted)
        }
        if (view.changes.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                // Only the changes the card's row budget reaches are drawn (a path row costs one).
                view.changes.take(plan.groupsDrawn).forEachIndexed { changeIndex, change ->
                    Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                        ) {
                            Text(cutLine(change.path, PATH_MAX).breakAnywhere(), style = TextStyle(fontFamily = type.mono, fontSize = rem(0.74f)), color = t.ink, modifier = Modifier.weight(1f))
                            // `.tag`: 0.64rem/0.04em uppercase muted on `--graphite-raised`.
                            Text(
                                change.kind.uppercase(Locale.ROOT),
                                style = TextStyle(fontFamily = type.mono, fontSize = rem(0.64f), letterSpacing = 0.04.em),
                                color = t.muted,
                                modifier = Modifier
                                    .background(t.graphiteRaised, RoundedCornerShape(t.radiusSm))
                                    .padding(horizontal = 6.4.dp, vertical = 1.28.dp)
                                    .semantics { contentDescription = change.kind },
                            )
                        }
                        val files = plan.files[changeIndex]
                        if (files.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                                files.forEach { planned -> DiffFile(planned.file, planned.label, planned.rows) }
                            }
                        }
                    }
                }
                plan.more?.let { DiffMore(it) }
            }
        } else if (view.streamingOutput.isEmpty()) {
            RichCopy("No file details were reported.")
        }
        if (view.streamingOutput.isNotEmpty()) RichPre(view.streamingOutput, t.tintXs, contentDescription = "Streaming file changes", live = true)
    }
}

@Composable
private fun CodexMcpCallCard(block: JsObj, nested: Boolean) {
    val t = LocalTetherTokens.current
    val mcp = remember(block) { mcpView(block) }
    val duration = formatDuration(mcp.durationMs)
    RichCard(mcp.failed, "MCP call ${mcp.server} ${mcp.tool}", nested = nested) {
        RichHead("${mcp.server} / ${mcp.tool}", richStatusText(mcp.running, mcp.failed, mcp.status), mcp.failed) {
            RichStatusIcon(mcp.running, mcp.failed)
            HeadIcon(TetherIcons.Network, t.muted)
        }
        if (mcp.appName != null || mcp.actionName != null || mcp.pluginId != null) {
            RichMeta(
                listOfNotNull(
                    mcp.appName?.let { TetherIcons.Box to "App · $it" },
                    mcp.actionName?.let { null to "Action · $it" },
                    mcp.pluginId?.let { TetherIcons.Package to "Plugin · $it" },
                ),
                label = "MCP attribution",
            )
        }
        if (mcp.arguments.isNotEmpty()) RichPre(mcp.arguments, Color.Transparent, contentDescription = "MCP arguments")
        if (mcp.progress.isNotEmpty()) RichPre(mcp.progress, t.tintXs, contentDescription = "MCP progress", live = true)
        if (mcp.result.isNotEmpty()) RichPre(mcp.result, t.tintXs, contentDescription = "MCP result")
        mcp.error?.let { RichCopy(it, color = t.danger) }
        duration?.let { RichMeta(listOf(null to it)) }
    }
}

@Composable
private fun CodexCollaborationCard(block: JsObj, nested: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val view = remember(block) { collaborationView(block) }
    RichCard(view.failed, "Collaboration ${view.action}", nested = nested) {
        RichHead(view.action, richStatusText(view.running, view.failed, view.status), view.failed) {
            RichStatusIcon(view.running, view.failed)
            HeadIcon(TetherIcons.Bot, t.muted)
        }
        if (view.model != null || view.reasoningEffort != null) {
            RichMeta(listOfNotNull(view.model?.let { null to "Model · $it" }, view.reasoningEffort?.let { null to "Effort · $it" }))
        }
        view.prompt?.let { RichCopy(it) }
        if (view.receivers.isNotEmpty()) {
            RichCopy("${view.receivers.size} recipient thread${if (view.receivers.size == 1) "" else "s"}")
        }
        if (view.agents.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).semantics { contentDescription = "Agent states" }) {
                view.agents.forEach { agent ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
                    ) {
                        Text(agent.id, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f)), color = t.muted, modifier = Modifier.weight(1f))
                        Text(agent.state, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.68f)), color = t.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodexSubagentActivityCard(block: JsObj, nested: Boolean) {
    val t = LocalTetherTokens.current
    val view = remember(block) { subagentView(block) }
    RichCard(view.failed, "Subagent activity", nested = nested) {
        RichHead("Subagent", view.kind, view.failed) {
            RichStatusIcon(view.running, view.failed)
            HeadIcon(TetherIcons.Bot, t.muted)
        }
        view.path?.let { RichCopy("Agent path · $it") }
    }
}

// --- Unified diff ---------------------------------------------------------------------------------

/**
 * `DiffFile`: the file head (diff glyph + label, mono 0.74rem ink) and its rows in a clamp that
 * scrolls sideways. `.diffRow`: a 1.75rem marker cell that stays put while the text scrolls
 * (`position: sticky`), then the text `white-space: pre`; hunk headers on `--tint-lg`,
 * additions/deletions on the diff tints, the rest muted. Plain text: no syntax highlighting.
 */
@Composable
internal fun DiffFile(file: DiffFileView, fallbackLabel: String, rowLimit: Int = DIFF_CARD_MAX_ROWS) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val label = cutLine(file.newPath ?: file.oldPath ?: fallbackLabel, PATH_MAX)
    Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).semantics { contentDescription = "Changes in $label" }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            HeadIcon(TetherIcons.FileDiff, t.ink)
            Text(label.breakAnywhere(), style = TextStyle(fontFamily = type.mono, fontSize = rem(0.74f)), color = t.ink)
        }
        // Collapsed, only a peek of rows is built (the clamp shows a few); open, up to [rowLimit].
        var open by remember { mutableStateOf(false) }
        val limit = minOf(rowLimit, file.rows.size)
        val total = file.totalRows
        TetherExpandableBlock(clamp = toolClamp(), onOpenChange = { open = it }, forceOverflow = limit > DIFF_PEEK_ROWS) {
            val more = if (open && total > limit) moreLinesLabel(total - limit) else null
            UnifiedDiffRows(file.rows, label, if (open) limit else minOf(limit, DIFF_PEEK_ROWS), more)
        }
    }
}

@Composable
private fun UnifiedDiffRows(allRows: List<UnifiedDiffRow>, label: String, limit: Int, more: String?) {
    // Security review M3 / R3-M2: [limit] rows (the card's budget share, or a peek while
    // collapsed), each cut at UNIFIED_LINE_MAX characters; a file the card cut short says how much.
    val rows = remember(allRows, limit, more) {
        val shown = allRows.take(limit).map { if (it.text.length > UNIFIED_LINE_MAX) it.copy(text = cutLine(it.text, UNIFIED_LINE_MAX)) else it }
        if (more != null) shown + UnifiedDiffRow("more", "…", more) else shown
    }
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = type.codeBlock.copy(fontSize = rem(0.72f), lineHeight = 1.5.em)
    val line = t.line
    fun colors(kind: String): Pair<Color, Color> = when (kind) {
        "add" -> t.diffAddBg to t.diffAddInk
        "delete" -> t.diffDelBg to t.diffDelInk
        "hunk" -> t.tintLg to t.muted
        else -> Color.Transparent to t.muted
    }
    val marker = 28.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .topRule(line)
            .padding(top = 1.dp)
            .semantics { contentDescription = "Unified diff for $label" }
            .testTag("unified-diff"),
    ) {
        val viewport = maxWidth - marker
        Row(Modifier.fillMaxWidth()) {
            // The sticky marker column.
            Column(Modifier.width(marker)) {
                rows.forEach { row ->
                    val (bg, _) = colors(row.kind)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .drawBehind { drawRect(line, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(row.marker, style = style, color = t.faint, softWrap = false)
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = viewport)) {
                    rows.forEach { row ->
                        val (bg, ink) = colors(row.kind)
                        Text(
                            row.text.ifEmpty { " " },
                            style = if (row.kind == "more") style.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else style,
                            color = if (row.kind == "more") t.faint else ink,
                            softWrap = false,
                            modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = t.css.spaceSm),
                        )
                    }
                }
            }
        }
    }
}

/** Rows a collapsed diff file builds (its clamp shows about a dozen). */
internal const val DIFF_PEEK_ROWS = 64

/** The card's "+N more lines · +M more files": faint italic mono under a rule. */
@Composable
private fun DiffMore(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        color = t.faint,
        modifier = Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs).testTag("diff-more"),
    )
}

/**
 * `CodexUnifiedDiff`: the turn's aggregate diff (`turn/diff/updated`), a `<details open>` card —
 * "Turn changes" and its file count — then each file.
 */
@Composable
internal fun CodexUnifiedDiff(unifiedDiff: String, label: String = "Turn changes") {
    val t = LocalTetherTokens.current
    // Parsed once, bounded by the card's row budget (R4-M2).
    val plan = remember(unifiedDiff) { planDiffCard(listOf(unifiedDiff)) }
    val fileCount = plan.totalFiles
    if (fileCount == 0) return
    val open = rememberDetailsOpen(true)
    RichCard(false, label) {
        RichHead(label, "${localeCount(fileCount)} file${if (fileCount == 1) "" else "s"}", false, Modifier
            .clickable(role = Role.Button) { open.toggle() }
            .semantics { stateDescription = if (open.value) "Expanded" else "Collapsed" }) {
            HeadIcon(TetherIcons.FileDiff, t.muted, 15.dp)
        }
        if (open.value) {
            Column(verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                plan.files.single().forEach { planned -> DiffFile(planned.file, planned.label, planned.rows) }
                plan.more?.let { DiffMore(it) }
            }
        }
    }
}

// --- Plan / review ----------------------------------------------------------------------------------

/**
 * `CodexPlanCard`: "Plan" with "N of M complete", the explanation, then the steps — a check,
 * a dot (in progress: `--violet` on `--violet-wash`) or a circle; completed steps struck through.
 * Below 42rem the status label drops under the step.
 */
@Composable
internal fun CodexPlanCard(plan: PlanView) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val narrow = currentLayoutClass() == TetherLayoutClass.Phone
    RichCard(false, "Codex plan") {
        RichHead("Plan", planCount(plan), false) { HeadIcon(TetherIcons.ClipboardCheck, t.muted, 15.dp) }
        plan.explanation?.let { RichCopy(it) }
        if (plan.steps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp)) {
                plan.steps.forEachIndexed { index, step ->
                    val inProgress = step.status == "in_progress"
                    val completed = step.status == "completed"
                    val tint = if (inProgress) t.violet else t.muted
                    val sep = t.tintSm
                    val last = index == plan.steps.lastIndex
                    val stepText = buildAnnotatedString {
                        if (completed) withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(step.step) } else append(step.step)
                    }
                    val small = planLabel(step.status)
                    val smallStyle = TextStyle(fontFamily = type.ui, fontSize = rem(0.65f), letterSpacing = 0.03.em)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (inProgress) t.violetWash else Color.Transparent)
                            .then(if (last) Modifier else Modifier.drawBehind { drawRect(sep, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) })
                            .padding(horizontal = t.css.spaceMd, vertical = 8.8.dp),
                        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                    ) {
                        Box(Modifier.padding(top = 1.dp)) {
                            HeadIcon(
                                when (step.status) {
                                    "completed" -> TetherIcons.Check
                                    "in_progress" -> TetherIcons.CircleDot
                                    else -> TetherIcons.Circle
                                },
                                tint,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stepText, style = type.body.copy(fontSize = rem(0.8f), lineHeight = 1.4.em), color = t.muted)
                            if (narrow && small.isNotEmpty()) Text(small.uppercase(Locale.ROOT), style = smallStyle, color = tint)
                        }
                        if (!narrow && small.isNotEmpty()) Text(small.uppercase(Locale.ROOT), style = smallStyle, color = tint)
                    }
                }
            }
        }
    }
}

/** `CodexReviewCard`: stop glyph when cancelled, else the status glyph; the target and result. */
@Composable
internal fun CodexReviewCard(review: ReviewView) {
    val t = LocalTetherTokens.current
    RichCard(review.failed, "Review ${review.statusLabel}") {
        RichHead("Review", review.statusLabel, review.failed) {
            if (review.cancelled) HeadIcon(TetherIcons.CircleStop, t.muted) else RichStatusIcon(review.running, review.failed)
        }
        review.target?.let { RichCopy(it, label = "Target") }
        review.result?.let { RichCopy(it, background = t.tintXs) }
    }
}

// --- opencode ---------------------------------------------------------------------------------------

/** `OpencodeRichToolCard`: the task card for opencode's `task` tool, or false. */
@Composable
internal fun OpencodeRichToolCard(block: JsObj, nested: Boolean = false): Boolean {
    if (opencodeRichToolKind(block) != "task") return false
    OpencodeTaskCard(block, nested)
    return true
}

@Composable
private fun OpencodeTaskCard(block: JsObj, nested: Boolean) {
    val t = LocalTetherTokens.current
    val task = remember(block) { taskView(block) }
    RichCard(task.failed, "opencode subagent task", nested = nested) {
        RichHead(task.description ?: "Subagent task", richStatusText(task.running, task.failed), task.failed) {
            RichStatusIcon(task.running, task.failed)
            HeadIcon(TetherIcons.Bot, t.muted)
        }
        task.subagentType?.let { RichMeta(listOf(null to "Agent · $it")) }
        task.prompt?.let { RichPre(it, t.graphite, contentDescription = "Subagent prompt") }
        if (task.output.isNotEmpty() || task.running || task.failed) {
            RichPre(
                task.output.ifEmpty { if (task.running) "Waiting for the subagent…" else "No error detail from the subagent." },
                t.tintXs,
                contentDescription = "Subagent result",
                live = task.running,
            )
        }
    }
}

/** Padding between the cards of one turn's rich details (`.turnDetails { gap: space-sm }`). */
internal val RichDetailsGap: Dp @Composable get() = LocalTetherTokens.current.css.spaceSm
