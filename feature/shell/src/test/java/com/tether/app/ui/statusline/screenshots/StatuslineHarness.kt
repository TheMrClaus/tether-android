package com.tether.app.ui.statusline.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.delay

/*
 * The statusline boards' screenshot harness — the same conventions as :core:designsystem's
 * (ScreenshotHarness.kt): the web's device classes, goldens checked in under src/test/screenshots,
 * a paused clock advanced 600ms before an exact (changeThreshold 0) capture.
 */

enum class ScreenSize(val id: String) { Phone("phone"), Tablet("tablet") }

const val GoldenDir = "src/test/screenshots"

fun goldenPath(board: String, skin: TetherSkin, size: ScreenSize): String = "$GoldenDir/$board/${skin.id}-${size.id}.png"

fun choiceFor(skin: TetherSkin): ThemeMode = skin.mode

const val CaptureAtMs: Long = 600
const val BoardTag = "board"

fun ComposeContentTestRule.snapBoard(
    board: String,
    skin: TetherSkin,
    size: ScreenSize,
    reducedMotion: Boolean = false,
    captureAtMs: Long = CaptureAtMs,
    beforeCapture: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                val t = LocalTetherTokens.current
                Column(
                    Modifier.testTag(BoardTag).fillMaxWidth().background(t.mineral).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
    }
    beforeCapture()
    mainClock.advanceTimeBy(captureAtMs)
    waitForIdle()
    onNodeWithTag(BoardTag).captureRoboImage(
        goldenPath(board, skin, size),
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** A labelled row of states on a board. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StateRow(caption: String, content: @Composable RowScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, color = t.faint, style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** An interaction source held pressed for the whole capture. */
@Composable
fun heldPress(): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        delay(50)
        source.emit(PressInteraction.Press(Offset.Zero))
    }
    return source
}
