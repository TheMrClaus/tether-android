package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import com.tether.app.client.Handover
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey

/** #273: the Hand over confirmation's two keys. */
const val HANDOVER_CONFIRM_TAG = "handover-confirm"
const val HANDOVER_CANCEL_TAG = "handover-cancel"

/**
 * #273 (dashboard.tsx fe4aa8c0 :1227-1248, handover-ui.mjs): "Hand this session over to a new lead?", in the owner's words
 * ([Handover.DIALOG_TITLE], [Handover.DIALOG_BODY]), on End session's chrome. Cancel takes initial focus (the safe key);
 * Hand over is the primary key (not danger, no icon) and acts on its first activation ([StaleTapGuard], as End session).
 * Back and the scrim cancel and send nothing. While [eligible] is false (the row state, ignoring this device's own
 * request, stopped being enabled, or the session left the list) the dialog closes itself through [onCancel], sending
 * nothing. Only the Hand over tap calls [onConfirm], and only while [endable] (the host's same-server rule).
 */
@Composable
fun HandOverDialog(
    /** What the confirmation was opened for (the session and the server origin): the stale-tap identity. */
    identity: Any,
    eligible: Boolean,
    endable: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    val dismiss by rememberUpdatedState(onCancel)
    LaunchedEffect(eligible) { if (!eligible) dismiss() }
    TetherDialog(
        onDismiss = onCancel,
        title = Handover.DIALOG_TITLE,
        footer = {
            // Inside the dialog's own composition, so the key is attached when focus is asked for.
            val input = androidx.compose.ui.platform.LocalInputModeManager.current
            // Cancel takes focus whatever opened the dialog (the web's showModal does). A clickable key is focusable only in
            // keyboard input mode, so ask for it, focus, then give a touch user's mode back; the ring follows the mode.
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { } // the key is placed and attached before focus is asked for
                val prior = input.inputMode
                input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                runCatching { cancelFocus.requestFocus() }
                if (prior != androidx.compose.ui.input.InputMode.Keyboard) input.requestInputMode(prior)
            }
            val keyboardMode = input.inputMode == androidx.compose.ui.input.InputMode.Keyboard
            TetherKey(
                onClick = onCancel,
                classes = KeyClasses.ButtonSecondary,
                label = "Cancel",
                showFocusRing = keyboardMode,
                modifier = Modifier.focusRequester(cancelFocus).testTag(HANDOVER_CANCEL_TAG),
            )
            StaleTapGuard(identity) { guard ->
                TetherKey(
                    onClick = { if (endable && eligible) onConfirm() },
                    classes = KeyClasses.ButtonPrimary,
                    label = "Hand over",
                    enabled = endable,
                    modifier = guard.testTag(HANDOVER_CONFIRM_TAG),
                )
            }
        },
    ) {
        TetherDialogText(Handover.DIALOG_BODY)
    }
}
