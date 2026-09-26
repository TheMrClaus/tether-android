package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.ModelUsageEntry
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.SubagentThread
import com.tether.app.protocol.model.SubagentUsage
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.TurnUsage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentRunModelTest {

    private fun toolBlock(
        id: String,
        name: String,
        input: kotlinx.serialization.json.JsonObject? = null,
        done: Boolean? = true,
        isError: Boolean? = false,
        thread: SubagentThread? = null,
    ) = TurnBlock(
        blockId = id, kind = "tool", name = name, input = input,
        done = done, isError = isError, subagent = thread,
    )

    private fun projectionOf(vararg turns: TurnProjection) = SessionProjection(
        tetherSessionId = "s1", provider = "claude", cwd = "/w",
        turnOrder = turns.map { it.turnId },
        turnsById = turns.associateBy { it.turnId },
    )

    private fun turnOf(id: String, vararg blocks: TurnBlock, usage: TurnUsage? = null) = TurnProjection(
        turnId = id, status = "done",
        blocks = blocks.map { it.blockId },
        blocksById = blocks.associateBy { it.blockId },
        usage = usage,
    )

    @Test
    fun launcherDetectionAcceptsAgentAndLegacyTaskOnly() {
        assertTrue(isSubagentLauncher(toolBlock("b1", "Agent")))
        assertTrue(isSubagentLauncher(toolBlock("b2", "Task")))
        assertTrue(!isSubagentLauncher(toolBlock("b3", "Bash")))
        assertTrue(!isSubagentLauncher(TurnBlock(blockId = "m1", kind = "message", text = "hi")))
    }

    @Test
    fun runsCollectInLaunchOrderWithIdentityAndFallbackTitles() {
        val withDescription = toolBlock(
            "t1", "Agent",
            buildJsonObject { put("description", "  scan the repo  "); put("subagent_type", "Explore") },
        )
        val withTypeOnly = toolBlock("t2", "Task", buildJsonObject { put("subagent_type", "general-purpose") })
        val bare = toolBlock("t3", "Agent", buildJsonObject { })
        val notLauncher = toolBlock("t4", "Bash", buildJsonObject { put("command", "ls") })
        val projection = projectionOf(turnOf("turn-1", withDescription, withTypeOnly, bare, notLauncher))

        val runs = collectSubagentRuns(projection)
        assertEquals(3, runs.size)
        assertEquals("turn-1::t1", runs[0].runId)
        assertEquals("t1", runs[0].toolId)
        assertEquals(1, runs[0].index)
        assertEquals(3, runs[2].index)
        assertEquals("scan the repo", runs[0].title) // trimmed description wins
        assertEquals("general-purpose", runs[1].title) // then the agent type
        assertEquals("Sub-agent 3", runs[2].title) // then launch order
        assertEquals("Explore", runs[0].agentType)
    }

    @Test
    fun statusMapsFromDoneAndErrorFlags() {
        val projection = projectionOf(
            turnOf(
                "turn-1",
                toolBlock("live", "Agent", done = null),
                toolBlock("failed", "Agent", done = true, isError = true),
                toolBlock("ok", "Agent", done = true, isError = false),
            ),
        )
        val runs = collectSubagentRuns(projection)
        assertEquals(RUN_RUNNING, runs[0].status)
        assertEquals(RUN_ERROR, runs[1].status)
        assertEquals(RUN_DONE, runs[2].status)
    }

    @Test
    fun usageIsNullNotZeroWhenNothingWasCaptured() {
        val noThread = toolBlock("t1", "Agent")
        val thread = SubagentThread(
            order = listOf("e1"),
            entries = mapOf("e1" to SubagentEntry(key = "e1", kind = "message", text = "hi")),
            usage = SubagentUsage(model = "claude-fable-5", inputTokens = 100, outputTokens = 50),
        )
        val measured = toolBlock("t2", "Agent", thread = thread)
        val usage = TurnUsage(
            modelUsages = listOf(
                ModelUsageEntry(model = "claude-fable-5", inputTokens = 300, outputTokens = 150, costUSD = 0.90),
            ),
        )
        val runs = collectSubagentRuns(projectionOf(turnOf("turn-1", noThread, measured, usage = usage)))

        assertNull(runs[0].totalTokens)
        assertNull(runs[0].estimatedCostUSD)
        assertEquals(0, runs[0].steps)
        assertEquals(150L, runs[1].totalTokens)
        assertEquals(1, runs[1].steps)
        // Share: 150/450 of $0.90 = $0.30.
        assertEquals(0.30, runs[1].estimatedCostUSD!!, 0.0001)
    }

    @Test
    fun apportionedCostFallsBackToCanonicalModelAndClampsTheShare() {
        val usage = SubagentUsage(model = "claude-fable-5", inputTokens = 900, outputTokens = 100)
        val canonical = listOf(
            ModelUsageEntry(model = "other", canonicalModel = "claude-fable-5", inputTokens = 10, outputTokens = 10, costUSD = 1.20),
        )
        // runTokens (1000) > modelTokens (20): the share clamps to 1.0 -> full $1.20, never more.
        assertEquals(1.20, apportionedRunCostUSD(usage, canonical)!!, 0.0001)

        assertNull(apportionedRunCostUSD(null, canonical))
        assertNull(apportionedRunCostUSD(SubagentUsage(model = null, outputTokens = 5), canonical))
        assertNull(apportionedRunCostUSD(usage, null))
        assertNull(apportionedRunCostUSD(usage, listOf(ModelUsageEntry(model = "unrelated", costUSD = 1.0))))
        assertNull(apportionedRunCostUSD(usage, listOf(ModelUsageEntry(model = "claude-fable-5", costUSD = null))))
        assertNull(apportionedRunCostUSD(usage, listOf(ModelUsageEntry(model = "claude-fable-5", costUSD = 1.0)))) // no tokens
    }

    @Test
    fun rosterSummaryCountsAndFlagsPartialTotals() {
        val projection = projectionOf(
            turnOf(
                "turn-1",
                toolBlock("a", "Agent", done = null),
                toolBlock(
                    "b", "Agent",
                    thread = SubagentThread(
                        order = listOf("e1"),
                        entries = mapOf("e1" to SubagentEntry(key = "e1", kind = "message", text = "x")),
                        usage = SubagentUsage(outputTokens = 40),
                    ),
                ),
                toolBlock("c", "Agent", done = true, isError = true),
            ),
        )
        val summary = subagentRosterSummary(collectSubagentRuns(projection))
        assertEquals(3, summary.total)
        assertEquals(1, summary.running)
        assertEquals(1, summary.errored)
        assertEquals(1, summary.done)
        assertEquals(40L, summary.tokens)
        assertEquals(1, summary.measured)
        assertTrue(summary.partial) // 1 of 3 measured

        val empty = subagentRosterSummary(emptyList())
        assertEquals(0, empty.total)
        assertNull(empty.tokens)
        assertTrue(!empty.partial)
    }

    @Test
    fun entriesRespectTheThinkingPreference() {
        val run = SubagentRun(
            runId = "turn-1::t1", toolId = "t1", turnId = "turn-1", index = 1,
            title = "t", agentType = null, prompt = null, requestedModel = null,
            requestedEffort = null, usage = null, totalTokens = null,
            estimatedCostUSD = null, status = RUN_DONE, steps = 3,
            elapsedSeconds = null, output = null, isError = false,
            thread = SubagentThread(
                order = listOf("e1", "e2", "e3"),
                entries = mapOf(
                    "e1" to SubagentEntry(key = "e1", kind = "thinking", text = "hmm"),
                    "e2" to SubagentEntry(key = "e2", kind = "thinking", text = ""),
                    "e3" to SubagentEntry(key = "e3", kind = "message", text = "hello"),
                ),
            ),
        )
        assertEquals(listOf("e3"), subagentRunEntries(run, showThinking = false).map { it.key })
        assertEquals(listOf("e1", "e3"), subagentRunEntries(run, showThinking = true).map { it.key })
        assertEquals(emptyList<SubagentEntry>(), subagentRunEntries(null, showThinking = true))
    }

    @Test
    fun negativeTokenCountsAreFilteredFromTotals() {
        val usage = SubagentUsage(model = "claude-fable-5", inputTokens = -50, outputTokens = 100)
        val modelUsages = listOf(
            ModelUsageEntry(model = "claude-fable-5", inputTokens = 100, outputTokens = 100, costUSD = 1.0),
        )
        // The -50 is dropped, not summed: run share is 100/200 of $1.00.
        assertEquals(0.50, apportionedRunCostUSD(usage, modelUsages)!!, 0.0001)
    }

    @Test
    fun whitespaceOnlyDescriptionFallsThroughToAgentType() {
        val block = toolBlock(
            "t1", "Agent",
            buildJsonObject { put("description", "   "); put("subagent_type", "Explore") },
        )
        val runs = collectSubagentRuns(projectionOf(turnOf("turn-1", block)))
        assertEquals("Explore", runs.single().title)
    }

    @Test
    fun promptAndRequestedModelAndEffortComeOffTheToolInput() {
        val block = toolBlock(
            "t1", "Agent",
            buildJsonObject {
                put("description", "d")
                put("prompt", "scan everything")
                put("model", "claude-fable-5")
                put("effort", "high")
            },
        )
        val run = collectSubagentRuns(projectionOf(turnOf("turn-1", block))).single()
        assertEquals("scan everything", run.prompt)
        assertEquals("claude-fable-5", run.requestedModel)
        assertEquals("high", run.requestedEffort)
    }

    @Test
    fun runIdsPairTurnAndToolSoACollisionAcrossTurnsCannotHappen() {
        val first = toolBlock("toolu_same", "Agent")
        val second = toolBlock("toolu_same", "Agent")
        val projection = projectionOf(turnOf("turn-1", first), turnOf("turn-2", second))
        val runs = collectSubagentRuns(projection)
        assertEquals(listOf("turn-1::toolu_same", "turn-2::toolu_same"), runs.map { it.runId })
        assertEquals(listOf(1, 2), runs.map { it.index }) // launch order/index continuity across turns
    }
}
