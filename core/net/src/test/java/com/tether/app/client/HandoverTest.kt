package com.tether.app.client

import com.tether.app.client.Handover.MenuState
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-ozn2.3: the pure rules, ported from lib/handover-ui.mjs at fe4aa8c0. */
class HandoverTest {
    private fun s(id: String = "a", name: String = id, block: (AgentSession) -> AgentSession = { it }) =
        block(AgentSession(id = id, provider = "claude", name = name, cwd = "/w", status = "idle", startedAt = 1, updatedAt = 1))

    private fun state(session: AgentSession, requested: Boolean = false, all: List<AgentSession> = listOf(session), proto: Int? = 150) =
        Handover.menuState(session, requested, all, proto)

    private fun code(c: String?) = s { it.copy(handoverBlockedReason = c?.let(::JsonPrimitive)) }

    @Test fun everyServerCodeHasItsCopy() {
        val table = mapOf(
            "unknown_session" to Handover.GENERIC_REASON,
            "ineligible_source" to "Only a top-level lead can hand over.",
            "read_only" to "Read-only — Tether isn't driving this session.",
            "archived" to "Archived sessions can't hand over.",
            "already_handed_off" to "Already handed off to another session.",
            "handover_pending" to "Handover under way.",
            "handover_in_progress" to "Handover under way.",
            "provider_no_bridge" to "This engine can't hand over yet.",
            "unsupported_engine" to "This engine can't hand over yet.",
            "plan_mode" to "Plan mode can't hand over — leave plan mode first.",
            "read_only_sandbox" to "A read-only sandbox can't hand over.",
            "locked" to "A Locked session can't hand over.",
            "handover_first_turn" to "A turn started by a handover can't hand over again — try after the next turn.",
            "a_future_code" to "This session can't hand over right now.",
        )
        for ((c, copy) in table) assertEquals(c, MenuState.Blocked(copy), state(code(c)))
        assertEquals(Handover.KNOWN_CODES.size, 13)
    }

    @Test fun nullAndAbsentCodesAreEnabledAndABusySessionIsNotBlocked() {
        assertEquals(MenuState.Enabled, state(code(null)))
        assertEquals(MenuState.Enabled, state(s { it.copy(status = "running") }))
    }

    @Test fun hiddenBelow150AndWhileTheServerIsUnknown() {
        assertEquals(MenuState.Hidden, state(s(), proto = 149))
        assertEquals(MenuState.Hidden, state(s(), proto = 143))
        assertEquals(MenuState.Hidden, state(s(), proto = null))
        assertEquals(MenuState.Enabled, state(s(), proto = 151))
    }

    @Test fun theLocalChecksRunInTheWebsOrder() {
        val busy = Handover.UNDER_WAY
        assertEquals(MenuState.Blocked(busy), state(s { it.copy(readOnly = true, handedOffTo = "b") }, requested = true))
        assertEquals(MenuState.Blocked(busy), state(s { it.copy(handoverPending = true, handedOffTo = "b") }))
        val b = s("b", "Successor")
        val a = s("a") { it.copy(handedOffTo = "b", readOnly = true) }
        assertEquals(MenuState.Blocked("Already handed off to Successor."), state(a, all = listOf(a, b)))
        assertEquals(MenuState.Blocked("Read-only — Tether isn't driving this session."), state(s { it.copy(readOnly = true, parentSessionId = "p") }))
        assertEquals(MenuState.Blocked("Only a top-level lead can hand over."), state(s { it.copy(parentSessionId = "p", runtimeArchived = true) }))
        for (via in listOf("api-agent", "delegate", "adopted")) {
            assertEquals(via, MenuState.Blocked("Only a top-level lead can hand over."), state(s { it.copy(createdVia = JsonPrimitive(via)) }))
        }
        assertEquals(MenuState.Enabled, state(s { it.copy(createdVia = JsonPrimitive("handoff")) }))
        assertEquals(MenuState.Blocked("Archived sessions can't hand over."), state(s { it.copy(runtimeArchived = true, handoverBlockedReason = JsonPrimitive("locked")) }))
    }

    @Test fun errorGuardRewritesOnlyKnownCodesOrRecentRequests() {
        val never = Long.MIN_VALUE / 2
        assertEquals(Handover.ErrorReading("Plan mode can't hand over — leave plan mode first.", true), Handover.readError("plan_mode: x", never, 1_000))
        assertEquals(Handover.ErrorReading("foo_bar: x", false), Handover.readError("foo_bar: x", never, 1_000))
        assertEquals(Handover.ErrorReading(Handover.GENERIC_REASON, true), Handover.readError("foo_bar: x", 1_000, 5_999))
        assertEquals(Handover.ErrorReading("foo_bar: x", false), Handover.readError("foo_bar: x", 1_000, 6_000))
        val signIn = "Handing a session over needs an owner sign-in."
        assertEquals(Handover.ErrorReading(signIn, false), Handover.readError(signIn, 1_000, 1_100))
        assertFalse(Handover.readError("handover_first_turn: x", never, 0).text.contains("handover_first_turn"))
    }

    @Test fun navigationDecisionsMatchTheWeb() {
        val b = s("b")
        val a = s("a") { it.copy(handedOffTo = "b") }
        assertEquals(listOf(Handover.NavigationDecision("a", Handover.NavigationDecision.Action.Navigate, "b")), Handover.decideNavigation(setOf("a"), "a", listOf(a, b)))
        assertEquals(listOf(Handover.NavigationDecision("a", Handover.NavigationDecision.Action.Drop)), Handover.decideNavigation(setOf("a"), "z", listOf(a, b)))
        assertTrue("successor not listed yet", Handover.decideNavigation(setOf("a"), "a", listOf(a)).isEmpty())
        assertTrue("not asked here", Handover.decideNavigation(emptySet(), "a", listOf(a, b)).isEmpty())
    }

    @Test fun theSidebarGlyphWords() {
        val b = s("b", "Beta")
        assertEquals("Handed off to Beta", Handover.handedOffDescription(s("a") { it.copy(handedOffTo = "b") }, listOf(b)))
        assertEquals("Handed off to another session", Handover.handedOffDescription(s("a") { it.copy(handedOffTo = "zz") }, listOf(b)))
        assertEquals(null, Handover.handedOffDescription(s("a"), listOf(b)))
    }
}
