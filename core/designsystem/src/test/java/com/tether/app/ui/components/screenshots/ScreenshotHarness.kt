package com.tether.app.ui.components.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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

/** The two device classes the parity program screenshots (PLAN §5.3). */
enum class ScreenSize(val id: String, val qualifiers: String) {
    Phone("phone", "w412dp-h915dp-420dpi"),
    Tablet("tablet", "w1280dp-h800dp-mdpi"),
}

/** Where goldens live: checked in, one PNG per primitive × skin × size. */
const val GoldenDir = "src/test/screenshots"

fun goldenPath(primitive: String, skin: TetherSkin, size: ScreenSize): String = "$GoldenDir/$primitive/${skin.id}-${size.id}.png"

fun choiceFor(skin: TetherSkin): ThemeMode = skin.mode

/**
 * Time the (paused) clock advances before capture: every press/colour transition has settled,
 * ambient motion (spinner, ping) sits at a fixed, reproducible frame.
 */
const val CaptureAtMs: Long = 600

/**
 * Renders [content] as a state board on the skin's `--mineral` page, advances the paused clock to
 * [CaptureAtMs] and captures (record) or compares (verify) the board against its golden.
 * Exact comparison: `verifyRoborazziDebug` fails on any changed pixel.
 */
fun ComposeContentTestRule.snapBoard(
    primitive: String,
    skin: TetherSkin,
    size: ScreenSize,
    reducedMotion: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                val t = LocalTetherTokens.current
                Column(
                    Modifier
                        .testTag(BoardTag)
                        .fillMaxWidth()
                        .background(t.mineral)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onNodeWithTag(BoardTag).captureRoboImage(
        goldenPath(primitive, skin, size),
        roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        ),
    )
}

const val BoardTag = "board"

/** A labelled row of states on a board. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StateRow(caption: String, wrap: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, color = t.faint, style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp))
        // [wrap]: the states flow onto another line when they outgrow the phone width (e.g. at
        // 1.3× font scale), so a board never clips a primitive at its edge. Rows that size a child
        // with `weight` keep a plain Row.
        if (!wrap) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** When a held interaction is emitted (well before [CaptureAtMs], so transitions settle). */
const val HoldAfterMs: Long = 50

/** An interaction source held in a state (pressed / keyboard-focused) for the whole capture. */
@Composable
fun heldInteraction(pressed: Boolean = false, focused: Boolean = false): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        // The interactions flow does not replay: wait until the primitive's collectors are
        // subscribed (they start in the same frame), then hold the state.
        delay(HoldAfterMs)
        if (pressed) source.emit(PressInteraction.Press(Offset.Zero))
        if (focused) source.emit(FocusInteraction.Focus())
    }
    return source
}
