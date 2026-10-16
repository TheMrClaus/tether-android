package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

private val F = SidebarFixtures

/** W39 (ta-v0h8): the phone-only states the restyle adds (both Studio skins; no tablet shot). */
private fun states(): Map<String, Pair<SidebarState, SidebarUiSeed>> = mapOf(
    // two cards: the current workspace with a selected row, a second one collapsed, Archived N.
    "sidebar-two-workspaces" to (F.state(F.groupSessions, pinned = listOf(F.DOCS), current = F.APP, activeId = "g3", collapsed = listOf(F.DOCS)) to SidebarUiSeed()),
    "sidebar-active-lens" to (F.state(F.statusSessions, histories = F.statusHistories, activeOnly = true) to SidebarUiSeed()),
    "sidebar-scheduled-active" to (F.state(F.drawerSessions.take(4), activeId = "s02").copy(scheduledActionCount = 3, scheduledActionsActive = true) to SidebarUiSeed()),
    "sidebar-groups-font-1.3x" to (sidebarState(SidebarShot.Groups) to sidebarSeed(SidebarShot.Groups)),
    "sidebar-drawer-font-2.0x" to (sidebarState(SidebarShot.Drawer) to sidebarSeed(SidebarShot.Drawer)),
)

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapPhone(name: String, skin: TetherSkin) {
    val (state, seed) = states().getValue(name)
    snapContent(skin, name, "phone") { SidebarUnderTest(skin, state, TetherLayoutClass.Phone, seed, SidebarActions(onCollapse = {})) }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarPhoneRestyleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun twoWorkspaces() = rule.snapPhone("sidebar-two-workspaces", skin)
    @Test fun activeLens() = rule.snapPhone("sidebar-active-lens", skin)
    @Test fun scheduledActive() = rule.snapPhone("sidebar-scheduled-active", skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SidebarPhoneRestyleFont13ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun groups() = rule.snapPhone("sidebar-groups-font-1.3x", skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class SidebarPhoneRestyleFont20ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun drawer() = rule.snapPhone("sidebar-drawer-font-2.0x", skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
