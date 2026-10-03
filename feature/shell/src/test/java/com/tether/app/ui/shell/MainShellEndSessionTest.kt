package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
 * T13.2 r2 through MainShell: End session needs a live link AND a live copy of the session, at the
 * header key and again at the confirmation (a link that drops under the open dialog disables it).
 * The kill asks the client to re-check the live set (requireLive).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h1000dp-mdpi")
class MainShellEndSessionTest {
    // ta-9dpl: the v2 rule, as for every test whose screen reads IO-fed preferences (ta-b72): under v1
    // the stored "Confirm before ending" was written to Compose state off the main thread and could be missed.
    val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(600, 1000)
    }

    private val session = AgentSession(id = "s1", provider = "claude", name = "ends", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)

    private val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Keep going.") },
    )

    private fun confirmKey() = rule.onNodeWithTag(com.tether.app.ui.chat.END_SESSION_CONFIRM_TAG)

    /** T6.7: the confirmation's key is armed like every operator control (500 ms). */
    private fun arm() {
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
    }

    private fun host(client: ShellConsentClient, prefs: UiPrefs = UiPrefs(ApplicationProvider.getApplicationContext())) {
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        rule.waitForIdle()
    }

    /** T6.7: the web's words; the key does nothing in its first 500 ms, then ends the session once. */
    @Test
    fun theConfirmationIsTheWebsAndItsKeyIsArmed() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertExists()
        rule.onNodeWithText("\u2068ends\u2069 — its running process will stop.").assertExists()
        rule.mainClock.autoAdvance = false
        confirmKey().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("an unarmed key ended the session: ${client.killCalls}", client.killCalls.isEmpty())
        arm()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN:true"), client.killCalls)
    }

    /**
     * T13.2 r2 / T6.7: a link that drops under the open dialog closes it (a saved copy never ends a
     * session, and the question never outlives the link it was asked on); the header key stays
     * disabled until the copy is live again, and a fresh confirmation then ends it once.
     */
    @Test
    fun theConfirmationClosesWhenTheCopyStopsBeingLive() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        arm()
        confirmKey().assertIsEnabled()

        rule.runOnIdle {
            client.link.value = ConnectionState.Disconnected
            client.live.value = emptySet()
        }
        arm()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertTrue("nothing from a saved copy: ${client.killCalls}", client.killCalls.isEmpty())
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsNotEnabled()

        // Back, but not yet confirmed on the new link (catching up): still disabled.
        rule.runOnIdle { client.link.value = ConnectionState.Connected }
        arm()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsNotEnabled()

        rule.runOnIdle { client.live.value = setOf("s1") }
        arm()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        arm()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN:true"), client.killCalls)
    }

    /**
     * r3 / T6.7: the confirmation carries the server it was opened for. A switch to another server
     * under the open dialog, even one where a same-id session is live, closes it and ends nothing.
     */
    @Test
    fun theConfirmationIsBoundToTheServerItWasOpenedFor() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        arm()
        confirmKey().assertIsEnabled()

        rule.runOnIdle { client.origin.value = OTHER_ORIGIN }
        arm()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertTrue("an End opened for one server ended a session on another: ${client.killCalls}", client.killCalls.isEmpty())

        // Opened afresh on the current server: it ends there, bound to that origin.
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        arm()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$OTHER_ORIGIN:true"), client.killCalls)
    }

    // ta-9dpl: the folder is the outer rule, deleted only once the composition is gone: a preference
    // write still on the disk at the end can no longer fail (and fail the test) under a live screen.
    val tmp = TemporaryFolder()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

    /** A preference store of this test's own, with Settings → General's "Confirm before ending" set. */
    private fun prefsConfirming(confirm: Boolean): UiPrefs {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(tmp.root, "ui.preferences_pb") }
        return UiPrefs.on(store).also { prefs -> runBlocking { prefs.updatePreferences { it.copy(confirmBeforeEnd = confirm) } } }
    }

    private val storeJob = Job()

    @After fun closeStore() = storeJob.cancel()

    /**
     * T10.1 (dashboard.tsx:1170-1180 `endSession`): with "Confirm before ending" off, the header's
     * End session sends at once, bound to the server it was drawn for; no question is asked.
     */
    @Test
    fun withConfirmBeforeEndOffTheHeaderEndsAtOnce() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client, prefsConfirming(false))
        // The stored value is read before the key is used.
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN:true"), client.killCalls)
    }

    /** On (the default), the same key asks first and ends nothing until confirmed. */
    @Test
    fun withConfirmBeforeEndOnTheHeaderAsks() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client, prefsConfirming(true))
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertExists()
        assertTrue(client.killCalls.isEmpty())
    }

    private companion object {
        const val OTHER_ORIGIN = "https://other.example"
    }
}
