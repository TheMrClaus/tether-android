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
 * `send-row-sending` (faint, spinner) and `send-row-waiting` (warning, alert).
 */
enum class SendShot(val id: String) {
    Sending("send-sending"),
    Waiting("send-waiting"),
    Failed("send-failed"),
    PhotoLost("send-photo-lost"),
    Uploading("send-uploading"),
    UploadingNoSpool("send-uploading-no-spool"),
    UploadFailed("send-upload-failed"),
    LinkAgain("send-link-again"),
    UploadRetryable("send-upload-retryable"),
    /** tth-87eo (AW.5 R4-f): a `Link` row the one-message bound released (2 attachments); phone only. */
    Released("send-released"),
    /** tth-87eo fix round: the row the client really produces when the bound releases a message (reason Expired, "Not delivered"); phone only. */
    ReleasedExpired("send-released-expired"),
    /** One golden only (phone, Studio): the not-kept waiting bubble in the warning tone (§4.1d A.2). */
    WaitingNotKept("send-waiting-not-kept"),
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
    SendShot.WaitingNotKept -> SendBubbles(
        listOf(SendFixtures.pending("k1", "Now rerun only the snapshot suite.", SendStatus.Waiting, keptOnDevice = false)),
        emptyList(),
    ) {}
    SendShot.Failed -> SendBubbles(
        emptyList(),
        listOf(SendFixtures.failed("k9", "Here are the two screenshots.", 2, 2, SendFixtures.THREE_MB)),
    ) {}
    SendShot.Released -> SendBubbles(
        emptyList(),
        listOf(
            SendFixtures.failed("k10", "Here are the two screenshots.", 2, 2, 6.0 * 1024 * 1024, reason = com.tether.app.client.FailedSendReason.Link)
                .copy(released = true),
        ),
    ) {}
    SendShot.ReleasedExpired -> SendBubbles(
        emptyList(),
        listOf(
            SendFixtures.failed("k11", "Here are the two screenshots.", 2, 2, 6.0 * 1024 * 1024, reason = com.tether.app.client.FailedSendReason.Expired)
                .copy(released = true),
        ),
    ) {}
    SendShot.PhotoLost -> SendBubbles(
        emptyList(),
        listOf(SendFixtures.failed("k8", "Here are the two screenshots.", 2, 2, SendFixtures.THREE_MB, reason = com.tether.app.client.FailedSendReason.PhotoLost)),
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
    // A9: a text-only Link bubble offers Send again; a retryable upload failure whose bytes are not held does not.
    SendShot.LinkAgain -> SendBubbles(
        emptyList(),
        listOf(SendFixtures.failed("k5", "Run the snapshot suite once more.", reason = com.tether.app.client.FailedSendReason.Link, canSendAgain = true)),
    ) {}
    SendShot.UploadRetryable -> SendBubbles(
        emptyList(),
        listOf(
            SendFixtures.failed("k4", "Here are the two screenshots.", 2, 2, SendFixtures.THREE_MB, reason = com.tether.app.client.FailedSendReason.Upload)
                .copy(detail = "The server could not take the upload right now.", retryable = true),
        ),
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
    WaitingNotKept("composer-send-row-waiting-not-kept"),
}

fun ComposeContentTestRule.snapSendRow(shot: StripShot, skin: TetherSkin, size: String = "phone", width: Dp? = null, suffix: String = "") {
    mainClock.autoAdvance = false
    val rows = when (shot) {
        StripShot.Sending -> listOf(SendFixtures.pending("k1", "a"), SendFixtures.pending("k2", "b"))
        StripShot.Waiting -> listOf(SendFixtures.pending("k1", "a", SendStatus.Waiting), SendFixtures.pending("k2", "b"))
        StripShot.WaitingNotKept -> listOf(SendFixtures.pending("k1", "a", SendStatus.Waiting, keptOnDevice = false))
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
        fun params(): List<Array<Any>> = SendShot.entries.filter { it != SendShot.WaitingNotKept }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
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
        fun params(): List<Array<Any>> = SendShot.entries.filter { it != SendShot.WaitingNotKept && it != SendShot.Released && it != SendShot.ReleasedExpired }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
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
        fun params(): List<Array<Any>> = SendShot.entries.filter { it != SendShot.WaitingNotKept && it != SendShot.Released && it != SendShot.ReleasedExpired }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
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

    @Test fun waitingNotKept() = rule.snapSendRow(StripShot.WaitingNotKept, skin, suffix = "-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SendBubblesNotKeptScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun waitingNotKept() = rule.snapSends(SendShot.WaitingNotKept, TetherSkin.Studio, "chat-${SendShot.WaitingNotKept.id}", "phone", WellHeightPhone)
}

/** v154: the "Agents are restarting" row above the composer (violet + alert icon + words), one golden per skin. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SupervisorRowScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun down() {
        rule.mainClock.autoAdvance = false
        rule.setContent { ComposerHost(skin) { ComposerSupervisorRow(down = true) } }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/composer-supervisor-row/${skin.id}-phone.png", roborazziOptions = exact)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
