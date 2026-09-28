package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.2 against T6.1's performance bar: a 5,000-block transcript that is mostly tool calls stays a
 * lazy list — finished runs collapse to one row each, so far fewer rows than blocks — and a live
 * `tool_output_delta` recomposes only the streaming card (its group's summary row is equal, so
 * skipped). Long payloads are clamped (summarize caps at 600 chars, diffs at 200 rows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolTranscriptPerfTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val turns = 1000 // × 5 blocks (prompt, 3 tools, reply) = 5,000 blocks
    private val adapter = LegacyProjectionAdapter()

    private fun events(): List<AgentEvent> = (1..turns).flatMap { n ->
        val id = "t$n"
        val last = n == turns
        buildList {
            add(ev("turn_started", id, ts = n * 1000L) { put("idempotencyKey", "k$n") })
            add(ev("user_message_accepted", id, ts = n * 1000L) { put("text", "Prompt $n") })
            for (k in 0 until 3) {
                val tool = "$id:tool$k"
                val name = if (k == 2) "command_execution" else "Read"
                add(ev("tool_start", id, ts = n * 1000L) { put("toolId", tool); put("name", name); put("input", buildJsonObject { put("command", "npm test $n"); put("file_path", "src/$n.ts") }) })
                // The very last command is still streaming.
                if (!(last && k == 2)) add(ev("tool_end", id, ts = n * 1000L) { put("toolId", tool); put("output", "x".repeat(2_000)) })
            }
            if (!last) {
                add(ev("message_started", id, ts = n * 1000L) { put("blockId", "$id:m") })
                add(ev("message_completed", id, ts = n * 1000L) { put("blockId", "$id:m"); put("text", "Reply $n") })
                add(ev("turn_end", id, ts = n * 1000L) { put("outcome", "ok") })
            } else {
                add(ev("message_started", id, ts = n * 1000L) { put("blockId", "$id:m") })
            }
        }
    }

    private val base: JsObj by lazy { events().fold(freshTree()) { acc, e -> reduce(acc, e.tree()) } }

    private fun delta(tree: JsObj, text: String): JsObj =
        reduce(tree, ev("tool_output_delta", "t$turns", ts = turns * 1000L) { put("toolId", "t$turns:tool2"); put("chunk", text) }.tree())

    @Test fun collapsedRunsKeepTheRowCountFarBelowTheBlockCount() {
        val projection = adapter.adapt(base)!!
        assertEquals(5000, projection.turnsById.values.sumOf { it.blocks.size })
        val items = buildChatItems(projection, base, showThinking = false, ChatFixtures.zone, richCodex = true)
        assertEquals(items.size, items.map { it.key }.toSet().size)
        // 999 finished turns: prompt + one collapsed group + reply; the last: prompt + an open (running) group of 3.
        assertEquals(999 * 3 + 1 + 1 + 3, items.size)
        // Every finished row keeps its instance across a delta.
        val next = delta(base, "more\n")
        val after = buildChatItems(adapter.adapt(next)!!, next, showThinking = false, ChatFixtures.zone, richCodex = true)
        val changed = items.indices.filter { items[it] != after[it] }
        assertEquals(listOf("t$turns/t$turns:tool2"), changed.map { items[it].key })
    }

    @Test fun aLiveOutputDeltaRecomposesOnlyTheStreamingCard() {
        var state by mutableStateOf(base to adapter.adapt(base)!!)
        val composed = mutableListOf<String>()
        val listState = LazyListState()
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                CompositionLocalProvider(LocalChatRowObserver provides { key: String -> composed += key }) {
                    ChatTranscript(
                        projection = state.second,
                        tree = state.first,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        onApproval = { _, _, _ -> },
                        onAnswer = { _, _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        showTimeline = false,
                        richCodex = true,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("only a screenful is composed: ${composed.toSet().size}", composed.toSet().size < 40)
        repeat(5) { n ->
            composed.clear()
            rule.runOnIdle {
                val next = delta(state.first, "chunk $n\n")
                state = next to adapter.adapt(next)!!
            }
            rule.waitForIdle()
            assertEquals(listOf("t$turns/t$turns:tool2"), composed.distinct())
        }
    }
}
