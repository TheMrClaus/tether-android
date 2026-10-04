package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.33: a chat always opens at its latest message, and stays there while it loads, unless the
 * reader scrolls up by hand. The web remounts its ChatView per session (dashboard.tsx
 * `key={activeSession.id}`), so nothing of the chat opened before (its scroll position, its follow
 * mode) carries over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatOpensAtLatestTest {
    @get:Rule val rule = createComposeRule()

    private val a = chatSession("a", historyId = null, name = "A")
    private val b = chatSession("b", historyId = null, name = "B")

    private fun conversation(prefix: String, turns: Int): ChatFixtures.Folded =
        ChatFixtures.fold(*(1..turns).flatMap { n -> ChatFixtures.turn("$prefix$n", "$prefix prompt $n", "$prefix reply $n", n * 1_000L).toList() }.toTypedArray<AgentEvent>())

    private fun shown(text: String): Boolean =
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    @Test fun aChatOpenedAfterReadingUpwardInAnotherOpensAtItsLatestMessage() {
        val client = ChatTestClient()
        client.show(a, conversation("a", 60))
        client.show(b, conversation("b", 60))
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        var open by mutableStateOf<AgentSession>(a)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = open, projection = projections[open.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        settle()
        assertTrue("A opens at its latest message", shown("a reply 60"))

        // The reader scrolls up in A by hand: A stops following.
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertFalse(shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()

        // Opening B: its latest message, following (no "Jump to latest").
        rule.runOnIdle { open = b }
        settle()
        assertTrue("B opens at its latest message", shown("b reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()

        // And back to A: a fresh open, at A's latest too.
        rule.runOnIdle { open = a }
        settle()
        assertTrue("A reopens at its latest message", shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
    }

    @Test fun whileFollowingTheViewStaysPinnedWhenTheLayoutChangesUnderIt() {
        val folded = conversation("c", 40)
        val list = LazyListState()
        var height by mutableStateOf(WellHeightPhone)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = height) {
                ChatTranscript(
                    projection = folded.projection,
                    tree = folded.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = list,
                    showTimeline = false,
                )
            }
        }
        settle()
        assertFalse("opens at the bottom", rule.runOnIdle { list.canScrollForward })

        // The same rows, laid out again in less room (the keyboard, a banner): still at the bottom.
        rule.runOnIdle { height = 300.dp }
        settle()
        assertFalse("still pinned to the bottom", rule.runOnIdle { list.canScrollForward })
        assertTrue(shown("c reply 40"))
    }

    @Test fun aReaderWhoScrolledUpIsNotPulledBackByALayoutChange() {
        val folded = conversation("d", 40)
        val list = LazyListState()
        var height by mutableStateOf(WellHeightPhone)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = height) {
                ChatTranscript(
                    projection = folded.projection,
                    tree = folded.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = list,
                    showTimeline = false,
                )
            }
        }
        settle()
        repeat(2) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        val before = rule.runOnIdle { list.firstVisibleItemIndex }
        rule.runOnIdle { height = 300.dp }
        settle()
        assertTrue("left where the reader is", rule.runOnIdle { list.canScrollForward })
        assertTrue(rule.runOnIdle { list.firstVisibleItemIndex } <= before + 1)
        assertFalse(shown("d reply 40"))
    }
}
