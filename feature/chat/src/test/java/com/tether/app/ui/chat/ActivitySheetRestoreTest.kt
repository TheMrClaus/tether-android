package com.tether.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-a5jl A5: an open sheet survives a recreate (a rotation, a process restore): the same sheet is open after it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivitySheetRestoreTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theSameSheetIsOpenAfterARestore() {
        val restoration = StateRestorationTester(rule)
        val fixture = ActivityFixtures.finishedShell
        restoration.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                    ChatTranscript(
                        projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone, showTimeline = false, groupToggles = allGroupsOpen(fixture),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.openRow("Read /w/p/README.md")
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        rule.onNode(hasText("# README", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        rule.onNode(hasText("# README", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
    }
}
