package com.tether.app.ui.settings

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.mode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dialog's seeded states at the web's device classes (phone 412×915 @2.625: full screen;
 * tablet 1280×800 @1: the centred dialog), both Studio skins, plus 1.3× font. Fresh preferences
 * but the skin's mode (the web reference's own state: ended sessions and confirm on, thinking off,
 * no default folder).
 * `settings-general` / `-appearance` / `-devices` / `-engines` are those tabs (Engines stands for
 * the panels a later slice fills); `settings-restart` is General under the restart banner.
 */
enum class SettingsShot(val id: String, val tab: SettingsTab, val restart: Boolean = false) {
    General("settings-general", SettingsTab.General),
    Appearance("settings-appearance", SettingsTab.Appearance),
    Devices("settings-devices", SettingsTab.Devices),
    Engines("settings-engines", SettingsTab.Engines),
    Restart("settings-restart", SettingsTab.General, restart = true),
}

fun ComposeContentTestRule.snapSettings(store: PrefsStore, shot: SettingsShot, skin: TetherSkin, size: String, name: String = shot.id) {
    // The stored mode is the skin shown (the web reference's seeded state), so Appearance agrees.
    store.seed(PreferenceKeys.THEME_MODE to skin.mode.id)
    val state = SettingsDialogState(shot.tab)
    setContent {
        SettingsUnderTest(
            store.prefs,
            state,
            mode = skin.mode,
            layout = if (size == "tablet") TetherLayoutClass.Expanded else TetherLayoutClass.Phone,
            restartRequired = shot.restart,
        )
    }
    waitUntil(5_000) { state.draft != null }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

abstract class SettingsShotBase {
    private val tmp = TemporaryFolder()
    protected val store = PrefsStore(tmp)
    protected val rule = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(rule)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsPhoneScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SettingsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SettingsTabletScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SettingsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the dialog (General and Appearance, Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SettingsFontScaleScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "phone", name = "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SettingsShot.General, SettingsShot.Appearance).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
