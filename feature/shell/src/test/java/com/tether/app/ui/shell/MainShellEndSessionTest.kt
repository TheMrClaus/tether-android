package com.tether.app.ui.shell

import com.tether.app.testsupport.runPrefsWrite

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
 * T13.2 r2 through MainShell: End session needs a live link AND a live copy of the session, at the
 * header key and again at the confirmation (a link that drops under the open dialog disables its
 * key; ta-coik.22: the dialog itself stays open, as the web's). The kill is bound to the server the
 * key was drawn for (ta-coik.24: the client no longer re-checks the live set or the listing).
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

    /** Let the composition settle; ta-coik.13: the confirmation's key has no arm delay. */
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
        // MainShell paints the session (and so its header) only once the stored preferences have arrived (`showEnded` is
        // null until then), and that DataStore read hops through Dispatchers.IO, which waitForIdle() does not wait for:
        // under load the first assertion could run before the header existed (ta-7njx: it flaked on 3adb3899 too).
        // So wait for the header itself, as MainShellNavigationTest's awaitTag does for its own async precondition.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ShellTags.EndSessionKey).fetchSemanticsNodes().isNotEmpty() }
    }

    /** T6.7: the web's words; ta-coik.13: the key ends the session on its first tap (dashboard.tsx 90fbb9f :1909). */
    @Test
    fun theConfirmationIsTheWebsAndItsKeyActsOnTheFirstTap() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertExists()
        rule.onNodeWithText("\u2068ends\u2069 — its running process will stop.").assertExists()
        rule.mainClock.autoAdvance = false
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN"), client.killCalls)
    }

    /**
     * ta-coik.22 r2: like the web's `<dialog>` (dashboard.tsx 90fbb9f :1902-1911), a link that drops
     * under the open dialog leaves it open and its confirm key live (the web's is never disabled); a
     * tap asks the client, which sends while the socket is open. The header key is live too
     * (workspace-header.tsx :133).
     */
    @Test
    fun theConfirmationStaysOpenAndLiveWhenTheCopyStopsBeingLive() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        arm()
        confirmKey().assertIsEnabled()

        rule.runOnIdle {
            client.link.value = ConnectionState.Connected
            client.live.value = emptySet()
        }
        arm()
        rule.onNodeWithText("End session?").assertExists()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN"), client.killCalls)
        rule.onNodeWithText("End session?").assertDoesNotExist()
    }

    /**
     * r3 / ta-coik.22: the confirmation carries the server it was opened for. A switch to another
     * server under the open dialog, even one where a same-id session is live, leaves it open (as on
     * the web) with its key disabled, and ends nothing; Cancel, then a fresh one ends it there.
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
        rule.onNodeWithText("End session?").assertExists()
        confirmKey().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("an End opened for one server ended a session on another: ${client.killCalls}", client.killCalls.isEmpty())

        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        // Opened afresh on the current server: it ends there, bound to that origin.
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        arm()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$OTHER_ORIGIN"), client.killCalls)
    }

    // ta-9dpl: the folder is the outer rule, deleted only once the composition is gone: a preference
    // write still on the disk at the end can no longer fail (and fail the test) under a live screen.
    val tmp = TemporaryFolder()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

    /** A preference store of this test's own, with Settings → General's "Confirm before ending" set. */
    private fun prefsConfirming(confirm: Boolean): UiPrefs {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(tmp.root, "ui.preferences_pb") }
        return UiPrefs.on(store).also { prefs -> runPrefsWrite { prefs.updatePreferences { it.copy(confirmBeforeEnd = confirm) } } }
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
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN"), client.killCalls)
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

    /**
     * ta-coik.52: "Confirm before ending" is the signed-in server's own (the web's preferences are per
     * origin): off on this server ends at once though another server, and the device-wide default, ask.
     */
    @Test
    fun confirmBeforeEndIsTheServersOwn() {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(tmp.root, "ui.preferences_pb") }
        val prefs = UiPrefs.on(store)
        runPrefsWrite {
            prefs.updatePreferencesFor("https://a.example:443") { it.copy(confirmBeforeEnd = false) }
            prefs.updatePreferencesFor("https://b.example:443") { it.copy(confirmBeforeEnd = true) }
        }
        val client = ShellConsentClient().also { it.show(session, tree) }
        client.server.value = "https://a.example"
        host(client, prefs)
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN"), client.killCalls)
    }

    private companion object {
        const val OTHER_ORIGIN = "https://other.example"
    }
}
