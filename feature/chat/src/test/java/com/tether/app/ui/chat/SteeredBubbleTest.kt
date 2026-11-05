package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.chat.ComposerFixtures.QueueSpec
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec §1 V6: a message delivered INTO the running turn says `Sent during the turn`; a turn-end delivery has no stamp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SteeredBubbleBehaviourTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aSteeredUserBlockCarriesTheStampAndAnOrdinaryOneDoesNot() {
        rule.setContent {
            ComposerHost(TetherSkin.Studio) {
                androidx.compose.foundation.layout.Column {
                    UserBubble(TurnBlock(blockId = "user:t1:q1", kind = "user_message", text = "Also run lint.", steered = true, queueId = "q1"))
                    UserBubble(TurnBlock(blockId = "user:t1", kind = "user_message", text = "Run the tests."))
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText(STEERED_STAMP).assertCountEquals(1)
        rule.onAllNodesWithTag(STEERED_STAMP_TAG).assertCountEquals(1)
        rule.onNodeWithText("Also run lint.").assertExists()
        rule.onNodeWithText("Run the tests.").assertExists()
    }
}

private val exactSteer = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

fun ComposeContentTestRule.snapSteered(skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = ComposerFixtures.delivery(tools = 0, steered = listOf("Also run lint when the tests pass."))
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            ChatTranscript(
                projection = fixture.projection,
                tree = fixture.tree,
                showThinking = false,
                onFetchTurns = { _, _ -> },
                zone = ChatFixtures.zone,
                listState = LazyListState(),
            )
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    onNodeWithTag(STEERED_STAMP_TAG).assertExists()
    onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exactSteer)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SteeredBubblePhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun bubble() = rule.snapSteered(skin, "chat-delivered-steer", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SteeredBubbleTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun bubble() = rule.snapSteered(skin, "chat-delivered-steer", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SteeredBubbleFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun bubble() = rule.snapSteered(skin, "chat-delivered-steer-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
