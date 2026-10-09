package com.tether.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeText
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * ta-a5jl: the compact activity row. Every tool call and (where it shows) every thinking block is ONE line in the
 * transcript: the kind glyph, a verb, the one-line argument and, unless the call is done, its state; a tap opens the
 * detail sheet (ActivitySheet.kt) that holds everything the expanded card showed. The approval, question, denial,
 * plan / diff / review cards, the `!` command panel and the group header are not rows and stay as they were.
 */

/** What a row asks of the transcript's sheet host: open [key]; [returnKey] is the row a closed sheet hands focus back to. */
@Stable
internal class ActivityOpener(val onOpen: (String) -> Unit) {
    var returnKey: String? by mutableStateOf(null)
}

/** The host's [ActivityOpener]; null (previews) = a tap does nothing. */
internal val LocalActivityOpener = staticCompositionLocalOf<ActivityOpener?> { null }

/** The row's kind glyph as the design system's vector. */
internal fun ActivityGlyph.icon(): ImageVector = when (this) {
    ActivityGlyph.SquareTerminal -> TetherIcons.SquareTerminal
    ActivityGlyph.Eye -> TetherIcons.Eye
    ActivityGlyph.Pencil -> TetherIcons.Pencil
    ActivityGlyph.FilePlus -> TetherIcons.FilePlus
    ActivityGlyph.FileDiff -> TetherIcons.FileDiff
    ActivityGlyph.Search -> TetherIcons.Search
    ActivityGlyph.FolderOpen -> TetherIcons.FolderOpen
    ActivityGlyph.Network -> TetherIcons.Network
    ActivityGlyph.Bot -> TetherIcons.Bot
    ActivityGlyph.ListTodo -> TetherIcons.ListTodo
    ActivityGlyph.Server -> TetherIcons.Server
    ActivityGlyph.Boxes -> TetherIcons.Boxes
    ActivityGlyph.Wrench -> TetherIcons.Wrench
    ActivityGlyph.Brain -> TetherIcons.Brain
}

/** The sheet's host key of a transcript Block item: `<turnId>/<blockId>`. */
internal val ChatItem.Block.activityKey: String get() = activityKey(turnId, block.blockId)

/** True for the rows that become an activity row: a tool block, or a thinking block (shown only where it shows today). */
internal val ChatItem.isActivityRow: Boolean
    get() = this is ChatItem.Block && (block.kind == com.tether.app.protocol.model.Vocab.BLOCK_TOOL || block.kind == com.tether.app.protocol.model.Vocab.BLOCK_THINKING)

/** A tool block as a row, and (a done tool whose output carries pictures) its tiles right under it, as the web draws them. */
@Composable
internal fun ActivityToolRow(raw: JsObj, key: String, nested: Boolean, showThinking: Boolean) {
    val model = remember(raw) { activityRowModel(raw) }
    Column {
        ActivityRow(model, key, nested)
        val output = raw["output"]
        if (raw.isDone() && toolStateOf(raw) != ToolState.Interrupted && !output.isNullish()) {
            val media = remember(output) { extractToolMedia(output) }
            if (media.isNotEmpty()) {
                val plan = remember(raw, showThinking) { cardMediaPlan(null, raw, showThinking) }
                Box(Modifier.fillMaxWidth().padding(start = ActivityVerbInset + if (nested) ActivityNestInset else 0.dp, end = 12.dp, top = 4.dp)) {
                    ToolMediaRow(media, limit = plan.card)
                }
            }
        }
    }
}

/** A thinking block as a row. */
@Composable
internal fun ActivityThinkingRow(block: TurnBlock, key: String) {
    val model = remember(block.done) { thinkingRowModel(block) }
    ActivityRow(model, key, nested = false)
}

private val ActivityGap = 8.dp
private val ActivityGlyphSize = 13.dp
private val ActivityNestInset = 21.dp

/** Where the verb starts: the row's 12 dp inset, the 13 dp glyph and the 8 dp gap. */
private val ActivityVerbInset = 12.dp + ActivityGlyphSize + ActivityGap
private val ActivityArgMin = 48.dp

/**
 * One row: [kind glyph 13] 8 [verb] 8 [argument, one line, end ellipsis] 8 [state cluster]. At least 44 dp tall and the
 * full width of its lane (one touch target), padded 12 / 4; the items wrap when the line is full (a larger font, a narrow
 * window), the continuation starting under the verb. A pressed row is `--tint-sm`; keyboard focus is the sheet rows'
 * inset violet ring. It is one accessible node: "<verb> <arg>, <state>", a button, "Show details".
 */
@Composable
internal fun ActivityRow(model: ActivityRowModel, key: String, nested: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val opener = LocalActivityOpener.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    // A closed sheet hands focus back to the row that opened it.
    val returns = opener?.returnKey == key
    LaunchedEffect(returns) { if (returns) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(t.radiusMd)
    val verbStyle = TextStyle(fontFamily = type.ui, fontSize = 13.12.sp, fontWeight = FontWeight(600), lineHeight = 1.45.em)
    val argStyle = TextStyle(fontFamily = type.mono, fontSize = 12.48.sp, fontWeight = FontWeight.Normal, textDirection = codeDirection)
    val ink = if (model.state == ToolState.Error) t.danger else t.muted
    val label = model.label
    Box(modifier.fillMaxWidth()) {
        Layout(
            content = {
                Icon(model.glyph.icon(), contentDescription = null, tint = t.muted, modifier = Modifier.layoutId("glyph").size(ActivityGlyphSize).testTag("activity-row-glyph"))
                Text(model.verb, style = verbStyle, color = t.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.layoutId("verb").testTag("activity-row-verb"))
                if (model.arg.isNotEmpty()) {
                    Text(codeText(model.arg), style = argStyle, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.layoutId("arg").testTag("activity-row-arg"))
                }
                if (model.state != ToolState.Done) {
                    Row(Modifier.layoutId("state").testTag("activity-row-cluster"), verticalAlignment = Alignment.CenterVertically) {
                        ActivityStateGlyph(model.state, ink)
                        Text(
                            model.words,
                            style = TextStyle(fontFamily = type.ui, fontSize = 10.88.sp, fontWeight = FontWeight(700)),
                            color = ink,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 4.dp).testTag("activity-row-status"),
                        )
                    }
                }
            },
            modifier = Modifier
                .maxWidthFraction(cardFraction(nested))
                .fillMaxWidth()
                .testTag("activity-row")
                .focusRequester(focus)
                .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Show details") { opener?.onOpen?.invoke(key) }
                .clearAndSetSemantics { contentDescription = label }
                .heightIn(min = 44.dp)
                .cssSurface(
                    shape,
                    if (pressed) t.tintSm else Color.Transparent,
                    shadows = if (focused) listOf(CssShadow(true, 0.dp, 0.dp, 0.dp, 1.dp, t.violetStrong)) else emptyList(),
                )
                .padding(start = 12.dp + if (nested) ActivityNestInset else 0.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            measurePolicy = activityRowPolicy,
        )
    }
}

/** The state glyph: the spinner while running (static under reduced motion), the alert on error, the stop on interrupted. */
@Composable
private fun ActivityStateGlyph(state: ToolState, tint: Color) {
    when (state) {
        ToolState.Running -> SpinningIcon(TetherIcons.Loader, tint = tint, size = ActivityGlyphSize, modifier = Modifier.testTag("activity-state-running"))
        ToolState.Error -> Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = tint, modifier = Modifier.size(ActivityGlyphSize).testTag("activity-state-error"))
        ToolState.Interrupted -> Icon(TetherIcons.CircleStop, contentDescription = null, tint = tint, modifier = Modifier.size(ActivityGlyphSize).testTag("activity-state-interrupted"))
        ToolState.Done -> Unit
    }
}

/**
 * The row's flex-wrap: glyph and verb are never shrunk (they never wrap); the argument never gets under 48 dp and takes
 * the rest of its line; the state cluster is at its natural width. A break goes before the first item that cannot meet its
 * minimum, and a continuation line starts at the verb's x. The block of lines is centred in the row, every item centred
 * on its line (a single line sits in the middle of the 44 dp row, the kind glyph on line 1).
 */
private val activityRowPolicy = androidx.compose.ui.layout.MeasurePolicy { measurables, constraints ->
    val gap = ActivityGap.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val argMin = ActivityArgMin.roundToPx()
    val glyph = measurables.first { it.layoutId == "glyph" }.measure(Constraints())
    val indent = glyph.width + gap
    val verb = measurables.first { it.layoutId == "verb" }.measure(Constraints(maxWidth = (width - indent).coerceAtLeast(0)))
    val argM = measurables.firstOrNull { it.layoutId == "arg" }
    val stateM = measurables.firstOrNull { it.layoutId == "state" }
    val state = stateM?.measure(Constraints(maxWidth = (width - indent).coerceAtLeast(0)))

    class Item(val placeable: Placeable, var x: Int, val line: Int)
    val items = ArrayList<Item>()
    items.add(Item(glyph, 0, 0))
    items.add(Item(verb, indent, 0))
    var line = 0
    var x = indent + verb.width
    var argPlaceable: Placeable? = null
    var statePlaceable: Item? = null
    if (argM != null) {
        if (x + gap + argMin > width) {
            line++
            x = indent - gap
        }
        val argX = x + gap
        val natural = argM.maxIntrinsicWidth(Constraints.Infinity)
        val clusterFits = state != null && argX + argMin + gap + state.width <= width
        val room = (width - argX - if (clusterFits) gap + state!!.width else 0).coerceAtLeast(0)
        argPlaceable = argM.measure(Constraints.fixedWidth(minOf(natural, room).coerceAtLeast(0)))
        items.add(Item(argPlaceable, argX, line))
        if (state != null) {
            if (clusterFits) {
                statePlaceable = Item(state, width - state.width, line)
            } else {
                line++
                statePlaceable = Item(state, indent, line)
            }
        }
    } else if (state != null) {
        statePlaceable = if (x + gap + state.width <= width) {
            Item(state, width - state.width, line)
        } else {
            line++
            Item(state, indent, line)
        }
    }
    statePlaceable?.let { items.add(it) }

    val lineHeights = IntArray(line + 1)
    items.forEach { lineHeights[it.line] = maxOf(lineHeights[it.line], it.placeable.height) }
    val content = lineHeights.sum()
    val height = maxOf(constraints.minHeight, content).coerceAtMost(constraints.maxHeight)
    layout(width, height) {
        val top = (height - content) / 2
        val lineTop = IntArray(lineHeights.size)
        var acc = top
        lineHeights.forEachIndexed { i, h ->
            lineTop[i] = acc
            acc += h
        }
        items.forEach { item ->
            item.placeable.placeRelative(item.x, lineTop[item.line] + (lineHeights[item.line] - item.placeable.height) / 2)
        }
    }
}
