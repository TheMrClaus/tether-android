package com.tether.app.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.components.screenshots.choiceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** T13.2: the freshness copy rules, and that every mark's words are its TalkBack label. */
@RunWith(RobolectricTestRunner::class)
class FreshnessMarksTest {
    @get:Rule val rule = createComposeRule()

    private val now = 10_000_000_000L
    private fun ago(min: Long) = now - min * 60_000

    @Test
    fun ages() {
        assertEquals("just now", FreshnessCopy.age(now - 30_000, now))
        assertEquals("12 min ago", FreshnessCopy.age(ago(12), now))
        assertEquals("3 hr ago", FreshnessCopy.age(ago(180), now))
        assertEquals("1 day ago", FreshnessCopy.age(ago(24 * 60), now))
        assertEquals("4 days ago", FreshnessCopy.age(ago(4 * 24 * 60), now))
        assertEquals("a clock that went back reads as now", "just now", FreshnessCopy.age(now + 60_000, now))
        assertNull(FreshnessCopy.age(null, now))
        assertEquals("12m", FreshnessCopy.shortAge(ago(12), now))
        assertEquals("3h", FreshnessCopy.shortAge(ago(180), now))
        assertEquals("2d", FreshnessCopy.shortAge(ago(2 * 24 * 60), now))
    }

    @Test
    fun liveIsUnmarkedAndEveryOtherStateHasWords() {
        assertNull(FreshnessCopy.sessionLabel(SessionSync(Freshness.Live, ago(1)), now))
        assertNull(FreshnessCopy.sessionLabel(null, now))
        assertEquals("Catching up…", FreshnessCopy.sessionLabel(SessionSync(Freshness.CatchingUp, null), now))
        assertEquals("Saved copy · updated 12 min ago", FreshnessCopy.sessionLabel(SessionSync(Freshness.Saved, ago(12)), now))
        assertEquals("Saved copy", FreshnessCopy.sessionLabel(SessionSync(Freshness.Saved, null), now))
        assertEquals("Not downloaded. Connect to load", FreshnessCopy.sessionLabel(SessionSync(Freshness.NotDownloaded, null), now))
    }

    @Test
    fun aSavedCopyNeverClaimsTheAgentIsBusyOrWaitingNow() {
        assertEquals("Was running · 12 min ago", FreshnessCopy.qualifiedStatus("active", ago(12), now))
        assertEquals("Was waiting on you · 12 min ago", FreshnessCopy.qualifiedStatus("waiting", ago(12), now))
        assertEquals("Was running", FreshnessCopy.qualifiedStatus("active", null, now))
        assertNull(FreshnessCopy.qualifiedStatus("ready", ago(12), now))
        // Not live: the qualified words on a faint, still dot (no spinner, no violet ping).
        assertEquals("Was waiting on you · 12 min ago" to StatusTone.History, FreshnessCopy.statusPill("waiting", false, ago(12), now))
        assertEquals("Was running · 12 min ago" to StatusTone.History, FreshnessCopy.statusPill("active", false, ago(12), now))
        // Live list: the ordinary words and tones.
        assertEquals("Needs you" to StatusTone.Waiting, FreshnessCopy.statusPill("waiting", true, ago(12), now))
        assertEquals("Active" to StatusTone.Active, FreshnessCopy.statusPill("active", true, ago(12), now))
        assertEquals("Ready" to StatusTone.Ready, FreshnessCopy.statusPill("ready", false, ago(12), now))
    }

    @Test
    fun talkBackReadsTheWordsOfEveryMark() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                androidx.compose.foundation.layout.Column {
                    FreshnessChip(SessionSync(Freshness.Saved, ago(12)), now)
                    FreshnessChip(SessionSync(Freshness.CatchingUp, null), now)
                    FreshnessChip(SessionSync(Freshness.Live, ago(1)), now)
                    FreshnessGlyph(SessionSync(Freshness.NotDownloaded, null), now)
                    ConnectionBanner(LinkBanner.Offline)
                }
            }
        }
        rule.onNodeWithContentDescription("Saved copy · updated 12 min ago").assertExists()
        rule.onNodeWithContentDescription("Catching up…").assertExists()
        rule.onNodeWithContentDescription("Not downloaded. Connect to load").assertExists()
        rule.onNodeWithContentDescription("Offline. Showing saved copies").assertExists()
        // Live draws nothing: exactly four marks.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(4)
    }
}
