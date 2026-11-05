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
    PhotoLost("send-photo-lost"),
    Uploading("send-uploading"),
    UploadingNoSpool("send-uploading-no-spool"),
    UploadFailed("send-upload-failed"),
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
    SendShot.PhotoLost -> SendBubbles(
        emptyList(),
        listOf(SendFixtures.failed("k8", "Here are the two screenshots.", reason = com.tether.app.client.FailedSendReason.PhotoLost)),
    ) {}
    // ta-delivery A2 (fix1 B2): "Uploading N%" while the photos go up, and "Not sent — the upload failed (<server reason>)".
    SendShot.Uploading -> SendBubbles(
        listOf(
            SendFixtures.pending("k7", "Here are the two screenshots.", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB)
                .copy(uploadPercent = 42, keptOnDevice = true),
        ),
        emptyList(),
    ) {}
    SendShot.UploadingNoSpool -> SendBubbles(
        listOf(
            SendFixtures.pending("k7", "Here are the two screenshots.", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB, percent = 42, keptOnDevice = false),
        ),
        emptyList(),
    ) {}
    SendShot.UploadFailed -> SendBubbles(
        emptyList(),
        listOf(
            SendFixtures.failed("k6", "Here are the two screenshots.", 2, 2, SendFixtures.THREE_MB, reason = com.tether.app.client.FailedSendReason.Upload)
                .copy(detail = "An attachment is limited to 9 MiB."),
        ),
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

enum class StripShot(val id: String) {
    Sending("composer-send-row-sending"),
    Waiting("composer-send-row-waiting"),
    Uploading("composer-send-row-uploading"),
    UploadingNoSpool("composer-send-row-uploading-no-spool"),
}

fun ComposeContentTestRule.snapSendRow(shot: StripShot, skin: TetherSkin, size: String = "phone", width: Dp? = null, suffix: String = "") {
    mainClock.autoAdvance = false
    val rows = when (shot) {
        StripShot.Sending -> listOf(SendFixtures.pending("k1", "a"), SendFixtures.pending("k2", "b"))
        StripShot.Waiting -> listOf(SendFixtures.pending("k1", "a", SendStatus.Waiting), SendFixtures.pending("k2", "b"))
        StripShot.Uploading -> listOf(SendFixtures.pending("k1", "a", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB, percent = 42))
        StripShot.UploadingNoSpool ->
            listOf(SendFixtures.pending("k1", "a", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB, percent = 42, keptOnDevice = false))
    }
    setContent {
        ComposerHost(skin, width) {
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
    onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/${shot.id}/${skin.id}-$size$suffix.png", roborazziOptions = exact)
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

    @Test fun sending() = rule.snapSendRow(StripShot.Sending, skin)

    @Test fun waiting() = rule.snapSendRow(StripShot.Waiting, skin)

    @Test fun uploading() = rule.snapSendRow(StripShot.Uploading, skin)

    @Test fun uploadingNoSpool() = rule.snapSendRow(StripShot.UploadingNoSpool, skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SendRowTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun waiting() = rule.snapSendRow(StripShot.Waiting, skin, "tablet", WellWidthTablet)

    @Test fun uploading() = rule.snapSendRow(StripShot.Uploading, skin, "tablet", WellWidthTablet)

    @Test fun uploadingNoSpool() = rule.snapSendRow(StripShot.UploadingNoSpool, skin, "tablet", WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SendRowFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun waiting() = rule.snapSendRow(StripShot.Waiting, skin, suffix = "-font-1.3x")

    @Test fun uploading() = rule.snapSendRow(StripShot.Uploading, skin, suffix = "-font-1.3x")

    @Test fun uploadingNoSpool() = rule.snapSendRow(StripShot.UploadingNoSpool, skin, suffix = "-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
