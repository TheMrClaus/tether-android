package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.tether.app.protocol.AgentEvent
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-gvyf: after an activity recreation the saved place is put back by its row key. A key that no longer names a
 * row (trimmed away, a pending-send or failed-send row, an anchor that could not be read) used to make no scroll request
 * and leave a non-following reader at the TOP; it now falls back to following the bottom, as a chat opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TranscriptRestoreFallbackTest {
    @get:Rule val rule = createComposeRule()

    private val folded = ChatFixtures.fold(*(1..40).flatMap { n -> ChatFixtures.turn("t$n", "prompt $n", "reply #$n#", n * 1_000L).toList<AgentEvent>() }.toTypedArray())

    private var scroll by mutableStateOf(TranscriptScroll(sticky = false))

    private fun host() {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                key(scroll) {
                    val restore = remember(scroll) { scroll.takeRestore() }
                    ChatTranscript(
                        projection = folded.projection,
                        tree = folded.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = scroll.listState,
                        follow = scroll.follow,
                        restore = restore,
                        showTimeline = false,
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun atTheBottom(s: TranscriptScroll) = !s.listState.canScrollForward && s.listState.layoutInfo.totalItemsCount > 0

    @Test fun aPlaceThatStillNamesARowIsKeptAndAPlaceThatNamesNoneFollowsTheBottom() {
        // The reader is mid-transcript, not following: a real anchor, taken as the saved state would.
        host()
        rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.listState.scrollToItem(12) } }
        settle()
        val anchor = rule.runOnIdle { scroll.anchor() }
        assertNotNull("a first visible row with a string key", anchor)
        assertFalse(atTheBottom(scroll))

        // POSITIVE CONTROL: recreated with that anchor, the reader is back at that row, still not following.
        val kept = TranscriptScroll(sticky = false, restore = anchor)
        rule.runOnIdle { scroll = kept }
        settle()
        assertEquals("the row is put back", anchor!!.key, kept.anchor()!!.key)
        assertFalse("not at the bottom", atTheBottom(kept))
        assertFalse("still not following", kept.follow.sticky)

        // A place whose row is gone: the reader follows the bottom (no longer parked at the top).
        val gone = TranscriptScroll(sticky = false, restore = TranscriptAnchor("pending-send:gone", 0))
        rule.runOnIdle { scroll = gone }
        settle()
        assertTrue("follows the bottom", gone.follow.sticky)
        assertTrue("and is at the latest message", atTheBottom(gone))
        assertTrue(gone.listState.firstVisibleItemIndex > 0)
    }
}
