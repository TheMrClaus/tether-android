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
 * T6.2 conformance: the Kotlin tool-card view models against the web's OWN code, run once over
 * every tool block and turn of the vendored reducer corpus plus synthetic edge cases
 * (tools/parity/gen-tool-render-expectations.mjs → src/test/resources/tool-render/expectations.json).
 * Covers codex-rich-render-model.mjs, opencode-rich-render-model.mjs, chat-tool-render.tsx's
 * summarize / media / lineDiff and chat-view.tsx's grouping, value for value.
 */
class ToolRenderConformanceTest {
    private val doc: JsObj by lazy {
        val text = checkNotNull(javaClass.getResource("/tool-render/expectations.json")) { "expectations.json missing" }.readText()
        JsCodec.parse(text) as JsObj
    }

    private fun JsValue?.obj() = this as JsObj
    private fun JsValue?.arr() = this as JsArr
    private fun JsValue?.str() = (this as JsStr).value

    private fun v(value: Any?): JsValue = when (value) {
        null -> JsNull
        is String -> JsStr(value)
        is Boolean -> JsBool.of(value)
        is Int -> JsNum(value.toDouble())
        is Long -> JsNum(value.toDouble())
        is Double -> JsNum(value)
        is JsValue -> value
        is List<*> -> JsArr.of(value.map { v(it) })
        else -> error("unsupported $value")
    }

    private fun o(vararg pairs: Pair<String, Any?>): JsObj = JsObj.from(pairs.associate { (k, x) -> k to v(x) })

    private fun same(where: String, expected: JsValue?, actual: JsValue) =
        assertEquals(where, JsCodec.canonical(expected ?: JsNull), JsCodec.canonical(actual))

    @Test fun everyBlockViewMatchesTheWeb() {
        val blocks = doc["blocks"].arr()
        assertTrue("the corpus must hold tool blocks", blocks.size > 40)
        for (entryValue in blocks) {
            val entry = entryValue.obj()
            val where = entry["source"].str()
            val block = entry["block"].obj()
            same("$where codexKind", entry["codexKind"], v(codexRichToolKind(block)))
            same("$where opencodeKind", entry["opencodeKind"], v(opencodeRichToolKind(block)))
            commandView(block).run {
                same("$where command", entry["command"], o("command" to command, "cwd" to cwd, "source" to source, "output" to output, "status" to status, "exitCode" to exitCode, "durationMs" to durationMs, "running" to running, "failed" to failed))
            }
            fileChangeView(block).run {
                same(
                    "$where fileChange",
                    entry["fileChange"],
                    o("changes" to changes.map { o("key" to it.key, "path" to it.path, "kind" to it.kind, "diff" to it.diff) }, "streamingOutput" to streamingOutput, "status" to status, "running" to running, "failed" to failed),
                )
            }
            mcpView(block).run {
                same(
                    "$where mcp",
                    entry["mcp"],
                    o(
                        "server" to server, "tool" to tool, "arguments" to arguments, "progress" to progress, "result" to result, "status" to status,
                        "error" to error, "durationMs" to durationMs, "appName" to appName, "actionName" to actionName, "pluginId" to pluginId,
                        "running" to running, "failed" to failed,
                    ),
                )
            }
            collaborationView(block).run {
                same(
                    "$where collaboration",
                    entry["collaboration"],
                    o(
                        "action" to action, "receivers" to receivers, "prompt" to prompt, "model" to model, "reasoningEffort" to reasoningEffort,
                        "agents" to agents.map { o("id" to it.id, "state" to it.state) }, "status" to status, "running" to running, "failed" to failed,
                    ),
                )
            }
            subagentView(block).run { same("$where subagent", entry["subagent"], o("kind" to kind, "path" to path, "running" to running, "failed" to failed)) }
            taskView(block).run {
                same("$where task", entry["task"], o("description" to description, "prompt" to prompt, "subagentType" to subagentType, "output" to output, "running" to running, "failed" to failed))
            }
            same("$where summarizeInput", entry["summarizeInput"], v(summarize(block["input"])))
            same("$where summarizeOutput", entry["summarizeOutput"], v(summarize(block["output"])))
            same("$where media", entry["media"], v(extractToolMedia(block["output"]).map { m -> JsObj.from(buildMap { put("kind", v(m.kind)); put("mediaType", v(m.mediaType)); put("src", v(m.src)); m.bytes?.let { put("bytes", v(it)) } }) }))
            same("$where remainingText", entry["remainingText"], v(remainingToolText(block["output"])))
            same("$where groupable", entry["groupable"], v(isGroupableBlock(block)))
            same("$where category", entry["category"], v(toolCategory(block.toolName())))
        }
    }

    private fun diffJs(files: List<DiffFileView>): JsValue = v(files.map { f ->
        o("key" to f.key, "oldPath" to f.oldPath, "newPath" to f.newPath, "rows" to f.rows.map { o("kind" to it.kind, "marker" to it.marker, "text" to it.text) })
    })

    @Test fun everyTurnSegmentsSummarisesAndParsesLikeTheWeb() {
        val turns = doc["turns"].arr()
        assertTrue(turns.size > 10)
        for (entryValue in turns) {
            val entry = entryValue.obj()
            val where = entry["source"].str()
            val turn = entry["turn"].obj()
            val ids = (turn["blocks"] as? JsArr)?.map { it.str() } ?: emptyList()
            val byId = turn["blocksById"] as? JsObj ?: JsObj.EMPTY
            val segments = segmentBlocks(ids) { byId[it] as? JsObj }
            same(
                "$where segments",
                entry["segments"],
                v(segments.map { s -> when (s) { is ActivitySegment.Single -> o("type" to "block", "blockId" to s.blockId); is ActivitySegment.Group -> o("type" to "group", "blockIds" to s.blockIds) } }),
            )
            val groups = segments.filterIsInstance<ActivitySegment.Group>().map { g -> g.blockIds.map { byId[it] as JsObj } }
            same("$where summaries", entry["summaries"], v(groups.map(::activitySummary)))
            same("$where running", entry["running"], v(groups.map(::groupHasRunning)))
            same("$where inline diffs", entry["hasInlineFileChangeDiffs"], v(hasInlineFileChangeDiffs(turn)))
            val diff = turnUnifiedDiff(turn)
            if (turn["diff"] is JsObj) same("$where diff", entry["diff"], diffJs(parseUnifiedDiff(diff)))
        }
    }

    @Test fun syntheticDiffsLineDiffsTaskResultsAndPluralsMatch() {
        for (e in doc["diffs"].arr()) same("diff ${e.obj()["input"].str()}", e.obj()["files"], diffJs(parseUnifiedDiff(e.obj()["input"].str())))
        for (e in doc["lineDiffs"].arr()) {
            val x = e.obj()
            same("lineDiff", x["rows"], v(lineDiff(x["old"].str(), x["new"].str()).map { o("t" to it.t, "text" to it.text) }))
        }
        for (e in doc["taskResults"].arr()) same("task", e.obj()["output"], v(taskResultText(e.obj()["input"].str())))
        for (e in doc["pluralize"].arr()) {
            val x = e.obj()
            same("pluralize", x["text"], v(pluralize((x["count"] as JsNum).value.toInt(), x["singular"].str())))
        }
        val long = doc["summarizeLong"].obj()
        same("summarize cap", long["text"], v(summarize(long["input"])))
    }
}
