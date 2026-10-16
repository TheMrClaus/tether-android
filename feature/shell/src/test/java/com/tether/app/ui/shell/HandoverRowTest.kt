package com.tether.app.ui.shell

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.Handover
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #273 (A1 §1): the "Session links" menu's Hand over row at the shell level (the popover, both states and the hidden one).
 * The full flow (confirm, send, focus) is in [MainShellHandoverTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverRowTest {
    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: Handover.MenuState?) {
        rule.setContent { ShellUnderTest(TetherSkin.Studio, PhoneShellState(linksOpen = true), ShellFixtures.idle, handover = state, onEvent = { events += it }) }
        rule.waitForIdle()
    }

    @Test
    fun anEnabledRowIsLastAndOpensTheConfirmation() {
        show(Handover.MenuState.Enabled)
        rule.onNodeWithTag(ShellTags.HandoverRow).assertIsEnabled().assertHasClickAction()
        rule.onAllNodesWithTag(ShellTags.HandoverReason).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        rule.onNodeWithTag(ShellTags.HandoverRow).performClick()
        rule.waitForIdle()
        assertEquals(listOf("handover"), events)
    }

    @Test
    fun aBlockedRowShowsItsReasonStaysFocusableAndDoesNothing() {
        val reason = Handover.reasonCopy("provider_no_bridge")
        show(Handover.MenuState.Blocked(reason))
        rule.onNodeWithText(reason).assertExists()
        rule.onNodeWithTag(ShellTags.HandoverRow).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("a blocked row sends nothing and opens nothing: $events", events.none { it == "handover" })
        // The popover stays open (the row is a no-op, not a dismissal).
        rule.onNodeWithTag(ShellTags.LinksPopover).assertExists()
    }

    @Test
    fun theBlockedRowIsOneTalkBackNodeNamingLabelReasonAndDisabled() {
        val reason = Handover.reasonCopy("plan_mode")
        show(Handover.MenuState.Blocked(reason))
        val node = rule.onNodeWithTag(ShellTags.HandoverRow).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertTrue(text, text.contains("Hand over to a new lead") && text.contains(reason))
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertTrue("disabled", node.config.contains(SemanticsProperties.Disabled))
        // Focusable, so a keyboard and TalkBack still reach it (it is never removed or hidden).
        assertTrue(node.config.contains(androidx.compose.ui.semantics.SemanticsActions.RequestFocus))
    }

    @Test
    fun hiddenOrUnknownDrawsNoRow() {
        show(Handover.MenuState.Hidden)
        rule.onAllNodesWithTag(ShellTags.HandoverRow).let { assertEquals(0, it.fetchSemanticsNodes().size) }
    }

    // ---- W34: Stop handover (v151) ----

    private val stage = "Handover prompt written. The new session starts when this turn ends."

    @Test
    fun theStopRowIsTheSameLastRowWithTheStageAndTheEffectAndPressingItStopsAtOnceWithNoConfirm() {
        show(Handover.MenuState.Stop(stage))
        val node = rule.onNodeWithTag(ShellTags.HandoverRow).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertTrue(text, text.contains("Stop handover") && text.contains(stage) && text.contains(Handover.STOP_EFFECT))
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertTrue("enabled", !node.config.contains(SemanticsProperties.Disabled))
        rule.onNodeWithTag(ShellTags.HandoverEffect, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ShellTags.HandoverRow).performClick()
        rule.waitForIdle()
        assertEquals(listOf("stop-handover"), events)
    }

    @Test
    fun theStoppingRowReadsStoppingStaysFocusableDisabledAndTapsDoNothing() {
        show(Handover.MenuState.Stopping)
        rule.onNodeWithText(Handover.STOPPING_LABEL).assertExists()
        rule.onAllNodesWithTag(ShellTags.HandoverEffect, useUnmergedTree = true).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        val node = rule.onNodeWithTag(ShellTags.HandoverRow).fetchSemanticsNode()
        assertTrue("disabled", node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config.contains(androidx.compose.ui.semantics.SemanticsActions.RequestFocus))
        rule.onNodeWithTag(ShellTags.HandoverRow).performClick()
        rule.waitForIdle()
        assertTrue("a Stopping row sends nothing: $events", events.isEmpty())
    }
}
