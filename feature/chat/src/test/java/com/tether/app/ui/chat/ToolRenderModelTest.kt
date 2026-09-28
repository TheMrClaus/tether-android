package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.2 unit checks of the pure tool-card helpers (JS outputs from node where noted). */
class ToolRenderModelTest {
    private fun js(text: String) = JsCodec.parse(text)

    @Test fun prettyStringifyMatchesJsonStringifyWithTwoSpaces() {
        // node: JSON.stringify({b:1,"2":[],"1":{},a:[1,"x ",{c:null}],e:1e21,f:0.1,g:-0}, null, 2)
        val value = js("""{"b":1,"2":[],"1":{},"a":[1,"x ",{"c":null}],"e":1e21,"f":0.1,"g":-0}""")
        assertEquals(
            "{\n  \"1\": {},\n  \"2\": [],\n  \"b\": 1,\n  \"a\": [\n    1,\n    \"x \",\n    {\n      \"c\": null\n    }\n  ],\n  \"e\": 1e+21,\n  \"f\": 0.1,\n  \"g\": 0\n}",
            jsonStringifyPretty(value),
        )
        assertEquals("\"a\\nb\"", jsonStringifyPretty(js("\"a\\nb\"")))
        assertEquals("", summarize(null))
        assertEquals("", summarize(js("null")))
        assertEquals("plain", summarize(js("\"plain\"")))
        assertEquals("x".repeat(600) + "…", summarize(js("\"${"x".repeat(601)}\"")))
        assertEquals("x".repeat(600), summarize(js("\"${"x".repeat(600)}\"")))
    }

    @Test fun toolInputRendersFileEditsAsDiffsAndTheRestAsJson() {
        val write = toolInputModel("Write", js("""{"file_path":"src/v.ts","content":"a\nb"}""")) as ToolInputModel.Edit
        assertEquals("src/v.ts", write.filePath)
        assertEquals("new / overwrite", write.tag)
        assertEquals(listOf(EditDiffRow("add", "a"), EditDiffRow("add", "b")), write.diffs.single())

        val edit = toolInputModel("Edit", js("""{"file_path":"a","old_string":"1\n2\n3\n4","new_string":"1\n2\nX\n4","replace_all":true}""")) as ToolInputModel.Edit
        assertEquals("all matches", edit.tag)
        assertEquals(listOf("ctx:1", "ctx:2", "del:3", "add:X", "ctx:4"), edit.diffs.single().map { "${it.t}:${it.text}" })
        assertNull((toolInputModel("Edit", js("""{"file_path":"a","old_string":"x","new_string":"y","replace_all":0}""")) as ToolInputModel.Edit).tag)

        val multi = toolInputModel("MultiEdit", js("""{"file_path":"a","edits":[{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d"},null]}""")) as ToolInputModel.Edit
        assertEquals("2 edits", multi.tag)
        assertEquals(2, multi.diffs.size)
        assertEquals("1 edit", (toolInputModel("MultiEdit", js("""{"file_path":"a","edits":[{"old_string":"a","new_string":"b"}]}""")) as ToolInputModel.Edit).tag)

        // No path (or an empty one), a missing string, or another tool: pretty JSON.
        assertTrue(toolInputModel("Write", js("""{"file_path":"","content":"x"}""")) is ToolInputModel.Raw)
        assertTrue(toolInputModel("Edit", js("""{"file_path":"a","old_string":"x"}""")) is ToolInputModel.Raw)
        assertTrue(toolInputModel("MultiEdit", js("""{"file_path":"a","edits":[{"old_string":1}]}""")) is ToolInputModel.Raw)
        assertEquals(ToolInputModel.Raw("{\n  \"command\": \"ls\"\n}"), toolInputModel("Bash", js("""{"command":"ls"}""")))
    }

    @Test fun aHugeWriteRendersTwoHundredRowsAndCountsTheRest() {
        val rows = (1..5_001).map { EditDiffRow("add", "line $it") }
        val capped = capDiff(rows)
        assertEquals(MAX_DIFF_ROWS, capped.shown.size)
        assertEquals(4_801, capped.hidden)
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("+4,801 more lines", moreLinesLabel(capped.hidden))
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals("+1 more line", moreLinesLabel(1))
        assertEquals(0, capDiff(rows.take(200)).hidden)
    }

    @Test fun theSeededToolScenarioSummarisesLikeTheWebShot() {
        // parity-seed.mjs `tools`: the web shot reads "1 file read, 2 searches, 2 shell commands,
        // 2 file edits, 1 file write, 1 web request, 1 tool call" (the MCP card with media stands apart).
        val folded = ToolFixtures.tools
        val turn = folded.tree.obj("turnsById").obj("t1")
        val ids = turn.array("blocks")
        val byId = turn.obj("blocksById")
        val segments = segmentBlocks(ids) { byId[it] as? JsObj }
        val groups = segments.filterIsInstance<ActivitySegment.Group>()
        assertEquals(1, groups.size)
        val blocks = groups.single().blockIds.map { byId[it] as JsObj }
        assertEquals("1 file read, 2 searches, 2 shell commands, 2 file edits, 1 file write, 1 web request, 1 tool call", activitySummary(blocks))
        assertTrue(groupHasErrors(blocks))
        assertEquals(false, groupOpenByDefault(blocks))
        val media = segments.filterIsInstance<ActivitySegment.Single>().map { byId[it.blockId] as JsObj }.single { it.isToolBlock() }
        assertEquals("mcp__parity__render_chart", media.toolName())
        assertEquals(1, extractToolMedia(media["output"]).size)
        assertEquals("[\n  {\n    \"type\": \"text\",\n    \"text\": \"Rendered the chart.\"\n  }\n]", remainingToolText(media["output"]))
    }

    @Test fun anInterruptedCallIsNotAnErrorAndIsCounted() {
        val blocks = listOf(
            js("""{"kind":"tool","name":"Bash","done":true,"isError":true,"interrupted":true}""") as JsObj,
            js("""{"kind":"tool","name":"Read","done":true,"isError":false}""") as JsObj,
        )
        assertEquals("1 shell command, 1 file read · 1 interrupted", activitySummary(blocks))
        assertEquals(false, groupHasErrors(blocks))
        assertEquals(ToolState.Interrupted, toolStateOf(blocks[0]))
        assertEquals("interrupted", toolStatusText(blocks[0]))
        assertEquals("running · 1m 5s", toolStatusText(js("""{"kind":"tool","elapsedSeconds":65.4}""") as JsObj))
        assertEquals("running", toolStatusText(js("""{"kind":"tool"}""") as JsObj))
        assertEquals("error", toolStatusText(js("""{"kind":"tool","done":true,"isError":true}""") as JsObj))
    }

    @Test fun durationsFormatLikeToFixed() {
        // node: Math.round / toFixed over the same inputs.
        val cases = mapOf(42.0 to "42 ms", 0.5 to "1 ms", 999.5 to "1000 ms", 1234.0 to "1.2 s", 1250.0 to "1.3 s", 1050.0 to "1.1 s", 9999.0 to "10.0 s", 12345.0 to "12 s", 15500.0 to "16 s")
        for ((ms, text) in cases) assertEquals("$ms", text, formatDuration(ms))
        assertNull(formatDuration(null))
        assertEquals("running", richStatusText(true, true, "failed"))
        assertEquals("failed", richStatusText(false, true, null))
        assertEquals("declined", richStatusText(false, true, "declined"))
        assertEquals("completed", richStatusText(false, false, ""))
    }

    @Test fun planAndReviewViewsFollowTheCodexCards() {
        val turn = js(
            """{"plan":{"explanation":null,"steps":[]},"initialPlan":{"explanation":"Initial plan","steps":[{"status":"in_progress","step":"Read code"},{"status":"completed","step":"Edit"}]},
               "reviews":[{"reviewId":"r1","status":"started","target":"uncommitted changes"},{"reviewId":"r2","status":"failed"},{"reviewId":"r3","status":"cancelled","result":""}],
               "diff":{"unifiedDiff":"--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n"}}""",
        ) as JsObj
        val plan = transcriptPlan(turn)!!
        assertEquals("Initial plan", plan.explanation)
        assertEquals("1 of 2 complete", planCount(plan))
        assertTrue(planShows(plan))
        assertEquals(false, planShows(transcriptPlan(js("""{"plan":{"explanation":"","steps":[]}}""") as JsObj)))
        assertEquals("No steps", planCount(PlanView("x", emptyList())))
        assertEquals("In progress", planLabel("in_progress"))
        val reviews = reviews(turn)
        assertEquals(listOf("in progress", "failed", "cancelled"), reviews.map { it.statusLabel })
        assertEquals(listOf(true, false, false), reviews.map { it.running })
        assertNull(reviews[2].result)
        assertEquals("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n", turnUnifiedDiff(turn))
    }

    @Test fun gitChangeWordsNeverRelyOnColour() {
        assertEquals("Added", committedWord("A"))
        assertEquals("Renamed", committedWord("R100"))
        assertEquals("X", committedWord("X"))
        assertEquals("", committedWord(""))
        assertEquals("Untracked", uncommittedWord("??"))
        assertEquals("Unstaged modified", uncommittedWord(" M"))
        assertEquals("Staged added", uncommittedWord("A"))
        assertEquals("Staged modified · Unstaged deleted", uncommittedWord("MD"))
        assertEquals("Staged x", uncommittedWord("x "))
        assertEquals("Changed", uncommittedWord(""))
        assertEquals("add", hunkLineKind("+x"))
        assertEquals("context", hunkLineKind("+++ b/x"))
        assertEquals("del", hunkLineKind("-x"))
        assertEquals("context", hunkLineKind("--- a/x"))
        assertEquals("hunk", hunkLineKind("@@ -1 +1 @@"))
        val summary = worktreeDiffSummary(
            com.tether.app.protocol.TetherJson.parseToJsonElement(
                """{"branch":"b","baseRef":"origin/main","baseCommit":"c","head":null,"commitsAhead":2,"committed":[{"path":"a","status":"M"},{"status":"A"}],"uncommitted":[]}""",
            ) as kotlinx.serialization.json.JsonObject,
        )!!
        assertEquals("2 ahead", changesCount(summary))
        assertEquals("Compared against origin/main", changesBase(summary))
        assertEquals(1, summary.committed.size)
        val empty = summary.copy(commitsAhead = 0.0, committed = emptyList())
        assertEquals("vs base", changesCount(empty))
        assertEquals("No changes vs origin/main", changesBase(empty))
        assertNull(worktreeDiffSummary(null))
    }

    @Test fun boundedDecodePlansAndDataUris() {
        assertEquals(1, BoundedMediaDecoder.plan(48, 32, 4))
        assertEquals(2, BoundedMediaDecoder.plan(4096, 100, 4))
        assertEquals(2, BoundedMediaDecoder.plan(2048, 2048, 8)) // 32 MB at F16 > 16 MB
        assertEquals(null, BoundedMediaDecoder.plan(20_000, 20_000, 4))
        assertEquals("image/png" to "AAAA", parseDataUri("data:image/png;base64,AAAA"))
        assertNull(parseDataUri("data:image/png,AAAA"))
        assertNull(parseDataUri("/api/tool-media/x.png"))
        assertEquals("100%", zoomLabel(1f))
        assertEquals("196%", zoomLabel(1.96f))
    }

    @Test fun aPreDropsOneTrailingNewlineLikeTheBrowser() {
        // `<pre>a\n</pre>` lays out one line; "a\n\n" keeps its blank line.
        assertEquals("# tests 4\n# pass 4", preText("# tests 4\n# pass 4\n"))
        assertEquals("a\n", preText("a\n\n"))
        assertEquals("a", preText("a"))
        assertEquals("", preText("\n"))
        assertEquals("", preText(""))
    }

    @Test fun jsTrimMatchesStringPrototypeTrim() {
        assertEquals("a", jsTrim("\uFEFF  a \t"))
        assertEquals("\u0085a", jsTrim("\u0085a"))
        assertEquals("x", "x".breakAnywhere())
        assertEquals("a\u200Bb", "ab".breakAnywhere())
    }
}

internal fun JsObj.obj(key: String) = this[key] as JsObj
internal fun JsObj.array(key: String) = (this[key] as com.tether.app.protocol.tree.JsArr).map { (it as com.tether.app.protocol.tree.JsStr).value }

class SpawnedRunMediaTest {
    @Test fun picturesSplitBySourceWithTheirLabels() {
        val media = JsCodec.parse(
            """[{"type":"media_ref","mediaKind":"image","mediaType":"image/png","url":"/api/tool-media/${"a".repeat(64)}.png","bytes":9,"source":"input","label":"shot.png"},
               {"type":"media_ref","mediaKind":"image","mediaType":"image/png","url":"/api/tool-media/${"b".repeat(64)}.png","bytes":9,"source":"viewed","label":null},
               {"type":"media_ref","mediaKind":"image","mediaType":"image/png","url":"/api/tool-media/${"c".repeat(64)}.png","bytes":9,"source":"input","label":""}]""",
        )
        val (input, labels) = spawnedRunMedia(media, "input")
        assertEquals(2, input.size)
        assertEquals(listOf("shot.png"), labels)
        val (viewed, none) = spawnedRunMedia(media, "viewed")
        assertEquals(listOf("/api/tool-media/${"b".repeat(64)}.png"), viewed.map { it.src })
        assertTrue(none.isEmpty())
        assertTrue(spawnedRunMedia(null, "input").first.isEmpty())
    }
}
