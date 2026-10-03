package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.evNullTurn
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
import kotlinx.serialization.json.put

/**
 * T13.2 in the chat (SYNC_DESIGN §4.2; native-only, no web reference): an approval card from a
 * saved copy is rendered but never actionable (T6.3's lock, now also wired to freshness), a
 * session with nothing on the device says "Not downloaded", and a saved copy's trimmed turns say
 * "Older turns not downloaded" instead of offering a key that cannot load them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatSyncTest {
    // ta-9dpl: the v2 rule, as for every test whose screen reads IO-fed preferences (ta-b72): under v1
    // the stored "Confirm before ending" was written to Compose state off the main thread and could be missed.
    val rule = createComposeRule()

    private val session = chatSession("s1", historyId = null)

    private fun host(client: ChatTestClient, shown: AgentSession = session, header: Boolean = false, prefs: UiPrefs = UiPrefs(ApplicationProvider.getApplicationContext())) {
        val vm = TetherViewModel(client)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = header)
            }
        }
        arm()
    }

    /**
     * r2: every freshness a copy can have that is not Live, plus "no entry" from a client that
     * reports freshness: each must lock, even while the live set still holds the session.
     */
    private fun notLive(id: String): List<Pair<String, Map<String, SessionSync>>> =
        Freshness.entries.filter { it != Freshness.Live }.map { it.name to mapOf(id to SessionSync(it, 1L)) } +
            ("missing entry" to emptyMap())

    private fun live(id: String) = mapOf(id to SessionSync(Freshness.Live, 2L))

    private fun cmd(id: String, at: Long): AgentEvent = evNullTurn("background_command_updated", ts = at) {
        put("commandId", id); put("command", "npm run $id"); put("cwd", "/w"); put("logFile", "/w/$id.log"); put("status", "running"); put("startedAt", at)
    }

    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
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

        // Freshness and the live set agree again: answerable at once (ta-coik.13: no arm delay).
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

    // ---- r2: every non-Live freshness locks cards, Stop keys, controls and Interrupt -------------

    @Test
    fun everyCopyThatIsNotLiveLocksTheCardEvenInTheLiveSet() {
        val client = ChatTestClient().also { it.reports = true }
        client.show(session, ApprovalFixtures.write, live = true)
        client.sync.value = live("s1")
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        for ((name, sync) in notLive("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("approval-allow").assertIsNotEnabled().performClick()
            rule.waitForIdle()
            assertTrue("$name: nothing left the card", client.consentCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = live("s1") }
        arm()
        rule.onNodeWithTag("approval-allow").assertIsEnabled()
    }

    @Test
    fun everyCopyThatIsNotLiveLocksTheStopKeyEvenInTheLiveSet() {
        val stopSession = SubagentFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.show(stopSession, ChatFixtures.fold(*ChatFixtures.turn("t1", "Run it.", "Started.", 1_000), cmd("a", 2_000)), live = true)
        client.sync.value = live(stopSession.id)
        host(client, stopSession)
        rule.onNodeWithTag("bg-command-stop").assertIsEnabled()
        for ((name, sync) in notLive(stopSession.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("bg-command-stop").assertIsNotEnabled().performClick()
            rule.waitForIdle()
            assertTrue("$name: no stop from a copy that is not live", client.stopCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = live(stopSession.id) }
        arm()
        rule.onNodeWithTag("bg-command-stop").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:a"), client.stopCalls)
    }

    @Test
    fun everyCopyThatIsNotLiveLocksTheSessionControlsEvenInTheLiveSet() {
        val controlled = SessionControlFixtures.claude
        val client = ChatTestClient().also { it.reports = true }
        client.show(controlled, ComposerFixtures.idle, live = true)
        client.sessionControls.value = mapOf(controlled.id to SessionControlFixtures.claudeControls)
        client.sync.value = live(controlled.id)
        host(client, controlled)
        rule.onNodeWithTag("session-settings-trigger").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        for ((name, sync) in notLive(controlled.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            // Model / Effort / Mode all sit behind this key; its lock is theirs (T7.2).
            rule.onNodeWithTag("session-settings-trigger").assert(state(controlLockCopy(ConsentLock.CatchingUp)!!))
        }
        rule.runOnIdle { client.sync.value = live(controlled.id) }
        arm()
        rule.onNodeWithTag("session-settings-trigger").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertTrue(client.controlCalls.isEmpty())
    }

    @Test
    fun everyCopyThatIsNotLiveLocksBothInterruptKeysAndTheLiveOneIsBoundToItsServer() {
        val busy = ComposerFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.show(busy, ComposerFixtures.queued, live = true)
        client.sync.value = live(busy.id)
        host(client, busy)
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsEnabled()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsEnabled()
        for ((name, sync) in notLive(busy.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsNotEnabled().performClick()
            rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsNotEnabled().performClick()
            rule.waitForIdle()
            assertTrue("$name: a stale busy never interrupts", client.interruptCalls.isEmpty())
        }
        rule.onNodeWithContentDescription("Interrupt the current turn, unavailable: ${stopLockCopy(ConsentLock.CatchingUp)}").assertExists()
        rule.runOnIdle { client.sync.value = live(busy.id) }
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsEnabled().performClick()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t1", "${busy.id}@$TEST_ORIGIN#t1"), client.interruptCalls)
    }

    @Test
    fun offlineTheInterruptKeySaysItIsASavedCopy() {
        val busy = ComposerFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.show(busy, ComposerFixtures.busy, live = false)
        client.link.value = ConnectionState.Disconnected
        client.sync.value = mapOf(busy.id to SessionSync(Freshness.Saved, 1L))
        host(client, busy)
        rule.onNodeWithContentDescription("Interrupt the current turn, unavailable: ${stopLockCopy(ConsentLock.Offline)}").assertIsNotEnabled()
    }

    @Test
    fun aClientThatReportsNoFreshnessKeepsTheLiveSetRule() {
        // The documented path: no freshness reported at all, so the T6.3 live set alone decides.
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = true)
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.onNodeWithTag("approval-allow").assertIsEnabled()
        rule.runOnIdle { client.live.value = emptySet() }
        arm()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
    }

    // ---- r2: indicators of a copy that is not live -----------------------------------------------

    @Test
    fun aSavedCopysRunRowSaysWasRunningAndStopsTicking() {
        val busy = ComposerFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.show(busy, ComposerFixtures.busy, live = false)
        client.link.value = ConnectionState.Disconnected
        client.sync.value = mapOf(busy.id to SessionSync(Freshness.Saved, null))
        host(client, busy)
        rule.onNodeWithTag(STALE_RUN_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Was running")))

        rule.runOnIdle {
            client.link.value = ConnectionState.Connected
            client.live.value = setOf(busy.id)
            client.sync.value = live(busy.id)
        }
        arm()
        rule.onNodeWithTag(STALE_RUN_TAG).assertDoesNotExist()
    }

    @Test
    fun connectedANotDownloadedSessionShowsTheLoadingStateNotTheOfflineWords() {
        val client = ChatTestClient().also { it.reports = true }
        client.sessions.value = listOf(session)
        client.sync.value = mapOf("s1" to SessionSync(Freshness.NotDownloaded, null))
        host(client)
        rule.onNodeWithTag(ChatFreshness.NOT_DOWNLOADED_TAG).assertDoesNotExist()
        rule.onNodeWithText("Connecting to the session…").assertIsDisplayed()
    }

    @Test
    fun theChatHeadersEndSessionNeedsALiveCopy() {
        val running = session.copy(status = "active")
        val client = ChatTestClient().also { it.reports = true }
        client.show(running, ApprovalFixtures.write, live = true)
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        host(client, running, header = true)
        rule.onNodeWithContentDescription("End session").assertIsNotEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()

        rule.runOnIdle { client.sync.value = live("s1") }
        arm()
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        // T6.7: the web's words (dashboard.tsx:1904-1905), the name isolated.
        rule.onNodeWithText("End session?").assertExists()
        rule.onNodeWithText("\u2068s1\u2069 — its running process will stop.").assertExists()
        // ta-coik.13: the web's End session key (dashboard.tsx 90fbb9f :1909) ends on the first tap,
        // in the dialog's first frame: no arm delay.
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$TEST_ORIGIN:true"), client.killCalls)
    }

    /** r3: the confirmation is bound to the server it was opened for; a switch under it ends nothing. */
    @Test
    fun theChatHeadersEndSessionIsBoundToTheServerItWasOpenedFor() {
        val running = session.copy(status = "active")
        val client = ChatTestClient().also { it.reports = true }
        client.show(running, ApprovalFixtures.write, live = true)
        client.sync.value = live("s1")
        host(client, running, header = true)
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled()
        // Signed in to another server that lists (and has live) a session with the same id, while
        // the finger was down on the key: the key goes with the dialog, so the lift lands nowhere.
        confirmKey().performTouchInput { down(center) }
        rule.waitForIdle()
        rule.runOnIdle { client.origin.value = "https://other.example" }
        arm()
        // T6.7: a pending confirmation closes on a server switch: nothing is left to tap.
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertTrue("an End opened for one server ended a session on another: ${client.killCalls}", client.killCalls.isEmpty())

        // Opened afresh on the current server: it ends there, bound to that origin.
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@https://other.example:true"), client.killCalls)
    }

    // ta-9dpl: the folder is the outer rule, deleted only once the composition is gone: a preference
    // write still on the disk at the end can no longer fail (and fail the test) under a live screen.
    val tmp = org.junit.rules.TemporaryFolder()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(tmp).around(rule)
    private val storeJob = kotlinx.coroutines.Job()

    @org.junit.After fun closeStore() = storeJob.cancel()

    /**
     * T10.1 (dashboard.tsx:1170-1180): with Settings → General's "Confirm before ending" off, the
     * chat header's End session sends at once (still live-gated and bound to its server).
     */
    @Test
    fun theChatHeadersEndSessionHonoursConfirmBeforeEnd() {
        val running = session.copy(status = "active")
        val client = ChatTestClient().also { it.reports = true }
        client.show(running, ApprovalFixtures.write, live = true)
        client.sync.value = live("s1")
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + storeJob),
        ) { java.io.File(tmp.root, "ui.preferences_pb") }
        val prefs = UiPrefs.on(store)
        kotlinx.coroutines.runBlocking { prefs.updatePreferences { it.copy(confirmBeforeEnd = false) } }
        host(client, running, header = true, prefs = prefs)
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertEquals(listOf("s1@$TEST_ORIGIN:true"), client.killCalls)
    }

    private fun confirmKey() = rule.onNodeWithTag(END_SESSION_CONFIRM_TAG)
}
