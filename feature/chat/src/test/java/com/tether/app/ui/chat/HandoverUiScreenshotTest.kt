package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #273 goldens (A1 §8 6-12) at the phone (412x915 @420dpi), Studio and StudioDark, each state seeded synchronously:
 * `handover-confirm` (Cancel focused), `handover-busy` (the pending notice + Tether's instruction in the queue),
 * `handoff-to`, `handoff-to-unlisted`, `handoff-from` (strip + HANDOVER FROM), `handover-instruction-row`,
 * `handover-notice-cancelled`. The row states (1-5) are in the shell module.
 */
enum class HandoverShot(val id: String) {
    Confirm("handover-confirm"), Busy("handover-busy"), To("handoff-to"), ToUnlisted("handoff-to-unlisted"),
    From("handoff-from"), InstructionRow("handover-instruction-row"), NoticeCancelled("handover-notice-cancelled"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverUiScreenshotTest(private val shot: HandoverShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun composerShot(session: com.tether.app.protocol.model.AgentSession, target: com.tether.app.protocol.model.AgentSession?, folded: ChatFixtures.Folded, underWay: Boolean) {
        rule.setContent {
            ComposerHost(skin) {
                Composer(
                    session = session,
                    projection = folded.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> false },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    handoffTarget = target,
                    handoverUnderWay = underWay,
                )
            }
        }
    }

    private fun transcriptShot(folded: ChatFixtures.Folded, from: String?, peer: com.tether.app.protocol.model.AgentSession?) {
        rule.setContent {
            ChatHost(skin) {
                ChatTranscript(
                    projection = folded.projection,
                    tree = folded.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                    showTimeline = false,
                    handedOffFrom = from,
                    handoffPeer = peer,
                    richCodex = shot == HandoverShot.NoticeCancelled,
                )
            }
        }
    }

    @Test
    fun golden() {
        rule.mainClock.autoAdvance = false
        val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
        when (shot) {
            HandoverShot.Confirm -> rule.setContent {
                TetherTheme(choiceFor(skin)) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        HandOverDialog(identity = "s1", eligible = true, endable = true, onConfirm = {}, onCancel = {}, keyboardFocus = true)
                    }
                }
            }
            HandoverShot.Busy -> composerShot(ComposerFixtures.session, null, HandoverUiFixtures.busyWithRequest, underWay = true)
            HandoverShot.To -> composerShot(ComposerFixtures.session.copy(handedOffTo = target.id), target, ComposerFixtures.idle, false)
            HandoverShot.ToUnlisted -> composerShot(ComposerFixtures.session.copy(handedOffTo = "gone"), null, ComposerFixtures.idle, false)
            HandoverShot.From -> transcriptShot(HandoverUiFixtures.successorTranscript, "src-1", HandoverUiFixtures.source)
            HandoverShot.InstructionRow -> transcriptShot(HandoverUiFixtures.instructionTranscript(ChatFixtures.idle.projection.tetherSessionId), null, null)
            HandoverShot.NoticeCancelled -> transcriptShot(HandoverUiFixtures.cancelledNotice, null, null)
        }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        val path = "src/test/screenshots/${shot.id}/${skin.id}-phone.png"
        when (shot) {
            HandoverShot.Confirm -> captureScreenRoboImage(path, roborazziOptions = exact)
            HandoverShot.Busy, HandoverShot.To, HandoverShot.ToUnlisted -> rule.onNodeWithTag(ComposerTag).captureRoboImage(path, roborazziOptions = exact)
            else -> rule.onNodeWithTag(WellTag).captureRoboImage(path, roborazziOptions = exact)
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = HandoverShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
