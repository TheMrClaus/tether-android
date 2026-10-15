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
        else -> GENERIC_REASON
    }

    /** What the session menu's last row does. */
    sealed interface MenuState {
        /** The server cannot take the frame (protocol < 150, or not yet known): the row is not shown. */
        data object Hidden : MenuState
        data object Enabled : MenuState
        data class Blocked(val reason: String) : MenuState
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
    ): MenuState {
        if (serverProtocol == null || serverProtocol < SERVER_PROTOCOL) return MenuState.Hidden
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
    fun readError(message: String, lastRequestAt: Long, now: Long): ErrorReading {
        val refusal = parseRefusal(message)
        val recent = now - lastRequestAt < RECENT_REQUEST_MS
        return if (refusal != null && (refusal.code in KNOWN_CODES || recent)) {
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
