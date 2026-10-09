package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
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
 * ta-jj9k (owner-directed): the "Jump to latest" key is for a reader who is NOT at the bottom. A reader who
 * settles at the end of the transcript (by hand, by a fling, with the keyboard raised) is at the latest message,
 * so the key goes and following resumes, as after a tap on it. (The web never re-engages without a click,
 * chat-view.tsx:1942-1955; this is the owner's ask, not a parity point.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatJumpToLatestTest {
    @get:Rule val rule = createComposeRule()

    private fun conversation(prefix: String, turns: Int, extra: List<AgentEvent> = emptyList()): ChatFixtures.Folded =
        ChatFixtures.fold(
            *((1..turns).flatMap { n -> ChatFixtures.turn("$prefix$n", "$prefix prompt $n", "$prefix reply $n", n * 1_000L).toList() } + extra)
                .toTypedArray<AgentEvent>(),
        )

    private fun settle() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun jumpKeys() = rule.onAllNodesWithContentDescription("Jump to latest").fetchSemanticsNodes().size

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private var folded by mutableStateOf(conversation("a", 40))
    private var find by mutableStateOf<TranscriptFind?>(null)
    private var height by mutableStateOf(WellHeightPhone)
    private val list = LazyListState()

    private fun host() {
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
                    find = find,
                )
            }
        }
        settle()
    }

    private fun readUp() {
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertTrue("scrolled up", rule.runOnIdle { list.canScrollForward })
        assertEquals("the key shows at once", 1, jumpKeys())
    }

    private fun readDownToTheEnd() {
        var swipes = 0
        while (rule.runOnIdle { list.canScrollForward } && swipes++ < 30) {
            rule.onNodeWithTag("chat-transcript").performTouchInput { swipeUp() }
            settle()
        }
        assertFalse("reached the end", rule.runOnIdle { list.canScrollForward })
    }

    @Test fun readingBackDownToTheEndHidesTheKeyAndResumesFollowing() {
        host()
        assertEquals(0, jumpKeys())
        readUp()
        readDownToTheEnd()
        assertEquals("the key is gone at the end", 0, jumpKeys())

        // Following again: a row arriving keeps the view at the end.
        folded = conversation("a", 40, ChatFixtures.turn("a41", "a prompt 41", "a reply 41", 41_000L).toList())
        settle()
        assertFalse("pinned to the new end", rule.runOnIdle { list.canScrollForward })
        assertTrue(shown("a reply 41"))
        assertEquals(0, jumpKeys())
    }

    @Test fun aFlingToTheEndHidesTheKey() {
        host()
        readUp()
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeUp(startY = bottom, endY = top, durationMillis = 60) }
        repeat(20) { settle() }
        if (rule.runOnIdle { list.canScrollForward }) readDownToTheEnd()
        assertEquals(0, jumpKeys())
    }

    @Test fun readingUpAgainAfterTheEndShowsTheKeyAtOnce() {
        host()
        readUp()
        readDownToTheEnd()
        assertEquals(0, jumpKeys())
        readUp()
    }

    @Test fun aShortTranscriptNeverShowsTheKey() {
        folded = conversation("s", 1)
        host()
        assertFalse(rule.runOnIdle { list.canScrollForward || list.canScrollBackward })
        assertEquals(0, jumpKeys())
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        settle()
        assertEquals(0, jumpKeys())
    }

    @Test fun aReaderAtTheBottomWhileDeltasArriveNeverSeesTheKey() {
        val open = listOf<AgentEvent>(
            ev("turn_started", "live", ts = 50_000L) { put("idempotencyKey", "k-live") },
            ev("user_message_accepted", "live", ts = 50_000L) { put("text", "go") },
            ev("message_started", "live", ts = 50_000L) { put("blockId", "live:m0") },
        )
        folded = conversation("a", 40, open)
        host()
        var text = ""
        repeat(12) { n ->
            text += "streamed words number $n arrive here and wrap onto further lines of the reply. "
            folded = conversation("a", 40, open + ev("message_delta", "live", ts = 50_001L + n) { put("blockId", "live:m0"); put("text", text) })
            settle()
            assertEquals("no key at delta $n", 0, jumpKeys())
            assertFalse("pinned at delta $n", rule.runOnIdle { list.canScrollForward })
        }
    }

    @Test fun theKeyboardShrinkingTheWellKeepsAReaderAtTheBottomWithoutTheKey() {
        host()
        height = 300.dp
        settle()
        assertFalse(rule.runOnIdle { list.canScrollForward })
        assertEquals(0, jumpKeys())
        height = WellHeightPhone
        settle()
        assertEquals(0, jumpKeys())
    }

    @Test fun aFindMatchShownAtTheEndDoesNotReEngageFollowing() {
        host()
        // A reader a little above the end: the match is centred by a move that the list's end clamps.
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        settle()
        val results = findResults(folded.projection, "reply 40", open = true)
        assertTrue("a hit", results.hits.isNotEmpty())
        find = TranscriptFind(results, "reply 40", results.hits.first())
        settle()
        repeat(10) { settle() }
        assertFalse("at the end", rule.runOnIdle { list.canScrollForward })
        assertEquals("the match is being shown: the key stays", 1, jumpKeys())
    }
}
