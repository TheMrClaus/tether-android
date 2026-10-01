package com.tether.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.components.tetherWell
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.Manrope
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * T7.1: the session composer's writing surface, ported from tether components/chat-view.tsx
 * (the `.chat-composer-well` at :4144-4516, the `QueuedMessageRow` at :1402-1489) and its CSS
 * (globals.css 7696-7758 queue, 11319-11477 well + toolbar, 11930-11950 phone; studio.css
 * 383-396, 457-460).
 */

/** The web's media/container queries that shape the composer, resolved once per composition. */
@Immutable
internal data class ComposerMetrics(
    /** Below 48rem: the web's mobile composer (one row of keys, icon-only actions). */
    val phone: Boolean,
    /** Below 64rem (`max-width: 63.99rem`): 44dp attach / Send keys (touch sizing). */
    val touchKeys: Boolean,
)

@Composable
internal fun composerMetrics(): ComposerMetrics {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    return ComposerMetrics(
        phone = phone,
        touchKeys = phone || LocalConfiguration.current.screenWidthDp < 1024,
    )
}

/** Placeholders for a touch pointer (chat-view.tsx:4187-4193, `finePointer` false). */
internal const val PLACEHOLDER_IDLE = "Message the agent…"
internal const val PLACEHOLDER_BUSY = "Agent is working — message queues"

/**
 * chat-view.tsx:3244 onKeyDown: Enter without Shift submits, Shift+Enter is a newline. An IME
 * composition in progress keeps its Enter: the browser reports that keystroke as key "Process"
 * (keyCode 229), never "Enter", so the web cannot send half-composed text either.
 */
internal fun isSubmitKey(event: KeyEvent, value: TextFieldValue): Boolean =
    event.type == KeyEventType.KeyDown &&
        (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
        !event.isShiftPressed &&
        value.composition == null

/** `.chat-input` inside the well: the type and padding each layout gives it. */
internal fun composerTextStyle(base: TextStyle, m: ComposerMetrics): TextStyle = when {
    m.phone -> base.copy(fontSize = 16.sp, lineHeight = 25.6.sp)
    else -> base.copy(fontSize = 14.8.sp, lineHeight = 23.68.sp)
}

/**
 * The composer's text field (`textarea.chat-input`, rows=1, auto-growing to its CSS max-height:
 * 30dvh on a phone, 60vh otherwise). The soft keyboard's action key is Send, like the web on a
 * phone, where the keyboard's Enter submits; a hardware Enter submits and Shift+Enter breaks the
 * line ([onKey] sees every key first, for the slash menu). The accessible name is the web's
 * `aria-label="Message the agent"`.
 */
@Composable
internal fun ComposerInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
    metrics: ComposerMetrics,
    onKey: (KeyEvent) -> Boolean,
    onImeSend: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    /** T7.3 `.chat-input--command`: the draft is a `!` command — drawn in the mono face. */
    command: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val base = composerTextStyle(type.body, metrics)
    val style = if (command) base.copy(fontFamily = type.mono) else base
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = screenHeight * (if (metrics.phone) 0.3f else 0.6f)
    val (minHeight, padding) = when {
        metrics.phone -> 52.8.dp to Pad(14.dp, 14.dp, 14.dp, 8.dp)
        else -> 67.2.dp to Pad(16.dp, 16.dp, 16.dp, 8.dp)
    }
    // Studio sets the placeholder to `--faint` (studio.css:481-482).
    val placeholderColor = t.faint
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .onPreviewKeyEvent(onKey)
            .semantics { contentDescription = "Message the agent" },
        enabled = enabled,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onImeSend() }),
        interactionSource = interactionSource,
        decorationBox = { inner ->
            Box(Modifier.padding(start = padding.start, top = padding.top, end = padding.end, bottom = padding.bottom)) {
                if (value.text.isEmpty()) {
                    Text(placeholder, style = style, color = placeholderColor, maxLines = 1)
                }
                inner()
            }
        },
    )
}

private data class Pad(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

/**
 * `.chat-composer-well`: ONE recessed well holding the text field on top and the key bank
 * seated at its foot. Focus belongs to the well, but only for the text field
 * (`:has(> .chat-input:focus)`): violet-strong border + focus-glow ring. Studio draws a raised
 * graphite card instead (studio.css:384-385).
 */
@Composable
internal fun ComposerWell(
    metrics: ComposerMetrics,
    inputFocused: Boolean,
    modifier: Modifier = Modifier,
    /**
     * T7.3 (globals.css 11353-11360): `!` command mode turns the whole well into the red-edged command
     * line — `--danger-edge` over `--danger-wash`; focused, `--danger` and a 3px `--danger-edge` ring.
     * The rule outranks Studio's own well (0,3,0 over 0,2,0), so every skin draws it.
     */
    command: Boolean = false,
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val surface = if (command) {
        val ring = if (inputFocused) listOf(com.tether.app.ui.theme.CssShadow(false, 0.dp, 0.dp, 0.dp, 3.dp, t.dangerEdge)) else emptyList()
        Modifier.cssSurface(
            RoundedCornerShape(16.dp),
            t.dangerWash,
            CssBorder(1.dp, if (inputFocused) t.danger else t.dangerEdge),
            ring, // `--well` (transparent in Studio) was retired at tether 887c222
        )
    } else run {
        val shadow = if (inputFocused) {
            softShadow(5.dp, 22.dp, Color(20, 35, 65).copy(alpha = 0.2f), spread = (-9).dp)
        } else {
            softShadow(4.dp, 18.dp, Color(20, 35, 65).copy(alpha = 0.14f), spread = (-8).dp)
        }
        Modifier.cssSurface(
            RoundedCornerShape(16.dp),
            t.graphite,
            CssBorder(1.dp, if (inputFocused) t.violet else t.lineStrong),
            listOf(shadow),
        )
    }
    Column(modifier.fillMaxWidth().then(surface)) { content() }
}

/** T13.2 r2: a queued row's "Interrupt now". */
internal const val QUEUE_INTERRUPT_TAG = "queue-interrupt-now"

/** ta-ceo (#229): a queued row's second, informed "Stop anyway" and its "Keep waiting". */
internal const val QUEUE_STOP_ANYWAY_TAG = "queue-stop-anyway"
internal const val QUEUE_KEEP_WAITING_TAG = "queue-keep-waiting"

/** chat-view.tsx:1468: an end-of-turn row's wait line. */
internal const val QUEUED_AFTER_TURN_COPY = "Queued — sends after the current turn"

/**
 * `.chat-queue` (v14): the messages queued while a turn runs, each editable in place. Labelled
 * "Queued messages" for TalkBack, as the web's `aria-label`.
 */
@Composable
internal fun QueuedMessages(
    queued: List<QueuedMessage>,
    onSave: (queueId: String, text: String) -> Unit,
    onRemove: (queueId: String) -> Unit,
    sessionId: String?,
    /** T6.7: the turn "Interrupt now" is drawn for (the active turn); null = none runs. */
    interruptTurnId: String?,
    onInterruptNow: (turnId: String) -> Unit,
    /** T13.2 r2: why "Interrupt now" cannot send (a copy that is not live); null = it can. */
    interruptLock: String?,
    /** ta-ceo (#229): what a Stop would destroy right now (chat-view.tsx:1974), off the projection. */
    liveWork: QueueWait.LiveWork = QueueWait.LiveWork.None,
    /** ta-ceo: the event-anchored server "now" a deferred row's wait is measured to. */
    serverNow: () -> Long = { 0L },
    /** ta-ceo: the copy is not live — a deferred row's wait stops counting (it claims nothing about now). */
    stale: Boolean = false,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Queued messages" },
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        for (message in queued) {
            androidx.compose.runtime.key(message.queueId) {
                QueuedMessageRow(
                    text = message.text,
                    atToolBoundary = message.atToolBoundary,
                    onSave = { onSave(message.queueId, it) },
                    onRemove = { onRemove(message.queueId) },
                    onInterruptNow = onInterruptNow,
                    interruptLock = interruptLock,
                    interruptIdentity = Triple(sessionId, message.queueId, interruptTurnId),
                    interruptTurnId = interruptTurnId,
                    queuedAt = message.queuedAt,
                    liveWork = liveWork,
                    serverNow = serverNow,
                    stale = stale,
                )
            }
        }
    }
}

/** What committing a queued row's edit does (chat-view.tsx:1433-1441). */
internal sealed interface QueueCommit {
    data object Nothing : QueueCommit
    data object Remove : QueueCommit
    data class Save(val text: String) : QueueCommit
}

/** Blank removes; a changed text saves trimmed; an unchanged one does nothing. */
internal fun queueCommit(edited: String, serverText: String): QueueCommit {
    val trimmed = edited.trim()
    return when {
        trimmed.isEmpty() -> QueueCommit.Remove
        trimmed != serverText -> QueueCommit.Save(trimmed)
        else -> QueueCommit.Nothing
    }
}

/**
 * One pending queued message: editable in place (commit on Enter or when focus leaves),
 * removable, and blanking it removes it. While not being edited it mirrors the server text
 * (another device may have edited it). Escape restores the server text without saving: the
 * web's handler means to, but its blur commits the pre-restore buffer from the same render's
 * closure (chat-view.tsx:1464-1467 then 1459) — see the T7.1 README.
 *
 * `flushMode: "next-call"` (issue #183) rows offer "Interrupt now". ta-ceo (issue #229,
 * chat-view.tsx:1410-1548): every row says under it, in words, when it sends; a deferred row says
 * what it waits for and for how long, measured from the journal-stamped [queuedAt] to the
 * event-anchored [serverNow] (never a device clock read), ticking each second. After
 * [QueueWait.DEFERRAL_CHOICE_AFTER_MS] with work still live it asks for a decision (keep waiting,
 * or "Interrupt now" naming what that stops), and with live work "Interrupt now" asks for a second,
 * informed tap ("Stop anyway") first. Status is in words, never colour alone.
 */
@Composable
internal fun QueuedMessageRow(
    text: String,
    atToolBoundary: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onInterruptNow: (turnId: String) -> Unit,
    interruptLock: String?,
    /** T6.7: what "Interrupt now" is armed for (session, queued message, turn): a change re-arms it. */
    interruptIdentity: Any = Unit,
    /** T6.7: the turn "Interrupt now" is drawn for; null = none runs, so it cannot send. */
    interruptTurnId: String? = null,
    /** v136: when a deferred row was queued (journal ts, epoch ms); null = unstamped. */
    queuedAt: Double? = null,
    liveWork: QueueWait.LiveWork = QueueWait.LiveWork.None,
    serverNow: () -> Long = { 0L },
    stale: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val focusManager = LocalFocusManager.current
    var value by remember { mutableStateOf(text) }
    var editing by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    LaunchedEffect(text) { if (!editing) value = text }

    // chat-view.tsx:1435-1445: "Keep waiting" acknowledges the choice; the destructive act is
    // confirmed with a second tap whenever it would stop live work. The confirmation belongs to the
    // turn it was asked for: a new turn (or a move to another session) drops it.
    var choiceAcknowledged by remember { mutableStateOf(false) }
    var confirmingInterrupt by remember(interruptIdentity) { mutableStateOf(false) }
    val currentServerNow by rememberUpdatedState(serverNow)
    var now by remember { mutableLongStateOf(serverNow()) }
    LaunchedEffect(atToolBoundary, stale) {
        // A saved copy's clock is frozen where it stood (T13.2), as the run row's.
        while (atToolBoundary && !stale) {
            now = currentServerNow()
            delay(1000)
        }
    }

    fun commit() {
        editing = false
        if (reverting) {
            reverting = false
            value = text
            return
        }
        when (val c = queueCommit(value, text)) {
            QueueCommit.Remove -> onRemove()
            is QueueCommit.Save -> onSave(c.text)
            QueueCommit.Nothing -> Unit
        }
    }

    val cost = QueueWait.stopCostCopy(liveWork)
    val wait = QueueWait.deferredWaitCopy(liveWork, queuedAt?.let { max(0.0, now - it) })
    // The confirmation lasts while there is a price to confirm and the copy can interrupt at all.
    LaunchedEffect(cost.isEmpty(), interruptLock != null) {
        if (cost.isEmpty() || interruptLock != null) confirmingInterrupt = false
    }
    val confirming = confirmingInterrupt && cost.isNotEmpty() && interruptLock == null
    val choice = atToolBoundary && wait.needsChoice && !choiceAcknowledged
    val waitLabel = if (atToolBoundary) wait.label else QUEUED_AFTER_TURN_COPY
    val shape = RoundedCornerShape(t.radiusMd)
    val rowHeight = 30.4.dp // 1.9rem
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tetherWell(t, shape)
            .queueEdge(t.violetStrong)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Box(Modifier.heightIn(min = rowHeight), contentAlignment = Alignment.Center) {
            // The web's icon carries the label as a tooltip only; the words are the line below.
            Icon(TetherIcons.Loader, contentDescription = null, tint = t.violet, modifier = Modifier.size(13.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = rowHeight, max = 128.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) editing = true else if (editing) commit()
                }
                .onPreviewKeyEvent { event -> queueRowKey(event, focusManager) { reverting = true } }
                .semantics { contentDescription = "Edit queued message" },
            textStyle = LocalTetherTypography.current.body.copy(color = t.ink, fontSize = 13.6.sp, lineHeight = 20.4.sp),
            cursorBrush = SolidColor(t.violet),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner -> Box(Modifier.padding(vertical = t.css.spaceXs)) { inner() } },
        )
        if (atToolBoundary && !confirming) {
            // issue #229: with live work the first tap asks for a second, informed one.
            QueueInterruptNow(
                onClick = { turnId -> if (cost.isNotEmpty()) confirmingInterrupt = true else onInterruptNow(turnId) },
                lock = interruptLock,
                identity = interruptIdentity,
                turnId = interruptTurnId,
            )
        }
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Remove queued message" }
                .size(rowHeight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
        }
    }
        QueueWaitLine(
            label = waitLabel,
            choice = choice,
            confirming = confirming,
            cost = cost,
            onStopAnyway = { turnId ->
                confirmingInterrupt = false
                onInterruptNow(turnId)
            },
            onKeepWaiting = { if (confirming) confirmingInterrupt = false else choiceAcknowledged = true },
            lock = interruptLock,
            identity = interruptIdentity,
            turnId = interruptTurnId,
        )
    }
}

/**
 * `.chat-queue-wait` (globals.css:7125-7137): the full-width line under a queued row — what it is
 * waiting for (never a silent spinner), then either the Stop confirmation ("<cost> — it cannot be
 * undone." · Stop anyway · Keep waiting) or, once the wait outlasted the threshold, the choice
 * ("Delivers at the next safe boundary…" · Keep waiting). 0.74rem / 1.4, `--muted`; the choice state
 * reads in `--ink`. The prompt is announced politely; the ticking wait is not (it changes each second).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QueueWaitLine(
    label: String,
    choice: Boolean,
    confirming: Boolean,
    cost: String,
    onStopAnyway: (turnId: String) -> Unit,
    onKeepWaiting: () -> Unit,
    lock: String?,
    identity: Any,
    turnId: String?,
) {
    val t = LocalTetherTokens.current
    val color = if (choice && !confirming) t.ink else t.muted
    val style = LocalTetherTypography.current.body.copy(color = color, fontSize = 11.84.sp, lineHeight = 16.576.sp)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Text(label, style = style, modifier = Modifier.align(Alignment.CenterVertically))
        if (confirming || choice) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.align(Alignment.CenterVertically).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                if (confirming) {
                    Text("$cost — it cannot be undone.", style = style)
                    QueueInterruptNow(
                        onClick = onStopAnyway,
                        lock = lock,
                        identity = "stop-anyway" to identity,
                        turnId = turnId,
                        label = "Stop anyway",
                        description = "Stop anyway",
                        confirm = true,
                        tag = QUEUE_STOP_ANYWAY_TAG,
                    )
                } else {
                    Text(
                        "Delivers at the next safe boundary or when the turn ends. Interrupting stops ${QueueWait.interruptStopsCopy(cost)}.",
                        style = style,
                    )
                }
                QueueKeepWaiting(onKeepWaiting)
            }
        }
    }
}

/** `.chat-queue-interrupt` "Keep waiting": non-destructive, so a plain tap (no arming). */
@Composable
private fun QueueKeepWaiting(onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(t.radiusSm), Color.Transparent, CssBorder(1.dp, t.lineStrong))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Keep waiting" }
            .testTag(QUEUE_KEEP_WAITING_TAG)
            .padding(horizontal = t.css.spaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text("Keep waiting", color = t.muted, fontFamily = Manrope, fontSize = 11.84.sp, maxLines = 1)
    }
}

/** Enter (no Shift) commits by leaving the field; Escape reverts first (web :1460-1468). */
private fun queueRowKey(event: KeyEvent, focusManager: FocusManager, revert: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> {
            focusManager.clearFocus()
            true
        }
        event.key == Key.Escape -> {
            revert()
            focusManager.clearFocus()
            true
        }
        else -> false
    }
}

/** `.chat-queue-row { border-left: 2px solid var(--violet-strong) }` over the well's 1px line. */
private fun Modifier.queueEdge(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color, size = Size(2.dp.toPx(), size.height))
}

/**
 * `.chat-queue-interrupt` (issue #183): the deliberate "stop the turn and send this now" action
 * on a row waiting for a tool boundary. 1.9rem tall, 2.75rem under a coarse pointer. ta-ceo:
 * [confirm] draws `.chat-queue-interrupt--confirm` (`--ink` words and edge), the second tap.
 */
@Composable
private fun QueueInterruptNow(
    onClick: (turnId: String) -> Unit,
    lock: String?,
    identity: Any,
    turnId: String?,
    label: String = "Interrupt now",
    description: String = "Interrupt now — stops the current turn, its open tool call and its background tasks, then sends this",
    confirm: Boolean = false,
    tag: String = QUEUE_INTERRUPT_TAG,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val ink = if (confirm) t.ink else t.muted
    // T6.7: bound to the turn it is drawn for and armed like the composer's Interrupt key (500 ms,
    // re-armed by a new turn or a move, no overlay touches); drawn as before while it arms. The
    // confirmation is a new control, so it arms afresh: a double tap never passes straight through.
    val arming = rememberArmedControl(identity, lock == null && turnId != null)
    val armed = arming.armed && lock == null && turnId != null
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .then(arming.modifier)
            // T13.2 r2: a copy that is not live cannot interrupt: shown, dimmed, and inert.
            .alpha(if (lock == null) 1f else 0.55f)
            .cssSurface(shape, Color.Transparent, CssBorder(1.dp, if (confirm) t.ink else t.lineStrong))
            .clickable(enabled = armed, onClick = { if (armed && turnId != null) onClick(turnId) })
            .semantics(mergeDescendants = true) {
                contentDescription = if (lock == null) description else "$description, unavailable: $lock"
            }
            .testTag(tag)
            .padding(horizontal = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.8.dp),
    ) {
        Icon(TetherIcons.CircleStop, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        Text(label, color = ink, fontFamily = Manrope, fontSize = 11.84.sp, maxLines = 1)
    }
}
