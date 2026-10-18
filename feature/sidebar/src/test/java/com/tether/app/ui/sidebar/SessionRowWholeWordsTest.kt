package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/**
 * ta-z4c1 Z1, the row's wiring, W53: the tablet rail draws the phone list's row, whose name is ONE line with an end ellipsis
 * (never wrapped into a second line, so never split inside a word), and the full name stays in the row's accessible label.
 * The row's texts sit under clearAndSetSemantics, so they are read from the unmerged tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SessionRowWholeWordsTest {
    @get:Rule val rule = createComposeRule()

    private val name = "Internationalizationandlocalization of the maintenance scheduler"

    @Test fun anOverlongNameIsOneLineWithTheFullNameInTheLabel() {
        val state = SidebarFixtures.state(listOf(SidebarFixtures.live("w1", name, ago = 5)))
        rule.setContent { SidebarUnderTest(TetherSkin.Studio, state, TetherLayoutClass.Expanded, SidebarUiSeed(), SidebarActions(onCollapse = {})) }
        rule.waitForIdle()
        val layouts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { a -> ArrayList<TextLayoutResult>().also { a.invoke(it) }.firstOrNull() } }
            .filter { it.layoutInput.text.text == name }
        assertTrue("the row's name is drawn: ${layouts.map { it.layoutInput.text.text }}", layouts.isNotEmpty())
        layouts.forEach { l -> assertEquals("one line, never wrapped", 1, l.lineCount) }
        val rowLabel = rule.onNodeWithTag(SidebarTags.row("live:w1"), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(rowLabel.toString(), rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(name, substring = true), useUnmergedTree = true).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(name) } == true })
    }
}
