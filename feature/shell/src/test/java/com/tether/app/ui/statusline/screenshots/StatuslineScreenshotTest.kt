package com.tether.app.ui.statusline.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tether.app.ui.statusline.UsageTrack
import com.tether.app.ui.statusline.UsageTrackPlacement
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Every board × all 6 skins at phone size (412×915dp @420dpi, the web's 412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StatuslinePhoneScreenshotTest(private val board: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() {
        val content = StatuslineBoards.getValue(board)
        rule.snapBoard(board, skin, ScreenSize.Phone) { content() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = StatuslineBoards.keys.flatMap { b -> TetherSkin.entries.map { arrayOf<Any>(b, it) } }
    }
}

/**
 * Tablet (1280×800dp @mdpi): the header rail the web shows from 48rem — the dial and the labelled
 * gauge — at the web tablet capture's 1 px/dp, for the montage against the streaming scenario.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class StatuslineTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun headerRail() {
        rule.snapBoard("header-rail", skin, ScreenSize.Tablet) { HeaderRailBoard() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/**
 * 1.3× font scale (PLAN §4): the strips re-fit (rem thresholds scale with the text), the dial and
 * gauge grow without clipping. Instrument (Machine) and Studio; a taller window so boards fit.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1600dp-420dpi", fontScale = 1.3f)
class StatuslineFontScaleScreenshotTest(private val board: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() {
        val content = StatuslineBoards.getValue(board)
        rule.snapBoard("$board-font-1.3x", skin, ScreenSize.Phone) { content() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = StatuslineBoards.keys.flatMap { b ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(b, it) }
        }
    }
}

/**
 * Reduced motion: the usage track's width transition (`var(--duration) var(--ease-out)`, 200ms) is
 * a jump. The reading flips 0% → 80% INSIDE the composition ([FlipAfterMs] on the paused test
 * clock), and both captures are taken [FrameAfterFlipMs] later: with motion the fill is
 * mid-transition, under reduced motion it is already at 80%. [UsageTrackMotionGoldensTest] asserts
 * the two goldens differ in exactly that way.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UsageTrackMotionScreenshotTest(private val reduced: Boolean) {
    @get:Rule val rule = createComposeRule()

    @Test fun framesAfterAChange() {
        rule.snapBoard(
            if (reduced) "usage-track-reduced-motion" else "usage-track-motion",
            TetherSkin.StudioDark,
            ScreenSize.Phone,
            reducedMotion = reduced,
            captureAtMs = FlipAfterMs + FrameAfterFlipMs,
        ) {
            var percent by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                delay(FlipAfterMs)
                percent = 80
            }
            StateRow("statusline · meter, ${FrameAfterFlipMs}ms after 0% → 80%") {
                UsageTrack(percent, "Context")
                Box(Modifier.width(200.dp)) {
                    UsageTrack(percent, "Five hour", placement = UsageTrackPlacement.Meter)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "reduced={0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}

/** When the motion board's reading flips, and when it is captured: a few frames in, well inside the 200ms transition. */
const val FlipAfterMs: Long = 100
const val FrameAfterFlipMs: Long = 64
