package com.tether.app.ui

import com.tether.app.client.ConnectionState
import com.tether.app.client.Handover
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-9wdf / ta-74gu (v151): the view model's Stop handover (the stopping flag and its three clear rules), the
 * handover composer's entry, the v151 gates, and the menu / strip flows. Red first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelHandoverV151Test {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private open class Client : StubClient() {
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val serverProtocolVersion = MutableStateFlow<Int?>(151)
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val snapshotSeq = MutableStateFlow(1L)
        override val providerCatalog = MutableStateFlow(listOf(ProviderCatalogEntry("claude", "claude", "ready", emptyList())))
        override val providerCatalogLive = MutableStateFlow(true)
        val stopFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
        override val handoverStopRefusals: SharedFlow<String> = stopFlow
        val cancels = mutableListOf<Pair<String, String>>()
        var sends = true

        override fun handoverCancel(sessionId: String, requestId: String): Boolean {
            if (!sends) return false
            cancels += sessionId to requestId
            return true
        }
    }

    private fun s(id: String, block: (AgentSession) -> AgentSession = { it }) =
        block(AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "idle", startedAt = 1, updatedAt = 1))

    private fun TestScope.vm(client: Client) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }).also { runCurrent() }

    private fun staged(stage: String) = s("a") { it.copy(handoverStage = JsonPrimitive(stage)) }

    // ---- Stop handover ----
    @Test fun stopSendsTheCancelFrameWithAFreshRequestIdAndSetsTheStoppingFlag() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(staged("scheduled"))
        runCurrent()
        assertTrue(vm.stopHandover("a"))
        assertEquals("a", client.cancels.single().first)
        assertTrue(client.cancels.single().second.isNotEmpty())
        assertEquals(setOf("a"), vm.handoverStopping.value)
        assertTrue(vm.stopHandover("a"))
        assertTrue("a second press is a second request", client.cancels[0].second != client.cancels[1].second)
    }

    @Test fun aFailedStopSetsNoFlag() = runTest(dispatcher) {
        val client = Client().also { it.sends = false }
        val vm = vm(client)
        assertFalse(vm.stopHandover("a"))
        assertTrue(vm.handoverStopping.value.isEmpty())
    }

    @Test fun stopNeverGoesToAServerBelow151() = runTest(dispatcher) {
        val client = Client().also { it.serverProtocolVersion.value = 150 }
        val vm = vm(client)
        assertFalse(vm.stopHandover("a"))
        assertTrue(client.cancels.isEmpty())
    }

    @Test fun stoppingClearsOnceTheStageAppearedAndThenWentTheEcho() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(staged("scheduled")); runCurrent()
        vm.stopHandover("a")
        client.sessions.value = listOf(staged("waiting_background")); runCurrent()
        assertEquals("still underway: still stopping", setOf("a"), vm.handoverStopping.value)
        client.sessions.value = listOf(s("a")); runCurrent()
        assertTrue(vm.handoverStopping.value.isEmpty())
    }

    @Test fun stoppingClearsWhenTheSessionIsGone() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(staged("scheduled")); runCurrent()
        vm.stopHandover("a")
        client.sessions.value = emptyList(); runCurrent()
        assertTrue(vm.handoverStopping.value.isEmpty())
    }

    @Test fun aStopRefusalClearsThatSessionsFlag() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(staged("scheduled"), s("b")); runCurrent()
        vm.stopHandover("a"); vm.stopHandover("b")
        client.stopFlow.tryEmit("a"); runCurrent()
        assertEquals(setOf("b"), vm.handoverStopping.value)
    }

    @Test fun aSnapshotClearsEveryStoppingFlagAndAStillPresentStageReadsStopAgain() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(staged("scheduled")); runCurrent()
        vm.stopHandover("a")
        client.snapshotSeq.value = 2; runCurrent()
        assertTrue(vm.handoverStopping.value.isEmpty())
        assertTrue(vm.handoverMenuState("a").first() is Handover.MenuState.Stop)
    }

    // ---- the flows ----
    @Test fun theMenuStateGoesStartStopStoppingAndTheStripFollows() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(s("a")); runCurrent()
        assertEquals(Handover.MenuState.Enabled, vm.handoverMenuState("a").first())
        assertNull(vm.handoverStrip("a").first())
        client.sessions.value = listOf(staged("scheduled")); runCurrent()
        assertTrue(vm.handoverMenuState("a").first() is Handover.MenuState.Stop)
        val strip = vm.handoverStrip("a").first() as Handover.Strip.Stage
        assertEquals("Handover prompt written. The new session is starting.", strip.view.line)
        vm.stopHandover("a"); runCurrent()
        assertEquals(Handover.MenuState.Stopping, vm.handoverMenuState("a").first())
        assertEquals("Stopping the handover…", (vm.handoverStrip("a").first() as Handover.Strip.Stage).view.line)
    }

    @Test fun at150TheRowIsBlockedUnderWayAndTheStripIsTheW33Notice() = runTest(dispatcher) {
        val client = Client().also { it.serverProtocolVersion.value = 150 }
        val vm = vm(client)
        client.sessions.value = listOf(s("a") { it.copy(handoverPending = true) }); runCurrent()
        assertEquals(Handover.MenuState.Blocked(Handover.UNDER_WAY), vm.handoverMenuState("a").first())
        assertEquals(Handover.Strip.Legacy, vm.handoverStrip("a").first())
    }

    // ---- the handover composer's entry ----
    @Test fun changeSettingsOpensTheSheetInHandoverModeAndClosingRestoresTheDraft() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(s("a") { it.copy(handoverSuccessorName = JsonPrimitive("a 2")) }); runCurrent()
        vm.draftComposer.refresh()
        vm.draftComposer.setText("typed")
        assertTrue(vm.openHandoverDraft("a"))
        assertTrue(vm.draftOpen.value)
        assertEquals("a", vm.draftComposer.state.value.handover!!.sourceId)
        assertEquals("a 2", vm.draftComposer.state.value.name)
        vm.closeDraft()
        assertFalse(vm.draftOpen.value)
        assertNull(vm.draftComposer.state.value.handover)
        assertEquals("typed", vm.draftComposer.state.value.text)
    }

    @Test fun anEngineMissingFromTheCatalogOpensNothing() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(s("a") { it.copy(provider = "gemini") }); runCurrent()
        assertFalse(vm.openHandoverDraft("a"))
        assertFalse(vm.draftOpen.value)
        assertFalse(Handover.canChangeSettings(client.sessions.value.single(), client.providerCatalog.value))
    }

    @Test fun changeSettingsIsRefusedBelow151AndForAnUnlistedSource() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        client.sessions.value = listOf(s("a")); runCurrent()
        assertFalse(vm.openHandoverDraft("nope"))
        client.serverProtocolVersion.value = 150
        assertFalse(vm.openHandoverDraft("a"))
        assertFalse(vm.draftOpen.value)
    }

    @Test fun theComposerSendClaimsTheSourceAndASuccessHidesTheSheet() = runTest(dispatcher) {
        val client = object : Client() {
            override fun handoverRequestWithSettings(sessionId: String, requestId: String, successor: JsonObject): Boolean = true
        }
        val vm = vm(client)
        client.sessions.value = listOf(s("a")); runCurrent()
        vm.draftComposer.refresh()
        assertTrue(vm.openHandoverDraft("a"))
        vm.draftComposer.submitHandover()
        assertEquals(setOf("a"), vm.handoverRequested.value)
        client.sessions.value = listOf(s("a") { it.copy(handoverStage = JsonPrimitive("requested")) }); runCurrent()
        assertNull(vm.draftComposer.state.value.handover)
        assertFalse("success closes the sheet", vm.draftOpen.value)
        assertEquals(setOf("a"), vm.handoverRequested.value)
    }
}
