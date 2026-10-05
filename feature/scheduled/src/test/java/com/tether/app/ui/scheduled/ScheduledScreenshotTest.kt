package com.tether.app.ui.scheduled

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ScheduledActionsState
import com.tether.app.client.SetupApproval
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
enum class ScheduledShot(
    val id: String,
    val state: ScheduledActionsState,
    val tab: ScheduledTab = ScheduledTab.Schedules,
    val editor: ((Int) -> ScheduleEditor)? = null,
    /** ta-m7ef: the editor's setup check (checking, or its approval panel), when the shot draws one. */
    val setup: ((ScheduleEditor) -> ScheduleSetupCheck)? = null,
) {
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
    // ta-m7ef (tether #241): an isolated save asks what a run would resolve, then shows it for approval.
    EditorSetupChecking("editor-setup-checking", ScheduledStates.populated, editor = isolatedEditor, setup = { e -> setupCheck(e, confirming = false) }),
    EditorSetupConfirm("editor-setup-confirm", ScheduledStates.populated, editor = isolatedEditor, setup = { e -> setupCheck(e, confirming = true) }),
}

private val isolatedEditor: (Int) -> ScheduleEditor = { _ ->
    ScheduleEditor.create(ScheduledFixtures.claude, "/home/op/projects/tether", ScheduledFixtures.NOW, ScheduledFixtures.UTC)
        .let { e -> e.copy(form = e.form.copy(name = "Morning issue triage", prompt = "Review new issues and pull requests.", useWorktree = true)) }
}

private fun setupCheck(editor: ScheduleEditor, confirming: Boolean): ScheduleSetupCheck {
    val input = (ScheduledRules.submit(editor.form, editor.mode, editor.onceValue, ScheduledFixtures.NOW, ScheduledFixtures.UTC) as SubmitResult.Ok).input
    val digest = "sha256:" + "ab".repeat(32)
    return ScheduleSetupCheck(
        confirming = confirming,
        inspectRequestId = "chk-1",
        errorSeq = 0,
        input = input,
        editingId = null,
        form = editor.form,
        approval = if (confirming) {
            SetupApproval(
                mode = "branch-off", baseRef = "origin/main", commit = "0123456789abcdef0123456789abcdef01234567",
                commands = listOf("pnpm install --frozen-lockfile", "echo аpi ‮ok"), teardown = listOf("pnpm run db:drop"),
                portScript = "/opt/tether/ports.sh", portScriptSha256 = "9f2c" + "0".repeat(56) + "ab12", hiddenCharacters = true,
                consent = digest, scheduleConsent = digest,
            )
        } else {
            null
        },
    )
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
                    val shown = editorFor(viewport)
                    ScheduleEditorFrame(
                        editor = shown,
                        setup = setup?.invoke(shown),
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
