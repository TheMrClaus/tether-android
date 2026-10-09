package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A3 (zero loss): for every tool block of a fixture per kind, the sheet a tap on its row opens holds exactly the
 * words ToolBlockView draws on its own under the same clamp and width: the sheet body IS the old renderer, not a copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivitySheetParityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun words(node: SemanticsNode, out: MutableList<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.let { out += it.joinToString("") { t -> t.text } }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
        node.children.forEach { words(it, out) }
    }

    /** The "<turnId>/<blockId>" of every tool block, in transcript order. */
    private fun toolKeys(fixture: ChatFixtures.Folded): List<String> = fixture.projection.turnOrder.flatMap { turnId ->
        val turn = fixture.projection.turnsById[turnId]!!
        turn.blocks.filter { turn.blocksById[it]?.kind == "tool" }.map { "$turnId/$it" }
    }

    /** Taps each tool row of [fixture]; the sheet's words (less its title and Close) equal the card drawn alone. */
    private fun parity(fixture: ChatFixtures.Folded, richCodex: Boolean = false, richOpencode: Boolean = false, skip: (JsObj) -> Boolean = { false }): Int {
        val flags = ToolRenderFlags(richCodex, richOpencode, showThinking = false)
        var alone by mutableStateOf<JsObj?>(null)
        val toggles = allGroupsOpen(fixture, richCodex)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 520.dp) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                    Column {
                        Box(Modifier.height(260.dp)) {
                            ChatTranscript(
                                projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                                zone = ChatFixtures.zone, groupToggles = toggles, showTimeline = false, richCodex = richCodex, richOpencode = richOpencode,
                            )
                        }
                        Box(Modifier.fillMaxWidth().testTag("alone").verticalScroll(rememberScrollState())) {
                            CompositionLocalProvider(LocalFullWidthCards provides true, LocalToolClamp provides 320.dp) {
                                alone?.let { ToolBlockView(it, flags) }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        var compared = 0
        for (key in toolKeys(fixture)) {
            val target = activityTarget(fixture.projection, fixture.tree, key) as ActivityTarget.Tool
            if (skip(target.raw)) continue
            val model = activityRowModel(target.raw)
            rule.openRow(model.label)
            val sheet = mutableListOf<String>()
            words(rule.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode(), sheet)
            // Close the sheet, then draw the same block alone.
            rule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
            rule.waitForIdle()
            rule.runOnIdle { alone = target.raw }
            rule.waitForIdle()
            val card = mutableListOf<String>()
            words(rule.onNodeWithTag("alone", useUnmergedTree = true).fetchSemanticsNode(), card)
            sheet.remove(model.verb)
            sheet.remove("Close")
            assertTrue("${model.label}: the sheet is not empty", sheet.isNotEmpty())
            assertEquals("${model.label}: the sheet body is the card's words", card.sorted(), sheet.sorted())
            compared++
        }
        return compared
    }

    @Test fun everyClaudeToolKindOpensTheSameWordsAsItsCard() {
        assertEquals(11, parity(ToolFixtures.tools))
    }

    @Test fun everyCodexKindOpensTheSameWordsAsItsRichCard() {
        assertEquals(6, parity(ToolFixtures.codexTools, richCodex = true))
    }

    @Test fun aRunningCodexCommandOpensItsCard() {
        assertEquals(1, parity(ActivityFixtures.runningCommand, richCodex = true))
    }

    @Test fun opencodeTasksOpenTheirCards() {
        assertEquals(2, parity(ToolFixtures.opencodeTask, richOpencode = true))
    }

    @Test fun anInterruptedCallOpensItsEvidenceDisclosure() {
        assertEquals(2, parity(ToolFixtures.corpusFinal("tool-lifecycle-progress")))
    }

    @Test fun theRowsBoardsErrorAndInterruptedCallsOpenTheirCards() {
        assertEquals(6, parity(ActivityFixtures.rows))
    }
}
