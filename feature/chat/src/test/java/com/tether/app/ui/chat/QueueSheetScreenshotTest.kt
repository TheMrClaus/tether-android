package com.tether.app.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** D-GH-8 (tth-5u90): the queued message's sheet boards, drawn in place (a Dialog is not in a node capture). */
enum class QueueSheetShot(val id: String) { Peer("peer"), Unconfirmed("unconfirmed"), Operator("operator") }

private val longBody = "Please look at the parser rewrite and report back what fails. ".repeat(12)

private fun sheetFor(shot: QueueSheetShot): Triple<QueuedMessage, Boolean, Boolean> = when (shot) {
    QueueSheetShot.Peer -> Triple(
        QueuedMessage(
            queueId = "p", text = "Message from session disaster52 (s-1): $longBody", origin = JsonPrimitive("system"),
            noticeKind = JsonPrimitive("peer"), fromSessionId = "s-1", fromSessionName = "disaster52",
        ),
        false, false,
    )
    QueueSheetShot.Unconfirmed -> Triple(QueuedMessage(queueId = "u", text = "Then fix the snapshot test.", held = true, heldReason = "steer_uncertain"), false, false)
    QueueSheetShot.Operator -> Triple(QueuedMessage(queueId = "o", text = "Then fix the snapshot test and rerun only that suite."), true, false)
}

fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapQueueSheet(shot: QueueSheetShot, skin: TetherSkin, size: String, width: Dp? = null) {
    mainClock.autoAdvance = false
    val (message, interrupt, _) = sheetFor(shot)
    val summary = QueueWait.queueRowSummary(message, steer = false)
    setContent {
        ComposerHost(skin, width) {
            QueuedMessageSheetSurface(
                message = message,
                summary = summary,
                waitLabel = queueWaitLabel(message, handedOff = false, handoverWaitLabel = null, steerCapable = false),
                offerInterruptAndSend = interrupt,
            )
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(ComposerTag).captureRoboImage(
        "src/test/screenshots/queue-sheet-${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class QueueSheetPhoneScreenshotTest(private val shot: QueueSheetShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapQueueSheet(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = QueueSheetShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class QueueSheetTabletScreenshotTest(private val shot: QueueSheetShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapQueueSheet(shot, skin, "tablet", WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = QueueSheetShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
