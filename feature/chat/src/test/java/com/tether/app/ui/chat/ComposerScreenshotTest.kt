package com.tether.app.ui.chat

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.chat.ComposerFixtures.QueueSpec
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.1 composer states (DoD: both Studio skins at phone size, the expanded layout where it differs, 1.3×):
 * `idle` = the web's idle-session deck (empty well, the disabled Send key); `busy` = the web's
 * streaming deck (the run row above the well, the busy placeholder, Interrupt alone because an
 * empty Queue key is hidden on a phone); `draft` = a three-line draft, focused (the well's violet
 * edge and focus glow, the auto-grown field, Send enabled); `queue` = two queued messages (the
 * second a legacy next-call row, both V4, the first with "Interrupt and send") and a typed draft, so Queue shows.
 * Delivery (spec §1, §4.1a C, §4.2): `queue-steer` = V5 rows (the engine can steer); `queue-steer-attachments` = the same engine, a row with attachments (V4); `held-open-failed`,
 * `held-steer-uncertain`, `held-attachment-missing` = a held row beside a live one; `interrupt-send-confirm` = the next
 * row's "Interrupt and send" tapped (heading, the cost sentence + the steered line, Keep queued + the danger commit);
 * `stop-confirm` = the composer's Stop tapped with live work (Keep running + the cost); `stop-confirm-steered` = the same with one steered message (Claude: the §4.1a line); `stop-confirm-spared` = Claude persistent on a >= 155 server (background tasks keep running); `held-handover` = a held handover instruction row.
 */
enum class ComposerShot(val id: String) {
    Idle("idle"),
    Busy("busy"),
    Draft("draft"),
    Queue("queue"),
    QueueSteer("queue-steer"),
    QueueSteerAttachments("queue-steer-attachments"),
    HeldOpenFailed("held-open-failed"),
    HeldSteerUncertain("held-steer-uncertain"),
    HeldAttachmentMissing("held-attachment-missing"),
    InterruptSendConfirm("interrupt-send-confirm"),
    StopConfirm("stop-confirm"),
    HeldHandover("held-handover"),
    StopConfirmSteered("stop-confirm-steered"),
    StopConfirmSpared("stop-confirm-spared"),
}

fun ComposeContentTestRule.snapComposer(shot: ComposerShot, skin: TetherSkin, name: String, size: String, width: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = when (shot) {
        ComposerShot.Idle, ComposerShot.Draft -> ComposerFixtures.idle
        ComposerShot.Busy -> ComposerFixtures.busy
        ComposerShot.Queue -> ComposerFixtures.queued
        ComposerShot.StopConfirm -> ComposerFixtures.deferred()
        ComposerShot.QueueSteer -> ComposerFixtures.delivery(QueueSpec("a", "Then fix the snapshot test and rerun only that suite."), QueueSpec("b", "Also bump the changelog."))
        ComposerShot.QueueSteerAttachments -> ComposerFixtures.delivery(QueueSpec("a", "Here are the screenshots.", attachmentIds = listOf("att-1")))
        ComposerShot.HeldOpenFailed -> ComposerFixtures.delivery(QueueSpec("h", "Then fix the snapshot test.", held = true, reason = "open_failed"), QueueSpec("b", "Also bump the changelog."))
        ComposerShot.HeldSteerUncertain -> ComposerFixtures.delivery(QueueSpec("h", "Then fix the snapshot test.", held = true, reason = "steer_uncertain"), QueueSpec("b", "Also bump the changelog."))
        ComposerShot.HeldAttachmentMissing -> ComposerFixtures.delivery(QueueSpec("h", "Here are the screenshots.", held = true, reason = "attachment_missing"))
        // Spec §4.1a C: the instruction row's hint. (A held PEER row is not listed in the composer: no golden, see the receipt.)
        ComposerShot.HeldHandover -> ComposerFixtures.delivery(
            QueueSpec("h", "Hand over to a fresh session.", held = true, origin = "system", kind = "handover-request"),
            QueueSpec("b", "Also bump the changelog."),
        )
        // Spec §4.1a A: Claude, one message already sent into the turn, live work -> the Stop panel names it.
        ComposerShot.StopConfirmSteered -> ComposerFixtures.delivery(tools = 1, background = 3, steered = listOf("Also run lint."))
        // Spec §4.1c A: Claude persistent on a >= 155 server: the tasks are spared, the tool call still opens the Stop panel.
        ComposerShot.StopConfirmSpared -> ComposerFixtures.delivery(tools = 1, background = 3)
        ComposerShot.InterruptSendConfirm -> ComposerFixtures.delivery(
            QueueSpec("a", "Then fix the snapshot test and rerun only that suite."),
            QueueSpec("b", "Also bump the changelog."),
            tools = 1, background = 3, steered = listOf("Also run lint."),
        )
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
                onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                backgroundSpared = shot == ComposerShot.StopConfirmSpared,
                steerCapableEngine = shot == ComposerShot.QueueSteer || shot == ComposerShot.QueueSteerAttachments,
                initialDraft = draft,
                controlActions = SessionControlFixtures.Recorder().actions(),
                tree = fixture.tree,
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
    val tap = when (shot) {
        ComposerShot.InterruptSendConfirm -> QUEUE_INTERRUPT_TAG
        ComposerShot.StopConfirm, ComposerShot.StopConfirmSteered, ComposerShot.StopConfirmSpared -> INTERRUPT_KEY_TAG
        else -> null
    }
    if (tap != null) {
        onNodeWithTag(tap).performClick()
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
        fun params(): List<Array<Any>> = listOf(ComposerShot.Idle, ComposerShot.Queue, ComposerShot.QueueSteer, ComposerShot.QueueSteerAttachments, ComposerShot.HeldOpenFailed, ComposerShot.HeldSteerUncertain, ComposerShot.HeldAttachmentMissing, ComposerShot.InterruptSendConfirm, ComposerShot.StopConfirm, ComposerShot.HeldHandover, ComposerShot.StopConfirmSteered, ComposerShot.StopConfirmSpared).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ComposerFontScaleScreenshotTest(private val shot: ComposerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun composer() = rule.snapComposer(shot, skin, "composer-${shot.id}-font-1.3x", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ComposerShot.Draft, ComposerShot.Queue, ComposerShot.QueueSteer, ComposerShot.QueueSteerAttachments, ComposerShot.HeldOpenFailed, ComposerShot.HeldSteerUncertain, ComposerShot.HeldAttachmentMissing, ComposerShot.InterruptSendConfirm, ComposerShot.StopConfirm, ComposerShot.HeldHandover, ComposerShot.StopConfirmSteered, ComposerShot.StopConfirmSpared).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
