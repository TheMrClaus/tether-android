package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ConsentResult
import com.tether.app.client.InterruptResult
import com.tether.app.client.SendStatus
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.19 visual states (web issue #135, chat-view.tsx 90fbb9f :1566-1606, globals.css
 * :4225-4256), at the foot of the idle transcript: `send-sending` = a text send and an attachment
 * send in flight (dimmed, spinner, "Sending…" / "Sending — 2 images · 3.0 MB"); `send-waiting` =
 * the link down (full strength, alert, "Waiting for link — …"); `send-failed` = a send given up on
 * ("Not delivered (2 images · 3.0 MB)", brick edge, Dismiss). And the composer's send-status row:
 * `send-row-sending` (faint, spinner) and `send-row-waiting` (violet, alert).
 */
enum class SendShot(val id: String) {
    Sending("send-sending"),
    Waiting("send-waiting"),
    Failed("send-failed"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private fun sendsFor(shot: SendShot): SendBubbles = when (shot) {
    SendShot.Sending -> SendBubbles(
        listOf(
            SendFixtures.pending("k1", "Now rerun only the snapshot suite."),
            SendFixtures.pending("k2", "Here are the two screenshots.", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB),
        ),
        emptyList(),
    ) {}
    SendShot.Waiting -> SendBubbles(
        listOf(SendFixtures.pending("k1", "Now rerun only the snapshot suite.", SendStatus.Waiting)),
        emptyList(),
    ) {}
    SendShot.Failed -> SendBubbles(
        emptyList(),
        listOf(SendFixtures.failed("k9", "Here are the two screenshots.", 2, 2, SendFixtures.THREE_MB)),
    ) {}
}

fun ComposeContentTestRule.snapSends(shot: SendShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            ChatTranscript(
                projection = ChatFixtures.idle.projection,
                tree = ChatFixtures.idle.tree,
                showThinking = false,
                onFetchTurns = { _, _ -> },
                zone = ChatFixtures.zone,
                listState = LazyListState(),
                sends = sendsFor(shot),
            )
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exact)
}

fun ComposeContentTestRule.snapSendRow(waiting: Boolean, skin: TetherSkin, name: String) {
    mainClock.autoAdvance = false
    val rows = listOf(
        SendFixtures.pending("k1", "a", if (waiting) SendStatus.Waiting else SendStatus.Sending),
        SendFixtures.pending("k2", "b"),
    )
    setContent {
        ComposerHost(skin) {
            Composer(
                session = ComposerFixtures.session,
                projection = ComposerFixtures.idle.projection,
                controls = SessionControlFixtures.claudeIdleControls,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = { InterruptResult.Sent },
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                controlActions = SessionControlFixtures.Recorder().actions(),
                sendRows = rows,
            )
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SendBubblesPhoneScreenshotTest(private val shot: SendShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sends() = rule.snapSends(shot, skin, "chat-${shot.id}", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SendShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SendBubblesTabletScreenshotTest(private val shot: SendShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sends() = rule.snapSends(shot, skin, "chat-${shot.id}", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SendShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the bubbles (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SendBubblesFontScaleScreenshotTest(private val shot: SendShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sends() = rule.snapSends(shot, skin, "chat-${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SendShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SendRowScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sending() = rule.snapSendRow(waiting = false, skin, "composer-send-row-sending")

    @Test fun waiting() = rule.snapSendRow(waiting = true, skin, "composer-send-row-waiting")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
