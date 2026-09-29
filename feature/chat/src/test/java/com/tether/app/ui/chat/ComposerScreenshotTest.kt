package com.tether.app.ui.chat

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
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
 * T7.1 composer states (DoD: 6 skins at phone size, the expanded layout where it differs, 1.3×):
 * `idle` = the web's idle-session deck (empty well, the disabled Send key); `busy` = the web's
 * streaming deck (the run row above the well, the busy placeholder, Interrupt alone because an
 * empty Queue key is hidden on a phone); `draft` = a three-line draft, focused (the well's violet
 * edge and focus glow, the auto-grown field, Send enabled); `queue` = two queued messages (the
 * second waiting for a tool boundary, with "Interrupt now") and a typed draft, so Queue shows.
 */
enum class ComposerShot(val id: String) {
    Idle("idle"),
    Busy("busy"),
    Draft("draft"),
    Queue("queue"),
}

fun ComposeContentTestRule.snapComposer(shot: ComposerShot, skin: TetherSkin, name: String, size: String, width: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = when (shot) {
        ComposerShot.Idle, ComposerShot.Draft -> ComposerFixtures.idle
        ComposerShot.Busy -> ComposerFixtures.busy
        ComposerShot.Queue -> ComposerFixtures.queued
    }
    val draft = when (shot) {
        ComposerShot.Draft -> ComposerFixtures.DRAFT
        ComposerShot.Queue -> "Then summarize."
        else -> ""
    }
    setContent {
        ComposerHost(skin, width) {
            Composer(
                session = ComposerFixtures.session,
                projection = fixture.projection,
                // T7.2: the web idle-session's controls (Opus (1M context), Manual) on a live session.
                controls = SessionControlFixtures.claudeIdleControls,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = {},
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                initialDraft = draft,
                controlActions = SessionControlFixtures.Recorder().actions(),
            )
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    if (shot == ComposerShot.Draft) {
        onNodeWithContentDescription("Message the agent").requestFocus()
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    onNodeWithTag(ComposerTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ComposerPhoneScreenshotTest(private val shot: ComposerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun composer() = rule.snapComposer(shot, skin, "composer-${shot.id}", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ComposerShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web desktop composer (1280×800): the chat frame's 950dp column. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ComposerTabletScreenshotTest(private val shot: ComposerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun composer() = rule.snapComposer(shot, skin, "composer-${shot.id}", "tablet", WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ComposerShot.Idle, ComposerShot.Queue).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (instrument + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ComposerFontScaleScreenshotTest(private val shot: ComposerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun composer() = rule.snapComposer(shot, skin, "composer-${shot.id}-font-1.3x", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ComposerShot.Draft, ComposerShot.Queue).flatMap { s ->
            listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
