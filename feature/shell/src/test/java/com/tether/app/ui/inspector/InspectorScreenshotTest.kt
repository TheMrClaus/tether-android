package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.tether.app.ui.inspector.InspectorBoards.Reference.Variant
import com.tether.app.ui.shell.TelemetrySheet
import com.tether.app.ui.statusline.screenshots.ScreenSize
import com.tether.app.ui.statusline.screenshots.snapBoard
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.10: the telemetry panel on the web reference's own fixture (tether 90fbb9f
 * tests/telemetry-panel.spec.ts; its captures are the design/telemetry-panel PNGs), in the spec's
 * states — full, empty, attention, a subagent selected — plus the full state with every
 * disclosure opened, in both Studio skins.
 *
 * Phone: the telemetry sheet at the web's 390-wide viewport (compare phone-sheet-full-studio(-dark),
 * phone-attention-studio, phone-empty-studio-dark). Tablet (1280dp): the sheet as the floating card the web
 * shows between 48rem and 100rem (compare the desktop column captures, same body). The sheet
 * is drawn tall enough to hold the whole body (the device scrolls it).
 */
enum class TelemetryPanelShot(val id: String, val variant: Variant, val phoneHeight: Int, val tabletHeight: Int, val open: Boolean = false) {
    Full("full", Variant.Full, 1_640, 1_700),
    Open("open", Variant.Full, 2_900, 3_200, open = true),
    Empty("empty", Variant.Empty, 640, 660),
    Attention("attention", Variant.Attention, 1_760, 1_840),
    Selected("selected", Variant.Selected, 2_060, 2_140),
}

internal fun ComposeContentTestRule.snapTelemetryPanel(shot: TelemetryPanelShot, skin: TetherSkin, size: ScreenSize, board: String) {
    val model = InspectorBoards.Reference.model(shot.variant)
    val state = InspectorBoards.Reference.stateOf(shot.variant)
    val tablet = size == ScreenSize.Tablet
    snapBoard(
        board,
        skin,
        size,
        reducedMotion = true,
        beforeCapture = {
            if (shot.open) {
                // Let each opened disclosure compose before the next lookup.
                mainClock.autoAdvance = true
                for (summary in listOf("Show 4 more", "By model, last turn", "RUNTIME")) {
                    onNodeWithText(summary).performSemanticsAction(SemanticsActions.OnClick)
                    mainClock.advanceTimeBy(400)
                    waitForIdle()
                }
                onNodeWithTag(InspectorTags.Names, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                mainClock.autoAdvance = false
            }
        },
    ) {
        Column(Modifier.width(if (tablet) 368.dp else 390.dp).height((if (tablet) shot.tabletHeight else shot.phoneHeight).dp)) {
            TelemetrySheet(onClose = {}, floating = tablet) {
                Inspector(model, state, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
            }
        }
    }
}

private fun panelParams(skins: List<TetherSkin> = listOf(TetherSkin.Studio, TetherSkin.StudioDark), shots: List<TelemetryPanelShot> = TelemetryPanelShot.entries): List<Array<Any>> =
    shots.flatMap { s -> skins.map { arrayOf<Any>(s, it) } }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w422dp-h3100dp-420dpi")
class TelemetryPanelPhoneScreenshotTest(private val shot: TelemetryPanelShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapTelemetryPanel(shot, skin, ScreenSize.Phone, "telemetry-panel-${shot.id}")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = panelParams()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h3400dp-mdpi")
class TelemetryPanelTabletScreenshotTest(private val shot: TelemetryPanelShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapTelemetryPanel(shot, skin, ScreenSize.Tablet, "telemetry-panel-${shot.id}")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = panelParams()
    }
}

/** 1.3x font: the phone sheet in the states with the most text, both skins. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w422dp-h3100dp-420dpi", fontScale = 1.3f)
class TelemetryPanelFontScaleScreenshotTest(private val shot: TelemetryPanelShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapTelemetryPanel(shot, skin, ScreenSize.Phone, "telemetry-panel-${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = panelParams(shots = listOf(TelemetryPanelShot.Full, TelemetryPanelShot.Attention, TelemetryPanelShot.Selected))
    }
}
