package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tether.app.client.Handover
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W34 (A-spec 1, 4.2, 4.5): the three-action Hand over dialog, the stage strip, and the copy the web's Stop piece made
 * false. Every surface is also pinned at protocol 150, where it is exactly the W33 one (wave-check W34 #1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverV151UiBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sent = mutableListOf<String>()
    private val removed = mutableListOf<String>()

    private fun dialog(
        v151: Boolean = true,
        phone: Boolean = true,
        onConfirm: () -> Unit = {},
        onCancel: () -> Unit = {},
        onChange: () -> Boolean = { true },
    ) = rule.setContent {
        TetherTheme(choiceFor(TetherSkin.Studio)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {
                HandOverDialog("s1", eligible = true, endable = true, onConfirm = onConfirm, onCancel = onCancel, v151 = v151, onChangeSettings = onChange, phone = phone)
            }
        }
    }

    // ---- the dialog ----

    @Test
    fun v151ShowsTheThreeActionsTheWebBodyAndTheChoiceLineAndCancelIsFocused() {
        dialog()
        rule.waitForIdle()
        rule.onNodeWithText(Handover.DIALOG_BODY_V151).assertExists()
        rule.onNodeWithTag(HANDOVER_CANCEL_TAG).assertIsFocused()
        rule.onNodeWithText(Handover.CANCEL_LABEL).assertExists()
        rule.onNodeWithText("Change settings…").assertExists()
        rule.onNodeWithText("Hand over as is").assertExists()
        val choice = "Hand over as is keeps the same settings and permissions. Change settings lets you pick them first — the lead writes nothing until you confirm there."
        rule.onNodeWithText(choice).assertExists()
    }

    @Test
    fun v150IsTheW33DialogByteForByte() {
        dialog(v151 = false)
        rule.waitForIdle()
        rule.onNodeWithText(Handover.DIALOG_BODY).assertExists()
        rule.onAllNodesWithTag(HANDOVER_CHANGE_TAG).assertCountEquals(0)
        rule.onNodeWithText("Hand over").assertExists()
    }

    @Test
    fun handOverAsIsConfirmsOnceAndCancelDoesNot() {
        var confirms = 0
        var cancels = 0
        dialog(onConfirm = { confirms++ }, onCancel = { cancels++ })
        rule.waitForIdle()
        rule.onNodeWithTag(HANDOVER_CONFIRM_TAG).performClick()
        assertEquals(1, confirms)
        assertEquals(0, cancels)
    }

    @Test
    fun changeSettingsAsksTheHostAndTheNoEngineLineShowsWhenItRefuses() {
        var asked = 0
        dialog(onChange = { asked++; false })
        rule.waitForIdle()
        rule.onAllNodesWithTag(HANDOVER_NO_ENGINE_TAG).assertCountEquals(0)
        rule.onNodeWithTag(HANDOVER_CHANGE_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, asked)
        rule.onNodeWithText(Handover.NO_ENGINE_LINE).assertExists()
        assertEquals(
            androidx.compose.ui.semantics.LiveRegionMode.Assertive,
            rule.onNodeWithTag(HANDOVER_NO_ENGINE_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion),
        )
    }

    @Test
    fun theStackedPhoneFooterIsDrawnAsIsChangeCancelAndReadCancelChangeAsIs() {
        dialog(phone = true)
        rule.waitForIdle()
        val asIs = rule.onNodeWithTag(HANDOVER_CONFIRM_TAG).getBoundsInRoot()
        val change = rule.onNodeWithTag(HANDOVER_CHANGE_TAG).getBoundsInRoot()
        val cancel = rule.onNodeWithTag(HANDOVER_CANCEL_TAG).getBoundsInRoot()
        assertTrue("drawn top to bottom: Hand over as is, Change settings, Cancel", asIs.top < change.top && change.top < cancel.top)
        assertEquals("full width keys", asIs.width, cancel.width)
        // TalkBack / Tab order: Cancel first, then Change settings, then Hand over as is.
        fun index(tag: String) = rule.onNodeWithTag("handover-slot-" + tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex)
        assertEquals(listOf(0f, 1f, 2f), listOf(index("cancel"), index("change"), index("as-is")))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun theWideFooterIsOneRowInDomOrder() {
        dialog(phone = false)
        rule.waitForIdle()
        val cancel = rule.onNodeWithTag(HANDOVER_CANCEL_TAG).getBoundsInRoot()
        val change = rule.onNodeWithTag(HANDOVER_CHANGE_TAG).getBoundsInRoot()
        val asIs = rule.onNodeWithTag(HANDOVER_CONFIRM_TAG).getBoundsInRoot()
        assertTrue(cancel.left < change.left && change.left < asIs.left)
        assertEquals(cancel.top, asIs.top)
    }

    // ---- the strip, the queue and the composer ----

    private fun composer(stage: HandoverV151Fixtures.Stage?, v151: Boolean = true, withStageView: Boolean = true) = rule.setContent {
        ComposerHost(TetherSkin.Studio) {
            Composer(
                session = stage?.session ?: ComposerFixtures.session,
                projection = (stage?.folded ?: ComposerFixtures.idle).projection,
                controls = null,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { text, _ -> sent += text; true },
                onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                onQueueEdit = { _, _ -> },
                onQueueRemove = { removed += it },
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                handoverUnderWay = stage != null,
                handoverStage = if (v151 && withStageView) stage?.view else null,
                handoverV151 = v151,
            )
        }
    }

    @Test
    fun theStageLineIsInkAndOneLinePerKeyWithTheNewSubLine() {
        val cases = mapOf(
            HandoverV151Fixtures.requestedQueued to "Handover requested. Tether asks the lead to write its handover prompt when this turn ends.",
            HandoverV151Fixtures.requestedWriting to "Handover under way. The lead is writing its handover prompt.",
            HandoverV151Fixtures.scheduled to "Handover prompt written. The new session starts when this turn ends.",
            HandoverV151Fixtures.waitingBackground to "Handover prompt written. Waiting for 2 background tasks to finish, then the new session starts.",
        )
        for ((stage, line) in cases) {
            assertEquals(line, stage.view?.line)
        }
        composer(HandoverV151Fixtures.waitingBackground)
        rule.waitForIdle()
        rule.onNodeWithTag(HANDOFF_STAGE_LINE_TAG).assertExists()
        rule.onNodeWithText(cases.getValue(HandoverV151Fixtures.waitingBackground)).assertExists()
        rule.onNodeWithText(Handover.STRIP_SUB).assertExists()
    }

    @Test
    fun stoppingHasNoSubLine() {
        composer(HandoverV151Fixtures.stopping)
        rule.waitForIdle()
        rule.onNodeWithText(Handover.STOPPING_LINE).assertExists()
        rule.onAllNodesWithTag(HANDOFF_STAGE_SUB_TAG).assertCountEquals(0)
    }

    @Test
    fun at150TheLegacyNoticeIsUntouched() {
        composer(HandoverV151Fixtures.scheduled, v151 = false)
        rule.waitForIdle()
        rule.onNodeWithText("Handover under way — a new lead takes over when this lead finishes.").assertExists()
        rule.onNodeWithText("Stop cancels it · new messages go to the new lead.").assertExists()
        rule.onAllNodesWithTag(HANDOFF_STAGE_LINE_TAG).assertCountEquals(0)
    }

    @Test
    fun theInstructionRowHasNoXAndAMovingRowSaysWhereItGoes() {
        composer(HandoverV151Fixtures.requestedQueued)
        rule.waitForIdle()
        rule.onNodeWithText(Handover.QUEUE_LABEL).assertExists()
        rule.onNodeWithText(Handover.QUEUE_MOVES).assertExists()
        rule.onAllNodesWithContentDescription(HANDOVER_QUEUE_REMOVE_DESCRIPTION).assertCountEquals(0)
        rule.onNodeWithContentDescription(HANDOVER_QUEUE_ROW_DESCRIPTION).assertExists()
        // The operator's own row keeps its X.
        rule.onNodeWithContentDescription("Remove queued message").performClick()
        assertEquals(listOf("mine"), removed)
    }

    @Test
    fun v151DropsTheInstructionRowsXOnTheRequestAloneEvenWithNoStageToShow() {
        composer(HandoverV151Fixtures.requestedQueued, withStageView = false)
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription(HANDOVER_QUEUE_REMOVE_DESCRIPTION).assertCountEquals(0)
    }

    @Test
    fun theUnderwayRefusalsAreRecognisedForTheArrowGlyph() {
        for (kind in listOf("command", "delegate", "attachments")) assertTrue(Handover.isUnderwayRefusal(Handover.underwayCopy(kind)))
        assertFalse(Handover.isUnderwayRefusal("Type a command after “!” to run it."))
    }

    @Test
    fun at150TheInstructionRowKeepsItsXAndItsW33Words() {
        composer(HandoverV151Fixtures.requestedQueued, v151 = false)
        rule.waitForIdle()
        rule.onNodeWithText(HANDOVER_QUEUE_WAIT_COPY).assertExists()
        rule.onNodeWithContentDescription(HANDOVER_QUEUE_REMOVE_DESCRIPTION).assertExists()
    }

    @Test
    fun waitingBackgroundHoldsTextThroughTheQueueKeyAndOffersNoInterrupt() {
        composer(HandoverV151Fixtures.waitingBackground)
        rule.waitForIdle()
        rule.onAllNodesWithTag(INTERRUPT_KEY_TAG).assertCountEquals(0)
        rule.onNode(hasSetTextAction()).performTextInput("go on")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(Handover.QUEUE_ARIA).assertExists()
        rule.onAllNodesWithTag(INTERRUPT_KEY_TAG).assertCountEquals(0)
        rule.onNodeWithContentDescription(Handover.QUEUE_ARIA).performClick()
        assertEquals(listOf("go on"), sent)
    }

    @Test
    fun interruptNamesWhatItDoesPerStage() {
        composer(HandoverV151Fixtures.scheduled)
        rule.waitForIdle()
        val description = rule.onNodeWithTag(INTERRUPT_KEY_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Interrupt the current turn — the handover then starts right away"), description)
    }

    @Test
    fun anAttachmentOrCommandWhileUnderwayIsRefusedInline() {
        assertEquals(
            "Attachments can’t wait for a handover. Stop handover first, or send text — it goes to the new session.",
            Handover.underwayCopy("attachments"),
        )
        assertEquals("Commands can’t wait for a handover. Stop handover first, or send text — it goes to the new session.", Handover.underwayCopy("command"))
        assertEquals("A delegation can’t wait for a handover. Stop handover first, or send text — it goes to the new session.", Handover.underwayCopy("delegate"))
    }
}
