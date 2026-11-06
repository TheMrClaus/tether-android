package com.tether.app.protocol.helpers

import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.tree.JsStr

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

    /**
     * The screen-level input of [backgroundSpared] (ChatScreen's composer): the session's provider row is looked up in [providers] by
     * id, and [serverProtocol] must be the CONNECTED SERVER's protocol (`serverProtocolVersion`), never a client constant. No session = false.
     */
    fun backgroundSparedFor(
        session: com.tether.app.protocol.model.AgentSession?,
        providers: List<com.tether.app.protocol.model.ProviderInfo>,
        serverProtocol: Int?,
    ): Boolean = session != null && backgroundSpared(
        session.provider,
        serverProtocol,
        providers.firstOrNull { it.id == session.provider }?.capabilities?.persistentSessions == true,
    )

    // ---- spec §4.1: the cost sentence ----------------------------------------------------------------

    /** Spec §4.1: foreground tool calls are certain to stop only on Claude and Pi (probed); every other engine is not. */
    fun foregroundCertain(provider: String?): Boolean = provider == "claude" || provider == "pi"

    /** Spec §4.1: background tasks are visible to Tether only where the engine reports them (today Claude). */
    fun backgroundVisible(provider: String?): Boolean = provider == "claude"

    /** First protocol whose server accepts `interrupt.stopBackground` and reports `backgroundTerminals` / `unstoppedBackground` (v156, additive). */
    const val STOP_BACKGROUND_PROTOCOL_MIN = 156

    /** The ONE gate of the "Also stop" box and of the `stopBackground` flag: the CONNECTED SERVER's protocol (`serverProtocolVersion`), never a client constant. */
    fun stopBackgroundSupported(serverProtocol: Int?): Boolean = serverProtocol != null && serverProtocol >= STOP_BACKGROUND_PROTOCOL_MIN

    // ---- spec §4.1f F.1 / F.3 / B.1 / B.3 (twin of lib/queue-wait.ts + lib/claude-stop-band.ts, tether w2-int) ----------------

    /** lib/claude-stop-band.ts CLAUDE_SPARES_BACKGROUND_FROM: the first Claude CLI whose interrupt spares background agents. */
    const val CLAUDE_SPARES_BACKGROUND_FROM = "2.1.246"

    enum class ClaudeStopBand { Spares, EndsAgents, Unknown }

    /** lib/claude-stop-band.ts `claudeStopBand`: `Spares` >= 2.1.246, `EndsAgents` below it, `Unknown` for an absent or malformed version (never a guess). */
    fun claudeStopBand(cliVersion: String?): ClaudeStopBand {
        if (cliVersion.isNullOrEmpty()) return ClaudeStopBand.Unknown
        return try {
            if (VersionCompare.compareVersionTriples(JsStr(cliVersion), JsStr(CLAUDE_SPARES_BACKGROUND_FROM)) >= 0.0) {
                ClaudeStopBand.Spares
            } else {
                ClaudeStopBand.EndsAgents
            }
        } catch (_: JsError) {
            ClaudeStopBand.Unknown
        }
    }

    /** spec §4.1f F.3: what the sentence says about background work an engine does NOT report. */
    enum class BackgroundTail { MayStop, KeepsRunning }

    /** lib/queue-wait.ts `stopFactsFor` result. */
    data class StopFacts(
        val foregroundCertain: Boolean,
        val backgroundVisible: Boolean,
        val backgroundSpared: Boolean,
        val backgroundTail: BackgroundTail,
    )

    /**
     * lib/queue-wait.ts `stopFactsFor`: per-engine facts of the cost sentence. [cliVersion] is the version the RUNNING Claude CLI
     * reported (the projection's `cliVersion`): a CLI below 2.1.246 (or one that reported none) does not spare background agents, so
     * `backgroundSpared` needs the Spares band (F.1) as well as `persistentSessions`. Supersedes [backgroundSparedFor]'s protocol-155 stand-in.
     */
    fun stopFactsFor(
        provider: String?,
        persistentSessions: Boolean = false,
        cliVersion: String? = null,
        engineGeneration: String? = null,
    ): StopFacts = StopFacts(
        foregroundCertain = provider == "claude" || provider == "pi" || provider == "reasonix",
        backgroundVisible = provider == "claude",
        backgroundSpared = provider == "claude" && persistentSessions && claudeStopBand(cliVersion) == ClaudeStopBand.Spares,
        backgroundTail = if (provider == "reasonix" || (provider == "codex" && engineGeneration == "codex-app-server-v2")) {
            BackgroundTail.KeepsRunning
        } else {
            BackgroundTail.MayStop
        },
    )

    /** spec §4.1f B.1: the background work a checked "Also stop" box would stop, per kind. */
    data class BackgroundStoppable(val tasks: Int = 0, val terminals: Int = 0, val spawned: Int = 0)

    /** A spawned-run row as [backgroundStoppable] reads it. */
    data class SpawnedRun(val status: String? = null, val origin: String? = null)

    /**
     * lib/queue-wait.ts `backgroundStoppable`: Claude persistent = every counted background task ([outstandingBackground] is the
     * server's basis, `stoppableBackgroundTaskCount`); Codex terminals = the app-server's main-thread count (`backgroundTerminals`); every
     * harness = the RUNNING spawn_agent runs this server started (origin "spawned").
     */
    fun backgroundStoppable(
        provider: String?,
        persistentSessions: Boolean = false,
        outstandingBackground: Int = 0,
        terminals: Int = 0,
        spawnedRuns: List<SpawnedRun> = emptyList(),
    ): BackgroundStoppable = BackgroundStoppable(
        tasks = if (provider == "claude" && persistentSessions) count(outstandingBackground) else 0,
        terminals = count(terminals),
        spawned = spawnedRuns.count { it.status == "running" && it.origin == "spawned" },
    )

    fun stoppableTotal(stoppable: BackgroundStoppable?): Int =
        if (stoppable == null) 0 else count(stoppable.tasks) + count(stoppable.terminals) + count(stoppable.spawned)

    /** spec §4.1f B.3: `1 background task and 2 spawned agents` - kinds in this order, zero counts dropped. */
    fun stopItems(stoppable: BackgroundStoppable?): String {
        val items = ArrayList<String>(3)
        if (stoppable != null) {
            if (count(stoppable.tasks) > 0) items.add(plural(count(stoppable.tasks), "background task", "background tasks"))
            if (count(stoppable.terminals) > 0) items.add(plural(count(stoppable.terminals), "background terminal", "background terminals"))
            if (count(stoppable.spawned) > 0) items.add(plural(count(stoppable.spawned), "spawned agent", "spawned agents"))
        }
        if (items.size <= 1) return items.joinToString("")
        return items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** The box label: `Also stop {items}`, no full stop. */
    fun alsoStopLabel(stoppable: BackgroundStoppable?): String = "Also stop ${stopItems(stoppable)}"

    /**
     * The ONE cost sentence of Stop and of Interrupt and send (spec §4.1 + §4.1f B.3), word for word lib/queue-wait.ts `interruptCostCopy`.
     * [alsoStop] = what the "Also stop" box covers WHEN CHECKED (null when unchecked or when there is no box).
     */
    fun interruptCostCopy(
        work: LiveWork,
        foregroundCertain: Boolean,
        backgroundVisible: Boolean,
        backgroundSpared: Boolean = false,
        backgroundTail: BackgroundTail = BackgroundTail.MayStop,
        alsoStop: BackgroundStoppable? = null,
    ): String {
        val spared = backgroundVisible && backgroundSpared
        val tools = count(work.openTools)
        val bg = count(work.outstandingBackground)
        val toolText = plural(tools, "running tool call", "running tool calls")
        val bgText = plural(bg, "background task", "background tasks")
        val covered = count(alsoStop?.tasks ?: 0) > 0
        val alsoItems = stopItems(alsoStop)
        val certain = ArrayList<String>(2).apply {
            add("the current response")
            if (foregroundCertain && tools > 0) add(toolText)
        }
        val maybe = ArrayList<String>(2).apply {
            if (!foregroundCertain && tools > 0) add(toolText)
            if (backgroundVisible && !spared && !covered && bg > 0) add(bgText)
        }
        return "Stops " + certain.joinToString(" and ") +
            (if (maybe.isNotEmpty()) ", and may stop " + maybe.joinToString(" and ") else "") + "." +
            (if (alsoItems.isNotEmpty()) " It also stops $alsoItems." else "") +
            (if (covered) (if (count(alsoStop?.tasks ?: 0) == 1) " Claude is told the task stopped and may reply right away." else " Claude is told the tasks stopped and may reply right away.") else "") +
            (if (spared && !covered && bg > 0) (if (bg == 1) " 1 background task keeps running." else " $bg background tasks keep running.") else "") +
            (if (backgroundVisible) "" else if (backgroundTail == BackgroundTail.KeepsRunning) {
                if (alsoItems.isNotEmpty()) " Other background work the agent started keeps running." else " Background work the agent started keeps running."
            } else {
                if (alsoItems.isNotEmpty()) " It may also stop other background work the agent started." else " It may also stop background work the agent started."
            })
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

    /** lib/queue-wait.ts `stopDescription`: `{cost} {line}`, the line omitted when empty. */
    fun stopDescription(cost: String, line: String): String = listOf(cost, line).filter { it.isNotEmpty() }.joinToString(" ")

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
