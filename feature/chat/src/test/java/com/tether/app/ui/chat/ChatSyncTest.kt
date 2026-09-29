package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T13.2 in the chat (SYNC_DESIGN §4.2; native-only, no web reference): an approval card from a
 * saved copy is rendered but never actionable (T6.3's lock, now also wired to freshness), a
 * session with nothing on the device says "Not downloaded", and a saved copy's trimmed turns say
 * "Older turns not downloaded" instead of offering a key that cannot load them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatSyncTest {
    @get:Rule val rule = createComposeRule()

    private val session = chatSession("s1", historyId = null)

    private fun host(client: ChatTestClient) {
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        arm()
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
    }

    private fun saved() = mapOf("s1" to SessionSync(Freshness.Saved, 1L))

    @Test
    fun aSavedCopyIsNeverActionableEvenWhereTheLiveSetStillHasIt() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = true)
        client.sync.value = saved()
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("nothing left the card", client.consentCalls.isEmpty())

        // Freshness and the live set agree again: answerable, after the arm delay.
        rule.runOnIdle { client.sync.value = mapOf("s1" to SessionSync(Freshness.Live, 2L)) }
        arm()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    @Test
    fun offlineTheCardSaysItIsASavedCopy() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = false)
        client.link.value = ConnectionState.Disconnected
        client.sync.value = saved()
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.onNodeWithText(ConsentLock.Offline.copy).assertIsDisplayed()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
    }

    @Test
    fun aSessionWithNothingOnTheDeviceSaysNotDownloaded() {
        val client = ChatTestClient()
        client.link.value = ConnectionState.Disconnected
        client.sessions.value = listOf(session)
        client.sync.value = mapOf("s1" to SessionSync(Freshness.NotDownloaded, null))
        host(client)
        rule.onNodeWithTag(ChatFreshness.NOT_DOWNLOADED_TAG).assertIsDisplayed()
        rule.onNodeWithContentDescription("Not downloaded. Connect to load").assertExists()
        rule.onNodeWithText("Connecting to the session…").assertDoesNotExist()
    }

    @Test
    fun aSavedCopysTrimmedTurnsSayNotDownloadedInsteadOfAKey() {
        val client = ChatTestClient()
        client.show(session, ChatFixtures.boundedOf(turns = 40, trimmed = 30), live = false)
        client.link.value = ConnectionState.Disconnected
        client.sync.value = saved()
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(ChatFreshness.OLDER_TURNS_TAG))
        rule.onNodeWithContentDescription("Older turns not downloaded").assertIsDisplayed()
        rule.onNodeWithText("Load 30 earlier turns").assertDoesNotExist()
    }
}
