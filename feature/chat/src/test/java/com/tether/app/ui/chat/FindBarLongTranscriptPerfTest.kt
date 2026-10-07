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
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-0lv item 2: the find bar OPEN over the 5,000-block transcript (LongTranscriptPerfTest covers
 * the bar closed). The harness is that test's: the same synthetic transcript, a lazy list with
 * stable keys, [LocalChatRowObserver] firing once per actual row (re)composition.
 *
 * Metric: ROW RECOMPOSITIONS per find-bar action, counted from the action to idle, with ~4,000
 * matching blocks live. Threshold: at most [ROW_BUDGET] row compositions per action (a screenful
 * plus the lazy list's prefetch; the closed-bar test bounds the window at 40 distinct rows), and
 * for a jump across the transcript only the window it leaves and the window it lands in compose, nothing between. A find that
 * re-marked or recomposed every matching row would be in the thousands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FindBarLongTranscriptPerfTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val turns = 1000 // × 5 blocks = 5,000 blocks
    private val adapter = LegacyProjectionAdapter()

    private fun events(): List<AgentEvent> = (1..turns).flatMap { n ->
        val id = "t$n"
        buildList {
            add(ev("turn_started", id, ts = n * 1000L) { put("idempotencyKey", "k$n") })
            add(ev("user_message_accepted", id, ts = n * 1000L) { put("text", "Prompt $n") })
            for (m in 0 until 4) {
                val block = "$id:m$m"
                add(ev("message_started", id, ts = n * 1000L) { put("blockId", block) })
                add(ev("message_delta", id, ts = n * 1000L) { put("blockId", block); put("text", "Reply $n.$m with **bold** and `code`") })
                add(ev("message_completed", id, ts = n * 1000L) { put("blockId", block); put("text", "Reply $n.$m with **bold** and `code`") })
            }
            add(ev("turn_end", id, ts = n * 1000L) { put("outcome", "ok") })
        }
    }

    private val base: JsObj by lazy { events().fold(freshTree()) { acc, e -> reduce(acc, e.tree()) } }

    /** What the find bar drives: the typed query and the active match index. */
    private data class Bar(val query: String, val index: Int)

    @Test
    fun typingInAndJumpingThroughTheFindBarRecomposesOnlyTheRowsOnScreen() {
        val projection = adapter.adapt(base)!!
        val counter = FindCounter()
        var bar by mutableStateOf(Bar("", 0))
        val composed = mutableListOf<String>()
        val listState = LazyListState()

        fun find(): TranscriptFind {
            val needle = findNeedle(bar.query)
            val results = findResults(projection, needle, open = true, counter)
            return TranscriptFind(results, needle, results.hits.getOrNull(activeHitIndex(results, bar.index)))
        }

        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalChatRowObserver provides { key: String -> composed += key }) {
                    ChatTranscript(
                        projection = projection,
                        tree = base,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        showTimeline = false,
                        find = find(),
                    )
                }
            }
        }
        rule.waitForIdle()
        val measured = mutableListOf<String>()

        fun action(name: String, change: () -> Unit): List<String> {
            composed.clear()
            rule.runOnIdle { change() }
            rule.waitForIdle()
            val rows = composed.toList()
            measured += "$name: ${rows.size} row compositions, ${rows.toSet().size} distinct"
            assertTrue("$name recomposed ${rows.size} rows (budget $ROW_BUDGET): $measured", rows.size <= ROW_BUDGET)
            return rows
        }

        // Typing: every keystroke re-marks the rows on screen and nothing else.
        for (typed in listOf("re", "rep", "repl", "reply")) action("type '$typed'") { bar = Bar(typed, 0) }
        val total = rule.runOnIdle { find().results.hits.size }
        assertTrue("the needle matches thousands of blocks, so a full re-mark would be visible: $total", total >= 4000)

        // Stepping to the next / previous match, then a jump across the transcript.
        action("next") { bar = Bar("reply", 1) }
        action("previous") { bar = Bar("reply", 0) }
        val landing = action("jump to match 3000") { bar = Bar("reply", 3000) }
        val target = rule.runOnIdle { find().activeHit!! }
        val targetTurn = target.turnId.removePrefix("t").toInt()
        val near = landing.map { it.substringBefore('/').removePrefix("t").toInt() }
        // The window it left (its active mark is dropped) and the window it lands in; nothing between.
        val left = 1
        assertTrue(
            "the jump composed rows outside the two windows: $near",
            near.all { kotlin.math.abs(it - targetTurn) <= JUMP_WINDOW_TURNS || kotlin.math.abs(it - left) <= JUMP_WINDOW_TURNS },
        )
        assertEquals("the jump landed on its match", true, listState.layoutInfo.visibleItemsInfo.any { (it.key as String).startsWith("t$targetTurn/") })
        println("FindBarLongTranscriptPerf " + measured.joinToString("; "))
    }

    private companion object {
        /** A screenful (the closed-bar test's 40-row window) plus prefetch (measured at most 20 per action). */
        const val ROW_BUDGET = 40

        /** Turns either side of the landing turn that the jump may compose (a screenful is a few turns). */
        const val JUMP_WINDOW_TURNS = 12
    }
}
