package com.tether.app.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.QueueRowSteer
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** v165 boards at the phone size (412 x 915 dp): the strip with Steer on every waiting row, in each mode, and the sheet's two footers. */
enum class QueueSteerShot(val id: String) { StripAuto("strip-auto"), StripHold("strip-hold"), SheetSteer("sheet-steer"), SheetInterrupt("sheet-interrupt"), SheetRefused("sheet-refused") }

private val steerRows = listOf(
    QueuedMessage(queueId = "a", text = "Then fix the snapshot test and rerun only that suite."),
    QueuedMessage(queueId = "b", text = "Also look at the flaky upload retry", attachmentIds = listOf("f1", "f2")),
    QueuedMessage(queueId = "c", text = "Rename the helper to match the others"),
)

fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapQueueSteer(shot: QueueSteerShot, skin: TetherSkin) {
    mainClock.autoAdvance = false
    setContent {
        ComposerHost(skin) {
            when (shot) {
                QueueSteerShot.StripAuto, QueueSteerShot.StripHold -> {
                    val hold = shot == QueueSteerShot.StripHold
                    QueuedMessages(
                        queued = steerRows,
                        onSave = { _, _ -> },
                        onRemove = {},
                        sessionId = "s1",
                        interruptTurnId = "t1",
                        onInterruptNow = {},
                        interruptLock = null,
                        steerCapable = true,
                        steerEnabled = true,
                        steerHold = hold,
                        steerStates = if (hold) emptyMap() else mapOf("c" to QueueRowSteer.Refused),
                    )
                }
                QueueSteerShot.SheetRefused -> {
                    val message = steerRows[2]
                    QueuedMessageSheetSurface(
                        message = message,
                        summary = QueueWait.queueRowSummary(message, steer = true, steerRefused = true),
                        waitLabel = queueWaitLabel(message, handedOff = false, handoverWaitLabel = null, steerCapable = true, steerRefused = true),
                        offerInterruptAndSend = true,
                        rowAction = QueueWait.rowAction(true, message, refused = true),
                    )
                }
                QueueSteerShot.SheetSteer, QueueSteerShot.SheetInterrupt -> {
                    val steer = shot == QueueSteerShot.SheetSteer
                    val message = if (steer) steerRows[0] else steerRows[1]
                    QueuedMessageSheetSurface(
                        message = message,
                        summary = QueueWait.queueRowSummary(message, steer = steer),
                        waitLabel = queueWaitLabel(message, handedOff = false, handoverWaitLabel = null, steerCapable = true),
                        offerInterruptAndSend = true,
                        rowAction = QueueWait.rowAction(true, message),
                    )
                }
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(ComposerTag).captureRoboImage(
        "src/test/screenshots/queue-steer-${shot.id}/${skin.id}-phone.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class QueueSteerPhoneScreenshotTest(private val shot: QueueSteerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapQueueSteer(shot, skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = QueueSteerShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
