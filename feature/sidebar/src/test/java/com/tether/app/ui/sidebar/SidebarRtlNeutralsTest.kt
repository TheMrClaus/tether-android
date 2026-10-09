package com.tether.app.ui.sidebar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-g8py: the drawer's own English words ("· 8m", "8m ago", "Filter sessions...", "Archive idle sessions...",
 * "· 3 matches") are laid out as LTR paragraphs under an RTL layout, so their leading or trailing neutral stays where
 * the words say ("..Archive", "8m ·" were the artefact). An LTR layout is the same paragraph it always was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarRtlNeutralsTest {
    @get:Rule val rule = createComposeRule()

    /**
     * The direction the Text was given. (getParagraphDirection is no witness: for a Text with no direction it reports the
     * content's, while the glyphs are placed in the layout's, so a trailing "..." or a leading dot went to the wrong end.)
     */
    private fun readsLeftToRight(layout: TextLayoutResult): Boolean = layout.layoutInput.style.textDirection == TextDirection.Ltr

    private fun directionOf(matches: (String) -> Boolean): List<Pair<String, Boolean>> =
        rule.onAllNodes(SemanticsMatcher("text") { it.config.contains(SemanticsActions.GetTextLayoutResult) }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                val layout = results.firstOrNull() ?: return@mapNotNull null
                val text = layout.layoutInput.text.text
                if (matches(text)) text to readsLeftToRight(layout) else null
            }

    private fun drawer(layoutDirection: LayoutDirection) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                SidebarUnderTest(
                    TetherSkin.StudioDark,
                    sidebarState(SidebarShot.Drawer),
                    TetherLayoutClass.Phone,
                    SidebarUiSeed(),
                    SidebarActions(onCollapse = {}),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertLtr(found: List<Pair<String, Boolean>>, what: String) {
        assertTrue("$what not found", found.isNotEmpty())
        for ((text, ltr) in found) assertTrue("\"$text\" is laid out left to right", ltr)
    }

    @Test fun theMetaSeparatorAndTheEllipsizedLabelsAreLtrUnderRtl() {
        drawer(LayoutDirection.Rtl)
        assertLtr(directionOf { it.startsWith("· ") }, "a meta \"· rel\"")
        assertLtr(directionOf { it.startsWith("Filter sessions") }, "the filter placeholder")
        assertLtr(directionOf { it == "Archive idle sessions…" }, "the archive label")
    }

    @Test fun theSameWordsAreLtrInAnLtrLayout() {
        drawer(LayoutDirection.Ltr)
        assertLtr(directionOf { it.startsWith("· ") }, "a meta \"· rel\"")
        assertLtr(directionOf { it == "Archive idle sessions…" }, "the archive label")
    }

    @Test fun theMatchCountIsLtrUnderRtl() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TetherTheme(choiceFor(TetherSkin.StudioDark)) { SnippetLine("updated the README table", matchCount = 4, phone = true) }
            }
        }
        rule.waitForIdle()
        assertLtr(directionOf { it.contains("4 matches") }, "the match count")
    }
}
