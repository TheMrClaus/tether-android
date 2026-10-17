package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The open-tree fixture shared with the goldens: head n3 (claude) over n2 (zzz: letter fallback), n1 (codex, with an open delegate nd), n0 (claude); and head h with two open delegates. */
internal object NestedFixture {
    private val F = SidebarFixtures
    val sessions = F.chain(4, "n", ago = 5, names = listOf("Probe the parser", "Plan the importer", "Build the importer", "Ship the importer")).map {
        when (it.id) {
            "n1" -> it.copy(provider = "codex")
            "n2" -> it.copy(provider = "zzz")
            else -> it
        }
    } + F.live("nd", "Index the fixtures", ago = 12, parent = "n1") +
        F.live("h", "Write the guide", ago = 30) +
        F.live("hd1", "Check the links", ago = 31, parent = "h") +
        F.live("hd2", "Proof the intro", ago = 32, parent = "h", provider = "codex")
    val state get() = F.state(sessions, current = F.APP, activeId = "n3")
    val seed = SidebarUiSeed(openChains = setOf("live:n0"), openChildren = setOf("live:n1", "live:h"))
}

/** ta-q2t6 slice 2: the phone's open tree (nested rows lead with the elbow, no handle; connectors are drawn, never read). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NestedTreeTest {
    @get:Rule val rule = createComposeRule()

    private fun show(rtl: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                SidebarUnderTest(TetherSkin.StudioDark, NestedFixture.state, seed = NestedFixture.seed)
            }
        }
        rule.waitForIdle()
    }

    private fun nodes(tag: String) = rule.onAllNodes(SemanticsMatcher("tag $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, useUnmergedTree = true).fetchSemanticsNodes()
    private fun has(tag: String) = nodes(tag).isNotEmpty()

    @Test fun aMemberLeadsWithTheElbowAndHasNoDragHandle() {
        show()
        for (k in listOf("live:n2", "live:n1", "live:n0")) assertFalse("$k handle", has(SidebarTags.handle(k)))
        assertTrue("head keeps its handle", has(SidebarTags.handle("live:n3")))
        assertEquals("one elbow lead per member", 3, nodes(NestedTags.Lead).size)
        for (k in listOf("live:n2", "live:n1", "live:n0")) assertTrue("$k row", has(SidebarTags.row(k)))
    }

    @Test fun theMarkIsOnlyOnAMemberWhoseProviderDiffersFromTheHead() {
        show()
        // n2 (zzz) and n1 (codex) differ from the claude head; n0 (claude) does not.
        assertEquals(2, nodes(NestedTags.Mark).size)
        val marks = nodes(NestedTags.Mark).map { it.boundsInRoot.center.y }.sorted()
        val n0Row = rule.onNodeWithTag(SidebarTags.row("live:n0")).fetchSemanticsNode().boundsInRoot
        assertTrue("no mark inside n0's row", marks.none { it in n0Row.top..n0Row.bottom })
    }

    @Test fun delegateRowsHaveNoHandleAndNoReorderSemantics() {
        show()
        for (k in listOf("live:nd", "live:hd1", "live:hd2")) {
            assertTrue("$k row", has(SidebarTags.row(k)))
            assertFalse("$k handle", has(SidebarTags.handle(k)))
        }
        val all = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        for (k in listOf("live:nd", "live:hd1", "live:hd2")) {
            val row = rule.onNodeWithTag(SidebarTags.row(k)).fetchSemanticsNode()
            val inside = all.filter { row.boundsInRoot.contains(it.boundsInRoot.center) }
            assertTrue("$k no reorder action", inside.none { n -> n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label.startsWith("Move ") } == true })
            assertTrue("$k no reorder text", inside.none { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains("drag") || it.contains("reorder") } == true })
        }
    }

    @Test fun theSpineAndElbowsAreNotInSemantics() {
        show()
        val all = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val words = all.flatMap { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertTrue(words.toString(), words.none { it.contains("spine", true) || it.contains("elbow", true) || it.contains("connector", true) })
    }

    /**
     * ta-hkk7.6 (W44 minor 2, mutation c2 = a `contentDescription` on the connector box): structurally, nothing between a nested row and the block
     * carries semantics, and no node lying fully inside the 6 dp connector band left of the nested wrapper does.
     */
    @Test fun theConnectorBandAndTheWrappersCarryNoSemantics() {
        show()
        fun carries(n: androidx.compose.ui.semantics.SemanticsNode) = n.config.getOrNull(SemanticsProperties.ContentDescription) != null ||
            n.config.getOrNull(SemanticsProperties.Text) != null || n.config.getOrNull(SemanticsProperties.Role) != null ||
            n.config.any { (_, v) -> v is androidx.compose.ui.semantics.AccessibilityAction<*> }
        val nested = listOf("live:n2", "live:n1", "live:n0", "live:nd", "live:hd1", "live:hd2")
        for (k in nested) {
            var p = rule.onNodeWithTag(SidebarTags.row(k)).fetchSemanticsNode().parent
            var reachedBlock = false
            while (p != null) {
                if (p.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sidebar-block:") == true) { reachedBlock = true; break }
                assertFalse("$k: an ancestor between the row and its block carries semantics: ${p.config}", carries(p))
                p = p.parent
            }
            assertTrue("$k reaches its block", reachedBlock)
        }
        val all = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val band = 6 * rule.density.density
        for (k in nested) {
            val row = rule.onNodeWithTag(SidebarTags.row(k)).fetchSemanticsNode().boundsInRoot
            val inBand = all.filter { it.boundsInRoot.left >= row.left - band - 1f && it.boundsInRoot.right <= row.left + 1f && it.boundsInRoot.height > 0f && it.boundsInRoot.top >= row.top - 1f && it.boundsInRoot.bottom <= row.bottom + 1f }
            assertTrue("$k: the connector band holds ${inBand.map { it.config }}", inBand.none { carries(it) })
        }
    }

    @Test fun theElbowLeadMirrorsInRtl() {
        show(rtl = true)
        val lead = nodes(NestedTags.Lead).first().boundsInRoot.center.x
        val width = rule.onNodeWithTag(SidebarTags.Root).fetchSemanticsNode().boundsInRoot.width
        assertTrue("RTL lead on the right (the start side): $lead of $width", lead > width / 2)
    }

    /** A non-last and a last connector (tags o0 / o1) in one composition; the pixels at the spine column above and below mid, and at the mirrored column below. */
    private fun connectorPixels(rtl: Boolean): List<Triple<Int, Int, Int>> {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                androidx.compose.foundation.layout.Row {
                    for (last in listOf(false, true)) {
                        Box(Modifier.size(100.dp, 60.dp).background(Color.Black).testTag("o$last")) {
                            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).size(60.dp, 40.dp).nestedConnector(last, Color.White))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val d = rule.density.density
        return listOf("ofalse", "otrue").map { tag ->
            val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            val sx = if (rtl) ((100 - 14) * d).toInt() else (14 * d).toInt()
            val other = if (rtl) (14 * d).toInt() else ((100 - 14) * d).toInt()
            fun px(x: Int, yDp: Float) = bmp.getPixel(x, (yDp * d).toInt()) and 0xFFFFFF
            Triple(px(sx, 20f), px(sx, 45f), px(other, 45f))
        }
    }

    @Test fun aNonLastRowContinuesTheSpineAndTheLastRowEndsIt() {
        val white = Color.White.toArgb() and 0xFFFFFF
        val (nonLast, last) = connectorPixels(rtl = false)
        assertEquals("elbow part drawn", white, nonLast.first)
        assertEquals("non-last: spine runs on below mid", white, nonLast.second)
        assertEquals("last: elbow still drawn", white, last.first)
        assertNotEquals("last: the spine ends at the elbow", white, last.second)
    }

    @Test fun theConnectorMirrorsInRtl() {
        val white = Color.White.toArgb() and 0xFFFFFF
        val (nonLast, _) = connectorPixels(rtl = true)
        assertEquals("RTL: spine on the right", white, nonLast.second)
        assertNotEquals("RTL: nothing on the left", white, nonLast.third)
    }
}
