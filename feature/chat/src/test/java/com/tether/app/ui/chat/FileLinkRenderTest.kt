package com.tether.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-9jnm F2: a link is laid over the text, it never changes it: the characters, the inline-code ranges and the find marks
 * are those of the same message drawn with no opener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileLinkRenderTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var t: TetherTokens
    private lateinit var type: TetherTypography

    private fun theme() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                t = LocalTetherTokens.current
                type = LocalTetherTypography.current
            }
        }
        rule.waitForIdle()
    }

    private fun lines(): List<List<MdInline>> =
        parseMarkdown(FileLinkFixtures.MESSAGE).filterIsInstance<MdBlock.Paragraph>().flatMap { it.lines }

    private fun draw(line: List<MdInline>, linked: Boolean, needle: String? = null): AnnotatedString {
        val files = if (linked) FileLinkDraw(WorkspaceFileLinks(FileLinkFixtures.CWD) {}) else null
        val cursor = needle?.let { FindCursor(it, 0, 0) }
        return inlineAnnotated(line, t, type, 400, {}, cursor, files)
    }

    @Test fun theTextIsTheSameWithOrWithoutLinks() {
        theme()
        var drawnLinks = 0
        for (line in lines()) {
            val plain = draw(line, linked = false)
            val linked = draw(line, linked = true)
            assertEquals(plain.text, linked.text)
            drawnLinks += linked.getLinkAnnotations(0, linked.length).size
            // The inline-code chips are where they were.
            assertEquals(
                plain.getStringAnnotations("md-code", 0, plain.length).map { Triple(it.start, it.end, it.item) },
                linked.getStringAnnotations("md-code", 0, linked.length).map { Triple(it.start, it.end, it.item) },
            )
        }
        // Presence: links were drawn at all (a renderer that drew none would pass the equalities).
        assertEquals(FileLinkFixtures.OPENED.size, drawnLinks)
    }

    @Test fun aLinkCoversTheTokenWithoutItsPunctuation() {
        theme()
        val all = lines().flatMap { line ->
            val s = draw(line, linked = true)
            s.getLinkAnnotations(0, s.length).sortedBy { it.start }.map { s.substring(it.start, it.end) }
        }
        assertEquals(FileLinkFixtures.DRAWN, all)
    }

    @Test fun aFileLinkIsInkedAndUnderlinedAsALinkIs() {
        theme()
        val line = lines().first()
        val s = draw(line, linked = true)
        val first = s.getLinkAnnotations(0, s.length).minBy { it.start }
        val styled = s.spanStyles.filter { it.start <= first.start && it.end >= first.end }
        assertTrue(styled.any { it.item.color == t.violet && it.item.textDecoration == TextDecoration.Underline })
    }

    @Test fun aFindMarkInsideALinkedTokenIsTheSameMarkAndKeepsItsInk() {
        theme()
        val line = lines().first { l -> l.any { it is MdInline.Code && it.text == "src/app/Main.kt" } }
        val plain = draw(line, linked = false, needle = "Main")
        val linked = draw(line, linked = true, needle = "Main")
        assertEquals(plain.text, linked.text)
        fun marks(s: AnnotatedString) = s.getStringAnnotations(FIND_TAG, 0, s.length).map { Triple(it.start, it.end, it.item) }
        assertEquals(1, marks(linked).size)
        assertEquals(marks(plain), marks(linked))
        val (start, end, _) = marks(linked).single()
        // The mark lies inside the link, and its ink is painted after the link's, so it wins.
        val link = linked.getLinkAnnotations(0, linked.length).single { it.start <= start && it.end >= end }
        assertTrue(link.start <= start && link.end >= end)
        val over = linked.spanStyles.filter { it.start <= start && it.end >= end }
        val inkAt = over.indexOfLast { it.item.color == t.css.findMatchInk }
        val violetAt = over.indexOfLast { it.item.color == t.violet }
        assertTrue("find ink after link ink", inkAt > violetAt && violetAt >= 0)
    }

    @Test fun noOpenerDrawsNoLink() {
        theme()
        for (line in lines()) {
            val s = draw(line, linked = false)
            assertEquals(0, s.getLinkAnnotations(0, s.length).size)
            assertFalse(s.spanStyles.any { it.item.color == t.violet })
        }
    }

    @Test fun aFileLinkNodeDrawsAsItsLabelWithNoOpener() {
        theme()
        val line = parseInline("see [the notes](/home/u/n.md)")
        val s = draw(line, linked = false)
        assertEquals("see the notes", s.text)
        assertEquals(0, s.getLinkAnnotations(0, s.length).size)
        val l = draw(line, linked = true)
        assertEquals("see the notes", l.text)
        assertEquals(1, l.getLinkAnnotations(0, l.length).size)
    }
}
