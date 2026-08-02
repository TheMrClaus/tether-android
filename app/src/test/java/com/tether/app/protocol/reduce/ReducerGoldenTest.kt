package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.Vocab
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the golden expectations in specs/reducer-spec.md §3.6. */
class ReducerGoldenTest {

    @Test
    fun goldenTurnFold() {
        val initial = freshState()
        val initialCopy = freshState()
        val toolId = "toolu_01"
        val events = arrayOf(
            ev("turn_started", turnId = "t1", seq = 1, ts = 1_000) { put("idempotencyKey", "key-1") },
            ev("tool_start", turnId = "t1", seq = 2, ts = 1_100) {
                put("toolId", toolId)
                put("name", "Bash")
                put("input", buildJsonObject { put("command", "echo hi") })
            },
            ev("token_progress", turnId = "t1", seq = 3, ts = 1_200) { put("tokens", 99) },
            ev("tool_end", turnId = "t1", seq = 4, ts = 1_300) {
                put("toolId", toolId)
                put("output", "hello-from-tool-spike")
                put("isError", false)
            },
            ev("message_started", turnId = "t1", seq = 5, ts = 1_400) { put("blockId", "msg1:t0") },
            ev("message_delta", turnId = "t1", seq = 6, ts = 1_500) {
                put("blockId", "msg1:t0")
                put("text", "partial")
            },
            // thinking_stop is patch-only: with no prior thinking block it must not create one.
            ev("thinking_stop", turnId = "t1", seq = 7, ts = 1_550) { put("blockId", "msg1:th0") },
            ev("message_completed", turnId = "t1", seq = 8, ts = 1_600) {
                put("blockId", "msg1:t0")
                put("text", "final text")
            },
            ev("usage", turnId = "t1", seq = 9, ts = 1_700) {
                put("model", "claude-sonnet-5")
                put("perTurnTokens", 115)
            },
            ev("turn_end", turnId = "t1", seq = 10, ts = 1_800) { put("outcome", "ok") },
        )
        val state = fold(initial, *events)

        assertEquals(Vocab.SESSION_READY, state.status)
        assertEquals("ok", state.lastTurnOutcome)
        assertNull(state.activeTurnId)

        val turn = state.turnsById.getValue("t1")
        assertEquals("key-1", turn.idempotencyKey)
        assertEquals(Vocab.TURN_DONE, turn.status)
        assertEquals("ok", turn.outcome)
        // NO thinking block: order is exactly [toolId, msg1:t0].
        assertEquals(listOf(toolId, "msg1:t0"), turn.blocks)

        val tool = turn.blocksById.getValue(toolId)
        assertEquals("tool", tool.kind)
        assertEquals("Bash", tool.name)
        assertEquals(true, tool.done)
        assertEquals(false, tool.isError)
        assertEquals(JsonPrimitive("hello-from-tool-spike"), tool.output)
        assertNull(tool.elapsedSeconds)

        val message = turn.blocksById.getValue("msg1:t0")
        assertEquals("final text", message.text)
        assertEquals(true, message.done)
        assertNull(message.aborted)

        assertEquals(99L, turn.liveTokens)
        assertEquals("claude-sonnet-5", turn.usage?.model)
        assertEquals(115L, turn.usage?.perTurnTokens)

        // Purity: input state must not have been mutated by folding.
        assertEquals(initialCopy, initial)
    }

    @Test
    fun toolProgressSetThenClearedByToolEnd() {
        var state = fold(
            freshState(),
            ev("turn_started", turnId = "t1", ts = 1_000),
            ev("tool_start", turnId = "t1", ts = 1_100) {
                put("toolId", "toolu_x")
                put("name", "Bash")
            },
            ev("tool_progress", turnId = "t1", ts = 1_200) {
                put("toolId", "toolu_x")
                put("elapsedSeconds", 3.5)
            },
        )
        assertEquals(3.5, state.turnsById.getValue("t1").blocksById.getValue("toolu_x").elapsedSeconds!!, 0.0)
        state = reduce(
            state,
            ev("tool_end", turnId = "t1", ts = 1_300) {
                put("toolId", "toolu_x")
                put("output", "done")
                put("isError", false)
            },
        )
        val block = state.turnsById.getValue("t1").blocksById.getValue("toolu_x")
        assertNull(block.elapsedSeconds)
        assertEquals(true, block.done)
        // name preserved via merge, pairing purely by toolId.
        assertEquals("Bash", block.name)
        assertTrue(state.turnsById.getValue("t1").blocks.count { it == "toolu_x" } == 1)
    }
}
