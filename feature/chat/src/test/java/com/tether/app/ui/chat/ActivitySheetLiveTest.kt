package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A4: the open sheet is LIVE. It reads its block from the projection on every change, so streaming output and the
 * end of the call update it in place; an activity group that closes as its run ends does not close it; a block that leaves
 * the projection closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivitySheetLiveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun inSheet(text: String) = hasText(text, substring = true) and hasAnyAncestor(isDialog())

    private fun dialogs() = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size

    @Test fun theOpenSheetFollowsTheCallToItsEndAndClosesWhenTheBlockIsGone() {
        var fixture by mutableStateOf(ActivityFixtures.runningCommand)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                    ChatTranscript(
                        projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone, showTimeline = false, richCodex = true,
                    )
                }
            }
        }
        rule.waitForIdle()
        // A running call holds its group open: its row is there to tap.
        rule.openRow("Shell npm test, running")
        assertEquals(1, dialogs())
        rule.onNode(inSheet("ok 1 - backs off"), useUnmergedTree = true).assertExists()
        rule.onNode(hasContentDescription("running") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()

        // More output, then the end: the open sheet shows both.
        rule.runOnIdle { fixture = ActivityFixtures.finishCommand(fixture) }
        rule.waitForIdle()
        rule.onNode(inSheet("ok 2 - gives up"), useUnmergedTree = true).assertExists()
        rule.onNode(hasContentDescription("completed") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        // The run ended, so its group closed and the row left the list; the sheet stayed.
        rule.onAllNodes(rowLabel("Shell npm test")).assertCountEquals(0)
        assertEquals(1, dialogs())

        // The block leaves the projection: the sheet closes.
        rule.runOnIdle { fixture = ChatFixtures.idle }
        rule.waitForIdle()
        assertEquals(0, dialogs())
    }
}
