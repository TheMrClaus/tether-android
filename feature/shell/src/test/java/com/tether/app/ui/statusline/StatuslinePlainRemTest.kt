package com.tether.app.ui.statusline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-v5n9 (ta-7njx): the statusline's container queries read the strip's width in plain rem
 * (`maxWidth / SP_PER_REM`), the same at every font scale: Chrome on Android never scales rem with the
 * Android font size, so the web's `@container` thresholds do not move with it. Only the pure
 * `statuslineFit(rem)` was pinned; a font term put back into the composable failed no unit test. Eight
 * widths straddle every threshold (8.5, 9, 11.5, 15.5, 22 rem), and the three classes run the same
 * table at 1.0, 1.3 and 2.0.
 */
abstract class StatuslinePlainRemBase {
    @get:Rule val rule = createComposeRule()

    private val cells = listOf(
        // width dp, rem, ranks shown, track
        Cell(135, 1, false), Cell(136, 2, false), Cell(144, 2, false), Cell(145, 2, true),
        Cell(184, 3, true), Cell(248, 4, true), Cell(351, 4, true), Cell(352, 5, true),
    )

    private data class Cell(val width: Int, val ranks: Int, val track: Boolean)

    private fun segments(width: Int) = List(5) { i ->
        StatusSegment(id = "s$i", key = "k$i", label = "L$i", value = "v$i", title = "$width/seg $i", percent = if (i == 0) 50 else null)
    }

    @Test fun theThresholdsAreInPlainRemAtEveryScale() {
        rule.setContent {
            TetherTheme {
                Column {
                    cells.forEach { c -> Box(Modifier.width(c.width.dp)) { StatuslineSegments(segments(c.width)) } }
                }
            }
        }
        rule.waitForIdle()
        cells.forEach { c ->
            val shown = (0 until 5).count { rule.onAllNodesWithContentDescription("${c.width}/seg $it").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("${c.width} dp (${c.width / TetherTypography.SP_PER_REM} rem): ranks shown", c.ranks, shown)
        }
        // The track appears from just above 9rem (145 dp, not 144): the first segment grows by the track and one gap.
        val trackStep = StatuslineTrackWidth.value + 0.3f * TetherTypography.SP_PER_REM
        val w144 = rule.onAllNodesWithContentDescription("144/seg 0")[0].getBoundsInRoot().width.value
        val w145 = rule.onAllNodesWithContentDescription("145/seg 0")[0].getBoundsInRoot().width.value
        assertTrue("the track shows at 145 dp and not at 144: delta ${w145 - w144}", w145 - w144 > 0f)
        assertEquals("the track step is the track and the segment's own gap", trackStep, w145 - w144, 0.5f)
        // The other cells carry the same: only 145 and up show it, so a segment at 184 is as wide as at 145.
        val w184 = rule.onAllNodesWithContentDescription("184/seg 0")[0].getBoundsInRoot().width.value
        assertEquals("the track stays from 145 up", w145, w184, 0.5f)
        val w135 = rule.onAllNodesWithContentDescription("135/seg 0")[0].getBoundsInRoot().width.value
        assertEquals("no track at 135", w144, w135, 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StatuslinePlainRemControlTest : StatuslinePlainRemBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class StatuslinePlainRemFont1_3Test : StatuslinePlainRemBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class StatuslinePlainRemFont2Test : StatuslinePlainRemBase()
