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
import kotlinx.coroutines.test.advanceUntilIdle
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

        override val connection = MutableStateFlow<com.tether.app.client.ConnectionState>(com.tether.app.client.ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>("https://a.example:443")
        override val providers = MutableStateFlow(listOf(com.tether.app.protocol.model.ProviderInfo("claude", "Claude", "C", true)))
        override val workspaceRoot = MutableStateFlow<String?>("/w")
        val requests = mutableListOf<com.tether.app.client.NewSessionRequest>()

        override fun createNewSession(request: com.tether.app.client.NewSessionRequest, expectedOrigin: String?): com.tether.app.client.NewSessionResult {
            requests += request
            return com.tether.app.client.NewSessionResult.Sent
        }

        override fun resume(history: HistorySession): Boolean {
            if (!sends) return false
            resumed += history.historyId
            return true
        }

        fun created(session: AgentSession, requestId: String? = null) {
            sessions.value = listOf(session) + sessions.value.filter { it.id != session.id }
            createdSessions.value = CreatedReply(session, (createdSessions.value?.seq ?: 0L) + 1, requestId)
        }
    }

    private fun session(id: String, historyId: String? = null) = AgentSession(
        id = id, provider = "claude", name = id, cwd = "/w", status = "ready",
        startedAt = 1, updatedAt = 1, historyId = historyId,
    )

    private fun history(id: String) = HistorySession(historyId = id, provider = "claude", name = id, cwd = "/w", updatedAt = 1)

    private fun TestScope.vm(client: ResumeClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }).also { advanceUntilIdle() }

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
        advanceUntilIdle()
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
        advanceUntilIdle()
        assertNull(vm.selectedSessionId.value)
        client.created(session("resumed", historyId = "hist-1"))
        advanceUntilIdle()
        assertEquals("resumed", vm.selectedSessionId.value)
    }

    @Test fun aDedupHitReturningTheSameSessionAgainStillOpensIt() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        client.created(session("live", historyId = "hist-1"))
        advanceUntilIdle()
        vm.selectSession("other")
        vm.resumeHistory(history("hist-1"))
        client.created(session("live", historyId = "hist-1"))
        advanceUntilIdle()
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
        advanceUntilIdle()
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
        advanceUntilIdle()
        assertNull(vm.selectedSessionId.value)
        client.created(session("new"), requestId = client.requests.single().requestId)
        advanceUntilIdle()
        assertEquals("new", vm.selectedSessionId.value)
        assertEquals(listOf("new"), client.attached)
    }

    /** ta-8cv: while a create is in flight, a resume's reply (no requestId) never takes its place. */
    @Test fun aResumesReplyDoesNotHijackAPendingCreate() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.resumeHistory(history("hist-1"))
        vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443")
        client.created(session("resumed", historyId = "hist-1"))
        advanceUntilIdle()
        assertNull("the resume's reply is not selected over the pending create", vm.selectedSessionId.value)
        assertNull("its row is no longer opening", vm.openingHistoryId.value)
        assertTrue(vm.draftComposer.state.value.creating)
        client.created(session("new"), requestId = client.requests.single().requestId)
        advanceUntilIdle()
        assertEquals("new", vm.selectedSessionId.value)
        assertEquals(listOf("new"), client.attached)
    }

    /** ta-8cv (negative control): a create's reply with another requestId is never selected. */
    @Test fun aCreatedWithAnotherRequestIdIsNeverSelected() = runTest(dispatcher) {
        val client = ResumeClient()
        val vm = vm(client)
        vm.createNewSession(com.tether.app.client.NewSessionChoice("claude", "claude", null), "https://a.example:443")
        client.created(session("stale"), requestId = "not-this-one")
        advanceUntilIdle()
        assertNull(vm.selectedSessionId.value)
        assertTrue(vm.draftComposer.state.value.creating)
        // No create in flight: a stray create reply is still never followed.
        client.created(session("new"), requestId = client.requests.single().requestId)
        advanceUntilIdle()
        assertEquals("new", vm.selectedSessionId.value)
        client.created(session("late"), requestId = "another")
        advanceUntilIdle()
        assertEquals("new", vm.selectedSessionId.value)
    }
}
