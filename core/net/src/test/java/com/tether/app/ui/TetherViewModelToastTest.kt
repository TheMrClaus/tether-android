package com.tether.app.ui

import com.tether.app.client.ServerErrorText
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val server = MutableSharedFlow<ServerErrorText>(extraBufferCapacity = 8)
        val url = MutableStateFlow<String?>(A)
        val live = MutableStateFlow<String?>(A)
        override val errors: SharedFlow<String> get() = local
        override val serverErrors: SharedFlow<ServerErrorText> get() = server
        override val serverUrl: StateFlow<String?> get() = url
        override val consentOrigin: StateFlow<String?> get() = live
    }

    private fun TestScope.vm(client: ToastClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime })

    @Test
    fun theServersWordsAreMarkedAsTheServers() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit(ServerErrorText("Session not found.", A))
        advanceUntilIdle()
        assertEquals(Toast("Session not found.", fromServer = true, origin = A), vm.toast.value)
        assertEquals("Session not found.", vm.activeToast.value)
    }

    @Test
    fun theClientsWordsAndLocalErrorsAreTheApps() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit(ServerErrorText("from the server", A))
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

    /** r2: a server's words never survive the switch to another server (configured or linked). */
    @Test
    fun aServerToastIsDroppedWhenTheServerChanges() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit(ServerErrorText("Session not found.", A))
        advanceUntilIdle()
        assertEquals(A, vm.toast.value?.origin)
        client.url.value = B
        advanceUntilIdle()
        assertNull("A's words on B's screen", vm.toast.value)

        // The link moved (same configured server, another origin on the socket): dropped too.
        client.url.value = A
        advanceUntilIdle()
        client.server.tryEmit(ServerErrorText("Forbidden.", A))
        advanceUntilIdle()
        assertEquals("Forbidden.", vm.toast.value?.text)
        client.live.value = B
        advanceUntilIdle()
        assertNull(vm.toast.value)
    }

    @Test
    fun aServerToastFromAnotherServerIsNeverShown() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit(ServerErrorText("late words from A", B))
        advanceUntilIdle()
        assertNull(vm.toast.value)
    }

    /** r3 (verifier): with no configured server, the live link alone decides: another origin's words are not shown. */
    @Test
    fun withNoConfiguredServerTheLiveOriginAloneFilters() = runTest(dispatcher) {
        val client = ToastClient()
        client.url.value = null
        client.live.value = B
        val vm = vm(client)
        advanceUntilIdle()
        client.server.tryEmit(ServerErrorText("words from A", A))
        advanceUntilIdle()
        assertNull("A's words shown on B's link", vm.toast.value)
        client.server.tryEmit(ServerErrorText("words from B", B))
        advanceUntilIdle()
        assertEquals(Toast("words from B", fromServer = true, origin = B), vm.toast.value)
    }

    @Test
    fun theAppsOwnToastSurvivesAServerSwitch() = runTest(dispatcher) {
        val client = ToastClient()
        val vm = vm(client)
        advanceUntilIdle()
        client.local.tryEmit("1 unsent message was not sent to this server.")
        advanceUntilIdle()
        client.url.value = B
        client.live.value = B
        advanceUntilIdle()
        assertEquals("1 unsent message was not sent to this server.", vm.toast.value?.text)
    }

    private companion object {
        const val A = "https://a.example:443"
        const val B = "https://b.example:443"
    }
}
