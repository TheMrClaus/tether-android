package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.3 end to end on the chat screen: the in-chat find bar (chat-view.tsx 2013-2120, 3258-3304) —
 * Ctrl+F, a global-search result's request (only for ITS conversation, once per nonce), the count
 * in words, Enter / Shift+Enter / Escape and the keys, the jump that centres the active match, the
 * reset on a session switch, and a rotation that keeps it all.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatFindBehaviourTest {
    @get:Rule val rule = createComposeRule()

    /** 24 turns; "alpha" only in turn 1 (prompt + reply) and turn 20's reply: 3 occurrences in 2 turns. */
    private val long = ChatFixtures.fold(
        *(1..24).flatMap { n ->
            val prompt = if (n == 1) "Prompt alpha $n" else "Prompt $n"
            val reply = when (n) {
                1 -> "Reply with **alpha** in it"
                20 -> "Late alpha reply"
                else -> "Reply $n\n\nwith a second paragraph so the transcript is long"
            }
            ChatFixtures.turn("t$n", prompt, reply, ChatFixtures.T_IDLE + n * 60_000L).toList()
        }.toTypedArray(),
    )

    private fun host(vm: TetherViewModel, client: ChatTestClient, restoration: StateRestorationTester? = null, initial: AgentSession) {
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        var shown by mutableStateOf(initial)
        current = { shown = it }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            androidx.compose.runtime.CompositionLocalProvider(LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) { TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            } }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
        rule.waitForIdle()
    }

    private var current: (AgentSession) -> Unit = {}

    private fun setUp(restoration: StateRestorationTester? = null): Pair<TetherViewModel, ChatTestClient> {
        val client = ChatTestClient()
        val session = chatSession("s1", historyId = "hist-1")
        client.show(session, long)
        val vm = TetherViewModel(client)
        host(vm, client, restoration, session)
        return vm to client
    }

    private fun count() = rule.onNodeWithTag(ChatFindTags.Count)

    private fun countText(): String =
        count().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun ctrlFOpensTheBarFocusedAndEnterStepsThroughTheMatches() {
        setUp()
        assertTrue(rule.onAllNodes(hasText("Find in conversation")).fetchSemanticsNodes().isEmpty())
        // A hardware keyboard: the composer has focus, Ctrl+F opens the bar with the caret in it.
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatFindTags.Input).assertIsFocused()
        assertEquals("", countText())
        rule.onNodeWithTag(ChatFindTags.Previous).assertIsNotEnabled()

        rule.onNodeWithTag(ChatFindTags.Input).performTextInput("Alpha")
        rule.waitForIdle()
        assertEquals("1 of 3 · turn 1 of 2", countText())
        rule.onNodeWithTag(ChatFindTags.Next).assertIsEnabled()
        rule.onNodeWithTag(ChatFindTags.Input).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("2 of 3 · turn 1 of 2", countText())
        rule.onNodeWithTag(ChatFindTags.Input).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.onNodeWithTag(ChatFindTags.Input).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertEquals("wraps backwards", "3 of 3 · turn 2 of 2", countText())
        rule.onNodeWithTag(ChatFindTags.Next).performClick()
        rule.waitForIdle()
        assertEquals("wraps forwards", "1 of 3 · turn 1 of 2", countText())

        // Typing restarts at the first match; no match says so in words.
        rule.onNodeWithTag(ChatFindTags.Next).performClick()
        rule.onNodeWithTag(ChatFindTags.Input).performTextReplacement("zzz-none")
        rule.waitForIdle()
        assertEquals("No matches", countText())
        rule.onNodeWithTag(ChatFindTags.Next).assertIsNotEnabled()
        rule.onNodeWithTag(ChatFindTags.Input).performTextReplacement("late alpha")
        rule.waitForIdle()
        assertEquals("1 of 1 · turn 1 of 1", countText())

        // Escape closes it (and the marks go with it).
        rule.onNodeWithTag(ChatFindTags.Input).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChatFindTags.Bar)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aGlobalSearchResultOpensTheBarAndCentresTheFirstMatch() {
        val (vm, _) = setUp()
        // The transcript follows the newest turn: turn 1 is far above the fold.
        assertTrue(rule.onAllNodes(hasText("Prompt alpha 1")).fetchSemanticsNodes().isEmpty())

        // Another conversation's request does nothing here.
        vm.requestFind("alpha", "hist-other")
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChatFindTags.Bar)).fetchSemanticsNodes().isEmpty())

        vm.requestFind("alpha", "hist-1")
        rule.waitForIdle()
        rule.onNodeWithTag(ChatFindTags.Input).assertTextEquals("alpha")
        assertEquals("1 of 3 · turn 1 of 2", countText())
        // The first occurrence (turn 1's prompt, the very first row) is on screen: as high as
        // the list can go, since nothing above it can be scrolled to the middle.
        rule.onNodeWithText("Prompt alpha 1").assertIsDisplayed()
        val well = bounds("chat-transcript")

        // Next jumps to turn 20's reply, centred too.
        rule.onNodeWithTag(ChatFindTags.Next).performClick()
        rule.onNodeWithTag(ChatFindTags.Next).performClick()
        rule.waitForIdle()
        assertEquals("3 of 3 · turn 2 of 2", countText())
        val late = rule.onNodeWithText("Late alpha reply").fetchSemanticsNode().boundsInRoot
        assertTrue("centred: $late in $well", kotlin.math.abs(late.center.y - well.center.y) < well.height / 4)

        // Closed, the same request is not applied again; a new one (a new nonce) is.
        rule.onNodeWithTag(ChatFindTags.Close).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChatFindTags.Bar)).fetchSemanticsNodes().isEmpty())
        vm.requestFind("alpha", "hist-1")
        rule.waitForIdle()
        assertEquals("1 of 3 · turn 1 of 2", countText())
    }

    @Test fun anotherSessionStartsWithTheBarClosed() {
        val (vm, client) = setUp()
        vm.requestFind("alpha", "hist-1")
        rule.waitForIdle()
        rule.onNodeWithTag(ChatFindTags.Bar).assertIsDisplayed()
        val other = chatSession("s2", historyId = "hist-2")
        client.show(other, ChatFixtures.idle)
        rule.runOnIdle { current(other) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChatFindTags.Bar)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aRotationKeepsTheBarItsQueryAndTheActiveMatch() {
        val restoration = StateRestorationTester(rule)
        val (vm, _) = setUp(restoration)
        vm.requestFind("alpha", "hist-1")
        rule.waitForIdle()
        rule.onNodeWithTag(ChatFindTags.Next).performClick()
        rule.waitForIdle()
        assertEquals("2 of 3 · turn 1 of 2", countText())
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(ChatFindTags.Input).assertTextEquals("alpha")
        assertEquals("2 of 3 · turn 1 of 2", countText())
    }

    @Test fun anActiveMatchInAClampedFenceRevealsIt() {
        val code = (1..40).joinToString("\n") { if (it == 38) "needle_line()" else "line_$it()" }
        val fence = ChatFixtures.fold(*ChatFixtures.turn("t1", "show the code", "```\n$code\n```", ChatFixtures.T_IDLE))
        val client = ChatTestClient()
        val session = chatSession("s1", historyId = "hist-1")
        client.show(session, fence)
        val vm = TetherViewModel(client)
        host(vm, client, null, session)
        // The fence is clamped: its toggle offers the hidden lines.
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Show more")).fetchSemanticsNodes().isNotEmpty())
        vm.requestFind("needle_line", "hist-1")
        rule.waitForIdle()
        assertEquals("1 of 1 · turn 1 of 1", countText())
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Show less")).assertExists()
    }
}
