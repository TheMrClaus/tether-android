package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.BreakIterator
import java.util.Locale

/**
 * ta-z4c1 Z1, the row's wiring: a session name draws through WholeWordText, so a word wider than the
 * copy column keeps a line of its own and no line ends inside a word. A plain `Text(maxLines = 2)` makes a
 * desperate break inside it ("Internationalizat/ionandlo…"), which the web never shows. The row's texts sit
 * under clearAndSetSemantics, so they are read from the unmerged tree. The rail's copy column is 130.4 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SessionRowWholeWordsTest {
    @get:Rule val rule = createComposeRule()

    private val name = "Internationalizationandlocalization of the maintenance scheduler"

    @Test fun anOverlongWordIsNeverSplitInTheRow() {
        val state = SidebarFixtures.state(listOf(SidebarFixtures.live("w1", name, ago = 5)))
        rule.setContent { SidebarUnderTest(TetherSkin.Studio, state, TetherLayoutClass.Expanded, SidebarUiSeed(), SidebarActions(onCollapse = {})) }
        rule.waitForIdle()
        val layouts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { a -> ArrayList<TextLayoutResult>().also { a.invoke(it) }.firstOrNull() } }
            .filter { it.layoutInput.text.text.let { t -> t.isNotBlank() && name.contains(t) } }
        assertTrue("the row's name is drawn: ${layouts.map { it.layoutInput.text.text }}", layouts.isNotEmpty())
        layouts.forEach { l ->
            val text = l.layoutInput.text.text
            val it = BreakIterator.getLineInstance(Locale.ROOT).also { b -> b.setText(text) }
            val boundaries = generateSequence(it.first()) { _ -> it.next().takeIf { n -> n != BreakIterator.DONE } }.toSet()
            (0 until l.lineCount - 1).forEach { i -> assertTrue("'$text': line $i ends at a break opportunity, not inside a word", l.getLineEnd(i) in boundaries) }
        }
        val lines = layouts.flatMap { l -> (0 until l.lineCount).map { l.layoutInput.text.text.substring(l.getLineStart(it), l.getLineEnd(it, visibleEnd = true)).trimEnd() } }
        assertEquals("the overlong word keeps a line of its own", "Internationalizationandlocalization", layouts.first().layoutInput.text.text)
        assertTrue("two lines at most: $lines", lines.size <= 2)
    }
}
