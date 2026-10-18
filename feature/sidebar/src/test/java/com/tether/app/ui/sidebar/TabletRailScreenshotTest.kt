package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W53 (ta-hkk7.8): the tablet rail's new goldens, both Studio skins: the row model's badges at the default 272 rail (the badge
 * stacks under the meta line there), the row menu hanging off the rail's edge, and the same badges at twice the text. All at 1280 dp.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TabletRailRowScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    private fun snap(shot: RowShot, name: String) = rule.snapContent(skin, name, "tablet") {
        SidebarUnderTest(skin, rowState(shot), TetherLayoutClass.Expanded, rowSeed(shot), SidebarActions(onCollapse = {}))
    }

    @Test fun badges() = snap(RowShot.Badges, "sidebar-badges")
    @Test fun rowMenu() = snap(RowShot.RowMenu, "sidebar-row-menu")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 2.0f)
class TabletRailBadgesFont2ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun badges() = rule.snapContent(skin, "sidebar-badges-font-2.0x", "tablet") {
        SidebarUnderTest(skin, rowState(RowShot.Badges), TetherLayoutClass.Expanded, rowSeed(RowShot.Badges), SidebarActions(onCollapse = {}))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/** A window of 1440 dp or more: the shell gives the rail 320 dp, and the long "Scheduled actions" label shows (no kbd chip on a touch rail). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1440dp-h900dp-mdpi")
class SidebarRail320ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun rail() = rule.snapContent(skin, "sidebar-rail-320", "tablet") {
        SidebarUnderTest(skin, sidebarState(SidebarShot.Drawer), TetherLayoutClass.Expanded, sidebarSeed(SidebarShot.Drawer), SidebarActions(onCollapse = {}), railWidth = 320.dp)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
