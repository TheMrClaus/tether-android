package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.Vocab
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerLifecycleTest {

    private fun openTurn(ts: Long = 1_000) = fold(
        freshState(),
        ev("turn_started", turnId = "t1", ts = ts) { put("idempotencyKey", "k1") },
    )

    @Test
    fun duplicateTurnStartedIsNoOp() {
        val state = openTurn()
        val next = reduce(state, ev("turn_started", turnId = "t1", ts = 2_000))
        assertSame(state, next)
    }

    @Test
    fun secondTurnStartedWhileOneOpenIsNoOp() {
        val state = openTurn()
        val next = reduce(state, ev("turn_started", turnId = "t2", ts = 2_000))
        assertSame(state, next)
        assertEquals(listOf("t1"), next.turnOrder)
    }

    @Test
    fun doneTurnIsNeverReopened() {
        val state = fold(
            openTurn(),
            ev("turn_end", turnId = "t1", ts = 2_000) { put("outcome", "ok") },
        )
        val next = reduce(
            state,
            ev("message_delta", turnId = "t1", ts = 3_000) {
                put("blockId", "m:t0")
                put("text", "late")
            },
        )
        assertEquals(state, next)
        assertTrue(next.turnsById.getValue("t1").blocksById.isEmpty())
        // A NEW turn may start after the old one is done.
        val reopened = reduce(next, ev("turn_started", turnId = "t2", ts = 4_000))
        assertEquals("t2", reopened.activeTurnId)
        assertEquals(listOf("t1", "t2"), reopened.turnOrder)
    }

    @Test
    fun cancelledPath() {
        var state = openTurn()
        state = reduce(state, ev("cancel_requested", turnId = "t1", ts = 2_000))
        assertEquals(Vocab.TURN_CANCELLING, state.turnsById.getValue("t1").status)
        assertEquals(Vocab.SESSION_ACTIVE, state.status) // session status untouched
        state = reduce(state, ev("cancelled", turnId = "t1", ts = 3_000))
        assertEquals(Vocab.TURN_DONE, state.turnsById.getValue("t1").status)
        assertEquals(Vocab.OUTCOME_CANCELLED, state.turnsById.getValue("t1").outcome)
        assertEquals(Vocab.SESSION_READY, state.status)
        assertNull(state.activeTurnId)
        assertEquals(Vocab.OUTCOME_CANCELLED, state.lastTurnOutcome)
    }

    @Test
    fun turnEndOutcomeIsWrittenVerbatim() {
        val state = fold(
            openTurn(),
            ev("turn_end", turnId = "t1", ts = 2_000) { put("outcome", "some_future_outcome") },
        )
        assertEquals("some_future_outcome", state.turnsById.getValue("t1").outcome)
        assertEquals("some_future_outcome", state.lastTurnOutcome)
    }

    @Test
    fun processExitRecordsButDoesNotEndTurn() {
        val state = fold(
            openTurn(),
            ev("process_exit", turnId = "t1", ts = 2_000) {
                put("code", 143)
                put("signal", "SIGTERM")
            },
        )
        assertEquals(143, state.turnsById.getValue("t1").exit?.code)
        assertEquals("SIGTERM", state.turnsById.getValue("t1").exit?.signal)
        assertEquals("t1", state.activeTurnId)
        assertEquals(Vocab.TURN_RUNNING, state.turnsById.getValue("t1").status)
    }

    @Test
    fun approvalFlowStatusWaitingThenActive() {
        var state = fold(
            openTurn(),
            ev("approval_request", turnId = "t1", ts = 2_000) {
                put("requestId", "r1")
                put("toolId", "toolu_1")
                put("name", "Bash")
            },
        )
        assertEquals(Vocab.SESSION_WAITING, state.status)
        assertNotNull(state.turnsById.getValue("t1").pendingApprovals["r1"])
        state = reduce(state, ev("approval_resolved", turnId = "t1", ts = 3_000) { put("requestId", "r1") })
        assertEquals(Vocab.SESSION_ACTIVE, state.status)
        assertTrue(state.turnsById.getValue("t1").pendingApprovals.isEmpty())
    }

    @Test
    fun questionFlow() {
        var state = fold(
            openTurn(),
            ev("question_request", turnId = "t1", ts = 2_000) {
                put("requestId", "q1")
                put("toolId", "toolu_q")
                putJsonArray("questions") {
                    addJsonObject {
                        put("question", "Which option?")
                        put("header", "Pick")
                        put("multiSelect", false)
                        putJsonArray("options") {
                            addJsonObject {
                                put("label", "A")
                                put("description", "first")
                            }
                        }
                    }
                }
            },
        )
        assertEquals(Vocab.SESSION_WAITING, state.status)
        val pending = state.turnsById.getValue("t1").pendingQuestions.getValue("q1")
        assertEquals("Which option?", pending.questions.single().question)
        state = reduce(state, ev("question_resolved", turnId = "t1", ts = 3_000) { put("requestId", "q1") })
        assertEquals(Vocab.SESSION_ACTIVE, state.status)
        assertTrue(state.turnsById.getValue("t1").pendingQuestions.isEmpty())
    }

    @Test
    fun syncTurnRunOpensClosesAndParksOnApproval() {
        // turn_started opens run 0.
        var state = openTurn(ts = 1_000)
        var turn = state.turnsById.getValue("t1")
        assertNotNull(turn.run)
        assertEquals(0, turn.run!!.index)
        assertEquals(1_000L, turn.run!!.startedAt)
        assertEquals(0L, turn.run!!.tokensStart)
        assertEquals(1, turn.runCount)
        assertEquals(0L, turn.activeMs)

        // Approval parks the turn: run closes and banks elapsed.
        state = reduce(
            state,
            ev("approval_request", turnId = "t1", ts = 5_000) {
                put("requestId", "r1")
                put("toolId", "toolu_1")
                put("name", "Bash")
            },
        )
        turn = state.turnsById.getValue("t1")
        assertNull(turn.run)
        assertEquals(4_000L, turn.activeMs)

        // Resolution resumes: run 1 opens.
        state = reduce(state, ev("approval_resolved", turnId = "t1", ts = 7_000) { put("requestId", "r1") })
        turn = state.turnsById.getValue("t1")
        assertNotNull(turn.run)
        assertEquals(1, turn.run!!.index)
        assertEquals(7_000L, turn.run!!.startedAt)
        assertEquals(2, turn.runCount)

        // turn_end closes the second run.
        state = reduce(state, ev("turn_end", turnId = "t1", ts = 9_000) { put("outcome", "ok") })
        turn = state.turnsById.getValue("t1")
        assertNull(turn.run)
        assertEquals(6_000L, turn.activeMs)
    }

    @Test
    fun unstampedEventLeavesRunBookkeepingAlone() {
        val state = fold(freshState(), ev("turn_started", turnId = "t1")) // no ts
        val turn = state.turnsById.getValue("t1")
        assertNull(turn.run)
        assertEquals(0, turn.runCount)
    }

    @Test
    fun apiRetryClearedByProgressButNotByApprovalRequest() {
        var state = fold(
            openTurn(),
            ev("api_retry", turnId = "t1", ts = 2_000) {
                put("attempt", 2)
                put("delayMs", 500)
                put("error", "overloaded")
            },
        )
        assertEquals(2, state.turnsById.getValue("t1").apiRetry?.attempt)

        // approval_request is NOT in the allow-list: marker stands.
        val afterApproval = reduce(
            state,
            ev("approval_request", turnId = "t1", ts = 3_000) {
                put("requestId", "r1")
                put("toolId", "toolu_1")
                put("name", "Bash")
            },
        )
        assertNotNull(afterApproval.turnsById.getValue("t1").apiRetry)

        // message_delta IS: marker clears.
        state = reduce(
            state,
            ev("message_delta", turnId = "t1", ts = 3_000) {
                put("blockId", "m:t0")
                put("text", "hi")
            },
        )
        assertNull(state.turnsById.getValue("t1").apiRetry)
    }

    @Test
    fun unknownEventTypeIsSilentNoOp() {
        val state = openTurn()
        val next = reduce(state, ev("totally_new_event", turnId = "t1", seq = 99, ts = 9_000) { put("x", 1) })
        assertSame(state, next)
    }

    @Test
    fun backgroundTaskEventsAreNoOps() {
        val state = openTurn()
        for (type in listOf("task_started", "task_progress", "task_completed", "background_tasks_changed", "background_pending")) {
            assertSame(state, reduce(state, ev(type, turnId = "t1", ts = 5_000)))
        }
    }

    @Test
    fun errorEventFirstWinsAndSessionLevelSetsLastError() {
        var state = fold(
            openTurn(),
            ev("error", turnId = "t1", ts = 2_000) { put("message", "root cause") },
            ev("error", turnId = "t1", ts = 2_100) { put("message", "generic backstop") },
        )
        assertEquals("root cause", state.turnsById.getValue("t1").error)
        state = reduce(state, evNullTurn("error", ts = 2_200) { put("message", "session-wide") })
        assertEquals("session-wide", state.lastError)
    }
}

