package com.tether.app.ui.shell

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
 * ta-coik.62: the phone's Session links popover for a session that carries a `resumeCommand` (v52):
 * the "Tap to copy resume command" key between the working directory and the Tether id
 * (workspace-header.tsx:56-70). The popover without one is the existing `shell-links` golden.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ResumeCommandLinksScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun popover() {
        rule.mainClock.autoAdvance = false
        val session = ShellFixtures.idle.copy(resumeCommand = "cd -- /srv/ws/parity-app && claude --resume 3f2a9c1e-77aa-4b0d-9d21-5c6f0e8b1a42")
        rule.setContent { ShellUnderTest(skin, PhoneShellState(linksOpen = true), session) }
        rule.mainClock.advanceTimeBy(600L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/shell-links-resume/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
