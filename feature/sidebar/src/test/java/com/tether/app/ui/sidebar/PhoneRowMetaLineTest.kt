package com.tether.app.ui.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithText
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-1jj7 (owner-directed design): a phone row's second line keeps the status words and the time, and the
 * location shares that line whenever 72 dp or more remains beside them (else it takes a line of its own).
 * The location is never dropped: it is in the row's label too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneRowMetaLineTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun show(state: SidebarState) {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state) }
        rule.waitForIdle()
    }

    @Test fun statusWordsAndTheTimeAreStillOnTheRow() {
        show(F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1"))
        assertTrue("Active", rule.onAllNodes(androidx.compose.ui.test.hasText("Active", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("Needs you", rule.onAllNodes(androidx.compose.ui.test.hasText("Needs you", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("time", rule.onAllNodes(androidx.compose.ui.test.hasText("· 1m", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aShortLocationSharesTheStatusLine() {
        show(F.state(listOf(F.live("m1", "Short one", cwd = "${F.ROOT}/repo", ago = 2))))
        val time = rule.textNode("Ready · 2m").boundsInRoot
        val location = rule.textNode("~/repo").boundsInRoot
        assertTrue("the location is on the status line: $location vs $time", location.top < time.bottom && location.bottom > time.top)
        assertTrue("and after it: $location vs $time", location.left > time.right)
    }

    @Test fun aSixtyCharacterLocationSharesTheStatusLineAndIsCut() {
        show(F.state(listOf(F.live("m2", "Worktree row", cwd = F.LONG_LOCATION, ago = 2))))
        val time = rule.textNode("Ready · 2m").boundsInRoot
        val layouts = rule.textLayoutsOf("~" + F.LONG_LOCATION.removePrefix(F.ROOT))
        assertEquals("one location text", 1, layouts.size)
        val l = layouts.single()
        assertEquals(1, l.lineCount)
        assertTrue("cut with an ellipsis", l.isLineEllipsized(0))
        val location = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("the location") { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.startsWith("~/repo/.worktrees") == true }, useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("on the status line: $location vs $time", location.top < time.bottom && location.bottom > time.top)
    }

    @Test fun theLocationIsNeverAbsentFromTheLabel() {
        show(F.state(listOf(F.live("m3", "Worktree row", cwd = F.LONG_LOCATION, ago = 2))))
        val label = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("the row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Worktree row,") == true }).fetchSemanticsNodes().single()
        assertTrue(label.description(), label.description()!!.contains("~/repo/.worktrees/reconnect-backoff-for-the-flapping-link-regression"))
    }

    // The rule itself, on fixed pieces: a 100 dp status cluster and a location, in rows of three widths.
    private fun measure(width: Int): Pair<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect> {
        rule.setContent {
            Box(Modifier.width(width.dp)) {
                PhoneMetaLine(
                    status = { Box(Modifier.size(100.dp, 14.dp).testTag("status")) },
                    location = { Text("~/repo/a/long/path/that/never/fits/the/line/at/all", maxLines = 1, modifier = Modifier.testTag("location")) },
                )
            }
        }
        rule.waitForIdle()
        val s = rule.onNodeWithTag("status").getBoundsInRoot()
        val l = rule.onNodeWithTag("location").getBoundsInRoot()
        val d = rule.density.density
        return androidx.compose.ui.geometry.Rect(s.left.value * d, s.top.value * d, s.right.value * d, s.bottom.value * d) to
            androidx.compose.ui.geometry.Rect(l.left.value * d, l.top.value * d, l.right.value * d, l.bottom.value * d)
    }

    @Test fun theLocationBesideTheStatusKeepsAtLeast72dp() {
        // 180 dp: 100 leaves 80 (>= 72): beside. ta-7ox8 B1: no gap (the location carries the same " · " the age has).
        val (status, location) = measure(180)
        assertTrue("beside: $status $location", location.left >= status.right && location.top < status.bottom)
        assertEquals("no gap before the location's own separator", status.right, location.left, 0.5f)
        assertTrue("at least 72dp: ${location.width}", location.width + 1f >= 72 * rule.density.density)
    }

    @Test fun lessThan72dpRemainingPutsTheLocationOnItsOwnLine() {
        // 170 dp: 100 leaves 70 (< 72): below, from the line's start.
        val (status, location) = measure(170)
        assertTrue("below: $status $location", location.top >= status.bottom)
        assertEquals("from the start", status.left, location.left, 0.5f)
        assertFalse("not squeezed", location.width < 72 * rule.density.density)
    }

    // ta-j0e8: the web's relativeTimeAgo on the three " ago" sites (history row, its TalkBack text, the "Ended" row).
    private fun histRow(ago: Long) = F.state(emptyList(), histories = mapOf(F.ROOT to listOf(F.history("hx", "Old chat", cwd = F.ROOT, ago = ago))))

    private fun rowLabel(prefix: String) = rule.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher("the row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith(prefix) == true },
    ).fetchSemanticsNodes().first().description()!!

    private fun visible(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun aHistoryRowSecondsOldReadsJustNowNeverNowAgo() {
        show(histRow(0))
        assertTrue(visible("just now"))
        assertFalse(visible("now ago"))
        assertTrue(rowLabel("Old chat").contains(", just now"))
        assertFalse(rowLabel("Old chat").contains("now ago"))
    }

    @Test fun aHistoryRowMinutesOldReadsNMAgo() {
        show(histRow(5))
        assertTrue(visible("5m ago"))
        assertTrue(rowLabel("Old chat").contains(", 5m ago"))
    }

    @Test fun aHistoryRowAWeekOldReadsABareDate() {
        val ago = 8 * 24 * 60L + 12 * 60 // 8.5 days: the date is the same in every zone within +-11h
        val date = com.tether.app.protocol.helpers.Format.relativeTime((F.NOW - ago * 60_000).toDouble(), F.NOW.toDouble())
        show(histRow(ago))
        assertTrue(date, visible(date))
        assertFalse(visible("$date ago"))
        assertTrue(rowLabel("Old chat").endsWith(", $date") || rowLabel("Old chat").contains(", $date"))
        assertFalse(rowLabel("Old chat").contains("$date ago"))
    }

    @Test fun anArchivedRowSaysEndedJustNow() {
        rule.setContent {
            SidebarUnderTest(TetherSkin.StudioDark, F.state(F.groupSessions + F.live("fresh", "Just ended", cwd = F.APP, ago = 0, archived = true), pinned = listOf(F.DOCS, F.APP), current = F.APP, collapsed = listOf(F.DOCS, F.APP)), TetherLayoutClass.Phone, SidebarUiSeed(archivedOpen = true))
        }
        rule.waitForIdle()
        assertTrue(visible("Ended just now"))
        assertFalse(visible("Ended now ago"))
        assertTrue(visible("Ended 10h ago"))
    }
}
