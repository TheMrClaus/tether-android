package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tether.app.client.EndConfirmation
import com.tether.app.client.LabelText
import com.tether.app.ui.components.CommandList
import com.tether.app.ui.components.ConsentText
import com.tether.app.ui.components.ConsentWarning
import com.tether.app.ui.components.HiddenCharactersWarning
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.consentSentence
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
 * ta-coik.22: the server an End session confirmation (or an unconfirmed End) is bound to: the live
 * link's origin, else the configured server's, so one opened while offline still names its server.
 */
fun endSessionServer(linkOrigin: String?, configuredServer: String?): String? =
    linkOrigin ?: com.tether.app.client.serverOrigin(configuredServer)

/**
 * ta-coik.22: whether the confirmation's End session key is live. The web's confirm key is never
 * disabled (dashboard.tsx 90fbb9f :1909): offline, catching up or a saved copy, its click goes to
 * `send`, which says "reconnecting, not sent" when the socket is closed. Here too. The one state
 * kept disabled is a link to ANOTHER server than the one the dialog was opened for: the web cannot
 * reach it (a page is bound to its origin; another server is another page, which drops the dialog),
 * and a confirmation opened for server A must never end a session on server B.
 */
fun endConfirmLive(drawnFor: String?, linkOrigin: String?): Boolean =
    drawnFor != null && (linkOrigin == null || linkOrigin == drawnFor)

/**
 * T6.7: the End session confirmation of the session header (chat header and shell header alike).
 * ta-coik.13: its End session key acts on the first tap, as on the web (dashboard.tsx 90fbb9f :1909,
 * no arm delay); a press across a change of [identity] is dropped ([StaleTapGuard]) and touches
 * through an overlay are refused. Only its tap calls [onConfirm], and only while [endable] (the
 * host's same-server rule, [endConfirmLive]). ta-coik.22: like the web's `<dialog>` (dashboard.tsx
 * 90fbb9f :1172-1185, :1902-1911), it stays open until Cancel, a dismissal or End session: a link
 * that drops or the app going to the background does not close it, and its key stays live then.
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

/** ta-m7ef: hooks for the teardown confirmation's behaviour tests and goldens. */
object TeardownConfirmTags {
    const val Dialog = "teardown-confirm"
    const val Run = "teardown-run"
    const val Skip = "teardown-skip"
    const val Cancel = "teardown-cancel"
    const val Changed = "teardown-changed"
    const val Stops = "teardown-stops-sessions"
    const val Message = "teardown-message"
}

/** components/setup-commands.tsx TeardownConfirmDialog's words (1bf4a465), verbatim. */
object TeardownConfirmCopy {
    fun title(sessionName: String?): String {
        val name = LabelText.label(sessionName)
        return "End ${if (name.isEmpty()) "this session" else "\u2068$name\u2069"}: run its teardown?"
    }

    const val COMMANDS_LABEL = "Teardown — runs once, now, before the checkout is removed"
    const val CHANGED = "The checkout has changed since it was approved (new commits, edits or new files)."
    const val UNKNOWN = "The checkout may have changed since it was approved: Tether could not check it, so treat it as changed."
    const val NO_TEARDOWN = "This session's teardown will not run."
    const val CANCEL = "Cancel"
    const val SKIP = "End without teardown"
    const val RUN = "Run teardown and end"
}

/**
 * ta-m7ef, components/setup-commands.tsx TeardownConfirmDialog (1bf4a465): ending an isolated session runs
 * its teardown on this host only if the owner approves it HERE. The commands are the ones approved at create,
 * but they run the checkout as the session left it, outside the agent's sandbox. With an approval: the
 * commands (numbered, hidden characters made visible), the warnings (changed or unknown checkout, hidden
 * characters, the other sessions the end would stop) and the three keys. With none ([EndConfirmation.message]
 * says why no teardown can run): only "End without teardown" and Cancel. It is the confirmation of an
 * isolated session's end, so the generic "End session?" is not shown as well.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeardownConfirmDialog(
    confirmation: EndConfirmation,
    onRun: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val approval = confirmation.approval
    TetherDialog(
        onDismiss = onCancel,
        title = TeardownConfirmCopy.title(confirmation.sessionName),
    ) {
        Column(Modifier.testTag(TeardownConfirmTags.Dialog), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (approval != null) {
                ConsentText(
                    consentSentence(
                        "This worktree's committed ", "tether.json", " (commit ", approval.commit.orEmpty(),
                        ") declares teardown commands. They run on this host, outside the agent's sandbox, in the checkout as the session left it — " +
                            "the session could have changed the files these commands run. Approve only if you trust what is there now.",
                    ),
                )
                // r4: unknown (null) is treated as changed, never as unchanged.
                if (approval.mayHaveChanged) {
                    ConsentWarning(if (approval.checkoutChanged == true) TeardownConfirmCopy.CHANGED else TeardownConfirmCopy.UNKNOWN, tag = TeardownConfirmTags.Changed)
                }
                if (approval.hiddenCharacters) HiddenCharactersWarning()
                approval.stopsSessionsNotice?.let { ConsentWarning(it, tag = TeardownConfirmTags.Stops) }
                CommandList(TeardownConfirmCopy.COMMANDS_LABEL, approval.commands, tagPrefix = "teardown-command")
            } else {
                ConsentText(confirmation.message ?: TeardownConfirmCopy.NO_TEARDOWN, tag = TeardownConfirmTags.Message)
            }
            // The three keys wrap under each other on a narrow screen: a long legend is shown whole, never cut to its verb.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = TeardownConfirmCopy.CANCEL, modifier = Modifier.testTag(TeardownConfirmTags.Cancel))
                TetherKey(onClick = onSkip, classes = KeyClasses.ButtonSecondary, label = TeardownConfirmCopy.SKIP, modifier = Modifier.testTag(TeardownConfirmTags.Skip))
                if (approval != null) {
                    TetherKey(onClick = onRun, classes = KeyClasses.ButtonDanger, label = TeardownConfirmCopy.RUN, modifier = Modifier.testTag(TeardownConfirmTags.Run))
                }
            }
        }
    }
}
