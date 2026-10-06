package com.tether.app.ui

import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.ConnectionState
import com.tether.app.client.LogoutResult
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T7.4: the view model's staged attachments and its half of the send gate. What is staged belongs to
 * ONE session on ONE server: it survives a rotation (this view model outlives the activity), and it
 * is dropped on a server switch (the configured server or the live link moving), a sign-out, a switch
 * to another session, and when its session locks. Only an explicit Send transmits it, bound to the
 * server the composer was drawn for; offline it is refused and kept, never held for later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelAttachmentTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    private val vms = TestViewModels()

    @After
    fun tearDown() {
        // r2 hygiene: the view models' scopes end before Main is reset (see TestViewModels).
        vms.clear()
        Dispatchers.resetMain()
    }

    private class Call(val sessionId: String, val text: String, val attachments: List<Attachment>, val mention: DelegateMention?, val origin: String?)

    private class AttachClient : StubClient() {
        val url = MutableStateFlow<String?>(URL_A)
        val link = MutableStateFlow<String?>(A)
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val list = MutableStateFlow(listOf(session("s1"), session("s2")))
        val conf = MutableStateFlow(true)
        var answer = AttachmentSendResult.Sent
        val calls = mutableListOf<Call>()
        val plainSends = mutableListOf<Pair<String, List<Attachment>>>()
        override val serverUrl: StateFlow<String?> get() = url
        override val consentOrigin: StateFlow<String?> get() = link
        override val connection: StateFlow<ConnectionState> get() = conn
        override val sessions: StateFlow<List<AgentSession>> get() = list
        override val configured: StateFlow<Boolean> get() = conf
        override fun sendAttachments(sessionId: String, text: String, attachments: List<Attachment>, mention: DelegateMention?, expectedOrigin: String?, expectedEpoch: Long?): AttachmentSendResult {
            calls += Call(sessionId, text, attachments, mention, expectedOrigin)
            return answer
        }
        override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
            plainSends += text to attachments
        }
        override suspend fun logout(): LogoutResult = LogoutResult.Revoked
    }

    private fun TestScope.vm(client: AttachClient = AttachClient()): Pair<TetherViewModel, AttachClient> {
        val vm = vms.track(TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }))
        advanceUntilIdle()
        return vm to client
    }

    private val pic = Attachment("pic.png", "image/png", "iVBORw0KGgo=")

    private fun TetherViewModel.stage(sessionId: String = "s1") {
        stagedAttachments.set(attachmentOrigin(), sessionId, listOf(StagedAttachment(stagedAttachments.newId(), pic, 8)))
    }

    private fun TetherViewModel.staged(sessionId: String = "s1") = stagedAttachments.items(attachmentOrigin(), sessionId)

    @Test
    fun stagedAttachmentsAreKeyedByServerAndSessionAndSurviveWhileBothHold() = runTest(dispatcher) {
        val (vm, _) = vm()
        vm.selectSession("s1")
        vm.stage()
        assertEquals(1, vm.staged().size)
        // Another session, or the same session under another server, sees none of them.
        assertTrue(vm.stagedAttachments.items(A, "s2").isEmpty())
        assertTrue(vm.stagedAttachments.items("http://b.example:80", "s1").isEmpty())
        // Re-selecting the same session (a rotation re-reads the view model) keeps them.
        vm.selectSession("s1")
        advanceUntilIdle()
        assertEquals(1, vm.staged().size)
    }

    @Test
    fun aServerSwitchDropsThem() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.stage()
        client.url.value = URL_B
        advanceUntilIdle()
        assertEquals(null, vm.stagedAttachments.current.value)
    }

    @Test
    fun theLiveLinkMovingToAnotherServerDropsThem() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.stage()
        // Offline (no link): kept, the operator may send them when it is back.
        client.link.value = null
        advanceUntilIdle()
        assertEquals(1, vm.staged().size)
        client.link.value = B
        advanceUntilIdle()
        assertEquals(null, vm.stagedAttachments.current.value)
    }

    @Test
    fun aSessionThatLocksDropsThem() = runTest(dispatcher) {
        for (locked in listOf(session("s1").copy(readOnly = true), session("s1").copy(handedOffTo = "s9"))) {
            val (vm, client) = vm()
            vm.stage()
            client.list.value = listOf(session("s1").copy(updatedAt = 2))
            advanceUntilIdle()
            assertEquals("an unlocked update keeps them", 1, vm.staged().size)
            client.list.value = listOf(locked.copy(updatedAt = 3))
            advanceUntilIdle()
            assertEquals("locked: $locked", null, vm.stagedAttachments.current.value)
        }
    }

    /** ta-coik.60: the web's composer does not lock on an archived runtime (chat-view.tsx 29537e0), nor does a pick. */
    @Test
    fun anArchivedRuntimeIsNotALock() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.selectSession("s1")
        vm.stage()
        advanceUntilIdle()
        assertEquals(true, vm.attachmentsAllowed("s1"))
        client.list.value = listOf(session("s1").copy(updatedAt = 2, runtimeArchived = true))
        advanceUntilIdle()
        assertEquals("an archived session still takes picks", true, vm.attachmentsAllowed("s1"))
        assertEquals("and keeps what is staged", 1, vm.staged().size)
    }

    @Test
    fun switchingToAnotherSessionOrSigningOutDropsThem() = runTest(dispatcher) {
        val (vm, _) = vm()
        vm.selectSession("s1")
        vm.stage()
        vm.selectSession("s2")
        assertEquals(null, vm.stagedAttachments.current.value)
        vm.selectSession("s1")
        vm.stage()
        vm.logout()
        advanceUntilIdle()
        assertEquals(null, vm.stagedAttachments.current.value)
    }

    @Test
    fun aSendIsBoundToTheServerTheComposerWasDrawnFor() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.stage()
        assertEquals(AttachmentSendResult.NotLive, vm.sendAttachments("s1", "x", null, B))
        assertTrue("the client was asked", client.calls.isEmpty())
        assertEquals(1, vm.staged().size)
    }

    /** ta-coik.69: a composer drawn with no server recorded (offline) is no other server's: it goes to the client. */
    @Test
    fun aSendDrawnWithNoServerRecordedIsPassedToTheClient() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.stage()
        assertEquals(AttachmentSendResult.Sent, vm.sendAttachments("s1", "x", null, null))
        assertEquals(listOf<String?>(null), client.calls.map { it.origin })
        assertEquals(0, vm.staged().size)
    }

    @Test
    fun offlineIsRefusedTheAttachmentsKeptAndNothingHeld() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.stage()
        client.conn.value = ConnectionState.Disconnected
        assertEquals(AttachmentSendResult.NotConnected, vm.sendAttachments("s1", "x", null, A))
        assertTrue(client.calls.isEmpty())
        assertEquals("kept for an explicit resend", 1, vm.staged().size)
        // The link comes back: nothing goes out on its own.
        client.conn.value = ConnectionState.Connected
        advanceUntilIdle()
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun aSentMessageClearsTheSetARefusedOneKeepsIt() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.stage()
        client.answer = AttachmentSendResult.Busy
        assertEquals(AttachmentSendResult.Busy, vm.sendAttachments("s1", "x", null, A))
        assertEquals(1, vm.staged().size)
        client.answer = AttachmentSendResult.Sent
        val m = DelegateMention("claude", "review")
        assertEquals(AttachmentSendResult.Sent, vm.sendAttachments("s1", "look", m, A))
        assertEquals(null, vm.stagedAttachments.current.value)
        val call = client.calls.last()
        assertEquals(listOf(pic), call.attachments)
        assertEquals(m, call.mention)
        assertEquals(A, call.origin)
        // Nothing staged: nothing to send (the text-only path is the composer's other key).
        assertEquals(AttachmentSendResult.Empty, vm.sendAttachments("s1", "x", null, A))
    }

    @Test
    fun theTextPathNeverCarriesAttachments() = runTest(dispatcher) {
        val (vm, client) = vm()
        assertEquals(false, vm.sendOrQueue("s1", "x", listOf(pic)))
        assertEquals(false, vm.sendDelegated("s1", "x", listOf(pic), DelegateMention("claude", "a"), A))
        assertTrue(client.plainSends.isEmpty())
        assertEquals(true, vm.sendOrQueue("s1", "plain"))
        assertEquals(listOf("plain" to emptyList<Attachment>()), client.plainSends)
    }

    // --- r2: every drop trigger drops a FIRST pick still being read (L4a, verifier M1) ---------------

    @Test
    fun aSwitchToAnotherSessionDropsAFirstPickStillBeingReadButReselectingKeepsIt() = runTest(dispatcher) {
        val (vm, _) = vm()
        vm.selectSession("s1")
        val g = vm.stagedAttachments.generation
        // A rotation re-selects the same session: a pick being read for it is kept.
        vm.selectSession("s1")
        assertEquals(g, vm.stagedAttachments.generation)
        // Nothing staged yet, and still: another selection ends the pick for s1.
        vm.selectSession("s2")
        assertTrue("no drop with nothing staged", vm.stagedAttachments.generation > g)
        assertEquals(false, vm.attachmentsAllowed("s1"))
        assertEquals(true, vm.attachmentsAllowed("s2"))
    }

    @Test
    fun theSelectedSessionLockingDropsAFirstPickStillBeingRead() = runTest(dispatcher) {
        for (locked in listOf(session("s1").copy(readOnly = true), session("s1").copy(handedOffTo = "s9"))) {
            val (vm, client) = vm()
            vm.selectSession("s1")
            advanceUntilIdle()
            assertEquals(true, vm.attachmentsAllowed("s1"))
            val g = vm.stagedAttachments.generation
            client.list.value = listOf(session("s1").copy(updatedAt = 2), session("s2"))
            advanceUntilIdle()
            assertEquals("an unlocked update drops nothing", g, vm.stagedAttachments.generation)
            client.list.value = listOf(locked.copy(updatedAt = 3), session("s2"))
            advanceUntilIdle()
            assertTrue("locked, nothing staged: $locked", vm.stagedAttachments.generation > g)
            assertEquals(false, vm.attachmentsAllowed("s1"))
        }
    }

    @Test
    fun aServerChangeOrALinkMoveDropsAFirstPickStillBeingRead() = runTest(dispatcher) {
        val (vm, client) = vm()
        var g = vm.stagedAttachments.generation
        client.url.value = URL_B
        advanceUntilIdle()
        assertTrue(vm.stagedAttachments.generation > g)
        g = vm.stagedAttachments.generation
        // The link drops and comes back to the same server: nothing is dropped.
        client.link.value = null
        advanceUntilIdle()
        client.link.value = A
        advanceUntilIdle()
        assertEquals(g, vm.stagedAttachments.generation)
        client.link.value = B
        advanceUntilIdle()
        assertTrue(vm.stagedAttachments.generation > g)
    }

    @Test
    fun aRevocationWithTheSameServerUrlDropsThemAndAnyPickBeingRead() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.selectSession("s1")
        vm.stage()
        val g = vm.stagedAttachments.generation
        // Revoked (or signed out elsewhere): configured goes false, the server URL stays.
        client.conf.value = false
        advanceUntilIdle()
        assertEquals(null, vm.stagedAttachments.current.value)
        assertTrue(vm.stagedAttachments.generation > g)
        // Re-paired to the same server: nothing from before comes back.
        client.conf.value = true
        advanceUntilIdle()
        assertTrue(vm.staged().isEmpty())
    }

    @Test
    fun aSessionThatLeavesTheListDropsThem() = runTest(dispatcher) {
        val (vm, client) = vm()
        vm.selectSession("s1")
        vm.stage()
        client.list.value = listOf(session("s2"))
        advanceUntilIdle()
        assertEquals(null, vm.stagedAttachments.current.value)
        assertEquals(false, vm.attachmentsAllowed("s1"))
    }

    @Test
    fun signingOutDropsAPickBeingReadAtOnce() = runTest(dispatcher) {
        val (vm, _) = vm()
        vm.selectSession("s1")
        val g = vm.stagedAttachments.generation
        vm.logout()
        // Before the logout's own call returns (nothing has run yet on Main).
        assertTrue(vm.stagedAttachments.generation > g)
        advanceUntilIdle()
    }

    private companion object {
        const val A = "http://a.example:80"
        const val B = "http://b.example:80"
        const val URL_A = "http://a.example"
        const val URL_B = "http://b.example"

        fun session(id: String) = AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)
    }
}
