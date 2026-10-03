package com.tether.app.ui.scheduled

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ScheduledActionsState
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
 * T9.3: the Scheduled destination's states in the two Studio skins, at the web's phone width
 * (412dp) and a tablet's (1280dp): `list` = active, running and paused schedules (a run error, a
 * last session), `completed` = the Completed tab, `continuations` = a session waiting for its limit
 * to reset, `empty` = no schedules; `editor-new` / `editor-edit` = the schedule editor (a new one;
 * a Run once edit), shot inline over its scrim. Clocks are pinned (2026-10-03 12:00 UTC, en-US).
 */
enum class ScheduledShot(val id: String, val state: ScheduledActionsState, val tab: ScheduledTab = ScheduledTab.Schedules, val editor: ((Int) -> ScheduleEditor)? = null) {
    List("list", ScheduledStates.populated),
    Completed("completed", ScheduledStates.populated, ScheduledTab.Completed),
    Continuations("continuations", ScheduledStates.populated, ScheduledTab.Continuations),
    Empty("empty", ScheduledStates.empty),
    EditorNew("editor-new", ScheduledStates.populated, editor = { _ ->
        ScheduleEditor.create(ScheduledFixtures.claude, "/home/op/projects/tether", ScheduledFixtures.NOW, ScheduledFixtures.UTC)
            .let { it.copy(error = "Name, prompt, and workspace are required.") }
    }),
    EditorEdit("editor-edit", ScheduledStates.populated, editor = { _ ->
        ScheduleEditor.edit(ScheduledStates.populated.schedules.first().copy(cron = "0 9 4 10 *", maxRuns = 1, nextRunAt = 1_791_104_400_000L), ScheduledFixtures.NOW, ScheduledFixtures.UTC)
    }),
}

private const val CaptureAtMs = 600L

private fun ScheduledShot.capture(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule, skin: TetherSkin, viewport: Int, path: String) {
    rule.mainClock.autoAdvance = false
    rule.setContent {
        val editorFor = editor
        if (editorFor == null) {
            ScheduledUnderTest(state, ScheduledRecorder(), viewport, ui = ScheduledUiState(tab = tab), skin = skin)
        } else {
            TetherTheme(skin.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ScheduleEditorFrame(
                        editor = editorFor(viewport),
                        entries = ScheduledStates.entries,
                        workspaces = listOf("/home/op/projects/tether", "/home/op/projects/site"),
                        viewportWidth = viewport,
                        zone = ScheduledFixtures.UTC,
                        onChange = {},
                        onClose = {},
                        onSubmit = {},
                        clock = { ScheduledFixtures.NOW },
                    )
                }
            }
        }
    }
    rule.mainClock.advanceTimeBy(CaptureAtMs)
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)))
}

private val StudioSkins = listOf(TetherSkin.Studio, TetherSkin.StudioDark)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1800dp-420dpi")
class ScheduledPhoneScreenshotTest(private val shot: ScheduledShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun scheduled() = shot.capture(rule, skin, 412, "src/test/screenshots/scheduled-${shot.id}/${skin.id}-phone.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ScheduledShot.entries.flatMap { s -> StudioSkins.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1000dp-160dpi")
class ScheduledTabletScreenshotTest(private val shot: ScheduledShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun scheduled() = shot.capture(rule, skin, 1280, "src/test/screenshots/scheduled-${shot.id}/${skin.id}-tablet.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ScheduledShot.entries.flatMap { s -> StudioSkins.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the list or the editor (Studio light, phone and tablet). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2400dp-420dpi", fontScale = 1.3f)
class ScheduledFontScalePhoneScreenshotTest(private val shot: ScheduledShot) {
    @get:Rule val rule = createComposeRule()

    @Test fun scheduled() = shot.capture(rule, TetherSkin.Studio, 412, "src/test/screenshots/scheduled-${shot.id}-font-1.3x/studio-phone.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(ScheduledShot.List, ScheduledShot.EditorEdit).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1000dp-160dpi", fontScale = 1.3f)
class ScheduledFontScaleTabletScreenshotTest(private val shot: ScheduledShot) {
    @get:Rule val rule = createComposeRule()

    @Test fun scheduled() = shot.capture(rule, TetherSkin.Studio, 1280, "src/test/screenshots/scheduled-${shot.id}-font-1.3x/studio-tablet.png")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(ScheduledShot.List, ScheduledShot.EditorEdit).map { arrayOf<Any>(it) }
    }
}
