package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.ModelUsageEntry
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.SubagentThread
import com.tether.app.protocol.model.SubagentUsage
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.Vocab
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure render model for the sub-agent run tabs — Kotlin port of
 * aidash/components/subagent-run-model.mjs. A sub-agent run is an Agent/Task
 * tool block carrying a folded `subagent` thread. DERIVED from the existing
 * SessionProjection: no protocol change, no reducer change.
 *
 * `totalTokens`/`estimatedCostUSD` are NULL, never 0, when unmeasured: a
 * resumed session replays no child records, and 0 would assert the run cost
 * nothing. Renderers must show those as unavailable, never as a zero reading.
 */

const val RUN_RUNNING = "running"
const val RUN_ERROR = "error"
const val RUN_DONE = "done"

// The launcher tool is named `Agent` in the pinned Agent SDK and was
// historically `Task`; accept both so replayed older transcripts still group.
private val SUBAGENT_LAUNCHER_NAMES = setOf("Agent", "Task")

data class SubagentRun(
    /** `"$turnId::$blockId"` — the parent tool_use id is the run's identity. */
    val runId: String,
    val toolId: String,
    val turnId: String,
    val index: Int,
    val title: String,
    val agentType: String?,
    val prompt: String?,
    val requestedModel: String?,
    val requestedEffort: String?,
    val usage: SubagentUsage?,
    val totalTokens: Long?,
    val estimatedCostUSD: Double?,
    val status: String,
    val steps: Int,
    val elapsedSeconds: Double?,
    val output: JsonElement?,
    val isError: Boolean,
    val thread: SubagentThread?,
)

data class SubagentRosterSummary(
    val total: Int,
    val running: Int,
    val errored: Int,
    val done: Int,
    val tokens: Long?,
    val estimatedCostUSD: Double?,
    val measured: Int,
    /** True when some runs contributed no readings — mark totals as partial. */
    val partial: Boolean,
)

/** True for a projected tool block that launched a sub-agent. */
fun isSubagentLauncher(block: TurnBlock): Boolean =
    block.kind == Vocab.BLOCK_TOOL && block.name != null && SUBAGENT_LAUNCHER_NAMES.contains(block.name)

private fun inputString(input: JsonElement?, field: String): String? =
    ((input as? JsonObject)?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Sum the four token counts the same way on both sides of an apportionment. */
private fun tokenTotal(vararg values: Long?): Long {
    var total = 0L
    for (value in values) if (value != null && value >= 0) total += value
    return total
}

/**
 * APPORTIONED per-run cost: this run's share of its served model's cost, by
 * token share — an ESTIMATE (the price per token is not uniform across
 * input/output/cache tiers), never billing. Null whenever it cannot be
 * computed honestly. Clamped so a lagging per-model total cannot let one run
 * claim more than the whole model's cost.
 */
fun apportionedRunCostUSD(usage: SubagentUsage?, modelUsages: List<ModelUsageEntry>?): Double? {
    val model = usage?.model ?: return null
    if (modelUsages == null) return null
    val entry = modelUsages.firstOrNull { it.model == model }
        ?: modelUsages.firstOrNull { it.canonicalModel == model }
        ?: return null
    val cost = entry.costUSD?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val modelTokens = tokenTotal(entry.inputTokens, entry.outputTokens, entry.cacheReadInputTokens, entry.cacheCreationInputTokens)
    val runTokens = tokenTotal(usage.inputTokens, usage.outputTokens, usage.cacheReadInputTokens, usage.cacheCreationInputTokens)
    if (modelTokens <= 0 || runTokens <= 0) return null
    val share = minOf(runTokens.toDouble() / modelTokens.toDouble(), 1.0)
    return cost * share
}

/**
 * Index every Agent/Task tool block in the session, in launch order, as a run.
 * A run appears as soon as its tool block does — before any nested activity.
 */
fun collectSubagentRuns(state: SessionProjection?): List<SubagentRun> {
    if (state == null) return emptyList()
    val runs = mutableListOf<SubagentRun>()
    for (turnId in state.turnOrder) {
        val turn = state.turnsById[turnId] ?: continue
        for (blockId in turn.blocks) {
            val block = turn.blocksById[blockId] ?: continue
            if (!isSubagentLauncher(block)) continue
            val index = runs.size + 1
            val agentType = inputString(block.input, "subagent_type")
            val description = inputString(block.input, "description")?.trim().orEmpty()
            val title = when {
                description.isNotEmpty() -> description
                agentType != null -> agentType
                else -> "Sub-agent $index"
            }
            val usage = block.subagent?.usage
            runs += SubagentRun(
                runId = "$turnId::$blockId",
                toolId = block.blockId,
                turnId = turnId,
                index = index,
                title = title,
                agentType = agentType,
                prompt = inputString(block.input, "prompt"),
                requestedModel = inputString(block.input, "model"),
                requestedEffort = inputString(block.input, "effort"),
                usage = usage,
                totalTokens = usage?.let {
                    tokenTotal(it.inputTokens, it.outputTokens, it.cacheReadInputTokens, it.cacheCreationInputTokens)
                },
                estimatedCostUSD = apportionedRunCostUSD(usage, turn.usage?.modelUsages),
                status = if (block.done != true) RUN_RUNNING else if (block.isError == true) RUN_ERROR else RUN_DONE,
                steps = block.subagent?.order?.size ?: 0,
                elapsedSeconds = block.elapsedSeconds,
                output = block.output,
                isError = block.isError == true,
                thread = block.subagent,
            )
        }
    }
    return runs
}

/** The entries of one run's thread in arrival order, thinking filtered by pref. */
fun subagentRunEntries(run: SubagentRun?, showThinking: Boolean): List<SubagentEntry> {
    val thread = run?.thread ?: return emptyList()
    return thread.order
        .mapNotNull { thread.entries[it] }
        .filter { it.kind != "thinking" || (showThinking && !it.text.isNullOrEmpty()) }
}

/**
 * Whole-session roster summary. `tokens`/`estimatedCostUSD` are null unless at
 * least one run reported a value, and sum only the runs that DID report;
 * `partial` says some runs are unmeasured (show totals as a subtotal).
 */
fun subagentRosterSummary(runs: List<SubagentRun>): SubagentRosterSummary {
    var tokens: Long? = null
    var cost: Double? = null
    var measured = 0
    var running = 0
    var errored = 0
    for (run in runs) {
        if (run.status == RUN_RUNNING) running += 1 else if (run.status == RUN_ERROR) errored += 1
        run.totalTokens?.let { tokens = (tokens ?: 0L) + it; measured += 1 }
        run.estimatedCostUSD?.let { cost = (cost ?: 0.0) + it }
    }
    return SubagentRosterSummary(
        total = runs.size,
        running = running,
        errored = errored,
        done = runs.size - running - errored,
        tokens = tokens,
        estimatedCostUSD = cost,
        measured = measured,
        partial = measured < runs.size,
    )
}
