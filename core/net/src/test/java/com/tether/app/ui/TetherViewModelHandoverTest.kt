package com.tether.app.ui

import com.tether.app.client.ConnectionState
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ta-ozn2.3: the focus rule and the requested set (dashboard.tsx:1214-1290) in the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelHandoverTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class HandoverClient(var sends: Boolean = true) : StubClient() {
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val serverProtocolVersion = MutableStateFlow<Int?>(150)
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val refusalFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override val handoverRefusals: SharedFlow<Unit> = refusalFlow
        val requests = mutableListOf<String>()
        override fun handoverRequest(sessionId: String): Boolean {
            if (!sends) return false
            requests += sessionId
            return true
        }
    }

    private fun s(id: String, block: (AgentSession) -> AgentSession = { it }) =
        block(AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "idle", startedAt = 1, updatedAt = 1))

    private fun TestScope.vm(client: HandoverClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }).also { runCurrent() }

    private fun TestScope.open(client: HandoverClient, vm: TetherViewModel, id: String) {
        client.sessions.value = listOf(s(id))
        vm.selectSession(id)
        runCurrent()
    }

    @Test fun successorArrivingAfterTheSourceUpdateNavigates() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        assertTrue(vm.requestHandover("a"))
        runCurrent()
        assertEquals(setOf("a"), vm.handoverRequested.value)
        client.sessions.value = listOf(s("a") { it.copy(handedOffTo = "b") }) // source first, successor not listed
        runCurrent()
        assertEquals("a", vm.selectedSessionId.value)
        assertEquals(setOf("a"), vm.handoverRequested.value)
        client.sessions.value = client.sessions.value + s("b") { it.copy(handedOffFrom = "a") }
        runCurrent()
        assertEquals("b", vm.selectedSessionId.value)
        assertTrue(vm.handoverRequested.value.isEmpty())
    }

    @Test fun successorCreatedBeforeTheSourceUpdateNavigates() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.sessions.value = listOf(s("a"), s("b") { it.copy(handedOffFrom = "a") })
        runCurrent()
        assertEquals("a", vm.selectedSessionId.value)
        client.sessions.value = listOf(s("a") { it.copy(handedOffTo = "b") }, s("b") { it.copy(handedOffFrom = "a") })
        runCurrent()
        assertEquals("b", vm.selectedSessionId.value)
    }

    @Test fun userSwitchedAwayFirstDropsTheClaimWithoutNavigating() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        client.sessions.value = listOf(s("a"), s("c"))
        vm.selectSession("a"); runCurrent()
        vm.requestHandover("a")
        vm.selectSession("c"); runCurrent()
        client.sessions.value = listOf(s("a") { it.copy(handedOffTo = "b") }, s("b"), s("c"))
        runCurrent()
        assertEquals("c", vm.selectedSessionId.value)
        assertTrue(vm.handoverRequested.value.isEmpty())
    }

    @Test fun anAgentInitiatedHandoverNeverNavigates() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        client.sessions.value = listOf(s("a") { it.copy(handedOffTo = "b", handoverPending = true) }, s("b"))
        runCurrent()
        assertEquals("a", vm.selectedSessionId.value)
    }

    @Test fun aRefusalClearsTheUnechoedClaim() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.refusalFlow.tryEmit(Unit)
        runCurrent()
        assertTrue(vm.handoverRequested.value.isEmpty())
    }

    @Test fun aRefusalKeepsAnEchoedClaim() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.sessions.value = listOf(s("a") { it.copy(handoverPending = true) })
        runCurrent()
        client.refusalFlow.tryEmit(Unit)
        runCurrent()
        assertEquals(setOf("a"), vm.handoverRequested.value)
    }

    @Test fun echoedPendingThenClearedWithNoLinkClears() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.sessions.value = listOf(s("a") { it.copy(handoverPending = true) })
        runCurrent()
        assertEquals(setOf("a"), vm.handoverRequested.value)
        client.sessions.value = listOf(s("a"))
        runCurrent()
        assertTrue(vm.handoverRequested.value.isEmpty())
    }

    @Test fun aClaimSettlesWhenItsRowDisappears() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.sessions.value = emptyList()
        runCurrent()
        assertTrue(vm.handoverRequested.value.isEmpty())
    }

    @Test fun aFailedSendSetsNoFlag() = runTest(dispatcher) {
        val client = HandoverClient(sends = false)
        val vm = vm(client)
        open(client, vm, "a")
        assertFalse(vm.requestHandover("a"))
        assertTrue(vm.handoverRequested.value.isEmpty())
    }

    @Test fun theBannerLinkDropsTheClaim() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.sessions.value = listOf(s("a"), s("b"))
        vm.openHandoverPeer("a", "b")
        runCurrent()
        assertTrue(vm.handoverRequested.value.isEmpty())
        assertEquals("b", vm.selectedSessionId.value)
    }

    @Test fun aReconnectKeepsTheClaimAndAReloadForgetsIt() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        vm.requestHandover("a")
        client.connection.value = ConnectionState.Disconnected
        runCurrent()
        client.connection.value = ConnectionState.Connected
        client.sessions.value = listOf(s("a"))
        runCurrent()
        assertEquals(setOf("a"), vm.handoverRequested.value)
        assertTrue("a new view model (a reload) starts with nothing", vm(client).handoverRequested.value.isEmpty())
    }

    @Test fun theMenuStateFollowsTheClaimAndTheServerVersion() = runTest(dispatcher) {
        val client = HandoverClient()
        val vm = vm(client)
        open(client, vm, "a")
        val seen = mutableListOf<Any?>()
        val job = backgroundScope.launch(dispatcher) { vm.handoverMenuState("a").collect { seen += it } }
        runCurrent()
        assertEquals(com.tether.app.client.Handover.MenuState.Enabled, seen.last())
        vm.requestHandover("a"); runCurrent()
        assertEquals(com.tether.app.client.Handover.MenuState.Blocked("Handover under way."), seen.last())
        client.serverProtocolVersion.value = 149; runCurrent()
        assertEquals(com.tether.app.client.Handover.MenuState.Hidden, seen.last())
        job.cancel()
    }
}
