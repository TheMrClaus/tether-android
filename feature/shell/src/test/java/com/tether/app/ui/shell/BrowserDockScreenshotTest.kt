package com.tether.app.ui.shell

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `browser-docked`: the open browser pane as a column of the shell (from 80rem) at the widths the
 * references exist for, 1280, 1440 and 1920; `browser-overlay`: 1279, below the threshold, the
 * overlay unchanged.
 */
private fun ComposeContentTestRule.snapDock(skin: TetherSkin, name: String, size: String) {
    mainClock.autoAdvance = false
    val harness = DockHarness().also { it.open = true }
    setContent { ExpandedShellUnderTest(skin, PhoneShellState(), ExpandedFixtures.idle, slots = dockSlots(harness)) }
    mainClock.advanceTimeBy(600L)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

private fun skins(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class BrowserDockedScreenshot1280Test(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapDock(skin, "browser-docked", "1280")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = skins()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1440dp-h900dp-mdpi")
class BrowserDockedScreenshot1440Test(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapDock(skin, "browser-docked", "1440")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = skins()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1920dp-h1080dp-mdpi")
class BrowserDockedScreenshot1920Test(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapDock(skin, "browser-docked", "1920")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = skins()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1279dp-h800dp-mdpi")
class BrowserOverlayScreenshot1279Test(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapDock(skin, "browser-overlay", "1279")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = skins()
    }
}
