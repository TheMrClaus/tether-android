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
 * W34 goldens (A-spec section 5 #13-#14): the Session links popover's Stop handover row (stage + effect lines) at the phone
 * and the desktop layout, and the Stopping row (spinner, muted) at the phone. The W33 `handover-row-pending` stays: it is
 * the protocol-150 path, unchanged.
 */
enum class HandoverRowV151Shot(val id: String, val state: Handover.MenuState, val expanded: Boolean) {
    StopPhone("handover-row-stop", Handover.MenuState.Stop("Handover prompt written. The new session starts when this turn ends."), false),
    StopExpanded("handover-row-stop", Handover.MenuState.Stop("Handover prompt written. The new session starts when this turn ends."), true),
    Stopping("handover-row-stopping", Handover.MenuState.Stopping, false),
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapStop(shot: HandoverRowV151Shot, skin: TetherSkin) {
    mainClock.autoAdvance = false
    setContent {
        if (shot.expanded) ExpandedShellUnderTest(skin, PhoneShellState(linksOpen = true), ExpandedFixtures.idle, PanelStore(PanelPrefs()), handover = shot.state)
        else ShellUnderTest(skin, PhoneShellState(linksOpen = true), ShellFixtures.idle, handover = shot.state)
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-${if (shot.expanded) "tablet" else "phone"}.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverRowV151PhoneScreenshotTest(private val shot: HandoverRowV151Shot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun row() = rule.snapStop(shot, skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(HandoverRowV151Shot.StopPhone, HandoverRowV151Shot.Stopping).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class HandoverRowV151ExpandedScreenshotTest(private val shot: HandoverRowV151Shot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun row() = rule.snapStop(shot, skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(HandoverRowV151Shot.StopExpanded, it) }
    }
}
