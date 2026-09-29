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
                    contentDescription = "$summary, ${progress.completed} of ${progress.total} tasks complete"
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
            Text(summary, style = TextStyle(fontFamily = type.ui, fontSize = rem(0.8f)), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                contentDescription = "${item.label}, $word"
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
            item.label,
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

/** What the command surfaces may do: open a command's output, and stop a running one (a TAP only). */
@androidx.compose.runtime.Immutable
class CommandActions internal constructor(
    /** Why Stop cannot send right now (null: it can). */
    val stopLock: String?,
    val onOpen: (commandId: String) -> Unit,
    /** Called from the Stop key's tap handler and nowhere else; the client re-checks everything. */
    val onStop: (commandId: String) -> com.tether.app.client.StopCommandResult,
) {
    companion object {
        val Unavailable = CommandActions("Connect to stop it. This is a saved copy.", {}, { com.tether.app.client.StopCommandResult.NotConnected })
    }
}

/**
 * The Stop key's state for one command: [sent] after a tap the client accepted (the key then
 * reads "Stopping…" and stays disabled while the command still runs), remembered per command.
 */
@Composable
private fun rememberStopSent(commandId: String) = rememberSaveable(commandId) { mutableStateOf(false) }

/**
 * `.chat-bg-commands`: the RUNNING background commands above the composer (like the CLI's
 * `/bashes`). A row opens the live output; its Stop key (`--danger-wash` under `--danger-edge`)
 * stops the command. Rows are at least 44dp (the web's are one text line).
 */
@Composable
internal fun RunningCommandsBar(commands: List<BackgroundCommandView>, actions: CommandActions) {
    if (commands.isEmpty()) return
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Running background commands" }.testTag("bg-commands"),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        commands.forEach { command -> RunningCommandRow(command, actions) }
    }
}

@Composable
private fun RunningCommandRow(command: BackgroundCommandView, actions: CommandActions) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val small = TextStyle(fontFamily = type.ui, fontSize = rem(0.72f))
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
                .semantics(mergeDescendants = true) { contentDescription = "View live output of ${command.command}, running" }
                .testTag("bg-command-open"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp))
            Text(command.command, style = small.copy(fontFamily = type.mono), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 11.dp)
            Text("running", style = small, color = t.muted)
        }
        StopKey(command, actions, compact = true)
    }
}

/**
 * The Stop key: `.chat-bg-command-stop` (compact) / `.command-modal-stop`. Enabled only while the
 * command runs, the link is live and the session may be driven; a tap asks the client, which
 * re-checks all of it against the live projection before a frame leaves. A disabled key says why
 * in its accessible name.
 */
@Composable
private fun StopKey(command: BackgroundCommandView, actions: CommandActions, compact: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var sent by rememberStopSent(command.commandId)
    val lock = actions.stopLock
    val enabled = command.running && lock == null && !sent
    val shape = RoundedCornerShape(t.radiusSm)
    val label = if (sent) "Stopping…" else "Stop"
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button) {
                        // The ONE place a stop-command originates: this tap.
                        if (actions.onStop(command.commandId) == com.tether.app.client.StopCommandResult.Sent) sent = true
                    }
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = when {
                    sent -> "Stopping ${command.command}"
                    lock != null -> "Stop this command, unavailable: $lock"
                    else -> if (compact) "Stop this background command" else "Stop this command"
                }
                if (!enabled) disabled()
                if (enabled) onClick("Stop") { if (actions.onStop(command.commandId) == com.tether.app.client.StopCommandResult.Sent) sent = true; true }
                testTag = "bg-command-stop"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .alpha(if (enabled) 1f else 0.55f)
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.mineralDeep)
            .border(1.dp, t.line, shape)
            .drawBehind { drawRect(edge, size = Size(2.dp.toPx(), size.height)) }
            .clickable(role = Role.Button, onClickLabel = "View output", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "View output of ${command.command}, $status" }
            .heightIn(min = 44.dp)
            .padding(start = t.css.spaceSm + 1.dp, end = t.css.spaceSm, top = 5.6.dp, bottom = 5.6.dp)
            .testTag("bg-command-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
        Text(command.command, style = small.copy(fontFamily = type.mono), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .padding(t.css.spaceMd),
            contentAlignment = Alignment.Center,
        ) {
            CommandOutputSurface(command, actions, onClose)
        }
    }
}

/**
 * `.command-modal`: `--graphite`, `--radius-md`, a 2px top edge (violet running, `--danger`
 * failed); the head (terminal glyph, the command, its status, Stop while running, the 44dp close
 * key), the output (stderr in `--warning`; the tail of a very long capture, with a note), the log
 * file foot. Drawn inline by the goldens; [CommandOutputDialog] hosts it.
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
    val tail = remember(command.segments) { outputTail(outputSegments(command.segments)) }
    val scroll = rememberScrollState()
    // Follow the tail while it streams, so a live command reads like a terminal.
    LaunchedEffect(command.segments, running) { if (running) scroll.scrollTo(scroll.maxValue) }
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
                .clickable(remember { MutableInteractionSource() }, indication = null) { }
                .semantics { paneTitle = "Output of ${command.command}" }
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
                Text(command.command, style = TextStyle(fontFamily = type.mono, fontSize = rem(0.74f)), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            val body = TextStyle(fontFamily = type.mono, fontSize = rem(0.8f), lineHeight = 1.5.em)
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll).padding(t.css.spaceMd)) {
                if (tail.segments.isEmpty() && tail.dropped == 0) {
                    Text(if (running) "waiting for output…" else "no output was captured", style = body, color = t.muted)
                } else {
                    val warning = t.warning
                    val faint = t.faint
                    val text = remember(tail, warning, faint) {
                        val built = buildAnnotatedString {
                            if (tail.dropped > 0) withStyle(SpanStyle(color = faint)) { append("… (earlier output not shown here — the full output is in the log file)\n") }
                            for (seg in tail.segments) if (seg.stderr) withStyle(SpanStyle(color = warning)) { append(seg.text) } else append(seg.text)
                        }
                        // A `<pre>` draws no line box for ONE trailing newline (preText).
                        if (built.text.endsWith("\n")) built.subSequence(0, built.length - 1) else built
                    }
                    Text(text, style = body, color = t.ink, modifier = Modifier.testTag("command-output-body"))
                }
            }
            Text(
                (if (command.outputTruncated) "Live view truncated — full output: " else "Full output: ") + command.logFile,
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

