package com.tether.app.client

import com.tether.app.protocol.model.AgentSession

/**
 * ta-ozn2.3 (tether #273, server v150): the pure rules of the operator's "Hand over to a new lead", ported from the
 * web's lib/handover-ui.mjs at fe4aa8c0. No I/O, no Compose. The server owns eligibility
 * ([AgentSession.handoverBlockedCode], a stable code); this turns codes into the web's sentences and orders the local
 * checks the way the web does.
 */
object Handover {
    /** The first protocol a server must speak to accept `handover-request` (the validator rejects it before). */
    const val SERVER_PROTOCOL = 150

    /**
     * ta-9wdf / ta-74gu (tether v151, #273 part 2): the first protocol whose server speaks Stop handover, handover with
     * changed settings (a v150 server SILENTLY DROPS `successor`, and `handover-cancel` is unknown to it), the stage
     * strip and the Session name. The hello stays 143: this gates on the SERVER's protocol only.
     */
    const val SETTINGS_PROTOCOL = 151

    /**
     * The ONE predicate every v151 surface goes through (wave-check W34 #1): the three-key dialog and Change settings,
     * the Stop / Stopping row, the stage strip, the §4.4 notice copy, the §4.5 copy (and the queue row's missing X),
     * the Session name row and `create.name`. Below 151 every W33 surface stays byte for byte as shipped in 1.3.0.
     */
    fun v151(serverProtocol: Int?): Boolean = serverProtocol != null && serverProtocol >= SETTINGS_PROTOCOL

    const val GENERIC_REASON = "This session can't hand over right now."
    const val UNDER_WAY = "Handover under way."
    const val DIALOG_TITLE = "Hand this session over to a new lead?"
    const val DIALOG_BODY =
        "Tether will ask the current lead to write a handover prompt and stop. A new session with the same settings and " +
            "permissions will take over the run from that prompt. The current session becomes read-only."

    /** The web's recent-request window: a refusal-shaped error this soon after a request is the request's. */
    const val RECENT_REQUEST_MS = 5_000L

    /** Every code the server refuses a `handover-request` with (the web's KNOWN_HANDOVER_CODES). */
    val KNOWN_CODES: Set<String> = setOf(
        "unknown_session", "ineligible_source", "read_only", "archived", "already_handed_off", "handover_pending",
        "handover_in_progress", "provider_no_bridge", "unsupported_engine", "plan_mode", "read_only_sandbox", "locked",
        "handover_first_turn",
    )

    /**
     * v151 (lib/handover-ui.mjs KNOWN_HANDOVER_CODES at 62ceca46): [KNOWN_CODES] plus the successor-override refusals and
     * the Stop handover codes. A separate set so a server below 151 reads exactly the W33 list ([readError]'s `v151`).
     */
    val KNOWN_CODES_V151: Set<String> = KNOWN_CODES + setOf(
        "invalid_settings", "engine_unavailable", "profile_unavailable", "folder_unavailable", "override_unsupported",
        "handover_underway", "not_pending", "handover_stopped", "handover_operator_stopped",
    )

    /** session-list-visibility.mjs AGENT_CREATED_VIA. */
    private val AGENT_CREATED_VIA = setOf("api-agent", "delegate", "adopted")

    /** handoverReasonCopy: an unknown code (a newer server) reads generically, never a crash. */
    fun reasonCopy(code: String?, successorName: String? = null): String = when (code) {
        "handover_pending", "handover_in_progress" -> UNDER_WAY
        "already_handed_off" -> "Already handed off to ${successorName?.takeIf { it.isNotEmpty() } ?: "another session"}."
        "read_only" -> "Read-only — Tether isn't driving this session."
        "ineligible_source" -> "Only a top-level lead can hand over."
        "archived" -> "Archived sessions can't hand over."
        "provider_no_bridge", "unsupported_engine" -> "This engine can't hand over yet."
        "handover_first_turn" -> "A turn started by a handover can't hand over again — try after the next turn."
        "plan_mode" -> "Plan mode can't hand over — leave plan mode first."
        "read_only_sandbox" -> "A read-only sandbox can't hand over."
        "locked" -> "A Locked session can't hand over."
        "handover_underway" -> "This can\u2019t wait for the handover. Stop handover first, or send text \u2014 it goes to the new session."
        "invalid_settings" -> "Those settings aren't valid. Change them and try again."
        "engine_unavailable" -> "Tether can't start that engine. Pick another and try again."
        "profile_unavailable" -> "That provider profile isn't available. Pick another and try again."
        "folder_unavailable" -> "Tether can't use that folder. Pick another folder and try again."
        "override_unsupported" -> "This session can't take changed settings. Hand over as is instead."
        else -> GENERIC_REASON
    }

    /** handoverRefusalCopy (the handover composer's inline line and the abandoned composer's toast). */
    const val GENERIC_REFUSAL = "Couldn't hand over with these settings. Change them and try again."

    /** An unknown / absent code (a newer server) is [GENERIC_REFUSAL], never raw; a known one reads "Couldn't hand over: ...". */
    fun refusalCopy(code: String?): String =
        if (code == null || code !in KNOWN_CODES_V151) GENERIC_REFUSAL else "Couldn't hand over: ${reasonCopy(code)}"

    /**
     * handoverStopRefusalCopy: a refusal of `handover-cancel` ONLY (the same `handover_in_progress` reads "Handover under
     * way." on a request, so the two never share a function).
     */
    fun stopRefusalCopy(code: String?): String = when (code) {
        "handover_in_progress" -> "Too late to stop \u2014 the new session is already starting."
        "not_pending" -> "There\u2019s no handover to stop \u2014 it already ended."
        else -> GENERIC_REASON
    }

    /** handoverBlockedCopy: the composer's "source became ineligible while open" line. */
    fun blockedCopy(sourceName: String, code: String?, successorName: String? = null): String =
        "\u201c$sourceName\u201d changed while you were choosing. ${reasonCopy(code, successorName)}"

    /** What the session menu's last row does. */
    sealed interface MenuState {
        /** The server cannot take the frame (protocol < 150, or not yet known): the row is not shown. */
        data object Hidden : MenuState
        data object Enabled : MenuState
        data class Blocked(val reason: String) : MenuState

        /** v151: a handover is underway; the row reads "Stop handover" with [stageLine] under it (then [STOP_EFFECT]). */
        data class Stop(val stageLine: String) : MenuState

        /** v151: this device pressed Stop and the server has not echoed: "Stopping handover...", disabled. */
        data object Stopping : MenuState
    }

    /**
     * handoverRowState in the web's order, behind the pre-150 gate: [localRequested] (this device asked and the
     * server has not echoed) -> handoverPending -> handedOffTo -> readOnly -> a child / agent-created session ->
     * archived -> the server's code -> enabled (null OR absent code, as the web; the server refuses if needed).
     * A busy session is NOT blocked: the request queues behind the turn. The Hidden gate is not an app-only rule:
     * the web cannot reach a pre-150 server (whose validator rejects the frame).
     */
    fun menuState(
        session: AgentSession,
        localRequested: Boolean,
        sessions: List<AgentSession>,
        serverProtocol: Int?,
        stopping: Boolean = false,
        hasInstructionRow: Boolean = false,
        turnRunning: Boolean = false,
    ): MenuState {
        if (serverProtocol == null || serverProtocol < SERVER_PROTOCOL) return MenuState.Hidden
        // v151 (handoverRowState, hu:198-210): underway is the Stop row, not a Blocked "Handover under way.".
        if (v151(serverProtocol)) {
            handoverStageView(session, hasInstructionRow, turnRunning, localRequested, stopping)?.let {
                return if (it.key == StageKey.Stopping) MenuState.Stopping else MenuState.Stop(it.line)
            }
            val code = rowCode(session, requested = false) ?: return MenuState.Enabled
            val successor = if (code == "already_handed_off") sessions.firstOrNull { it.id == session.handedOffTo }?.name?.let(LabelText::label) else null
            return MenuState.Blocked(reasonCopy(code, successor))
        }
        if (localRequested) return MenuState.Blocked(UNDER_WAY)
        if (session.handoverPending) return MenuState.Blocked(UNDER_WAY)
        session.handedOffTo?.let { to ->
            return MenuState.Blocked(reasonCopy("already_handed_off", sessions.firstOrNull { it.id == to }?.name?.let(LabelText::label)))
        }
        if (session.readOnly) return MenuState.Blocked(reasonCopy("read_only"))
        if (session.parentSessionId != null || session.createdViaStamp in AGENT_CREATED_VIA) {
            return MenuState.Blocked(reasonCopy("ineligible_source"))
        }
        if (session.runtimeArchived) return MenuState.Blocked(reasonCopy("archived"))
        session.handoverBlockedCode?.let { return MenuState.Blocked(reasonCopy(it)) }
        return MenuState.Enabled
    }

    /**
     * handoverRowCode (v151, hu:131-142) in the web's order: requested -> pending OR a stage -> handed off -> read-only ->
     * a child / agent-created session -> archived -> the server's code; null = eligible. [requested] is the local flag.
     */
    fun rowCode(session: AgentSession, requested: Boolean = false): String? {
        if (requested) return "handover_pending"
        if (session.handoverPending || session.handoverStageCode != null) return "handover_pending"
        if (session.handedOffTo != null) return "already_handed_off"
        if (session.readOnly) return "read_only"
        if (session.parentSessionId != null || session.createdViaStamp in AGENT_CREATED_VIA) return "ineligible_source"
        if (session.runtimeArchived) return "archived"
        return session.handoverBlockedCode
    }

    /** v151 (handoverUnderway, hu:160-163): a server stage, a handoverPending, or this device's un-echoed request; never once handed off. */
    fun underway(session: AgentSession?, requested: Boolean = false): Boolean {
        if (session == null || session.handedOffTo != null) return false
        return session.handoverStageCode != null || session.handoverPending || requested
    }

    /** The keys of [handoverStageView] (hu:150-158). */
    enum class StageKey { RequestedQueued, RequestedWriting, Scheduled, ScheduledNow, WaitingBackground, Stopping }

    /** [key] is what shows; [underlyingKey] the stage beneath a stopping flag (queue rows keep their pre-stop label). */
    data class StageView(val key: StageKey, val underlyingKey: StageKey, val line: String)

    const val STOPPING_LINE = "Stopping the handover\u2026"
    private const val LINE_REQUESTED_QUEUED = "Handover requested. Tether asks the lead to write its handover prompt when this turn ends."
    private const val LINE_REQUESTED_WRITING = "Handover under way. The lead is writing its handover prompt."
    private const val LINE_SCHEDULED = "Handover prompt written. The new session starts when this turn ends."
    private const val LINE_SCHEDULED_NOW = "Handover prompt written. The new session is starting."

    private fun waitingLine(n: Int) =
        "Handover prompt written. Waiting for $n background ${if (n == 1) "task" else "tasks"} to finish, then the new session starts."

    /**
     * handoverStageView (hu:170-190), v151: null (no handover) or what the strip and the Stop row say. No timer anywhere.
     * [hasInstructionRow]: the instruction is a queued row, not the running turn; [turnRunning]: a turn is in flight;
     * [requested]: this device asked and the server has not echoed; [stopping]: this device pressed Stop.
     */
    fun handoverStageView(
        session: AgentSession?,
        hasInstructionRow: Boolean,
        turnRunning: Boolean,
        requested: Boolean,
        stopping: Boolean,
    ): StageView? {
        if (session == null || !underway(session, requested)) return null
        val tasks = session.handoverBackgroundTaskCount ?: 0
        val key = when (session.handoverStageCode) {
            "requested" -> if (hasInstructionRow) StageKey.RequestedQueued else StageKey.RequestedWriting
            "scheduled" -> if (turnRunning) StageKey.Scheduled else StageKey.ScheduledNow
            "waiting_background" -> if (tasks > 0) StageKey.WaitingBackground else StageKey.ScheduledNow
            else -> StageKey.RequestedWriting
        }
        val line = when (key) {
            StageKey.RequestedQueued -> LINE_REQUESTED_QUEUED
            StageKey.RequestedWriting -> LINE_REQUESTED_WRITING
            StageKey.Scheduled -> LINE_SCHEDULED
            StageKey.ScheduledNow -> LINE_SCHEDULED_NOW
            StageKey.WaitingBackground -> waitingLine(tasks)
            StageKey.Stopping -> STOPPING_LINE
        }
        return if (stopping) StageView(StageKey.Stopping, key, STOPPING_LINE) else StageView(key, key, line)
    }

    /** What the strip above the composer shows: the W33 pending notice (server below 151) or a v151 stage line. */
    sealed interface Strip {
        /** Protocol 150: "Handover under way — a new lead takes over when this lead finishes." exactly as shipped. */
        data object Legacy : Strip
        data class Stage(val view: StageView) : Strip
    }

    /**
     * The strip for [session] (null = nothing underway). v151 ([v151]): [handoverStageView]. Below it, the W33 rule:
     * asked here (not yet settled) or the server's own `handoverPending`, and not yet handed off.
     */
    fun strip(
        session: AgentSession?,
        requested: Boolean,
        serverProtocol: Int?,
        stopping: Boolean = false,
        hasInstructionRow: Boolean = false,
        turnRunning: Boolean = false,
    ): Strip? {
        if (session == null) return null
        if (v151(serverProtocol)) return handoverStageView(session, hasInstructionRow, turnRunning, requested, stopping)?.let(Strip::Stage)
        return if (session.handedOffTo == null && (requested || session.handoverPending)) Strip.Legacy else null
    }

    /** The Stop row's effect line, under the stage line. */
    const val STOP_EFFECT = "Nothing goes to a new session. Messages waiting for it run here."
    const val STOP_LABEL = "Stop handover"
    const val STOPPING_LABEL = "Stopping handover\u2026"

    /** The strip's sub line (every stage but stopping). \u22ef is the web's "⋯"; the Android trigger is "Session links". */
    const val STRIP_SUB = "New messages wait, then go to the new session. Stop handover in the Session links menu (\u22ef) cancels it."

    // ---- the 3-key dialog and the handover composer's copy (hu:8-44), verbatim ----
    const val DIALOG_BODY_V151 =
        "Tether asks the current lead to write a handover prompt and stop. A new session takes over the run from that prompt, and this one becomes read-only."
    const val CHOICE_AS_IS = "Hand over as is"
    const val CHOICE_CHANGE = "Change settings"
    const val CHOICE_TAIL_AS_IS = " keeps the same settings and permissions. "
    const val CHOICE_TAIL_CHANGE = " lets you pick them first \u2014 the lead writes nothing until you confirm there."
    const val CANCEL_LABEL = "Cancel"
    const val CHANGE_LABEL = "Change settings\u2026"
    const val AS_IS_LABEL = "Hand over as is"
    const val NO_ENGINE_LINE = "This session\u2019s engine isn\u2019t available here, so its settings can\u2019t be changed. Hand over as is instead."
    const val MODE_SUBTITLE = "The new lead starts with this session's settings. Change anything, then hand over."
    const val MODE_CLOSE_LABEL = "Close \u2014 nothing is handed over"
    const val ISOLATION_NOTE = "Works in this folder \u2014 a handover never creates a worktree."
    const val EXPLAINER_TITLE = "The current lead writes the first message"
    const val PI_NOTE = "Pi can't hand over yet, so the new session won't be able to pass the run on."
    const val CTA_LABEL = "Hand over"
    const val PENDING_LABEL = "Handing over\u2026"
    const val NAME_FIELD_LABEL = "Session name"
    const val NAME_PLACEHOLDER = "Leave empty to name it automatically"
    const val NAME_MAX_LENGTH = 80
    const val SUBMIT_NEED_PROVIDER = "That provider isn't available right now. Pick another and try again."
    const val SUBMIT_NEED_CWD = "Choose a working directory."
    const val SUBMIT_RECONNECTING = "The secure link is reconnecting \u2014 nothing was handed over."

    fun modeTitle(sourceName: String) = "Hand over \u201c$sourceName\u201d"
    fun explainerBody(sourceName: String) =
        "When you hand over, Tether asks \u201c$sourceName\u201d to write a handover prompt and stop; the new session starts from it. Close this window and nothing is sent."
    fun autoNameHintLine(hint: String) = "Automatic name: $hint"

    /** nameHintView: the helper under the name field, only while the field is empty and a hint exists; null = hide. */
    fun nameHintView(value: String?, hint: String?): String? {
        val text = hint?.trim().orEmpty()
        return if (value.isNullOrEmpty() && text.isNotEmpty()) autoNameHintLine(text) else null
    }

    // ---- §4.5: the copy the web piece made false on Android, v151 only ----
    const val QUEUE_LABEL = "Tether\u2019s handover instruction \u2014 runs when this turn ends."
    const val QUEUE_MOVES = "Waits for the handover, then goes to the new session."
    const val QUEUE_MOVES_PEER =
        "From another session, not you. Waits for the handover, then goes to the new session; remove it to withdraw it."
    const val PLACEHOLDER_FINE = "Handover under way \u2014 your message waits, then goes to the new session"
    const val PLACEHOLDER_COARSE = "Message goes to the new session"
    const val QUEUE_ARIA = "Queue message for the new session"
    const val QUEUE_TITLE = "Queue this message (it goes to the new session after the handover)"

    /** handoverUnderwayCopy: the composer's inline refusal by what the operator tried to send. [kind]: "command" | "delegate" | other. */
    fun underwayCopy(kind: String): String {
        val subject = when (kind) { "command" -> "Commands"; "delegate" -> "A delegation"; else -> "Attachments" }
        return "$subject can\u2019t wait for a handover. Stop handover first, or send text \u2014 it goes to the new session."
    }

    /** handoverInterruptTitle: Interrupt's description per stage; null when the stage has no running turn to interrupt. */
    fun interruptTitle(key: StageKey): String? = when (key) {
        StageKey.RequestedQueued -> "Interrupt the current turn \u2014 the handover instruction then runs"
        StageKey.RequestedWriting -> "Interrupt \u2014 the lead stops before writing its handover prompt, so the handover ends"
        StageKey.Scheduled -> "Interrupt the current turn \u2014 the handover then starts right away"
        else -> null
    }

    /**
     * handoverQueueRowLabel: the wait label of one queued row while a handover is underway, or null to keep the row's
     * own. A row that will move says so; the instruction row and a row AHEAD of it keep theirs; a held row never moves;
     * while stopping, rows keep their pre-stop label ([StageView.underlyingKey]).
     */
    fun queueRowLabel(
        view: StageView?,
        instruction: Boolean = false,
        peer: Boolean = false,
        held: Boolean = false,
        index: Int = -1,
        instructionIndex: Int = -1,
    ): String? {
        if (view == null || held) return null
        if (instruction) return QUEUE_LABEL
        if (view.underlyingKey == StageKey.RequestedQueued && instructionIndex >= 0 && index >= 0 && index < instructionIndex) return null
        return if (peer) QUEUE_MOVES_PEER else QUEUE_MOVES
    }

    /**
     * canChangeSettings (db:1321-1338): the source's engine is in the catalog, so there is something to seed from.
     * False = the dialog stays open with [NO_ENGINE_LINE].
     */
    fun canChangeSettings(session: AgentSession, entries: List<ProviderCatalogEntry>): Boolean = entryForSource(entries, session) != null

    /** lib/draft-form.ts entryForSource: the source's profile row, else the provider's own row, else any row of the provider. */
    fun entryForSource(entries: List<ProviderCatalogEntry>, session: AgentSession): ProviderCatalogEntry? {
        val profileId = session.profileId?.takeIf { it.isNotEmpty() }
        if (profileId != null) entries.firstOrNull { it.provider == session.provider && it.profileId == profileId }?.let { return it }
        return entries.firstOrNull { it.provider == session.provider && it.profileId.isNullOrEmpty() }
            ?: entries.firstOrNull { it.provider == session.provider }
    }

    /** "<code>: detail", the shape of a refused `handover-request`'s `error` frame. */
    data class Refusal(val code: String, val detail: String)

    private val REFUSAL = Regex("^([a-z][a-z_]*): ([\\s\\S]*)$")

    fun parseRefusal(message: String?): Refusal? =
        REFUSAL.find(message ?: "")?.let { Refusal(it.groupValues[1], it.groupValues[2]) }

    /** The error text to show, and whether it is a handover refusal (the requested flag must clear). */
    data class ErrorReading(val text: String, val refusal: Boolean)

    /**
     * use-tether.ts:1241-1250 exactly: rewrite into the operator sentence ONLY when the message parses as a refusal AND
     * (its code is a known handover code OR a request was sent less than [RECENT_REQUEST_MS] ago). Everything else,
     * including the codeless owner-sign-in refusal and any unrelated "foo_bar: x", reaches the toast verbatim.
     * [lastRequestAt] is the clock reading of the last successful send (0 = never, as the web's ref).
     */
    fun readError(message: String, lastRequestAt: Long, now: Long, v151: Boolean = false): ErrorReading {
        val refusal = parseRefusal(message)
        val recent = now - lastRequestAt < RECENT_REQUEST_MS
        return if (refusal != null && (refusal.code in (if (v151) KNOWN_CODES_V151 else KNOWN_CODES) || recent)) {
            ErrorReading(reasonCopy(refusal.code), true)
        } else {
            ErrorReading(message, false)
        }
    }

    /** One decision of the focus rule. */
    data class NavigationDecision(val sourceId: String, val action: Action, val to: String? = null) {
        enum class Action { Navigate, Drop }
    }

    /**
     * decideHandoverNavigation: for every source this device asked about whose `handedOffTo` successor is now listed,
     * navigate (still viewing the source) or drop (the operator moved on). A source this device did not ask about is
     * never touched: an agent-initiated handover, another tab or device never navigates anyone.
     */
    fun decideNavigation(requested: Set<String>, activeId: String?, sessions: List<AgentSession>): List<NavigationDecision> =
        requested.mapNotNull { sourceId ->
            val successorId = sessions.firstOrNull { it.id == sourceId }?.handedOffTo ?: return@mapNotNull null
            if (sessions.none { it.id == successorId }) return@mapNotNull null
            if (sourceId == activeId) NavigationDecision(sourceId, NavigationDecision.Action.Navigate, successorId)
            else NavigationDecision(sourceId, NavigationDecision.Action.Drop)
        }

    /** The sidebar glyph's words for a handed-off source. */
    fun handedOffDescription(session: AgentSession, sessions: List<AgentSession>): String? {
        val to = session.handedOffTo ?: return null
        val name = sessions.firstOrNull { it.id == to }?.name?.let(LabelText::label)?.takeIf { it.isNotBlank() }
        return "Handed off to ${name ?: "another session"}"
    }
}
