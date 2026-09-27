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
 * 1280×800 @1), all 6 skins, plus 1.3× font. `full` = a mixed log with stats loaded; `empty` =
 * stats loaded, no entries; `warnings` = the Warnings filter on; `corpus` = the fake-engine
 * capture's own records (parity-corpus/wire/ambient.jsonl), stats loaded.
 */
enum class LogShot(val id: String) { Full("log-dialog"), Empty("log-dialog-empty"), Warnings("log-dialog-warnings"), Corpus("log-dialog-corpus") }

private fun entriesFor(shot: LogShot): List<LogEntry> = when (shot) {
    LogShot.Empty -> emptyList()
    LogShot.Corpus -> LogFixtures.corpusLog("ambient.jsonl")
    else -> LogFixtures.mixed
}

fun ComposeContentTestRule.snapLog(shot: LogShot, skin: TetherSkin, size: String, name: String = shot.id) {
    mainClock.autoAdvance = false
    val state = LogDialogState(
        level = if (shot == LogShot.Warnings) LogLevelFilter.Warnings else LogLevelFilter.All,
        stats = LogFixtures.stats,
    )
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                LogDialogFrame(
                    entries = entriesFor(shot),
                    sessions = LogFixtures.sessions,
                    state = state,
                    onRefresh = {},
                    onClose = {},
                    locale = LogFixtures.locale,
                    zone = LogFixtures.zone,
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
class LogDialogTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun log() = rule.snapLog(LogShot.Full, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** PLAN §4: 1.3× font scale does not break the dialog (instrument uppercase + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class LogDialogFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun log() = rule.snapLog(LogShot.Full, skin, "phone", name = "log-dialog-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
