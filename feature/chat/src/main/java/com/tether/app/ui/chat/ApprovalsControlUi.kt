package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.tether.app.client.ApprovalsControl
import com.tether.app.ui.icons.TetherIcons

/**
 * Reasonix's Approvals control (Ask / Auto / YOLO), drawn once for every host: the new-session composer's live row and
 * its phone settings sheet, and later a proposal card. It owns no state: the host passes the model
 * ([com.tether.app.client.DraftApprovals.control] or [com.tether.app.client.DraftApprovals.of]), the enabled flag and
 * the pick callback. It lives in `:feature:chat` so a card there reaches it directly and `:feature:shell` (which
 * depends on chat) reuses it; the model sits in `:core:net`, below both.
 *
 * Words, hints and the danger row come from the model (the web's own copy). YOLO takes the warning tone and always
 * shows its word.
 */
object ApprovalsControlTags {
    const val Pill = "draft-approvals"
    const val HubLabel = "Approvals"
}

/** The leading glyph of the chosen row: ShieldCheck Ask, Shield Auto, Zap YOLO (approvals-select.tsx ApprovalIcon). */
fun approvalsIcon(value: String): ImageVector = when (value) {
    "ask" -> TetherIcons.ShieldCheck
    "yolo" -> TetherIcons.Zap
    else -> TetherIcons.Shield
}

/** The pill with its drop-up of three rows (the composer's live row, beside Mode). */
@Composable
fun ApprovalsPill(
    control: ApprovalsControl,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    testTag: String = ApprovalsControlTags.Pill,
) {
    ControlSelect(
        control = control.select,
        name = "Approvals",
        enabled = enabled,
        lockCopy = null,
        onSelect = onSelect,
        danger = control.danger,
        icon = approvalsIcon(control.value),
        testTag = testTag,
    )
}

/** The settings hub's row: the shield glyph, "Approvals" and the chosen word; [onOpen] opens the options view. */
@Composable
fun ApprovalsHubRow(control: ApprovalsControl, onOpen: () -> Unit) {
    HubRow(approvalsIcon(control.value), ApprovalsControlTags.HubLabel, control.word, danger = control.danger, onClick = onOpen)
}

/** The options view's rows: a pick other than the current one goes to [onSelect]; [onDone] runs after any tap. */
@Composable
fun ApprovalsOptionRows(control: ApprovalsControl, enabled: Boolean = true, onSelect: (String) -> Unit, onDone: () -> Unit = {}) {
    control.select.options.forEach { option ->
        ControlOptionRow(option.copy(disabled = option.disabled || !enabled), selected = option.value == control.value, armedRow = false, divider = false) {
            if (option.value != control.value) onSelect(option.value)
            onDone()
        }
    }
}
