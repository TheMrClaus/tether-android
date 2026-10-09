package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-1jj7 (owner-directed design): a phone row's name is ONE line cut with an end ellipsis, the label
 * still carries the whole name, and the row grows with the text instead of clipping it. ta-z4c1 forbids a
 * line BREAK inside a word; a single line has none, and the rail keeps its two whole-word lines
 * (SessionRowWholeWordsTest).
 */
abstract class PhoneRowOneLineBase {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun show() {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, F.state(F.longNameSessions, histories = F.longNameHistories, activeId = "l3")) }
        rule.waitForIdle()
    }

    @Test fun aLongNameAndAnOverlongWordEachTakeOneEllipsizedLine() {
        show()
        for (full in listOf(F.LONG_NAME, F.LONG_WORD)) {
            val layouts = rule.textLayoutsOf(full)
            assertTrue("'$full' is drawn", layouts.isNotEmpty())
            layouts.forEach { l ->
                assertEquals("'$full' is one line", 1, l.lineCount)
                assertTrue("'$full' ends in an ellipsis", l.isLineEllipsized(0) || l.layoutInput.text.text.endsWith("…"))
            }
        }
    }

    @Test fun theLabelStartsWithTheWholeName() {
        show()
        for (full in listOf(F.LONG_NAME, F.LONG_WORD)) {
            val nodes = rule.onAllNodes(
                SemanticsMatcher("a label starting with the whole name") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("$full,") == true },
            ).fetchSemanticsNodes()
            assertEquals("one row's label begins '$full,'", 1, nodes.size)
        }
    }

    @Test fun aRowGrowsWithItsTextAndNothingRunsPastIt() {
        show()
        val rows = rule.rowNodes()
        assertTrue("rows are drawn: ${rows.size}", rows.size >= 3)
        rows.forEach { row ->
            val box = row.boundsInRoot
            assertTrue("a row is at least 48dp: ${box.height}", box.height + 0.5f >= with(rule.density) { PhoneDrawer.Floor.toPx() })
            row.descendants().forEach { d ->
                val b = d.boundsInRoot
                assertTrue("${d.tag() ?: d.description()} runs past the row's foot: $b in $box", b.bottom <= box.bottom + 0.5f)
                assertTrue("${d.tag() ?: d.description()} runs past the row's end: $b in $box", b.right <= box.right + 0.5f)
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneRowOneLineTest : PhoneRowOneLineBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class PhoneRowOneLineFont13Test : PhoneRowOneLineBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class PhoneRowOneLineFont20Test : PhoneRowOneLineBase()
