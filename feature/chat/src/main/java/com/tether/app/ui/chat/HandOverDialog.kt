package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.withStyle
import com.tether.app.ui.icons.TetherIcons
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.windowWidthDp
import com.tether.app.client.Handover
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey

/** #273: the Hand over confirmation's two keys. */
const val HANDOVER_CONFIRM_TAG = "handover-confirm"
const val HANDOVER_CANCEL_TAG = "handover-cancel"
const val HANDOVER_CHANGE_TAG = "handover-change"
const val HANDOVER_NO_ENGINE_TAG = "handover-no-engine"

/** W34 (studio.css:1103): the three-key dialog is 500dp wide, the family 440. */
val HANDOVER_DIALOG_WIDTH = 500.dp

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
    /**
     * W34 (server protocol >= 151, [Handover.v151]): the web's three actions. Below it this is the W33 two-key dialog,
     * byte for byte (its body copy, "Hand over", no Change settings).
     */
    v151: Boolean = false,
    /** v151: "Change settings…": true = the handover composer opened (the host closes this dialog); false = no engine, say so. */
    onChangeSettings: () -> Boolean = { false },
    /** The web's `max-width: 40rem` cut ([windowWidthDp] <= 640): the stacked footer. */
    phone: Boolean = windowWidthDp() <= DRAFT_SHEET_PHONE_MAX_DP,
) {
    val cancelFocus = remember { FocusRequester() }
    val dismiss by rememberUpdatedState(onCancel)
    LaunchedEffect(eligible) { if (!eligible) dismiss() }
    if (v151) {
        HandOverDialogV151(identity, endable, eligible, onConfirm, onCancel, onChangeSettings, phone, cancelFocus)
        return
    }
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
            // Web (:focus-visible): the ring shows after a key, not after a pointer open. Decided from the mode at open (a
            // keyboard open) and from a real key event in the dialog, never from the mode after our own forced switch.
            var keyed by remember { androidx.compose.runtime.mutableStateOf(input.inputMode == androidx.compose.ui.input.InputMode.Keyboard) }
            TetherKey(
                onClick = onCancel,
                classes = KeyClasses.ButtonSecondary,
                label = "Cancel",
                showFocusRing = keyed,
                modifier = Modifier.onPreviewKeyEvent { keyed = true; false }.focusRequester(cancelFocus).testTag(HANDOVER_CANCEL_TAG),
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

/** The web's 40rem phone cut, as the New Session sheet's (`DRAFT_SHEET_MAX_WIDTH_DP`). */
internal const val DRAFT_SHEET_PHONE_MAX_DP = 640

/**
 * W34 (dashboard.tsx 62ceca46 :2198-2227, studio.css:1103-1115): the three actions. DOM / focus / TalkBack order is
 * Cancel -> Change settings... -> Hand over as is on every width; on a phone they are DRAWN stacked, Hand over as is on
 * top and Cancel nearest the thumb (`column-reverse`), the order pinned with traversal indexes and focus properties.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HandOverDialogV151(
    identity: Any,
    endable: Boolean,
    eligible: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onChangeSettings: () -> Boolean,
    phone: Boolean,
    cancelFocus: FocusRequester,
) {
    val t = com.tether.app.ui.theme.LocalTetherTokens.current
    val changeFocus = remember { FocusRequester() }
    val asIsFocus = remember { FocusRequester() }
    var noEngine by remember { androidx.compose.runtime.mutableStateOf(false) }
    val strong = androidx.compose.ui.text.SpanStyle(color = t.ink, fontWeight = androidx.compose.ui.text.font.FontWeight(680))
    TetherDialog(
        onDismiss = onCancel,
        title = Handover.DIALOG_TITLE,
        width = HANDOVER_DIALOG_WIDTH,
        footerContent = {
            val input = androidx.compose.ui.platform.LocalInputModeManager.current
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                val prior = input.inputMode
                input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                runCatching { cancelFocus.requestFocus() }
                if (prior != androidx.compose.ui.input.InputMode.Keyboard) input.requestInputMode(prior)
            }
            // Web (:focus-visible): the ring shows after a key, not after a pointer open. Decided from the mode at open (a
            // keyboard open) and from a real key event in the dialog, never from the mode after our own forced switch.
            var keyed by remember { androidx.compose.runtime.mutableStateOf(input.inputMode == androidx.compose.ui.input.InputMode.Keyboard) }
            val fill = if (phone) Modifier.fillMaxWidth() else Modifier
            val cancel: @Composable () -> Unit = {
                TetherKey(
                    onClick = onCancel,
                    classes = KeyClasses.ButtonSecondary,
                    label = Handover.CANCEL_LABEL,
                    showFocusRing = keyed,
                    modifier = fill
                        .onPreviewKeyEvent { keyed = true; false }
                        .focusProperties { next = changeFocus; previous = asIsFocus }
                        .focusRequester(cancelFocus)
                        .testTag(HANDOVER_CANCEL_TAG),
                )
            }
            val change: @Composable () -> Unit = {
                TetherKey(
                    onClick = { if (!onChangeSettings()) noEngine = true },
                    classes = KeyClasses.ButtonSecondary,
                    label = Handover.CHANGE_LABEL,
                    icon = TetherIcons.SlidersHorizontal,
                    iconSize = 16.dp,
                    modifier = fill
                        .focusProperties { next = asIsFocus; previous = cancelFocus }
                        .focusRequester(changeFocus)
                        .testTag(HANDOVER_CHANGE_TAG),
                )
            }
            val asIs: @Composable () -> Unit = {
                StaleTapGuard(identity) { guard ->
                    TetherKey(
                        onClick = { if (endable && eligible) onConfirm() },
                        classes = KeyClasses.ButtonPrimary,
                        label = Handover.AS_IS_LABEL,
                        enabled = endable,
                        modifier = fill
                            .focusProperties { next = cancelFocus; previous = changeFocus }
                            .focusRequester(asIsFocus)
                            .then(guard)
                            .testTag(HANDOVER_CONFIRM_TAG),
                    )
                }
            }
            if (phone) {
                // Drawn top to bottom: Hand over as is / Change settings... / Cancel; read Cancel / Change / As is.
                Column(
                    Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.semantics { traversalIndex = 2f; isTraversalGroup = true }.testTag("handover-slot-as-is")) { asIs() }
                    Box(Modifier.semantics { traversalIndex = 1f; isTraversalGroup = true }.testTag("handover-slot-change")) { change() }
                    Box(Modifier.semantics { traversalIndex = 0f; isTraversalGroup = true }.testTag("handover-slot-cancel")) { cancel() }
                }
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cancel()
                    change()
                    asIs()
                }
            }
        },
    ) {
        TetherDialogText(Handover.DIALOG_BODY_V151)
        TetherDialogText(
            androidx.compose.ui.text.buildAnnotatedString {
                withStyle(strong) { append(Handover.CHOICE_AS_IS) }
                append(Handover.CHOICE_TAIL_AS_IS)
                withStyle(strong) { append(Handover.CHOICE_CHANGE) }
                append(Handover.CHOICE_TAIL_CHANGE)
            },
            Modifier.padding(top = 10.dp),
        )
        if (noEngine) {
            // role=alert: announced as soon as it appears.
            TetherDialogText(
                androidx.compose.ui.text.AnnotatedString(Handover.NO_ENGINE_LINE),
                Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive }.testTag(HANDOVER_NO_ENGINE_TAG),
            )
        }
    }
}
