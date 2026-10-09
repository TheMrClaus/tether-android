package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A7: a row fits its lane at every font size: the verb is never clipped or squeezed, a long argument ends in an
 * ellipsis on its one line, the row is at least 44 dp and grows with the font (no fixed height), the state cluster
 * wraps below the verb when the line is full, and a one-line row is centred in its 44 dp (glyph on the verb's centre).
 */
abstract class ActivityRowFitBase(private val narrow: Boolean) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fixture: ChatFixtures.Folded by lazy {
        val s = ActivityFixtures.Script()
        s.start("Fit.")
        s.tool("f1", "Bash", """{"command":${ActivityFixtures.text(ActivityFixtures.LONG_COMMAND + " && " + ActivityFixtures.LONG_COMMAND)}}""", ActivityFixtures.text("ok"))
        s.tool("f2", "Read", """{"file_path":"/a.kt"}""", ActivityFixtures.text("x"))
        s.tool("f3", "Bash", """{"command":${ActivityFixtures.text(ActivityFixtures.LONG_COMMAND + " && " + ActivityFixtures.LONG_COMMAND)}}""", done = false)
        s.progress("f3", 12)
        s.fold()
    }

    private val doneShell = rowLabel("Shell git -C") and hasContentDescription(", done", substring = true)
    private val runningShell = rowLabel("Shell git -C") and hasContentDescription(", running", substring = true)
    private val read = rowLabel("Read /a.kt, done")

    private fun part(row: SemanticsMatcher, tag: String): SemanticsNode =
        rule.onNode(hasTestTag(tag) and hasAnyAncestor(row), useUnmergedTree = true).fetchSemanticsNode()

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
        return out.single()
    }

    private fun reach(row: SemanticsMatcher) = rule.onNodeWithTag("chat-transcript").performScrollToNode(row)

    private fun height(row: SemanticsMatcher) = rule.onNode(row).fetchSemanticsNode().size.height

    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }

    private var fontScale by mutableFloatStateOf(1f)

    @Test fun theRowFitsAtEveryFontSize() {
        val toggles = allGroupsOpen(fixture)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                ChatHost(TetherSkin.StudioDark, wellHeight = 800.dp) {
                    CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                        ChatTranscript(
                            projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                            zone = ChatFixtures.zone, showTimeline = false, groupToggles = toggles,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        var runningAt1 = 0
        for (scale in listOf(1f, 1.3f, 2f)) {
            rule.runOnIdle { fontScale = scale }
            rule.waitForIdle()
            // The one-line row: the verb is drawn whole, on its natural width, and the row is at least 44 dp.
            reach(read)
            val verb = part(read, "activity-row-verb")
            val verbLayout = layoutOf(verb)
            assertEquals("verb stays on one line at $scale", 1, verbLayout.lineCount)
            assertFalse("verb is not ellipsized at $scale", verbLayout.isLineEllipsized(0))
            assertEquals("verb width is its intrinsic at $scale", ceil(verbLayout.multiParagraph.maxIntrinsicWidth).toInt(), verb.size.width)
            assertTrue("at least 44 dp at $scale (${height(read)} px)", height(read) >= px(44f) - 1)
            if (scale == 1f) {
                // Centred: the glyph on the verb's centre, the line block centred in the row (1 dp).
                val row = rule.onNode(read).fetchSemanticsNode().boundsInRoot
                val glyph = part(read, "activity-row-glyph").boundsInRoot
                val v = verb.boundsInRoot
                assertTrue("glyph centre on the verb centre", abs(glyph.center.y - v.center.y) <= px(1f))
                assertTrue("top gap equals bottom gap", abs((v.top - row.top) - (row.bottom - v.bottom)) <= px(1f))
            }
            // The long argument ends in an ellipsis on its one line.
            reach(doneShell)
            val arg = layoutOf(part(doneShell, "activity-row-arg"))
            assertEquals(1, arg.lineCount)
            assertTrue("a long argument is ellipsized at $scale", arg.isLineEllipsized(0))
            // The running row's state cluster wraps below the verb once the line is full; the row grows with the font.
            reach(runningShell)
            val runningHeight = height(runningShell)
            if (scale == 1f) runningAt1 = runningHeight
            if (scale == 2f) {
                val cluster = part(runningShell, "activity-row-cluster")
                val runningVerb = part(runningShell, "activity-row-verb")
                if (narrow) {
                    assertTrue("2.0x is taller than 1.0x ($runningHeight vs $runningAt1)", runningHeight > runningAt1)
                    assertTrue("the cluster sits below the verb", cluster.boundsInRoot.top >= runningVerb.boundsInRoot.bottom - 1)
                } else {
                    assertTrue("a wide lane keeps the cluster on the verb's line", cluster.boundsInRoot.top < runningVerb.boundsInRoot.bottom)
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi")
class ActivityRowFitPhoneTest : ActivityRowFitBase(narrow = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ActivityRowFitTabletTest : ActivityRowFitBase(narrow = false)
