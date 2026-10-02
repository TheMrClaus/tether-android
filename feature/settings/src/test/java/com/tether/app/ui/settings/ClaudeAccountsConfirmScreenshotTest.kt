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
 * ta-7rh: the Claude accounts confirmations (Devices' pattern: seeded synchronously, the clock
 * driven by hand, captured with their own window): `-confirm-remove` removing an account with its
 * stored login, `-confirm-logout` logging one out.
 */
enum class AccountsConfirmShot(val id: String, val confirm: AccountsConfirm) {
    Remove("settings-engines-confirm-remove", AccountsConfirm.Remove("claude-work", "Claude Code (work)", deleteCredentials = true, hostDefault = false)),
    Logout("settings-engines-confirm-logout", AccountsConfirm.Logout("claude-work", "Claude Code (work)")),
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapAccountsConfirm(shot: AccountsConfirmShot, skin: TetherSkin, size: String) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                ClaudeAccountsConfirmDialog(shot.confirm, onCancel = {}, onConfirm = {})
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
class ClaudeAccountsConfirmPhoneScreenshotTest(private val shot: AccountsConfirmShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapAccountsConfirm(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = AccountsConfirmShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ClaudeAccountsConfirmTabletScreenshotTest(private val shot: AccountsConfirmShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapAccountsConfirm(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = AccountsConfirmShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
