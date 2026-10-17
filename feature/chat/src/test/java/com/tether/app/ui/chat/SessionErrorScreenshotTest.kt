package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * W50: the session-error rows (the web's `chat-outcome-error` notes, "at" stamp muted). `session-errors` = two turns
 * with three errors interleaved by ts (one from the day before wears the date prefix); `session-errors-only` = a
 * session with no turn at all (the empty state is not shown). Hand-built: the pre-R1 fold never fills sessionErrors.
 * The stamp clock is pinned next to the zone.
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

fun ComposeContentTestRule.snapSessionErrors(skin: TetherSkin, name: String, size: String, wellHeight: Dp, only: Boolean) {
    mainClock.autoAdvance = false
    val fixture = if (only) SessionErrorFixtures.errorOnly else SessionErrorFixtures.interleaved
    setContent {
        ChatHost(skin, wellHeight, null) {
            CompositionLocalProvider(LocalStampClock provides StampClock({ SessionErrorFixtures.NOW }, { Locale.US })) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                    showTimeline = false,
                )
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionErrorPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun interleaved() = rule.snapSessionErrors(skin, "session-errors", "phone", WellHeightPhone, only = false)

    @Test fun errorOnly() = rule.snapSessionErrors(skin, "session-errors-only", "phone", WellHeightPhone, only = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
