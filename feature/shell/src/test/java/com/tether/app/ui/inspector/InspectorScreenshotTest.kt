package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.SessionView
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
 * T9.1: the phone telemetry sheet with the inspector body — populated (every section, its
 * disclosures opened) and sparse (a session that has not answered yet) — in both Studio skins and
 * Machine. The sheet is drawn tall enough to hold the whole body (the device scrolls it).
 */
enum class InspectorSheetShot(val id: String, val height: Int) { Populated("populated", 3_300), Sparse("sparse", 700) }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h3600dp-420dpi")
class InspectorScreenshotTest(private val shot: InspectorSheetShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() {
        val (model, state) = when (shot) {
            InspectorSheetShot.Populated -> InspectorBoards.fullModel to InspectorBoards.fullState
            InspectorSheetShot.Sparse -> InspectorBoards.sparseModel to null as SessionView?
        }
        rule.snapBoard(
            "inspector-sheet-${shot.id}",
            skin,
            ScreenSize.Phone,
            reducedMotion = true,
            beforeCapture = {
                if (shot == InspectorSheetShot.Populated) {
                    for (summary in listOf("Runtime details", "PER-MODEL BREAKDOWN (2)", "NAMES", "Changes")) {
                        rule.onNodeWithText(summary).performClick()
                        rule.mainClock.advanceTimeBy(400)
                        rule.waitForIdle()
                    }
                }
            },
        ) {
            Column(Modifier.width(380.dp).height(shot.height.dp)) {
                TelemetrySheet(onClose = {}) {
                    Inspector(model, state, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = InspectorSheetShot.entries.flatMap { s ->
            listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(s, it) }
        }
    }
}
