# Chat Controls Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Android app to chat-controls parity with the mobile web app — permission-mode selector, model selector with slash-command autocomplete, sub-agent tabs — and fix the IME gap bug.

**Architecture:** Mirror the web's split of pure derivation files (unit-tested, no UI imports) and render layers. No server, wire-schema, or reducer changes — everything derives from data the app already receives; the two new client frames are already in `specs/protocol-spec.md` §3. Spec: `specs/2026-08-03-chat-controls-design.md`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `DropdownMenu`), kotlinx.serialization, JUnit4 + MockWebServer for tests.

**Repo:** `/home/marcelloc/git/tether-android`. All paths below are relative to it. All commands run with `workdir=/home/marcelloc/git/tether-android`.

> **Git policy (repo owner's rule, overrides the skill template): do NOT run `git add`/`git commit` at any step.** The owner commits explicitly. Tasks end at "tests pass / build passes".

---

### Task 1: IME gap fix (windowSoftInputMode)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add `adjustResize` to MainActivity**

Root cause of the "massive white gap between keyboard and text box": no
`windowSoftInputMode` is declared, so under enforced edge-to-edge (targetSdk 36)
the default `adjustPan` pans the window AND the composer's `imePadding()` pads
it — a double offset. With `adjustResize`, `imePadding()` alone owns placement.

Change the activity element to:

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
```

- [ ] **Step 2: Verify the build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manual verification (device/emulator — note for the owner)**

Open a session, tap the text field: the composer rides tight above the
keyboard with no gap; the transcript resizes above it. (Not automatable here.)

---

### Task 2: `modelsDiverge` port

**Files:**
- Test: `app/src/test/java/com/tether/app/protocol/reduce/ModelIdTest.kt`
- Create: `app/src/main/java/com/tether/app/protocol/reduce/ModelId.kt`

- [ ] **Step 1: Write the failing test** (cases transcribed from `aidash/tests/model-id.test.mjs`)

```kotlin
package com.tether.app.protocol.reduce

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIdTest {

    @Test
    fun identicalIdsDoNotDiverge() {
        assertFalse(modelsDiverge("claude-fable-5", "claude-fable-5"))
    }

    @Test
    fun differentFamiliesDiverge() {
        assertTrue(modelsDiverge("claude-fable-5", "claude-opus-4-8")) // picked Fable, served Opus
        assertTrue(modelsDiverge("claude-opus-4-8", "claude-opus-4-5"))
        assertTrue(modelsDiverge("claude-fable-5", "Opus 4.5"))
    }

    @Test
    fun displayNameContainedInIdDoesNotDiverge() {
        assertFalse(modelsDiverge("claude-fable-5", "Fable"))
        assertFalse(modelsDiverge("claude-opus-4-5", "Opus 4.5"))
    }

    @Test
    fun datedVariantDoesNotDiverge() {
        assertFalse(modelsDiverge("claude-sonnet-5", "claude-sonnet-5-20260203"))
    }

    @Test
    fun normalizationIgnoresCaseAndPunctuation() {
        assertFalse(modelsDiverge("Claude-Fable-5", "claude_fable_5"))
    }

    @Test
    fun missingSideNeverDiverges() {
        assertFalse(modelsDiverge("", "claude-opus-4-8")) // "" = CLI default pick
        assertFalse(modelsDiverge(null, "claude-opus-4-8"))
        assertFalse(modelsDiverge("claude-fable-5", null))
        assertFalse(modelsDiverge("claude-fable-5", ""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.reduce.ModelIdTest"`
Expected: FAIL — `e: unresolved reference: modelsDiverge`

- [ ] **Step 3: Implement**

```kotlin
package com.tether.app.protocol.reduce

/**
 * Port of aidash/lib/model-id.mjs — did the model that actually served the
 * last turn demonstrably differ from the operator-selected one?
 *
 * The two sides come from different vocabularies, so equality is judged on a
 * normalized form (lowercase, alphanumerics only) with containment either way
 * counting as a match. Deliberately conservative: with either side missing it
 * returns false — the badge must never cry wolf.
 */
fun modelsDiverge(selected: String?, served: String?): Boolean {
    val a = normalizeModelId(selected)
    val b = normalizeModelId(served)
    if (a.isEmpty() || b.isEmpty()) return false
    return a != b && !a.contains(b) && !b.contains(a)
}

private val NON_ALNUM = Regex("[^a-z0-9]")

private fun normalizeModelId(value: String?): String =
    value?.lowercase()?.replace(NON_ALNUM, "") ?: ""
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.reduce.ModelIdTest"`
Expected: `BUILD SUCCESSFUL`

---

### Task 3: Sub-agent run derivation (`SubagentRunModel.kt`) + `estimatedUsd`

**Files:**
- Test: `app/src/test/java/com/tether/app/protocol/reduce/SubagentRunModelTest.kt`
- Test: `app/src/test/java/com/tether/app/ui/util/FormatTest.kt`
- Create: `app/src/main/java/com/tether/app/protocol/reduce/SubagentRunModel.kt`
- Modify: `app/src/main/java/com/tether/app/ui/util/Format.kt` (append one function)

This is a line-faithful port of `aidash/components/subagent-run-model.mjs`.
Key invariants: a run's identity is `"$turnId::$blockId"`; `totalTokens` and
`estimatedCostUSD` are **null, never 0, when unmeasured** (resumed sessions
replay no child records — 0 would assert "cost nothing"); the apportioned cost
is clamped to never exceed the served model's whole cost.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/tether/app/protocol/reduce/SubagentRunModelTest.kt`:

```kotlin
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
}
```

`app/src/test/java/com/tether/app/ui/util/FormatTest.kt`:

```kotlin
package com.tether.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun estimatedUsdMatchesTheWebCompactStyle() {
        assertEquals("$1.23", estimatedUsd(1.234))
        assertEquals("$0.30", estimatedUsd(0.3))
        assertEquals("$0.0045", estimatedUsd(0.0045)) // sub-cent: 4 digits
        assertEquals("—", estimatedUsd(null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.reduce.SubagentRunModelTest" --tests "com.tether.app.ui.util.FormatTest"`
Expected: FAIL — unresolved references (`isSubagentLauncher`, `collectSubagentRuns`, `estimatedUsd`, …)

- [ ] **Step 3: Implement `SubagentRunModel.kt`**

```kotlin
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
```

- [ ] **Step 4: Append `estimatedUsd` to `Format.kt`**

Append at the end of `app/src/main/java/com/tether/app/ui/util/Format.kt`:

```kotlin
/** aidash telemetry-readings.estimatedUSD(value, "compact"): "$1.23" / "$0.0045". */
fun estimatedUsd(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val digits = if (kotlin.math.abs(value) >= 0.01) 2 else 4
    val format = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US)
    format.minimumFractionDigits = digits
    format.maximumFractionDigits = digits
    return format.format(value)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.reduce.SubagentRunModelTest" --tests "com.tether.app.ui.util.FormatTest"`
Expected: `BUILD SUCCESSFUL`

> **Review hardening (added during execution):** four extra tests now also live in
> `SubagentRunModelTest.kt` — negative token counts are filtered from totals;
> whitespace-only description falls through to agentType; prompt/requestedModel/
> requestedEffort extract off the tool input; runIds pair turn+tool so a
> cross-turn tool-id collision cannot collide (and index continues across turns).
> Keep them when editing this file.

---

### Task 4: Session-controls derivations (`SessionControlsModel.kt`)

**Files:**
- Test: `app/src/test/java/com/tether/app/protocol/reduce/SessionControlsModelTest.kt`
- Create: `app/src/main/java/com/tether/app/protocol/reduce/SessionControlsModel.kt`

Ports of `pickerModels` / `activeModel` / `composerCommandList` /
`resolveModelArg` from `aidash/components/chat-view.tsx` (lines 69–105,
932–971, 1093–1102). Reuses the existing wire types `SessionModelOption` and
`SessionCommandOption` (they are field-identical to the web's `ModelOption` /
`SlashCommandInfo`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tether.app.protocol.reduce

import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.CliCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlsModelTest {

    private fun model(value: String, name: String, current: Boolean? = null, resolved: String? = null) =
        SessionModelOption(value = value, displayName = name, current = current, resolvedModel = resolved)

    // --- pickerModels ---------------------------------------------------

    @Test
    fun pickerIsEmptyWhenNoModelsLoaded() {
        assertEquals(emptyList<SessionModelOption>(), pickerModels(emptyList(), null))
    }

    @Test
    fun pickerSynthesizesExactlyOneDefaultRow() {
        val live = listOf(model("default", "Default"), model("claude-opus-4-8", "Opus 4.1"))
        // The live list already carries a default row — none is synthesized,
        // and it is relabeled with what it resolves to (resolvedModel).
        val withResolution = pickerModels(
            listOf(
                model("default", "Default", resolved = "claude-opus-4-8"),
                model("claude-opus-4-8", "Opus 4.1"),
            ),
            null,
        )
        assertEquals(2, withResolution.size)
        assertEquals("CLI Default (Opus 4.1)", withResolution[0].displayName)
        assertTrue(withResolution[0].current == true) // no explicit pick -> default row is current
        assertTrue(live.any { it.value == "default" })

        // No default row in the source list -> one is prepended, unlabeled
        // resolution -> plain "CLI Default".
        val synthesized = pickerModels(listOf(model("claude-opus-4-8", "Opus 4.1")), null)
        assertEquals("", synthesized[0].value)
        assertEquals("CLI Default", synthesized[0].displayName)
        assertTrue(synthesized[0].current == true)
        assertEquals(2, synthesized.size)
    }

    @Test
    fun pickerKeepsServerCurrentMarkingWhenPresent() {
        val picked = pickerModels(
            listOf(model("default", "Default"), model("claude-sonnet-5", "Sonnet 5", current = true)),
            null,
        )
        assertTrue(picked[0].current != true) // not re-marked: a row already carries current
        assertTrue(picked[1].current == true)
    }

    @Test
    fun pickerResolutionPrefersExactIdMatchOverContainment() {
        // "claude-opus-4-8" must not mislabel as the earlier "claude-opus-4" row.
        val rows = pickerModels(
            listOf(
                model("default", "Default", resolved = "claude-opus-4-8"),
                model("claude-opus-4", "Opus 4"),
                model("claude-opus-4-8", "Opus 4.8"),
            ),
            null,
        )
        assertEquals("CLI Default (Opus 4.8)", rows[0].displayName)
    }

    // --- activeModel ----------------------------------------------------

    @Test
    fun activeModelFallsThroughInOrder() {
        val models = listOf(model("default", "Default"), model("claude-opus-4-8", "Opus 4.1", current = true))
        val picker = pickerModels(models, null)
        assertEquals("Opus 4.1", activeModel(models, picker, "claude-opus-4-8")?.displayName)
        assertEquals("Opus 4.1", activeModel(models, picker, null)?.displayName) // server current
        val noCurrent = listOf(model("default", "Default"))
        assertEquals("CLI Default", activeModel(noCurrent, pickerModels(noCurrent, null), null)?.displayName)
    }

    // --- composerCommandList ---------------------------------------------

    @Test
    fun commandListMergesAdvertisedWithControlsAndGuaranteesModel() {
        val advertised = listOf(
            CliCommand(name = "compact", description = null, argumentHint = null, aliases = null),
            CliCommand(name = "vim", description = "Vim mode", argumentHint = null, aliases = null),
        )
        val controls = listOf(
            SessionCommandOption(name = "compact", description = "Compact the conversation", argumentHint = null, aliases = null, supported = false),
        )
        val commands = composerCommandList(advertised, controls)
        // Advertised wins on membership; description enriched from controls;
        // the closed native allow-set marks only `model` supported; /model is
        // guaranteed and sorts with the supported group first.
        assertEquals(listOf("model", "compact", "vim"), commands.map { it.name })
        assertEquals("Compact the conversation", commands[1].description)
        assertTrue(commands[0].supported)
        assertTrue(!commands[1].supported)
        assertEquals("Switch the model for this session", commands[0].description)
        assertEquals("[model]", commands[0].argumentHint)
    }

    @Test
    fun commandListFallsBackToControlsWhenNothingAdvertised() {
        val controls = listOf(
            SessionCommandOption(name = "model", description = "d", argumentHint = null, aliases = null, supported = true),
            SessionCommandOption(name = "agents", description = "a", argumentHint = null, aliases = null, supported = false),
        )
        val commands = composerCommandList(null, controls)
        assertEquals(listOf("model", "agents"), commands.map { it.name })
        assertTrue(commands.none { it.name == "model" && it.description == "Switch the model for this session" })
    }

    // --- resolveModelArg --------------------------------------------------

    @Test
    fun freeTextModelArgResolvesByExactThenSubstring() {
        val models = listOf(
            model("default", "Default"),
            model("claude-opus-4-8", "Opus 4.8"),
            model("claude-fable-5", "Fable 5"),
        )
        assertEquals("claude-fable-5", resolveModelArg("fable", models)?.value) // displayName substring
        assertEquals("claude-opus-4-8", resolveModelArg("Claude-Opus-4-8", models)?.value) // exact value, any case
        assertEquals("claude-opus-4-8", resolveModelArg("opus", models)?.value)
        assertNull(resolveModelArg("gpt-5", models))
        assertNull(resolveModelArg("   ", models))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.reduce.SessionControlsModelTest"`
Expected: FAIL — unresolved references (`pickerModels`, `activeModel`, `composerCommandList`, `resolveModelArg`)

- [ ] **Step 3: Implement**

```kotlin
package com.tether.app.protocol.reduce

import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.CliCommand

/**
 * Ports of the composer-controls derivations in aidash/components/chat-view.tsx
 * (pickerModels / activeModel / composerCommandList / resolveModelArg). Pure:
 * no I/O, no Compose. The wire types SessionModelOption / SessionCommandOption
 * are field-identical to the web's ModelOption / SlashCommandInfo.
 */

/** Tether-native slash commands — the closed allow-set (advertisement ≠ executable). */
private val TETHER_NATIVE_COMMANDS = setOf("model")

/** The picker always offers exactly ONE "Default" (clear) row. */
fun isDefaultModelRow(model: SessionModelOption): Boolean =
    model.value.isEmpty() || model.value == "default"

/**
 * The model-picker rows: the live list plus a synthesized Default row when the
 * source has none; default rows are labeled with the concrete model they
 * resolve to (exact id match first, then the normalized-containment fallback);
 * with no explicit selection the default row is marked `current`.
 */
fun pickerModels(models: List<SessionModelOption>, sessionModel: String?): List<SessionModelOption> {
    if (models.isEmpty()) return emptyList()
    val list = if (models.any(::isDefaultModelRow)) {
        models
    } else {
        listOf(SessionModelOption(value = "", displayName = "Default", description = "The CLI's default model")) + models
    }
    val labeled = list.map { model ->
        if (!isDefaultModelRow(model)) return@map model
        val resolved = model.resolvedModel
        val match = resolved?.let { r ->
            list.firstOrNull { !isDefaultModelRow(it) && it.value == r }
                ?: list.firstOrNull { !isDefaultModelRow(it) && it.value.isNotEmpty() && !modelsDiverge(it.value, r) }
        }
        val name = match?.displayName ?: resolved
        model.copy(displayName = if (!name.isNullOrEmpty()) "CLI Default ($name)" else "CLI Default")
    }
    if (sessionModel.isNullOrEmpty() && labeled.none { it.current == true }) {
        return labeled.map { if (isDefaultModelRow(it)) it.copy(current = true) else it }
    }
    return labeled
}

/** The model actually in effect: explicit pick, else server `current`, else the default row. */
fun activeModel(
    models: List<SessionModelOption>,
    picker: List<SessionModelOption>,
    sessionModel: String?,
): SessionModelOption? =
    models.firstOrNull { it.value == sessionModel }
        ?: models.firstOrNull { it.current == true }
        ?: picker.firstOrNull { it.current == true }

/**
 * The composer's slash-command list. CLI advertisement and Tether execution
 * support are deliberately independent: the advertised list wins on membership
 * (replace semantics), name-only entries are enriched from controls, and the
 * closed native allow-set decides `supported`. /model is guaranteed present.
 * Sorted supported-first, then by name.
 */
fun composerCommandList(
    advertised: List<CliCommand>?,
    controls: List<SessionCommandOption>,
): List<SessionCommandOption> {
    val source: List<CliCommand> = advertised ?: controls.map {
        CliCommand(name = it.name, description = it.description, argumentHint = it.argumentHint, aliases = it.aliases)
    }
    val controlByName = controls.associateBy { it.name }
    val commands = source.map { command ->
        val detail = controlByName[command.name]
        val aliases = command.aliases ?: detail?.aliases
        SessionCommandOption(
            name = command.name,
            description = command.description ?: detail?.description ?: "",
            argumentHint = command.argumentHint ?: detail?.argumentHint,
            aliases = aliases,
            supported = TETHER_NATIVE_COMMANDS.contains(command.name) ||
                aliases?.any { TETHER_NATIVE_COMMANDS.contains(it) } == true,
        )
    }.toMutableList()
    if (commands.none { it.name == "model" }) {
        commands.add(
            0,
            SessionCommandOption(
                name = "model",
                description = "Switch the model for this session",
                argumentHint = "[model]",
                aliases = null,
                supported = true,
            ),
        )
    }
    return commands.sortedWith(compareBy({ !it.supported }, { it.name }))
}

/**
 * Resolve a free-text /model argument ("fable", "Sonnet", a full id) against
 * the known models: exact value, then exact display name, then substring of
 * either — all case-insensitive.
 */
fun resolveModelArg(arg: String, models: List<SessionModelOption>): SessionModelOption? {
    val q = arg.trim().lowercase()
    if (q.isEmpty()) return null
    return models.firstOrNull { it.value.lowercase() == q }
        ?: models.firstOrNull { it.displayName.lowercase() == q }
        ?: models.firstOrNull { it.value.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.reduce.SessionControlsModelTest"`
Expected: `BUILD SUCCESSFUL`

> **Review hardening (added during execution):** `pickerModels` now gates the
> resolution with `model.resolvedModel?.takeIf { it.isNotEmpty() }` (JS falsy
> parity — an empty string is "no resolution"); the dead `live`-fixture
> assertion was removed; three extra pins live in the test file —
> `twoDefaultRowsInSourceAreNotDeduplicated`, `aliasOfANativeCommandMarksSupported`,
> `exactDisplayNameBeatsSubstringAcrossSteps`.

---

### Task 5: Wire frames `set-model` + `session-controls` request

**Files:**
- Modify: `app/src/main/java/com/tether/app/protocol/ClientMessage.kt` (add two data classes)
- Test: `app/src/test/java/com/tether/app/protocol/WireTest.kt` (extend one test)

Both frames are already specified in `specs/protocol-spec.md` §3 — no spec change.

- [ ] **Step 1: Write the failing test**

Add to the end of `clientMessagesSerializeExactFields` in `WireTest.kt`
(after the `Kill` assertion):

```kotlin
        assertEquals(
            setOf("type", "sessionId", "model"),
            ClientMessage.SetModel("s1", "claude-opus-4-8").toJsonObject().keys,
        )
        assertEquals(
            "set-model",
            ClientMessage.SetModel("s1", "claude-opus-4-8").toJsonObject()["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            setOf("type", "sessionId"),
            ClientMessage.SessionControlsRequest("s1").toJsonObject().keys,
        )
        assertEquals(
            "session-controls",
            ClientMessage.SessionControlsRequest("s1").toJsonObject()["type"]!!.jsonPrimitive.content,
        )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.WireTest"`
Expected: FAIL — unresolved references `SetModel`, `SessionControlsRequest`

- [ ] **Step 3: Implement**

In `ClientMessage.kt`, add after the `SetMode` data class:

```kotlin
    /** Claude only: switch the session's model ("" or "default" clears to the CLI default). */
    data class SetModel(val sessionId: String, val model: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "set-model")
            put("sessionId", sessionId)
            put("model", model)
        }
    }

    /** Ask for the session's available models + slash-command list (reply: session-controls). */
    data class SessionControlsRequest(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "session-controls")
            put("sessionId", sessionId)
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.protocol.WireTest"`
Expected: `BUILD SUCCESSFUL`

---

### Task 6: Client surface — `setModel`, `requestSessionControls`, `sessionControls` flow

**Files:**
- Modify: `app/src/main/java/com/tether/app/client/TetherClient.kt`
- Modify: `app/src/main/java/com/tether/app/client/RealTetherClient.kt`
- Modify: `app/src/main/java/com/tether/app/ui/fake/FakeTetherClient.kt`
- Test: `app/src/test/java/com/tether/app/client/RealTetherClientTest.kt`

The parsed `session-controls` reply is currently DROPPED in
`RealTetherClient.handleFrame` — route it into a per-session map instead.

- [ ] **Step 1: Write the failing test**

Add to `RealTetherClientTest.kt`:

```kotlin
    @Test
    fun sessionControlsReplyIsRoutedAndCommandsSend() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"protocolVersion":40}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "tether_session=c4; Path=/")
                .setBody("""{"ok":true}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
        assertEquals(LoginResult.Success, runBlocking { client.login(server.url("/").toString(), "pw") })
        val serverSocket = serverSockets.poll(10, TimeUnit.SECONDS)!!
        serverSocket.send("""{"type":"ready","protocolVersion":40,"sessions":[],"providers":[],"workspaceRoot":null}""")
        await(client.connection) { it == ConnectionState.Connected }

        // The request frame is exactly what protocol-validate expects.
        client.requestSessionControls("s1")
        val request = nextFrame()
        assertEquals("session-controls", request["type"]!!.jsonPrimitive.content)
        assertEquals("s1", request["sessionId"]!!.jsonPrimitive.content)

        // The reply lands in the per-session flow (it is NOT dropped).
        serverSocket.send(
            """
            {"type":"session-controls","sessionId":"s1",
             "models":[{"value":"default","displayName":"Default","current":true,"resolvedModel":"claude-opus-4-8"},
                       {"value":"claude-opus-4-8","displayName":"Opus 4.1"}],
             "commands":[{"name":"model","description":"Switch the model for this session","supported":true}],
             "model":"claude-opus-4-8"}
            """.trimIndent(),
        )
        val controls = await(client.sessionControls) { it.containsKey("s1") }.getValue("s1")
        assertEquals(2, controls.models.size)
        assertEquals("claude-opus-4-8", controls.models[0].resolvedModel)
        assertEquals("claude-opus-4-8", controls.model)

        // set-model sends the verbatim id ("" / "default" = reset, server's mapping).
        client.setModel("s1", "claude-opus-4-8")
        val setModel = nextFrame()
        assertEquals("set-model", setModel["type"]!!.jsonPrimitive.content)
        assertEquals("claude-opus-4-8", setModel["model"]!!.jsonPrimitive.content)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.client.RealTetherClientTest"`
Expected: FAIL — unresolved references `requestSessionControls`, `sessionControls`, `setModel`

- [ ] **Step 3: Extend the `TetherClient` interface**

In `TetherClient.kt`, add the import:

```kotlin
import com.tether.app.protocol.ServerMessage
```

Add after `directories`:

```kotlin
    /** Latest session-controls reply per session (models + slash commands for the composer). */
    val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>>
```

Add after `setMode`:

```kotlin
    /** Claude only: switch the session's model ("" or "default" resets to the CLI default). */
    fun setModel(sessionId: String, model: String)

    /** Ask for the session's available models + slash-command list. Cheap + idempotent. */
    fun requestSessionControls(sessionId: String)
```

- [ ] **Step 4: Implement in `RealTetherClient`**

Add the flow (with the other flow declarations, after `directoriesState`):

```kotlin
    private val sessionControlsState = MutableStateFlow<Map<String, ServerMessage.SessionControls>>(emptyMap())
```

and the override (after `directories`):

```kotlin
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = sessionControlsState
```

In `handleFrame`, replace the ignore-branch:

```kotlin
            is ServerMessage.SearchResults, is ServerMessage.SessionControls,
            is ServerMessage.Log, is ServerMessage.Unknown,
            -> Unit
```

with:

```kotlin
            is ServerMessage.SessionControls ->
                sessionControlsState.value = sessionControlsState.value + (message.sessionId to message)
            is ServerMessage.SearchResults, is ServerMessage.Log, is ServerMessage.Unknown,
            -> Unit
```

Add the two commands (after `setMode`):

```kotlin
    override fun setModel(sessionId: String, model: String) {
        sendFrame(ClientMessage.SetModel(sessionId, model))
    }

    override fun requestSessionControls(sessionId: String) {
        sendFrame(ClientMessage.SessionControlsRequest(sessionId))
    }
```

- [ ] **Step 5: Implement in `FakeTetherClient`**

Add imports:

```kotlin
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
```

Add the flow + stubs (the seed lets previews show a working picker for `s-active`):

```kotlin
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = MutableStateFlow(
        mapOf(
            "s-active" to ServerMessage.SessionControls(
                sessionId = "s-active",
                models = listOf(
                    SessionModelOption(value = "default", displayName = "Default", current = true, resolvedModel = "claude-opus"),
                    SessionModelOption(value = "claude-opus", displayName = "Opus", description = "Most capable"),
                    SessionModelOption(value = "claude-sonnet", displayName = "Sonnet", description = "Balanced"),
                ),
                commands = listOf(
                    SessionCommandOption(name = "model", description = "Switch the model for this session", argumentHint = "[model]", supported = true),
                    SessionCommandOption(name = "compact", description = "Compact the conversation", supported = false),
                ),
                model = "claude-opus",
            ),
        ),
    )

    override fun setModel(sessionId: String, model: String) {}
    override fun requestSessionControls(sessionId: String) {}
```

(The existing `override fun setMode(...) {}` stub stays.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.client.RealTetherClientTest"`
Expected: `BUILD SUCCESSFUL`

---

### Task 7: Permission-mode option table

**Files:**
- Create: `app/src/main/java/com/tether/app/ui/chat/PermissionModes.kt`
- Test: `app/src/test/java/com/tether/app/ui/chat/PermissionModesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.tether.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionModesTest {

    @Test
    fun valuesMatchTheServerAllowSetExactly() {
        // The wire is strict (protocol-validate.mjs + engines/claude.mjs allow-set);
        // a typo here is a silently rejected frame. Keep in sync with
        // PERMISSION_MODE_OPTIONS in aidash/lib/protocol.ts.
        assertEquals(
            listOf("default", "acceptEdits", "plan", "dontAsk", "bypassPermissions"),
            PERMISSION_MODE_OPTIONS.map { it.value },
        )
        assertEquals(
            listOf("Manual", "Accept Edits", "Plan", "Locked", "Auto"),
            PERMISSION_MODE_OPTIONS.map { it.label },
        )
        assertEquals(listOf(false, false, false, false, true), PERMISSION_MODE_OPTIONS.map { it.danger })
    }

    @Test
    fun lookupDefaultsToManual() {
        assertEquals("Manual", permissionModeOption(null).label)
        assertEquals("Manual", permissionModeOption("default").label)
        assertEquals("Auto", permissionModeOption("bypassPermissions").label)
        assertEquals("Manual", permissionModeOption("bogus-from-the-future").label)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.ui.chat.PermissionModesTest"`
Expected: FAIL — unresolved reference `PERMISSION_MODE_OPTIONS`

- [ ] **Step 3: Implement**

```kotlin
package com.tether.app.ui.chat

/**
 * UI-facing metadata for the permission-mode selector. Hand-synced with
 * PERMISSION_MODE_OPTIONS in aidash/lib/protocol.ts — `default` (Manual) is
 * the safe default: every gated tool prompts via the approval chips.
 */
data class PermissionModeOption(
    val value: String,
    val label: String,
    val hint: String,
    val danger: Boolean = false,
)

val PERMISSION_MODE_OPTIONS: List<PermissionModeOption> = listOf(
    PermissionModeOption("default", "Manual", "Prompts before every gated tool (Bash, Write, Edit…)"),
    PermissionModeOption("acceptEdits", "Accept Edits", "Auto-accepts file edits; still prompts for other tools"),
    PermissionModeOption("plan", "Plan", "Researches and proposes a plan without making changes"),
    PermissionModeOption("dontAsk", "Locked", "Denies tools that are not pre-approved; agent questions are denied instead of shown"),
    PermissionModeOption("bypassPermissions", "Auto", "Runs everything without asking — including destructive commands", danger = true),
)

/** The current option for a session row; null/bogus wires down to Manual. */
fun permissionModeOption(value: String?): PermissionModeOption =
    PERMISSION_MODE_OPTIONS.firstOrNull { it.value == (value ?: "default") } ?: PERMISSION_MODE_OPTIONS.first()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tether.app.ui.chat.PermissionModesTest"`
Expected: `BUILD SUCCESSFUL`

---

### Task 8: Mode row, drop-up pickers, slash menu, composer notices

**Files:**
- Create: `app/src/main/java/com/tether/app/ui/chat/ChatControls.kt`
- Modify: `app/src/main/java/com/tether/app/ui/chat/Composer.kt`
- Modify: `app/src/main/java/com/tether/app/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/tether/app/ui/Previews.kt` (pass `controls = null`
  + the three no-op callbacks at the two Composer preview call sites — the new
  params have no defaults, so the previews need them to compile)

> **Execution adaptations (already applied, kept here so the spec matches the code):**
> 1. The Composer's pre-existing attachment launcher `val picker` was renamed to
>    `attachmentPicker` (declaration + its single `picker.launch` call site) — the
>    plan's state block below declares its own `val picker` (the picker-models
>    list) and a same-scope redeclaration does not compile.
> 2. The state block from step (b) sits after the `hasApproval`/`hasQuestion`
>    declarations (not immediately after `var picked`): `menuOpen` and `submit()`
>    read `busy`, which is declared between those two points, and Kotlin requires
>    declaration-before-capture. All block code is verbatim, still before the Column.

Compose UI has no unit tests in this repo — verification is `assembleDebug`
plus the manual checklist in Task 12. The BEHAVIOR ports live in the already
tested `SessionControlsModel.kt` / `PermissionModes.kt`; this task is the
render layer. All user-facing strings are verbatim from `chat-view.tsx`.

- [ ] **Step 1: Create `ChatControls.kt`**

Four composables. `DropdownMenu` anchored at the bottom of the screen opens
upward automatically — the web's `dropUp`.

```kotlin
package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.X
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * The composer mode row (visual-spec §4, Claude only): ShieldCheck + "Mode" +
 * the permission-mode pill (drop-up) + the model chip. Mirrors the web
 * chat-mode-row; the inline hint lives in the menu rows on the phone.
 */
@Composable
fun ChatModeRow(
    permissionMode: String?,
    modelLabel: String,
    onSetMode: (String) -> Unit,
    onModelClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val current = permissionModeOption(permissionMode)
    var modeMenuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.ShieldCheck, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text("Mode", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 12.5.sp)
        Box {
            Row(
                Modifier
                    .height(29.dp)
                    .background(t.keyFace, RoundedCornerShape(999.dp))
                    .border(1.dp, t.keySide, RoundedCornerShape(999.dp))
                    .clickable { modeMenuOpen = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current.label,
                    color = if (current.danger) t.danger else t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                )
            }
            DropdownMenu(
                expanded = modeMenuOpen,
                onDismissRequest = { modeMenuOpen = false },
                modifier = Modifier.background(t.graphite).border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd)),
            ) {
                PERMISSION_MODE_OPTIONS.forEach { option ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                modeMenuOpen = false
                                onSetMode(option.value)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .heightIn(min = TetherDimens.touchTargetDp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                option.label,
                                color = if (option.danger) t.danger else t.white,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.label,
                                fontSize = 13.6.sp,
                            )
                            if (option.value == current.value) {
                                Icon(Lucide.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(13.dp))
                            }
                        }
                        Text(
                            option.hint,
                            color = if (option.danger) t.danger else t.muted,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.body,
                            fontSize = 12.2.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .height(29.dp)
                .background(t.keyFace, RoundedCornerShape(999.dp))
                .border(1.dp, t.keySide, RoundedCornerShape(999.dp))
                .clickable(onClick = onModelClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Lucide.Cpu, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Text(
                modelLabel,
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The model picker drop-up panel (web chat-model-picker): header, empty state,
 * one row per picker model with a Check on the active one.
 */
@Composable
fun ModelPickerPanel(
    pickerModels: List<SessionModelOption>,
    sessionModel: String?,
    onChoose: (SessionModelOption) -> Unit,
    onClose: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.graphite, RoundedCornerShape(TetherDimens.radiusMd))
            .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd))
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Cpu, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(6.dp))
            Text("Model", color = t.white, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 13.1.sp)
            Spacer(Modifier.weight(1f))
            Icon(
                Lucide.X,
                contentDescription = "Close",
                tint = t.muted,
                modifier = Modifier
                    .size(TetherDimens.touchTargetDp)
                    .clickable(onClick = onClose)
                    .padding(14.dp),
            )
        }
        if (pickerModels.isEmpty()) {
            Text(
                "Send a message first to load the available models.",
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 12.8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            pickerModels.forEach { model ->
                val isActive = model.value == (sessionModel ?: "") || (sessionModel.isNullOrEmpty() && model.current == true)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(model) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(min = TetherDimens.touchTargetDp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            model.displayName,
                            color = if (isActive) t.white else t.ink,
                            fontFamily = Manrope,
                            fontWeight = if (isActive) TetherWeights.label else TetherWeights.body,
                            fontSize = 13.6.sp,
                        )
                        if (isActive) Icon(Lucide.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(13.dp))
                    }
                    model.description?.let {
                        Text(it, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.2.sp)
                    }
                }
            }
        }
    }
}

/** The slash-command autocomplete drop-up (web chat-slash-menu). */
@Composable
fun SlashCommandMenu(
    matches: List<SessionCommandOption>,
    onAccept: (SessionCommandOption) -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .background(t.graphite, RoundedCornerShape(TetherDimens.radiusMd))
            .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        matches.forEach { command ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onAccept(command) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(min = TetherDimens.touchTargetDp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "/${command.name}",
                            color = t.white,
                            fontFamily = JetBrainsMono,
                            fontWeight = TetherWeights.label,
                            fontSize = 13.1.sp,
                        )
                        command.argumentHint?.let {
                            Text(" $it", color = t.faint, fontFamily = JetBrainsMono, fontSize = 12.2.sp)
                        }
                    }
                    if (!command.description.isNullOrEmpty()) {
                        Text(
                            command.description,
                            color = t.muted,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.body,
                            fontSize = 12.2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (command.supported) {
                    Text("Tether", color = t.violet, fontFamily = Manrope, fontWeight = TetherWeights.strong, fontSize = 10.6.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Lucide.Terminal, contentDescription = null, tint = t.faint, modifier = Modifier.size(11.dp))
                        Text("terminal only", color = t.faint, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 10.6.sp)
                    }
                }
            }
        }
    }
}

/** The in-composer flash notice (web chat-notice): Terminal icon + text. */
@Composable
fun ComposerNotice(message: String) {
    val t = LocalTetherTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text(message, color = t.ink, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.8.sp)
    }
}
```

- [ ] **Step 2: Wire the Composer**

In `Composer.kt`:

a) Extend the signature with the controls inputs (new params between
`projection` and `serverNow`, plus three callbacks after `onQueueRemove`):

```kotlin
fun Composer(
    session: AgentSession?,
    projection: SessionProjection?,
    controls: ServerMessage.SessionControls?,
    serverNow: () -> Long,
    onSend: (String, List<Attachment>) -> Boolean,
    onInterrupt: () -> Unit,
    onQueueEdit: (queueId: String, text: String) -> Unit,
    onQueueRemove: (queueId: String) -> Unit,
    onSetMode: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onRequestControls: () -> Unit,
    modifier: Modifier = Modifier,
    onAttachError: (String) -> Unit = {},
)
```

Add imports: `com.tether.app.protocol.ServerMessage`,
`com.tether.app.protocol.SessionCommandOption`,
`com.tether.app.protocol.SessionModelOption`,
`com.tether.app.protocol.reduce.activeModel`,
`com.tether.app.protocol.reduce.composerCommandList`,
`com.tether.app.protocol.reduce.pickerModels`,
`com.tether.app.protocol.reduce.resolveModelArg`.
(`kotlinx.coroutines.delay` and `LaunchedEffect` are already imported.)

b) Add the controls state + behavior ports right after the
`var picked by remember(session?.id) …` line (local `fun` declarations inside
the composable body capture state fine — declare them before the `Column`):

```kotlin
    val claude = session?.provider == "claude"
    var showModelPicker by remember(session?.id) { mutableStateOf(false) }
    var menuDismissed by remember(session?.id) { mutableStateOf(false) }
    var notice by remember(session?.id) { mutableStateOf<String?>(null) }

    // The web flash(): 6 s auto-dismiss; a new notice re-times itself.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            notice = null
        }
    }
    fun flash(message: String) {
        notice = message
    }

    val models = controls?.models ?: emptyList()
    val picker = remember(models, session?.model) { pickerModels(models, session?.model) }
    val active = remember(models, picker, session?.model) { activeModel(models, picker, session?.model) }
    val modelLabel = active?.displayName ?: (session?.model ?: "Default")
    val commands = remember(projection?.cliInventory, controls) {
        composerCommandList(projection?.cliInventory?.commands, controls?.commands ?: emptyList())
    }

    // The command-name fragment being typed ("/mod" -> "mod"), or null when the
    // draft isn't a bare slash command — drives whether the menu shows.
    val slashQuery = if (claude && draft.startsWith("/") && !draft.drop(1).contains(" ")) draft.drop(1) else null
    val menuMatches = remember(slashQuery, commands) {
        if (slashQuery == null) {
            emptyList()
        } else {
            val q = slashQuery.lowercase()
            commands.filter { command ->
                command.name.lowercase().startsWith(q) ||
                    command.aliases.orEmpty().any { it.lowercase().startsWith(q) }
            }
        }
    }
    val menuOpen = slashQuery != null && !menuDismissed && menuMatches.isNotEmpty() && !busy

    fun openModelPicker() {
        if (!claude) return
        onRequestControls() // refresh to the live list if the session has since warmed
        showModelPicker = true
        menuDismissed = true
    }

    fun chooseModel(model: SessionModelOption) {
        onSetModel(model.value)
        val isDefaultChoice = model.value.isEmpty() || model.value == "default"
        flash(if (isDefaultChoice) "Model reset to the CLI default." else "Model set to ${model.displayName}.")
        showModelPicker = false
        if (draft.startsWith("/model")) draft = ""
    }

    // Native commands (currently /model) run in-app; everything else is flagged
    // terminal-only rather than sent as prompt text (the /model-as-text bug).
    fun runSlashCommand(raw: String) {
        val body = raw.drop(1)
        val name = body.split(Regex("\\s+")).first()
        val arg = body.removePrefix(name).trim()
        val info = commands.find { it.name == name || it.aliases.orEmpty().contains(name) }

        if (name == "model" || info?.name == "model") {
            if (arg.isEmpty()) {
                openModelPicker()
                draft = ""
                return
            }
            val match = resolveModelArg(arg, models)
            if (match != null) {
                chooseModel(match)
                return
            }
            flash("No model matches “$arg”. Choose one from the list.")
            openModelPicker()
            draft = ""
            return
        }
        if (info != null && !info.supported) {
            flash("/${info.name} isn’t available in Tether yet — run it from a terminal (claude --resume …).")
            draft = ""
            return
        }
        flash("Unknown command “/$name”. Type “/” to see what’s available.")
    }

    fun acceptCommand(command: SessionCommandOption) {
        menuDismissed = true
        if (command.name == "model" || command.aliases.orEmpty().contains("model")) {
            openModelPicker()
            draft = ""
            return
        }
        if (!command.supported) {
            flash("/${command.name} isn’t available in Tether yet — run it from a terminal.")
            draft = ""
            return
        }
        draft = "/${command.name} "
    }

    /** Slash commands are in-app control requests: never queued, no attachments. */
    fun trySlashCommand(text: String, hasAttachments: Boolean): Boolean {
        if (!claude || !text.startsWith("/") || hasAttachments) return false
        runSlashCommand(text)
        return true
    }

    fun submit() {
        val text = draft.trim()
        val hasAttachments = picked.isNotEmpty()
        if (text.isEmpty() && !hasAttachments) return
        if (trySlashCommand(text, hasAttachments)) return
        if (busy) {
            // Queue path is text-only; attachments are only attachable while
            // idle, so none are pending here.
            if (text.isNotEmpty() && onSend(text, emptyList())) draft = ""
        } else {
            // Refused sends (§5.6 rollback) keep draft + chips.
            if (onSend(text, picked.map { it.attachment })) {
                draft = ""
                picked = emptyList()
            }
        }
    }
```

c) Render the new rows inside the composer's `Column`, AFTER the
`TurnActivity` block and BEFORE the queued-messages block (web DOM order:
mode row → notice → shell with slash menu / model picker):

```kotlin
        if (claude) {
            ChatModeRow(
                permissionMode = session?.permissionMode,
                modelLabel = modelLabel,
                onSetMode = onSetMode,
                onModelClick = { openModelPicker() },
            )
        }
        notice?.let { ComposerNotice(it) }
        if (menuOpen) {
            SlashCommandMenu(matches = menuMatches, onAccept = { acceptCommand(it) })
        }
        if (showModelPicker) {
            ModelPickerPanel(
                pickerModels = picker,
                sessionModel = session?.model,
                onChoose = { chooseModel(it) },
                onClose = { showModelPicker = false },
            )
        }
```

d) Route both send paths through `submit()` so slash commands intercept in
idle AND busy states. In the busy branch, replace the `Queue` key's
`onClick = { if (draft.isNotBlank()) { … } }` body with `onClick = { submit() }`;
in the idle branch, replace the `Send` key's `onClick = { if (draft.isNotBlank() || picked.isNotEmpty()) { … } }`
body with `onClick = { submit() }`. Keep the existing `enabled` logic unchanged.

- [ ] **Step 3: Wire ChatScreen**

In `ChatScreen.kt`:

a) Collect the controls map at the top of `ChatScreen` (after the
`showThinking` line):

```kotlin
    val controlsMap by vm.client.sessionControls.collectAsStateWithLifecycle()
```

b) Request controls when a Claude session's chat opens (web open-time effect;
cheap + idempotent server-side):

```kotlin
    LaunchedEffect(session?.id, session?.provider) {
        val s = session
        if (s != null && s.provider == "claude") vm.client.requestSessionControls(s.id)
    }
```

c) Pass the new Composer arguments:

```kotlin
        Composer(
            session = session,
            projection = projection,
            controls = session?.let { controlsMap[it.id] },
            serverNow = { vm.serverNow(session?.id) },
            onSend = { text, attachments -> session?.let { vm.sendOrQueue(it.id, text, attachments) } ?: false },
            onInterrupt = { session?.let { vm.client.interrupt(it.id) } },
            onQueueEdit = { queueId, text -> session?.let { vm.client.queueEdit(it.id, queueId, text) } },
            onQueueRemove = { queueId -> session?.let { vm.client.queueRemove(it.id, queueId) } },
            onSetMode = { mode -> session?.let { vm.client.setMode(it.id, mode) } },
            onSetModel = { model -> session?.let { vm.client.setModel(it.id, model) } },
            onRequestControls = { session?.let { vm.client.requestSessionControls(it.id) } },
            onAttachError = { message -> vm.reportLocalError(message) },
        )
```

- [ ] **Step 4: Build + full unit suite**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` for both

> **Review fixes (added during execution):**
> - `TetherInputWell.onValueChange` re-arms `menuDismissed` on every draft edit (web `onDraftChange` parity — without it the slash menu never reopened after one accepted command).
> - A `noticeSeq` monotonic counter keys the notice auto-dismiss, so identical re-flashes re-time instead of being no-ops.
> - `setModel` returns `Boolean` through the whole chain (`TetherClient`/`RealTetherClient`= `sendFrame` result/`Fake`/`ChatScreen` lambda/`Previews`/Composer param); `chooseModel` gates flash+close+draft-clear on it (no lying on send failure).
> - `submit()` flashes "Wait for the current turn to finish before sending attachments." and returns when `busy && hasAttachments` (the text-only queue path now only runs with no attachments, matching its comment).
> - `session?.permissionMode` → `session.permissionMode` (K2 proves `session != null` inside `if (claude)`; warning gone).

---

### Task 9: Run-tab selection state (ViewModel)

**Files:**
- Modify: `app/src/main/java/com/tether/app/ui/TetherViewModel.kt`

- [ ] **Step 1: Add the selection map**

In `TetherViewModel`, after the `currentWorkspace` block:

```kotlin
    /**
     * The selected sub-agent run tab per session (null/absent = the whole
     * session transcript). Owned here — like the web's dashboard — NOT in the
     * composable, so it survives recomposition; consumers resolve the id by
     * lookup against the current runs list, so a vanished run degrades to the
     * Session tab by itself (no reset effect).
     */
    private val _selectedRunIdBySession = MutableStateFlow<Map<String, String?>>(emptyMap())
    val selectedRunIdBySession: StateFlow<Map<String, String?>> = _selectedRunIdBySession.asStateFlow()

    fun selectRun(sessionId: String, runId: String?) {
        _selectedRunIdBySession.value = _selectedRunIdBySession.value + (sessionId to runId)
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

> **Review hardening (added during execution):** `onSessions` now also prunes
> `_selectedRunIdBySession` for sessions that no longer exist (parallel to the
> existing `_selectedSessionId` prune — stale entries are inert but this keeps
> the map from growing unbounded):
> ```kotlin
> val liveIds = list.mapTo(HashSet()) { it.id }
> val runSelections = _selectedRunIdBySession.value
> if (runSelections.isNotEmpty() && runSelections.any { it.key !in liveIds }) {
>     _selectedRunIdBySession.value = runSelections.filterKeys { it in liveIds }
> }
> ```

---

### Task 10: Sub-agent tabs UI (`SubagentRuns.kt`)

**Files:**
- Create: `app/src/main/java/com/tether/app/ui/chat/SubagentRuns.kt`
- Modify: `app/src/main/java/com/tether/app/ui/chat/ToolCard.kt` (optional
  `maxChars` parameter on `toolOutputText`, default 600 — the run-result
  section truncates at 4000 like the web's `summarize(run.output, 4000)`)

The inline collapsible thread inside parent tool cards already exists
(`SubagentThreadView`) and stays as-is. This adds the three web surfaces:
tab strip, transcript roster, per-run panel. Status is always icon + text,
never color alone; violet only marks the selected tab.

- [ ] **Step 1: Relax `toolOutputText`**

In `ToolCard.kt` change the signature and the truncation line:

```kotlin
/** Best-effort text extraction from a tool output payload; truncated to [maxChars]. */
internal fun toolOutputText(output: JsonElement?, maxChars: Int = 600): String? {
```

```kotlin
    return if (trimmed.length > maxChars) trimmed.take(maxChars) + "…" else trimmed
```

- [ ] **Step 2: Create `SubagentRuns.kt`**

```kotlin
package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wrench
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.reduce.RUN_ERROR
import com.tether.app.protocol.reduce.RUN_RUNNING
import com.tether.app.protocol.reduce.SubagentRun
import com.tether.app.protocol.reduce.SubagentRosterSummary
import com.tether.app.protocol.reduce.subagentRunEntries
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.compactNumber
import com.tether.app.ui.util.elapsedLabel
import com.tether.app.ui.util.estimatedUsd

private val STATUS_TEXT = mapOf(RUN_RUNNING to "running", RUN_ERROR to "error", "done" to "done")

@Composable
private fun RunStatusIcon(status: String, size: Dp) {
    val t = LocalTetherTokens.current
    when (status) {
        RUN_RUNNING -> SpinningIcon(Lucide.Loader, tint = t.muted, size = size)
        RUN_ERROR -> Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(size))
        else -> Icon(Lucide.Check, contentDescription = null, tint = t.faint, modifier = Modifier.size(size))
    }
}

/**
 * The tab strip: tab 0 is the whole session transcript; each following tab is
 * one sub-agent run. Selection by run.runId (null = transcript). Violet marks
 * the selected tab only; status is icon + text, never color alone.
 */
@Composable
fun SubagentTabs(
    runs: List<SubagentRun>,
    activeRunId: String?,
    onSelect: (String?) -> Unit,
) {
    val t = LocalTetherTokens.current
    val runningCount = runs.count { it.status == RUN_RUNNING }
    val listState = rememberLazyListState()

    // Keep the selected tab in view when it changes off-screen.
    LaunchedEffect(activeRunId) {
        val index = activeRunId?.let { id -> runs.indexOfFirst { it.runId == id } + 1 } ?: 0
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(t.graphite)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "session") {
            TabChip(selected = activeRunId == null, onClick = { onSelect(null) }) {
                Text(
                    "Session",
                    color = if (activeRunId == null) t.white else t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                )
                if (runningCount > 0 && activeRunId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SpinningIcon(Lucide.Loader, tint = t.muted, size = 11.dp)
                        Text("$runningCount", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 11.2.sp)
                    }
                }
            }
        }
        items(runs.size, key = { runs[it].runId }) { index ->
            val run = runs[index]
            val selected = run.runId == activeRunId
            TabChip(selected = selected, isError = run.status == RUN_ERROR, onClick = { onSelect(run.runId) }) {
                RunStatusIcon(run.status, 12.dp)
                Text(
                    run.title,
                    color = if (selected) t.white else t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (run.status == RUN_RUNNING) "running" else "${run.steps} step${if (run.steps == 1) "" else "s"}",
                    color = if (run.status == RUN_ERROR) t.danger else t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.2.sp,
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    selected: Boolean,
    onClick: () -> Unit,
    isError: Boolean = false,
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusSm)
    Row(
        Modifier
            .height(32.dp)
            .background(if (selected) t.violetWash else t.keyFace, shape)
            .border(
                1.dp,
                when {
                    selected -> t.violetStrong
                    isError -> t.dangerEdge
                    else -> t.keySide
                },
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** The identity/cost readings for one run, as a chip row. Absent = omitted. */
@Composable
fun SubagentRunStats(run: SubagentRun) {
    val served = run.usage?.model
    val showRequested = run.requestedModel != null && run.requestedModel != served
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        served?.let { SubrunChip(it) }
        if (showRequested) SubrunChip("asked ${run.requestedModel}")
        run.requestedEffort?.let { SubrunChip("$it effort") }
        val tokens = run.totalTokens
        if (tokens != null) {
            SubrunChip("${compactNumber(tokens)} tok")
        } else {
            SubrunChip("usage not captured", muted = true)
        }
        run.estimatedCostUSD?.let { SubrunChip("~${estimatedUsd(it)}") }
    }
}

@Composable
private fun SubrunChip(text: String, muted: Boolean = false) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = if (muted) t.faint else t.muted,
        fontFamily = JetBrainsMono,
        fontSize = 10.6.sp,
        maxLines = 1,
        modifier = Modifier
            .background(t.tintXs, RoundedCornerShape(999.dp))
            .border(1.dp, t.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * The collapsible Subagents roster: Session-tab-only headline that expands
 * into rows jumping to each run's tab.
 */
@Composable
fun SubagentRoster(
    runs: List<SubagentRun>,
    summary: SubagentRosterSummary,
    activeRunId: String?,
    onSelect: (String?) -> Unit,
) {
    val t = LocalTetherTokens.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summaryParts = buildList {
        if (summary.running > 0) add("${summary.running} running")
        if (summary.errored > 0) add("${summary.errored} failed")
        summary.tokens?.let { add("${compactNumber(it)} tok") }
        summary.estimatedCostUSD?.let { add("~${estimatedUsd(it)}") }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimens.radiusMd))
            .background(t.mineralDeep)
            .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = TetherDimens.touchTargetDp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Lucide.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            Text("Subagents", color = t.ink, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 12.8.sp)
            Text("${summary.total}", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.strong, fontSize = 11.5.sp)
            Spacer(Modifier.weight(1f))
            Text(
                summaryParts.joinToString(" · "),
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = t.faint,
                modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            if (summary.partial && summary.measured > 0) {
                Text(
                    "Totals cover ${summary.measured} of ${summary.total} runs — the rest have no captured usage.",
                    color = t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.8.sp,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                )
            }
            runs.forEach { run ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(run.runId) }
                        .then(
                            if (run.runId == activeRunId) {
                                Modifier.border(1.dp, t.violetStrong)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RunStatusIcon(run.status, 12.dp)
                        Text(
                            run.title,
                            color = if (run.status == RUN_ERROR) t.danger else t.ink,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.label,
                            fontSize = 12.8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        run.agentType?.let {
                            Text(it, color = t.faint, fontFamily = JetBrainsMono, fontSize = 10.6.sp)
                        }
                    }
                    SubagentRunStats(run)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * One sub-agent run, expanded: what it was asked, every step it took, and the
 * result it handed back — full width, no collapse.
 */
@Composable
fun SubagentRunPanel(run: SubagentRun, showThinking: Boolean) {
    val t = LocalTetherTokens.current
    val entries = remember(run, showThinking) { subagentRunEntries(run, showThinking) }
    val elapsed = elapsedLabel(run.elapsedSeconds?.toLong())
    val result = remember(run.output) { toolOutputText(run.output, 4000) }
    var promptExpanded by rememberSaveable(run.runId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Header: title + identity/status chips + readings.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
                Text(
                    run.title,
                    color = t.white,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.heading,
                    fontSize = 15.2.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                run.agentType?.let { SubrunChip(it) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(t.tintXs, RoundedCornerShape(999.dp))
                        .border(1.dp, t.line, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    RunStatusIcon(run.status, 12.dp)
                    Text(
                        STATUS_TEXT[run.status].orEmpty() + if (run.status == RUN_RUNNING && elapsed.isNotEmpty()) " · $elapsed" else "",
                        color = if (run.status == RUN_ERROR) t.danger else t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 10.6.sp,
                    )
                }
                SubrunChip("${run.steps} step${if (run.steps == 1) "" else "s"}")
            }
            SubagentRunStats(run)
        }

        // The task, collapsed.
        if (run.prompt != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TetherDimens.radiusMd))
                    .background(t.tintXs)
                    .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { promptExpanded = !promptExpanded }
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Task given to this sub-agent",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 12.2.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Lucide.ChevronRight,
                        contentDescription = if (promptExpanded) "Collapse" else "Expand",
                        tint = t.faint,
                        modifier = Modifier.size(14.dp).rotate(if (promptExpanded) 90f else 0f),
                    )
                }
                if (promptExpanded) {
                    Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                        MarkdownText(text = run.prompt, color = t.ink, fontSize = 13.1.sp)
                    }
                }
            }
        }

        // The step stream.
        if (entries.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.status == RUN_RUNNING) {
                    SpinningIcon(Lucide.Loader, tint = t.muted, size = 14.dp)
                    Text(
                        "Waiting for this sub-agent’s first step…",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 13.1.sp,
                    )
                } else {
                    Icon(Lucide.CircleStop, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
                    Text(
                        "No step-by-step activity was recorded for this run.",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 13.1.sp,
                    )
                }
            }
        } else {
            entries.forEach { entry ->
                when (entry.kind) {
                    "message" -> AgentBubble(
                        TurnBlock(blockId = entry.key, kind = "message", text = entry.text, done = true),
                    )
                    "thinking" -> ThinkingCard(
                        TurnBlock(blockId = entry.key, kind = "thinking", text = entry.text, done = true),
                    )
                    else -> SubrunToolCard(entry)
                }
            }
        }

        // The result handed back to the parent.
        if (run.status != RUN_RUNNING && !result.isNullOrEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TetherDimens.radiusMd))
                    .background(t.mineralDeep)
                    .border(1.dp, if (run.isError) t.dangerEdge else t.line, RoundedCornerShape(TetherDimens.radiusMd)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (run.isError) {
                        Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(13.dp))
                    } else {
                        Icon(Lucide.Check, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
                    }
                    Text(
                        if (run.isError) "Error returned to the parent" else "Result returned to the parent",
                        color = if (run.isError) t.danger else t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 12.2.sp,
                    )
                }
                Text(
                    result,
                    color = t.ink,
                    fontFamily = JetBrainsMono,
                    fontSize = 12.2.sp,
                    lineHeight = 12.2.sp * 1.5f,
                    modifier = Modifier.fillMaxWidth().background(t.tintXs).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One nested tool call in a run's stream — the compact subrun-block form. */
@Composable
private fun SubrunToolCard(entry: SubagentEntry) {
    val t = LocalTetherTokens.current
    val running = entry.done != true
    val isError = entry.isError == true
    val summary = remember(entry.name, entry.input) { toolInputSummary(entry.name, entry.input) }
    val output = remember(entry.output) { toolOutputText(entry.output) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimens.radiusMd))
            .background(t.mineralDeep)
            .border(
                1.dp,
                when {
                    isError -> t.dangerEdge
                    running -> t.lineStrong
                    else -> t.line
                },
                RoundedCornerShape(TetherDimens.radiusMd),
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                running -> SpinningIcon(Lucide.Loader, tint = t.muted, size = 14.dp)
                isError -> Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(14.dp))
                else -> Icon(Lucide.Wrench, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            }
            Text(
                entry.name ?: "tool",
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontWeight = TetherWeights.label,
                fontSize = 12.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            val statusText = when {
                running -> {
                    val secs = entry.elapsedSeconds?.toLong()
                    if (secs != null) "running · ${elapsedLabel(secs)}" else "running"
                }
                isError -> "error"
                else -> "done"
            }
            Text(
                statusText,
                color = if (isError) t.danger else t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 11.5.sp,
                letterSpacing = 0.05.em,
            )
        }
        summary?.let {
            Text(
                it,
                color = t.muted,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
        if (!running && output != null) {
            Text(
                output,
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                lineHeight = 12.2.sp * 1.5f,
                modifier = Modifier.fillMaxWidth().background(t.tintXs).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

> **Review hardening (added during execution):** `SubagentRoster` early-returns
> when `summary.total == 0` (first statement, before the token lookup) —
> self-defending guard matching the web's `if (summary.total === 0) return null`,
> so an empty "Subagents · 0" card can never render even if a future caller
> forgets the `runs.isNotEmpty()` gate.

---

### Task 11: ChatScreen integration — tab strip, transcript swap, roster

**Files:**
- Modify: `app/src/main/java/com/tether/app/ui/chat/ChatScreen.kt`

Web invariants to preserve (`chat-view.tsx` lines 1234–1256):
1. The tab strip renders only when runs exist.
2. A run tab replaces ONLY the transcript — the active turn's pending
   approval/question cards render below the panel on every tab.
3. The roster sits at the TOP of the Session transcript (scrolls away), only
   when no run tab is active.
4. Tab-switch scroll: a running run follows newest; a finished run parks at top.

- [ ] **Step 1: Compute runs + selection in `ChatScreen`**

Add imports:

```kotlin
import com.tether.app.protocol.reduce.RUN_RUNNING
import com.tether.app.protocol.reduce.collectSubagentRuns
import com.tether.app.protocol.reduce.subagentRosterSummary
```

Inside `ChatScreen`, after the `showThinking` line:

```kotlin
    val selectedRunIds by vm.selectedRunIdBySession.collectAsStateWithLifecycle()
    val runs = remember(projection) { collectSubagentRuns(projection) }
    // Resolve by lookup, never by trusting the stored id: a run that vanished
    // from a re-snapshot degrades to the Session tab on its own.
    val activeRun = session?.let { selectedRunIds[it.id] }?.let { id -> runs.firstOrNull { it.runId == id } }
```

- [ ] **Step 2: Render the tab strip between the header and the transcript**

Inside the root `Column`, after the `WorkspaceHeader` `if` block and before
the `Box(Modifier.weight(1f)…)`:

```kotlin
        if (session != null && runs.isNotEmpty()) {
            SubagentTabs(
                runs = runs,
                activeRunId = activeRun?.runId,
                onSelect = { runId -> vm.selectRun(session.id, runId) },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }
```

- [ ] **Step 3: Swap the transcript content on a run tab**

In the transcript `Box(Modifier.weight(1f).fillMaxWidth())`'s `when`, replace
the final `else -> Transcript(...)` branch with:

```kotlin
                activeRun != null -> RunTab(
                    vm = vm,
                    sessionId = session.id,
                    projection = projection,
                    run = activeRun,
                    showThinking = showThinking,
                )

                else -> Transcript(
                    vm = vm,
                    sessionId = session.id,
                    projection = projection,
                    showThinking = showThinking,
                    roster = if (runs.isNotEmpty()) {
                        {
                            SubagentRoster(
                                runs = runs,
                                summary = subagentRosterSummary(runs),
                                activeRunId = null,
                                onSelect = { runId -> vm.selectRun(session.id, runId) },
                            )
                        }
                    } else {
                        null
                    },
                )
```

(The earlier `when` branches — no session / connecting / empty transcript —
are unchanged. The empty-transcript branch precedes these, so a run can only
be selected once blocks exist, which is exactly when `runs` is non-empty.)

- [ ] **Step 4: Add the roster slot to `Transcript`**

Change the `Transcript` signature:

```kotlin
@Composable
private fun Transcript(
    vm: TetherViewModel,
    sessionId: String,
    projection: SessionProjection,
    showThinking: Boolean,
    roster: (@Composable () -> Unit)? = null,
) {
```

Inside its `LazyColumn`, BEFORE `items.forEach { item ->`:

```kotlin
            if (roster != null) {
                item(key = "subagent-roster") { roster() }
            }
```

- [ ] **Step 5: Add the `RunTab` composable**

After the `Transcript` composable in `ChatScreen.kt`:

```kotlin
/**
 * A sub-agent run tab: the run panel replaces the transcript, but the active
 * turn's pending approval/question cards render below it on every tab — a
 * card the turn is stalled on must never be hidden behind a tab.
 */
@Composable
private fun RunTab(
    vm: TetherViewModel,
    sessionId: String,
    projection: SessionProjection,
    run: com.tether.app.protocol.reduce.SubagentRun,
    showThinking: Boolean,
) {
    val listState = rememberLazyListState()
    val activeTurn = projection.activeTurnId?.let { projection.turnsById[it] }
    val pending = activeTurn?.pendingApprovals?.values?.toList().orEmpty()
    val pendingQ = activeTurn?.pendingQuestions?.values?.toList().orEmpty()
    val itemCount = 1 + pending.size + pendingQ.size

    // Web scroll intent per destination: a running run follows the newest
    // activity; a finished run reads from the top.
    LaunchedEffect(run.runId) {
        if (run.status == RUN_RUNNING && itemCount > 0) {
            listState.scrollToItem(itemCount - 1, scrollOffset = Int.MAX_VALUE / 2)
        } else {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "run-panel") {
            SubagentRunPanel(run = run, showThinking = showThinking)
        }
        pending.forEach { approval ->
            item(key = "approval/${approval.requestId}") {
                ApprovalCard(
                    approval = approval,
                    onChoice = { choiceId, decision ->
                        vm.client.approval(sessionId, approval.requestId, choiceId, decision)
                    },
                )
            }
        }
        pendingQ.forEach { question ->
            item(key = "question/${question.requestId}") {
                QuestionCard(
                    question = question,
                    onSubmit = { answers, response ->
                        vm.client.answerQuestion(sessionId, question.requestId, answers, response)
                    },
                )
            }
        }
    }
}
```

The jump-to-latest pill belongs to the main transcript only — `RunTab` has no
pill (matches the web: the pill lives in the transcript scroller the panel
replaces). No code needed beyond the swap.

- [ ] **Step 6: Build + full unit suite**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` for both

> **Review fix (added during execution):** `RunTab`'s scroll effect is now
> content-keyed — `LaunchedEffect(run.runId, run.steps, pending.size,
> pendingQ.size, run.status)` re-fires as the run streams, reading the current
> last index inside the effect body — so a running run continuously follows
> newest activity (web parity) instead of scrolling once on tab entry. The
> finished-run case still parks at top. The now-unused `itemCount` val was
> removed.

---

### Task 12: Spec update + final verification

**Files:**
- Modify: `specs/visual-spec.md`

- [ ] **Step 1: Update the visual spec**

In `specs/visual-spec.md` §4 Composer section, replace the line:

```
- Mode row (claude only): "Mode" label + pill select (28.8dp height, key-face, radius pill) + model chip (Cpu 13 + name, pill, hover→violet). May defer to v1.5.
```

with:

```
- Mode row (claude only, implemented): ShieldCheck 14 + "Mode" label + permission-mode pill (29dp height, key-face, radius pill, drop-up menu with label+hint rows, danger styling on "Auto") + right-aligned model chip (Cpu 13 + active model name, pill → model picker panel with Default-row synthesis / "CLI Default (<resolved>)" labeling / Check on active / empty state "Send a message first to load the available models."). Slash-command drop-up (claude only): rows "/name <argHint>" + description + "Tether" | "terminal only" tag; /model is Tether-native (picker + free-text resolution); unsupported/unknown flash an in-composer notice row (Terminal 14 + text, 6 s auto-dismiss).
- Sub-agent tabs (implemented): when a session has Agent/Task tool blocks, a tab strip sits between workspace header and transcript — "Session" tab (+ "N running" badge when a run tab is active) + one tab per run (status icon+text: spinner "running" / AlertTriangle "error" / Check + "N steps"; title; selected = violet-wash bg + violet-strong border; error tab = danger border + danger text). A run tab replaces ONLY the transcript with the run panel (Bot + title, agentType/status/steps chips, stats chips — served model, "asked X", effort, "N tok" | "usage not captured", ~$ est, collapsible "Task given to this sub-agent", step stream, "Result/Error returned to the parent"); the active turn's approval/question cards stay visible below the panel on every tab. The Session transcript opens with the collapsed "Subagents" roster (count + "N running · N failed · N tok · ~$" summary + partial-totals note; rows jump to tabs).
```

- [ ] **Step 2: Full suite + build**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` for both

- [ ] **Step 3: Manual verification checklist (owner, on device)**

1. Keyboard: tap the text field — composer rides tight above the IME, no gap.
2. Claude session: mode row visible; switching Mode updates on the server
   (web client reflects it); "Auto" shows danger styling in the pill and menu.
3. Model chip shows the active model; picker lists models with Check on the
   active one; picking flashes the confirmation notice; `/model` and
   `/model sonnet` work from the composer; `/vim` (or any terminal-only
   command) flashes the terminal-only notice instead of sending.
4. Session with sub-agents: tab strip appears; tabs swap the transcript; a
   running run streams its steps; approvals still show on the run tab; the
   roster expands on the Session tab and jumps to tabs.
5. Codex session: no mode row, no model chip, no slash menu — unchanged.

---

## Notes for the executor

- **Previews**: `ui/Previews.kt` and the fake client drive screenshots. After
  Task 6 the fake seeds controls for `s-active`; check that the seeded
  launcher tool block in `FakeTetherClient.activeProjection()` is named
  `Agent` or `Task` so previews show the tab strip — rename it if not.
- **Hand-sync discipline**: `PermissionModes.kt` carries the hand-synced copy
  of `aidash/lib/protocol.ts` `PERMISSION_MODE_OPTIONS`; the canary is
  `PermissionModesTest`. If the web table changes, update both.
- **No commits** — the repo owner commits explicitly (see the git policy note
  at the top).
