package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.util.LocalRecompositionProbe
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-jtfq: a streamed delta that only appends text to the agent's message changes what the transcript shows and
 * nothing else, so nothing outside the transcript recomposes: not the shell, not the drawer or the sidebar in it, not
 * the composer. The statusline and the inspector read the tree but show nothing of a message's words, so they hold their reading too.
 */
abstract class StreamingRecompositionBase(private val widthPx: Int, private val shell: String) {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(widthPx, 1000)
    }

    private val adapter = LegacyProjectionAdapter()
    private val counts = HashMap<String, Int>()
    private val composed = HashSet<String>()

    private fun conversation(): JsObj {
        val events = buildList {
            for (n in 1..6) {
                val turn = "t$n"
                add(ev("turn_started", turn, ts = n * 1_000L) { put("idempotencyKey", "k-$turn") })
                add(ev("user_message_accepted", turn, ts = n * 1_000L) { put("text", "Prompt $n") })
                add(ev("message_started", turn, ts = n * 1_000L) { put("blockId", "$turn:m0") })
                add(ev("message_delta", turn, ts = n * 1_000L) { put("blockId", "$turn:m0"); put("text", "Reply $n") })
                add(ev("message_completed", turn, ts = n * 1_000L) { put("blockId", "$turn:m0"); put("text", "Reply $n") })
                if (n < 6) add(ev("turn_end", turn, ts = n * 1_000L) { put("outcome", "ok") })
            }
            // The last turn is still streaming.
            add(ev("message_started", "t6", ts = 6_000L) { put("blockId", "t6:m1") })
            add(ev("message_delta", "t6", ts = 6_000L) { put("blockId", "t6:m1"); put("text", "Streaming") })
        }
        return foldTree(freshTree(), *events.toTypedArray())
    }

    private fun session() =
        AgentSession(id = "s1", provider = "claude", name = "s1", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1, historyId = "h-s1")

    /** The stored preferences load on another thread: let those late recompositions finish before counting. */
    private fun settleQuietly() {
        var quiet = 0
        var rounds = 0
        while (quiet < 3 && rounds++ < 60) {
            counts.clear()
            Thread.sleep(50)
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            if (counts.isEmpty()) quiet++ else quiet = 0
        }
        assertTrue("the shell settled", quiet >= 3)
    }

    private val client = ShellConsentClient()
    private var tree = conversation()

    private fun host() {
        client.show(session(), tree)
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(
                    LocalWindowInfo provides window,
                    LocalRecompositionProbe provides { name -> composed += name; counts[name] = (counts[name] ?: 0) + 1 },
                ) { MainShell(vm, prefs) }
            }
        }
        settleQuietly()
    }

    private fun publish(type: String, block: String? = null, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        rule.runOnIdle {
            tree = reduce(tree, ev(type, "t6", ts = 6_000L) { if (block != null) put("blockId", block); extra() }.tree())
            // The store publishes the tree, then the projection adapted from it.
            client.projectionTrees.value = client.projectionTrees.value + ("s1" to tree)
            client.projections.value = client.projections.value + ("s1" to adapter.adapt(tree)!!)
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    @Test
    fun theComposerFollowsTheTurnOnceItEnds() {
        host()
        counts.clear()
        publish("message_completed", "t6:m1") { put("text", "Streaming done") }
        publish("turn_end") { put("outcome", "ok") }
        assertTrue("the composer took the turn's end: $counts", (counts["Composer"] ?: 0) >= 1)
    }

    @Test
    fun aPlainDeltaRecomposesNothingOutsideTheTranscript() {
        host()
        val watched = listOf("MainShellBody", shell, "SessionDrawer", "SessionSidebar", "Composer")
        for (name in watched) assertTrue("$name is composed", composed.contains(name))
        // The tablet draws the inspector in its column, so there it is counted for real (the statusline draws only with the metrics this session lacks).
        if (shell == "ExpandedShell") assertTrue("InspectorHost is composed", composed.contains("InspectorHost"))

        counts.clear()
        // Control: the same time passing with no delta recomposes nothing either, so a count below is the deltas'.
        repeat(3) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }
        assertTrue("idle time alone recomposed: $counts", counts.isEmpty())
        val perDelta = mutableListOf<String>()
        repeat(6) { n ->
            publish("message_delta", "t6:m1") { put("text", " delta$n") }
            perDelta += "delta $n: ${HashMap(counts)}"
        }
        // The transcript's rows are derived off the main thread: give the last one its time to arrive.
        var shown = false
        for (i in 0 until 100) {
            shown = rule.onAllNodes(hasText("delta5", substring = true)).fetchSemanticsNodes().isNotEmpty()
            if (shown) break
            Thread.sleep(50)
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
        }
        assertTrue("the stream shows", shown)
        // The statusline and the inspector read the tree and show nothing of a message's words: quiet too.
        val recomposed = (watched + listOf("SessionStatusline", "InspectorHost")).associateWith { counts[it] ?: 0 }.filterValues { it > 0 }
        assertTrue("6 plain deltas recomposed: $recomposed; $perDelta", recomposed.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
class MainShellStreamingRecompositionPhoneTest : StreamingRecompositionBase(412, "PhoneShell")

@RunWith(RobolectricTestRunner::class)
// 1700 dp: wide enough for the inspector column.
@Config(qualifiers = "w1700dp-h1000dp-mdpi")
class MainShellStreamingRecompositionTabletTest : StreamingRecompositionBase(1700, "ExpandedShell")
