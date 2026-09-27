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
 * The phone shell's states, matching the seeded web scenarios (parity-corpus/screens/web):
 * `empty` = empty-state, `idle` = idle-session, `drawer` = session-drawer, `details` =
 * session-details (telemetry panel open), plus the header's `links` popover (no web shot).
 * Hosted surfaces are placeholder slots (see [placeholderSlots]); ambient motion is static
 * (reduced motion), like a settled frame.
 */
enum class ShellShot(val id: String) { Empty("empty"), Idle("idle"), Drawer("drawer"), Details("details"), Links("links") }

fun shellState(shot: ShellShot): PhoneShellState = when (shot) {
    ShellShot.Drawer -> PhoneShellState(drawerOpen = true)
    ShellShot.Details -> PhoneShellState(telemetryOpen = true)
    ShellShot.Links -> PhoneShellState(linksOpen = true)
    else -> PhoneShellState()
}

fun shellSession(shot: ShellShot) = when (shot) {
    ShellShot.Empty -> null
    ShellShot.Details -> ShellFixtures.details
    else -> ShellFixtures.idle
}

/** 600ms past the first frame: every enter/slide transition has settled. */
private const val CaptureAtMs = 600L

fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapShell(shot: ShellShot, skin: TetherSkin, name: String) {
    mainClock.autoAdvance = false
    setContent { ShellUnderTest(skin, shellState(shot), shellSession(shot)) }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-phone.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every shell state × all 6 skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ShellPhoneScreenshotTest(private val shot: ShellShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapShell(shot, skin, "shell-${shot.id}")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ShellShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the chrome (instrument uppercase + Studio legends). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ShellFontScaleScreenshotTest(private val shot: ShellShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapShell(shot, skin, "shell-${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ShellShot.Idle, ShellShot.Empty, ShellShot.Details).flatMap { s ->
            listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
