package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.ModeVocabulary
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.2 visual states (DoD: every state in all 6 skins at phone size, the expanded layout where it
 * differs, 1.3× font). Phone: `sheet` = the session sheet's hub (Model / Effort / Mode / Fast for a
 * Claude session whose model has effort levels and fast mode), `mode` = its Mode list (Auto in
 * `--warning`, the check on Manual), `confirm` = the Android-only confirmation before Auto,
 * `codex-panel` = the Codex provider-controls view. Tablet (the web's desktop row from 64rem):
 * `opencode-row` = Model (with a provider tag) / Effort / Mode / the Auto toggle ON (warning edge,
 * "Auto" spoken) and the danger hint; `codex-row` = the Codex catalogs' Model / Effort / Mode, Auto
 * off, and the Provider controls key; `unknown-row` (round 2) = a Claude session whose stored mode
 * this app does not know ("Unknown mode (dontAsk)", warning edge and hint) and the Fast key (I6).
 * The idle Claude row is composer-idle's tablet golden.
 */
enum class ControlsShot(val id: String) { Sheet("sheet"), Mode("mode"), Confirm("confirm"), CodexPanel("codex-panel") }

enum class RowShot(val id: String) { Opencode("opencode-row"), Codex("codex-row"), Unknown("unknown-row") }

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private fun AndroidComposeTestRule<*, ComponentActivity>.snapSheet(shot: ControlsShot, skin: TetherSkin, name: String, size: String) {
    mainClock.autoAdvance = false
    val codex = shot == ControlsShot.CodexPanel
    val session: AgentSession = if (codex) SessionControlFixtures.codex else SessionControlFixtures.claude
    val controls: ServerMessage.SessionControls? = if (codex) null else SessionControlFixtures.claudeControls
    val actions = SessionControlFixtures.Recorder().actions(codex = if (codex) SessionControlFixtures.codexState else null)
    setContent {
        ComposerHost(skin) {
            Composer(
                session = session,
                projection = ComposerFixtures.idle.projection,
                controls = controls,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = {},
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                controlActions = actions,
            )
        }
    }
    fun step(ms: Long = 300) {
        mainClock.advanceTimeBy(16); waitForIdle()
        mainClock.advanceTimeBy(ms); waitForIdle()
    }
    step()
    onNodeWithTag("session-settings-trigger").performClick(); step()
    when (shot) {
        ControlsShot.Sheet -> Unit
        ControlsShot.Mode -> { onNodeWithTag("sheet-row-Mode").performClick(); step(700) }
        ControlsShot.Confirm -> {
            onNodeWithTag("sheet-row-Mode").performClick(); step(700)
            onNodeWithTag("control-option-${ModeVocabulary.AUTO}").performClick(); step(700)
        }
        ControlsShot.CodexPanel -> { onNodeWithTag("sheet-row-Provider controls").performClick(); step(700) }
    }
    captureScreenRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionControlsPhoneScreenshotTest(private val shot: ControlsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun controls() = rule.snapSheet(shot, skin, "controls-${shot.id}", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ControlsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (instrument + Studio) for the sheet and the confirmation. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SessionControlsFontScaleScreenshotTest(private val shot: ControlsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun controls() = rule.snapSheet(shot, skin, "controls-${shot.id}-font-1.3x", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ControlsShot.Sheet, ControlsShot.Confirm).flatMap { s ->
            listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}

/** The web desktop row (1280×800): the composer at the chat frame's 950dp column. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SessionControlsTabletScreenshotTest(private val shot: RowShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun row() {
        rule.mainClock.autoAdvance = false
        val session = when (shot) {
            RowShot.Opencode -> SessionControlFixtures.opencode.copy(approvalPolicy = "never", reasoningEffort = "high")
            RowShot.Codex -> SessionControlFixtures.codex
            RowShot.Unknown -> SessionControlFixtures.claude.copy(permissionMode = "dontAsk")
        }
        rule.setContent {
            ComposerHost(skin, WellWidthTablet) {
                Composer(
                    session = session,
                    projection = ComposerFixtures.idle.projection,
                    controls = when (shot) {
                        RowShot.Opencode -> SessionControlFixtures.opencodeControls
                        RowShot.Codex -> null
                        RowShot.Unknown -> SessionControlFixtures.claudeControls
                    },
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = {},
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    controlActions = SessionControlFixtures.Recorder().actions(codex = if (shot == RowShot.Codex) SessionControlFixtures.codexState else null),
                )
            }
        }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/controls-${shot.id}/${skin.id}-tablet.png", roborazziOptions = exact)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = RowShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
