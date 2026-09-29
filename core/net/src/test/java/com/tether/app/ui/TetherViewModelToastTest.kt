package com.tether.app.ui

import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T6.7: the one error toast says who wrote its words. The client's own ([TetherClient.errors],
 * local failures) are the app's; the server's ([TetherClient.serverErrors], already cleaned by the
 * client) are marked as the server's, so MainShell draws them under "From the server".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelToastTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class ToastClient : StubClient() {
        val local = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val server = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val errors: SharedFlow<String> get() = local
        override val serverErrors: SharedFlow<String> get() = server
    }

    private fun TestScope.vm(client: ToastClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime })

    @Test
    fun theServersWordsAreMarkedAsTheServers() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit("Session not found.")
        advanceUntilIdle()
        assertEquals(Toast("Session not found.", fromServer = true), vm.toast.value)
        assertEquals("Session not found.", vm.activeToast.value)
    }

    @Test
    fun theClientsWordsAndLocalErrorsAreTheApps() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit("from the server")
        advanceUntilIdle()
        client.local.tryEmit("The secure link is reconnecting. The turn was not interrupted.")
        advanceUntilIdle()
        assertEquals(Toast("The secure link is reconnecting. The turn was not interrupted.", fromServer = false), vm.toast.value)
        vm.reportLocalError("Not connected")
        assertEquals(Toast("Not connected", fromServer = false), vm.toast.value)
        vm.dismissToast()
        assertNull(vm.toast.value)
        assertNull(vm.activeToast.value)
    }
}
