package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.lazy.LazyListState
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.4 against T6.1's performance bar. A running sub-agent with 5,000 steps is a lazy run tab, one
 * row per step: only a screenful composes, a new step composes its own rows (plus the one row
 * that was last before it), and every untouched step keeps its instance, so its row skips: a
 * delta that changes no shown step recomposes nothing. A long transcript does not recompose
 * any row while a background command streams its output (the command lives in the composer's
 * bar, and the finished chips are equal, so skipped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SubagentRunPerfTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val steps = 2_500 // × (a tool call + its result, then a message) = 5,000 entries
    private val adapter = LegacyProjectionAdapter()

    private fun step(n: Int): AgentEvent = ev("subagent_message", "t1", ts = 1_000) {
        put("parentToolUseId", "toolu_run")
        putJsonArray("items") {
            addJsonObject { put("key", "c$n"); put("kind", "tool"); put("name", "Read"); putJsonObject("input") { put("file_path", "src/$n.ts") } }
            addJsonObject { put("key", "c$n"); put("kind", "tool_result"); put("output", "x".repeat(800)); put("isError", false) }
            addJsonObject { put("key", "m$n"); put("kind", "message"); put("text", "Read file $n.") }
        }
    }

    private val base: JsObj by lazy {
        val events = buildList {
            add(ev("turn_started", "t1", ts = 1_000) { put("idempotencyKey", "k1") })
            add(ev("user_message_accepted", "t1", ts = 1_000) { put("text", "Read everything.") })
            add(ev("tool_start", "t1", ts = 1_000) { put("toolId", "toolu_run"); put("name", "Agent"); putJsonObject("input") { put("description", "Read every file") } })
            for (n in 1..steps) add(step(n))
        }
        events.fold(freshTree()) { acc, e -> reduce(acc, e.tree()) }
    }

    private fun runOf(tree: JsObj) = collectSubagentRuns(tree).single()

    @Test fun aNewStepAddsOneRowAndEveryOtherStepKeepsItsInstance() {
        val run = runOf(base)
        assertEquals(steps * 2, run.steps)
        val rows = panelRows(run, subagentRunEntries(run, showThinking = false))
        assertEquals(1 + steps * 2, rows.size) // the head (no prompt), then one row per entry
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        val next = reduce(base, step(steps + 1).tree())
        val runAfter = runOf(next)
        val after = panelRows(runAfter, subagentRunEntries(runAfter, showThinking = false))
        assertEquals(rows.size + 2, after.size)
        for (i in 1 until rows.size) assertSame("step row $i kept its entry", (rows[i] as PanelRow.Step).entry, (after[i] as PanelRow.Step).entry)
    }

    @Test fun aRunningRunOf5000StepsComposesAScreenfulAndANewStepOnlyItsRow() {
        var tree by mutableStateOf(base)
        val composed = mutableListOf<String>()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalChatRowObserver provides { key: String -> composed += key }) {
                    SubagentRunTab(runOf(tree), showThinking = false, pending = emptyList(), pendingQuestions = emptyList(), answeredIds = emptySet())
                }
            }
        }
        rule.waitForIdle()
        assertTrue("only a screenful is composed: ${composed.toSet().size}", composed.toSet().size < 40)
        // Following the newest step: the view is at the end.
        assertTrue(composed.contains("step/m$steps"))
        composed.clear()
        rule.runOnIdle {
            tree = reduce(tree, ev("subagent_message", "t1", ts = 1_000) {
                put("parentToolUseId", "toolu_run")
                putJsonArray("items") { addJsonObject { put("key", "hidden"); put("kind", "thinking"); put("text", "not shown") } }
            }.tree())
        }
        rule.waitForIdle()
        assertEquals("a delta that changes no shown step recomposes no row", emptyList<String>(), composed.distinct())
        repeat(4) { k ->
            composed.clear()
            val n = steps + 1 + k
            rule.runOnIdle { tree = reduce(tree, step(n).tree()) }
            rule.waitForIdle()
            val again = composed.distinct().toSet()
            // The new step's rows, plus the row that was last before it (the lazy list re-runs the
            // row whose end it moved, observed on every append; its data is unchanged, see above).
            // Constant per step, whatever the run's length.
            assertTrue("a new step composes its own rows only: $again", again.all { it in setOf("step/c$n", "step/m$n", "step/m${n - 1}") })
            assertTrue(again.contains("step/m$n"))
        }
    }

    @Test fun streamingBackgroundOutputRecomposesNoTranscriptRow() {
        val turns = 800
        var state = freshTree()
        for (n in 1..turns) {
            for (e in ChatFixtures.turn("t$n", "Prompt $n", "Reply $n", n * 1_000L)) state = reduce(state, e.tree())
        }
        fun cmd(id: String, status: String, at: Long) = evNullTurn("background_command_updated", ts = at) {
            put("commandId", id); put("command", "npm run $id"); put("cwd", "/w"); put("logFile", "/w/$id.log"); put("status", status); put("startedAt", at)
            if (status != "running") { put("exitCode", 0); put("endedAt", at + 1) }
        }
        state = reduce(state, cmd("done-1", "finished", turns * 1_000L - 500).tree())
        state = reduce(state, cmd("live", "running", turns * 1_000L + 10).tree())
        var holder by mutableStateOf(state to adapter.adapt(state)!!)
        val composed = mutableListOf<String>()
        val listState = LazyListState()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalChatRowObserver provides { key: String -> composed += key }) {
                    ChatTranscript(
                        projection = holder.second,
                        tree = holder.first,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        showTimeline = false,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue(composed.toSet().size < 40)
        repeat(5) { k ->
            composed.clear()
            rule.runOnIdle {
                val next = reduce(holder.first, evNullTurn("background_command_output", ts = 1) { put("commandId", "live"); put("stream", "stdout"); put("text", "line $k\n") }.tree())
                holder = next to adapter.adapt(next)!!
            }
            rule.waitForIdle()
            assertEquals("no transcript row recomposes", emptyList<String>(), composed.distinct())
        }
    }
}
