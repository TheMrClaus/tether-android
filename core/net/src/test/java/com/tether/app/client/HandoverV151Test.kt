package com.tether.app.client

import com.tether.app.client.Handover.MenuState
import com.tether.app.client.Handover.StageKey
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-9wdf / ta-74gu (tether v151, #273 part 2): the pure rules, ported from lib/handover-ui.mjs at 62ceca46. One test
 * per surface at 150 (W33 byte for byte) and at 151 (wave-check W34 #1).
 */
class HandoverV151Test {
    private fun s(id: String = "a", block: (AgentSession) -> AgentSession = { it }) =
        block(AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "idle", startedAt = 1, updatedAt = 1))

    private fun staged(stage: String?, tasks: Int? = null, pending: Boolean = false) = s {
        it.copy(
            handoverStage = stage?.let(::JsonPrimitive),
            handoverBackgroundTasks = tasks?.let { n -> JsonPrimitive(n) },
            handoverPending = pending,
        )
    }

    private fun menu(session: AgentSession, proto: Int?, requested: Boolean = false, stopping: Boolean = false, row: Boolean = false, turn: Boolean = false) =
        Handover.menuState(session, requested, listOf(session), proto, stopping, row, turn)

    // ---- the predicate ----
    @Test fun theOnePredicateIs151AndUp() {
        assertFalse(Handover.v151(null)); assertFalse(Handover.v151(143)); assertFalse(Handover.v151(150))
        assertTrue(Handover.v151(151)); assertTrue(Handover.v151(152))
        assertEquals(151, Handover.SETTINGS_PROTOCOL)
        assertEquals("the W33 gate stays", 150, Handover.SERVER_PROTOCOL)
    }

    // ---- the Stop / Stopping row ----
    @Test fun at150UnderwayIsStillTheBlockedRowEvenWithAStage() {
        assertEquals(MenuState.Blocked(Handover.UNDER_WAY), menu(s { it.copy(handoverPending = true) }, 150))
        assertEquals(MenuState.Blocked(Handover.UNDER_WAY), menu(s(), 150, requested = true))
        // a stage is unknown to a 150 reading: W33 ignores it
        assertEquals(MenuState.Enabled, menu(staged("scheduled"), 150))
    }

    @Test fun at151UnderwayIsTheStopRowWithTheStageLine() {
        assertEquals(MenuState.Stop("Handover under way. The lead is writing its handover prompt."), menu(s { it.copy(handoverPending = true) }, 151))
        assertEquals(MenuState.Stop("Handover under way. The lead is writing its handover prompt."), menu(s(), 151, requested = true))
        assertEquals(MenuState.Stop("Handover prompt written. The new session is starting."), menu(staged("scheduled"), 151))
        assertEquals(MenuState.Stopping, menu(staged("scheduled"), 151, stopping = true))
        assertEquals(MenuState.Stopping, menu(s(), 151, requested = true, stopping = true))
    }

    @Test fun at151TheStartRowKeepsTheWebsOrderAndAHandedOffSourceIsNeverUnderway() {
        assertEquals(MenuState.Enabled, menu(s(), 151))
        val b = s("b") { it.copy(name = "Successor") }
        val a = s("a") { it.copy(handedOffTo = "b", handoverPending = true, handoverStage = JsonPrimitive("scheduled")) }
        // web order (hu:131-135): pending / a stage is checked BEFORE handedOffTo, so it reads "Handover under way." (never Stop)
        assertEquals(MenuState.Blocked(Handover.UNDER_WAY), Handover.menuState(a, false, listOf(a, b), 151))
        // the request is still un-echoed on this device (hu:198-210 passes `requested`): the row code is handover_pending
        val handed = a.copy(handoverPending = false, handoverStage = null)
        assertEquals(MenuState.Blocked(Handover.UNDER_WAY), Handover.menuState(handed, true, listOf(handed, b), 151))
        val done = a.copy(handoverPending = false, handoverStage = null)
        assertEquals(MenuState.Blocked("Already handed off to Successor."), Handover.menuState(done, false, listOf(done, b), 151))
        assertEquals(MenuState.Blocked("Read-only — Tether isn't driving this session."), menu(s { it.copy(readOnly = true) }, 151))
        assertEquals(MenuState.Blocked("Plan mode can't hand over — leave plan mode first."), menu(s { it.copy(handoverBlockedReason = JsonPrimitive("plan_mode")) }, 151))
        assertEquals(MenuState.Hidden, menu(s(), 149))
    }

    // ---- the stage view ----
    @Test fun everyStageLineIsTheWebsVerbatim() {
        fun v(session: AgentSession, row: Boolean = false, turn: Boolean = false, requested: Boolean = false, stopping: Boolean = false) =
            Handover.handoverStageView(session, row, turn, requested, stopping)
        assertEquals(
            Handover.StageView(StageKey.RequestedQueued, StageKey.RequestedQueued, "Handover requested. Tether asks the lead to write its handover prompt when this turn ends."),
            v(staged("requested"), row = true),
        )
        assertEquals("Handover under way. The lead is writing its handover prompt.", v(staged("requested"))!!.line)
        assertEquals(StageKey.RequestedWriting, v(staged("requested"))!!.key)
        assertEquals("Handover prompt written. The new session starts when this turn ends.", v(staged("scheduled"), turn = true)!!.line)
        assertEquals("Handover prompt written. The new session is starting.", v(staged("scheduled"))!!.line)
        assertEquals("Handover prompt written. The new session is starting.", v(staged("waiting_background"))!!.line)
        assertEquals("Handover prompt written. The new session is starting.", v(staged("waiting_background", tasks = 0))!!.line)
        assertEquals("Handover prompt written. Waiting for 1 background task to finish, then the new session starts.", v(staged("waiting_background", tasks = 1))!!.line)
        assertEquals("Handover prompt written. Waiting for 3 background tasks to finish, then the new session starts.", v(staged("waiting_background", tasks = 3))!!.line)
        // pending with no stage, a local request, and an unknown future stage all read as "writing"
        assertEquals("Handover under way. The lead is writing its handover prompt.", v(s { it.copy(handoverPending = true) })!!.line)
        assertEquals("Handover under way. The lead is writing its handover prompt.", v(s(), requested = true)!!.line)
        assertEquals(StageKey.RequestedWriting, v(staged("a_future_stage"))!!.key)
        // stopping keeps the stage beneath it (queue rows keep their pre-stop label)
        assertEquals(Handover.StageView(StageKey.Stopping, StageKey.Scheduled, "Stopping the handover…"), v(staged("scheduled"), turn = true, stopping = true))
        // nothing underway, and never once handed off
        assertNull(v(s())); assertNull(Handover.handoverStageView(null, false, false, false, false))
        assertNull(v(s { it.copy(handedOffTo = "b", handoverPending = true) }))
    }

    @Test fun theStripIsTheW33NoticeAt150AndTheStageAt151() {
        val pending = s { it.copy(handoverPending = true) }
        assertEquals(Handover.Strip.Legacy, Handover.strip(pending, false, 150))
        assertEquals(Handover.Strip.Legacy, Handover.strip(s(), true, 150))
        assertNull(Handover.strip(s(), false, 150))
        assertNull("a handed-off source shows no strip", Handover.strip(s { it.copy(handedOffTo = "b", handoverPending = true) }, true, 150))
        assertNull("a stage is not a 150 reading", Handover.strip(staged("scheduled"), false, 150))
        val stage = Handover.strip(staged("scheduled"), false, 151) as Handover.Strip.Stage
        assertEquals(StageKey.ScheduledNow, stage.view.key)
        assertNull(Handover.strip(s(), false, 151))
        assertEquals(
            "New messages wait, then go to the new session. Stop handover in the Session links menu (⋯) cancels it.",
            Handover.STRIP_SUB,
        )
    }

    @Test fun rowCodeReadsAStageLikePending() {
        assertEquals("handover_pending", Handover.rowCode(staged("requested")))
        assertEquals("handover_pending", Handover.rowCode(s(), requested = true))
        assertNull(Handover.rowCode(s()))
        assertEquals("already_handed_off", Handover.rowCode(s { it.copy(handedOffTo = "b") }))
    }

    // ---- codes and copy ----
    @Test fun everyNewCodeHasItsCopyAndTheKnownSetsAreExact() {
        assertEquals(13, Handover.KNOWN_CODES.size)
        assertEquals(22, Handover.KNOWN_CODES_V151.size)
        val table = mapOf(
            "handover_underway" to "This can’t wait for the handover. Stop handover first, or send text — it goes to the new session.",
            "invalid_settings" to "Those settings aren't valid. Change them and try again.",
            "engine_unavailable" to "Tether can't start that engine. Pick another and try again.",
            "profile_unavailable" to "That provider profile isn't available. Pick another and try again.",
            "folder_unavailable" to "Tether can't use that folder. Pick another folder and try again.",
            "override_unsupported" to "This session can't take changed settings. Hand over as is instead.",
        )
        for ((c, copy) in table) {
            assertEquals(c, copy, Handover.reasonCopy(c))
            assertEquals(c, "Couldn't hand over: $copy", Handover.refusalCopy(c))
        }
    }

    @Test fun refusalCopyIsGenericForAnUnknownOrAbsentCode() {
        assertEquals("Couldn't hand over with these settings. Change them and try again.", Handover.refusalCopy(null))
        assertEquals(Handover.GENERIC_REFUSAL, Handover.refusalCopy("a_future_code"))
        assertEquals("Couldn't hand over: Plan mode can't hand over — leave plan mode first.", Handover.refusalCopy("plan_mode"))
    }

    @Test fun theStopCopyIsItsOwnAndHandoverInProgressNeverReadsUnderWay() {
        assertEquals("Too late to stop — the new session is already starting.", Handover.stopRefusalCopy("handover_in_progress"))
        assertEquals("There’s no handover to stop — it already ended.", Handover.stopRefusalCopy("not_pending"))
        assertEquals("This session can't hand over right now.", Handover.stopRefusalCopy("unknown_session"))
        assertEquals("This session can't hand over right now.", Handover.stopRefusalCopy(null))
        assertEquals("on a request the same code is the W33 sentence", "Handover under way.", Handover.reasonCopy("handover_in_progress"))
    }

    @Test fun readErrorKnowsTheNewCodesOnlyAt151() {
        val never = Long.MIN_VALUE / 2
        assertEquals(Handover.ErrorReading("invalid_settings: x", false), Handover.readError("invalid_settings: x", never, 1_000))
        assertEquals(
            Handover.ErrorReading("Those settings aren't valid. Change them and try again.", true),
            Handover.readError("invalid_settings: x", never, 1_000, v151 = true),
        )
    }

    @Test fun blockedCopyNamesTheSourceWithCurlyQuotes() {
        assertEquals(
            "“Billing” changed while you were choosing. Plan mode can't hand over — leave plan mode first.",
            Handover.blockedCopy("Billing", "plan_mode"),
        )
        assertEquals("“X” changed while you were choosing. Already handed off to Y.", Handover.blockedCopy("X", "already_handed_off", "Y"))
    }

    // ---- name hint, titles ----
    @Test fun titlesAndTheNameHintFollowTheWeb() {
        assertEquals("Hand over “Billing”", Handover.modeTitle("Billing"))
        assertEquals(
            "When you hand over, Tether asks “Billing” to write a handover prompt and stop; the new session starts from it. Close this window and nothing is sent.",
            Handover.explainerBody("Billing"),
        )
        assertEquals("Automatic name: parity15", Handover.nameHintView("", " parity15 "))
        assertEquals("a null value is cleared too", "Automatic name: p", Handover.nameHintView(null, "p"))
        assertNull(Handover.nameHintView("typed", "parity15"))
        assertNull(Handover.nameHintView("", "  "))
        assertNull(Handover.nameHintView("", null))
    }

    // ---- §4.5 copy and the queue rows ----
    @Test fun queueRowLabelsKeepTheRowsAheadOfTheInstructionAndHeldRows() {
        val view = Handover.handoverStageView(staged("requested"), true, true, false, false)
        assertEquals(Handover.QUEUE_LABEL, Handover.queueRowLabel(view, instruction = true))
        assertNull("a row AHEAD of the instruction keeps its label", Handover.queueRowLabel(view, index = 0, instructionIndex = 1))
        assertEquals(Handover.QUEUE_MOVES, Handover.queueRowLabel(view, index = 2, instructionIndex = 1))
        assertEquals(Handover.QUEUE_MOVES_PEER, Handover.queueRowLabel(view, peer = true, index = 2, instructionIndex = 1))
        assertNull(Handover.queueRowLabel(view, held = true))
        assertNull(Handover.queueRowLabel(null))
        val stopping = Handover.handoverStageView(staged("requested"), true, true, false, true)
        assertNull("stopping keeps the pre-stop label of an earlier row", Handover.queueRowLabel(stopping, index = 0, instructionIndex = 1))
        assertEquals("Tether’s handover instruction — runs when this turn ends.", Handover.QUEUE_LABEL)
    }

    @Test fun underwayAndInterruptCopy() {
        assertEquals("Attachments can’t wait for a handover. Stop handover first, or send text — it goes to the new session.", Handover.underwayCopy("attachment"))
        assertTrue(Handover.underwayCopy("command").startsWith("Commands can"))
        assertTrue(Handover.underwayCopy("delegate").startsWith("A delegation can"))
        assertEquals("Interrupt the current turn — the handover instruction then runs", Handover.interruptTitle(StageKey.RequestedQueued))
        assertNull(Handover.interruptTitle(StageKey.WaitingBackground))
        assertNull(Handover.interruptTitle(StageKey.Stopping))
    }

    // ---- canChangeSettings ----
    @Test fun canChangeSettingsNeedsTheSourceEngineInTheCatalog() {
        val rows = listOf(
            ProviderCatalogEntry("claude", "claude", "ready", emptyList()),
            ProviderCatalogEntry("work", "claude", "ready", emptyList(), profileId = "work"),
        )
        assertTrue(Handover.canChangeSettings(s(), rows))
        assertEquals("work", Handover.entryForSource(rows, s { it.copy(profileId = "work") })!!.key)
        assertEquals("a missing profile falls back to the provider's own row", "claude", Handover.entryForSource(rows, s { it.copy(profileId = "gone") })!!.key)
        assertFalse(Handover.canChangeSettings(s { it.copy(provider = "codex") }, rows))
        assertFalse(Handover.canChangeSettings(s(), emptyList()))
    }
}
