package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.InterruptResult
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.7 visual states (both Studio skins at phone size, the desktop layout, 1.3× font):
 * `chat-errors` = a turn that failed (its outcome row, the engine's words cleaned) and the
 * session's `lastError` row after the turns; `interrupt-busy` = the busy deck's Queue + Interrupt at
 * the desktop width (the web's `streaming` frame; the phone deck is `composer-busy`);
 * `interrupt-refused` = the busy deck after a tap its turn had outlived (the refusal in words);
 * `end-session-confirm` = the End session confirmation in the web's words.
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

fun ComposeContentTestRule.snapChatErrors(skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = InterruptErrorFixtures.errors
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
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
    mainClock.advanceTimeBy(700)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exact)
}

fun ComposeContentTestRule.snapInterruptRefused(skin: TetherSkin, name: String) {
    mainClock.autoAdvance = false
    setContent {
        ComposerHost(skin) {
            Composer(
                session = ComposerFixtures.session,
                projection = ComposerFixtures.busy.projection,
                controls = SessionControlFixtures.claudeIdleControls,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = { InterruptResult.NotCurrentTurn },
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                controlActions = SessionControlFixtures.Recorder().actions(),
            )
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
    mainClock.advanceTimeBy(300)
    waitForIdle()
    onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapEndConfirm(skin: TetherSkin, name: String) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {
                EndSessionDialog(sessionName = "Deploy preview", identity = "s1" to TEST_ORIGIN, endable = true, onConfirm = {}, onCancel = {})
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InterruptErrorPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun chatErrors() = rule.snapChatErrors(skin, "chat-errors", "phone", WellHeightPhone)

    @Test fun interruptRefused() = rule.snapInterruptRefused(skin, "interrupt-refused")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class EndSessionConfirmScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapEndConfirm(skin, "end-session-confirm")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** The web's desktop layout (1280×800): the transcript column and the composer at 950dp. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class InterruptErrorTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun chatErrors() = rule.snapChatErrors(skin, "chat-errors", "tablet", WellHeightTablet, WellWidthTablet)

    @Test fun interruptBusy() = rule.snapComposer(ComposerShot.Busy, skin, "interrupt-busy", "tablet", WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** PLAN §4: 1.3× font scale (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class InterruptErrorFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun chatErrors() = rule.snapChatErrors(skin, "chat-errors-font-1.3x", "phone", WellHeightPhone)

    @Test fun interruptRefused() = rule.snapInterruptRefused(skin, "interrupt-refused-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/** PLAN §4: the End session confirmation at 1.3× (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class EndSessionConfirmFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapEndConfirm(skin, "end-session-confirm-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
