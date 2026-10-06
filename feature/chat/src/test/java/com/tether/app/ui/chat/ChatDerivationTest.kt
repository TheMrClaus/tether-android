package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * ta-coik.37: the per-change rebuild (rows, timeline prompts, subagent runs) is off the main thread.
 * The dispatcher is a queue the test drains by hand on a worker thread: no clock, no waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatDerivationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }

        /** Runs everything queued on a fresh worker thread and returns once it is done. */
        fun drainOnWorker() {
            val worker = Thread { while (true) queue.poll()?.run() ?: break }
            worker.start()
            worker.join()
        }
    }

    private class Seen(val kind: String, val thread: Thread)

    private val adapter = LegacyProjectionAdapter()

    private fun session(id: String, reply: String): JsObj {
        val events: List<AgentEvent> = listOf(
            ev("turn_started", "t1", ts = 1000L) { put("idempotencyKey", "k1") },
            ev("user_message_accepted", "t1", ts = 1000L) { put("text", "Prompt of $id") },
            ev("message_started", "t1", ts = 1000L) { put("blockId", "t1:m0") },
            ev("message_delta", "t1", ts = 1000L) { put("blockId", "t1:m0"); put("text", reply) },
        )
        return events.fold(initialSessionState(id, "claude", "/workspace")) { acc, e -> reduce(acc, e.tree()) }
    }

    @Test
    fun theFirstBuildIsOnTheMainThreadAndAStreamingDeltaRebuildsOffIt() {
        val main = Thread.currentThread()
        val dispatcher = QueueDispatcher()
        val seen = ConcurrentLinkedQueue<Seen>()
        val first = session("s1", "Alpha reply")
        var state by mutableStateOf(first to adapter.adapt(first)!!)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(
                    LocalChatDerivationDispatcher provides dispatcher,
                    LocalChatDerivationObserver provides { kind -> seen += Seen(kind, Thread.currentThread()) },
                ) {
                    ChatTranscript(state.second, state.first, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, showTimeline = false)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Alpha reply", substring = true).assertExists()
        assertEquals("the first build is synchronous: nothing waits on the dispatcher", 0, dispatcher.queue.size)
        assertEquals(listOf("rows"), seen.map { it.kind })
        assertTrue("the first build ran on the main thread", seen.single().thread === main)

        // A streaming delta: the main thread builds nothing; the last result shows meanwhile.
        rule.runOnIdle {
            val next = reduce(state.first, ev("message_delta", "t1", ts = 1000L) { put("blockId", "t1:m0"); put("text", " Omega tail") }.tree())
            state = next to adapter.adapt(next)!!
        }
        rule.waitForIdle()
        assertEquals("the delta's rebuild is queued, not run on the main thread", 1, dispatcher.queue.size)
        assertEquals("no rebuild yet", 1, seen.size)
        rule.onNodeWithText("Omega tail", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Alpha reply", substring = true).assertExists()

        dispatcher.drainOnWorker()
        rule.waitForIdle()
        assertEquals(listOf("rows", "rows"), seen.map { it.kind })
        assertNotEquals("the delta's rebuild ran off the main thread", main, seen.last().thread)
        rule.onNodeWithText("Omega tail", substring = true).assertExists()
    }

    @Test
    fun aSessionSwitchBuildsSynchronouslyAndNeverShowsTheOtherSessionsRows() {
        val main = Thread.currentThread()
        val dispatcher = QueueDispatcher()
        val seen = ConcurrentLinkedQueue<Seen>()
        val a = session("sA", "Reply of A")
        val b = session("sB", "Reply of B")
        var state by mutableStateOf(a to adapter.adapt(a)!!)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(
                    LocalChatDerivationDispatcher provides dispatcher,
                    LocalChatDerivationObserver provides { kind -> seen += Seen(kind, Thread.currentThread()) },
                ) {
                    ChatTranscript(state.second, state.first, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, showTimeline = false)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Reply of A", substring = true).assertExists()

        rule.runOnIdle { state = b to adapter.adapt(b)!! }
        rule.waitForIdle()
        assertEquals("the other session is built at once, not queued", 0, dispatcher.queue.size)
        assertTrue(seen.all { it.thread === main })
        rule.onNodeWithText("Reply of B", substring = true).assertExists()
        rule.onNodeWithText("Reply of A", substring = true).assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // ta-nx60: the build loop itself (rememberDerived), with a plain string result
    // ------------------------------------------------------------------

    private class In(val n: Int)

    private class Recorder {
        val computed = ConcurrentLinkedQueue<Int>()
        val published = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
    }

    private fun showDerived(dispatcher: QueueDispatcher, rec: Recorder, input: () -> In, shown: () -> Boolean = { true }, boom: (Int) -> Boolean = { false }) {
        rule.setContent {
            CompositionLocalProvider(
                LocalChatDerivationDispatcher provides dispatcher,
                LocalChatDerivationFailureLog provides { t -> rec.failures += t },
            ) {
                if (shown()) {
                    val inputs = input()
                    val value = rememberDerived<String, Int>(
                        sessionKey = "s",
                        inputs = inputs,
                        capture = { inputs.n },
                        onPublish = { rec.published += it },
                        compute = { n ->
                            rec.computed += n
                            if (boom(n)) throw IllegalStateException("boom $n")
                            "value-$n"
                        },
                    )
                    androidx.compose.foundation.text.BasicText(value)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun drainUntilQuiet(dispatcher: QueueDispatcher) {
        repeat(4) {
            dispatcher.drainOnWorker()
            rule.waitForIdle()
        }
    }

    @Test
    fun aBurstOfChangesBuildsTheLastOneAndNeverTheOnesBetween() {
        val dispatcher = QueueDispatcher()
        val rec = Recorder()
        var input by mutableStateOf(In(0))
        showDerived(dispatcher, rec, { input })
        rule.onNodeWithText("value-0").assertExists()
        for (n in 1..5) {
            rule.runOnIdle { input = In(n) }
            rule.waitForIdle()
        }
        assertEquals("nothing built off the queue yet", listOf(0), rec.computed.toList())
        rule.onNodeWithText("value-0").assertExists()
        drainUntilQuiet(dispatcher)
        rule.onNodeWithText("value-5").assertExists()
        assertEquals("the in-flight build, then the newest: 2..4 were never built", listOf(0, 1, 5), rec.computed.toList())
        assertEquals(listOf("value-0", "value-1", "value-5"), rec.published.toList())
        assertTrue(rec.failures.isEmpty())
    }

    @Test
    fun leavingTheChatCancelsTheBuildInFlightAndPublishesNothingAfter() {
        val dispatcher = QueueDispatcher()
        val rec = Recorder()
        var input by mutableStateOf(In(0))
        var shown by mutableStateOf(true)
        showDerived(dispatcher, rec, { input }, { shown })
        rule.runOnIdle { input = In(1) }
        rule.waitForIdle()
        assertEquals("a rebuild is queued", 1, dispatcher.queue.size)
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        drainUntilQuiet(dispatcher)
        assertEquals("the cancelled build never ran", listOf(0), rec.computed.toList())
        assertEquals("and nothing was published after the dispose", listOf("value-0"), rec.published.toList())
        assertTrue("a cancellation is not a failure", rec.failures.isEmpty())
    }

    @Test
    fun aBuildThatThrowsKeepsTheLastRowsLogsOnceAndTheNextChangeBuildsAgain() {
        val dispatcher = QueueDispatcher()
        val rec = Recorder()
        var input by mutableStateOf(In(0))
        showDerived(dispatcher, rec, { input }, boom = { it == 1 })
        rule.runOnIdle { input = In(1) }
        rule.waitForIdle()
        drainUntilQuiet(dispatcher)
        rule.onNodeWithText("value-0").assertExists()
        assertEquals("tried once, not retried in a loop", listOf(0, 1), rec.computed.toList())
        assertEquals(1, rec.failures.size)
        assertEquals("boom 1", rec.failures.single().message)
        assertEquals("nothing was published for it", listOf("value-0"), rec.published.toList())

        rule.runOnIdle { input = In(2) }
        rule.waitForIdle()
        drainUntilQuiet(dispatcher)
        rule.onNodeWithText("value-2").assertExists()
        assertEquals(listOf(0, 1, 2), rec.computed.toList())
        assertEquals(1, rec.failures.size)
    }
}
