package com.tether.app.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.Handover
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #273 goldens (A1 §8 1-5): the "Session links" menu's Hand over row. `handover-row-enabled`, `-pending`, `-handed-off` and
 * `-engine` at the phone (412x915 @420dpi, the End session confirmation's qualifier), `-expanded` at the desktop layout
 * (1280x800). Both Studio skins; each state is seeded synchronously (the row state is a constructor argument).
 */
enum class HandoverRowShot(val id: String, val state: Handover.MenuState, val expanded: Boolean = false) {
    Enabled("handover-row-enabled", Handover.MenuState.Enabled),
    Expanded("handover-row-expanded", Handover.MenuState.Enabled, expanded = true),
    Pending("handover-row-pending", Handover.MenuState.Blocked(Handover.UNDER_WAY)),
    HandedOff("handover-row-handed-off", Handover.MenuState.Blocked(Handover.reasonCopy("already_handed_off", "Parser rewrite"))),
    Engine("handover-row-engine", Handover.MenuState.Blocked(Handover.reasonCopy("provider_no_bridge"))),
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapRow(shot: HandoverRowShot, skin: TetherSkin, size: String) {
    mainClock.autoAdvance = false
    setContent {
        if (shot.expanded) ExpandedShellUnderTest(skin, PhoneShellState(linksOpen = true), ExpandedFixtures.idle, PanelStore(PanelPrefs()), handover = shot.state)
        else ShellUnderTest(skin, PhoneShellState(linksOpen = true), ShellFixtures.idle, handover = shot.state)
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverRowPhoneScreenshotTest(private val shot: HandoverRowShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun row() = rule.snapRow(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(HandoverRowShot.Enabled, HandoverRowShot.Pending, HandoverRowShot.HandedOff, HandoverRowShot.Engine)
            .flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class HandoverRowExpandedScreenshotTest(private val shot: HandoverRowShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun row() = rule.snapRow(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(HandoverRowShot.Expanded, it) }
    }
}
