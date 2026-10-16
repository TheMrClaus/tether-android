package com.tether.app.ui.draft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.Handover
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.shell.choiceFor
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
 * W34 (A-spec 2, 3): the handover-mode sheet's behaviour and the Session name row, at the phone, over the real engine
 * (the frame is drawn from [HandoverComposerSeed]; the actions record what the CTA and the field do).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverComposerBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun show(shot: HandoverComposerShot, actions: DraftSheetActions) {
        val seed = HandoverComposerSeed(shot)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DraftComposerFrame(seed.inputs(), actions, layout = TetherLayoutClass.Phone)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun handoverModeHasNoMessageBoxNoAttachAndOneCtaThatSubmitsOnce() {
        var submits = 0
        var sends = 0
        show(HandoverComposerShot.Default, DraftSheetActions(onSubmitHandover = { submits++ }, onSubmit = { sends++ }))
        rule.onAllNodesWithTag(DraftComposerTags.Input).assertCountEquals(0)
        rule.onAllNodesWithTag(DraftComposerTags.Attach).assertCountEquals(0)
        rule.onAllNodesWithTag(DraftComposerTags.Send).assertCountEquals(0)
        rule.onNodeWithTag(DraftComposerTags.Explainer).assertExists()
        rule.onNodeWithTag(DraftComposerTags.IsolationNote).assertExists()
        rule.onNodeWithTag(DraftComposerTags.HandoverCta).performClick()
        assertEquals(1, submits)
        assertEquals(0, sends)
    }

    @Test
    fun theCloseKeyIsNamedNothingIsHandedOverAndCloses() {
        var closes = 0
        show(HandoverComposerShot.Default, DraftSheetActions(onClose = { closes++ }))
        val node = rule.onNodeWithTag(DraftComposerTags.Close).fetchSemanticsNode()
        assertEquals(listOf(Handover.MODE_CLOSE_LABEL), node.config.getOrNull(SemanticsProperties.ContentDescription))
        rule.onNodeWithTag(DraftComposerTags.Close).performClick()
        assertEquals(1, closes)
    }

    @Test
    fun theNameFieldIsPrefilledAndTypingReachesTheEngine() {
        val typed = mutableListOf<String>()
        show(HandoverComposerShot.Default, DraftSheetActions(onName = { typed += it }))
        rule.onNodeWithTag(DraftComposerTags.NameField).performTextReplacement("Next parser")
        assertEquals(listOf("Next parser"), typed)
    }

    @Test
    fun theClearedNameShowsTheAutomaticNameHelper() {
        show(HandoverComposerShot.NameCleared, DraftSheetActions())
        rule.onNodeWithTag(DraftComposerTags.NameHint).assertExists()
        assertEquals(Handover.autoNameHintLine("Parser rewrite 2"), rule.onNodeWithTag(DraftComposerTags.NameHint).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
    }

    @Test
    fun aPendingRequestDisablesTheCtaAndReadsHandingOver() {
        var submits = 0
        show(HandoverComposerShot.Pending, DraftSheetActions(onSubmitHandover = { submits++ }))
        val cta = rule.onNodeWithTag(DraftComposerTags.HandoverCta)
        assertTrue(cta.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        cta.performClick()
        assertEquals(0, submits)
    }

    @Test
    fun aBlockedCtaStaysFocusableWithDisabledSemanticsAndDoesNothing() {
        var submits = 0
        show(HandoverComposerShot.Blocked, DraftSheetActions(onSubmitHandover = { submits++ }))
        rule.onNodeWithTag(DraftComposerTags.Blocked).assertExists()
        val cta = rule.onNodeWithTag(DraftComposerTags.HandoverCta)
        cta.performClick()
        assertEquals(0, submits)
        // The wrapper carries the focus and the disabled semantics (the web's aria-disabled).
        val parent = rule.onNodeWithTag(DraftComposerTags.HandoverCta).onParent().fetchSemanticsNode()
        assertTrue(parent.config.contains(SemanticsProperties.Disabled))
        assertTrue(parent.config.contains(SemanticsProperties.Focused))
    }

    @Test
    fun theNameRowIsTheOrdinarySheetsFirstRowAtV151AndAbsentBefore() {
        show(HandoverComposerShot.NameEmpty, DraftSheetActions())
        rule.onNodeWithTag(DraftComposerTags.NameRow).assertExists()
        rule.onAllNodesWithTag(DraftComposerTags.NameHint).assertCountEquals(0)
    }

    @Test
    fun belowV151TheOrdinarySheetHasNoNameRow() {
        val noName = HandoverComposerSeed(HandoverComposerShot.NameEmpty)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                DraftComposerFrame(DraftSheetInputs(draft = noName.state, browser = noName.inputs().browser, quickPicks = emptyList(), workspaceRoot = "/", readiness = ""), DraftSheetActions(), layout = TetherLayoutClass.Phone)
            }
        }
        rule.waitForIdle()
        assertFalse(rule.onAllNodesWithTag(DraftComposerTags.NameRow).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theNameFieldExposesItsTypedValueNotAMaskingDescription() {
        show(HandoverComposerShot.Default, DraftSheetActions())
        val config = rule.onNodeWithTag(DraftComposerTags.NameField).fetchSemanticsNode().config
        assertEquals(null, config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("Parser rewrite 2", config.getOrNull(SemanticsProperties.EditableText)?.text)
        // The label merges into the field's node: it is the field's accessible name, the typed value still read.
        val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue(texts.toString(), texts.contains(Handover.NAME_FIELD_LABEL))
    }

    @Test
    fun theHelperTextIsTheNameFieldsDescription() {
        show(HandoverComposerShot.NameCleared, DraftSheetActions())
        val description = rule.onNodeWithTag(DraftComposerTags.NameField).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertTrue(description.toString(), description != null && description.startsWith("Automatic name"))
    }

    @Test
    fun imeDoneInHandoverModeDoesNothingAndTheFieldKeepsFocus() {
        var submits = 0
        show(HandoverComposerShot.Default, DraftSheetActions(onSubmitHandover = { submits++ }))
        val field = rule.onNodeWithTag(DraftComposerTags.NameField)
        field.performClick()
        field.assertIsFocused()
        field.performImeAction()
        rule.waitForIdle()
        field.assertIsFocused()
        assertEquals(0, submits)
    }

    @Test
    fun theSheetsPaneTitleIsBidiIsolatedLikeTheHeader() {
        show(HandoverComposerShot.Default, DraftSheetActions())
        val title = rule.onNodeWithTag(DraftComposerTags.Sheet).fetchSemanticsNode().config.getOrNull(SemanticsProperties.PaneTitle)
        assertTrue(title.orEmpty(), title.orEmpty().contains("\u2068") && title.orEmpty().contains("\u2069"))
    }
}
