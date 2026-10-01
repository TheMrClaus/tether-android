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
 * ta-q6p: `settings-profile-confirm`, the confirmation a profile command change waits for: the
 * Gemini CLI profile's command from `gemini --experimental-acp` to a typed one that was split at a
 * double space (the note) and holds a zero-width space (its visible token), each part on its own
 * line. Seeded synchronously: the dialog is composed with its values, no tap, the clock driven by
 * hand; captured with its own window.
 */
private fun AndroidComposeTestRule<*, ComponentActivity>.snapProfileConfirm(skin: TetherSkin, size: String) {
    mainClock.autoAdvance = false
    val review = ProfileRunsReview(
        profileId = "gemini",
        name = "Gemini CLI",
        extends = "acp",
        home = false,
        parts = listOf("/opt/gemini/bin/gem\u200Bini", "--experimental-acp", "--sandbox"),
        now = listOf("gemini", "--experimental-acp"),
        normalized = true,
        snapshot = com.tether.app.client.RunsSnapshot.of(ProfileFixtures.list().profile("gemini")!!),
    )
    setContent {
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                ProfileConfirmDialog(review, onConfirm = {}, onCancel = {})
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage(
        "src/test/screenshots/settings-profile-confirm/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfileConfirmPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapProfileConfirm(skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ProfileConfirmTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapProfileConfirm(skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
