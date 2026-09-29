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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tether.app.ui.theme.ThemeFamily

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
    val studio: Boolean,
)

@Composable
internal fun composerMetrics(): ComposerMetrics {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    return ComposerMetrics(
        phone = phone,
        touchKeys = phone || LocalConfiguration.current.screenWidthDp < 1024,
        studio = t.skin.family == ThemeFamily.Studio,
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
    m.studio && m.phone -> base.copy(fontSize = 16.sp, lineHeight = 25.6.sp)
    m.studio -> base.copy(fontSize = 14.8.sp, lineHeight = 23.68.sp)
    m.phone -> base.copy(fontSize = 16.sp, lineHeight = 24.sp) // ≥16px: 8512 (the iOS zoom rule)
    else -> base.copy(fontSize = 14.4.sp, lineHeight = 21.6.sp)
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
) {
    val t = LocalTetherTokens.current
    val style = composerTextStyle(LocalTetherTypography.current.body, metrics)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = screenHeight * (if (metrics.phone) 0.3f else 0.6f)
    val (minHeight, padding) = when {
        metrics.studio && metrics.phone -> 52.8.dp to Pad(14.dp, 14.dp, 14.dp, 8.dp)
        metrics.studio -> 67.2.dp to Pad(16.dp, 16.dp, 16.dp, 8.dp)
        metrics.phone -> 44.dp to Pad(t.css.spaceMd, 9.6.dp, t.css.spaceMd, 9.6.dp)
        else -> 40.dp to Pad(t.css.spaceMd, t.css.spaceSm, t.css.spaceMd, 5.6.dp)
    }
    // Chromium's default ::placeholder colour (#757575, measured in every instrument web shot);
    // Studio sets `--faint` (studio.css:481-482).
    val placeholderColor = if (metrics.studio) t.faint else Color(0xFF757575)
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
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val surface = if (metrics.studio) {
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
    } else {
        Modifier.tetherWell(t, RoundedCornerShape(t.radiusMd), inputFocused)
    }
    Column(modifier.fillMaxWidth().then(surface)) { content() }
}

/** T13.2 r2: a queued row's "Interrupt now". */
internal const val QUEUE_INTERRUPT_TAG = "queue-interrupt-now"

/** T6.7 r2: why the head row's "Interrupt now" is locked while the turn is being interrupted. */
internal const val QUEUE_HEAD_CANCELLING_COPY = "Interrupting — this message sends as soon as the turn stops."

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
    /**
     * T6.7 r2: the active turn is already being interrupted. The head row's message flushes into a
     * new turn the moment it stops, which this client may not see before the server has started it;
     * an "Interrupt now" tapped then could stop that new turn, so the head row's key stays locked.
     */
    turnCancelling: Boolean = false,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Queued messages" },
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        for ((index, message) in queued.withIndex()) {
            androidx.compose.runtime.key(message.queueId) {
                QueuedMessageRow(
                    text = message.text,
                    atToolBoundary = message.atToolBoundary,
                    onSave = { onSave(message.queueId, it) },
                    onRemove = { onRemove(message.queueId) },
                    onInterruptNow = onInterruptNow,
                    interruptLock = interruptLock ?: if (index == 0 && turnCancelling) QUEUE_HEAD_CANCELLING_COPY else null,
                    interruptIdentity = Triple(sessionId, message.queueId, interruptTurnId),
                    interruptTurnId = interruptTurnId,
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
 * `flushMode: "next-call"` (issue #183) rows say they send at the next tool boundary and offer
 * "Interrupt now". Status is in words (the icon's description), never colour alone.
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
) {
    val t = LocalTetherTokens.current
    val focusManager = LocalFocusManager.current
    var value by remember { mutableStateOf(text) }
    var editing by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    LaunchedEffect(text) { if (!editing) value = text }

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

    val status = if (atToolBoundary) "Queued — sends at the next tool boundary." else "Queued — sends after the current turn."
    val shape = RoundedCornerShape(t.radiusMd)
    val rowHeight = 30.4.dp // 1.9rem
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tetherWell(t, shape)
            .queueEdge(t.violetStrong)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Box(Modifier.heightIn(min = rowHeight), contentAlignment = Alignment.Center) {
            Icon(TetherIcons.Loader, contentDescription = status, tint = t.violet, modifier = Modifier.size(13.dp))
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
        if (atToolBoundary) {
            QueueInterruptNow(onInterruptNow, interruptLock, interruptIdentity, interruptTurnId)
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
 * on a row waiting for a tool boundary. 1.9rem tall, 2.75rem under a coarse pointer.
 */
@Composable
private fun QueueInterruptNow(onClick: (turnId: String) -> Unit, lock: String?, identity: Any, turnId: String?) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val what = "Interrupt now — stops the current turn, its open tool call and its background tasks, then sends this"
    // T6.7: bound to the turn it is drawn for and armed like the composer's Interrupt key (500 ms,
    // re-armed by a new turn or a move, no overlay touches); drawn as before while it arms.
    val arming = rememberArmedControl(identity, lock == null && turnId != null)
    val armed = arming.armed && lock == null && turnId != null
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .then(arming.modifier)
            // T13.2 r2: a copy that is not live cannot interrupt: shown, dimmed, and inert.
            .alpha(if (lock == null) 1f else 0.55f)
            .cssSurface(shape, Color.Transparent, CssBorder(1.dp, t.lineStrong))
            .clickable(enabled = armed, onClick = { if (armed && turnId != null) onClick(turnId) })
            .semantics(mergeDescendants = true) {
                contentDescription = if (lock == null) what else "$what, unavailable: $lock"
            }
            .testTag(QUEUE_INTERRUPT_TAG)
            .padding(horizontal = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.8.dp),
    ) {
        Icon(TetherIcons.CircleStop, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
        Text("Interrupt now", color = t.muted, fontFamily = Manrope, fontSize = 11.84.sp, maxLines = 1)
    }
}
