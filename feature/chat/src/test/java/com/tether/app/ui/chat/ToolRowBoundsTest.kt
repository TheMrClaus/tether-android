package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.theme.TetherSkin
import kotlin.random.Random
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.2 round 6 (R5-M1, R5-L1..L3): every list a server supplies draws a bounded number of rows —
 * sub-agent steps (newest 50 while the parent runs, first 50 once done) and collaboration agent
 * states (50) — and the helpers that run per line or per block are regex- and split-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolRowBoundsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun js(s: String) = JsStr(s)

    private fun thread(steps: Int, mediaAt: Set<Int> = emptySet()): JsObj {
        val order = ArrayList<JsValue>(steps)
        val entries = LinkedHashMap<String, JsValue>(steps)
        for (i in 0 until steps) {
            val key = "s$i"
            order += js(key)
            val output: JsValue = if (i in mediaAt) {
                JsArr.of(JsObj.of("type" to js("media_ref"), "mediaKind" to js("image"), "mediaType" to js("image/png"), "url" to js("/api/tool-media/${"%064x".format(i)}.png")))
            } else {
                js("ok $i")
            }
            entries[key] = if (i % 3 == 0) {
                JsObj.of("key" to js(key), "kind" to js("message"), "text" to js("step $i"))
            } else {
                JsObj.of("key" to js(key), "kind" to js("tool"), "name" to js("Read"), "done" to JsBool.TRUE, "output" to output)
            }
        }
        return JsObj.of("order" to JsArr.of(order), "entries" to JsObj.from(entries))
    }

    private fun task(done: Boolean, thread: JsObj) = JsObj.of(
        "blockId" to js("task"),
        "kind" to js("tool"),
        "name" to js("Task"),
        "done" to if (done) JsBool.TRUE else null,
        "output" to if (done) js("finished") else null,
        "subagent" to thread,
    )

    @Test fun aRunningTaskDrawsItsNewestFiftyStepsAndADoneOneItsFirstFifty() {
        val big = thread(20_000)
        val started = System.nanoTime()
        val running = subagentWindow(big, showThinking = false, newest = true)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(50, running.entries.size)
        assertEquals("s19999", asString(running.entries.last()["key"]))
        assertEquals(20_000, running.total)
        assertEquals(19_950 to 0, running.earlier to running.later)
        assertTrue("one cheap pass: $ms ms", ms < 500)
        val done = subagentWindow(big, showThinking = false, newest = false)
        assertEquals("s0", asString(done.entries.first()["key"]))
        assertEquals(0 to 19_950, done.earlier to done.later)
        // Hidden thinking steps are not steps.
        val withThinking = JsObj.of(
            "order" to JsArr.of(js("a"), js("b")),
            "entries" to JsObj.of("a" to JsObj.of("kind" to js("thinking"), "text" to js("t")), "b" to JsObj.of("kind" to js("message"), "text" to js("m"))),
        )
        assertEquals(1, subagentWindow(withThinking, showThinking = false, newest = true).total)
        assertEquals(2, subagentWindow(withThinking, showThinking = true, newest = true).total)
    }

    @Test fun twentyThousandStreamedStepsDrawBoundedRowsPerDelta() {
        var block by mutableStateOf(task(done = false, thread(20_000)))
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                androidx.compose.foundation.layout.Box(Modifier.verticalScroll(rememberScrollState())) { ToolCard(block, showThinking = false) }
            }
        }
        rule.waitForIdle()
        fun drawnSteps() = rule.onAllNodes(hasText("step ", substring = true)).fetchSemanticsNodes().size +
            rule.onAllNodes(hasText("Read")).fetchSemanticsNodes().size
        assertTrue("bounded rows: ${drawnSteps()}", drawnSteps() <= SUBAGENT_ROWS_MAX)
        rule.onAllNodes(hasText("+19,950 earlier steps")).fetchSemanticsNodes().single()
        // Three more steps stream in: still 50 drawn, the newest among them.
        repeat(3) { n ->
            val started = System.nanoTime()
            rule.runOnIdle { block = task(done = false, thread(20_001 + n)) }
            rule.waitForIdle()
            assertTrue("bounded work per delta", (System.nanoTime() - started) / 1_000_000 < 5_000)
            assertTrue(drawnSteps() <= SUBAGENT_ROWS_MAX)
        }
        rule.onAllNodes(hasText("+19,953 earlier steps")).fetchSemanticsNodes().single()
        // Done: the first fifty, the rest counted after them.
        rule.runOnIdle { block = task(done = true, thread(20_003)) }
        rule.waitForIdle()
        // Done without media, the thread closes like the web's <details>; the reader opens it.
        rule.onNode(hasText("Subagent · 20003 steps")).performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("+19,953 more steps")).fetchSemanticsNodes().single()
        rule.onAllNodes(hasText("step 0")).fetchSemanticsNodes().single()
    }

    /** ta-a5jl: the bounded-rows rule, entered through the transcript: the Task's row, a tap, the sheet. */
    @Test fun aRunningTasksSheetDrawsOnlyItsNewestFiftySteps() {
        val events = ArrayList<com.tether.app.protocol.AgentEvent>()
        events += com.tether.app.protocol.reduce.ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k") }
        events += com.tether.app.protocol.reduce.ev("tool_start", "t1", ts = 1) {
            put("toolId", "task"); put("name", "Task"); put("input", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"description":"Walk the tree"}"""))
        }
        val items = (0 until 600).joinToString(",", "[", "]") { """{"key":"s$it","kind":"message","text":"step $it"}""" }
        events += com.tether.app.protocol.reduce.ev("subagent_message", "t1", ts = 1) {
            put("parentToolUseId", "task"); put("items", com.tether.app.protocol.TetherJson.parseToJsonElement(items))
        }
        rule.showTranscript(ChatFixtures.fold(*events.toTypedArray()))
        rule.onNode(rowLabel("Agent Walk the tree, running")).assertExists()
        rule.openRow("Agent Walk the tree, running")
        val inSheet = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())
        rule.onAllNodes(hasText("+550 earlier steps") and inSheet).fetchSemanticsNodes().single()
        val drawn = rule.onAllNodes(hasText("step ", substring = true) and inSheet).fetchSemanticsNodes().size
        assertTrue("bounded rows in the sheet: $drawn", drawn in 1..SUBAGENT_ROWS_MAX)
        rule.onAllNodes(hasText("step 599") and inSheet).fetchSemanticsNodes().single()
    }

    @Test fun theTilePlanCoversOnlyTheDrawnSteps() {
        // Media only at step 55 (past the first 50 of a done thread): nothing may load for it.
        val done = task(done = true, thread(100, mediaAt = setOf(55)))
        val plan = cardMediaPlan(done["subagent"] as JsObj, done, showThinking = false)
        assertEquals(null, plan.byEntry["s55"])
        // Running, the newest 50 include it.
        val running = task(done = false, thread(100, mediaAt = setOf(55)))
        assertEquals(1, cardMediaPlan(running["subagent"] as JsObj, running, showThinking = false).byEntry["s55"])
    }

    @Test fun twentyThousandAgentStatesDrawFiftyRows() {
        val states = JsObj.from((0 until 20_000).associate { "thread-$it" to (js("running") as JsValue) })
        val block = JsObj.of("kind" to js("tool"), "name" to js("collaboration:wait"), "done" to JsBool.TRUE, "input" to JsObj.EMPTY, "output" to JsObj.of("agentsStates" to states))
        val view = collaborationView(block)
        assertEquals(AGENT_ROWS_MAX, view.agents.size)
        assertEquals(20_000, view.agentsTotal)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                androidx.compose.foundation.layout.Box(Modifier.verticalScroll(rememberScrollState())) { ToolBlockView(block, ToolRenderFlags(richCodex = true, richOpencode = false, showThinking = false)) }
            }
        }
        rule.waitForIdle()
        assertEquals(AGENT_ROWS_MAX, rule.onAllNodes(hasText("thread-", substring = true)).fetchSemanticsNodes().size)
        val more = rule.onAllNodesWithTag("agents-more").fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals("+19,950 more agents", more)
    }

    @Test fun mcpNamesSplitAtTheFirstSlashLikeSplitAndJoin() {
        fun old(name: String): Pair<String, String> {
            val fallback = if (name.startsWith("mcp:")) name.substring(4).split("/") else emptyList()
            return (fallback.getOrNull(0).orEmpty().ifEmpty { "MCP" }) to (fallback.drop(1).joinToString("/").ifEmpty { "tool" })
        }
        val random = Random(6)
        val names = listOf("mcp:", "mcp:a", "mcp:a/", "mcp:/b", "mcp:a/b/c", "mcp://", "notmcp:a/b", "mcp:a//b", "") +
            List(5_000) { "mcp:" + (0 until random.nextInt(0, 8)).joinToString("") { listOf("a", "/", "b", "é")[random.nextInt(4)] } }
        for (name in names) {
            val v = mcpView(JsObj.of("kind" to js("tool"), "name" to js(name), "input" to JsObj.EMPTY))
            assertEquals(name, old(name), v.server to v.tool)
        }
    }

    @Test fun diffPathEqualsTheOldSplitAndRegexOnFuzz() {
        fun old(line: String): String? {
            val path = jsTrim(line.substring(4).split("\t", limit = 2)[0])
            return if (path == "/dev/null") null else path.replaceFirst(Regex("^[ab]/"), "")
        }
        val random = Random(7)
        val parts = listOf("a/", "b/", "/dev/null", " ", "\t", "x", "c/", "a", " ")
        repeat(20_000) {
            val line = listOf("--- ", "+++ ")[random.nextInt(2)] + (0 until random.nextInt(0, 6)).joinToString("") { parts[random.nextInt(parts.size)] }
            assertEquals(line, old(line), diffPath(line))
            // The offset form over a longer text gives the same.
            val text = "zz\n$line\nyy"
            assertEquals(line, old(line), diffPath(text, 3, 3 + line.length))
        }
    }

    @Test fun millionsOfPathLinesPastTheBudgetParseOnce() {
        val crafted = "diff --git a/f b/f\n" + "+x\n".repeat(10) + "--- a/late\n".repeat(1_000_000) + "+++ b/last"
        val started = System.nanoTime()
        val parse = parseUnifiedDiffBounded(crafted, 5)
        assertTrue("linear: ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 3_000)
        val file = parse.files.single()
        assertEquals("late", file.oldPath)
        assertEquals("last", file.newPath)
        // The same as the unbounded parse.
        val full = parseUnifiedDiff("diff --git a/f b/f\n+x\n--- a/one\n--- a/late\n+++ b/last").single()
        val bounded = parseUnifiedDiffBounded("diff --git a/f b/f\n+x\n--- a/one\n--- a/late\n+++ b/last", 2).files.single()
        assertEquals(full.oldPath to full.newPath, bounded.oldPath to bounded.newPath)
    }

    @Test fun emptyMultiEditsStillCountTowardTheSizeCap() {
        val edits = JsArr.of(List(300_000) { JsObj.of("old_string" to js(""), "new_string" to js("")) })
        val model = toolInputModel("MultiEdit", JsObj.of("file_path" to js("a"), "edits" to edits))
        assertTrue("300k empty edits are not diffed", model is ToolInputModel.Raw)
        val few = toolInputModel("MultiEdit", JsObj.of("file_path" to js("a"), "edits" to JsArr.of(JsObj.of("old_string" to js(""), "new_string" to js("x")))))
        assertTrue(few is ToolInputModel.Edit)
    }

    @Test fun aHiddenChangeWithNoDiffCountsOnlyAsAFile() {
        val oneFile = "diff --git a/f b/f\n" + (1..2_000).joinToString("\n") { "+r$it" }
        assertEquals("+1 more file", planDiffCard(listOf(oneFile, ""), headerCost = 1).more)
        assertEquals("+2 more lines · +1 more file", planDiffCard(listOf(oneFile, "+a\n+b"), headerCost = 1).more)
    }

    @Test fun aRefusedEditSaysWhy() {
        val big = "y".repeat(EDIT_DIFF_MAX_CHARS)
        val refused = toolInputModel("Edit", JsObj.of("file_path" to js("a"), "old_string" to js(big), "new_string" to js("x"))) as ToolInputModel.Raw
        assertEquals(TOO_LARGE_TO_DIFF, refused.note)
        val multi = toolInputModel("MultiEdit", JsObj.of("file_path" to js("a"), "edits" to JsArr.of(List(300_000) { JsObj.of("old_string" to js(""), "new_string" to js("")) }))) as ToolInputModel.Raw
        assertEquals(TOO_LARGE_TO_DIFF, multi.note)
        // A malformed Edit is raw for another reason: no note.
        assertEquals(null, (toolInputModel("Edit", JsObj.of("file_path" to js("a"), "old_string" to js("x"))) as ToolInputModel.Raw).note)
        assertEquals(null, (toolInputModel("Bash", JsObj.of("command" to js("ls"))) as ToolInputModel.Raw).note)
        rule.setContent { ChatHost(TetherSkin.StudioDark) { ToolInputView("Edit", JsObj.of("file_path" to js("a"), "old_string" to js(big), "new_string" to js("x"))) } }
        rule.waitForIdle()
        rule.onAllNodes(hasText(TOO_LARGE_TO_DIFF)).fetchSemanticsNodes().single()
    }

    @Test fun aClosedThreadGivesItsTilesBackToTheCardsResult() {
        fun media(seed: Int) = JsArr.of(List(12) { JsObj.of("type" to js("media_ref"), "mediaKind" to js("image"), "mediaType" to js("image/png"), "url" to js("/api/tool-media/${"%064x".format(seed * 100 + it)}.png")) })
        val thread = JsObj.of(
            "order" to JsArr.of(js("s0")),
            "entries" to JsObj.of("s0" to JsObj.of("key" to js("s0"), "kind" to js("tool"), "name" to js("Shot"), "done" to JsBool.TRUE, "output" to media(1))),
        )
        val block = JsObj.of("kind" to js("tool"), "name" to js("Task"), "done" to JsBool.TRUE, "output" to media(2), "subagent" to thread)
        val loader = ToolFixtures.FakeLoader()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                    androidx.compose.foundation.layout.Box(Modifier.verticalScroll(rememberScrollState())) { ToolCard(block, showThinking = false) }
                }
            }
        }
        rule.waitForIdle()
        // Open (it holds media): the step's 12 tiles, none for the result.
        val stepUrls = (0 until 12).map { "/api/tool-media/${"%064x".format(100 + it)}.png" }.toSet()
        val cardUrls = (0 until 12).map { "/api/tool-media/${"%064x".format(200 + it)}.png" }.toSet()
        assertEquals(stepUrls, loader.loads.toSet())
        rule.onNode(hasText("Subagent · 1 step")).performClick()
        rule.waitForIdle()
        assertTrue("closed, the result gets the tiles", loader.loads.toSet().containsAll(cardUrls))
    }

    @Test fun longTitlesAreCutBeforeTheyAreDrawn() {
        val long = "x".repeat(1_000_000)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides null) {
                    androidx.compose.foundation.layout.Column {
                        ToolCard(JsObj.of("kind" to js("tool"), "name" to js(long), "done" to JsBool.TRUE), showThinking = false)
                        ToolBlockView(JsObj.of("kind" to js("tool"), "name" to js("mcp:$long/t"), "done" to JsBool.TRUE), ToolRenderFlags(true, false, false))
                    }
                }
            }
        }
        rule.waitForIdle()
        val longest = rule.onAllNodes(hasText("xxxx", substring = true)).fetchSemanticsNodes()
            .maxOf { n -> n.config[SemanticsProperties.Text].joinToString("") { it.text }.length }
        assertTrue("drawn at most PATH_MAX + 1: $longest", longest <= PATH_MAX + 1)
    }
}
