package com.tether.app.ui.shell

import androidx.compose.ui.test.junit4.ComposeContentTestRule
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
 * The expanded shell's states, matching the seeded web scenarios at the tablet viewport
 * (parity-corpus/screens/web/<scenario>/<skin>-tablet.png, 1280×800 @1x): `idle` = idle-session,
 * `empty` = empty-state, `details` = session-details (the floating telemetry sheet — "inspector
 * open" below 100rem), plus `resized` (a stored 360px rail), `collapsed` (the rail collapsed, the
 * dock showing) and `links` (the Session links popover), which have no web shot.
 */
enum class ExpandedShot(val id: String) {
    Idle("idle"), Empty("empty"), Details("details"), Resized("resized"), Collapsed("collapsed"), Links("links"),
    /** ≥ 100rem only: the inspector as the third column, default widths. */
    Column("column"),
    /** ≥ 100rem only: both columns dragged (rail 300px, inspector 360px). */
    ColumnResized("column-resized"),
}

fun expandedState(shot: ExpandedShot): PhoneShellState = when (shot) {
    ExpandedShot.Details -> PhoneShellState(telemetryOpen = true)
    ExpandedShot.Links -> PhoneShellState(linksOpen = true)
    else -> PhoneShellState()
}

fun expandedPanels(shot: ExpandedShot): PanelPrefs = when (shot) {
    ExpandedShot.Resized -> PanelPrefs(sidebarWidth = 360)
    ExpandedShot.Collapsed -> PanelPrefs(sidebarCollapsed = true)
    ExpandedShot.ColumnResized -> PanelPrefs(sidebarWidth = 300, inspectorWidth = 360)
    else -> PanelPrefs()
}

fun expandedSession(shot: ExpandedShot) = when (shot) {
    ExpandedShot.Empty -> null
    ExpandedShot.Details -> ExpandedFixtures.details
    else -> ExpandedFixtures.idle
}

private const val CaptureAtMs = 600L

fun ComposeContentTestRule.snapExpanded(shot: ExpandedShot, skin: TetherSkin, name: String, size: String) {
    mainClock.autoAdvance = false
    setContent { ExpandedShellUnderTest(skin, expandedState(shot), expandedSession(shot), PanelStore(expandedPanels(shot))) }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

private fun allSkins(shots: List<ExpandedShot>): List<Array<Any>> =
    shots.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }

/** The web's tablet viewport (1280×800 @1x): every state below 100rem × both Studio skins. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ExpandedTabletScreenshotTest(private val shot: ExpandedShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(shot, skin, "shell-expanded-${shot.id}", "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = allSkins(
            listOf(ExpandedShot.Idle, ExpandedShot.Empty, ExpandedShot.Details, ExpandedShot.Resized, ExpandedShot.Collapsed, ExpandedShot.Links),
        )
    }
}

/**
 * A foldable-ish window just above the 840dp cutoff (900×700): no tool words (< 80rem), the
 * stage's narrow left gutter (< 64rem), the 40vw rail ceiling.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h700dp-mdpi")
class ExpandedFoldableScreenshotTest(private val shot: ExpandedShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(shot, skin, "shell-expanded-${shot.id}", "foldable")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = allSkins(listOf(ExpandedShot.Idle, ExpandedShot.Empty, ExpandedShot.Details))
    }
}

/** ≥ 100rem (1680×1050): the three-column grid with the inspector column and both handles. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1680dp-h1050dp-mdpi")
class ExpandedDesktopScreenshotTest(private val shot: ExpandedShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(shot, skin, "shell-expanded-${shot.id}", "desktop")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = allSkins(listOf(ExpandedShot.Column, ExpandedShot.ColumnResized))
    }
}

/** PLAN §4: 1.3× font scale at the tablet viewport (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 1.3f)
class ExpandedFontScaleScreenshotTest(private val shot: ExpandedShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapExpanded(shot, skin, "shell-expanded-${shot.id}-font-1.3x", "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ExpandedShot.Idle, ExpandedShot.Details, ExpandedShot.Links).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}

/**
 * T4.1 verifier follow-up: the phone's Session links popover at 1.3× on a short window (412×320,
 * e.g. a phone in split screen) is capped at `calc(100dvh - 8rem)` and scrolls instead of being
 * cut off (globals.css 11865-11866).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h320dp-420dpi", fontScale = 1.3f)
class LinksPopoverShortScreenScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun popover() {
        rule.mainClock.autoAdvance = false
        rule.setContent { ShellUnderTest(skin, PhoneShellState(linksOpen = true), ShellFixtures.idle) }
        rule.mainClock.advanceTimeBy(CaptureAtMs)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/shell-links-short-font-1.3x/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
