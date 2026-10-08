package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * ta-z4c1: at twice the text the sidebar footer's "Private runtime" wraps at its space and never
 * inside a word ("Priva/te/runti/me" before). The row's own texts sit under clearAndSetSemantics, so
 * the rows are pinned by the sidebar-status-font-2.0x goldens.
 */
private fun ComposeContentTestRule.assertFooterWrapsWhole(layout: TetherLayoutClass) {
    setContent { SidebarUnderTest(TetherSkin.Studio, sidebarState(SidebarShot.Status), layout, sidebarSeed(SidebarShot.Status), SidebarActions(onCollapse = {})) }
    waitForIdle()
    val out = ArrayList<TextLayoutResult>()
    onNodeWithContentDescription("Private runtime").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
    val l = out[0]
    val text = l.layoutInput.text.text
    val it = BreakIterator.getLineInstance(Locale.ROOT).also { b -> b.setText(text) }
    val boundaries = generateSequence(it.first()) { _ -> it.next().takeIf { n -> n != BreakIterator.DONE } }.toSet()
    assertEquals("Private runtime", text)
    assertEquals("two lines at twice the text", 2, l.lineCount)
    (0 until l.lineCount - 1).forEach { i -> assertTrue("line $i ends at a space, not inside a word", l.getLineEnd(i) in boundaries) }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 2.0f)
class SidebarWholeWordsRailTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theFooterWrapsAtItsSpace() = rule.assertFooterWrapsWhole(TetherLayoutClass.Expanded)
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class SidebarWholeWordsDrawerTest {
    @get:Rule val rule = createComposeRule()

    /** The drawer is wide enough for one line at 2.0x (the golden): the words are whole either way. */
    @Test fun theFooterNeverSplitsAWord() {
        rule.setContent { SidebarUnderTest(TetherSkin.Studio, sidebarState(SidebarShot.Status), TetherLayoutClass.Phone, sidebarSeed(SidebarShot.Status), SidebarActions(onCollapse = {})) }
        rule.waitForIdle()
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithContentDescription("Private runtime").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        val l = out[0]
        val text = l.layoutInput.text.text
        (0 until l.lineCount).forEach { i ->
            val line = text.substring(l.getLineStart(i), l.getLineEnd(i)).trim()
            assertTrue("'$line' is a whole word or words", line in setOf("Private runtime", "Private", "runtime"))
        }
    }
}
