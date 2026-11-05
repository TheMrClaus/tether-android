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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.text.font.FontWeight
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
/** Spec §3.1 (coarse pointer), when the engine can steer ([QueueWait.QUEUED_STEER_COPY]). */
internal const val PLACEHOLDER_BUSY_STEER = "Agent is working — message goes into this turn"

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

/** The well's border (`.chat-composer-well`, 1px `--line-strong`): the web's toolbar container sits inside it ([composerToolbarContentWidth]). */
internal val COMPOSER_WELL_BORDER = 1.dp

/** `.chat-composer-toolbar { padding: 0.25rem 0.65rem 0.65rem }` (studio.css:394), the side padding: 0.65rem. */
internal val COMPOSER_TOOLBAR_SIDE = 10.4.dp

/**
 * ta-9mcp: the toolbar's CONTENT width, the box its container query (`@container (max-width: 28rem)`, globals.css:10650-10652,
 * on `.chat-composer-toolbar { container-type: inline-size }`, :10554) reads: the well less its border and the toolbar's side
 * padding, both sides. 441.2 at the 800 pane, 486.2 at 845 (the served captures).
 */
internal fun composerToolbarContentWidth(well: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    well - COMPOSER_WELL_BORDER * 2 - COMPOSER_TOOLBAR_SIDE * 2

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
            CssBorder(COMPOSER_WELL_BORDER, if (inputFocused) t.danger else t.dangerEdge),
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
            CssBorder(COMPOSER_WELL_BORDER, if (inputFocused) t.violet else t.lineStrong),
            listOf(shadow),
        )
    }
    Column(
        Modifier
            // ta-4vun: the well's top edge, for the host that lays the transcript over the strip above it.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height, mapOf(ComposerWellTop to 0)) { placeable.place(0, 0) }
            }
            .then(modifier)
            .fillMaxWidth()
            .testTag(CHAT_COMPOSER_WELL_TAG)
            .then(surface),
    ) { content() }
}

/** ta-4vun: the composer's well (its box starts where the transcript's overflow stops). */
internal const val CHAT_COMPOSER_WELL_TAG = "chat-composer-well"

/**
 * ta-4vun: where the composer's well begins (its top edge), as an alignment line: everything above it in the composer
 * (the deck's top padding, the waiting or question line, the rows above the well) is the `.chat-composer`'s static
 * strip, which the web paints UNDER the transcript's overflow, while the well (`.chat-composer-shell`, positioned) paints
 * over it. The nearest line wins when several wells are present.
 */
internal val ComposerWellTop = androidx.compose.ui.layout.HorizontalAlignmentLine(merger = { a, b -> minOf(a, b) })

/** Spec §4.2: a queued row's "Interrupt and send", its confirmation's commit, and its safe button. */
internal const val QUEUE_INTERRUPT_TAG = "queue-interrupt-and-send"
internal const val QUEUE_INTERRUPT_COMMIT_TAG = "queue-interrupt-and-send-commit"
internal const val QUEUE_KEEP_QUEUED_TAG = "queue-keep-queued"
internal const val QUEUE_HELD_TAG = "queue-held-row"
internal const val QUEUE_STATE_STEER_TAG = "queue-state-steer"
internal const val QUEUE_STATE_TURN_END_TAG = "queue-state-turn-end"
internal const val QUEUE_INTERRUPT_HEADING = "Interrupt and send now?"
internal const val QUEUE_INTERRUPT_THEN_SENT = "Then this message is sent."
internal const val QUEUE_INTERRUPT_LABEL = "Interrupt and send"

/** #273: Tether's handover instruction row (lib/queued-message.ts isHandoverRequestQueuedMessage), read-only and removable. */
internal const val HANDOVER_QUEUE_ROW_TAG = "handover-queue-row"
internal const val HANDOVER_QUEUE_ROW_DESCRIPTION = "Tether's handover instruction (read-only)"
internal const val HANDOVER_QUEUE_REMOVE_DESCRIPTION = "Remove the handover instruction to cancel the handover"
internal const val HANDOVER_QUEUE_WAIT_COPY = "Queued — Tether's handover instruction, sent after the current turn. Remove it to cancel the handover."

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
    /** T6.7: the turn "Interrupt and send" is drawn for (the active turn); null = none runs. */
    interruptTurnId: String?,
    onInterruptNow: (turnId: String) -> Unit,
    /** T13.2 r2: why "Interrupt and send" cannot send (a copy that is not live); null = it can. */
    interruptLock: String?,
    /** What a Stop would destroy right now (chat-view.tsx:1974), off the projection: the confirmation's cost sentence. */
    liveWork: QueueWait.LiveWork = QueueWait.LiveWork.None,
    /** Spec §4.2: the ONE row that may offer "Interrupt and send" ([QueueWait.interruptAndSendRowId]); null = none. */
    interruptSendId: String? = null,
    /** Spec §1 V5: the session's engine can take a message into the running turn (`capabilities.steer`). */
    steerCapable: Boolean = false,
    /** The session's engine, for the confirmation's per-engine sentences (spec §4.1, §4.1a A). */
    provider: String? = null,
    /** Spec §4.1a A: messages already sent INTO the running turn (n of them). */
    steeredInTurn: Int = 0,
    /** W34 (v151): the handover stage underway; rows then say where they go and the instruction row has no X. */
    handoverStage: com.tether.app.client.Handover.StageView? = null,
    /** W34: v151: the instruction row has no X, whatever the stage (chat-view.tsx:1632 `!handoverRequest`). */
    handoverV151: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val instructionIndex = queued.indexOfFirst { it.isHandoverRequest }
    val confirmation = listOf(
        QueueWait.interruptCostCopy(liveWork, QueueWait.foregroundCertain(provider), QueueWait.backgroundVisible(provider)),
        QueueWait.steeredStopCopy(provider, steeredInTurn),
        QUEUE_INTERRUPT_THEN_SENT,
    ).filter { it.isNotEmpty() }.joinToString(" ")
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Queued messages" },
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        for ((index, message) in queued.withIndex()) {
            androidx.compose.runtime.key(message.queueId) {
                QueuedMessageRow(
                    message = message,
                    handoverWaitLabel = com.tether.app.client.Handover.queueRowLabel(
                        handoverStage, instruction = message.isHandoverRequest, index = index, instructionIndex = instructionIndex,
                    ),
                    steerCapable = steerCapable,
                    handoverV151 = handoverV151,
                    onSave = { onSave(message.queueId, it) },
                    onRemove = { onRemove(message.queueId) },
                    onInterruptNow = onInterruptNow,
                    interruptLock = interruptLock,
                    interruptIdentity = Triple(sessionId, message.queueId, interruptTurnId),
                    interruptTurnId = interruptTurnId,
                    offerInterruptAndSend = message.queueId == interruptSendId && !message.held && !message.isHandoverRequest,
                    confirmationCopy = confirmation,
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
 * Delivery states (spec §1, §3.2): a row the server holds says in words and a glyph when it sends —
 * V4 `Clock` "Queued on the server · sends when this turn ends", or V5 `CornerDownRight` "… goes into this
 * turn at the agent's next step" (only when the engine can steer) — with a solid `line-strong` edge, no
 * spinner and no violet. A held row (`held`) never sends by itself: `TriangleAlert` + the danger edge + the
 * reason's words ([QueueWait.heldLabel]); Edit and Remove only, never "Interrupt and send". The ONE next
 * row ([offerInterruptAndSend]) offers "Interrupt and send", which always confirms with the cost sentence.
 * Status is in words and shape, never colour alone.
 */
@Composable
internal fun QueuedMessageRow(
    message: QueuedMessage,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onInterruptNow: (turnId: String) -> Unit,
    interruptLock: String?,
    /** T6.7: what "Interrupt and send" is drawn for (session, queued message, turn): a press across a change is dropped. */
    interruptIdentity: Any = Unit,
    /** T6.7: the turn it is drawn for; null = none runs, so it cannot send. */
    interruptTurnId: String? = null,
    steerCapable: Boolean = false,
    /** Spec §4.2: this is the next row to be delivered, it is the operator's own, and it is not held. */
    offerInterruptAndSend: Boolean = false,
    /** Spec §4.2: `{cost} {steer line} Then this message is sent.` */
    confirmationCopy: String = QUEUE_INTERRUPT_THEN_SENT,
    /** W34 (v151): the wait line this row reads while a handover is underway (null = its own). #273: the instruction row is read-only and removable. */
    handoverWaitLabel: String? = null,
    handoverV151: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val focusManager = LocalFocusManager.current
    val text = message.text
    val handoverRequest = message.isHandoverRequest
    val held = message.held
    var value by remember { mutableStateOf(text) }
    var editing by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    LaunchedEffect(text) { if (!editing) value = text }

    // The confirmation belongs to the turn it was asked for: a new turn (or another session) drops it.
    var confirmingInterrupt by remember(interruptIdentity) { mutableStateOf(false) }

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

    // A row that stops being the next one, or a copy that cannot interrupt, closes its confirmation.
    LaunchedEffect(offerInterruptAndSend, interruptLock != null) {
        if (!offerInterruptAndSend || interruptLock != null) confirmingInterrupt = false
    }
    val confirming = confirmingInterrupt && offerInterruptAndSend && interruptLock == null
    val steered = !held && !handoverRequest && QueueWait.isSteered(steerCapable, message)
    val waitLabel = when {
        held -> QueueWait.heldLabel(message)
        handoverWaitLabel != null -> handoverWaitLabel
        handoverRequest -> HANDOVER_QUEUE_WAIT_COPY
        else -> QueueWait.queuedLabel(steerCapable, message)
    }
    val shape = RoundedCornerShape(t.radiusMd)
    val rowHeight = 30.4.dp // 1.9rem
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tetherWell(t, shape)
            .queueEdge(if (held) t.brick else t.lineStrong)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs)
            .then(if (held) Modifier.testTag(QUEUE_HELD_TAG) else Modifier),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Box(Modifier.heightIn(min = rowHeight), contentAlignment = Alignment.Center) {
            // The glyph and the words carry the state, never colour (spec R2); static, never a spinner (issue #135).
            Icon(
                when {
                    held -> TetherIcons.TriangleAlert
                    steered && !handoverRequest -> TetherIcons.CornerDownRight
                    else -> TetherIcons.Clock
                },
                contentDescription = null,
                tint = if (held) t.brick else t.muted,
                modifier = Modifier.size(13.dp).testTag(if (held) QUEUE_HELD_TAG + "-glyph" else if (steered) QUEUE_STATE_STEER_TAG else QUEUE_STATE_TURN_END_TAG),
            )
        }
        if (handoverRequest) {
            Text(
                text,
                style = LocalTetherTypography.current.body.copy(color = t.ink, fontSize = 13.6.sp, lineHeight = 20.4.sp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = rowHeight)
                    .padding(vertical = t.css.spaceXs)
                    .semantics { contentDescription = HANDOVER_QUEUE_ROW_DESCRIPTION }
                    .testTag(HANDOVER_QUEUE_ROW_TAG),
            )
        } else BasicTextField(
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
        if (offerInterruptAndSend && !confirming) {
            QueueInterruptButton(
                onClick = { confirmingInterrupt = true },
                lock = interruptLock,
                identity = interruptIdentity,
                turnId = interruptTurnId,
            )
        }
        // v151: Stop handover (the Session links menu) is the one stop, so Tether's instruction row has no X.
        if (!(handoverRequest && handoverV151)) Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClick = onRemove)
                .semantics { contentDescription = if (handoverRequest) HANDOVER_QUEUE_REMOVE_DESCRIPTION else "Remove queued message" }
                .size(rowHeight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
        }
    }
        // `span.chat-queue-wait-label[role=status]` (spec §3.2): the live region is the label alone.
        val style = LocalTetherTypography.current.body.copy(color = t.muted, fontSize = 11.84.sp, lineHeight = 16.576.sp)
        Text(
            waitLabel,
            style = style,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (confirming) {
            QueueInterruptConfirmation(
                copy = confirmationCopy,
                onKeepQueued = { confirmingInterrupt = false },
                onCommit = { turnId ->
                    confirmingInterrupt = false
                    onInterruptNow(turnId)
                },
                lock = interruptLock,
                identity = interruptIdentity,
                turnId = interruptTurnId,
            )
        }
    }
}

/**
 * Spec §4.2: the confirmation opens under the status line inside the row — heading, `{cost} Then this message
 * is sent.`, then [Keep queued] (the safe key) and the commit in the danger recipe (`--danger` on `--danger-wash`
 * with the danger edge). It is announced politely as it opens.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QueueInterruptConfirmation(
    copy: String,
    onKeepQueued: () -> Unit,
    onCommit: (turnId: String) -> Unit,
    lock: String?,
    identity: Any,
    turnId: String?,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Text(
            QUEUE_INTERRUPT_HEADING,
            style = type.body.copy(color = t.ink, fontSize = 11.84.sp, lineHeight = 16.576.sp, fontWeight = FontWeight(650)),
        )
        Text(copy, style = type.body.copy(color = t.muted, fontSize = 11.84.sp, lineHeight = 16.576.sp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .cssSurface(RoundedCornerShape(t.radiusSm), Color.Transparent, CssBorder(1.dp, t.lineStrong))
                    .clickable(onClick = onKeepQueued)
                    .semantics(mergeDescendants = true) { contentDescription = "Keep queued" }
                    .testTag(QUEUE_KEEP_QUEUED_TAG)
                    .padding(horizontal = t.css.spaceSm),
                contentAlignment = Alignment.Center,
            ) {
                Text("Keep queued", color = t.ink, fontFamily = Manrope, fontSize = 11.84.sp, maxLines = 1)
            }
            QueueInterruptButton(
                onClick = onCommit,
                lock = lock,
                identity = "commit" to identity,
                turnId = turnId,
                danger = true,
                tag = QUEUE_INTERRUPT_COMMIT_TAG,
            )
        }
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

/** Spec §3.2: `.chat-queue-row { border-left: 2px solid var(--line-strong) }` (danger for a held row) over the well's 1px line. */
private fun Modifier.queueEdge(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color, size = Size(2.dp.toPx(), size.height))
}

/**
 * Spec §4.2 `button.chat-queue-interrupt`: `CircleStop` + "Interrupt and send", a neutral outline, 44dp. [danger] draws the
 * confirmation's commit in the danger recipe. It acts on its first tap (ta-coik.13); a press that began before the turn or
 * the row changed is dropped ([StaleTapGuard]); a copy that is not live shows it dimmed and inert (T13.2 r2).
 */
@Composable
private fun QueueInterruptButton(
    onClick: (turnId: String) -> Unit,
    lock: String?,
    identity: Any,
    turnId: String?,
    danger: Boolean = false,
    tag: String = QUEUE_INTERRUPT_TAG,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val ink = if (danger) t.brick else t.muted
    val description = "Interrupt and send — stops the current turn, then sends this message"
    val live = lock == null && turnId != null
    StaleTapGuard(identity) { guard ->
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .then(guard)
                .alpha(if (lock == null) 1f else 0.55f)
                .cssSurface(shape, if (danger) t.dangerWash else Color.Transparent, CssBorder(1.dp, if (danger) t.dangerEdge else t.lineStrong))
                .clickable(enabled = live, onClick = { if (live && turnId != null) onClick(turnId) })
                .semantics(mergeDescendants = true) {
                    contentDescription = if (lock == null) description else "$description, unavailable: $lock"
                }
                .testTag(tag)
                .padding(horizontal = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.8.dp),
        ) {
            Icon(TetherIcons.CircleStop, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
            Text(QUEUE_INTERRUPT_LABEL, color = ink, fontFamily = Manrope, fontSize = 11.84.sp, maxLines = 1)
        }
    }
}
