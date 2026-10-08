package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-jyj0: a phone turned sideways swaps PhoneShell for ExpandedShell (and back), each composing its own
 * ChatScreen. The browser keeps the reader's place across a resize, and a reader following the bottom stays
 * there; a chat opened after another never inherits that other chat's place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellTranscriptScrollTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var restorer: StateRestorationTester

    private var width by mutableIntStateOf(PORTRAIT)

    /** The screen's height: a phone turned sideways is wide AND short (the transcript pane is ~100dp tall). */
    private var height by mutableIntStateOf(1000)

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(width, height)
    }

    private fun session(id: String) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "active", startedAt = 1, updatedAt = 1, historyId = "h-$id")

    /** A tall row (the device's cards are far taller than the landscape pane): a marker line, then filler. */
    private fun reply(prefix: String, n: Int) = "$prefix reply #$n#" + (1..12).joinToString("") { "\n\nfiller line $it of $prefix $n" }

    private fun conversation(prefix: String, turns: Int): JsObj {
        val events = (1..turns).flatMap { n ->
            val turn = "$prefix$n"
            listOf(
                ev("turn_started", turn, ts = n * 1_000L) { put("idempotencyKey", "k-$turn") },
                ev("user_message_accepted", turn, ts = n * 1_000L) { put("text", "$prefix prompt $n") },
                ev("message_started", turn, ts = n * 1_000L) { put("blockId", "$turn:m0") },
                ev("message_delta", turn, ts = n * 1_000L) { put("blockId", "$turn:m0"); put("text", reply(prefix, n)) },
                ev("message_completed", turn, ts = n * 1_000L) { put("blockId", "$turn:m0"); put("text", reply(prefix, n)) },
                ev("turn_end", turn, ts = n * 1_000L) { put("outcome", "ok") },
            )
        }
        return foldTree(freshTree(), *events.toTypedArray())
    }

    private lateinit var vm: TetherViewModel

    private fun host(aTree: JsObj = conversation("a", 60)) {
        val client = ShellConsentClient()
        client.show(session("a"), aTree)
        client.show(session("b"), conversation("b", 60))
        vm = TetherViewModel(client)
        vm.selectSession("a")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        restorer = StateRestorationTester(rule)
        restorer.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    Box(Modifier.requiredSize(width.dp, height.dp)) { MainShell(vm, prefs) }
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text.replace(Regex(" reply (\\d+)$"), " reply #$1#"), substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** The lowest-numbered reply of [prefix] on screen: the reader's place. */
    private fun firstShownReply(prefix: String): Int? = (1..60).firstOrNull { shown("$prefix reply #$it#") }

    /** The window changes size; [recreate]: the activity is also recreated (MainActivity handles no configuration change). */
    private fun resize(to: Int, tall: Int = 1000, recreate: Boolean = false) {
        width = to
        height = tall
        if (recreate) restorer.emulateSavedInstanceStateRestore()
        settle()
    }

    @Test fun aReaderScrolledUpKeepsTheirPlaceAcrossTheShellSwitchBothWays() {
        host()
        assertTrue("opens at the latest message", shown("a reply 60"))
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertFalse(shown("a reply 60"))
        val place = checkNotNull(firstShownReply("a"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()

        resize(LANDSCAPE)
        assertEquals("landscape (ExpandedShell) keeps the place", place, firstShownReply("a"))
        assertFalse(shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()

        resize(PORTRAIT)
        assertEquals("portrait (PhoneShell) keeps it too", place, firstShownReply("a"))
        assertFalse(shown("a reply 60"))
    }

    /** ta-twjm: a few turns, then one that ran a shell command (its activity group is collapsed once finished). */
    private fun withATool(): JsObj = foldTree(
        conversation("a", 3),
        ev("turn_started", "tool1", ts = 9_000L) { put("idempotencyKey", "k-tool1") },
        ev("user_message_accepted", "tool1", ts = 9_000L) { put("text", "run it") },
        ev("tool_start", "tool1", ts = 9_000L) { put("toolId", "b"); put("name", "Bash"); put("input", kotlinx.serialization.json.buildJsonObject { put("command", "seq 3") }) },
        ev("tool_end", "tool1", ts = 9_000L) { put("toolId", "b"); put("output", "1\n2\n3") },
        ev("message_started", "tool1", ts = 9_000L) { put("blockId", "tool1:m0") },
        ev("message_completed", "tool1", ts = 9_000L) { put("blockId", "tool1:m0"); put("text", "Done.") },
        ev("turn_end", "tool1", ts = 9_000L) { put("outcome", "ok") },
    )

    private fun group() = rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("tool-activity-group")).let { rule.onNodeWithTag("tool-activity-group") }

    private fun groupIs(state: String) = group().assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, state))

    /** ta-twjm: the browser keeps a `<details>` open across a resize; so does an activity group the reader opened. */
    @Test fun anOpenedActivityGroupStaysOpenAcrossTheShellSwitchAndARecreation() {
        host(withATool())
        groupIs("Collapsed")
        group().performClick()
        settle()
        groupIs("Expanded")

        resize(LANDSCAPE)
        groupIs("Expanded")
        resize(PORTRAIT)
        groupIs("Expanded")
        resize(914, 411, recreate = true)
        groupIs("Expanded")
        resize(412, 914, recreate = true)
        groupIs("Expanded")
        // Closing it is kept the same way.
        group().performClick()
        settle()
        groupIs("Collapsed")
        resize(LANDSCAPE)
        groupIs("Collapsed")
    }

    @Test fun aReaderAtTheBottomStaysAtTheBottomAcrossTheShellSwitch() {
        host()
        assertTrue(shown("a reply 60"))
        resize(LANDSCAPE)
        assertTrue("still at the latest message", shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
        resize(PORTRAIT)
        assertTrue(shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
    }

    @Test fun anotherSessionNeverInheritsThePlaceOfTheOneBeforeIt() {
        host()
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertFalse(shown("a reply 60"))
        resize(LANDSCAPE)
        assertFalse(shown("a reply 60"))

        rule.runOnIdle { vm.selectSession("b") }
        settle()
        assertTrue("B opens at its latest message", shown("b reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()

        // And a shell switch on B still keeps B at the bottom (nothing of A's place was carried).
        resize(PORTRAIT)
        assertTrue(shown("b reply 60"))
    }

    /** The device path (lead's capture): a real phone, 412x914dp upright and 914x411dp sideways, drag-scrolled mid-transcript. */
    @Test fun aReaderScrolledUpOnAPhoneKeepsTheirPlaceThroughTheShortLandscapeShell() {
        width = 412
        height = 914
        host()
        assertTrue(shown("a reply 60"))
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertFalse(shown("a reply 60"))
        val place = checkNotNull(firstShownReply("a"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()

        resize(914, 411, recreate = true)
        assertFalse("landscape did not jump to the bottom", shown("a reply 60"))
        assertEquals("landscape keeps the place", place, firstShownReply("a"))

        resize(412, 914, recreate = true)
        assertFalse("portrait did not jump to the bottom", shown("a reply 60"))
        assertEquals("and back", place, firstShownReply("a"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()
    }

    @Test fun aReaderAtTheBottomStaysAtTheBottomWhenTheActivityIsRecreated() {
        width = 412
        height = 914
        host()
        assertTrue(shown("a reply 60"))
        resize(914, 411, recreate = true)
        assertTrue("still at the latest message", shown("a reply 60"))
        resize(412, 914, recreate = true)
        assertTrue(shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
    }

    private companion object {
        const val PORTRAIT = 412
        const val LANDSCAPE = 1200
    }
}
