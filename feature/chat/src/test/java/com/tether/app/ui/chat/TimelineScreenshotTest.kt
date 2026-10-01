package com.tether.app.ui.chat

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.5 goldens. `rest` is the web's `conversation-timeline` scenario (five prompts, pinned to the
 * newest: the scale line, resting ticks, the violet needle). `scrub` holds a touch on the fourth
 * mark (the bubble: time · n/N, prompt, reply; the magnified dashes). `saved` is a saved copy's
 * prompt still waiting for its reply.
 */
enum class TimelineShot(val id: String) { Rest("rest"), Scrub("scrub"), Saved("saved") }

fun ComposeContentTestRule.snapTimeline(shot: TimelineShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    val fixture = if (shot == TimelineShot.Saved) TimelineFixtures.edges else TimelineFixtures.seeded
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            ChatTranscript(
                projection = fixture.projection,
                tree = fixture.tree,
                showThinking = false,
                onFetchTurns = { _, _ -> },
                zone = ChatFixtures.zone,
                liveCopy = shot != TimelineShot.Saved,
            )
        }
    }
    waitForIdle()
    when (shot) {
        TimelineShot.Rest -> Unit
        TimelineShot.Scrub -> onNodeWithTag(TIMELINE_TAG).performTouchInput { down(slotOffset(3, 5)) }
        TimelineShot.Saved -> onNodeWithTag(TIMELINE_TAG).performTouchInput { down(slotOffset(2, 3)) }
    }
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TimelinePhoneScreenshotTest(private val shot: TimelineShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun timeline() = rule.snapTimeline(shot, skin, "timeline-${shot.id}", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TimelineShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web's desktop layout: the rail docks left and its bubble opens to the right. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TimelineTabletScreenshotTest(private val shot: TimelineShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun timeline() = rule.snapTimeline(shot, skin, "timeline-${shot.id}", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(TimelineShot.Rest, TimelineShot.Scrub).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3x font scale keeps the bubble readable and on screen (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class TimelineFontScaleScreenshotTest(private val shot: TimelineShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun timeline() = rule.snapTimeline(shot, skin, "timeline-${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(TimelineShot.Scrub, TimelineShot.Saved).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
