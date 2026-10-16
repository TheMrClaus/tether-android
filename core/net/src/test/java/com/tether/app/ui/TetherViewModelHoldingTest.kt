package com.tether.app.ui

import com.tether.app.client.ConnectionState
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** W34 (chat-view.tsx 62ceca46 :3488 `holding ? queueMessage : sendText`): a handover underway HOLDS a message even with no turn. */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelHoldingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Client : StubClient() {
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val queued = mutableListOf<String>()
        val sent = mutableListOf<String>()
        override fun queueAdd(sessionId: String, text: String) { queued += text }
        override fun send(sessionId: String, text: String, attachments: List<Attachment>) { sent += text }
    }

    private fun TestScope.vm(client: Client) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }).also { runCurrent() }

    @Test fun anIdleSessionSendsAndAHoldingOneQueues() = runTest(dispatcher) {
        val client = Client()
        val vm = vm(client)
        vm.sendOrQueue("a", "plain")
        vm.sendOrQueue("a", "held", holding = true)
        assertEquals(listOf("plain"), client.sent)
        assertEquals(listOf("held"), client.queued)
    }
}
