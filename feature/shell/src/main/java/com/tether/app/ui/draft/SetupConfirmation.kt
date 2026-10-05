package com.tether.app.ui.draft

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tether.app.client.DraftSetupConfirmation
import com.tether.app.ui.components.CommandList
import com.tether.app.ui.components.ConsentPanel
import com.tether.app.ui.components.ConsentText
import com.tether.app.ui.components.HiddenCharactersWarning
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.consentSentence
import com.tether.app.ui.theme.LocalTetherTokens

/** components/draft-composer.tsx SetupConfirmation's words (1bf4a465), verbatim. */
object SetupConfirmationCopy {
    const val TITLE = "This project's setup will run on this host"
    const val SETUP_LABEL = "Setup — runs once, right after the checkout is created, before the first turn"
    const val TEARDOWN_LABEL =
        "Teardown — declared for archive. It does NOT run on this approval: when the session is ended you are asked again, because the session may change the files it runs"
    const val PORT_SCRIPT_LABEL =
        "Service port script — executed each time a service of this session is started, to pick its port (only while its contents still match what you approve)"
    const val CANCEL = "Cancel"
    const val RUN = "Run setup and start"

    /** `what`: "Pull request #N" | "Existing branch <b>" | "New branch". */
    fun what(confirmation: DraftSetupConfirmation): String = when (confirmation.approval.mode) {
        "checkout-pr" -> "Pull request #${confirmation.prNumber ?: "?"}"
        "checkout-branch" -> "Existing branch ${confirmation.branch.orEmpty()}".trim()
        else -> "New branch"
    }
}

/**
 * ta-m7ef, draft-composer.tsx SetupConfirmation (1bf4a465): the operator's approval of a project's setup,
 * shown only when the server resolved setup/teardown commands (or a service port script) for the ref THIS
 * create will use, listing exactly those with the full commit they come from. The consent it sends is
 * bound to that commit and those commands. Each command is its own numbered block and every character a
 * reviewer could not see is drawn as a U+XXXX token; teardown is shown for information only (it needs its
 * own approval when the session is ended).
 */
@Composable
internal fun SetupConfirmation(confirmation: DraftSetupConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalTetherTokens.current
    val approval = confirmation.approval
    ConsentPanel(
        title = SetupConfirmationCopy.TITLE,
        modifier = Modifier.padding(vertical = t.css.spaceXs).testTag(DraftComposerTags.SetupConfirm),
        actions = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = SetupConfirmationCopy.CANCEL, modifier = Modifier.testTag(DraftComposerTags.SetupCancel))
            TetherKey(onClick = onConfirm, classes = KeyClasses.ButtonPrimary, label = SetupConfirmationCopy.RUN, modifier = Modifier.testTag(DraftComposerTags.SetupRun))
        },
    ) {
        ConsentText(
            consentSentence(
                "${SetupConfirmationCopy.what(confirmation)} from ", approval.baseRef, " at commit ", approval.commit,
                " declares commands in its committed ", "tether.json", ". They run outside the agent's sandbox. Approve only what you have read.",
            ),
        )
        if (approval.hiddenCharacters) HiddenCharactersWarning()
        if (approval.commands.isNotEmpty()) CommandList(SetupConfirmationCopy.SETUP_LABEL, approval.commands, tagPrefix = "draft-setup-command")
        if (approval.teardown.isNotEmpty()) CommandList(SetupConfirmationCopy.TEARDOWN_LABEL, approval.teardown, tagPrefix = "draft-teardown-command")
        approval.portScript?.takeIf { it.isNotEmpty() }?.let { CommandList(SetupConfirmationCopy.PORT_SCRIPT_LABEL, listOf(it), tagPrefix = "draft-port-script") }
        val sha = approval.portScriptSha256
        if (!approval.portScript.isNullOrEmpty() && !sha.isNullOrEmpty()) {
            ConsentText(consentSentence("Port script contents (SHA-256): ", sha))
        }
    }
}
