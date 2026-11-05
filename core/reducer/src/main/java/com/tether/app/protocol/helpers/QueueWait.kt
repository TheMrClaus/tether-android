package com.tether.app.protocol.helpers

import com.tether.app.protocol.model.QueuedMessage

/**
 * Delivery UI words (delivery-composer-spec §1 V4/V5, §3.2, §4.1, §4.1a A/C). Pure, no clock reads. ta-ceo's
 * deferred-wait copy (formatWaited, liveWorkCopy, deferredWaitCopy, the 60 s choice) is RETIRED with the old row wait choice
 * (spec §3.2): a queued row now says V4 or V5, and the only client action is "Interrupt and send" (§4.2).
 * [stopConfirms] only decides WHETHER the composer's Stop confirms (live visible work that Stop ends); the words are [interruptCostCopy] + [steeredStopCopy].
 */
object QueueWait {

    /** What a turn-wide Stop would destroy right now (chat-view.tsx:1974 `liveWork`). */
    data class LiveWork(val openTools: Int = 0, val outstandingBackground: Int = 0) {
        companion object {
            val None = LiveWork()
        }
    }

    // lib/queue-wait.mjs:13
    private fun plural(count: Int, singular: String, pluralForm: String): String =
        "$count ${if (count == 1) singular else pluralForm}"

    // lib/queue-wait.mjs:17
    private fun count(value: Int): Int = if (value > 0) value else 0

    /**
     * Whether the composer's Stop asks for a confirmation (spec §4.3, §4.1c A.3): an open tool call, or a visible background task
     * that Stop ENDS. Background tasks that Stop spares ([backgroundSpared]) never open it. The words are [interruptCostCopy].
     */
    fun stopConfirms(work: LiveWork, backgroundSpared: Boolean = false): Boolean =
        count(work.openTools) > 0 || (count(work.outstandingBackground) > 0 && !backgroundSpared)

    /** First protocol version whose server declares the persistent Claude engine's Stop spares background work (D-L148-1 (1)). */
    const val SPARED_PROTOCOL_MIN = 155

    /**
     * Spec §4.1c A.2 / D-L148-1 (1): background tasks are spared only for provider `claude`, when the connected server's protocol is
     * >= [SPARED_PROTOCOL_MIN] AND the provider row's `capabilities.persistentSessions` is true. Anything else (an older server,
     * the per-turn engine, an unknown version) keeps the §4.1 "may stop" words.
     */
    fun backgroundSpared(provider: String?, serverProtocol: Int?, persistentSessions: Boolean): Boolean =
        provider == "claude" && persistentSessions && serverProtocol != null && serverProtocol >= SPARED_PROTOCOL_MIN

    // ---- spec §4.1: the cost sentence ----------------------------------------------------------------

    /** Spec §4.1: foreground tool calls are certain to stop only on Claude and Pi (probed); every other engine is not. */
    fun foregroundCertain(provider: String?): Boolean = provider == "claude" || provider == "pi"

    /** Spec §4.1: background tasks are visible to Tether only where the engine reports them (today Claude). */
    fun backgroundVisible(provider: String?): Boolean = provider == "claude"

    /** Spec §4.1 `interruptCostCopy`, exactly. */
    fun interruptCostCopy(
        work: LiveWork,
        foregroundCertain: Boolean,
        backgroundVisible: Boolean,
        backgroundSpared: Boolean = false,
    ): String {
        val spared = backgroundVisible && backgroundSpared
        val tools = count(work.openTools)
        val bg = count(work.outstandingBackground)
        val toolText = plural(tools, "running tool call", "running tool calls")
        val bgText = plural(bg, "background task", "background tasks")
        val certain = ArrayList<String>(2).apply {
            add("the current response")
            if (foregroundCertain && tools > 0) add(toolText)
        }
        val maybe = ArrayList<String>(2).apply {
            if (!foregroundCertain && tools > 0) add(toolText)
            if (backgroundVisible && !spared && bg > 0) add(bgText)
        }
        return "Stops " + certain.joinToString(" and ") +
            (if (maybe.isNotEmpty()) ", and may stop " + maybe.joinToString(" and ") else "") + "." +
            (if (spared && bg > 0) (if (bg == 1) " 1 background task keeps running." else " $bg background tasks keep running.") else "") +
            (if (!backgroundVisible) " It may also stop background work the agent started." else "")
    }

    /**
     * Spec §4.1a A: the line for messages already sent INTO the running turn ([steered] = n of them), or "" when n = 0 or
     * the engine has no row below. Every line says "may".
     */
    fun steeredStopCopy(provider: String?, steered: Int): String {
        if (steered < 1) return ""
        val one = steered == 1
        val subject = if (one) "The message you sent into this turn" else "The $steered messages you sent into this turn"
        return when (provider) {
            "claude" -> "$subject may still run after Stop."
            "opencode" -> "$subject may wait and go with your next message."
            "codex", "pi" -> "$subject may go back to the queue, after any newer messages."
            else -> ""
        }
    }

    // ---- spec §1 V4/V5, §3.2 --------------------------------------------------------------------------

    const val QUEUED_TURN_END_COPY = "Queued on the server \u00B7 sends when this turn ends"
    const val QUEUED_STEER_COPY = "Queued on the server \u00B7 goes into this turn at the agent\u2019s next step"

    /**
     * THE one V5 predicate (label and glyph both read it): the engine can steer, the row has no attachments (never steered in the
     * first ship, D-L143-3) and it is not a legacy `next-call` row (spec §3.2: legacy renders as V4). Otherwise V4.
     */
    fun isSteered(steerCapable: Boolean, message: QueuedMessage): Boolean =
        steerCapable && message.attachmentIds.isNullOrEmpty() && message.attachments.isNullOrEmpty() && !message.atToolBoundary

    fun queuedLabel(steerCapable: Boolean, message: QueuedMessage): String =
        if (isSteered(steerCapable, message)) QUEUED_STEER_COPY else QUEUED_TURN_END_COPY

    // ---- spec §4.1a C: held rows ----------------------------------------------------------------------

    /** The reason lead of a held row: `open_failed` or absent, `steer_uncertain`, `attachment_missing`; any other value reads "Not sent.". */
    fun heldReasonLead(reason: String?): String = when (reason) {
        null, "open_failed" -> "Not sent: the session failed to open."
        "steer_uncertain" -> "May not have reached the agent; it was not sent again."
        "attachment_missing" -> "Not sent: an attachment was missing when it was time to send."
        else -> "Not sent."
    }

    /** The row's hint: names only what works, by row kind and reason. */
    fun heldHint(reason: String?, peer: Boolean, handoverRequest: Boolean): String = when {
        handoverRequest -> "Stop handover in the Session links menu (\u22EF) cancels it."
        peer -> "This message from another session cannot be edited; remove it."
        reason == "steer_uncertain" -> "Edit to resend it, or remove it."
        reason == "attachment_missing" -> "Edit to send the text without it, or remove this message."
        else -> "Edit to send it again, or remove it."
    }

    /** Label = reason lead + " " + hint (exact). [message] must be [QueuedMessage.held]. */
    fun heldLabel(message: QueuedMessage): String =
        heldReasonLead(message.heldReason) + " " + heldHint(message.heldReason, message.isPeer, message.isHandoverRequest)

    // ---- spec §4.2: Interrupt and send ----------------------------------------------------------------

    /**
     * The one row that may offer "Interrupt and send": the FIRST non-held row of the WHOLE queue (the server delivers one row
     * per turn, in order), and only when it is the operator's own. A peer, handover or notice row first = none.
     */
    fun interruptAndSendRowId(queue: List<QueuedMessage>): String? {
        val next = queue.firstOrNull { !it.held } ?: return null
        return next.queueId.takeIf { next.isOperatorMessage }
    }
}
