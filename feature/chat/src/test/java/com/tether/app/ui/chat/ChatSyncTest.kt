package com.tether.app.ui.chat

import com.tether.app.testsupport.runPrefsWrite

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
import androidx.compose.ui.test.performTextInput
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
 * T13.2 in the chat (SYNC_DESIGN §4.2): ta-coik.24: an approval card from a saved copy is
 * answerable as on the web (the client sends on an open socket), a
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
            androidx.compose.runtime.CompositionLocalProvider(LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) { TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = header)
            } }
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

    /**
     * ta-coik.24: as on the web, a card is answerable whatever the copy (chat-view.tsx 90fbb9f
     * :1191-1210 disables only once `submitted`); the tap goes to the client, which sends on an open
     * socket (use-tether.ts :337-344).
     */
    @Test
    fun aSavedCopysCardIsAnswerableAsOnTheWeb() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = true)
        client.sync.value = saved()
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.onNodeWithText("Catching up", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    /**
     * ta-coik.24: offline the card is not locked either; the client refuses the closed link (it says
     * "The secure link is reconnecting. Your input was not sent.", as the web's `send`) and the card
     * stays answerable, as the web's (its `submitted` is set only by a send that went out).
     */
    @Test
    fun offlineTheCardStaysAnswerableAndATapTheLinkRefusedCanBeRepeated() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = false)
        client.link.value = ConnectionState.Disconnected
        client.sync.value = saved()
        client.consentResult = com.tether.app.client.ConsentResult.NotConnected
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.onNodeWithText(ConsentLock.Offline.copy).assertDoesNotExist()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(List(2) { "approval:s1:req-w:allow" }, client.consentCalls)
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

    // ---- r2 / ta-coik.22 / ta-coik.24: no non-Live freshness locks cards, Stop keys, controls or Interrupt

    @Test
    fun everyCopyThatIsNotLiveLeavesTheCardAnswerable() {
        val client = ChatTestClient().also { it.reports = true }
        client.show(session, ApprovalFixtures.write, live = true)
        client.sync.value = live("s1")
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        for ((name, sync) in notLive("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("approval-allow").assertIsEnabled()
            rule.onNodeWithTag("consent-lock").assertDoesNotExist()
        }
        // One decision still: the tap on a copy that is not live reaches the client once.
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    /**
     * ta-coik.22 (item 4): the web leaves Stop live on a copy that is not live (chat-view.tsx 90fbb9f
     * :3866-3875; the socket sends only when open). Here a tap asks the client, which re-checks the
     * link and the live set under its lock (its own tests), on every kind of copy.
     */
    @Test
    fun theStopKeyStaysLiveOnEveryCopyThatIsNotLive() {
        val stopSession = SubagentFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.show(stopSession, ChatFixtures.fold(*ChatFixtures.turn("t1", "Run it.", "Started.", 1_000), cmd("a", 2_000)), live = true)
        client.sync.value = live(stopSession.id)
        host(client, stopSession)
        rule.onNodeWithTag("bg-command-stop").assertIsEnabled()
        var expected = 0
        for ((name, sync) in notLive(stopSession.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("bg-command-stop").assertIsEnabled().performClick()
            rule.waitForIdle()
            expected++
            assertEquals("$name: the tap reaches the client", expected, client.stopCalls.size)
        }
        rule.runOnIdle { client.sync.value = live(stopSession.id) }
        arm()
        rule.onNodeWithTag("bg-command-stop").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(List(expected + 1) { "s1:a" }, client.stopCalls)
    }

    /** ta-coik.24: chat-view.tsx 90fbb9f :2503, :2970, :3018, :4491, none gated on the link or the copy. */
    @Test
    fun everyCopyThatIsNotLiveLeavesTheSessionControlsLive() {
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
            rule.onNodeWithTag("session-settings-trigger").assertIsEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
        // Offline too.
        rule.runOnIdle { client.link.value = ConnectionState.Disconnected }
        arm()
        rule.onNodeWithTag("session-settings-trigger").assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertTrue("nothing is sent without a tap", client.controlCalls.isEmpty())
    }

    /**
     * ta-coik.22: the web leaves Interrupt and "Interrupt now" live on a copy that is not live
     * (chat-view.tsx 90fbb9f :4559-4573, :1495-1506). Each tap goes to the client, bound to the server
     * and the turn the key was drawn for; the client re-checks the link and the live set.
     */
    @Test
    fun bothInterruptKeysStayLiveOnEveryCopyThatIsNotLiveAndAreBoundToTheirServer() {
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
            rule.onNodeWithContentDescription("Interrupt the current turn").assertIsEnabled()
            rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsEnabled().performClick()
            rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsEnabled().performClick()
            rule.waitForIdle()
            assertEquals("$name: both taps reach the client", 2, client.interruptCalls.size)
            client.interruptCalls.clear()
        }
        rule.runOnIdle { client.sync.value = live(busy.id) }
        arm()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsEnabled().performClick()
        rule.onNodeWithTag(QUEUE_INTERRUPT_TAG).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${busy.id}@$TEST_ORIGIN#t1", "${busy.id}@$TEST_ORIGIN#t1"), client.interruptCalls)
    }

    /**
     * ta-coik.22 (item 4): the web leaves the command keys live on a copy that is not live
     * (chat-view.tsx 90fbb9f :4535-4541 Background, :4559-4573 Stop, :4576-4596 Send to agent /
     * Background): a click goes to the socket, which sends only when open. Here every tap goes to the
     * client (which re-checks the link and the live set under its lock), on every kind of copy.
     */
    @Test
    fun theCommandKeysStayLiveOnEveryCopyThatIsNotLive() {
        val s = ComposerFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.providers.value = listOf(
            com.tether.app.protocol.model.ProviderInfo(
                id = s.provider, label = "Claude", glyph = "C", available = true,
                capabilities = com.tether.app.protocol.model.ProviderCapabilities(commandRunner = true),
            ),
        )
        client.show(s, CommandFixtures.running, live = true)
        client.sync.value = live(s.id)
        host(client, s)
        for ((name, sync) in notLive(s.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithContentDescription("Send this command to the background").assertIsEnabled().performClick()
            rule.onNodeWithContentDescription("Stop the command").assertIsEnabled().performClick()
            rule.waitForIdle()
            assertEquals("$name: Background reaches the client", listOf("${s.id}#t1"), client.backgroundCalls)
            assertEquals("$name: Stop reaches the client", listOf("${s.id}@$TEST_ORIGIN#t1"), client.interruptCalls)
            client.backgroundCalls.clear()
            client.interruptCalls.clear()
        }
        // Idle, a "!" draft on a saved copy: Send to agent and Background run.
        rule.runOnIdle {
            client.show(s, ComposerFixtures.idle, live = false)
            client.sync.value = mapOf(s.id to SessionSync(Freshness.Saved, 1L))
        }
        arm()
        val input = rule.onNodeWithContentDescription("Message the agent")
        input.performTextInput("!npm test")
        arm()
        rule.onNodeWithContentDescription("Run command and send output to the agent").assertIsEnabled().performClick()
        rule.waitForIdle()
        input.performTextInput("!sleep 30")
        arm()
        rule.onNodeWithContentDescription("Run command in the background").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${s.id}:npm test", "${s.id}:sleep 30:bg"), client.runCalls)
    }

    /** ta-coik.22: offline the Interrupt key stays live, as the web's; the client says the link is down. */
    @Test
    fun offlineTheInterruptKeyStaysLive() {
        val busy = ComposerFixtures.session
        val client = ChatTestClient().also { it.reports = true }
        client.show(busy, ComposerFixtures.busy, live = false)
        client.link.value = ConnectionState.Disconnected
        client.sync.value = mapOf(busy.id to SessionSync(Freshness.Saved, 1L))
        client.interruptResult = com.tether.app.client.InterruptResult.NotConnected
        host(client, busy)
        rule.onNodeWithContentDescription("Interrupt the current turn").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, client.interruptCalls.size)
    }

    @Test
    fun aClientThatReportsNoFreshnessLeavesTheCardAnswerableOffTheLiveSet() {
        // No freshness reported at all: ta-coik.24, leaving the live set no longer locks the card
        // either (as on the web, which has no live set).
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = true)
        host(client)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.onNodeWithTag("approval-allow").assertIsEnabled()
        rule.runOnIdle { client.live.value = emptySet() }
        arm()
        rule.onNodeWithTag("approval-allow").assertIsEnabled()
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

    /**
     * ta-coik.22 r2: the web's header End session is disabled only once the session exited
     * (workspace-header.tsx 90fbb9f :133) and its confirm key never (dashboard.tsx :1909). On a saved
     * copy both act: the kill goes to the client, which sends while the socket is open.
     */
    @Test
    fun theChatHeadersEndSessionIsLiveOnASavedCopy() {
        val running = session.copy(status = "active")
        val client = ChatTestClient().also { it.reports = true }
        client.show(running, ApprovalFixtures.write, live = true)
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        host(client, running, header = true)
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        // T6.7: the web's words (dashboard.tsx:1904-1905), the name isolated.
        rule.onNodeWithText("End session?").assertExists()
        rule.onNodeWithText("\u2068s1\u2069 — its running process will stop.").assertExists()
        // ta-coik.13: the web's End session key (dashboard.tsx 90fbb9f :1909) ends on the first tap.
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$TEST_ORIGIN"), client.killCalls)
    }

    /**
     * ta-coik.22 r2: offline (no link, so no live origin) the header key and the confirmation are live
     * too, bound to the configured server; the client then says the session was not ended (the
     * web's "reconnecting, not sent").
     */
    @Test
    fun offlineTheChatHeadersEndSessionIsBoundToTheConfiguredServer() {
        val running = session.copy(status = "active")
        val client = ChatTestClient().also { it.reports = true }
        client.show(running, ApprovalFixtures.write, live = false)
        client.link.value = ConnectionState.Disconnected
        client.origin.value = null
        client.server.value = "$TEST_ORIGIN/"
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        host(client, running, header = true)
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$TEST_ORIGIN"), client.killCalls)
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
        // the finger was down on the key: the lift ends nothing.
        confirmKey().performTouchInput { down(center) }
        rule.waitForIdle()
        rule.runOnIdle { client.origin.value = "https://other.example" }
        arm()
        confirmKey().performTouchInput { up() }
        arm()
        // ta-coik.22: as on the web the confirmation stays open, its key disabled: it was opened for
        // the other server.
        rule.onNodeWithText("End session?").assertExists()
        confirmKey().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("an End opened for one server ended a session on another: ${client.killCalls}", client.killCalls.isEmpty())

        // Cancelled, then opened afresh on the current server: it ends there, bound to that origin.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@https://other.example"), client.killCalls)
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
        runPrefsWrite { prefs.updatePreferences { it.copy(confirmBeforeEnd = false) } }
        host(client, running, header = true, prefs = prefs)
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertEquals(listOf("s1@$TEST_ORIGIN"), client.killCalls)
    }

    /**
     * ta-coik.54 (ChatScreen's header read): "Confirm before ending" is the configured server's own, as the
     * web's preferences are per origin. Both ways round, so a read of another server's record, of the
     * device-wide one, or of a constant, fails one of them.
     */
    private fun headerOnServer(server: String, a: Boolean, b: Boolean, deviceWide: Boolean): ChatTestClient {
        val running = session.copy(status = "active")
        val client = ChatTestClient().also { it.reports = true }
        client.show(running, ApprovalFixtures.write, live = true)
        client.sync.value = live("s1")
        client.server.value = server
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + storeJob),
        ) { java.io.File(tmp.root, "ui.preferences_pb") }
        val prefs = UiPrefs.on(store)
        runPrefsWrite {
            prefs.updatePreferencesFor("https://a.example:443") { it.copy(confirmBeforeEnd = a) }
            prefs.updatePreferencesFor("https://b.example:443") { it.copy(confirmBeforeEnd = b) }
            prefs.updatePreferencesFor(null) { it.copy(confirmBeforeEnd = deviceWide) }
        }
        host(client, running, header = true, prefs = prefs)
        rule.onNodeWithContentDescription("End session").assertIsEnabled().performClick()
        rule.waitForIdle()
        return client
    }

    @Test
    fun theHeaderEndsAtOnceWhenThisServerTurnedConfirmOffThoughAnotherAndTheDefaultAsk() {
        val client = headerOnServer("https://a.example", a = false, b = true, deviceWide = true)
        rule.onNodeWithText("End session?").assertDoesNotExist()
        assertEquals(listOf("s1@$TEST_ORIGIN"), client.killCalls)
    }

    @Test
    fun theHeaderAsksWhenThisServerKeepsConfirmOnThoughAnotherAndTheDefaultAreOff() {
        val client = headerOnServer("https://a.example", a = true, b = false, deviceWide = false)
        rule.onNodeWithText("End session?").assertExists()
        assertTrue(client.killCalls.isEmpty())
    }

    private fun confirmKey() = rule.onNodeWithTag(END_SESSION_CONFIRM_TAG)
}
