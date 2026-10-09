package com.tether.app.ui.sidebar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-1jj7 (owner-directed design): on a window shorter than 480 dp x the font scale the header's lower
 * rows scroll with the list (the New session row stays pinned), so the list keeps a viewport; on a tall
 * window they sit above it.
 */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.showDrawer() {
    setContent { SidebarUnderTest(TetherSkin.StudioDark, SidebarFixtures.state(SidebarFixtures.drawerSessions)) }
    waitForIdle()
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h360dp-xhdpi")
class PhoneDrawerShortHeightTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theNewSessionRowIsPinnedAboveTheList() {
        rule.showDrawer()
        val key = rule.onNodeWithTag(SidebarTags.NewSession).fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag(SidebarTags.List).fetchSemanticsNode().boundsInRoot
        assertTrue("the key $key is above the list $list", key.bottom <= list.top + 0.5f)
    }

    @Test fun theScheduledRowIsInsideTheListAndScrollsAway() {
        rule.showDrawer()
        val list = rule.onNodeWithTag(SidebarTags.List).fetchSemanticsNode().boundsInRoot
        val scheduled = rule.onNodeWithTag(SidebarTags.Scheduled).fetchSemanticsNode().boundsInRoot
        assertTrue("scheduled $scheduled is inside the list $list", scheduled.top >= list.top - 0.5f)
        rule.onNodeWithTag(SidebarTags.Scheduled).assertIsDisplayed()
        rule.onNodeWithTag(SidebarTags.List).performScrollToIndex(6)
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.Scheduled).assertDoesNotExist()
    }

    @Test fun theListHasRoomForSeveralRows() {
        rule.showDrawer()
        val list = rule.onNodeWithTag(SidebarTags.List).fetchSemanticsNode().boundsInRoot
        assertTrue("a list viewport under 3 rows: ${list.height}", list.height >= 3 * 48 * rule.density.density)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneDrawerTallHeightTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theScheduledRowSitsAboveTheList() {
        rule.showDrawer()
        val list = rule.onNodeWithTag(SidebarTags.List).fetchSemanticsNode().boundsInRoot
        val scheduled = rule.onNodeWithTag(SidebarTags.Scheduled).fetchSemanticsNode().boundsInRoot
        assertTrue("scheduled $scheduled is above the list $list", scheduled.bottom <= list.top + 0.5f)
        rule.onNodeWithTag(SidebarTags.List).performScrollToIndex(2)
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.Scheduled).assertIsDisplayed()
    }
}

/** At twice the text a 915 dp window is "short" too (480 x 2.0 = 960): the header scrolls. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class PhoneDrawerShortAtTwiceTheTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theHeaderScrollsAt2x() {
        rule.showDrawer()
        val list = rule.onNodeWithTag(SidebarTags.List).fetchSemanticsNode().boundsInRoot
        val scheduled = rule.onNodeWithTag(SidebarTags.Scheduled).fetchSemanticsNode().boundsInRoot
        assertTrue(scheduled.top >= list.top - 0.5f)
        assertFalse(PhoneDrawer.headerScrolls(915f, 1.0f))
        assertTrue(PhoneDrawer.headerScrolls(915f, 2.0f))
    }
}
