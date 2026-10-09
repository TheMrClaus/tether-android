package com.tether.app.ui.log

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.protocol.LogEntry
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The log dialog's seeded states at the web's device classes (phone 412×915 @2.625, tablet
 * 1280×800 @1), both Studio skins, plus 1.3× font. `full` = a mixed log with stats loaded; `empty` =
 * stats loaded, no entries; `warnings` = the Warnings filter on; `corpus` = the fake-engine
 * capture's own records (parity-corpus/wire/ambient.jsonl), stats loaded; `web` = the web
 * reference's own state (phone and tablet), for the montages.
 */
enum class LogShot(val id: String) {
    Full("log-dialog"),

    /** ta-otgf: the full dialog under the device's section: a crash record (42 stack lines) and two exits. */
    Crash("log-dialog-crash"),
    Empty("log-dialog-empty"), Warnings("log-dialog-warnings"), Corpus("log-dialog-corpus"),

    /** The web reference's seeded state, for the montages (docs/parity/screens/log-dialog). */
    Web("log-dialog-web"),
}

private fun entriesFor(shot: LogShot, size: String): List<LogEntry> = when (shot) {
    LogShot.Empty -> emptyList()
    LogShot.Corpus -> LogFixtures.corpusLog("ambient.jsonl")
    LogShot.Web -> if (size == "tablet") LogFixtures.webTablet else LogFixtures.webPhone
    else -> LogFixtures.mixed
}

fun ComposeContentTestRule.snapLog(shot: LogShot, skin: TetherSkin, size: String, name: String = shot.id) {
    mainClock.autoAdvance = false
    val state = LogDialogState(
        level = if (shot == LogShot.Warnings) LogLevelFilter.Warnings else LogLevelFilter.All,
        stats = if (shot == LogShot.Web) LogFixtures.webStats else LogFixtures.stats,
    )
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                LogDialogFrame(
                    entries = entriesFor(shot, size),
                    sessions = LogFixtures.sessions,
                    state = state,
                    onRefresh = {},
                    onClose = {},
                    locale = LogFixtures.locale,
                    zone = LogFixtures.zone,
                    crash = if (shot == LogShot.Crash) LogFixtures.crash else null,
                    exits = if (shot == LogShot.Crash) LogFixtures.exits else emptyList(),
                )
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LogDialogPhoneScreenshotTest(private val shot: LogShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun log() = rule.snapLog(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = LogShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class LogDialogTabletScreenshotTest(private val shot: LogShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun log() = rule.snapLog(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(LogShot.Full, LogShot.Crash, LogShot.Web).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the dialog (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class LogDialogFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun log() = rule.snapLog(LogShot.Full, skin, "phone", name = "log-dialog-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/** ta-otgf: the crash section at 360 dp and 2.0x font (the keys wrap under the heading, the summary cuts to two lines). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 2.0f)
class LogDialogCrashFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun log() = rule.snapLog(LogShot.Crash, skin, "phone", name = "log-dialog-crash-360-font-2.0x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
