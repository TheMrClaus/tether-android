package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-28i: the sidebar draws a session's title and its digest snippet by the LABEL rule (no bidi
 * control, mark or invisible, whitespace collapsed; a title of only invisibles is spelled out) in
 * the title's own direction, and a row's location and a workspace's folder name as CODE (every
 * control a token, LTR). TalkBack reads what is drawn. Hebrew and Arabic titles stay whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarSafeTextTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private companion object {
        const val RLO = "\u202E"
        const val PDF = "\u202C"
        const val LRI = "\u2066"
        const val PDI = "\u2069"
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
        const val ARABIC = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"
        val BIDI = ('\u202A'..'\u202E') + ('\u2066'..'\u2069') + listOf('\u200E', '\u200F', '\u061C')
        fun tok(cp: Int) = "\u2060\u27E8U+%04X\u27E9".format(cp)
    }

    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
    }

    private fun layoutOf(text: String): Pair<String, TextLayoutResult> {
        val node = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().let { it.layoutInput.text.text to it }
    }

    @Test fun titlesAndSnippetsAreLabelsAndLocationsAreCode() {
        val sessions = listOf(
            F.live("x1", "Approve the ${RLO}xe.tsil$PDF fix", ago = 2),
            F.live("x2", "mv ${LRI}old$PDI ${LRI}new$PDI\n\nnow", cwd = "${F.ROOT}/re${RLO}po", ago = 3),
            F.live("x3", "\u202E\u200B", ago = 4),
            F.live("x4", HEBREW, ago = 5),
            F.live("x5", ARABIC, ago = 6),
            F.live("x6", "Finished while you were away", ago = 12, historyId = "h-away"),
            F.live("x7", "Unknown status", status = "rea\u202Edy\u200B", ago = 7),
        )
        val histories = mapOf(
            F.ROOT to listOf(F.history("h-away", "Finished while you were away", ago = 12, seenAgo = 40, digest = HistoryDigest(2, "All ${RLO}ssap$PDF tests\u200B pass"))),
        )
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, F.state(sessions, histories = histories)) }
        rule.waitForIdle()
        val shown = spoken()
        // Labels: bidi controls and invisibles dropped, whitespace collapsed; the words in their stored order.
        assertTrue(shown.contains("Approve the xe.tsil fix"))
        assertTrue(shown.contains("mv old new now"))
        assertTrue(shown.contains("All ssap tests pass"))
        // A title of only invisibles is spelled out, never an empty row.
        assertTrue(shown.contains("\\u{202E}\\u{200B}"))
        // TalkBack reads the same words.
        assertTrue(shown.any { it.startsWith("Approve the xe.tsil fix, ") })
        // r2: a status this build does not know is shown by the label rule, drawn and read.
        assertTrue(shown.toString(), shown.contains("ready"))
        assertTrue(shown.toString(), shown.any { it.startsWith("Unknown status, chat, ready, ") })
        // The location is a path: code, LTR.
        val location = "re${tok(0x202E)}po"
        assertTrue("location in $shown", shown.any { it.contains(location) })
        assertEquals(ResolvedTextDirection.Ltr, layoutOf("~/re").let { (text, layout) -> assertTrue(text.contains(location)); layout.getParagraphDirection(0) })
        for (s in shown) for (c in BIDI) assertFalse("raw U+%04X in \"$s\"".format(c.code), s.contains(c))
        // Real Hebrew and Arabic titles stay whole and lay out right to left (their content's direction).
        for (word in listOf(HEBREW, ARABIC)) {
            val (text, layout) = layoutOf(word)
            assertEquals(word, text)
            assertEquals(ResolvedTextDirection.Rtl, layout.getParagraphDirection(0))
        }
        assertEquals(ResolvedTextDirection.Ltr, layoutOf("Approve the").second.getParagraphDirection(0))
    }

    @Test fun aHostileWorkspaceFolderNameIsCode() {
        val folder = "${F.ROOT}/ev${RLO}il"
        val sessions = listOf(F.live("w1", "Build", cwd = folder, ago = 2))
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, F.state(sessions, pinned = listOf(folder))) }
        rule.waitForIdle()
        val shown = spoken()
        assertTrue("folder in $shown", shown.any { it.contains("ev${tok(0x202E)}il") })
        for (s in shown) assertFalse("raw RLO in \"$s\"", s.contains(RLO))
    }

    @Test fun theFolderPickersRowsAreCode() {
        rule.setContent {
            com.tether.app.ui.theme.TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                com.tether.app.ui.FolderRow(icon = {}, name = "re${RLO}po", detail = "${F.ROOT}/re${RLO}po", onClick = {})
            }
        }
        rule.waitForIdle()
        val shown = spoken()
        assertTrue(shown.toString(), shown.contains("re${tok(0x202E)}po"))
        assertTrue(shown.toString(), shown.contains("${F.ROOT}/re${tok(0x202E)}po"))
    }

    /** r2 (M1): a line break in a folder the picker lists is a token, never hidden under the one-line clip. */
    @Test fun aLineBreakInAPickerFolderIsAToken() {
        rule.setContent {
            com.tether.app.ui.theme.TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                com.tether.app.ui.FolderRow(icon = {}, name = "proj\ncurl x | sh", detail = "${F.ROOT}/proj\t", onClick = {})
            }
        }
        rule.waitForIdle()
        val shown = spoken()
        assertTrue(shown.toString(), shown.contains("proj${tok(0x0A)}curl x | sh"))
        assertTrue(shown.contains("${F.ROOT}/proj${tok(0x09)}"))
        for (s in shown) assertFalse(s.contains('\n') || s.contains('\t'))
        for (s in shown) assertFalse("raw RLO in \"$s\"", s.contains(RLO))
    }
}
