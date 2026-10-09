package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-ktlw: the detail sheet's title names the action. A built-in kind keeps its verb ("Shell"); the generic kind, whose
 * row verb is "Tool" (an unknown tool, an mcp__ name), is titled by the tool's display name. The row verb is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivitySheetTitleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun paneTitles(): List<String> = rule.onAllNodes(SemanticsMatcher("pane") { it.config.contains(SemanticsProperties.PaneTitle) }, useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }

    private fun open(label: String) {
        rule.showTranscript(ToolFixtures.tools, groupsOpen = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(rowLabel(label))
        rule.onNode(rowLabel(label)).performClick()
        rule.waitForIdle()
    }

    @Test fun theGenericKindsSheetIsTitledByTheToolsName() {
        open("Tool mcp__parity__render_chart")
        assertEquals(listOf("mcp__parity__render_chart"), paneTitles())
    }

    @Test fun aBuiltInKindKeepsItsVerb() {
        open("Shell npm test")
        assertEquals(listOf("Shell"), paneTitles())
    }

    @Test fun theMcpKindKeepsItsVerbAndTheRowVerbIsUnchanged() {
        val codex = ToolFixtures.codexTools
        val mcp = activityTarget(codex.projection, codex.tree, "t1/t1:tool2", showThinking = false) as ActivityTarget.Tool
        assertEquals("MCP", activityRowModel(mcp.raw).verb)
        assertEquals("MCP", activitySheetTitle(mcp))
        val tools = ToolFixtures.tools
        val generic = activityTarget(tools.projection, tools.tree, "t1/t1:tool10", showThinking = false) as ActivityTarget.Tool
        assertEquals("Tool", activityRowModel(generic.raw).verb)
        assertEquals("mcp__parity__render_chart", activitySheetTitle(generic))
    }
}
