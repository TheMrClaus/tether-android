package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.4: the Devices confirmations, seeded synchronously (composed with their values, no tap, the
 * clock driven by hand) and captured with their own window: `-confirm-self` revoking the phone the
 * app is signed in with (ta-coik.5: the web's words only, as for any device), `-confirm-passkey` the
 * web's Remove this passkey.
 */
enum class DevicesConfirmShot(val id: String, val confirm: DevicesConfirm) {
    Self("settings-devices-confirm-self", DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.Yes)),
    Passkey("settings-devices-confirm-passkey", DevicesConfirm.RemovePasskey(DevicesFixtures.LAPTOP_KEY)),
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapDevicesConfirm(shot: DevicesConfirmShot, skin: TetherSkin, size: String) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                DevicesConfirmDialog(shot.confirm, onCancel = {}, onConfirm = {})
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DevicesConfirmPhoneScreenshotTest(private val shot: DevicesConfirmShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapDevicesConfirm(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = DevicesConfirmShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DevicesConfirmTabletScreenshotTest(private val shot: DevicesConfirmShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapDevicesConfirm(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = DevicesConfirmShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
