package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import com.tether.app.client.InterruptResult
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.helpers.QueueWait.LiveWork
import com.tether.app.ui.chat.ComposerFixtures.QueueSpec
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AW.3 (twin of web §4.3 / §4.1f Part B): the Stop key toggles a panel (never commits); its box is offered only on a server that
 * speaks v156 and when something is stoppable; Stop with the box checked is the ONLY `stopBackground = true` path. Each assertion
 * names the TOKEN that decides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StopPanelBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val plain = mutableListOf<String>()
    private val also = mutableListOf<String>()
    private var fixture by mutableStateOf(ComposerFixtures.deferred())
    private var protocol by mutableStateOf<Int?>(156)
    private var cli by mutableStateOf<String?>(null)
    private var inputMode: InputModeManager? = null

    private fun show(folded: ChatFixtures.Folded, serverProtocol: Int? = 156, cliVersion: String? = null) {
        fixture = folded
        protocol = serverProtocol
        cli = cliVersion
        rule.mainClock.autoAdvance = false
        rule.setContent {
            inputMode = LocalInputModeManager.current
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = fixture.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> true },
                    onInterrupt = { turnId -> plain += turnId; InterruptResult.Sent },
                    onInterruptStopBackground = { turnId -> also += turnId; InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    serverProtocol = protocol,
                    persistentSessions = true,
                    tree = withCliVersion(fixture, cli).tree,
                )
            }
        }
        frames()
    }

    private fun frames() {
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun tapKey() {
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        frames()
    }

    private fun panelCount() = rule.onAllNodesWithTag(STOP_PANEL_TAG).fetchSemanticsNodes().size
    private fun boxCount() = rule.onAllNodesWithTag(STOP_BOX_TAG).fetchSemanticsNodes().size
    private fun resting(): String? = rule.onNodeWithTag(INTERRUPT_KEY_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun boxChecked(): Boolean? = rule.onNodeWithTag(STOP_BOX_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)?.let { it == androidx.compose.ui.state.ToggleableState.On }

    @Test
    fun anEligibleKeyOpensAndClosesThePanelAndNeverInterrupts() {
        show(ComposerFixtures.deferred())
        assertEquals(0, panelCount())
        tapKey()
        assertEquals(1, panelCount())
        tapKey()
        assertEquals(0, panelCount())
        assertTrue("the key never commits: $plain $also", plain.isEmpty() && also.isEmpty())
    }

    @Test
    fun stopSendsStopBackgroundOnlyWhenTheBoxIsShownAndChecked() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        tapKey()
        assertEquals(1, boxCount())
        assertEquals("unchecked on open", false, boxChecked())
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).performClick()
        frames()
        assertEquals("unchecked: no stopBackground", listOf("t1"), plain)
        assertTrue(also.isEmpty())
        tapKey()
        rule.onNodeWithTag(STOP_BOX_TAG).performClick()
        frames()
        assertEquals(true, boxChecked())
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).performClick()
        frames()
        assertEquals("checked: stopBackground = true", listOf("t1"), also)
        assertEquals(listOf("t1"), plain)
    }

    @Test
    fun noBoxMeansStopNeverSendsTheFlag() {
        show(ComposerFixtures.delivery(tools = 1, background = 0))
        tapKey()
        assertEquals(1, panelCount())
        assertEquals(0, boxCount())
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).performClick()
        frames()
        assertEquals(listOf("t1"), plain)
        assertTrue(also.isEmpty())
    }

    @Test
    fun aServerBelow156OffersNoBoxAndSparedTasksAloneNeverOpenThePanel() {
        // Claude persistent, CLI 2.1.295 (spares), 3 tasks, no tool: the tasks are spared; on 155 the box is gated off, so one tap stops.
        show(ComposerFixtures.delivery(tools = 0, background = 3), serverProtocol = 155, cliVersion = "2.1.295")
        tapKey()
        assertEquals(0, panelCount())
        assertEquals(listOf("t1"), plain)
        assertTrue(also.isEmpty())
    }

    @Test
    fun anUnknownServerVersionCountsAsBelow156() {
        show(ComposerFixtures.delivery(tools = 0, background = 3), serverProtocol = null, cliVersion = "2.1.295")
        tapKey()
        assertEquals(0, panelCount())
        assertEquals(listOf("t1"), plain)
    }

    @Test
    fun anOpenToolOn155OpensThePanelWithoutTheBox() {
        show(ComposerFixtures.delivery(tools = 1, background = 3), serverProtocol = 155, cliVersion = "2.1.295")
        tapKey()
        assertEquals(1, panelCount())
        assertEquals(0, boxCount())
    }

    @Test
    fun theBoxIsUncheckedOnEveryOpen() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        tapKey()
        rule.onNodeWithTag(STOP_BOX_TAG).performClick()
        frames()
        assertEquals(true, boxChecked())
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).performClick()
        frames()
        assertEquals(0, panelCount())
        tapKey()
        assertEquals("never remembered", false, boxChecked())
        assertTrue(plain.isEmpty() && also.isEmpty())
    }

    @Test
    fun theRowConfirmationAndThePanelNeverShowTogether() {
        show(ComposerFixtures.delivery(QueueSpec("a"), tools = 1, background = 0))
        tapKey()
        assertEquals(1, panelCount())
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).performClick()
        frames()
        assertEquals("opening the row closes the panel", 0, panelCount())
        rule.onNodeWithTag(QUEUE_INTERRUPT_COMMIT_TAG).assertExists()
        tapKey()
        assertEquals("opening the panel closes the row", 1, panelCount())
        rule.onAllNodesWithTag(QUEUE_INTERRUPT_COMMIT_TAG).assertCountEquals(0)
        assertTrue(plain.isEmpty() && also.isEmpty())
    }

    @Test
    fun thePanelClosesWhenItsTurnEnds() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        tapKey()
        assertEquals(1, panelCount())
        rule.runOnIdle { fixture = ComposerFixtures.delivery(tools = 1, background = 3, turnId = "t2") }
        frames()
        assertEquals("another turn", 0, panelCount())
        tapKey()
        assertEquals(1, panelCount())
        rule.runOnIdle { fixture = ComposerFixtures.idle }
        frames()
        assertEquals("the turn ended", 0, panelCount())
    }

    @Test
    fun escapeAndBackActAsKeepRunning() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        keyboard() // a clickable takes focus (and a key event) only in keyboard input mode
        tapKey()
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).requestFocus()
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        frames()
        assertEquals("Escape", 0, panelCount())
        tapKey()
        assertEquals(1, panelCount())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        frames()
        assertEquals("Back", 0, panelCount())
        assertTrue(plain.isEmpty() && also.isEmpty())
    }

    @Test
    fun theKeysAndTheRestingKeyCarryTheExactWords() {
        show(ComposerFixtures.delivery(tools = 1, background = 0))
        val cost = QueueWait.interruptCostCopy(LiveWork(1, 0), true, true)
        assertEquals("eligible and closed", "Collapsed. $cost", resting())
        tapKey()
        assertEquals("open", "Expanded", resting())
        rule.onNodeWithContentDescription("Keep running").assertExists()
        rule.onNodeWithTag(STOP_ANYWAY_KEY_TAG).assertExists()
        rule.onNodeWithContentDescription("Stop").assertExists()
        rule.onNodeWithText("Stop this turn?").assertExists()
        rule.onNodeWithText(cost).assertExists()
        rule.onNodeWithContentDescription("Stop the current turn").assertExists()
    }

    @Test
    fun aKeyThatIsNotEligibleKeepsTheUnprefixedDescription() {
        // Server 155 + CLI 2.1.295 + tasks only: spared, no tool -> not eligible.
        show(ComposerFixtures.delivery(tools = 0, background = 3), serverProtocol = 155, cliVersion = "2.1.295")
        assertEquals("Stops the current response. 3 background tasks keep running.", resting())
    }

    @Test
    fun theBoxLabelAndTheCheckedSentenceFollowTheCounts() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        tapKey()
        rule.onNodeWithContentDescription("Also stop 3 background tasks").assertExists()
        rule.onNodeWithTag(STOP_BOX_TAG).performClick()
        frames()
        rule.onNodeWithText(
            "Stops the current response and 1 running tool call. It also stops 3 background tasks. Claude is told the tasks stopped and may reply right away.",
        ).assertExists()
    }

    // ---- hand-off pin (c): focus when the panel opens (AW.3.5) --------------------------------------------------------

    private fun keyboard() = rule.runOnUiThread { inputMode!!.requestInputMode(InputMode.Keyboard) }

    @Test
    fun keyboardModeOpensOnTheBoxWhenShown() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        keyboard()
        tapKey()
        rule.onNodeWithTag(STOP_BOX_TAG).assertIsFocused()
    }

    @Test
    fun keyboardModeOpensOnKeepRunningWithoutTheBox() {
        show(ComposerFixtures.delivery(tools = 1, background = 0))
        keyboard()
        tapKey()
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).assertIsFocused()
    }

    @Test
    fun focusMovesToKeepRunningWhenTheBoxRowGoes() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        keyboard()
        tapKey()
        rule.onNodeWithTag(STOP_BOX_TAG).assertIsFocused()
        rule.runOnIdle { fixture = ComposerFixtures.delivery(tools = 1, background = 0) }
        frames()
        assertEquals(0, boxCount())
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).assertIsFocused()
    }

    @Test
    fun touchModeMovesNoFocusOnOpen() {
        show(ComposerFixtures.delivery(tools = 1, background = 3))
        tapKey()
        assertEquals(1, panelCount())
        rule.onNodeWithTag(STOP_BOX_TAG).assertIsNotFocused()
        rule.onNodeWithTag(KEEP_RUNNING_KEY_TAG).assertIsNotFocused()
    }
}
