package com.tether.app.ui.components.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One golden per primitive × skin at phone size (412×915dp @420dpi, the web's Pixel-class
 * viewport). `recordRoborazziDebug` writes src/test/screenshots; `verifyRoborazziDebug` (the
 * gate) fails on any changed pixel.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PrimitivePhoneScreenshotTest(private val primitive: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() {
        val board = PrimitiveBoards.getValue(primitive)
        rule.snapBoard(primitive, skin, ScreenSize.Phone) { board() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = PrimitiveBoards.keys.flatMap { p -> TetherSkin.entries.map { arrayOf<Any>(p, it) } }
    }
}

/** Tablet (1280×800dp @mdpi) goldens for the primitives that change at the web's 48rem breakpoint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class PrimitiveTabletScreenshotTest(private val primitive: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() {
        val board = TabletBoards.getValue(primitive)
        rule.snapBoard(primitive, skin, ScreenSize.Tablet) { board() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TabletBoards.keys.flatMap { p -> TetherSkin.entries.map { arrayOf<Any>(p, it) } }
    }
}

/** Reduced motion: ambient motion renders its static frame (spinner at rest, ping dot alone). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ReducedMotionScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun indicators() {
        rule.snapBoard("indicators-reduced-motion", skin, ScreenSize.Phone, reducedMotion = true) { IndicatorsBoard() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/**
 * 1.3× font scale (PLAN §4): legends, chips and the expand toggle grow without clipping. Two
 * skins (instrument uppercase legends; Studio's sentence-case ones), phone width. The window is
 * taller than a phone (1600dp) only so the grown keys board is captured whole.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1600dp-420dpi", fontScale = 1.3f)
class FontScaleScreenshotTest(private val primitive: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() {
        val board = FontScaleBoards.getValue(primitive)
        rule.snapBoard("$primitive-font-1.3x", skin, ScreenSize.Phone) { board() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = FontScaleBoards.keys.flatMap { p ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(p, it) }
        }
    }
}
