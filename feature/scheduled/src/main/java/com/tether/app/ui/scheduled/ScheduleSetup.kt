package com.tether.app.ui.scheduled

import com.tether.app.client.CreateErrorReply
import com.tether.app.client.ScheduleConsentStep
import com.tether.app.client.SetupApproval
import com.tether.app.client.SetupConsentSteps
import com.tether.app.client.WorktreeSourceReply
import com.tether.app.protocol.OrNull
import com.tether.app.protocol.ScheduledActionInput

/*
 * ta-m7ef (tether PR #241, PROTOCOL 143 r3): an isolated schedule runs its project's setup only if the owner
 * approved it when SAVING the schedule. Saving asks the server what a run would resolve (the default create:
 * branch-off from origin's default, `worktree-inspect` with `worktree: { mode: "branch-off" }`) and, when
 * there is setup, shows it for approval; the schedule stores the approval (`setupConsent`) and each run
 * compares it with what it resolves then. A port of components/scheduled-actions-view.tsx's
 * ScheduleSetupCheck, `submit`, and its three effects (1bf4a465).
 */

/** scheduled-actions-view.tsx ScheduleSetupCheck. */
data class ScheduleSetupCheck(
    val confirming: Boolean,
    val inspectRequestId: String,
    /** The latest error's seq when the check went out: a newer requestId-less error ends it. */
    val errorSeq: Long,
    /** The validated input, saved (with the consent chosen) once the check settles. */
    val input: ScheduledActionInput,
    val editingId: String?,
    /** What the check was made for: any edit of the form cancels it. */
    val form: ScheduleForm,
    val approval: SetupApproval?,
) {
    override fun toString(): String = "ScheduleSetupCheck(confirming=$confirming, editing=${editingId != null})"
}

/** What a settled check does. */
sealed interface ScheduleSetupOutcome {
    /** Not this check's answer. */
    data object Ignore : ScheduleSetupOutcome

    /** Nothing would run: save [input] with `setupConsent: "none"`. */
    data class Save(val input: ScheduledActionInput) : ScheduleSetupOutcome

    /** Setup would run: show [check] for approval. */
    data class Confirm(val check: ScheduleSetupCheck) : ScheduleSetupOutcome

    /** The check failed: say [message], save nothing. */
    data class Refuse(val message: String) : ScheduleSetupOutcome
}

object ScheduleSetup {
    const val LINK_DOWN_COPY = "The secure link is reconnecting — the schedule was not saved."
    const val CHECKING = "Checking what this project's setup would run…"
    const val CONFIRM_LABEL = "Approve this project's setup for the schedule"
    const val TITLE = "This project's setup will run on this host before each run"
    const val BACK = "Back"
    const val SAVE_WITHOUT = "Save without setup"
    const val APPROVE = "Approve setup and save"
    const val SETUP_LABEL = "Setup — runs before each run's first turn"
    const val PORT_SCRIPT_LABEL =
        "Service port script — executed when a service of a run is started (only while its contents still match what you approve)"

    /** The saved input with its consent: an explicit null when none is given (the web's `setupConsent: null`). */
    fun withConsent(input: ScheduledActionInput, consent: String?): ScheduledActionInput = input.copy(setupConsent = OrNull(consent))

    /** The answer to THIS save's check (matched by its own requestId). */
    fun onReply(check: ScheduleSetupCheck?, reply: WorktreeSourceReply?): ScheduleSetupOutcome {
        if (check == null || check.confirming || reply == null || reply.requestId != check.inspectRequestId) return ScheduleSetupOutcome.Ignore
        return when (val step = SetupConsentSteps.schedule(reply.info)) {
            is ScheduleConsentStep.Save -> ScheduleSetupOutcome.Save(withConsent(check.input, step.setupConsent))
            is ScheduleConsentStep.Confirm -> ScheduleSetupOutcome.Confirm(check.copy(confirming = true, approval = step.preview))
            is ScheduleConsentStep.Refuse -> ScheduleSetupOutcome.Refuse(step.message)
        }
    }

    /** A refusal of the check (no `worktree-source` will come) ends it: the message to show, else null. */
    fun errorMessage(check: ScheduleSetupCheck?, error: CreateErrorReply?): String? {
        if (check == null || error == null) return null
        return error.message.takeIf { SetupConsentSteps.errorEndsCheck(!check.confirming, check.inspectRequestId, check.errorSeq, error) }
    }

    /** Any edit of the form while checking or confirming cancels the check. */
    fun editCancels(check: ScheduleSetupCheck?, form: ScheduleForm): Boolean = check != null && check.form != form
}
