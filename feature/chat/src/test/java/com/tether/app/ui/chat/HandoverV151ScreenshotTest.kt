package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.Handover
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W34 goldens (A-spec section 5 #1-#3, #15-#22), Studio and StudioDark. The W33 goldens are untouched (they ARE the
 * protocol-150 path, which is unchanged); a name the W33 set owns carries a `-v151` suffix. Phone = 412x915 @420dpi,
 * expanded = 1280x800.
 */
enum class HandoverV151Shot(val id: String, val phone: Boolean, val expanded: Boolean) {
    Dialog("handover-dialog", true, true),
    DialogFocus("handover-dialog-focus", true, true),
    DialogNoEngine("handover-dialog-no-engine", true, false),
    StripRequestedQueued("handover-strip-requested-queued", true, false),
    StripRequestedWriting("handover-strip-requested-writing", true, false),
    StripScheduled("handover-strip-scheduled", true, false),
    StripWaitingBg("handover-strip-waiting-bg", true, true),
    StripStopping("handover-strip-stopping", true, false),
    NoticeStopped("handover-notice-stopped", true, false),
    NoticeCancelled("handover-notice-cancelled-v151", true, false),
    RefusedAttach("handover-composer-refused-attach", true, false),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private val stoppedNotice: ChatFixtures.Folded by lazy { notice("handover_operator_stopped") }
private val cancelledNotice: ChatFixtures.Folded get() = HandoverUiFixtures.cancelledNotice

private fun notice(code: String): ChatFixtures.Folded {
    val notice = com.tether.app.protocol.reduce.ev("provider_notice", "t1", ts = HandoverUiFixtures.TS) {
        put("noticeId", "n-$code"); put("level", "info"); put("code", code); put("message", "raw server words that are never shown")
    }
    return ChatFixtures.fold(
        com.tether.app.protocol.reduce.ev("turn_started", "t1", ts = HandoverUiFixtures.TS) { put("idempotencyKey", "k-t1") },
        com.tether.app.protocol.reduce.ev("user_message_accepted", "t1", ts = HandoverUiFixtures.TS) { put("text", "Hand this over.") },
        notice,
        com.tether.app.protocol.reduce.ev("message_started", "t1", ts = HandoverUiFixtures.TS) { put("blockId", "t1:m0") },
        com.tether.app.protocol.reduce.ev("message_delta", "t1", ts = HandoverUiFixtures.TS) { put("blockId", "t1:m0"); put("text", "Carrying on here.") },
        com.tether.app.protocol.reduce.ev("message_completed", "t1", ts = HandoverUiFixtures.TS) { put("blockId", "t1:m0"); put("text", "Carrying on here.") },
        com.tether.app.protocol.reduce.ev("turn_end", "t1", ts = HandoverUiFixtures.TS) { put("outcome", "ok") },
    )
}

private fun AndroidComposeTestRule<*, *>.snapV151(shot: HandoverV151Shot, skin: TetherSkin, size: String) {
    mainClock.autoAdvance = false
    val staged = listOf(StagedAttachment(1, Attachment("build-output.log", "text/plain", "aGVsbG8="), 18_432))
    fun composer(stage: HandoverV151Fixtures.Stage, attachments: ComposerAttachments = ComposerAttachments.Unavailable, draft: String? = null) = setContent {
        ComposerHost(skin) {
            Composer(
                session = stage.session,
                projection = stage.folded.projection,
                controls = null,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> false },
                onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                handoverUnderWay = true,
                handoverStage = stage.view,
                handoverV151 = true,
                attachments = attachments,
                initialDraft = draft,
            )
        }
    }
    fun transcript(folded: ChatFixtures.Folded) = setContent {
        ChatHost(skin) {
            ChatTranscript(
                projection = folded.projection, tree = folded.tree, showThinking = false, onFetchTurns = { _, _ -> },
                zone = ChatFixtures.zone, listState = LazyListState(), showTimeline = false, richCodex = true, handoverV151 = true,
            )
        }
    }
    when (shot) {
        HandoverV151Shot.Dialog, HandoverV151Shot.DialogFocus, HandoverV151Shot.DialogNoEngine -> setContent {
            TetherTheme(choiceFor(skin)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    HandOverDialog("s1", eligible = true, endable = true, onConfirm = {}, onCancel = {}, v151 = true, onChangeSettings = { false })
                }
            }
        }
        HandoverV151Shot.StripRequestedQueued -> composer(HandoverV151Fixtures.requestedQueued)
        HandoverV151Shot.StripRequestedWriting -> composer(HandoverV151Fixtures.requestedWriting)
        HandoverV151Shot.StripScheduled -> composer(HandoverV151Fixtures.scheduled)
        HandoverV151Shot.StripWaitingBg -> composer(HandoverV151Fixtures.waitingBackground, draft = "Carry on with the parser rewrite")
        HandoverV151Shot.StripStopping -> composer(HandoverV151Fixtures.stopping)
        HandoverV151Shot.RefusedAttach -> composer(
            HandoverV151Fixtures.waitingBackground,
            ComposerAttachments(staged, { emptyList() }, {}, { _, _ -> AttachmentSendResult.Sent }),
            "Carry on with the parser rewrite",
        )
        HandoverV151Shot.NoticeStopped -> transcript(stoppedNotice)
        HandoverV151Shot.NoticeCancelled -> transcript(cancelledNotice)
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    if (shot == HandoverV151Shot.DialogNoEngine) {
        onNodeWithTag(HANDOVER_CHANGE_TAG).performClick()
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }
    if (shot == HandoverV151Shot.DialogFocus) {
        // A keyboard user: Tab to Change settings…, so keyboard input mode shows the ring there.
        onNodeWithTag(HANDOVER_CANCEL_TAG).performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.Tab)
            keyUp(androidx.compose.ui.input.key.Key.Tab)
        }
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }
    if (shot == HandoverV151Shot.RefusedAttach) {
        onNodeWithContentDescription(Handover.QUEUE_ARIA).performClick()
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }
    val path = "src/test/screenshots/${shot.id}/${skin.id}-$size.png"
    when (shot) {
        HandoverV151Shot.Dialog, HandoverV151Shot.DialogFocus, HandoverV151Shot.DialogNoEngine -> captureScreenRoboImage(path, roborazziOptions = exact)
        HandoverV151Shot.NoticeStopped, HandoverV151Shot.NoticeCancelled -> onNodeWithTag(WellTag).captureRoboImage(path, roborazziOptions = exact)
        else -> onNodeWithTag(ComposerTag).captureRoboImage(path, roborazziOptions = exact)
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverV151PhoneScreenshotTest(private val shot: HandoverV151Shot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun golden() = rule.snapV151(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = HandoverV151Shot.entries.filter { it.phone }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class HandoverV151ExpandedScreenshotTest(private val shot: HandoverV151Shot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun golden() = rule.snapV151(shot, skin, "expanded")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = HandoverV151Shot.entries.filter { it.expanded }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
