package com.tether.app.ui.shell

import com.tether.app.testsupport.runPrefsWrite

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.tether.app.client.Handover
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #273 through MainShell: the menu's Hand over row, its confirmation (owner's words, Cancel focused, one send), the
 * auto-dismiss when the session stops being eligible, back = cancel, and the "under way" notice after the send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h1000dp-mdpi")
class MainShellHandoverTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(600, 1000)
    }

    private val session = AgentSession(id = "s1", provider = "claude", name = "leads", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)

    private val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Keep going.") },
    )

    private fun host(client: ShellConsentClient) {
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, UiPrefs(ApplicationProvider.getApplicationContext())) } }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ShellTags.EndSessionKey).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openMenuThenRow() {
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ShellTags.HandoverRow).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
    }

    @Test
    fun theRowOpensTheOwnersConfirmationCancelIsFocusedAndHandOverSendsOnce() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        openMenuThenRow()
        rule.onNodeWithTag(ShellTags.HandoverRow).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.LinksPopover).assertDoesNotExist()
        rule.onNodeWithText(Handover.DIALOG_TITLE).assertExists()
        rule.onNodeWithText(Handover.DIALOG_BODY).assertExists()
        settle()
        rule.onNodeWithTag(com.tether.app.ui.chat.HANDOVER_CONFIRM_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(Handover.DIALOG_TITLE).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("s1"), client.handoverCalls)
        // This device asked: the "under way" notice shows, and the row says so (blocked) without a second send.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("handoff-notice-pending").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ShellTags.HandoverRow).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(ShellTags.HandoverRow).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1"), client.handoverCalls)
    }

    @Test
    fun cancelAndBackCloseTheConfirmationAndSendNothing() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        openMenuThenRow()
        rule.onNodeWithTag(ShellTags.HandoverRow).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(com.tether.app.ui.chat.HANDOVER_CANCEL_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(Handover.DIALOG_TITLE).assertDoesNotExist()

        openMenuThenRow()
        rule.onNodeWithTag(ShellTags.HandoverRow).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(Handover.DIALOG_TITLE).assertExists()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithText(Handover.DIALOG_TITLE).assertDoesNotExist()
        assertTrue("neither Cancel nor Back sends: ${client.handoverCalls}", client.handoverCalls.isEmpty())
    }

    @Test
    fun theConfirmationClosesItselfWhenTheSessionTurnsIneligibleAndSendsNothing() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        openMenuThenRow()
        rule.onNodeWithTag(ShellTags.HandoverRow).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(Handover.DIALOG_TITLE).assertExists()
        rule.runOnIdle { client.sessions.value = listOf(session.copy(handoverPending = true)) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(Handover.DIALOG_TITLE).fetchSemanticsNodes().isEmpty() }
        assertTrue(client.handoverCalls.isEmpty())
    }

    @Test
    fun aBlockedSessionShowsItsReasonAndTheTapDoesNothing() {
        val client = ShellConsentClient().also { it.show(session.copy(readOnly = true), tree) }
        host(client)
        openMenuThenRow()
        rule.onNodeWithText(Handover.reasonCopy("read_only")).assertExists()
        rule.onNodeWithTag(ShellTags.HandoverRow).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(Handover.DIALOG_TITLE).assertCountEquals(0)
        assertTrue(client.handoverCalls.isEmpty())
    }

    @Test
    fun anOldServerDrawsNoRow() {
        val client = ShellConsentClient().also { it.show(session, tree); it.serverProtocolVersion.value = 149 }
        host(client)
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ShellTags.LinksPopover).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag(ShellTags.HandoverRow).assertCountEquals(0)
    }
}
