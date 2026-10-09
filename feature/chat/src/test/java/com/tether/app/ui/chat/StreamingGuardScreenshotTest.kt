package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-jtfq: guard goldens of a STREAMING agent message (done = false, caret visible), recorded before the streaming
 * bubble stopped taking an intrinsic-width pass. They pin the geometry that change must not move: a long Latin
 * paragraph above a short Hebrew one (mixed direction), Hebrew first under a right-to-left layout, the tablet layout,
 * and 360 dp at 2.0x text.
 */
private fun streamingOf(text: String): ChatFixtures.Folded = ChatFixtures.fold(
    ev("turn_started", "t1", ts = ChatFixtures.T_STREAM) { put("idempotencyKey", "k-t1") },
    ev("user_message_accepted", "t1", ts = ChatFixtures.T_STREAM) { put("text", "Run the test suite and tell me what fails.") },
    ev("message_started", "t1", ts = ChatFixtures.T_STREAM + 1_000) { put("blockId", "t1:m0") },
    ev("message_delta", "t1", ts = ChatFixtures.T_STREAM + 1_000) { put("blockId", "t1:m0"); put("text", text) },
)

private const val LATIN_LONG =
    "The suite runs in three stages and the first two pass without a single warning, but the third stage stops early " +
        "because a snapshot of the timeline fold no longer matches what the reducer now produces for a message that is " +
        "still being typed, so the remaining checks have not started yet."

private const val HEBREW_SHORT = "שני מבחנים נכשלו עד כה"

private const val HEBREW_FIRST = "המבחנים רצים בשלושה שלבים והשניים הראשונים עוברים בלי אזהרה אחת, אבל השלב השלישי נעצר מוקדם.\n\n" +
    "Two suites fail so far."

private fun ComposeContentTestRule.snapStreamingGuard(
    folded: ChatFixtures.Folded,
    dir: String,
    size: String,
    wellHeight: Dp,
    wellWidth: Dp? = null,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    mainClock.autoAdvance = false
    val listState = LazyListState()
    setContent {
        ChatHost(TetherSkin.Studio, wellHeight, wellWidth) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                ChatTranscript(
                    projection = folded.projection,
                    tree = folded.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = listState,
                )
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$dir/${TetherSkin.Studio.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** A long Latin paragraph, then a short Hebrew one (mixed direction), left-to-right layout. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StreamingBidiScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun streaming() = rule.snapStreamingGuard(streamingOf("$LATIN_LONG\n\n$HEBREW_SHORT"), "chat-streaming-bidi", "phone", WellHeightPhone)
}

/** Hebrew first, under a right-to-left layout direction. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StreamingRtlScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun streaming() = rule.snapStreamingGuard(streamingOf(HEBREW_FIRST), "chat-streaming-rtl", "phone", WellHeightPhone, layoutDirection = LayoutDirection.Rtl)
}

/** The existing streaming content on the tablet layout. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class StreamingTabletScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun streaming() = rule.snapStreamingGuard(ChatFixtures.streaming, "chat-streaming", "tablet", WellHeightTablet, WellWidthTablet)
}

/** The existing streaming content at 360 dp wide and 2.0x text. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 2.0f)
class StreamingFont20ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun streaming() = rule.snapStreamingGuard(ChatFixtures.streaming, "chat-streaming-360-font-2.0x", "phone", WellHeightPhone)
}
