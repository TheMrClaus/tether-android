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

/**
 * ta-q6p r2: the other two confirmations of what a profile runs, seeded the same way.
 * `settings-profile-env-confirm`: a risky env key's value change (PATH on the Gemini CLI
 * profile), both values masked as the dialog opens (FAKE values behind the masks).
 * `settings-profile-extends-confirm`: the Gemini CLI profile moving from acp to claude, with
 * the command and home the new engine will run (the command shown although Claude hides it) and, r3, the
 * names of its risky env keys (PATH; never the value).
 */
enum class ProfileConfirmShot(val id: String) { Command("settings-profile-confirm"), Env("settings-profile-env-confirm"), Extends("settings-profile-extends-confirm") }

private fun AndroidComposeTestRule<*, ComponentActivity>.snapOther(shot: ProfileConfirmShot, skin: TetherSkin, size: String) {
    if (shot == ProfileConfirmShot.Command) return snapProfileConfirm(skin, size)
    mainClock.autoAdvance = false
    val gemini = ProfileFixtures.list(ProfileFixtures.profiles(ProfileFixtures.gemini(extraEnv = ""","PATH":"/usr/bin""""))).profile("gemini")!!
    val snapshot = com.tether.app.client.RunsSnapshot.of(gemini)
    setContent {
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                when (shot) {
                    ProfileConfirmShot.Env -> EnvConfirmDialog(
                        EnvReview("gemini", "Gemini CLI", com.tether.app.client.EnvChange.Change("PATH", com.tether.app.client.SecretText("FAKE-/opt/demo/bin")), gemini.envValue("PATH"), snapshot),
                        onConfirm = {}, onCancel = {},
                    )
                    else -> ExtendsConfirmDialog(
                        ExtendsReview("gemini", "Gemini CLI", "acp", "claude", gemini.command.orEmpty(), gemini.homeDir, snapshot, riskyKeys = gemini.envKeys.filter(com.tether.app.client.RiskyEnvKeys::risky)),
                        onConfirm = {}, onCancel = {},
                    )
                }
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
class ProfileOtherConfirmPhoneScreenshotTest(private val shot: ProfileConfirmShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapOther(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ProfileConfirmShot.Env, ProfileConfirmShot.Extends).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ProfileOtherConfirmTabletScreenshotTest(private val shot: ProfileConfirmShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapOther(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ProfileConfirmShot.Env, ProfileConfirmShot.Extends).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
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
