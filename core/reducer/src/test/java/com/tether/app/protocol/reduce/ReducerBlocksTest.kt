package com.tether.app.protocol.reduce

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerBlocksTest {

    private fun openTurn() = fold(
        freshState(),
        ev("turn_started", turnId = "t1", ts = 1_000),
    )

    @Test
    fun messageDeltaAppendsThenCompletedReplacesSameBlock() {
        var state = fold(
            openTurn(),
            ev("message_started", turnId = "t1", ts = 1_100) { put("blockId", "m1:t0") },
            ev("message_delta", turnId = "t1", ts = 1_200) {
                put("blockId", "m1:t0")
                put("text", "Hello ")
            },
            ev("message_delta", turnId = "t1", ts = 1_300) {
                put("blockId", "m1:t0")
                put("text", "world")
            },
        )
        var turn = state.turnsById.getValue("t1")
        assertEquals(listOf("m1:t0"), turn.blocks)
        assertEquals("Hello world", turn.blocksById.getValue("m1:t0").text)
        assertEquals(false, turn.blocksById.getValue("m1:t0").done)

        // Final completed record shares the blockId: replaced, not duplicated.
        state = reduce(
            state,
            ev("message_completed", turnId = "t1", ts = 1_400) {
                put("blockId", "m1:t0")
                put("text", "Hello world!")
            },
        )
        turn = state.turnsById.getValue("t1")
        assertEquals(listOf("m1:t0"), turn.blocks)
        assertEquals("Hello world!", turn.blocksById.getValue("m1:t0").text)
        assertEquals(true, turn.blocksById.getValue("m1:t0").done)
    }

    @Test
    fun messageStartedIsIdempotentAndNeverWipesDeltas() {
        val state = fold(
            openTurn(),
            ev("message_delta", turnId = "t1", ts = 1_100) {
                put("blockId", "m1:t0")
                put("text", "partial")
            },
            ev("message_started", turnId = "t1", ts = 1_200) { put("blockId", "m1:t0") },
        )
        assertEquals("partial", state.turnsById.getValue("t1").blocksById.getValue("m1:t0").text)
    }

    @Test
    fun abortedIsOnlyEverLiteralTrue() {
        val state = fold(
            openTurn(),
            ev("message_completed", turnId = "t1", ts = 1_100) {
                put("blockId", "m1:t0")
                put("text", "cut")
                put("aborted", true)
            },
            ev("message_completed", turnId = "t1", ts = 1_200) {
                put("blockId", "m1:t1")
                put("text", "fine")
                put("aborted", false) // malformed/legacy false normalized away
            },
        )
        val turn = state.turnsById.getValue("t1")
        assertEquals(true, turn.blocksById.getValue("m1:t0").aborted)
        assertNull(turn.blocksById.getValue("m1:t1").aborted)
    }

    @Test
    fun thinkingFlowAndThinkingStopPatchOnly()  {
        var state = fold(
            openTurn(),
            ev("thinking_delta", turnId = "t1", ts = 1_100) {
                put("blockId", "m1:th0")
                put("text", "hmm ")
            },
            ev("thinking_delta", turnId = "t1", ts = 1_200) {
                put("blockId", "m1:th0")
                put("text", "ok")
            },
            ev("thinking_stop", turnId = "t1", ts = 1_300) { put("blockId", "m1:th0") },
        )
        var turn = state.turnsById.getValue("t1")
        assertEquals("hmm ok", turn.blocksById.getValue("m1:th0").text)
        assertEquals(true, turn.blocksById.getValue("m1:th0").done)
        assertEquals("thinking", turn.blocksById.getValue("m1:th0").kind)

        // thinking_stop for an absent block never creates one.
        val before = state
        state = reduce(state, ev("thinking_stop", turnId = "t1", ts = 1_400) { put("blockId", "m1:th9") })
        assertSame(before, state)
    }

    @Test
    fun toolOutputDeltaAppendsAsString() {
        val state = fold(
            openTurn(),
            ev("tool_start", turnId = "t1", ts = 1_100) {
                put("toolId", "toolu_1")
                put("name", "Bash")
            },
            ev("tool_output_delta", turnId = "t1", ts = 1_200) {
                put("toolId", "toolu_1")
                put("chunk", "line1\n")
            },
            ev("tool_output_delta", turnId = "t1", ts = 1_300) {
                put("toolId", "toolu_1")
                put("chunk", "line2")
            },
        )
        assertEquals(
            JsonPrimitive("line1\nline2"),
            state.turnsById.getValue("t1").blocksById.getValue("toolu_1").output,
        )
    }

    @Test
    fun toolEndWithoutStartStillCreatesTheBlock() {
        val state = fold(
            openTurn(),
            ev("tool_end", turnId = "t1", ts = 1_100) {
                put("toolId", "toolu_late")
                put("output", "result")
                put("isError", true)
            },
        )
        val block = state.turnsById.getValue("t1").blocksById.getValue("toolu_late")
        assertEquals("tool", block.kind)
        assertEquals(true, block.done)
        assertEquals(true, block.isError)
        assertEquals(listOf("toolu_late"), state.turnsById.getValue("t1").blocks)
    }

    @Test
    fun toolProgressNeverCreatesAndSubagentNestedProgressPatchesDoneTurn() {
        // Progress without a tool card: no-op.
        val bare = openTurn()
        assertSame(bare, reduce(bare, ev("tool_progress", turnId = "t1", ts = 1_100) {
            put("toolId", "toolu_missing")
            put("elapsedSeconds", 2)
        }))

        // Build a parent Task card with one nested subagent tool entry.
        var state = fold(
            openTurn(),
            ev("tool_start", turnId = "t1", ts = 1_100) {
                put("toolId", "task_1")
                put("name", "Task")
            },
            ev("subagent_message", turnId = "t1", ts = 1_200) {
                put("parentToolUseId", "task_1")
                putJsonArray("items") {
                    addJsonObject {
                        put("kind", "tool")
                        put("key", "toolu_child")
                        put("name", "Bash")
                    }
                }
            },
            ev("turn_end", turnId = "t1", ts = 1_300) { put("outcome", "ok") },
        )
        // Nested progress may amend the DONE turn (documented exception).
        state = reduce(state, ev("tool_progress", turnId = "t1", ts = 1_400) {
            put("toolId", "toolu_child")
            put("parentToolUseId", "task_1")
            put("elapsedSeconds", 7.0)
        })
        val child = state.turnsById.getValue("t1")
            .blocksById.getValue("task_1").subagent!!.entries.getValue("toolu_child")
        assertEquals(7.0, child.elapsedSeconds!!, 0.0)

        // Nested tool_result clears elapsed and closes the entry.
        state = reduce(state, ev("subagent_message", turnId = "t1", ts = 1_500) {
            put("parentToolUseId", "task_1")
            putJsonArray("items") {
                addJsonObject {
                    put("kind", "tool_result")
                    put("key", "toolu_child")
                    put("output", "done!")
                    put("isError", false)
                }
            }
            put("usage", kotlinx.serialization.json.buildJsonObject {
                put("model", "claude-haiku-5")
                put("outputTokens", 42)
            })
        })
        val closed = state.turnsById.getValue("t1")
            .blocksById.getValue("task_1").subagent!!
        assertEquals(true, closed.entries.getValue("toolu_child").done)
        assertNull(closed.entries.getValue("toolu_child").elapsedSeconds)
        assertEquals("Bash", closed.entries.getValue("toolu_child").name)
        assertEquals(42L, closed.usage?.outputTokens)

        // Usage-less follow-up must not blank the carried usage (SET, never blank).
        state = reduce(state, ev("subagent_message", turnId = "t1", ts = 1_600) {
            put("parentToolUseId", "task_1")
            putJsonArray("items") {
                addJsonObject {
                    put("kind", "message")
                    put("key", "sm1:t0")
                    put("text", "child says hi")
                }
            }
        })
        val thread = state.turnsById.getValue("t1").blocksById.getValue("task_1").subagent!!
        assertEquals(42L, thread.usage?.outputTokens)
        assertEquals(listOf("toolu_child", "sm1:t0"), thread.order)
    }

    @Test
    fun subagentMessageDropsWhenParentCardMissing() {
        val state = openTurn()
        val next = reduce(state, ev("subagent_message", turnId = "t1", ts = 1_100) {
            put("parentToolUseId", "task_none")
            putJsonArray("items") {
                addJsonObject {
                    put("kind", "message")
                    put("key", "k1")
                    put("text", "orphan")
                }
            }
        })
        assertSame(state, next)
    }

    @Test
    fun userMessageAcceptedMintsUserBlockAndReplacesWholesale() {
        var state = fold(
            openTurn(),
            ev("user_message_accepted", turnId = "t1", ts = 1_100) {
                put("text", "first")
                putJsonArray("attachments") {
                    addJsonObject {
                        put("name", "shot.png")
                        put("mediaType", "image/png")
                    }
                }
            },
        )
        var block = state.turnsById.getValue("t1").blocksById.getValue("user:t1")
        assertEquals("user_message", block.kind)
        assertEquals("first", block.text)
        assertEquals("shot.png", block.attachments?.single()?.name)

        state = reduce(state, ev("user_message_accepted", turnId = "t1", ts = 1_200) { put("text", "second") })
        block = state.turnsById.getValue("t1").blocksById.getValue("user:t1")
        assertEquals("second", block.text)
        assertNull(block.attachments) // wholesale replace
        assertEquals(listOf("user:t1"), state.turnsById.getValue("t1").blocks)
        assertTrue(state.turnsById.getValue("t1").blocks.size == 1)
    }
}
