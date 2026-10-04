package com.tether.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeText
import com.tether.app.ui.text.proseText
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.appendStyled

/*
 * T6.4: the composer deck's session activity (chat-view.tsx 3703-3830, 4520-4525): the v56 todo
 * bar, the v54/v55 running background commands with their Stop key, the finished commands' chips
 * in the transcript and the output sheet both open. Styles: globals.css 6835-7110.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

// --- Todo bar -------------------------------------------------------------------------------------

/**
 * `TodoBar`: one 44dp row (the current item, or "All tasks complete" / "N tasks", and the n/total
 * count) that expands to the whole list; the WHOLE bar toggles (issue #9). Muted, never violet;
 * each item's state is an icon AND a word (pending is a hollow ring, not a colour). The list is
 * bounded (40% of the window) and scrolls inside the bar.
 */
@Composable
internal fun TodoBar(progress: ProgressView, sessionKey: String?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    var expanded by rememberSaveable(sessionKey) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, if (reduced) snap() else tween(160), label = "todoChevron")
    val shape = RoundedCornerShape(t.radiusSm)
    val summary = progressSummary(progress)
    val toggle = { expanded = !expanded }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.graphite)
            .border(1.dp, t.line, shape)
            // Issue #9: a tap anywhere on the bar toggles it. Pointer-only, like the web's container
            // handler (the head stays the one assistive control, so the items keep their own nodes).
            .pointerInput(Unit) { detectTapGestures { expanded = !expanded } }
            .testTag("todo-bar"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = toggle)
                .clearAndSetSemantics {
                    role = Role.Button
                    // ta-blf r2: the agent's words, prose-ruled for TalkBack too.
                    contentDescription = "${SafeText.prose(summary)}, ${progress.completed} of ${progress.total} tasks complete"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    onClick(if (expanded) "Collapse task list" else "Expand task list") { toggle(); true }
                    testTag = "todo-bar-head"
                }
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.ListTodo, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp).alpha(0.85f))
            Text(proseText(summary), style = TextStyle(fontFamily = type.ui, fontSize = rem(0.8f)), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                "${progress.completed}/${progress.total}",
                style = TextStyle(fontFamily = type.ui, fontSize = rem(0.75f), fontFeatureSettings = "tnum"),
                color = t.muted.copy(alpha = t.muted.alpha * 0.9f),
            )
            Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp).rotate(rotation))
        }
        if (expanded) {
            run {
                // `max-height: 40vh`: of the window, which the deck's constraints do not know — use the screen.
                val cap = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.4f).dp
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = cap)
                        .verticalScroll(rememberScrollState())
                        .padding(start = t.css.spaceSm, end = t.css.spaceSm, bottom = t.css.spaceXs),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    progress.items.forEach { item -> TodoItemRow(item) }
                }
            }
        }
    }
}

@Composable
private fun TodoItemRow(item: ProgressItem) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val done = item.status == ProgressStatus.COMPLETED
    val ink = if (item.status == ProgressStatus.IN_PROGRESS) t.ink else t.muted
    val word = progressStatusWord(item.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "${SafeText.prose(item.label)}, $word"
                testTag = "todo-item"
            }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Box(Modifier.padding(top = 1.dp).size(13.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                ProgressStatus.COMPLETED -> Icon(TetherIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
                ProgressStatus.IN_PROGRESS -> SpinningIcon(TetherIcons.Loader, tint = ink, size = 13.dp)
                else -> Box(Modifier.size(13.dp).alpha(0.55f).border(1.5.dp, ink, CircleShape))
            }
        }
        Text(
            proseText(item.label),
            style = TextStyle(fontFamily = type.ui, fontSize = rem(0.8f), textDecoration = if (done) TextDecoration.LineThrough else null),
            color = if (done) ink.copy(alpha = ink.alpha * 0.65f) else ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            word.uppercase(Locale.ROOT),
            style = TextStyle(fontFamily = type.ui, fontSize = rem(0.7f), letterSpacing = 0.04.em),
            color = ink.copy(alpha = ink.alpha * 0.6f),
        )
    }
}

// --- Background commands ------------------------------------------------------------------------

/**
 * Whether a Stop key may send, and why not in words (T6.3's lock rules: a saved copy, catching
 * up, read-only, handed off). Null = it may.
 */
internal fun stopLockCopy(lock: ConsentLock?): String? = when (lock) {
    null -> null
    ConsentLock.Offline -> "Connect to stop it. This is a saved copy."
    ConsentLock.CatchingUp -> "Catching up… Stop is available once this session is live."
    ConsentLock.ReadOnly -> "Read-only: Tether isn't driving this conversation."
    ConsentLock.HandedOff -> "This session was handed off."
}

/**
 * ta-coik.22: the lock the command keys honour (the composer's Send to agent / Background / Stop,
 * Interrupt and a queued row's "Interrupt now", and the background commands' Stop). The web leaves
 * every one of them live on a copy that is not live (chat-view.tsx 90fbb9f :3866-3875, :4535-4596):
 * a tap goes to the socket, which sends only when it is open. So offline and catching up lock
 * nothing here either (the client still refuses off a live link and says why). Read-only and a
 * handed-off session keep their lock (the web draws no composer there; the server refuses a
 * read-only session's `stop-command`).
 */
internal fun commandKeyLock(lock: ConsentLock?): ConsentLock? = when (lock) {
    ConsentLock.Offline, ConsentLock.CatchingUp -> null
    else -> lock
}

/**
 * The display form of a command (Info): its FIRST line, "…" when it has more, isolated
 * (FSI…PDI) so right-to-left text or bidi controls in it cannot reorder the words around it.
 */
internal fun commandLabel(command: String): String {
    // Round 3/4: embedding, override and isolate controls and the directional marks (U+200E/200F,
    // U+061C) are dropped (the FSI/PDI wrap is the only bidi control left), and leading lines that
    // are blank or only invisible characters are skipped, so the label is never just "…".
    val clean = command.filterNot { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it == '\u200E' || it == '\u200F' || it == '\u061C' }
    val breaks = charArrayOf('\n', '\r', '\u2028', '\u2029')
    fun blank(text: String) = text.codePoints().allMatch(::invisibleCodePoint)
    var start = 0
    while (true) {
        val nl = clean.indexOfAny(breaks, start)
        val line = if (nl < 0) clean.substring(start) else clean.substring(start, nl)
        if (nl < 0 || !blank(line)) break
        start = nl + 1
    }
    val rest = clean.substring(start)
    val nl = rest.indexOfAny(breaks)
    val firstLine = (if (nl < 0) rest else rest.substring(0, nl)).trimEnd()
    if (blank(firstLine)) return "\u2068(blank command)\u2069"
    val more = nl >= 0 && !blank(rest.substring(nl).filterNot { it in breaks })
    return "\u2068" + firstLine + (if (more) "…" else "") + "\u2069"
}

/**
 * A code point that draws nothing a reader can see: whitespace, FORMAT characters, and (round 5)
 * the blank-looking letters and symbols (U+115F/1160, U+3164, U+FFA0 Hangul fillers, U+2800
 * braille blank), tag characters (U+E0000-E007F), variation selectors and the other
 * default-ignorable code points (U+034F, U+17B4/17B5, U+180B-180F, U+FE00-FE0F, U+E0100-E01EF).
 */
internal fun invisibleCodePoint(cp: Int): Boolean = com.tether.app.client.LabelText.invisibleCodePoint(cp)

/** What the command surfaces may do: open a command's output, and stop a running one (a TAP only). */
@androidx.compose.runtime.Immutable
class CommandActions internal constructor(
    /** Why Stop cannot send right now (null: it can). */
    val stopLock: String?,
    val onOpen: (commandId: String) -> Unit,
    /**
     * Called from a Stop key's tap handler and nowhere else; the client re-checks everything
     * (L3: against the server origin captured here, the one this row was drawn for).
     */
    val onStop: (commandId: String) -> com.tether.app.client.StopCommandResult,
) {
    companion object {
        val Unavailable = CommandActions("Connect to stop it. This is a saved copy.", {}, { com.tether.app.client.StopCommandResult.NotConnected })
    }
}

/**
 * `.chat-bg-commands`: the RUNNING background commands above the composer (like the CLI's
 * `/bashes`). A row opens the live output; its Stop key (`--danger-wash` under `--danger-edge`)
 * stops the command. Rows are keyed by commandId (M1: a key's state never follows a position) and
 * at least 44dp (the web's are one text line).
 */
@Composable
internal fun RunningCommandsBar(commands: List<BackgroundCommandView>, actions: CommandActions) {
    if (commands.isEmpty()) return
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Running background commands" }.testTag("bg-commands"),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        commands.forEach { command -> key(command.commandId) { RunningCommandRow(command, actions) } }
    }
}

@Composable
private fun RunningCommandRow(command: BackgroundCommandView, actions: CommandActions) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val small = TextStyle(fontFamily = type.ui, fontSize = rem(0.72f))
    val label = commandLabel(command.command)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.graphite)
            .border(1.dp, if (command.running) t.violetStrong else t.line, shape)
            .padding(horizontal = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = "View live output") { actions.onOpen(command.commandId) }
                .semantics(mergeDescendants = true) { contentDescription = "View live output of $label, running" }
                .testTag("bg-command-open"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp))
            Text(label, style = small.copy(fontFamily = type.mono), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 11.dp)
            Text("running", style = small, color = t.muted)
        }
        StopKey(command, actions, compact = true)
    }
}

/**
 * The Stop key: `.chat-bg-command-stop` (compact) / `.command-modal-stop`. Enabled only while the
 * command runs, the link is live and the session may be driven. ta-coik.13: the first tap stops it,
 * as on the web (chat-view.tsx 90fbb9f :3866-3875 and :1719, no arm delay); a press that began on
 * another command's key is dropped ([StaleTapGuard], keyed by the command). Touches through an
 * overlay are refused. A tap asks the client, which re-checks it all against the live projection.
 * ta-coik.22: no "Stopping…" latch: like the web's key (always "Stop", no disabled state after a
 * click), it stays live while the command runs, and every tap asks the client again.
 */
@Composable
private fun StopKey(command: BackgroundCommandView, actions: CommandActions, compact: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val lock = actions.stopLock
    val actionable = command.running && lock == null
    val name = commandLabel(command.command)
    val shape = RoundedCornerShape(t.radiusSm)
    val label = "Stop"
    fun stop() {
        // The ONE place a stop-command originates: a tap on a live key.
        if (actionable) actions.onStop(command.commandId)
    }
    StaleTapGuard(command.commandId) { guard ->
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .widthIn(min = 44.dp)
                .then(guard)
                .then(if (actionable) Modifier.clickable(role = Role.Button) { stop() } else Modifier)
                // Its own node whether or not it is live (a disabled key must not merge into its row).
                .semantics(mergeDescendants = true) { }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = when {
                        lock != null -> "Stop $name, unavailable: $lock"
                        else -> "Stop $name"
                    }
                    if (!actionable) disabled()
                    if (actionable) onClick("Stop") { stop(); true }
                    testTag = "bg-command-stop"
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .alpha(if (actionable) 1f else 0.55f)
                    .clip(shape)
                    .background(t.dangerWash)
                    .border(1.dp, t.dangerEdge, shape)
                    .padding(horizontal = if (compact) 6.4.dp else 8.dp, vertical = if (compact) 2.4.dp else 4.8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.2.dp),
            ) {
                Icon(TetherIcons.CircleStop, contentDescription = null, tint = t.danger, modifier = Modifier.size(if (compact) 12.dp else 13.dp))
                Text(label, style = TextStyle(fontFamily = type.ui, fontSize = if (compact) rem(0.68f) else rem(0.7f), fontWeight = FontWeight(600)), color = t.danger)
            }
        }
    }
}

/**
 * `BackgroundCommandChip` (v55): a finished command, anchored in the transcript where it was
 * launched. One tap opens its captured output. `--mineral-deep`, a 2px left edge (`--danger` when it
 * failed; the status is also spelt out: "exit 2", "signal SIGKILL", "interrupted").
 */
@Composable
internal fun BackgroundCommandChip(command: BackgroundCommandView, onOpen: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val failed = backgroundCommandFailed(command)
    val shape = RoundedCornerShape(t.radiusMd)
    val edge = if (failed) t.danger else t.muted
    val status = backgroundCommandStatusLabel(command)
    val small = TextStyle(fontFamily = type.ui, fontSize = rem(0.72f))
    val label = commandLabel(command.command)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.mineralDeep)
            .border(1.dp, t.line, shape)
            .drawBehind { drawRect(edge, size = Size(2.dp.toPx(), size.height)) }
            .clickable(role = Role.Button, onClickLabel = "View output", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "View output of $label, $status" }
            .heightIn(min = 44.dp)
            .padding(start = t.css.spaceSm + 1.dp, end = t.css.spaceSm, top = 5.6.dp, bottom = 5.6.dp)
            .testTag("bg-command-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
        Text(label, style = small.copy(fontFamily = type.mono), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(status, style = small, color = if (failed) t.danger else t.muted)
        Text("View output", style = small.copy(fontWeight = FontWeight(600)), color = t.violetStrong)
    }
}

/**
 * `BackgroundCommandModal`: the live / captured output of one command, over the scrim. Closes on
 * the X, a tap outside, or Back. [command] null (evicted from the bounded list, or gone) = nothing.
 */
@Composable
internal fun CommandOutputDialog(command: BackgroundCommandView?, actions: CommandActions, onClose: () -> Unit) {
    command ?: return
    val t = LocalTetherTokens.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(dialogScrim(t))
                // A tap outside closes (pointer-only: the close key and Back are the accessible ways).
                .pointerInput(onClose) { detectTapGestures { onClose() } }
                .padding(t.css.spaceMd),
            // P3 (r3): top-anchored, not centred like the web's, so the sheet's head (and its Stop
            // key) stays put while short output grows the sheet downward.
            contentAlignment = Alignment.TopCenter,
        ) {
            CommandOutputSurface(command, actions, onClose, Modifier.padding(top = SHEET_TOP_INSET))
        }
    }
}

/** Where the top-anchored output sheet starts below the window's top edge. */
private val SHEET_TOP_INSET = 48.dp

/** L4: how often a streaming sheet redraws its output at most (about 4 Hz). */
internal const val COMMAND_SHEET_SAMPLE_MS = 250L

/** One drawn line of the output: its pieces (a line can mix stdout and stderr). */
@androidx.compose.runtime.Immutable
internal data class OutputLine(val pieces: List<OutputSegment>)

/** [tail] as lines (split at "\n"; the final newline makes no empty last line, as a `<pre>`). */
internal fun outputLines(tail: OutputTail): List<OutputLine> {
    val lines = ArrayList<OutputLine>()
    var current = ArrayList<OutputSegment>()
    for (seg in tail.segments) {
        var start = 0
        while (true) {
            val nl = seg.text.indexOf('\n', start)
            if (nl < 0) {
                if (start < seg.text.length) current.add(OutputSegment(seg.stderr, seg.text.substring(start)))
                break
            }
            if (nl > start) current.add(OutputSegment(seg.stderr, seg.text.substring(start, nl)))
            lines.add(OutputLine(current))
            current = ArrayList()
            start = nl + 1
        }
    }
    if (current.isNotEmpty()) lines.add(OutputLine(current))
    return lines
}

/**
 * `.command-modal`: `--graphite`, `--radius-md`, a 2px top edge (violet running, `--danger`
 * failed); the head (terminal glyph, the command, its status, Stop while running, the 44dp close
 * key), the output (stderr in `--warning`), the log file foot. L4: the output is the capture's
 * tail (64,000 characters, with a note when earlier output is not shown) as a lazy list of lines,
 * and a streaming command redraws it at most every [COMMAND_SHEET_SAMPLE_MS], so a flood of chunks
 * costs one rebuild per interval, off the main thread. Drawn inline by the goldens; [CommandOutputDialog] hosts it.
 */
@Composable
internal fun CommandOutputSurface(command: BackgroundCommandView, actions: CommandActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val failed = backgroundCommandFailed(command)
    val running = command.running
    val shape = RoundedCornerShape(t.radiusMd)
    val topEdge: Color? = when {
        running -> t.violetStrong
        failed -> t.danger
        else -> null
    }
    val line = t.line
    val status = backgroundCommandStatusLabel(command)
    val latestSegments by rememberUpdatedState(command.segments)
    var lines by remember(command.commandId) { mutableStateOf(outputLines(outputTail(outputSegments(command.segments))) to outputTail(outputSegments(command.segments)).dropped) }
    val observer by rememberUpdatedState(LocalChatRowObserver.current)
    LaunchedEffect(command.commandId) {
        snapshotFlow { latestSegments }
            .conflate()
            .collect { segments ->
                val next = withContext(Dispatchers.Default) {
                    val tail = outputTail(outputSegments(segments))
                    outputLines(tail) to tail.dropped
                }
                lines = next
                observer?.invoke("command-output-lines")
                delay(COMMAND_SHEET_SAMPLE_MS)
            }
    }
    val listState = rememberLazyListState()
    // Follow the tail while it streams, so a live command reads like a terminal.
    LaunchedEffect(lines, running) { if (running && lines.first.isNotEmpty()) listState.scrollToItem(lines.first.size) }
    BoxWithConstraints(modifier) {
        Column(
            Modifier
                .widthIn(max = 880.dp)
                .fillMaxWidth()
                .heightIn(max = minOf(maxHeight * 0.8f, 720.dp))
                .clip(shape)
                .background(t.graphite)
                .border(1.dp, t.line, shape)
                .then(if (topEdge != null) Modifier.drawBehind { drawRect(topEdge, size = Size(size.width, 2.dp.toPx())) } else Modifier)
                // A tap on the sheet must not reach the scrim (which closes it). Pointer-only, so the
                // sheet's controls and lines stay their own accessibility nodes.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = "Output of ${commandLabel(command.command)}" }
                .testTag("command-output"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
                    .padding(start = t.css.spaceMd, end = t.css.spaceSm, top = t.css.spaceXs, bottom = t.css.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
                Text(commandLabel(command.command), style = TextStyle(fontFamily = type.mono, fontSize = rem(0.74f)), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (running) SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 12.dp)
                    Text(status, style = TextStyle(fontFamily = type.ui, fontSize = rem(0.74f)), color = if (failed) t.danger else t.muted)
                }
                if (running) StopKey(command, actions, compact = false)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(t.radiusSm))
                        .clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose)
                        .semantics { contentDescription = "Close" }
                        .testTag("command-output-close"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp))
                }
            }
            // One Text per line: keep each line's full 1.5 leading (a single Text trims only its ends).
            val body = TextStyle(
                fontFamily = type.mono,
                fontSize = rem(0.8f),
                lineHeight = 1.5.em,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
                ),
            )
            val (shown, dropped) = lines
            val warning = t.warning
            val faint = t.faint
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().testTag("command-output-body"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(t.css.spaceMd),
            ) {
                if (shown.isEmpty() && dropped == 0) {
                    item(key = "empty") { Text(if (running) "waiting for output…" else "no output was captured", style = body, color = t.muted) }
                }
                if (dropped > 0) {
                    item(key = "dropped") { Text("… (earlier output not shown here — the full output is in the log file)", style = body, color = faint) }
                }
                items(shown.size) { i ->
                    // ta-blf: each piece as terminal output ([SafeText.terminal]): SGR colour
                    // dropped, every other control, bidi or invisible code point a visible token.
                    val token = tokenStyle(t)
                    val text = remember(shown[i], warning, token) {
                        buildAnnotatedString {
                            for (piece in shown[i].pieces) {
                                val display = SafeText.terminal(piece.text)
                                if (piece.stderr) withStyle(SpanStyle(color = warning)) { appendStyled(display, token) } else appendStyled(display, token)
                            }
                        }
                    }
                    Text(text, style = body.copy(textDirection = codeDirection), color = t.ink, modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                codeText((if (command.outputTruncated) "Live view truncated — full output: " else "Full output: ") + command.logFile),
                style = TextStyle(fontFamily = type.ui, fontSize = rem(0.68f)),
                color = t.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(line, size = Size(size.width, 1.dp.toPx())) }
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs),
            )
        }
    }
}
