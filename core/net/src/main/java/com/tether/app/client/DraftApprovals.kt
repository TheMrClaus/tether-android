package com.tether.app.client

import com.tether.app.protocol.helpers.DraftForm

/**
 * Reasonix's Approvals choice (Ask / Auto / YOLO), as the web's new-session composer draws it
 * (lib/reasonix-approvals.ts APPROVAL_COPY and approvalPillModel on its static option list, draft-composer.tsx:170-176).
 * A control separate from Mode. Pure: the one model every host reads (the new-session composer today, a proposal card
 * later), so the words, hints, danger tone and default never drift between them.
 *
 * [select] carries the value and the three rows; [word] is the chosen row's label ("Ask" / "Auto" / "YOLO"), [danger]
 * is true for YOLO (the warning tone, never colour alone: the word is always shown), [hint] is the chosen row's one
 * line, [title] the web pill's title (the hint plus when the choice takes effect), [ariaLabel] the pill's accessible name.
 */
data class ApprovalsControl(
    val select: SelectControl,
    val word: String,
    val danger: Boolean,
    val hint: String,
    val title: String,
    val ariaLabel: String,
) {
    val value: String get() = select.value
}

object DraftApprovals {
    /** lib/draft-form.ts:55 DEFAULT_DRAFT_TOOL_APPROVAL: what a Reasonix draft starts on and sends untouched. */
    const val DEFAULT = DraftForm.DEFAULT_DRAFT_TOOL_APPROVAL

    /** lib/reasonix-approvals.ts APPROVAL_COPY: the rows' words and hints, in the web's order; YOLO is the danger row. */
    val CHOICES: List<ModeChoice> = listOf(
        ModeChoice("ask", "Ask", "Asks before writes and commands."),
        ModeChoice("auto", "Auto", "Runs ordinary tool calls without asking. Your ask and deny rules still apply."),
        ModeChoice("yolo", "YOLO", "Skips ordinary prompts. Deny rules, plan approval and the agent's questions still wait; the sandbox still applies.", danger = true),
    )

    /** lib/draft-form.ts isDraftToolApproval. */
    fun isChoice(value: String?): Boolean = CHOICES.any { it.value == value }

    /** draft-composer.tsx:170: the draft's value, Ask unless it holds one of the three; the control only for Reasonix. */
    fun of(provider: String, formValue: String?): ApprovalsControl? {
        if (provider != "reasonix") return null
        return control(if (isChoice(formValue)) formValue!! else DEFAULT)
    }

    /** The control for a chosen [value] (one of the three) over the static rows. */
    fun control(value: String): ApprovalsControl {
        val chosen = CHOICES.first { it.value == value }
        return ApprovalsControl(
            select = SelectControl(value, CHOICES.map { ControlOption(it.value, it.label, it.hint, danger = it.danger) }),
            word = chosen.label,
            danger = chosen.danger,
            hint = chosen.hint,
            title = "${chosen.hint} Tether sets this when the session starts; the session then shows what Reasonix reports.",
            ariaLabel = "Approvals: ${chosen.label}",
        )
    }

    /** lib/draft-form.ts:67 createToolApprovalFor: Reasonix always sends the explicit choice (Ask by default); no other provider sends one. */
    fun createValueFor(provider: String, choice: String?): String? =
        if (provider != "reasonix") null else if (isChoice(choice)) choice else DEFAULT
}
