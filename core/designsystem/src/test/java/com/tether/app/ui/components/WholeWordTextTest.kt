package com.tether.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-z4c1 Z1: the session name's whole-word 2-line clamp (globals.css 10917-10923), at the 412 drawer's
 * 184.81 dp copy column less the dot, and at the rail's 144.81 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class WholeWordTextTest {
    @get:Rule val rule = createComposeRule()

    private fun layouts(): List<TextLayoutResult> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node ->
                val out = ArrayList<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
                out[0]
            }

    /** Every displayed line, ellipsis included only as the cut: the visible text of each line. */
    private fun lines(): List<String> = layouts().flatMap { r ->
        val text = r.layoutInput.text.text
        (0 until r.lineCount).map { text.substring(r.getLineStart(it), r.getLineEnd(it, visibleEnd = true)).trimEnd() }
    }

    private fun render(text: String, widthDp: Float = 144.81f) {
        rule.setContent {
            TetherTheme {
                val style = TextStyle(fontFamily = LocalTetherTypography.current.ui, fontSize = 12.48.sp, fontWeight = FontWeight(600))
                Box(Modifier.width(widthDp.dp)) { WholeWordText(text, style, Color.Black) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun aLongNameTakesTwoLinesWithTheSecondEllipsized() {
        val name = "Summarize the README in one line, then open the release pull request"
        render(name)
        val ls = layouts()
        assertEquals("the ordinary Text path draws one node", 1, ls.size)
        assertEquals(2, ls[0].lineCount)
        assertTrue("line 2 is clamped with an ellipsis", ls[0].isLineEllipsized(1))
        val boundaries = lineBoundaries(name)
        assertTrue("line 1 ends at a break opportunity", ls[0].getLineEnd(0) in boundaries)
        assertEquals("Summarize the", lines()[0].take(13))
    }

    @Test fun anOverlongSegmentKeepsItsOwnLineAndIsNeverSplit() {
        render("Supercalifragilisticexpialidocious-refactor-branch")
        val ls = layouts()
        assertEquals("one node per line on the whole-word path", 2, ls.size)
        assertTrue("line 1 is the first segment, cut at the edge with an ellipsis", ls[0].isLineEllipsized(0))
        assertEquals(1, ls[0].lineCount)
        assertEquals("Supercalifragilisticexpialidocious-", ls[0].layoutInput.text.text)
        assertEquals("refactor-branch", ls[1].layoutInput.text.text)
        assertTrue(!ls[1].isLineEllipsized(0))
    }

    @Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
    @Test fun aShortNameWrapsAtItsSpaceAtTwiceTheText() {
        render("Video fixture 2")
        assertEquals(listOf("Video", "fixture 2"), lines())
    }

    @Test fun theGreedyPlanBreaksOnlyAtOpportunities() {
        // 1 unit per character: a pure check of the plan, no fonts.
        val width = { s: String -> s.length.toFloat() }
        assertEquals(listOf("aaaa bbbb", "cccc"), wholeWordLines("aaaa bbbb cccc", 2, 9f, width))
        assertEquals(listOf("abcdefghij-", "klm nop"), wholeWordLines("abcdefghij-klm nop", 2, 6f, width))
        assertEquals(listOf("aa", "bb cc dd"), wholeWordLines("aa bb cc dd", 2, 2f, width))
        assertEquals(listOf("one"), wholeWordLines("one", 2, 9f, width))
    }
}
