package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.layout.FirstBaseline
import com.tether.app.protocol.model.UsageWindow
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T9.1 / ta-coik.10: the telemetry panel drawn — hostile text shows its hidden code points, the
 * band order, the run rows and their selection, the disclosures and the reads. On a phone (the
 * telemetry sheet's body); [InspectorTabletUiTest] runs every case again on a tablet, in the
 * inspector column's width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
open class InspectorUiTest {
    @get:Rule val rule = createComposeRule()

    /** The panel's host width: the phone sheet's body (the tablet subclass: the docked column). */
    protected open val hostWidth: Dp = 380.dp

    private fun show(model: InspectorModel, state: SessionView? = null, onSelectRun: (String?) -> Unit = {}, onRequest: (String) -> Unit = {}) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                Column(Modifier.width(hostWidth).verticalScroll(rememberScrollState())) {
                    Inspector(model, state, onSelectRun, fileDiffs = null, onRequestFileDiff = onRequest, env = { InspectorBoards.env })
                }
            }
        }
    }

    @Test
    fun hostileBranchPathAndMcpTextIsDrawnWithVisibleTokens() {
        val rlo = "\u202e"
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
        // The branch (code rule) and the account in the header (code rule).
        rule.onNodeWithText(SafeText.line("main${rlo}x"), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(SafeText.line("a${rlo}@b.test"), substring = true, useUnmergedTree = true).assertExists()
        // The MCP server name (code rule) and, behind "View error", its error (prose rule).
        rule.onNodeWithText(SafeText.line("git${rlo}hub"), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("View error").performScrollTo().performClick()
        rule.onNodeWithText(SafeText.prose("denied${rlo} ok"), substring = true, useUnmergedTree = true).assertExists()
        // Runtime: the path is code.
        rule.onNodeWithText("RUNTIME").performScrollTo().performClick()
        rule.onNodeWithText(SafeText.line("/w/${rlo}p"), substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText(rlo, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aLineBreakOrTabInABranchOrWorktreePathIsDrawnAsATokenOnOneLine() {
        val session = InspectorBoards.session(
            metrics = SessionMetrics(gitBranch = "main\nforged"),
            worktree = WorktreeInfo(path = "/w/a\tb", branch = "b", status = "active"),
        )
        show(InspectorBoards.model(session))
        rule.onNodeWithText(SafeText.line("main\nforged"), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("RUNTIME").performScrollTo().performClick()
        rule.onNodeWithText(SafeText.line("/w/a\tb"), substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("\n", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    /** inspector.tsx:632-1005: the operator's order, top to bottom. */
    @Test
    fun theBandsFollowTheOperatorsOrder() {
        val m = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Attention)
        show(m, InspectorBoards.Reference.attentionState)
        val order = listOf(
            InspectorTags.Header, InspectorTags.Attention, InspectorTags.Context, InspectorTags.Limits,
            InspectorTags.Subagents, "mcp-health", InspectorTags.Tokens, InspectorTags.Repository, InspectorTags.Runtime,
        )
        val tops = order.map { rule.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun theHeaderCarriesModelEffortAccountAndTheTask() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full), InspectorBoards.Reference.state)
        for (text in listOf("MODEL", "claude-opus-5-5", "EFFORT", "Medium", "ACCOUNT", "operator@example.com", "Team 4", "NOW", "Verifying the Android parity slice", "2 of 5 complete")) {
            rule.onAllNodesWithText(text, useUnmergedTree = true)[0].assertExists()
        }
        // The header is above the fold: before the Context band.
        val header = rule.onNodeWithTag(InspectorTags.Header).getUnclippedBoundsInRoot()
        val context = rule.onNodeWithTag(InspectorTags.Context).getUnclippedBoundsInRoot()
        assertTrue(header.bottom <= context.top)
    }

    /** T14.2: the drawn capitals keep the original words as the accessible name (the web's CSS text-transform). */
    @Test
    fun capitalisedLabelsKeepTheirOriginalWordsAsTheAccessibleName() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full), InspectorBoards.Reference.state)
        for (words in listOf("Model", "Effort", "Account", "Now")) {
            rule.onAllNodesWithContentDescription(words, useUnmergedTree = true)[0].assertExists()
        }
        rule.onAllNodesWithText("MODEL", useUnmergedTree = true)[0].assertExists()
    }

    @Test
    fun theAttentionStripAppearsOnlyWhenSomethingIsWrong() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Attention), InspectorBoards.Reference.attentionState)
        rule.onNodeWithText("Approaching the rate limit (five hour) — resets in 38m", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("MCP servers: 1 failed — details under MCP health", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(InspectorTags.RateLimit).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun aHealthySessionHasNoAttentionStrip() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full), InspectorBoards.Reference.state)
        rule.onNodeWithTag(InspectorTags.Attention).assertDoesNotExist()
    }

    /** Status is never colour alone: every gauge prints its number. */
    @Test
    fun everyGaugePrintsItsNumber() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Attention), InspectorBoards.Reference.attentionState)
        for (value in listOf("81%", "93%", "77%")) rule.onNodeWithText(value, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag(InspectorTags.Gauge).assertCountEquals(3)
    }

    @Test
    fun anEmptySessionSaysTelemetryAppearsAfterTheFirstResponseAndOmitsLimits() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Empty))
        rule.onNodeWithText("Telemetry appears after the agent completes its first response.").assertExists()
        rule.onNodeWithText("Waiting").assertExists()
        rule.onNodeWithText("Not reported yet", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(InspectorTags.Limits).assertDoesNotExist()
        rule.onNodeWithTag(InspectorTags.Subagents).assertDoesNotExist()
    }

    @Test
    fun runtimeIsADisclosureClosedByDefaultWithTheCliInItsSummary() {
        show(InspectorBoards.fullModel, InspectorBoards.fullState)
        rule.onNodeWithText("CLI 2.3.1", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Inventory", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("RUNTIME").performScrollTo().performClick()
        rule.onNodeWithText("Inventory", useUnmergedTree = true).assertExists()
        // The inventory names wait behind their own disclosure.
        rule.onNodeWithText("Tools: Bash, Read, Edit", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Names").performScrollTo().performClick()
        rule.onNodeWithText("Tools: Bash, Read, Edit", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theByModelLedgerOpensOnATap() {
        show(InspectorBoards.fullModel, InspectorBoards.fullState)
        rule.onNodeWithText("2 models", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Max output", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("By model, last turn").performScrollTo().performClick()
        rule.onAllNodesWithText("Max output", useUnmergedTree = true).assertCountEquals(2)
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
    fun aRunRowSelectsItsRunAndASelectedRunShowsItsBandAndShowSessionClearsIt() {
        val runs = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full).runs
        var selected by mutableStateOf<String?>(null)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                Column(Modifier.width(hostWidth).verticalScroll(rememberScrollState())) {
                    val model = inspectorModel(
                        InspectorBoards.Reference.session(InspectorBoards.Reference.metrics), InspectorBoards.Reference.providers,
                        InspectorBoards.Reference.state, runs, selected, InspectorReplies(), InspectorBoards.env,
                    )
                    Inspector(model, InspectorBoards.Reference.state, { selected = it }, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
                }
            }
        }
        rule.onNodeWithTag(InspectorTags.RunUsage).assertDoesNotExist()
        rule.onAllNodesWithTag(InspectorTags.RunRow)[1].performScrollTo().performClick()
        assertEquals(runs[1].runId, selected)
        rule.onAllNodesWithTag(InspectorTags.RunRow)[1].assertIsSelected()
        rule.onNodeWithTag(InspectorTags.RunUsage).assertExists()
        rule.onNodeWithTag(InspectorTags.SessionDivider).assertExists()
        rule.onNodeWithText("Session · Live").assertExists()
        rule.onNodeWithTag(InspectorTags.ShowSession).performScrollTo().performClick()
        assertEquals(null, selected)
        rule.onNodeWithTag(InspectorTags.RunUsage).assertDoesNotExist()
    }

    /** inspector.tsx:470-481: six rows, the rest behind "Show N more", open when the selection is there. */
    @Test
    fun theRestOfTheRunsWaitBehindShowMoreUnlessTheSelectionIsThere() {
        val runs = InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full).runs
        var selected by mutableStateOf<String?>(null)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                Column(Modifier.width(hostWidth).verticalScroll(rememberScrollState())) {
                    val model = inspectorModel(
                        InspectorBoards.Reference.session(InspectorBoards.Reference.metrics), InspectorBoards.Reference.providers,
                        InspectorBoards.Reference.state, runs, selected, InspectorReplies(), InspectorBoards.env,
                    )
                    Inspector(model, InspectorBoards.Reference.state, { selected = it }, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
                }
            }
        }
        rule.onAllNodesWithTag(InspectorTags.RunRow).assertCountEquals(6)
        rule.onNodeWithText("Show 4 more").performScrollTo().performClick()
        rule.onAllNodesWithTag(InspectorTags.RunRow).assertCountEquals(10)
        rule.onNodeWithText("Show 4 more").performScrollTo().performClick()
        rule.onAllNodesWithTag(InspectorTags.RunRow).assertCountEquals(6)
        // A run behind the disclosure becomes selected (the transcript's tab strip): it opens.
        selected = runs[8].runId
        rule.onAllNodesWithTag(InspectorTags.RunRow).assertCountEquals(10)
    }

    /** 44dp targets on a coarse pointer: every run row, disclosure summary and the Show session key. */
    @Test
    fun everyControlIsAtLeast44dpTall() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Selected), InspectorBoards.Reference.state)
        val tags = listOf(InspectorTags.RunRow, InspectorTags.RunsMore, InspectorTags.PerModel, InspectorTags.Runtime, InspectorTags.ShowSession, InspectorTags.Changes)
        for (tag in tags) {
            val nodes = rule.onAllNodesWithTag(tag)
            val count = nodes.fetchSemanticsNodes().size
            assertTrue("$tag present", count > 0)
            for (i in 0 until count) {
                val h = nodes[i].getUnclippedBoundsInRoot().let { it.bottom - it.top }
                assertTrue("$tag[$i] is ${h.value}dp", h.value >= 43.5f)
            }
        }
    }

    /**
     * #233 (0b4d91f, inspector.tsx:772-777): an idle Claude session with metrics but no window, no
     * grant summary and no banked credit prints the neutral sentence, never the old "… yet" copy.
     */
    @Test
    fun anIdleClaudeSessionWithNoReadingPrintsTheNeutralLimitsSentence() {
        val idle = InspectorBoards.session(status = "idle", metrics = SessionMetrics(totalTokens = 1_200, model = "claude-opus-5-5"))
        show(InspectorBoards.model(idle))
        rule.onNodeWithTag(InspectorTags.Limits).assertExists()
        rule.onNodeWithText("No current 5-hour or weekly reading for this account.").assertExists()
        rule.onNodeWithText("No 5-hour or weekly window reported for this account yet.").assertDoesNotExist()
        rule.onAllNodesWithTag(InspectorTags.Gauge).assertCountEquals(0)
    }

    /** The positive control: the same idle session with its served windows draws them, and no sentence. */
    @Test
    fun anIdleClaudeSessionWithReadingsDrawsThemAndNoSentence() {
        val idle = InspectorBoards.session(
            status = "idle",
            metrics = SessionMetrics(
                totalTokens = 1_200,
                fiveHour = UsageWindow(12.0, 300, InspectorBoards.NOW + 60 * InspectorBoards.MIN),
                weekly = UsageWindow(40.0, 10_080, null),
            ),
        )
        show(InspectorBoards.model(idle))
        rule.onNodeWithText("No current 5-hour or weekly reading for this account.").assertDoesNotExist()
        rule.onAllNodesWithTag(InspectorTags.Gauge).assertCountEquals(2)
        rule.onNodeWithText("12%", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("40%", useUnmergedTree = true).assertExists()
    }

    /** `.ti-ledger > div { align-items: baseline }`: a number sits on its label's line, not the note's. */
    @Test
    fun aLedgerNumberSitsOnItsLabelsBaseline() {
        show(InspectorBoards.Reference.model(InspectorBoards.Reference.Variant.Full), InspectorBoards.Reference.state)
        fun baseline(text: String): Float {
            val node = rule.onNodeWithText(text, useUnmergedTree = true)
            return node.getUnclippedBoundsInRoot().top.value + node.getAlignmentLinePosition(FirstBaseline).value
        }
        for ((label, value) in listOf("Last turn" to "79.9M", "Processed" to "1.1B", "Fresh input" to "21.4M")) {
            val labelLine = baseline(label)
            val valueNode = rule.onAllNodesWithText(value, useUnmergedTree = true)[0]
            val valueLine = valueNode.getUnclippedBoundsInRoot().top.value + valueNode.getAlignmentLinePosition(FirstBaseline).value
            assertEquals("$label / $value", labelLine, valueLine, 0.6f)
        }
    }

    @Test
    fun theResetActionsAreNotOfferedHere() {
        show(InspectorBoards.fullModel, InspectorBoards.fullState)
        rule.onNodeWithText("1 reset left · expires in 26 d").assertExists()
        rule.onNodeWithText("Use reset").assertDoesNotExist()
    }
}

/** The same cases on a tablet: the expanded layout's docked inspector column (288dp, as the web's 18rem). */
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class InspectorTabletUiTest : InspectorUiTest() {
    override val hostWidth: Dp = 288.dp
}
