package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import kotlin.math.floor

/**
 * T6.4: the sub-agent run tabs' render model, a line-for-line port of the web's
 * components/subagent-run-model.mjs at PARITY_BASE, read straight off the v128 projection TREE
 * (the typed legacy projection carries neither `backgroundTasks` nor `spawnedRuns`).
 *
 * A run is a launcher tool block (Claude's Agent/Task, OpenCode's task, Codex's
 * collaboration:spawnAgent, Reasonix's task/read_only_task, Tether's mcp__tether__delegate), a
 * child thread known only from lifecycle notifications (Codex `subagent_activity`, issue #172),
 * a run another run's thread launched (depth-first, right after its spawner), or an agent-CLI
 * child linked at spawn time (`spawnedRuns`, issue #173). Status is a COMPLETION claim (v117,
 * issue #171): a background launch ticks only once its task record is terminal.
 *
 * `usage` / `totalTokens` are null, never 0, when nothing was captured. No per-run cost is
 * derived (the web removed its apportioned estimate in v42). Pure: no clock, no I/O.
 * [SubagentRunConformanceTest] checks every field against the web's own module.
 */

const val RUN_RUNNING = "running"
const val RUN_ERROR = "error"
const val RUN_DONE = "done"

/** `SubagentRun["source"]`. */
object RunSource {
    const val LAUNCHER = "launcher"
    const val DELEGATE = "delegate"
    const val THREAD = "thread"
    const val SPAWNED = "spawned"
}

/** `SubagentRun["spawned"]`: an agent-CLI child (issue #173). [media] is the filtered `media_ref` list. */
@Immutable
data class SpawnedRunInfo(
    val id: String,
    /** "spawned" or "discovered" (linked by its launch marker). */
    val origin: String,
    /** The raw status: running / finished / stopped / interrupted / error. */
    val status: String,
    val logFile: String?,
    val nativeId: String?,
    val exitCode: Int?,
    val output: String,
    val outputTruncated: Boolean,
    val media: JsArr,
)

/**
 * One run. JS `undefined` and `null` both read as Kotlin null here ([elapsedSeconds], [output],
 * [thread]); [children] holds the runs this one's thread launched (the flat list has them too).
 */
@Immutable
data class SubagentRun(
    val runId: String,
    val toolId: String,
    val turnId: String,
    val index: Int,
    val title: String,
    val agentType: String?,
    val prompt: String?,
    val requestedModel: String?,
    val requestedEffort: String?,
    val childProvider: String?,
    val delegateMode: String?,
    /** The harness that PRODUCED the run (the delegate child's for a delegate). */
    val provider: String?,
    val source: String,
    val usage: JsObj?,
    val totalTokens: Double?,
    val status: String,
    val background: Boolean,
    val taskStatus: String?,
    val unconfirmed: Boolean,
    val steps: Int,
    val elapsedSeconds: Double?,
    val thread: JsObj?,
    val output: JsValue?,
    val isError: Boolean,
    val parentRunId: String?,
    val depth: Int,
    val children: List<SubagentRun>,
    val agentThreadId: String?,
    val agentPath: String?,
    val lifecycle: List<String>,
    val spawned: SpawnedRunInfo?,
)

@Immutable
data class SubagentRosterSummary(
    val total: Int,
    val running: Int,
    val errored: Int,
    val done: Int,
    val tokens: Double?,
    val measured: Int,
    /** True when some runs contributed no readings: the totals are a subtotal. */
    val partial: Boolean,
)

private const val TETHER_DELEGATE_LAUNCHER_NAME = "mcp__tether__delegate"
private val CLAUDE_LAUNCHER_NAMES = setOf("Agent", "Task", TETHER_DELEGATE_LAUNCHER_NAME)
private val OPENCODE_LAUNCHER_NAMES = setOf("task", TETHER_DELEGATE_LAUNCHER_NAME)
private val CODEX_LAUNCHER_NAMES = setOf("collaboration:spawnAgent", TETHER_DELEGATE_LAUNCHER_NAME)
private val REASONIX_LAUNCHER_NAMES = setOf("task", "read_only_task", TETHER_DELEGATE_LAUNCHER_NAME)
private val LAUNCHER_NAMES_BY_PROVIDER = linkedMapOf(
    "claude" to CLAUDE_LAUNCHER_NAMES,
    "opencode" to OPENCODE_LAUNCHER_NAMES,
    "codex" to CODEX_LAUNCHER_NAMES,
    "reasonix" to REASONIX_LAUNCHER_NAMES,
)
private val ALL_LAUNCHER_NAMES = LAUNCHER_NAMES_BY_PROVIDER.values.flatten().toSet()

private val LIFECYCLE_KINDS = setOf("started", "interacted", "interrupted", "completed")

private class LifecycleRow(val threadId: String, val kind: String, val path: String?, val harness: String)

private class ThreadActivitySpec(val name: String, val parse: (JsValue?) -> Triple<String, String, String?>?)

private val THREAD_ACTIVITY_BY_PROVIDER = linkedMapOf(
    "codex" to ThreadActivitySpec("subagent_activity") { input ->
        val record = input as? JsObj
        val threadId = if (record != null) (record["agentThreadId"] as? JsStr)?.value?.let(::jsTrim) else ""
        val kind = if (record != null) (record["kind"] as? JsStr)?.value else null
        if (threadId.isNullOrEmpty() || kind.isNullOrEmpty() || kind !in LIFECYCLE_KINDS) {
            null
        } else {
            Triple(threadId, kind, (record!!["agentPath"] as? JsStr)?.value?.let(::jsTrim)?.ifEmpty { null })
        }
    },
)

private fun str(v: JsValue?): String? = (v as? JsStr)?.value

private fun threadActivityFor(block: JsObj, provider: String?): List<LifecycleRow>? {
    if (str(block["kind"]) != "tool") return null
    val name = str(block["name"]) ?: return null
    val specs: List<Pair<String, ThreadActivitySpec>> = when {
        provider != null && THREAD_ACTIVITY_BY_PROVIDER.containsKey(provider) -> listOf(provider to THREAD_ACTIVITY_BY_PROVIDER.getValue(provider))
        provider != null && LAUNCHER_NAMES_BY_PROVIDER.containsKey(provider) -> emptyList()
        else -> THREAD_ACTIVITY_BY_PROVIDER.entries.map { it.key to it.value }
    }
    for ((harness, spec) in specs) {
        if (spec.name != name) continue
        val first = spec.parse(block["input"])
        val second = if (truthy(block["done"])) spec.parse(block["output"]) else null
        val rows = ArrayList<LifecycleRow>(2)
        if (first != null) rows.add(LifecycleRow(first.first, first.second, first.third, harness))
        if (second != null && (first == null || (second.first == first.first && second.second != first.second))) {
            rows.add(LifecycleRow(second.first, second.second, second.third, harness))
        }
        return rows
    }
    return null
}

private fun spawnedThreadIds(block: JsObj): List<String> {
    if (str(block["name"]) != "collaboration:spawnAgent") return emptyList()
    val ids = LinkedHashSet<String>()
    val input = block["input"] as? JsObj
    (input?.get("receiverThreadIds") as? JsArr)?.forEach { id -> str(id)?.takeIf { it.isNotEmpty() }?.let(ids::add) }
    for (source in listOf(input?.get("agentsStates"), (block["output"] as? JsObj)?.get("agentsStates"))) {
        (source as? JsObj)?.keys?.forEach { id -> if (id.isNotEmpty()) ids.add(id) }
    }
    return ids.toList()
}

private fun lifecycleStatus(lifecycle: List<String>): String? {
    var status: String? = null
    for (kind in lifecycle) {
        when (kind) {
            "started", "interacted" -> status = RUN_RUNNING
            "completed" -> status = RUN_DONE
            "interrupted" -> status = RUN_ERROR
        }
    }
    return status
}

/** `/root/impeccable_finish_reviewer` → `impeccable_finish_reviewer`. */
internal fun agentPathName(path: String?): String? {
    if (path.isNullOrEmpty()) return null
    return path.split("/").filter { it.isNotEmpty() }.lastOrNull()
}

/** True for a launcher tool block, scoped to [provider]'s vocabulary (the union when unknown). */
internal fun isSubagentLauncher(block: JsObj?, provider: String? = null): Boolean {
    if (block == null || str(block["kind"]) != "tool") return false
    val name = str(block["name"]) ?: return false
    return (provider?.let { LAUNCHER_NAMES_BY_PROVIDER[it] } ?: ALL_LAUNCHER_NAMES).contains(name)
}

/** v117: the launcher's own input says it ran in the background (the explicit `true` only). */
internal fun isBackgroundLaunch(block: JsObj?): Boolean = (block?.get("input") as? JsObj)?.get("run_in_background")?.let { it is JsBool && it.value } == true

private fun deriveRunStatus(block: JsObj, task: JsObj?): String {
    if (!truthy(block["done"])) return RUN_RUNNING
    if (truthy(block["isError"])) return RUN_ERROR
    if (task != null) {
        return when (str(task["status"])) {
            "completed" -> RUN_DONE
            "failed", "stopped", "killed" -> RUN_ERROR
            else -> RUN_RUNNING
        }
    }
    return if (isBackgroundLaunch(block)) RUN_RUNNING else RUN_DONE
}

private fun totalTokensOf(usage: JsObj): Double {
    var total = 0.0
    for (key in listOf("inputTokens", "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens")) {
        val value = (usage[key] as? JsNum)?.value ?: continue
        if (value.isFinite() && value >= 0) total += value
    }
    return total
}

private const val PROMPT_TITLE_MAX_CHARS = 48

private fun jsTrimEnd(s: String): String {
    val trimmed = jsTrim("x$s")
    return trimmed.substring(1)
}

/** Codex's spawnAgent carries only a prompt: its first line, bounded. */
internal fun promptTitle(prompt: JsValue?): String? {
    val text = str(prompt) ?: return null
    val firstLine = jsTrim(text.substringBefore('\n'))
    if (firstLine.isEmpty()) return null
    if (firstLine.length <= PROMPT_TITLE_MAX_CHARS) return firstLine
    return "${jsTrimEnd(firstLine.substring(0, PROMPT_TITLE_MAX_CHARS - 1))}…"
}

private fun runTitle(input: JsValue?, agentType: String?, label: String): String {
    val record = input as? JsObj
    val description = record?.let { str(it["description"]) }
    val trimmed = description?.let(::jsTrim).orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    if (!agentType.isNullOrEmpty()) return agentType
    val fromPrompt = record?.let { promptTitle(it["prompt"]) }
    if (fromPrompt != null) return fromPrompt
    return "Sub-agent $label"
}

private fun runOutput(provider: String?, output: JsValue?): JsValue? =
    if (provider == "opencode" && output is JsStr) JsStr(taskResultText(output.value)) else output

private fun delegateResultOutput(output: JsValue?): JsValue? {
    var record = output as? JsObj
    if (record == null && output is JsStr) {
        record = try {
            JsCodec.parse(output.value) as? JsObj
        } catch (_: Exception) {
            return null
        }
    }
    val text = record?.let { str(it["text"]) } ?: return null
    if (str(record["childSessionId"]) == null) return null
    val outcome = str(record["outcome"])
    val files = (record["filesTouched"] as? JsArr)?.mapNotNull(::str).orEmpty()
    val parts = mutableListOf(text)
    if (!outcome.isNullOrEmpty() && outcome != "ok") {
        val reason = str(record["cancelReason"])
        parts.add("[outcome: $outcome${if (reason != null) " ($reason)" else ""}]")
    }
    if (files.isNotEmpty()) parts.add("Files touched: ${files.joinToString(", ")}")
    return JsStr(parts.joinToString("\n"))
}

/** A run under construction (the web mutates its run objects while it folds the level). */
private class RunBuilder(
    var run: SubagentRun,
    val lifecycle: MutableList<String> = ArrayList(),
    var lastLifecycleTurnId: String? = null,
)

private class LevelItem(val block: JsObj, val turnId: String)

private class Ctx(val taskByToolUseId: Map<String, JsObj>, val turnsById: JsObj?) {
    fun turnEnded(turnId: String?): Boolean =
        turnId != null && str((turnsById?.get(turnId) as? JsObj)?.get("status")) == "done"
}

/**
 * Every run in the session, in launch order and depth-first ([collectSubagentRuns] of the web):
 * launchers, lifecycle-only threads, nested runs right after their spawner, spawned CLI children.
 */
internal fun collectSubagentRuns(state: JsObj?): List<SubagentRun> {
    val turnOrder = state?.get("turnOrder") as? JsArr ?: return emptyList()
    val provider = str(state["provider"])
    val taskByToolUseId = HashMap<String, JsObj>()
    (state["backgroundTasks"] as? JsArr)?.forEach { value ->
        val task = value as? JsObj ?: return@forEach
        val id = str(task["toolUseId"])
        if (!id.isNullOrEmpty()) taskByToolUseId[id] = task
    }
    val turnsById = state["turnsById"] as? JsObj
    val items = ArrayList<LevelItem>()
    for (turnValue in turnOrder) {
        val turnId = str(turnValue) ?: continue
        val turn = turnsById?.get(turnId) as? JsObj ?: continue
        val blocks = turn["blocks"] as? JsArr ?: continue
        val byId = turn["blocksById"] as? JsObj
        for (blockId in blocks) {
            val block = str(blockId)?.let { byId?.get(it) } as? JsObj ?: continue
            items.add(LevelItem(block, turnId))
        }
    }
    val ctx = Ctx(taskByToolUseId, turnsById)
    val flat = ArrayList<SubagentRun>()
    // L2: a repeated runId (a duplicated block in a snapshot, a repeated order key) is one run, the
    // first: tabs, lazy keys and selection are all built on runId (the web would render both).
    val seenIds = HashSet<String>()
    fun visit(run: SubagentRun) {
        if (!seenIds.add(run.runId)) return
        flat.add(run)
        run.children.forEach(::visit)
    }
    withSpawnedRuns(collectLevel(items, provider, null, ctx), state, turnOrder, turnsById).forEach(::visit)
    return flat
}

private fun withSpawnedRuns(topLevel: List<SubagentRun>, state: JsObj, turnOrder: JsArr, turnsById: JsObj?): List<SubagentRun> {
    val spawned = (state["spawnedRuns"] as? JsArr)?.mapNotNull { v -> (v as? JsObj)?.takeIf { it["runId"] is JsStr } }.orEmpty()
    if (spawned.isEmpty()) return topLevel
    val byTurn = LinkedHashMap<String, MutableList<SubagentRun>>()
    for (run in topLevel) byTurn.getOrPut(run.turnId) { ArrayList() }.add(run)
    val merged = ArrayList<SubagentRun>()
    val placed = HashSet<String>()
    for (turnValue in turnOrder) {
        // `state.turnOrder` holds strings; a non-string key would stringify, so only strings join.
        val turnId = str(turnValue) ?: continue
        for (run in spawned) {
            if (str(run["parentTurnId"]) == turnId && turnsById?.get(turnId).let { truthy(it) }) {
                merged.add(spawnedRunEntry(run, turnId, merged.size + 1))
                placed.add(str(run["runId"])!!)
            }
        }
        byTurn.remove(turnId)?.let(merged::addAll)
    }
    for (runs in byTurn.values) merged.addAll(runs)
    for (run in spawned) {
        if (str(run["runId"]) !in placed) merged.add(spawnedRunEntry(run, str(run["parentTurnId"]) ?: "", merged.size + 1))
    }
    return merged
}

private fun spawnedRunStatus(status: String?): String = when (status) {
    "running" -> RUN_RUNNING
    "finished" -> RUN_DONE
    else -> RUN_ERROR
}

/** JS `Math.round` (halves toward +∞). */
private fun jsRound(x: Double): Double = floor(x + 0.5)

private fun spawnedRunEntry(run: JsObj, turnId: String, index: Int): SubagentRun {
    val status = spawnedRunStatus(str(run["status"]))
    val started = (run["startedAt"] as? JsNum)?.value
    val ended = (run["endedAt"] as? JsNum)?.value
    val provider = str(run["provider"])
    val runId = str(run["runId"])!!
    val exitCode = (run["exitCode"] as? JsNum)?.value?.takeIf { it.isFinite() && it == floor(it) }
    val media = (run["media"] as? JsArr)?.filterKeep { item -> item is JsObj && str(item["type"]) == "media_ref" && item["url"] is JsStr } ?: JsArr.EMPTY
    return SubagentRun(
        runId = "spawn::$runId",
        toolId = str(run["toolId"]) ?: runId,
        turnId = turnId,
        index = index,
        title = str(run["title"]) ?: promptTitle(run["prompt"]) ?: "Spawned ${provider ?: "agent"} $index",
        agentType = null,
        prompt = str(run["prompt"]),
        requestedModel = str(run["model"]),
        requestedEffort = null,
        childProvider = provider,
        delegateMode = str(run["mode"]),
        provider = provider,
        source = RunSource.SPAWNED,
        usage = null,
        totalTokens = null,
        status = status,
        background = true,
        taskStatus = null,
        unconfirmed = status == RUN_RUNNING && str(run["origin"]) == "discovered",
        steps = 0,
        elapsedSeconds = if (started != null && ended != null && ended >= started) jsRound((ended - started) / 1000) else null,
        thread = null,
        output = null,
        isError = status == RUN_ERROR,
        parentRunId = null,
        depth = 1,
        children = emptyList(),
        agentThreadId = str(run["nativeId"]),
        agentPath = null,
        lifecycle = emptyList(),
        spawned = SpawnedRunInfo(
            id = runId,
            origin = if (str(run["origin"]) == "discovered") "discovered" else "spawned",
            status = str(run["status"]) ?: "running",
            logFile = str(run["logFile"]),
            nativeId = str(run["nativeId"]),
            exitCode = exitCode?.toInt(),
            output = str(run["output"]) ?: "",
            outputTruncated = (run["outputTruncated"] as? JsBool)?.value == true,
            media = media,
        ),
    )
}

private fun collectLevel(items: List<LevelItem>, provider: String?, parent: SubagentRun?, ctx: Ctx): List<SubagentRun> {
    val runs = ArrayList<RunBuilder>()
    val runByThread = HashMap<String, RunBuilder>()
    val claimedBy = HashMap<String, String>()
    for (item in items) {
        if (!isSubagentLauncher(item.block, provider)) continue
        val blockId = str(item.block["blockId"]) ?: ""
        for (threadId in spawnedThreadIds(item.block)) claimedBy.putIfAbsent(threadId, blockId)
    }
    val pendingRows = HashMap<String, MutableList<Pair<LifecycleRow, String>>>()
    fun applyRow(builder: RunBuilder, row: LifecycleRow, turnId: String) {
        builder.lifecycle.add(row.kind)
        builder.lastLifecycleTurnId = turnId
        var run = builder.run
        if (run.agentPath.isNullOrEmpty() && !row.path.isNullOrEmpty()) run = run.copy(agentPath = row.path)
        if (run.agentType.isNullOrEmpty() && run.source != RunSource.THREAD) run = run.copy(agentType = agentPathName(row.path))
        builder.run = run
    }
    for (item in items) {
        val block = item.block
        if (isSubagentLauncher(block, provider)) {
            val blockId = str(block["blockId"]) ?: ""
            val builder = RunBuilder(launcherRun(block, item.turnId, provider, runs.size + 1, parent, ctx.taskByToolUseId[blockId]))
            runs.add(builder)
            for (threadId in spawnedThreadIds(block)) {
                if (claimedBy[threadId] != blockId) continue
                runByThread[threadId] = builder
                if (builder.run.agentThreadId.isNullOrEmpty()) builder.run = builder.run.copy(agentThreadId = threadId)
                pendingRows.remove(threadId)?.forEach { (row, turnId) -> applyRow(builder, row, turnId) }
            }
            continue
        }
        for (row in threadActivityFor(block, provider).orEmpty()) {
            var builder = runByThread[row.threadId]
            if (builder == null && claimedBy.containsKey(row.threadId)) {
                pendingRows.getOrPut(row.threadId) { ArrayList() }.add(row to item.turnId)
                continue
            }
            if (builder == null) {
                if (row.kind == "interacted") continue
                builder = RunBuilder(threadRun(row, item.turnId, runs.size + 1, parent))
                runs.add(builder)
                runByThread[row.threadId] = builder
            }
            applyRow(builder, row, item.turnId)
        }
    }
    return runs.map { builder ->
        var run = builder.run.copy(lifecycle = builder.lifecycle.toList())
        val status = lifecycleStatus(builder.lifecycle)
        if (status != null) {
            run = run.copy(
                status = status,
                isError = status == RUN_ERROR,
                unconfirmed = status == RUN_RUNNING && ctx.turnEnded(builder.lastLifecycleTurnId),
            )
        }
        val children = if (run.thread != null) collectLevel(threadItems(run), run.provider, run, ctx) else emptyList()
        run.copy(children = children)
    }
}

private fun threadItems(run: SubagentRun): List<LevelItem> {
    val thread = run.thread ?: return emptyList()
    val order = thread["order"] as? JsArr ?: return emptyList()
    val entries = thread["entries"] as? JsObj
    val out = ArrayList<LevelItem>()
    for (keyValue in order) {
        val key = str(keyValue) ?: continue
        val entry = entries?.get(key) as? JsObj ?: continue
        if (str(entry["kind"]) != "tool") continue
        val blockId = entry["key"].takeIf { it != null && it !is JsNull } ?: JsStr(key)
        out.add(
            LevelItem(
                JsObj.of(
                    "kind" to JsStr("tool"),
                    "blockId" to blockId,
                    "name" to entry["name"],
                    "input" to entry["input"],
                    "output" to entry["output"],
                    "isError" to entry["isError"],
                    "done" to entry["done"],
                    "elapsedSeconds" to entry["elapsedSeconds"],
                    "nested" to JsBool.TRUE,
                ),
                run.turnId,
            ),
        )
    }
    return out
}

private fun launcherRun(block: JsObj, turnId: String, provider: String?, index: Int, parent: SubagentRun?, task: JsObj?): SubagentRun {
    val input = block["input"] as? JsObj
    val isDelegate = str(block["name"]) == TETHER_DELEGATE_LAUNCHER_NAME
    val childProvider = if (isDelegate) (input?.let { str(it["provider"]) } ?: provider) else null
    val delegateMode = if (isDelegate) input?.let { str(it["mode"]) } else null
    val agentType = input?.let { str(it["subagent_type"]) ?: (if (provider == "reasonix") str(it["profile"]) else null) }
    val subagent = block["subagent"] as? JsObj
    val usage = subagent?.get("usage") as? JsObj
    val label = if (parent != null) "${parent.index}.$index" else "$index"
    val derived = deriveRunStatus(block, task)
    val inherited = derived == RUN_RUNNING && truthy(block["nested"]) && parent != null && parent.status != RUN_RUNNING
    val status = if (inherited) parent!!.status else derived
    val blockId = str(block["blockId"]) ?: ""
    val rawOutput = block["output"]
    return SubagentRun(
        runId = if (parent != null) "${parent.runId}/$blockId" else "$turnId::$blockId",
        toolId = blockId,
        turnId = turnId,
        index = index,
        title = runTitle(block["input"], agentType, label),
        agentType = agentType,
        prompt = input?.let { str(it["prompt"]) },
        requestedModel = input?.let { str(it["model"]) },
        requestedEffort = input?.let { str(it["effort"]) ?: str(it["reasoningEffort"]) },
        childProvider = childProvider,
        delegateMode = delegateMode,
        provider = childProvider ?: provider,
        source = if (isDelegate) RunSource.DELEGATE else RunSource.LAUNCHER,
        usage = usage,
        totalTokens = usage?.let(::totalTokensOf),
        status = status,
        background = isBackgroundLaunch(block),
        taskStatus = task?.let { str(it["status"]) },
        unconfirmed = status == RUN_RUNNING && (block["done"] as? JsBool)?.value == true && (task == null || !truthy(task["live"])),
        steps = if (truthy(block["subagent"])) (subagent?.get("order") as? JsArr)?.size ?: 0 else 0,
        elapsedSeconds = (block["elapsedSeconds"] as? JsNum)?.value,
        thread = subagent,
        output = if (isDelegate) delegateResultOutput(rawOutput) ?: runOutput(provider, rawOutput) else runOutput(provider, rawOutput),
        isError = if (inherited) parent!!.isError else truthy(block["isError"]),
        parentRunId = parent?.runId,
        depth = if (parent != null) parent.depth + 1 else 1,
        children = emptyList(),
        agentThreadId = null,
        agentPath = null,
        lifecycle = emptyList(),
        spawned = null,
    )
}

private fun threadRun(row: LifecycleRow, turnId: String, index: Int, parent: SubagentRun?): SubagentRun {
    val label = if (parent != null) "${parent.index}.$index" else "$index"
    return SubagentRun(
        runId = if (parent != null) "${parent.runId}/thread::${row.threadId}" else "thread::${row.threadId}",
        toolId = row.threadId,
        turnId = turnId,
        index = index,
        title = agentPathName(row.path) ?: "Sub-agent $label",
        agentType = null,
        prompt = null,
        requestedModel = null,
        requestedEffort = null,
        childProvider = null,
        delegateMode = null,
        provider = row.harness,
        source = RunSource.THREAD,
        usage = null,
        totalTokens = null,
        status = RUN_RUNNING,
        background = false,
        taskStatus = null,
        unconfirmed = false,
        steps = 0,
        elapsedSeconds = null,
        thread = null,
        output = null,
        isError = false,
        parentRunId = parent?.runId,
        depth = if (parent != null) parent.depth + 1 else 1,
        children = emptyList(),
        agentThreadId = row.threadId,
        agentPath = row.path,
        lifecycle = emptyList(),
        spawned = null,
    )
}

/** Which run's thread holds the tool call [toolId] (a permission denial's origin link). */
internal fun runForToolId(runs: List<SubagentRun>, toolId: String?): SubagentRun? {
    if (toolId.isNullOrEmpty()) return null
    return runs.firstOrNull { (it.thread?.get("entries") as? JsObj)?.has(toolId) == true }
}

/** ONE roster derivation for every surface that counts runs. */
internal fun subagentRosterSummary(runs: List<SubagentRun>): SubagentRosterSummary {
    var tokens: Double? = null
    var measured = 0
    var running = 0
    var errored = 0
    for (run in runs) {
        if (run.status == RUN_RUNNING) running++ else if (run.status == RUN_ERROR) errored++
        run.totalTokens?.let {
            tokens = (tokens ?: 0.0) + it
            measured++
        }
    }
    return SubagentRosterSummary(runs.size, running, errored, runs.size - running - errored, tokens, measured, measured < runs.size)
}

/** The entries of one run's thread in arrival order; thinking only when shown and non-empty. */
internal fun subagentRunEntries(run: SubagentRun?, showThinking: Boolean): List<JsObj> =
    run?.thread?.let { subagentEntries(it, showThinking) }.orEmpty()

// --- Labels (subagent-runs.tsx) --------------------------------------------------------------

internal val STATUS_TEXT = mapOf(RUN_RUNNING to "running", RUN_ERROR to "error", RUN_DONE to "done")
internal const val UNCONFIRMED_STATUS_TEXT = "running (unconfirmed)"

/** `statusLabel`: an unconfirmed running run says so in words (never by colour or icon alone). */
internal fun statusLabel(run: SubagentRun): String =
    if (run.status == RUN_RUNNING && run.unconfirmed) UNCONFIRMED_STATUS_TEXT else STATUS_TEXT.getValue(run.status)

/** `runStatusText`: a spawned run's raw status (finished / stopped …) unless unconfirmed. */
internal fun runStatusText(run: SubagentRun): String =
    if (run.spawned != null && !run.unconfirmed) run.spawned.status else statusLabel(run)

/** `harnessLabel`: how a tab / roster row names the harness that produced the run. */
internal fun harnessLabel(run: SubagentRun): String? {
    val provider = run.provider?.takeIf { it.isNotEmpty() } ?: return null
    if (run.source == RunSource.DELEGATE) return "$provider delegate"
    run.spawned?.let { return if (it.origin == "discovered") "$provider run (linked by marker)" else "$provider spawned" }
    return "$provider sub-agent"
}

/** `usageGapReason`: why a run has no token reading. */
internal fun usageGapReason(run: SubagentRun): String = when {
    run.spawned != null -> "A spawned ${run.provider?.takeIf { it.isNotEmpty() } ?: "CLI"} child records its token usage in its own native session"
    run.source == RunSource.THREAD ->
        "${run.provider?.takeIf { it.isNotEmpty() } ?: "The harness"} reports this sub-agent's token usage on its own thread, which is not streamed to this session"
    run.depth > 1 -> "A nested sub-agent's token usage is not reported separately from the run that spawned it"
    else -> "Sub-agent token counts are not captured for resumed sessions"
}

/** `runTabTitle`: the tab's full description (the web's `title`; here its accessibility label). */
internal fun runTabTitle(run: SubagentRun, runsById: Map<String, SubagentRun>): String {
    val parent = run.parentRunId?.let { runsById[it] }
    return listOfNotNull(
        run.title,
        run.agentType,
        harnessLabel(run),
        run.delegateMode,
        parent?.let { "spawned by ${it.title}" },
        runStatusText(run),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The tab's short meta: status while running or for a thread child, a spawned run's status, else the step count. */
internal fun runTabMeta(run: SubagentRun): String = when {
    run.status == RUN_RUNNING || run.source == RunSource.THREAD -> STATUS_TEXT.getValue(run.status)
    run.spawned != null -> runStatusText(run)
    else -> stepsLabel(run.steps)
}

internal fun stepsLabel(steps: Int): String = "$steps step${if (steps == 1) "" else "s"}"

/** The roster head's summary: "2 running · 1 failed · 12K tok" (only the parts that apply). */
internal fun rosterSummaryText(summary: SubagentRosterSummary, compact: (Double) -> String): String = buildString {
    if (summary.running > 0) append("${summary.running} running")
    if (summary.running > 0 && (summary.errored > 0 || summary.tokens != null)) append(" · ")
    if (summary.errored > 0) append("${summary.errored} failed")
    if (summary.errored > 0 && summary.tokens != null) append(" · ")
    summary.tokens?.let { append("${compact(it)} tok") }
}
