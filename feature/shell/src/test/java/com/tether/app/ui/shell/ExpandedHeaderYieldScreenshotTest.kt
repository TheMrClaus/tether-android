package com.tether.app.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-7njx (W23, L4 ruling B2): the expanded header's yield ladder above text scale 1.0, on the approval session ("Needs
 * you"), Studio. Four cells, one per state the ladder shows: 768 @ 1.3 (S1, Telemetry drops its word), 768 @ 2.0 (S2, Pin
 * in the "Session links" menu), 845 @ 2.0 (S1 again, the old worst band's top) and 768 @ 2.0 with the rail at its 307 dp
 * ceiling (a stored 480 dp, clamped to min(30rem, 40vw); S3, End session drops its word). 846 and 900 are behaviour cases
 * in [HeaderActionReachTest] only.
 */
@RunWith(RobolectricTestRunner::class)
class ExpandedHeaderYieldScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val approval = ExpandedFixtures.idle.copy(name = "Approval fixture", status = "waiting")

    private fun snap(name: String, panels: PanelPrefs = PanelPrefs()) {
        rule.mainClock.autoAdvance = false
        rule.setContent { ExpandedShellUnderTest(TetherSkin.Studio, PhoneShellState(), approval, PanelStore(panels)) }
        rule.mainClock.advanceTimeBy(600L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/shell-expanded-header-c3/$name.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    @Test @Config(qualifiers = "w768dp-h1024dp-mdpi", fontScale = 1.3f)
    fun s1At768Font1_3() = snap("studio-w768-font-1.3x")

    @Test @Config(qualifiers = "w768dp-h1024dp-mdpi", fontScale = 2.0f)
    fun s2At768Font2() = snap("studio-w768-font-2.0x")

    @Test @Config(qualifiers = "w845dp-h1024dp-mdpi", fontScale = 2.0f)
    fun s1At845Font2() = snap("studio-w845-font-2.0x")

    @Test @Config(qualifiers = "w768dp-h1024dp-mdpi", fontScale = 2.0f)
    fun s3At768Font2WithTheRailAtItsCeiling() = snap("studio-w768-font-2.0x-rail307", PanelPrefs(sidebarWidth = 480))
}
