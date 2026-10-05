package com.tether.app.ui

import com.tether.app.client.CreatedReply
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T5.2: the view-model half of the web's reopen (dashboard.tsx:202-206, 394-409, 708-722): a sent
 * `resume` makes the row the opening one and clears the selection; the unicast `created` reply is
 * followed; any explicit selection retires the opening row; a refusal leaves it as the web does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelResumeTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class ResumeClient(var sends: Boolean = true) : StubClient() {
        val resumed = mutableListOf<String>()
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val createdSessions = MutableStateFlow<CreatedReply?>(null)
        override val createErrors = MutableStateFlow<com.tether.app.client.CreateErrorReply?>(null)

        override val connection = MutableStateFlow<com.tether.app.client.ConnectionState>(com.tether.app.client.ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>("https://a.example:443")
        override val providers = MutableStateFlow(listOf(com.tether.app.protocol.model.ProviderInfo("claude", "Claude", "C", true)))
        override val workspaceRoot = MutableStateFlow<String?>("/w")
        override val serverUrl = MutableStateFlow<String?>("https://a.example:443")
        val requests = mutableListOf<com.tether.app.client.NewSessionRequest>()
        val sent = mutableListOf<Pair<String, String>>()

        override fun send(sessionId: String, text: String, attachments: List<com.tether.app.protocol.Attachment>) {
            sent += sessionId to text
        }

        override fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean {
            if (consentOrigin.value != expectedOrigin) return false
            sent += sessionId to text
            return true
        }

        /** ta-8cv r2: the replies in order, never conflated (the real client's createdReplies). */
        val replies = kotlinx.coroutines.flow.MutableSharedFlow<CreatedReply>(extraBufferCapacity = 16)
        override val createdReplies: kotlinx.coroutines.flow.Flow<CreatedReply> = replies

        override fun createNewSession(request: com.tether.app.client.NewSessionRequest, expectedOrigin: String?): com.tether.app.client.NewSessionResult {
            requests += request
            return com.tether.app.client.NewSessionResult.Sent
        }

        /** ta-m7ef: the setup checks that went out (an isolated Send asks before it creates). */
        val setupChecks = mutableListOf<String>()
        val sources = kotlinx.coroutines.flow.MutableSharedFlow<com.tether.app.client.WorktreeSourceReply>(extraBufferCapacity = 16)
        override val worktreeSources: kotlinx.coroutines.flow.Flow<com.tether.app.client.WorktreeSourceReply> = sources

        override fun inspectSetup(cwd: String, worktree: com.tether.app.protocol.WorktreeCreateRequest, requestId: String, expectedEpoch: Long): Boolean {
            setupChecks += requestId
            return true
        }

        override fun resume(history: HistorySession): Boolean {
            if (!sends) return false
            resumed += history.historyId
            return true
        }

        fun created(session: AgentSession, requestId: String? = null) {
            sessions.value = listOf(session) + sessions.value.filter { it.id != session.id }
            val reply = CreatedReply(session, (createdSessions.value?.seq ?: 0L) + 1, requestId)
            createdSessions.value = reply
            replies.tryEmit(reply)
        }
    }

    private fun session(id: String, historyId: String? = null) = AgentSession(
        id = id, provider = "claude", name = id, cwd = "/w", status = "ready",
        startedAt = 1, updatedAt = 1, historyId = historyId,
    )

    private fun history(id: String) = HistorySession(historyId = id, provider = "claude", name = id, cwd = "/w", updatedAt = 1)

    private fun TestScope.vm(client: ResumeClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }).also { runCurrent() }

    @Test fun aSentResumeOpensTheRowAndTheCreatedReplySelectsItsSession() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.selectSession("previous")
        client.attached.clear()

        assertTrue(vm.resumeHistory(history("hist-1")))
        assertEquals(listOf("hist-1"), client.resumed)
        assertEquals("hist-1", vm.openingHistoryId.value)
        assertNull("the previous chat does not stay on screen during the round trip", vm.selectedSessionId.value)

        client.created(session("fresh", historyId = "hist-1"))
        runCurrent()
        assertEquals("fresh", vm.selectedSessionId.value)
        assertNull(vm.openingHistoryId.value)
        assertEquals("attached exactly once", listOf("fresh"), client.attached)
    }

    @Test fun theCreatedReplyWinsOverAnOlderSessionOfTheSameConversation() = runTest(dispatcher) {
        // An ended session of the same conversation is already in the list; the server's
        // reply names the NEW session, and that is the one that opens.
        val client = ResumeClient()
        client.sessions.value = listOf(session("ended", historyId = "hist-1"))
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        runCurrent()
        assertNull(vm.selectedSessionId.value)
        client.created(session("resumed", historyId = "hist-1"))
        runCurrent()
        assertEquals("resumed", vm.selectedSessionId.value)
    }

    @Test fun aDedupHitReturningTheSameSessionAgainStillOpensIt() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        client.created(session("live", historyId = "hist-1"))
        runCurrent()
        vm.selectSession("other")
        vm.resumeHistory(history("hist-1"))
        client.created(session("live", historyId = "hist-1"))
        runCurrent()
        assertEquals("live", vm.selectedSessionId.value)
    }

    @Test fun anUnsentResumeChangesNothing() = runTest(dispatcher) {
        val client = ResumeClient(sends = false)
        val vm = vm(client)
        vm.selectSession("previous")
        assertFalse(vm.resumeHistory(history("hist-1")))
        assertNull(vm.openingHistoryId.value)
        assertEquals("previous", vm.selectedSessionId.value)
    }

    @Test fun aRefusedResumeKeepsTheRowOpeningUntilTheOperatorPicksSomething() = runTest(dispatcher) {
        // The refusal is an `error` toast (no `created`), and the web leaves openingHistoryId set.
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("gone"))
        runCurrent()
        assertEquals("gone", vm.openingHistoryId.value)
        // dashboard.tsx:227 selectActiveId: every explicit selection retires it.
        vm.selectSession("s1")
        assertNull(vm.openingHistoryId.value)
        assertEquals("s1", vm.selectedSessionId.value)
    }

    @Test fun aReplyThatLandedBeforeTheViewModelIsNotFollowed() = runTest(dispatcher) {
        val client = ResumeClient()
        client.created(session("old"))
        val vm = vm(client)
        assertNull(vm.selectedSessionId.value)
    }

    @Test fun theProviderPickersCreateIsSelectedOnceFromItsOwnReply() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        assertEquals(
            com.tether.app.client.DraftSubmitResult.Sent,
            vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443"),
        )
        // ta-8cv: another device's new session landing in the list is NOT this create's.
        client.sessions.value = listOf(session("someone-elses"))
        runCurrent()
        assertNull(vm.selectedSessionId.value)
        client.created(session("new"), requestId = client.requests.single().requestId)
        runCurrent()
        assertEquals("new", vm.selectedSessionId.value)
        assertEquals(listOf("new"), client.attached)
    }

    /**
     * ta-8cv r2 (verifier P3): a resume that overlaps a create opens as it lands (the web's dashboard
     * opens every `created`); it never completes the create, which still opens ITS session when its
     * own reply lands, and the resumed one is never lost if the create fails.
     */
    @Test fun aResumeOverlappingACreateOpensAndTheCreateStillCompletesOnItsOwnReply() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443")
        client.created(session("resumed", historyId = "hist-1"))
        runCurrent()
        assertEquals("the resumed session opens", "resumed", vm.selectedSessionId.value)
        assertNull(vm.openingHistoryId.value)
        assertTrue("the create still waits for its own reply", vm.draftComposer.state.value.creating)
        client.created(session("new"), requestId = client.requests.single().requestId)
        runCurrent()
        assertEquals("new", vm.selectedSessionId.value)
        assertEquals(listOf("resumed", "new"), client.attached)
    }

    @Test fun aResumeOverlappingACreateThatFailsStaysOpen() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443")
        client.created(session("resumed", historyId = "hist-1"))
        runCurrent()
        client.createErrors.value = com.tether.app.client.CreateErrorReply("refused", 1, client.requests.single().requestId)
        runCurrent()
        assertFalse(vm.draftComposer.state.value.creating)
        assertEquals("refused", vm.draftComposer.state.value.error)
        assertEquals("resumed", vm.selectedSessionId.value)
    }

    /** ta-8cv r2 (security F2): two replies in one burst (a resume's and the create's) are both acted on. */
    @Test fun twoRepliesInOneBurstAreBothActedOn() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443")
        // Both land before the collector runs: the latest-value flow would keep only the second.
        client.created(session("new"), requestId = client.requests.single().requestId)
        client.created(session("resumed", historyId = "hist-1"))
        assertEquals("resumed", client.createdSessions.value?.session?.id)
        runCurrent()
        assertFalse("the create completed on its own reply", vm.draftComposer.state.value.creating)
        assertEquals(1, vm.draftComposer.state.value.completed)
        assertEquals("both opened, in order", listOf("new", "resumed"), client.attached)
        assertEquals("resumed", vm.selectedSessionId.value)
    }

    /** ta-8cv: the first message goes out once, from the matched reply, into the selected session. */
    @Test fun theDraftsFirstMessageGoesToItsOwnNewSession() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        val composer = vm.draftComposer
        composer.selectProvider("claude")
        composer.setText("first words")
        assertEquals(com.tether.app.client.DraftSubmitResult.Sent, composer.submit("https://a.example:443"))
        client.created(session("other"), requestId = "not-mine")
        runCurrent()
        assertTrue(client.sent.isEmpty())
        client.created(session("new"), requestId = client.requests.single().requestId)
        runCurrent()
        assertEquals(listOf("new" to "first words"), client.sent)
        assertEquals("new", vm.selectedSessionId.value)
    }

    /** ta-8cv: an orphaned first message is the new session's draft (stored and shown), never resent. */
    @Test fun anOrphanedFirstMessageBecomesTheNewSessionsDraft() = runTest(dispatcher) {
        val client = ResumeClient()
        val store = InMemoryDraftStore()
        val vm = TetherViewModel(client, store, monotonicClock = { testScheduler.currentTime }).also { runCurrent() }
        val composer = vm.draftComposer
        composer.selectProvider("claude")
        composer.setText("do not lose me")
        composer.submit("https://a.example:443")
        // The link went to another server between the reply and the first send.
        client.consentOrigin.value = "https://b.example:443"
        client.created(session("new"), requestId = client.requests.single().requestId)
        runCurrent()
        assertTrue("never sent", client.sent.isEmpty())
        assertEquals("do not lose me", store.read("https://a.example:443", "new"))
        assertEquals("do not lose me", vm.drafts.value["new"])
        assertEquals("", composer.state.value.text)
    }

    /** ta-8cv (negative control): a create's reply with another requestId is never selected. */
    @Test fun aCreatedWithAnotherRequestIdIsNeverSelected() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443")
        client.created(session("stale"), requestId = "not-this-one")
        runCurrent()
        assertNull(vm.selectedSessionId.value)
        assertTrue(vm.draftComposer.state.value.creating)
        // No create in flight: a stray create reply is still never followed.
        client.created(session("new"), requestId = client.requests.single().requestId)
        runCurrent()
        assertEquals("new", vm.selectedSessionId.value)
        client.created(session("late"), requestId = "another")
        runCurrent()
        assertEquals("new", vm.selectedSessionId.value)
    }

    // --- ta-abm: the new-session sheet's open flag (dashboard.tsx draftOpen) -------------------------

    @Test fun theSheetStaysOpenThroughItsCreateAndClosesOnItsCreated() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.openDraft()
        vm.draftComposer.refresh()
        vm.draftComposer.selectProvider("claude")
        vm.draftComposer.setText("first words")
        assertEquals(com.tether.app.client.DraftSubmitResult.Sent, vm.draftComposer.submit("https://a.example:443"))
        runCurrent()
        assertTrue("still composing while the create is in flight", vm.draftOpen.value)
        client.created(session("new"), requestId = client.requests.single().requestId)
        runCurrent()
        assertFalse(vm.draftOpen.value)
        assertEquals("new", vm.selectedSessionId.value)
        assertEquals(listOf("new" to "first words"), client.sent)
    }

    @Test fun aSelectionAResumeAndAServerSwitchCloseTheSheetOnlyTheSwitchDropsTheDraft() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.openDraft()
        vm.draftComposer.setText("kept")
        client.sessions.value = listOf(session("s1"))
        runCurrent()
        vm.selectSession("s1")
        assertFalse(vm.draftOpen.value)
        assertEquals("kept", vm.draftComposer.state.value.text)
        vm.openDraft()
        vm.resumeHistory(history("h1"))
        assertFalse(vm.draftOpen.value)
        assertEquals("kept", vm.draftComposer.state.value.text)
        vm.openDraft()
        vm.closeDraft()
        assertFalse(vm.draftOpen.value)
        vm.openDraft()
        client.serverUrl.value = "https://b.example:443"
        runCurrent()
        assertFalse(vm.draftOpen.value)
        assertEquals("", vm.draftComposer.state.value.text)
    }

    /**
     * ta-m7ef: Send on an isolated draft asks what the create would run and creates only with the answer's
     * consent (here: nothing declared, so "none"); closing the sheet while the check waits cancels it.
     */
    @Test fun anIsolatedSendChecksThenCreatesWithTheConsent() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        runCurrent()
        vm.draftComposer.refresh()
        vm.draftComposer.selectProvider("claude")
        vm.draftComposer.setText("first words")
        assertTrue(vm.draftComposer.selectIsolation("branch-off"))
        vm.openDraft()
        assertEquals(com.tether.app.client.DraftSubmitResult.Checking, vm.draftComposer.submit("https://a.example:443"))
        assertTrue("nothing is created before the answer", client.requests.isEmpty())
        assertTrue(vm.draftComposer.state.value.setupChecking)
        val none = com.tether.app.client.WorktreeSetupPreview("branch-off", "origin", "origin/main", "c".repeat(40), emptyList(), emptyList(), null, true, null, false, null, "none", "none", null)
        client.sources.tryEmit(
            com.tether.app.client.WorktreeSourceReply(com.tether.app.client.WorktreeSourceInfo(isRepo = true, setupPreview = none), client.setupChecks.single(), client.linkEpoch.value),
        )
        runCurrent()
        assertEquals(1, client.requests.size)
        assertEquals("none", client.requests.single().setupConsent)
        assertEquals(com.tether.app.protocol.tree.JsBool.TRUE, client.requests.single().form["useWorktree"])
        assertEquals("", vm.draftComposer.state.value.error)
    }

    @Test fun closingTheSheetCancelsAPendingSetupCheck() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        runCurrent()
        vm.draftComposer.refresh()
        vm.draftComposer.selectProvider("claude")
        vm.draftComposer.setText("first words")
        assertTrue(vm.draftComposer.selectIsolation("branch-off"))
        vm.openDraft()
        assertEquals(com.tether.app.client.DraftSubmitResult.Checking, vm.draftComposer.submit("https://a.example:443"))
        vm.closeDraft()
        assertFalse(vm.draftComposer.state.value.setupChecking)
        assertNull(vm.draftComposer.state.value.setupConfirmation)
        client.sources.tryEmit(
            com.tether.app.client.WorktreeSourceReply(
                com.tether.app.client.WorktreeSourceInfo(isRepo = true), client.setupChecks.single(), client.linkEpoch.value,
            ),
        )
        runCurrent()
        assertTrue("a late answer starts nothing", client.requests.isEmpty())
    }

    /** ta-abm r2 (F2, T7.4 r2 L4b): a sign-out drops the draft's attachments and any pick still being read. */
    @Test fun aSignOutDropsTheDraftsAttachmentsAndKeepsItsText() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.draftComposer.setText("kept")
        vm.draftComposer.setAttachments(listOf(com.tether.app.protocol.Attachment(name = "a.png", mediaType = "image/png", data = "AAAA")))
        val reading = vm.draftComposer.attachmentGeneration
        vm.logout()
        runCurrent()
        assertTrue(vm.draftComposer.state.value.staged.isEmpty())
        assertEquals("kept", vm.draftComposer.state.value.text)
        val late = com.tether.app.client.StagedAttachment(99, com.tether.app.protocol.Attachment(name = "late.png", mediaType = "image/png", data = "AAAA"), 3)
        assertFalse("a pick read before the sign-out never lands", vm.draftComposer.addAttachments(listOf(late), reading))
        assertTrue(vm.draftComposer.state.value.staged.isEmpty())
    }
}
