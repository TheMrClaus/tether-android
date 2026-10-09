package com.tether.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.helpers.Elapsed
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.components.TetherExpandablePre
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import java.util.Locale
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeText
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.safePreDisplay

/*
 * T6.2: the generic tool card (chat-view.tsx:360-390 `ToolCard`), its input renderer
 * (chat-tool-render.tsx `ToolInput` / `DiffBlock`), the interrupted-call evidence, the nested
 * sub-agent thread (chat-view.tsx:280-345) and the activity-group summary row
 * (chat-view.tsx:841-910). Styles resolve the cascade of globals.css (5062-5107 base, 6118-6165
 * edits, 9073-9084 + 11276-11289 + 11744-11745 material layer, 8460-8466 phone) and studio.css
 * (376-380).
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * ta-a5jl: the clamp a detail sheet gives its cards (a sheet scrolls, so it can afford more height than a transcript
 * row); null = the transcript's own [toolClamp].
 */
internal val LocalToolClamp = androidx.compose.runtime.compositionLocalOf<Dp?> { null }

/** ta-a5jl: true inside a detail sheet, where a card runs the full width of the sheet's body. */
internal val LocalFullWidthCards = androidx.compose.runtime.compositionLocalOf { false }

/** `--chat-clamp`: 9rem on a phone (globals.css:8464), 16rem wider. */
@Composable
internal fun toolClamp(): Dp {
    val t = LocalTetherTokens.current
    LocalToolClamp.current?.let { return it }
    return if (currentLayoutClass() == TetherLayoutClass.Phone) 144.dp else t.css.chatClamp
}

/** `--chat-card-width`: 100% on a phone, 94% wider; [nested] inside an activity group, of the group. */
@Composable
internal fun cardFraction(nested: Boolean = false): Float {
    val t = LocalTetherTokens.current
    if (LocalFullWidthCards.current) return 1f
    if (currentLayoutClass() == TetherLayoutClass.Phone) return 1f
    return if (nested) t.css.chatCardWidth * t.css.chatCardWidth else t.css.chatCardWidth
}

/** A `--line` hairline across the top of the box (a CSS `border-top: 1px`), drawn behind. */
internal fun Modifier.topRule(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    drawRect(color, size = Size(size.width, width.toPx()))
}

// --- The frame ------------------------------------------------------------------------------------

/** The tool card's visual state (`is-running` / `is-error` / `is-interrupted`). */
internal enum class ToolState { Running, Done, Error, Interrupted }

internal fun toolStateOf(block: JsObj): ToolState = when {
    !block.isDone() -> ToolState.Running
    block.isInterrupted() -> ToolState.Interrupted
    block.isErrorBlock() -> ToolState.Error
    else -> ToolState.Done
}

/**
 * `.chat-tool`: `--graphite` under a `--line` border (`--line-strong` running or interrupted,
 * `--danger-edge` on error), `--radius-md`, the lit top edge + raised shadow; Studio: a flat
 * 0.75rem card with no shadow. Children clip to the rounded inner edge.
 */
@Composable
internal fun ToolFrame(state: ToolState, modifier: Modifier = Modifier, nested: Boolean = false, fullWidth: Boolean = false, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val radius = 12.dp
    val shape = RoundedCornerShape(radius)
    val border = t.line
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .maxWidthFraction(if (fullWidth) 1f else cardFraction(nested))
                .fillMaxWidth()
                .cssSurface(
                    shape,
                    background = t.graphite,
                    border = CssBorder(1.dp, border),
                    shadows = emptyList(),
                )
                .padding(1.dp)
                .clip(RoundedCornerShape(radius - 1.dp)),
        ) { content() }
    }
}

/** `.chat-tool-status` text: "running · 12s", "interrupted", "error", "done". */
internal fun toolStatusText(block: JsObj): String = when (toolStateOf(block)) {
    ToolState.Running -> {
        val elapsed = Elapsed.elapsedLabel(block["elapsedSeconds"])
        if (elapsed.isNotEmpty()) "running · $elapsed" else "running"
    }
    ToolState.Interrupted -> "interrupted"
    ToolState.Error -> "error"
    ToolState.Done -> "done"
}

/**
 * `.chat-tool-head`: a `--tint-xs` strip (Studio `--graphite-raised`), at least 2.5rem (Studio
 * 2.75rem) tall, the state glyph, the name in ink 680 and the status pushed right (uppercase
 * 0.62rem/700/0.08em faint, `--danger` on error; Studio: UI font 0.68rem, no transform).
 */
@Composable
internal fun ToolHead(icon: @Composable () -> Unit, name: String?, status: String, errorStatus: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val size = rem(0.75f)
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.graphiteRaised)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.4.dp, vertical = 10.4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        icon()
        if (!name.isNullOrEmpty()) {
            Text(
                codeText(cutLine(name, PATH_MAX)),
                style = TextStyle(fontFamily = type.mono, fontSize = size, fontWeight = FontWeight(680)),
                color = t.ink,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        StatusLabel(status, errorStatus)
    }
}

/** The status words (`margin-left: auto`), uppercase outside Studio; the accessible name keeps the words. */
@Composable
internal fun StatusLabel(status: String, error: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = TextStyle(fontFamily = type.ui, fontSize = rem(0.68f), fontWeight = FontWeight(700))
    Text(
        status,
        style = style,
        color = if (error) t.danger else t.faint,
        modifier = Modifier.semantics { contentDescription = status },
    )
}

@Composable
internal fun HeadIcon(icon: ImageVector, tint: Color, size: Dp = 14.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** The state glyph: spinner (running), stop (interrupted), alert (error), wrench (done). */
@Composable
internal fun ToolStateIcon(state: ToolState, size: Dp = 14.dp) {
    val t = LocalTetherTokens.current
    when (state) {
        ToolState.Running -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = size)
        ToolState.Interrupted -> HeadIcon(TetherIcons.CircleStop, t.muted, size)
        ToolState.Error -> HeadIcon(TetherIcons.TriangleAlert, t.muted, size)
        ToolState.Done -> HeadIcon(TetherIcons.Wrench, t.muted, size)
    }
}

// --- Monospace payloads ------------------------------------------------------------------------

/**
 * A `<pre>`'s text as the browser lays it out: a single newline right at the end of the content
 * starts no new line box (HTML drops it), while a Compose Text would draw an empty last line.
 * Only that one newline goes; "a\n\n" still shows its blank line, as on the web.
 */
internal fun preText(text: String): String = if (text.endsWith("\n")) text.substring(0, text.length - 1) else text

/** `.chat-tool-io` / `.chat-tool-output` text style (mono 0.76rem/1.65; Studio 0.75rem/1.7). */
@Composable
internal fun toolIoStyle(): TextStyle {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    return type.codeBlock.copy(fontSize = rem(0.75f), lineHeight = 1.7.em)
}

/**
 * A `pre.chat-tool-io` in its `.chat-expand` clamp: `--mineral-deep` under a `--line` rule, ink
 * pre-wrap mono, padded `space-sm space-md`; [output] adds the output's inset rule (outside
 * Studio). Long text clamps at [toolClamp] with the expand toggle, never a nested scroller.
 * ta-blf: drawn as code ([SafeText]), LTR; as command output ([SafeText.terminal]) when [terminal].
 */
@Composable
internal fun ToolIoPre(text: String, output: Boolean = false, contentDescription: String? = null, background: Color? = null, terminal: Boolean = false) {
    val t = LocalTetherTokens.current
    val bg = background ?: t.mineralDeep
    val line = t.line
    TetherExpandablePre(
        text = preText(text),
        style = toolIoStyle().copy(textDirection = codeDirection),
        display = safePreDisplay(terminal),
        color = t.ink,
        clamp = toolClamp(),
        contentDescription = contentDescription,
        textModifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(bg)
                drawRect(line, size = Size(size.width, 1.dp.toPx()))
            }
            .padding(top = 1.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
    )
}

// --- ToolInput ------------------------------------------------------------------------------------

/** `ToolInput`: a file edit as its diff(s), anything else as pretty JSON. */
@Composable
internal fun ToolInputView(name: String?, input: com.tether.app.protocol.tree.JsValue?) {
    val model = remember(name, input) { toolInputModel(name, input) }
    when (model) {
        is ToolInputModel.Raw -> {
            model.note?.let { note ->
                val t = LocalTetherTokens.current
                Text(
                    note,
                    style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = rem(0.72f)),
                    color = t.muted,
                    modifier = Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs).testTag("edit-too-large"),
                )
            }
            ToolIoPre(model.text)
        }
        is ToolInputModel.Edit -> FileEditView(model)
    }
}

/**
 * `.chat-tool-edit`: a `--line` rule, the path row (mono 0.76rem ink, broken anywhere) with its
 * tag (0.66rem uppercase faint on `--tint-md`), then one clamped diff per edit; a MultiEdit's
 * second and later diffs get the heavier 2px rule.
 */
@Composable
private fun FileEditView(model: ToolInputModel.Edit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Text(
                codeText(cutLine(model.filePath, PATH_MAX), breakAnywhere = true),
                style = TextStyle(fontFamily = type.mono, fontSize = rem(0.76f)),
                color = t.ink,
                modifier = Modifier.weight(1f, fill = false),
            )
            model.tag?.let { tag ->
                Text(
                    tag.uppercase(Locale.ROOT),
                    style = TextStyle(fontFamily = type.mono, fontSize = rem(0.66f), letterSpacing = 0.04.em),
                    color = t.faint,
                    modifier = Modifier
                        .background(t.tintMd, RoundedCornerShape(t.radiusSm))
                        .padding(horizontal = 6.4.dp, vertical = 0.8.dp)
                        .semantics { contentDescription = tag },
                )
            }
        }
        // The 200-row cap spans the whole card, so a 50-edit MultiEdit cannot draw 10,000 rows
        // (divergence, noted: the web caps each edit's DiffBlock at 200 on its own).
        val capped = remember(model) { capEdits(model.diffs, model.totals) }
        capped.forEachIndexed { index, diff -> EditDiffBlock(diff, heavyRule = index > 0) }
    }
}

/**
 * `word-break: break-all`: a break opportunity between every two characters lets a path wrap
 * anywhere. ta-blf r2: the opportunity is [SafeText]'s zero-width WORD JOINER + ZWSP pair (never a
 * bare U+200B), so a copy of the path never carries it, and a token stays whole.
 */
internal fun String.breakAnywhere(): String = SafeText.breakAnywhere(this)

/**
 * `DiffBlock`: at most [MAX_DIFF_ROWS] rows (then "+N more lines", faint italic), in the clamp.
 * `.diff-line`: a 1.4rem centred gutter (`-`/`+`/space, faint, `--line` right rule) and the
 * pre-wrap text padded `space-sm`; context muted, deletions/additions on the diff tints.
 */
@Composable
internal fun EditDiffBlock(capped: CappedDiff, heavyRule: Boolean = false) {
    val t = LocalTetherTokens.current
    val style = LocalTetherTypography.current.codeBlock.copy(fontSize = rem(0.76f), lineHeight = 1.5.em)
    TetherExpandableBlock(clamp = toolClamp()) {
        Column(
            Modifier
                .fillMaxWidth()
                .topRule(t.line, if (heavyRule) 2.dp else 1.dp)
                .padding(top = if (heavyRule) 2.dp else 1.dp)
                .semantics(mergeDescendants = false) { contentDescription = "File change diff" }
                .testTag("chat-diff"),
        ) {
            for (row in capped.shown) {
                val (bg, ink) = when (row.t) {
                    EditDiffRow.DEL -> t.diffDelBg to t.diffDelInk
                    EditDiffRow.ADD -> t.diffAddBg to t.diffAddInk
                    else -> Color.Transparent to t.muted
                }
                val gutter = when (row.t) {
                    EditDiffRow.DEL -> "-"
                    EditDiffRow.ADD -> "+"
                    else -> " "
                }
                DiffLine(gutter, cutLine(row.text).ifEmpty { " " }, bg, gutterInk = if (row.t == EditDiffRow.CTX) t.faint else ink, ink = ink, style = style)
            }
            if (capped.hidden > 0) {
                DiffLine("…", moreLinesLabel(capped.hidden), Color.Transparent, t.faint, t.faint, style.copy(fontStyle = FontStyle.Italic))
            }
        }
    }
}

@Composable
private fun DiffLine(gutter: String, text: String, bg: Color, gutterInk: Color, ink: Color, style: TextStyle) {
    val t = LocalTetherTokens.current
    val line = t.line
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(bg)) {
        Box(
            Modifier
                .width(22.4.dp)
                .fillMaxHeight()
                .drawBehind { drawRect(line, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height)) }
                .clearAndSetSemantics { },
            contentAlignment = Alignment.TopCenter,
        ) {
            // T6.7: `.diff-gutter { user-select: none }`: a copied diff is the lines, not the +/- marks.
            androidx.compose.foundation.text.selection.DisableSelection { Text(gutter, style = style, color = gutterInk) }
        }
        Text(codeText(text), style = style.copy(textDirection = codeDirection), color = ink, modifier = Modifier.weight(1f).padding(horizontal = t.css.spaceSm))
    }
}

// --- The generic card --------------------------------------------------------------------------

/** A legacy typed block as the tree block the renderers read (previews and callers without a tree). */
internal fun TurnBlock.asTree(): JsObj = JsCodec.fromJson(TetherJson.encodeToJsonElement(TurnBlock.serializer(), this)) as JsObj

/** Public entry for previews: the generic card for a typed block. */
@Composable
fun ToolCard(block: TurnBlock, modifier: Modifier = Modifier) {
    val tree = remember(block) { block.asTree() }
    ToolCard(tree, showThinking = false, modifier = modifier)
}

/**
 * `ToolCard` (chat-view.tsx:360-390): head (state glyph, name, status), the input, a sub-agent
 * thread, then — once done — the interrupted call's evidence, or the output's media and the text
 * beside it. A running tool shows no output here (the web streams output only on the Codex
 * command / MCP cards).
 */
@Composable
internal fun ToolCard(block: JsObj, showThinking: Boolean, modifier: Modifier = Modifier, nested: Boolean = false, fullWidth: Boolean = false) {
    val t = LocalTetherTokens.current
    val state = toolStateOf(block)
    val name = block.toolName()
    val output = block["output"]
    ToolFrame(state, modifier.testTag("tool-card"), nested, fullWidth) {
        ToolHead(
            icon = { ToolStateIcon(state) },
            name = name,
            status = toolStatusText(block),
            errorStatus = state == ToolState.Error,
        )
        val input = block["input"]
        if (!input.isNullish()) ToolInputView(name, input)
        val subagent = block["subagent"] as? JsObj
        val running = state == ToolState.Running
        // The thread's <details> state lives here so a CLOSED thread gives its tile share back
        // to the card's own result (its steps load nothing while closed).
        // ta-cqf: "+N more / earlier steps" pages SUBAGENT_ROWS_MAX more at a time (reset when the run starts or ends).
        var limit by rememberSaveable(asString(block["blockId"]), running) { mutableStateOf(SUBAGENT_ROWS_MAX) }
        val window = remember(subagent, showThinking, running, limit) { subagent?.let { subagentWindow(it, showThinking, newest = running, max = limit) } }
        // The web opens a finished thread when ANY step's result carries media (not only the drawn ones).
        val threadOpen = rememberDetailsOpen(running || window?.hasMedia == true)
        val plan = remember(block, showThinking, threadOpen.value, limit) { cardMediaPlan(if (threadOpen.value) subagent else null, block, showThinking, limit) }
        if (window != null) SubagentThread(window, threadOpen, showThinking = showThinking, tileLimits = plan.byEntry, onMore = { limit += SUBAGENT_ROWS_MAX })
        if (state == ToolState.Interrupted) InterruptedEvidence(output)
        if (block.isDone() && state != ToolState.Interrupted && !output.isNullish()) {
            val media = remember(output) { extractToolMedia(output) }
            val text = remember(output) { remainingToolText(output) }
            ToolMediaRow(media, limit = plan.card)
            if (text.isNotEmpty()) ToolIoPre(text, output = true, contentDescription = null)
        }
    }
}

/**
 * `InterruptedToolEvidence` (issue #184): the CLI's abort text behind a "What the CLI reported"
 * disclosure (faint 0.72rem, padded `space-xs space-md`, under a `--line` rule).
 */
@Composable
internal fun InterruptedEvidence(output: com.tether.app.protocol.tree.JsValue?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val text = remember(output) { remainingToolText(output) }
    if (text.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("What the CLI reported", style = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f)), color = t.faint)
        }
        if (open) ToolIoPre(text, output = true)
    }
}

// --- The sub-agent thread ------------------------------------------------------------------------

/**
 * `SubagentThread` (chat-view.tsx:280-345): the Task child's own steps under its parent card, a
 * `<details>` open while the parent runs or while a nested result carries media. `--tint-xs`
 * under a `--line` rule; the head (bot + "Subagent · N steps", mono 0.76rem muted); the body
 * indented `space-md` behind a 2px `--line-strong` rail.
 */
@Composable
internal fun SubagentThread(window: SubagentWindow, open: DetailsOpen, showThinking: Boolean, tileLimits: Map<String, Int> = emptyMap(), onMore: () -> Unit = {}) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    // R5-M1: at most SUBAGENT_ROWS_MAX steps are drawn (the caller's [window]) — the newest while
    // the parent runs, the first once it is done — so a never-ending Task cannot grow one list
    // item without bound. [open] is the <details> state, held by the card (it decides the tiles).
    val entries = window.entries
    if (entries.isEmpty()) return
    val steps = window.total
    Column(Modifier.fillMaxWidth().background(t.tintXs).topRule(t.line).padding(top = 1.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open.toggle() }
                .semantics(mergeDescendants = true) { stateDescription = if (open.value) "Expanded" else "Collapsed" }
                .heightIn(min = 44.dp)
                .then(if (open.value) Modifier.drawBehind { drawRect(t.line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) } else Modifier)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            HeadIcon(TetherIcons.Bot, t.muted, 13.dp)
            Text("Subagent · $steps step${if (steps == 1) "" else "s"}", style = TextStyle(fontFamily = type.mono, fontSize = rem(0.76f)), color = t.muted)
        }
        if (open.value) {
            val rail = t.lineStrong
            Column(
                Modifier
                    .padding(start = t.css.spaceMd)
                    .fillMaxWidth()
                    .drawBehind { drawRect(rail, size = Size(2.dp.toPx(), size.height)) }
                    .padding(start = 2.dp)
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                if (window.earlier > 0) StepsMore("+${localeCount(window.earlier)} earlier step${if (window.earlier == 1) "" else "s"}", onMore)
                entries.forEach { entry ->
                    when (asString(entry["kind"])) {
                        "message" -> asString(entry["text"])?.takeIf { it.isNotEmpty() }?.let { text ->
                            val blocks = remember(text) { parseMarkdown(text) }
                            MarkdownBody(blocks, LocalTetherTypography.current.chatBody.copy(fontSize = rem(0.82f)), t.ink)
                        }
                        "thinking" -> ThinkingCard(TurnBlock(blockId = asString(entry["key"]) ?: "", kind = "thinking", text = asString(entry["text"])))
                        else -> SubagentToolCard(entry, tileLimits[asString(entry["key"])] ?: 0)
                    }
                }
                if (window.later > 0) StepsMore("+${localeCount(window.later)} more step${if (window.later == 1) "" else "s"}", onMore)
            }
        }
    }
}

/**
 * `.chat-subagent-tool`: a `--mineral-deep` box, `--radius-sm`, `--line` border (`--danger-edge` on
 * error, `--line-strong` interrupted); head padded 0.3rem `space-md`, mono 0.74rem, 12px glyphs.
 */
@Composable
private fun SubagentToolCard(entry: JsObj, tileLimit: Int) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val state = toolStateOf(entry)
    val shape = RoundedCornerShape(t.radiusSm)
    val border = when (state) {
        ToolState.Error -> t.dangerEdge
        ToolState.Interrupted -> t.lineStrong
        else -> t.line
    }
    val output = entry["output"]
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, border))
            .padding(1.dp)
            .clip(RoundedCornerShape(t.radiusSm - 1.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = 4.8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            ToolStateIcon(state, 12.dp)
            asString(entry["name"])?.let {
                Text(codeText(it), style = TextStyle(fontFamily = type.mono, fontSize = rem(0.74f), fontWeight = FontWeight(600)), color = t.ink, modifier = Modifier.weight(1f))
            } ?: Spacer(Modifier.weight(1f))
            StatusLabel(toolStatusText(entry), error = false)
        }
        val input = entry["input"]
        if (!input.isNullish()) ToolInputView(asString(entry["name"]), input)
        if (state == ToolState.Interrupted) InterruptedEvidence(output)
        if (entry.isDone() && state != ToolState.Interrupted && !output.isNullish()) {
            ToolMediaRow(remember(output) { extractToolMedia(output) }, limit = tileLimit)
            val text = remember(output) { remainingToolText(output) }
            if (text.isNotEmpty()) ToolIoPre(text, output = true)
        }
    }
}

/** The steps a thread draws, how many it holds before and after them, and whether ANY step's result carries media. */
internal class SubagentWindow(val entries: List<JsObj>, val total: Int, val earlier: Int, val later: Int, val hasMedia: Boolean = false)

/** Sub-agent steps one thread draws before "+N earlier/more steps" (the web draws them all). */
internal const val SUBAGENT_ROWS_MAX = 50

/**
 * The [subagentEntries] a thread draws: the [max] newest when [newest] (the parent is running),
 * else the first [max]. One pass over the order with no list of every entry kept, so a delta on a
 * 20k-step thread costs a cheap scan and [max] rows, not 20k rows.
 */
internal fun subagentWindow(thread: JsObj, showThinking: Boolean, newest: Boolean, max: Int = SUBAGENT_ROWS_MAX): SubagentWindow {
    val order = thread["order"] as? com.tether.app.protocol.tree.JsArr ?: com.tether.app.protocol.tree.JsArr.EMPTY
    val byKey = thread["entries"] as? JsObj ?: JsObj.EMPTY
    val kept = ArrayDeque<JsObj>(minOf(max, 64))
    var total = 0
    var hasMedia = false
    for (k in order) {
        val key = (k as? JsStr)?.value ?: continue
        val e = byKey[key] as? JsObj ?: continue
        if (asString(e["kind"]) == "thinking" && !(showThinking && !asString(e["text"]).isNullOrEmpty())) continue
        total++
        if (!hasMedia && asString(e["kind"]) == "tool" && extractToolMedia(e["output"]).isNotEmpty()) hasMedia = true
        if (newest) {
            kept.addLast(e)
            if (kept.size > max) kept.removeFirst()
        } else if (kept.size < max) {
            kept.addLast(e)
        }
    }
    val drawn = kept.toList()
    return if (newest) SubagentWindow(drawn, total, total - drawn.size, 0, hasMedia) else SubagentWindow(drawn, total, 0, total - drawn.size, hasMedia)
}

/** "+N more steps": a 44dp key that draws the next [SUBAGENT_ROWS_MAX] (ta-cqf). */
@Composable
private fun StepsMore(text: String, onMore: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "Show $SUBAGENT_ROWS_MAX more steps", onClick = onMore)
            .testTag("steps-more"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f)), color = t.faint)
    }
}

/** The thread's shown entries, in order (a thinking entry only when shown and non-empty). */
internal fun subagentEntries(thread: JsObj, showThinking: Boolean): List<JsObj> {
    val order = (thread["order"] as? com.tether.app.protocol.tree.JsArr)?.mapNotNull { (it as? JsStr)?.value } ?: emptyList()
    val byKey = thread["entries"] as? JsObj ?: JsObj.EMPTY
    return order.mapNotNull { byKey[it] as? JsObj }.filter { e ->
        asString(e["kind"]) != "thinking" || (showThinking && !asString(e["text"]).isNullOrEmpty())
    }
}

/** How many tiles each part of one tool card may draw: per sub-agent entry key, and the card's own result. */
internal class CardMediaPlan(val byEntry: Map<String, Int>, val card: Int)

/**
 * R3-M1: one [MediaLimits.MAX_TILES] budget per card, in screen order — the sub-agent entries
 * (which render first), then the card's own result.
 */
internal fun cardMediaPlan(subagent: JsObj?, block: JsObj, showThinking: Boolean, max: Int = SUBAGENT_ROWS_MAX): CardMediaPlan {
    // Over the DRAWN steps only (R5-M1): a step past the window loads nothing.
    val entries = subagent?.let { subagentWindow(it, showThinking, newest = !block.isDone(), max = max).entries } ?: emptyList()
    fun shows(o: JsObj) = o.isDone() && !o.isInterrupted() && !o["output"].isNullish()
    val keyed = entries.filter { asString(it["kind"]) != "message" && asString(it["kind"]) != "thinking" && shows(it) }
        .map { (asString(it["key"]) ?: "") to extractToolMedia(it["output"]).size }
    val cardCount = if (shows(block)) extractToolMedia(block["output"]).size else 0
    val limits = tileBudget(keyed.map { it.second } + cardCount)
    return CardMediaPlan(keyed.mapIndexed { i, (key, _) -> key to limits[i] }.toMap(), limits.last())
}

/**
 * A `<details open={default}>` as React drives it: the reader's toggle holds until [default]
 * itself changes, which resets the element to it (e.g. a running group closes when it finishes).
 */
internal class DetailsOpen(private val state: androidx.compose.runtime.MutableState<Boolean>) {
    val value: Boolean get() = state.value
    fun toggle() {
        state.value = !state.value
    }
}

@Composable
internal fun rememberDetailsOpen(default: Boolean): DetailsOpen {
    val state = rememberSaveable(default) { mutableStateOf(default) }
    return remember(state) { DetailsOpen(state) }
}

// --- The activity group summary --------------------------------------------------------------------

/**
 * `.chat-activity-summary` (globals.css:5242-5280): the collapsed "2 shell commands, 1 file read"
 * line of a run of tool calls — chevron (turns 90° open), then spinner (running) / alert (errors)
 * / wrench, then the summary in mono 0.78rem: muted, ink while running, `--danger` with errors.
 * At least 44dp tall (a touch target; the web's row is its text height).
 */
@Composable
internal fun ToolActivityHeader(summary: String, running: Boolean, hasErrors: Boolean, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val rotation by animateFloatAsState(if (open) 90f else 0f, if (reduced) snap() else tween(120), label = "activityChevron")
    val ink = when {
        hasErrors -> t.danger
        running -> t.ink
        else -> t.muted
    }
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .maxWidthFraction(cardFraction())
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.radiusMd))
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = summary
                    stateDescription = if (open) "Expanded" else "Collapsed"
                }
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs)
                .testTag("tool-activity-group"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp).rotate(rotation))
            when {
                running -> SpinningIcon(TetherIcons.Loader, tint = ink, size = 13.dp)
                hasErrors -> HeadIcon(TetherIcons.TriangleAlert, ink, 13.dp)
                else -> HeadIcon(TetherIcons.Wrench, ink, 13.dp)
            }
            Text(summary, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.78f)), color = ink, modifier = Modifier.weight(1f))
        }
    }
}
