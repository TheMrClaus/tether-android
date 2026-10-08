package com.tether.app.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca (W20): the Expanded shell between the web's 48rem floor and 64rem, the windows that were Phone at 840: the floor
 * itself (768 x 1024), a tablet portrait (800 x 1280) and a small phone turned sideways (780 x 360 at 420dpi, the web's
 * innerHeight; no height guard, the web has none). Idle session, both Studio skins.
 */
private fun studioSkins(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w768dp-h1024dp-mdpi")
class ExpandedFloorScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(ExpandedShot.Idle, skin, "shell-expanded-idle", "w768")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = studioSkins()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp-mdpi")
class ExpandedTabletPortraitScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(ExpandedShot.Idle, skin, "shell-expanded-idle", "w800")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = studioSkins()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w780dp-h360dp-420dpi")
class ExpandedSmallLandscapeScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(ExpandedShot.Idle, skin, "shell-expanded-idle", "w780-landscape")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = studioSkins()
    }
}

/** The bar at the floor: the app's fold (W2, the web's overflow at 768-810 is not copied) in both Studio skins. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w768dp-h1024dp-mdpi")
class TopbarFloorScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun topbar() = rule.snapTopbar(TopbarShot.Bar, skin, wide = true, size = "w768")

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = studioSkins()
    }
}
