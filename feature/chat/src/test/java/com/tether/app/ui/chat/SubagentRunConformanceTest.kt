package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.4 conformance: [collectSubagentRuns], [subagentRosterSummary], [subagentRunEntries],
 * [runForToolId], the tab labels, [selectProgress] and the background-command labels against the
 * web's OWN code (subagent-run-model.mjs as it is; the subagent-runs.tsx / todo-bar.tsx /
 * chat-view.tsx helpers sliced verbatim), run once over every reducer-corpus step that carries
 * runs, tasks, spawned runs, todos, plans or commands, plus synthetic edge cases
 * (tools/parity/gen-subagent-run-expectations.mjs → src/test/resources/subagent-runs/).
 *
 * Every field of every run is compared. JS `undefined` and `null` are one value here (the Kotlin
 * model has no undefined), so null keys are dropped on both sides at the run's top level.
 */
class SubagentRunConformanceTest {
    private val doc: JsObj by lazy {
        val text = checkNotNull(javaClass.getResource("/subagent-runs/expectations.json")) { "expectations.json missing" }.readText()
        JsCodec.parse(text) as JsObj
    }

    private fun JsValue?.obj() = this as JsObj
    private fun JsValue?.arr() = this as JsArr
    private fun JsValue?.str() = (this as JsStr).value

    private fun js(value: String?): JsValue = value?.let(::JsStr) ?: JsNull
    private fun js(value: Double?): JsValue = value?.let(::JsNum) ?: JsNull
    private fun js(value: Int?): JsValue = value?.let { JsNum(it.toDouble()) } ?: JsNull
    private fun js(value: Boolean): JsValue = JsBool.of(value)

    private fun withoutNulls(o: JsObj): JsObj = JsObj.from(o.filterValues { it !is JsNull })

    private fun tree(run: SubagentRun): JsObj = withoutNulls(
        JsObj.from(
            linkedMapOf(
                "requestedModel" to js(run.requestedModel),
                "requestedEffort" to js(run.requestedEffort),
                "childProvider" to js(run.childProvider),
                "delegateMode" to js(run.delegateMode),
                "provider" to js(run.provider),
                "source" to js(run.source),
                "usage" to (run.usage ?: JsNull),
                "totalTokens" to js(run.totalTokens),
                "runId" to js(run.runId),
                "toolId" to js(run.toolId),
                "turnId" to js(run.turnId),
                "index" to js(run.index),
                "title" to js(run.title),
                "agentType" to js(run.agentType),
                "prompt" to js(run.prompt),
                "status" to js(run.status),
                "background" to js(run.background),
                "taskStatus" to js(run.taskStatus),
                "unconfirmed" to js(run.unconfirmed),
                "steps" to js(run.steps),
                "elapsedSeconds" to js(run.elapsedSeconds),
                "thread" to (run.thread ?: JsNull),
                "output" to (run.output ?: JsNull),
                "isError" to js(run.isError),
                "spawned" to (
                    run.spawned?.let {
                        JsObj.from(
                            linkedMapOf(
                                "id" to js(it.id),
                                "origin" to js(it.origin),
                                "status" to js(it.status),
                                "logFile" to js(it.logFile),
                                "nativeId" to js(it.nativeId),
                                "exitCode" to js(it.exitCode),
                                "output" to js(it.output),
                                "outputTruncated" to js(it.outputTruncated),
                                "media" to it.media,
                            ),
                        )
                    } ?: JsNull
                    ),
                "parentRunId" to js(run.parentRunId),
                "depth" to js(run.depth),
                "children" to JsArr.of(run.children.map { JsStr(it.runId) }),
                "agentThreadId" to js(run.agentThreadId),
                "agentPath" to js(run.agentPath),
                "lifecycle" to JsArr.of(run.lifecycle.map(::JsStr)),
            ),
        ),
    )

    private fun canon(v: JsValue?) = JsCodec.canonical(v ?: JsNull)

    @Test fun everyRunMatchesTheWebField_by_field() {
        val states = doc["states"].arr()
        assertTrue("the corpus must hold run states", states.size >= 100)
        var runsSeen = 0
        val sources = HashSet<String>()
        for (entryValue in states) {
            val entry = entryValue.obj()
            val where = entry["source"].str()
            val runs = collectSubagentRuns(entry["state"].obj())
            val expected = entry["runs"].arr()
            assertEquals("$where run ids", expected.map { it.obj()["runId"].str() }, runs.map { it.runId })
            runs.forEachIndexed { i, run ->
                assertEquals("$where ${run.runId}", canon(withoutNulls(expected[i].obj())), canon(tree(run)))
                sources.add(run.source)
            }
            runsSeen += runs.size
        }
        assertTrue("runs compared: $runsSeen", runsSeen >= 500)
        assertEquals(setOf(RunSource.LAUNCHER, RunSource.DELEGATE, RunSource.THREAD, RunSource.SPAWNED), sources)
    }

    @Test fun labelsSummaryEntriesAndOwnersMatchTheWeb() {
        for (entryValue in doc["states"].arr()) {
            val entry = entryValue.obj()
            val where = entry["source"].str()
            val runs = collectSubagentRuns(entry["state"].obj())
            val byId = runs.associateBy { it.runId }
            entry["labels"].arr().forEachIndexed { i, l ->
                val labels = l.obj()
                val run = runs[i]
                assertEquals("$where ${run.runId} status", labels["status"].str(), statusLabel(run))
                assertEquals("$where ${run.runId} statusText", labels["statusText"].str(), runStatusText(run))
                assertEquals("$where ${run.runId} harness", (labels["harness"] as? JsStr)?.value, harnessLabel(run))
                assertEquals("$where ${run.runId} gap", labels["gap"].str(), usageGapReason(run))
                assertEquals("$where ${run.runId} title", labels["title"].str(), runTabTitle(run, byId))
            }
            val s = subagentRosterSummary(runs)
            val summary = JsObj.from(
                linkedMapOf(
                    "total" to js(s.total), "running" to js(s.running), "errored" to js(s.errored), "done" to js(s.done),
                    "tokens" to js(s.tokens), "measured" to js(s.measured), "partial" to js(s.partial),
                ),
            )
            assertEquals("$where summary", canon(entry["summary"]), canon(summary))
            entry["entries"].arr().forEachIndexed { i, pair ->
                val (hidden, shown) = pair.arr().map { list -> list.arr().map { it.str() } }
                assertEquals("$where ${runs[i].runId} entries", hidden, subagentRunEntries(runs[i], false).map { (it["key"] as JsStr).value })
                assertEquals("$where ${runs[i].runId} entries+thinking", shown, subagentRunEntries(runs[i], true).map { (it["key"] as JsStr).value })
            }
            for (owner in entry["toolOwners"].arr()) {
                val (toolId, runId) = owner.arr()
                assertEquals("$where owner of ${toolId.str()}", (runId as? JsStr)?.value, runForToolId(runs, toolId.str())?.runId)
            }
        }
    }

    @Test fun progressMatchesTheWebTodoBar() {
        var seen = 0
        for (entryValue in doc["states"].arr()) {
            val entry = entryValue.obj()
            val progress = selectProgress(entry["state"].obj())
            val actual: JsValue = progress?.let { p ->
                JsObj.from(
                    linkedMapOf(
                        "items" to JsArr.of(p.items.map { JsObj.of("label" to JsStr(it.label), "status" to JsStr(it.status)) }),
                        "activeLabel" to js(p.activeLabel),
                        "completed" to js(p.completed),
                        "total" to js(p.total),
                    ),
                )
            } ?: JsNull
            assertEquals("${entry["source"].str()} progress", canon(entry["progress"]), canon(actual))
            if (progress != null) seen++
        }
        assertTrue("progress states compared: $seen", seen >= 20)
    }

    @Test fun commandLabelsMatchTheWeb() {
        for (c in doc["commands"].arr()) {
            val row = c.obj()
            val view = backgroundCommandView(row["command"].obj().put("command", JsStr("x")))!!
            assertEquals(row["label"].str(), backgroundCommandStatusLabel(view))
            assertEquals((row["failed"] as JsBool).value, backgroundCommandFailed(view))
        }
    }
}
