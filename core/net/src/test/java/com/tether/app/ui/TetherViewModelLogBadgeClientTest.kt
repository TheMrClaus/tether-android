package com.tether.app.ui

import com.tether.app.client.ConnectionHarness
import com.tether.app.client.ConnectionState
import com.tether.app.client.HEALTH_132
import com.tether.app.client.LoginResult
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T4.5 (verify round 2): the badge's acknowledged mark with the real [com.tether.app.client.RealTetherClient]
 * over a MockWebServer "Tether", the main thread held while the client signs in again, so the view
 * model never observes the empty log in between (StateFlow conflation). The sign-in generation,
 * not an observed empty state, is what starts the mark over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelLogBadgeClientTest {
    private val main = StandardTestDispatcher()
    private val h = ConnectionHarness()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        h.close()
        Dispatchers.resetMain()
    }

    private fun warns(boot: Long, vararg seqs: Long) =
        """{"type":"log","bootId":$boot,"entries":[${seqs.joinToString(",") { """{"seq":$it,"ts":$it,"level":"warn","event":"session.evict","reason":"idle"}""" }}]}"""

    private fun connect(): WebSocket {
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun signInAgainWhileMainIsHeld(): WebSocket {
        h.server.enqueue(MockResponse().setBody(HEALTH_132))
        h.server.enqueue(MockResponse().addHeader("Set-Cookie", "tether_session=fresh; Path=/").setBody("""{"ok":true}"""))
        h.enqueueConnect()
        val result = runBlocking { h.client.login(h.server.url("/").toString(), "pw") }
        assertTrue("$result", result is LoginResult.Success)
        return connect()
    }

    @Test
    fun aReSignInStartsTheMarkOverEvenWhenTheViewModelNeverSeesTheEmptyLog() {
        h.enqueueConnect()
        h.newClient()
        val vm = TetherViewModel(h.client, InMemoryDraftStore(), monotonicClock = { 0 })
        h.client.start()
        val first = connect()
        first.send(warns(1, 1, 2, 3))
        h.await(h.client.eventLog) { it.entries.size == 3 }
        vm.openLog()
        // Main stays held from here: the re-login's cleared log and the new boot's replay land
        // back to back, and the view model's collectors only run afterwards.
        val second = signInAgainWhileMainIsHeld()
        second.send(warns(2, 1, 2))
        h.await(h.client.eventLog) { it.bootId == "2" && it.entries.size == 2 }
        main.scheduler.advanceUntilIdle()
        assertEquals(2, vm.unseenWarnings.value)
        assertEquals(ConnectionState.Connected, h.client.connection.value)
    }

    @Test
    fun controlTheSameFlowWithMainRunningAfterTheSignIn() {
        h.enqueueConnect()
        h.newClient()
        val vm = TetherViewModel(h.client, InMemoryDraftStore(), monotonicClock = { 0 })
        h.client.start()
        val first = connect()
        first.send(warns(1, 1, 2, 3))
        h.await(h.client.eventLog) { it.entries.size == 3 }
        vm.openLog()
        main.scheduler.advanceUntilIdle()
        val second = signInAgainWhileMainIsHeld()
        main.scheduler.advanceUntilIdle()
        second.send(warns(2, 1, 2))
        h.await(h.client.eventLog) { it.bootId == "2" && it.entries.size == 2 }
        main.scheduler.advanceUntilIdle()
        assertEquals(2, vm.unseenWarnings.value)
    }
}
