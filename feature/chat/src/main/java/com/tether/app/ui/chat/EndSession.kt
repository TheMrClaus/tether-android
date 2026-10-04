package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import com.tether.app.client.LabelText
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons

/** T6.7: the End session confirmation's End session key. */
const val END_SESSION_CONFIRM_TAG = "end-session-confirm"

/**
 * dashboard.tsx:1902-1911 `.confirm-dialog`: "End session?", then "<name> — its running process
 * will stop.", Cancel and the danger End session key. The name is the server's: cleaned
 * ([LabelText.label]) and isolated, so right-to-left text in it cannot reorder the sentence.
 */
fun endSessionBody(sessionName: String?): String {
    val name = LabelText.label(sessionName)
    return if (name.isEmpty()) "Its running process will stop." else "\u2068$name\u2069 — its running process will stop."
}

/**
 * T6.7: the End session confirmation of the session header (chat header and shell header alike).
 * ta-coik.13: its End session key acts on the first tap, as on the web (dashboard.tsx 90fbb9f :1909,
 * no arm delay); a press across a change of [identity] is dropped ([StaleTapGuard]) and touches
 * through an overlay are refused. Only its tap calls [onConfirm], and only while [endable] (the
 * host's live-copy and same-server rule, T13.2). ta-coik.22: like the web's `<dialog>` (dashboard.tsx
 * 90fbb9f :1172-1185, :1902-1911), it stays open until Cancel, a dismissal or End session: a link
 * that drops or the app going to the background does not close it. While [endable] is false its key
 * is disabled; it is live again once the copy is (the [identity] still binds it to its session and server).
 */
@Composable
fun EndSessionDialog(
    sessionName: String?,
    /** What the confirmation was opened for (the session and the server origin): the stale-tap identity. */
    identity: Any,
    endable: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    TetherDialog(
        onDismiss = onCancel,
        title = "End session?",
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel")
            StaleTapGuard(identity) { guard ->
                TetherKey(
                    onClick = { if (endable) onConfirm() },
                    classes = KeyClasses.ButtonDanger,
                    label = "End session",
                    icon = TetherIcons.CircleStop,
                    enabled = endable,
                    modifier = guard.testTag(END_SESSION_CONFIRM_TAG),
                )
            }
        },
    ) {
        TetherDialogText(endSessionBody(sessionName))
    }
}
