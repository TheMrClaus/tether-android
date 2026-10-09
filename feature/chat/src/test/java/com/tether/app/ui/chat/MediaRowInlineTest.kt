package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-a5jl A10: a tool that returned a picture draws its row AND the picture's tile in the transcript, before any tap. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class MediaRowInlineTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun theTileSitsUnderItsRowWithNoSheetOpen() {
        val loader = ToolFixtures.FakeLoader()
        rule.showTranscript(ActivityFixtures.rows, showThinking = true, loader = loader, groupsOpen = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(rowLabel("Read /w/p/chart.png, done"))
        rule.onNode(rowLabel("Read /w/p/chart.png, done")).assertExists()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("View image full size"))
        rule.onNodeWithContentDescription("View image full size").assertExists()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        // The tile is the transcript's own (it loaded through the loader), not a sheet's.
        rule.onAllNodesWithContentDescription("View image full size").assertCountEquals(1)
    }

    /** ta-0jtb: the picture sits bare under its row: the gap is css.spaceSm (8 dp) and nothing (no 1 px rule, no chrome padding) is added to it. */
    @Test fun theTileSitsBareUnderItsRowAtTheSmallGap() {
        rule.showTranscript(ActivityFixtures.rows, showThinking = true, groupsOpen = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(rowLabel("Read /w/p/chart.png, done"))
        val row = rule.onNode(rowLabel("Read /w/p/chart.png, done")).fetchSemanticsNode().boundsInRoot
        val media = rule.onNodeWithTag("tool-media").fetchSemanticsNode().boundsInRoot
        val gap = 8f * rule.density.density
        assertEquals("gap between the row and its picture, px", gap, media.top - row.bottom, 0.5f)
    }
}
