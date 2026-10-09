package com.tether.app.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.crash.CrashRecord
import com.tether.app.crash.ProcessExit
import com.tether.app.ui.components.ExpandToggleRow
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.text.SafeCopyClipboard
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.codeText
import com.tether.app.ui.text.proseText
import com.tether.app.ui.text.putOnClipboard
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.tabularNums
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

/*
 * ta-otgf: the "Last crash" and "Recent exits" section at the head of the Health & Event Log
 * dialog's body. An owner-directed addition with no web counterpart (the browser has its own
 * devtools); it only adds, and gates, confirms and restricts nothing. Drawn from the dialog's own
 * vocabulary (the LogControls head, MetaLine, LogRow, the in-module Pre surface, the shared
 * ExpandToggleRow); the spec is w30/D-spec.md.
 *
 * The stack is NEVER one Text: a record can hold 64 KiB, and one Text that tall (at 360 dp and 2.0x
 * font about 280k px) breaks the 262,143 px Constraints ceiling, which would kill the process each
 * time the dialog opened. It is lazy items of at most 100 lines and 2,048 units
 * ([CrashReadings.chunks]), wrapped, with no intrinsic-width query anywhere in the section.
 */

/** Copy's feedback: the key reads "Copied" or "Failed" for [FEEDBACK_MS], then "Copy" again. */
@Stable
internal class CrashCopyState {
    var result by mutableStateOf<Boolean?>(null)
    var serial by mutableIntStateOf(0)

    fun done(ok: Boolean) {
        result = ok
        serial++
    }

    companion object {
        /** The app's copy-feedback length (MainShell's CopiedFeedbackMs). */
        const val FEEDBACK_MS = 1_500L
    }
}

internal fun LazyListScope.crashSection(
    crash: CrashRecord?,
    exits: List<ProcessExit>,
    narrow: Boolean,
    locale: Locale,
    zone: ZoneId,
    gap: Dp,
    firstModifier: Modifier,
    stackOpen: Boolean,
    onToggleStack: () -> Unit,
    copy: CrashCopyState,
    onClear: () -> Unit,
) {
    val copyText = { CrashReadings.copyText(crash, exits, zone) }
    if (crash != null) {
        item(key = "crash-head") {
            SectionHead(
                title = "Last crash",
                icon = TetherIcons.CircleAlert,
                danger = true,
                tag = LogDialogTags.Crash,
                modifier = firstModifier,
            ) {
                CopyKey("Copy crash record", copyText, copy)
                ClearKey(onClear)
            }
        }
        item(key = "crash-meta") {
            val lines = listOf(
                MetaItem("When", null, CrashReadings.crashTime(crash.timeMs, locale, zone)),
                MetaItem("App version", null, CrashReadings.version(crash), codeDirection),
                MetaItem("Android", null, CrashReadings.android(crash), codeDirection),
                MetaItem("Thread", null, crash.thread, codeDirection),
            )
            MetaRows(lines, narrow, Modifier.padding(top = LocalTetherTokens.current.css.spaceMd).testTag(LogDialogTags.CrashMeta))
        }
        item(key = "crash-exception") {
            val t = LocalTetherTokens.current
            val type = LocalTetherTypography.current
            Text(
                codeLabel(CrashReadings.summary(crash.exceptionClass, crash.message)),
                color = t.white,
                style = cssText(type.ui, 0.8125f, 600),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = t.css.spaceLg).testTag(LogDialogTags.CrashException),
            )
        }
        val chunks = CrashReadings.chunks(crash.stack)
        if (chunks.isNotEmpty()) {
            val peek = CrashReadings.peek(chunks)
            val shown = if (stackOpen) chunks.map { it.text } else listOf(peek.text)
            shown.forEachIndexed { i, text ->
                item(key = "crash-stack-$i") {
                    StackChunk(
                        index = i,
                        text = text,
                        first = i == 0,
                        last = i == shown.lastIndex,
                        toggle = peek.toggle,
                    )
                }
            }
            if (peek.toggle) {
                item(key = "crash-toggle") { StackToggle(stackOpen, peek.hidden, onToggleStack) }
            }
        }
    }
    if (exits.isNotEmpty()) {
        item(key = "exits-head") {
            SectionHead(
                title = "Recent exits",
                icon = TetherIcons.History,
                danger = false,
                tag = LogDialogTags.Exits,
                modifier = if (crash != null) Modifier.padding(top = gap) else firstModifier,
            ) {
                if (crash == null) CopyKey("Copy recent exits", copyText, copy)
            }
        }
        items(exits.size, key = { "exit-$it" }) { index ->
            ExitRow(
                exit = exits[index],
                first = index == 0,
                last = index == exits.lastIndex,
                narrow = narrow,
                locale = locale,
                zone = zone,
            )
        }
    }
    item(key = "crash-end") {
        val t = LocalTetherTokens.current
        // The same 24dp of air and 1dp rule that close the stat tiles.
        Box(Modifier.fillMaxWidth().height(25.dp).drawBottomRule(t.line).testTag(LogDialogTags.CrashEnd))
    }
}

/** The heading with its icon, and the keys (if any) at the end: LogControls' wrapping line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionHead(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean,
    tag: String,
    modifier: Modifier,
    keys: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(
        modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (danger) t.danger else t.muted, modifier = Modifier.size(16.dp))
            Text(
                title,
                color = t.white,
                style = cssText(type.ui, 0.8125f, 700),
                modifier = Modifier.semantics { heading() },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) { keys() }
    }
}

/** Copy: the whole record as plain text. The key says "Copied" (check) or "Failed" (warning), then goes back after 1.5 s. */
@Composable
private fun CopyKey(idleName: String, text: () -> String, state: CrashCopyState) {
    val context = LocalContext.current
    val result = state.result
    LaunchedEffect(state.serial) {
        if (state.result != null) {
            delay(CrashCopyState.FEEDBACK_MS)
            state.result = null
        }
    }
    TetherKey(
        onClick = { state.done(putOnClipboard(context, text(), "Crash record")) },
        classes = KeyClasses.ButtonSecondary,
        label = when (result) {
            true -> "Copied"
            false -> "Failed"
            null -> "Copy"
        },
        icon = when (result) {
            true -> TetherIcons.Check
            false -> TetherIcons.TriangleAlert
            null -> TetherIcons.Copy
        },
        iconSize = 13.dp,
        contentDescription = when (result) {
            true -> "Copied"
            false -> "Copy failed"
            null -> idleName
        },
        modifier = Modifier.testTag(LogDialogTags.CrashCopy).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Clear: deletes the record, no confirmation (nothing here is a session or a server). */
@Composable
private fun ClearKey(onClear: () -> Unit) {
    TetherKey(
        onClick = onClear,
        classes = KeyClasses.ButtonSecondary,
        label = "Clear",
        icon = TetherIcons.Trash2,
        iconSize = 13.dp,
        contentDescription = "Clear crash record",
        modifier = Modifier.testTag(LogDialogTags.CrashClear),
    )
}

/**
 * One lazy item's share of the stack block: the block's fill and border are drawn per item
 * ([blockEdges]), the words wrap (an unbounded width over a 64 KiB line would break the same
 * Constraints ceiling), and a selection stays inside its chunk (Copy is the whole-record path).
 */
@Composable
private fun StackChunk(index: Int, text: String, first: Boolean, last: Boolean, toggle: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val base = LocalClipboard.current
    val clipboard = remember(base) { SafeCopyClipboard(base, null) }
    val inner = PaddingValues(
        start = t.css.spaceMd + 1.dp,
        end = t.css.spaceMd + 1.dp,
        top = if (first) t.css.spaceMd + 1.dp else 0.dp,
        bottom = when {
            !last -> 0.dp
            toggle -> t.css.spaceMd
            else -> t.css.spaceMd + 1.dp
        },
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) t.css.spaceMd else 0.dp)
            .blockEdges(t, top = first, bottom = last && !toggle)
            .padding(inner),
    ) {
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            SelectionContainer {
                Text(
                    codeText(text.replace("\t", "  ")),
                    color = t.ink,
                    style = cssText(type.mono, 0.75f, 400, lineHeight = 1.5f).copy(textDirection = codeDirection),
                    modifier = Modifier.fillMaxWidth().testTag(LogDialogTags.crashStack(index)),
                )
            }
        }
    }
}

@Composable
private fun StackToggle(open: Boolean, hidden: Int?, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val corner = t.radiusSm - 1.dp
    Box(
        Modifier
            .fillMaxWidth()
            .blockEdges(t, top = false, bottom = true)
            .padding(start = 1.dp, end = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(bottomStart = corner, bottomEnd = corner))
            .testTag(LogDialogTags.CrashToggle),
    ) {
        ExpandToggleRow(open, hidden, onClick)
    }
}

/**
 * The block's `--mineral-deep` fill and 1dp `--line` border (the in-module Pre's surface), one
 * item's share of it: corners round only at the block's ends, and an edge that joins the next item
 * is drawn one radius past the item and clipped, so the joins are square and seamless.
 */
internal fun Modifier.blockEdges(t: TetherTokens, top: Boolean, bottom: Boolean): Modifier = drawBehind {
    val radius = t.radiusSm.toPx()
    val px = 1.dp.toPx()
    val above = if (top) 0f else radius
    val below = if (bottom) 0f else radius
    clipRect {
        drawRoundRect(t.mineralDeep, Offset(0f, -above), Size(size.width, size.height + above + below), CornerRadius(radius))
        val half = px / 2f
        drawRoundRect(
            t.line,
            Offset(half, -above + half),
            Size(size.width - px, size.height + above + below - px),
            CornerRadius((radius - half).coerceAtLeast(0f)),
            style = Stroke(px),
        )
    }
}

/** One exit: the LogRow grammar without the level column (the reason is the label). */
@Composable
private fun ExitRow(exit: ProcessExit, first: Boolean, last: Boolean, narrow: Boolean, locale: Locale, zone: ZoneId) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val time = CrashReadings.crashTime(exit.timeMs, locale, zone)
    val label = CrashReadings.reasonLabel(exit.reason)
    val detail = CrashReadings.exitDetail(exit)
    val tint = when (CrashReadings.reasonTone(exit.reason)) {
        ExitTone.Warn -> t.warning.copy(alpha = t.warning.alpha * 0.08f)
        ExitTone.Error -> t.danger.copy(alpha = t.danger.alpha * 0.10f)
        ExitTone.None -> Color.Transparent
    }
    val timeView: @Composable (Modifier) -> Unit = { m ->
        // t.muted, not t.faint: faint on the light error tint is 3.9:1.
        Text(time, color = t.muted, style = cssText(type.ui, 0.75f, 400).tabularNums(), modifier = m)
    }
    val body: @Composable (Modifier) -> Unit = { m ->
        BaselineFlow(hGap = 8.dp, vGap = 8.dp, modifier = m) {
            Text(label, color = t.white, style = cssText(type.ui, 0.8125f, 600))
            if (detail.isNotEmpty()) {
                Text(proseText(detail), color = t.muted, style = cssText(type.ui, 0.75f, 400), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) t.css.spaceSm else 0.dp)
            .logRowEdges(t, last, tint)
            .padding(vertical = 12.dp)
            .padding(bottom = if (last) 0.dp else 1.dp)
            .clearAndSetSemantics {
                testTag = LogDialogTags.Exit
                contentDescription = listOf(time, label, detail).filter { it.isNotEmpty() }.joinToString(", ")
            },
    ) {
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                timeView(Modifier)
                body(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd)) {
                timeView(Modifier.alignByBaseline().widthIn(min = 176.dp))
                body(Modifier.weight(1f).alignByBaseline())
            }
        }
    }
}
