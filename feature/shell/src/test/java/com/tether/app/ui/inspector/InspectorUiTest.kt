package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T9.1: the inspector drawn — hostile text shows its hidden code points, the reads and the run scope. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InspectorUiTest {
    @get:Rule val rule = createComposeRule()

    private fun show(model: InspectorModel, state: SessionView? = null, onSelectRun: (String?) -> Unit = {}, onRequest: (String) -> Unit = {}) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Inspector(model, state, onSelectRun, fileDiffs = null, onRequestFileDiff = onRequest, env = { InspectorBoards.env })
                }
            }
        }
    }

    @Test
    fun hostileBranchPathAndMcpTextIsDrawnWithVisibleTokens() {
        val rlo = "‮"
        val state = SessionView(
            foldTree(
                freshTree(),
                evNullTurn("mcp_health_updated", seq = 1, ts = InspectorBoards.NOW) { put("name", "git${rlo}hub"); put("status", "failed"); put("error", "denied${rlo} ok") },
            ),
        )
        val session = InspectorBoards.session(
            metrics = SessionMetrics(gitBranch = "main${rlo}x", accountEmail = "a${rlo}@b.test"),
            worktree = WorktreeInfo(path = "/w/${rlo}p", branch = "b", status = "active"),
        )
        show(InspectorBoards.model(session, state), state)
        // The branch (code rule).
        rule.onNodeWithText(SafeText.code("main${rlo}x"), substring = true, useUnmergedTree = true).assertExists()
        // The MCP server name (code rule) and, behind "View error", its error (prose rule).
        rule.onNodeWithText(SafeText.code("git${rlo}hub"), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("View error").performScrollTo().performClick()
        rule.onNodeWithText(SafeText.prose("denied${rlo} ok"), substring = true, useUnmergedTree = true).assertExists()
        // Runtime details: the path is code; the account label is cleaned.
        rule.onNodeWithText("Runtime details").performScrollTo().performClick()
        rule.onNodeWithText(SafeText.code("/w/${rlo}p"), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("a@b.test", substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText(rlo, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun runtimeDetailsIsADisclosureClosedByDefault() {
        show(InspectorBoards.fullModel, InspectorBoards.fullState)
        rule.onNodeWithText("CLI", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Runtime details").performScrollTo().performClick()
        rule.onNodeWithText("CLI", useUnmergedTree = true).assertExists()
        // The inventory names wait behind their own disclosure.
        rule.onNodeWithText("Tools: Bash, Read, Edit", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("NAMES").performScrollTo().performClick()
        rule.onNodeWithText("Tools: Bash, Read, Edit", useUnmergedTree = true).assertExists()
    }

    @Test
    fun expandingAChangedFileAsksForItsHunksOnce() {
        val asked = ArrayList<String>()
        show(InspectorBoards.fullModel, InspectorBoards.fullState, onRequest = { asked += it })
        rule.onNodeWithText("Changes", useUnmergedTree = true).performScrollTo().performClick()
        rule.onAllNodesWithText("docs/parity/TRACKER.md", substring = true, useUnmergedTree = true)[0].performScrollTo().performClick()
        assertEquals(listOf("docs/parity/TRACKER.md"), asked)
    }

    @Test
    fun aSelectedRunShowsItsScopeAndShowSessionClearsIt() {
        val run = InspectorBoards.fullModel.runs.single()
        val selections = ArrayList<String?>()
        val model = InspectorBoards.model(InspectorBoards.full, InspectorBoards.fullState, InspectorBoards.fullReplies, selectedRunId = run.runId)
        show(model, InspectorBoards.fullState, onSelectRun = { selections += it })
        rule.onNodeWithTag(InspectorTags.RunUsage).assertExists()
        rule.onNodeWithTag(InspectorTags.SessionDivider).assertExists()
        rule.onNodeWithTag(InspectorTags.ShowSession).performClick()
        assertEquals(listOf<String?>(null), selections)
    }

    @Test
    fun theResetActionsAreNotOfferedHere() {
        show(InspectorBoards.fullModel, InspectorBoards.fullState)
        rule.onNodeWithText("1 reset left · expires in 26 d").assertExists()
        rule.onNodeWithText("Use reset").assertDoesNotExist()
    }
}
