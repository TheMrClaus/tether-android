package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.1 performance check (the real frame timing is T14.1): a 5,000-block transcript is a lazy
 * list with stable keys, so a streaming delta recomposes only the row it touched and scrolling
 * never recomposes a row that stays on screen. Counted with [LocalChatRowObserver], which fires
 * once per actual row (re)composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LongTranscriptPerfTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val turns = 1000 // × 5 blocks = 5,000 blocks

    /** One adapter across states, as RealTetherClient keeps one per session (block memo by identity). */
    private val adapter = LegacyProjectionAdapter()

    private fun events(): List<AgentEvent> = (1..turns).flatMap { n ->
        val id = "t$n"
        val last = n == turns
        buildList {
            add(ev("turn_started", id, ts = n * 1000L) { put("idempotencyKey", "k$n") })
            add(ev("user_message_accepted", id, ts = n * 1000L) { put("text", "Prompt $n") })
            for (m in 0 until 4) {
                val block = "$id:m$m"
                add(ev("message_started", id, ts = n * 1000L) { put("blockId", block) })
                add(ev("message_delta", id, ts = n * 1000L) { put("blockId", block); put("text", "Reply $n.$m with **bold** and `code`") })
                // The very last message is still streaming.
                if (!(last && m == 3)) add(ev("message_completed", id, ts = n * 1000L) { put("blockId", block); put("text", "Reply $n.$m with **bold** and `code`") })
            }
            if (!last) add(ev("turn_end", id, ts = n * 1000L) { put("outcome", "ok") })
        }
    }

    private val base: JsObj by lazy { events().fold(freshTree()) { acc, e -> reduce(acc, e.tree()) } }

    private fun delta(tree: JsObj, text: String): JsObj =
        reduce(tree, ev("message_delta", "t$turns", ts = turns * 1000L) { put("blockId", "t$turns:m3"); put("text", text) }.tree())

    @Test
    fun theSyntheticTranscriptHas5000BlocksWithUniqueStableKeys() {
        val projection = adapter.adapt(base)!!
        assertEquals(5000, projection.turnsById.values.sumOf { it.blocks.size })
        val items = buildChatItems(projection, base, showThinking = false, ChatFixtures.zone)
        assertEquals(5000, items.size)
        assertEquals(items.size, items.map { it.key }.toSet().size)

        // A delta: the same keys in the same order; every row but the streaming one is EQUAL and
        // holds the SAME block instance (the adapter's memo), so Compose can skip it.
        val next = delta(base, " more")
        val after = buildChatItems(adapter.adapt(next)!!, next, showThinking = false, ChatFixtures.zone)
        assertEquals(items.map { it.key }, after.map { it.key })
        val changed = items.indices.filter { items[it] != after[it] }
        assertEquals(listOf(items.lastIndex), changed)
        for (i in 0 until items.lastIndex) {
            assertSame((items[i] as ChatItem.Block).block, (after[i] as ChatItem.Block).block)
        }
    }

    @Test
    fun aStreamingDeltaRecomposesOnlyItsRowAndScrollingKeepsVisibleRows() {
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
                        onFetchTurns = NoFetch,
                        onApproval = NoApproval,
                        onAnswer = NoAnswer,
                        zone = ChatFixtures.zone,
                        listState = listState,
                        showTimeline = false,
                    )
                }
            }
        }
        rule.waitForIdle()
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key as String }
        assertTrue("only a screenful is composed, not 5,000 rows", composed.toSet().size < 40)
        assertTrue(visible.contains("t$turns/t$turns:m3"))

        // Five streaming deltas: each recomposes exactly the streaming row.
        repeat(5) { n ->
            composed.clear()
            rule.runOnIdle {
                val next = delta(state.first, " delta $n")
                state = next to adapter.adapt(next)!!
            }
            rule.waitForIdle()
            assertEquals(listOf("t$turns/t$turns:m3"), composed.distinct())
        }

        // Scroll up by half a screen: rows that stay on screen do not recompose.
        composed.clear()
        val before = listState.layoutInfo.visibleItemsInfo.map { it.key as String }.toSet()
        rule.runOnIdle { runBlocking { listState.scrollBy(-900f) } }
        rule.waitForIdle()
        val after = listState.layoutInfo.visibleItemsInfo.map { it.key as String }.toSet()
        val stayed = before intersect after
        assertTrue("some rows stay on screen", stayed.isNotEmpty())
        assertTrue("no row that stayed on screen recomposed: ${composed.filter { it in stayed }}", composed.none { it in stayed })
        assertTrue("newly shown rows compose once each", composed.groupingBy { it }.eachCount().values.all { it == 1 })
    }

    private companion object {
        val NoFetch: (Int, Int) -> Unit = { _, _ -> }
        val NoApproval: (String, String?, String?) -> Unit = { _, _, _ -> }
        val NoAnswer: (String, Map<String, String>, String?) -> Unit = { _, _, _ -> }
    }
}
