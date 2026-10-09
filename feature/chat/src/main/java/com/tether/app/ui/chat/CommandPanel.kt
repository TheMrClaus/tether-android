package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.LabelText
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.components.widthMaxContent
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendStyled
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import kotlin.math.floor

/*
 * T7.3: the v54 `!` command panel (tether components/chat-view.tsx:608-650; globals.css 6800-6851):
 * a foreground command's `command_output` block, folded from command_output_started / _delta /
 * _completed — a terminal-style view that streams live and shows the exit status, plus the log file
 * the agent was given.
 */

internal const val COMMAND_OUTPUT_BLOCK = "command_output"
internal const val COMMAND_PANEL_TAG = "command-panel"

/** How much of the output the transcript panel draws (its tail); the full stream is in the log file. */
internal const val COMMAND_PANEL_MAX_CHARS = 16_000

/** One `command_output` block, read off the projection tree. */
@Immutable
internal data class CommandOutputView(
    val command: String,
    val logFile: String?,
    val segments: JsArr,
    val done: Boolean,
    val exitCode: Int?,
    val signal: String?,
    val timedOut: Boolean,
    val killed: Boolean,
    val outputTruncated: Boolean,
) {
    val running: Boolean get() = !done

    /** chat-view.tsx:613-621: the outcome always in words. */
    val status: String get() = when {
        running -> "running…"
        killed -> "stopped"
        timedOut -> "timed out"
        signal != null -> "signal ${LabelText.label(signal).ifEmpty { LabelText.visibleValue(signal) }}"
        else -> "exit ${exitCode ?: 0}"
    }

    /** chat-view.tsx:622. */
    val failed: Boolean get() = !running && !killed && (timedOut || signal != null || (exitCode ?: 0) != 0)
}

internal fun commandOutputView(block: JsObj?): CommandOutputView? {
    if (block == null || (block["kind"] as? JsStr)?.value != COMMAND_OUTPUT_BLOCK) return null
    val exit = (block["exitCode"] as? JsNum)?.value?.takeIf { it.isFinite() && it == floor(it) }?.toInt()
    return CommandOutputView(
        command = (block["command"] as? JsStr)?.value ?: "",
        logFile = (block["logFile"] as? JsStr)?.value?.takeIf { it.isNotEmpty() },
        segments = block["segments"] as? JsArr ?: JsArr.EMPTY,
        done = (block["done"] as? JsBool)?.value == true,
        exitCode = exit,
        signal = (block["signal"] as? JsStr)?.value,
        timedOut = (block["timedOut"] as? JsBool)?.value == true,
        killed = (block["killed"] as? JsBool)?.value == true,
        outputTruncated = (block["outputTruncated"] as? JsBool)?.value == true,
    )
}

/**
 * The panel's body: the drawn tail of the output and how much was left out. r3: drawn by the shared
 * terminal rule ([SafeText.terminal], ta-blf): SGR colour is dropped, every other escape and every
 * control, bidi or invisible code point is a visible token. Nothing is hidden: the operator sees
 * what the agent sees. Each segment's [OutputSegment.text] is the DRAWN text (tokens included).
 */
internal class CommandPanelText(val segments: List<OutputSegment>, val dropped: Int)

internal fun commandPanelText(segments: JsArr, max: Int = COMMAND_PANEL_MAX_CHARS): CommandPanelText {
    // r2: only a RAW tail (twice the bound) is encoded, so a redraw costs the bound, not everything
    // the command printed. r3: the drawn tail is then cut at a unit boundary, never inside a token.
    val raw = commandPanelRawTail(segments, max)
    panelCharsCleaned.addAndGet(raw.segments.sumOf { it.text.length }.toLong())
    val drawn = raw.segments.map { OutputSegment(it.stderr, SafeText.terminal(it.text)) }.filter { it.text.isNotEmpty() }
    var budget = max
    val kept = ArrayDeque<OutputSegment>()
    var dropped = raw.dropped
    for (i in drawn.indices.reversed()) {
        val seg = drawn[i]
        if (budget <= 0) {
            dropped += seg.text.length
            continue
        }
        if (seg.text.length <= budget) {
            kept.addFirst(seg)
            budget -= seg.text.length
        } else {
            val cut = unitBoundaryAtOrAfter(seg.text, seg.text.length - budget)
            if (cut < seg.text.length) kept.addFirst(OutputSegment(seg.stderr, seg.text.substring(cut)))
            dropped += cut
            budget = 0
        }
    }
    return CommandPanelText(kept.toList(), dropped)
}

/** The first index at or after [from] where a drawn unit starts: never inside a token or a surrogate pair. */
internal fun unitBoundaryAtOrAfter(display: String, from: Int): Int {
    var i = 0
    while (i < display.length) {
        val u = if (display[i] == SafeText.MARK) SafeText.unitAt(display, i) else null
        val end = when {
            u != null -> u.end
            Character.isHighSurrogate(display[i]) && i + 1 < display.length && Character.isLowSurrogate(display[i + 1]) -> i + 2
            else -> i + 1
        }
        if (i >= from) return i
        i = end
    }
    return display.length
}

/** Test seam (r2): how many raw characters [commandPanelText] has handed to the drawing rule. */
internal val panelCharsCleaned = java.util.concurrent.atomic.AtomicLong()

/** The raw tail [commandPanelText] cleans: at most `2 × max` characters of the newest output. */
internal fun commandPanelRawTail(segments: JsArr, max: Int = COMMAND_PANEL_MAX_CHARS): OutputTail =
    outputTail(outputSegments(segments), 2 * max)

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * `.chat-command-panel` (as wide as its content, at most the row): `1px --line`, a 2px left edge (`--muted`; `--violet-strong` running,
 * `--danger` failed), `--radius-md`, `--mineral-deep`. The head (Terminal, the command in the mono
 * face, the status — spinner + words, `--danger` when failed), the output (`<pre>`, 0.78rem mono,
 * stderr in `--warning`, at most 40% of the window tall and scrolling inside, following the tail while
 * it streams, the violet caret while it runs), and the foot naming the log file. Output and names are
 * the server's words drawn by the shared terminal rule: nothing in them can reorder, hide, or pass
 * for the app's own text, and nothing is hidden from the operator. The row is selectable; a copy
 * goes through the transcript's SafeCopyClipboard (the exact output, the drawn tokens decoded).
 */
@Composable
internal fun CommandOutputPanel(view: CommandOutputView) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    val edge = when {
        view.running -> t.violetStrong
        view.failed -> t.danger
        else -> t.muted
    }
    val label = commandLabel(view.command)
    val line = t.line
    val text = remember(view.segments) { commandPanelText(view.segments) }
    val warning = t.warning
    val token = tokenStyle(t)
    val body = remember(text, warning, token) {
        val all = buildAnnotatedString {
            // Each drawn segment with its tokens styled (ta-blf's token look); stderr in --warning.
            for (seg in text.segments) if (seg.stderr) withStyle(SpanStyle(color = warning)) { appendStyled(seg.text, token) } else appendStyled(seg.text, token)
        }
        // A `<pre>` draws no empty line for the final newline; the caret sits on the line after.
        if (all.text.endsWith('\n')) all.subSequence(0, all.length - 1) else all
    }
    val maxBody = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    Box(Modifier.fillMaxWidth().padding(vertical = t.css.spaceXs).testTag(COMMAND_PANEL_TAG)) {
        // `.chat-row` is a flex row and the panel has no width of its own: it wraps its content, up
        // to the row's width (a long line wraps inside it).
        Column(
            Modifier
                .widthMaxContent()
                .clip(shape)
                .background(t.mineralDeep)
                .border(1.dp, t.line, shape)
                .drawBehind { drawRect(edge, size = Size(2.dp.toPx(), size.height)) },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
                    .padding(start = t.css.spaceSm + 1.dp, end = t.css.spaceSm, top = t.css.spaceXs, bottom = t.css.spaceXs)
                    .semantics(mergeDescendants = true) { contentDescription = "Command $label, ${view.status}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
                Text(label, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f)), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (view.running) SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 12.dp)
                    Text(view.status, style = TextStyle(fontFamily = type.ui, fontSize = rem(0.72f)), color = if (view.failed) t.danger else t.muted, maxLines = 1)
                }
            }
            val scroll = rememberScrollState()
            // Follow the tail while it streams, so a live command reads like a terminal.
            LaunchedEffect(body, view.running) { if (view.running) scroll.scrollTo(scroll.maxValue) }
            // Code surfaces lay out LTR, as the web's `<pre>` (ta-blf codeDirection).
            val mono = TextStyle(fontFamily = type.mono, fontSize = rem(0.78f), lineHeight = 1.45.em, textDirection = codeDirection)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBody)
                    .verticalScroll(scroll)
                    .padding(start = t.css.spaceSm + 1.dp, end = t.css.spaceSm, top = t.css.spaceSm, bottom = t.css.spaceSm)
                    .testTag("command-panel-body"),
            ) {
                if (text.dropped > 0) {
                    Text("… (earlier output not shown here — the full output is in the log file)", style = mono, color = t.faint)
                }
                if (text.segments.isEmpty() && view.running) {
                    Text("waiting for output…", style = mono, color = t.muted)
                } else if (body.isNotEmpty()) {
                    Text(body, style = mono, color = t.ink, modifier = Modifier.fillMaxWidth())
                }
                if (view.running) Caret()
            }
            view.logFile?.let { path ->
                val pathText = com.tether.app.ui.text.codeText(path)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawRect(line, size = Size(size.width, 1.dp.toPx())) }
                        .padding(start = t.css.spaceSm + 1.dp, end = t.css.spaceSm, top = t.css.spaceXs, bottom = t.css.spaceXs),
                ) {
                    Text(
                        buildAnnotatedString {
                            append(if (view.outputTruncated) "Output truncated above — full log: " else "Full output: ")
                            withStyle(SpanStyle(fontFamily = type.mono)) { append(pathText) }
                        },
                        style = TextStyle(fontFamily = type.ui, fontSize = rem(0.68f)),
                        color = t.muted,
                    )
                }
            }
        }
    }
}

/** `.chat-caret`: a 0.5rem × 1rem violet block, blinking (still under reduced motion). */
@Composable
private fun Caret() {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val visible = if (reduced) {
        true
    } else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
        val phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)),
            label = "caret-phase",
        )
        phase.value < 0.5f
    }
    Box(
        Modifier
            .padding(start = 2.dp, top = 2.dp)
            .width(8.dp)
            .height(16.dp)
            .background(if (visible) t.violet else androidx.compose.ui.graphics.Color.Transparent)
            .clearAndSetSemantics { },
    )
}
