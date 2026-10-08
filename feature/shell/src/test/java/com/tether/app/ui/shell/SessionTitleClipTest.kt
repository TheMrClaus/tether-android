package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val titleStyle = TextStyle(fontSize = 17.92.sp)

private class TitleProbe(val widthOfAEllipsis: Float, val node: SemanticsNode) {
    val layout: TextLayoutResult
        get() {
            val out = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
            return out.single()
        }
    val drawn: String get() = layout.layoutInput.text.text

    /** The heading's text: the node's own (a box wide enough for the ellipsis) or its parent's (a clipped box). */
    val headingText: String
        get() = generateSequence(node) { it.parent }
            .first { it.config.contains(SemanticsProperties.Heading) }
            .config[SemanticsProperties.Text].joinToString("") { it.text }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.title(name: String, widthDp: Float, style: TextStyle = titleStyle): TitleProbe {
    var wA = 0f
    setContent {
        TetherTheme(choiceFor(TetherSkin.StudioDark)) {
            val m = rememberTextMeasurer()
            wA = m.measure("A…", style, softWrap = false, maxLines = 1).size.width.toFloat() // mdpi: px = dp
            Box(Modifier.width(widthDp.dp)) { SessionTitle(name, Color.White, style) }
        }
    }
    waitForIdle()
    return TitleProbe(wA, onNodeWithTag(HeaderTitleTextTag, useUnmergedTree = true).fetchSemanticsNode())
}

/**
 * ta-m5sy (W21): the title below the width of "A…" keeps its first character, as the web does (CSS Overflow 3; the web at
 * tether 29537e0, 914 x 411 Needs you: "A" and the start of "…" in 19.75 px). dp = px (mdpi). The heading's text stays the
 * full name at every width.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h400dp-mdpi")
class SessionTitleClipTest(private val width: Float) {
    @get:Rule val rule = createComposeRule()

    @Test fun theFirstLetterNeverDisappearsAndTheHeadingKeepsTheFullName() {
        val name = "Approval fixture"
        val p = rule.title(name, width)
        println("W21-RECORD title w=$width wA=${p.widthOfAEllipsis} drawn='${p.drawn}'")
        assertEquals("heading text", name, p.headingText)
        if (width > 0f && width < p.widthOfAEllipsis) {
            assertEquals("a box under width(\"A…\") draws the first letter and a clipped ellipsis", "A…", p.drawn)
        } else if (width >= p.widthOfAEllipsis) {
            assertTrue("a wider box keeps the ordinary end ellipsis", p.layout.isLineEllipsized(0))
            assertTrue("and at least the first letter before it", p.layout.getLineEnd(0, visibleEnd = true) >= 1)
            assertEquals("the plain text is the whole name", name, p.drawn)
        }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0}") fun params() = listOf(0f, 8f, 19.75f, 24.9f, 30f, 60f).map { arrayOf<Any>(it) }
    }
}

/** The first grapheme cluster, not the first code point, and the start edge of a right-to-left name. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h400dp-mdpi")
class SessionTitleGraphemeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aDecomposedEKeepsItsAccent() {
        val name = "étude du depot"
        val p = rule.title(name, 12f)
        assertEquals("é…", p.drawn)
        assertEquals(name, p.headingText)
    }

    @Test fun aZwjEmojiIsOneLetter() {
        val family = "👨‍👩‍👧"
        val name = "$family family plan"
        val p = rule.title(name, 12f)
        assertEquals("$family…", p.drawn)
        assertEquals(name, p.headingText)
    }

    @Test fun anRtlNameKeepsItsFirstLogicalLetter() {
        val name = "שלום עולם"
        val p = rule.title(name, 12f, titleStyle.copy(textDirection = TextDirection.Rtl))
        assertEquals("ש…", p.drawn)
        assertEquals(name, p.headingText)
    }

    @Test fun aNameThatFitsIsNeverClipped() {
        val p = rule.title("A", 12f)
        assertEquals("A", p.drawn)
    }
}
