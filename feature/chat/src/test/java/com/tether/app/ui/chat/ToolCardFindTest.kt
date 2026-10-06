package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
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
 * T6.2 × T5.3: tool cards are never find targets — chat-view.tsx:522 `countFindOccurrences`
 * counts only user and assistant messages — and the in-chat find still works over a transcript
 * full of them. The seeded tool-cards session says "config" once in the prompt and a dozen times
 * inside tool inputs and outputs; "lint" once in the reply and once in a Bash command.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolCardFindTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onlyMessagesCountNotToolInputsOrOutputs() {
        val projection = ToolFixtures.tools.projection
        val config = findResults(projection, "config", open = true)
        assertEquals(listOf("t1:u0"), config.hits.map { "${it.turnId}:${projection.turnsById[it.turnId]!!.blocksById[it.blockId]!!.kind.let { k -> if (k == "user_message") "u" else k }}${it.ordinal}" })
        val lint = findResults(projection, "lint", open = true)
        assertEquals(1, lint.hits.size)
        assertEquals("message", projection.turnsById["t1"]!!.blocksById[lint.hits.single().blockId]!!.kind)
        // No tool block is ever painted.
        for (turn in projection.turnsById.values) for ((id, block) in turn.blocksById) {
            if (block.kind == "tool") assertEquals(0, countFindOccurrences(block, "config"))
            if (block.kind == "tool") assertTrue(findBlockKey(turn.turnId, id) !in config.blocksWithHits)
        }
    }

    @Test fun theFindBarCountsAndStepsOverATranscriptWithToolCards() {
        val client = ChatTestClient()
        val session = chatSession("s1", historyId = "hist-1")
        client.show(session, ToolFixtures.tools)
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) { TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            } }
        }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatFindTags.Input).performTextInput("config")
        rule.waitForIdle()
        fun count() = rule.onNodeWithTag(ChatFindTags.Count).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals("1 of 1 · turn 1 of 1", count())
        rule.onNodeWithTag(ChatFindTags.Input).performTextReplacement("lint")
        rule.waitForIdle()
        assertEquals("1 of 1 · turn 1 of 1", count())
        rule.onNodeWithTag(ChatFindTags.Next).performClick()
        rule.waitForIdle()
        assertEquals("1 of 1 · turn 1 of 1", count())
        // The run of tool cards is still there, collapsed, next to the marks.
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("tool-activity-group"))
        rule.onNodeWithTag("tool-activity-group").assertExists()
    }
}
