package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.TetherSheet
import com.tether.app.ui.components.TetherSheetSurface
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.tetherWell
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.Manrope
import androidx.compose.ui.platform.LocalConfiguration

/** tth-5u90 (spec §2.6): the sheet's parts, tagged for the behaviour tests. */
internal const val QUEUE_SHEET_TEXT_TAG = "queue-sheet-text"
internal const val QUEUE_SHEET_EDIT_TAG = "queue-sheet-edit"
internal const val QUEUE_SHEET_WAIT_TAG = "queue-sheet-wait"
internal const val QUEUE_SHEET_META_TAG = "queue-sheet-meta"
internal const val QUEUE_SHEET_WITHDRAW_LABEL = "Withdraw message"
internal const val QUEUE_SHEET_REMOVE_LABEL = "Remove"
internal const val QUEUE_SHEET_SEND_AGAIN_LABEL = "Send again"

/** The sheet's edit buffer: [value] is what the field shows, [dirty] says the operator typed since the server text last mirrored in. */
@Stable
internal class QueueEditState(initial: String) {
    var value by mutableStateOf(initial)
    var dirty by mutableStateOf(false)
    fun type(next: String) { value = next; dirty = true }
    fun revert(serverText: String) { value = serverText; dirty = false }
    /** Blank removes; changed saves; unchanged does nothing (the web's blur commit, [queueCommit]). */
    fun commit(serverText: String): QueueCommit = queueCommit(value, serverText)
}

/**
 * The message in full (D-GH-8): a [TetherSheet] (docked on a phone, a centred card from the Medium layout class) holding the
 * meta line, the row's full wait label (plain text: the row's open area owns the one live region), the attachments, the text
 * exactly as stored (read-only) or an editable field for the operator's own rows, and the row's actions. Closing it commits a
 * pending edit with no confirm; on a held row a CHANGED close re-queues it and an UNCHANGED close sends nothing. Send again
 * calls [onSave] with the current text directly (it must reach the server even when unchanged).
 */
@Composable
internal fun QueuedMessageSheet(
    message: QueuedMessage,
    summary: QueueWait.QueueRowSummary,
    waitLabel: String,
    handedOff: Boolean,
    removable: Boolean,
    offerInterruptAndSend: Boolean,
    confirmationCopy: String,
    confirming: Boolean,
    onConfirmingChange: (Boolean) -> Unit,
    interruptLock: String?,
    interruptTurnId: String?,
    interruptIdentity: Any,
    onInterruptNow: (turnId: String) -> Unit,
    onSave: (text: String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val edit = remember(message.queueId) { QueueEditState(message.text) }
    // Text changed on another device mirrors in unless this one is editing.
    LaunchedEffect(message.text) { if (!edit.dirty) edit.value = message.text }
    val editable = !message.isPeer && !message.isHandoverRequest && !handedOff
    val dismiss: () -> Unit = {
        if (!editable) onClose() else when (val c = edit.commit(message.text)) {
            QueueCommit.Remove -> onRemove()
            is QueueCommit.Save -> { onSave(c.text); onClose() }
            QueueCommit.Nothing -> onClose()
        }
    }
    TetherSheet(onDismiss = dismiss, title = summary.sheetTitle, cardWidth = queueSheetCardWidth()) {
        QueuedMessageSheetBody(
            message, summary, waitLabel, handedOff, removable, editable, edit,
            offerInterruptAndSend, confirmationCopy, confirming, onConfirmingChange, interruptLock, interruptTurnId, interruptIdentity,
            onInterruptNow, onSave, onRemove, onClose,
        )
    }
}

/** The sheet's surface drawn in place (no window): what the boards shoot, since a Dialog is not in a node capture. */
@Composable
internal fun QueuedMessageSheetSurface(
    message: QueuedMessage,
    summary: QueueWait.QueueRowSummary,
    waitLabel: String,
    modifier: Modifier = Modifier,
    handedOff: Boolean = false,
    removable: Boolean = true,
    offerInterruptAndSend: Boolean = false,
    docked: Boolean = currentLayoutClass() == TetherLayoutClass.Phone,
) {
    val editable = !message.isPeer && !message.isHandoverRequest && !handedOff
    TetherSheetSurface(title = summary.sheetTitle, modifier = modifier, docked = docked, onClose = {}, cardWidth = queueSheetCardWidth()) {
        QueuedMessageSheetBody(
            message, summary, waitLabel, handedOff, removable, editable, remember { QueueEditState(message.text) },
            offerInterruptAndSend, QUEUE_INTERRUPT_THEN_SENT, false, {}, null, "t1", Unit, {}, {}, {}, {},
        )
    }
}

@Composable
private fun queueSheetCardWidth(): Dp {
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    return if (phone) 352.dp else minOf(560, LocalConfiguration.current.screenWidthDp - 48).coerceAtLeast(0).dp
}

@Composable
private fun ColumnScope.QueuedMessageSheetBody(
    message: QueuedMessage,
    summary: QueueWait.QueueRowSummary,
    waitLabel: String,
    handedOff: Boolean,
    removable: Boolean,
    editable: Boolean,
    edit: QueueEditState,
    offerInterruptAndSend: Boolean,
    confirmationCopy: String,
    confirming: Boolean,
    onConfirmingChange: (Boolean) -> Unit,
    interruptLock: String?,
    interruptTurnId: String?,
    interruptIdentity: Any,
    onInterruptNow: (turnId: String) -> Unit,
    onSave: (text: String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val focusManager = LocalFocusManager.current
    val held = message.held || handedOff
    // IME Done / Enter = queueCommit (blank removes, changed saves, unchanged does nothing).
    fun commitNow() {
        when (val c = edit.commit(message.text)) {
            QueueCommit.Remove -> onRemove()
            is QueueCommit.Save -> { onSave(c.text); edit.dirty = false }
            QueueCommit.Nothing -> Unit
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = t.css.spaceSm), verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            Icon(
                when (summary.glyph) {
                    QueueWait.QueueRowGlyph.AlertTriangle -> TetherIcons.TriangleAlert
                    QueueWait.QueueRowGlyph.CornerDownRight -> TetherIcons.CornerDownRight
                    QueueWait.QueueRowGlyph.ArrowRightLeft -> TetherIcons.ArrowRightLeft
                    QueueWait.QueueRowGlyph.Clock -> TetherIcons.Clock
                },
                contentDescription = null,
                tint = if (summary.glyph == QueueWait.QueueRowGlyph.AlertTriangle) t.brick else t.muted,
                modifier = Modifier.size(13.dp),
            )
            Text(summary.meta, style = type.body.copy(color = if (held) t.brick else t.muted, fontSize = 12.8.sp, fontWeight = FontWeight(650)), modifier = Modifier.testTag(QUEUE_SHEET_META_TAG))
        }
        // The full wait label as plain text: a label change is announced once, by the row's open area.
        Text(waitLabel, style = type.body.copy(color = t.muted, fontSize = 12.8.sp, lineHeight = 18.sp), modifier = Modifier.fillMaxWidth().testTag(QUEUE_SHEET_WAIT_TAG))
        message.attachments?.takeIf { it.isNotEmpty() }?.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                Icon(TetherIcons.Paperclip, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp))
                Text(a.name, style = type.body.copy(color = t.muted, fontSize = 12.8.sp), maxLines = 1)
            }
        }
        if (editable) {
            val shape = RoundedCornerShape(t.radiusMd)
            BasicTextField(
                value = edit.value,
                onValueChange = { edit.type(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .tetherWell(t, shape)
                    .heightIn(min = 96.dp)
                    .onPreviewKeyEvent { event ->
                        // Enter (no Shift) commits like IME Done; Escape reverts without saving (queueRowKey).
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            (event.key == androidx.compose.ui.input.key.Key.Enter || event.key == androidx.compose.ui.input.key.Key.NumPadEnter) &&
                            !event.isShiftPressed
                        ) {
                            commitNow()
                            focusManager.clearFocus()
                            true
                        } else {
                            queueRowKey(event, focusManager) { edit.revert(message.text) }
                        }
                    }
                    .semantics { contentDescription = "Edit queued message" }
                    .testTag(QUEUE_SHEET_EDIT_TAG)
                    .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceSm),
                textStyle = type.body.copy(color = t.ink, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(t.violet),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commitNow()
                    focusManager.clearFocus()
                }),
            )
        } else {
            SelectionContainer {
                Text(
                    message.text,
                    style = type.body.copy(color = t.ink, fontSize = 14.72.sp, lineHeight = 22.8.sp),
                    modifier = Modifier.fillMaxWidth().testTag(QUEUE_SHEET_TEXT_TAG),
                )
            }
        }
        // The footer: the row's own actions; a footer with no action is not drawn (the handover instruction while one is underway).
        val withdrawLabel = if (message.isPeer) QUEUE_SHEET_WITHDRAW_LABEL else QUEUE_SHEET_REMOVE_LABEL
        val canSendAgain = editable && message.held
        val hasActions = removable || canSendAgain || offerInterruptAndSend
        if (hasActions) {
            Box(Modifier.fillMaxWidth().padding(top = t.css.spaceXs).height(1.dp).background(t.line))
            if (confirming && offerInterruptAndSend) {
                QueueInterruptConfirmation(
                    copy = confirmationCopy,
                    onKeepQueued = { onConfirmingChange(false) },
                    onCommit = { turnId ->
                        onConfirmingChange(false)
                        onInterruptNow(turnId)
                        onClose()
                    },
                    lock = interruptLock,
                    identity = interruptIdentity,
                    turnId = interruptTurnId,
                )
            } else {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(bottom = t.css.spaceSm),
                    horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                ) {
                    if (removable) SheetAction(withdrawLabel, onRemove)
                    if (canSendAgain) SheetAction(QUEUE_SHEET_SEND_AGAIN_LABEL, { onSave(edit.value.trim()); edit.dirty = false; onClose() }, enabled = edit.value.isNotBlank())
                    if (offerInterruptAndSend) QueueInterruptButton(
                        onClick = { onConfirmingChange(true) },
                        lock = interruptLock,
                        identity = interruptIdentity,
                        turnId = interruptTurnId,
                    )
                }
            }
        }
    }
}

/** One footer button (`.step-sheet-action`, 44 dp): its accessible name is its visible label. */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(t.radiusSm), Color.Transparent, CssBorder(1.dp, t.lineStrong))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = t.css.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) t.ink else t.muted, fontFamily = Manrope, fontSize = 13.sp, maxLines = 1)
    }
}
