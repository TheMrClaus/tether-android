package com.tether.app.ui

import com.tether.app.client.EventLog
import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.ServerMessage
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
import org.junit.Before
import org.junit.Test

/**
 * T4.5 (the T4.1 verifier's defect): the topbar Health badge counts only the warnings logged since
 * the log was last opened (dashboard.tsx:194-199: `seenWarnAt`, `max(0, warnCount - seenWarnAt)`,
 * `openLog` acknowledges the current count). Info entries and client-side errors never count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelLogBadgeTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class LogClient : StubClient() {
        val log = MutableStateFlow(EventLog())
        override val eventLog: StateFlow<EventLog> = log

        fun push(boot: String, vararg levels: String) {
            val last = log.value.entries.lastOrNull()?.seq ?: 0
            val entries = levels.mapIndexed { i, level -> LogEntry(seq = last + i + 1, level = level, event = "e") }
            log.value = log.value.accept(ServerMessage.Log(entries, boot))
        }
    }

    private fun TestScope.vm(client: LogClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime })

    @Test
    fun onlyNonInfoEntriesCount() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        client.push("b", "info", "warn", "info", "error")
        advanceUntilIdle()
        assertEquals(2, vm.unseenWarnings.value)
    }

    @Test
    fun openingTheLogClearsTheBadgeAndOnlyNewWarningsCountAfter() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        client.push("b", "warn", "warn", "warn")
        advanceUntilIdle()
        assertEquals(3, vm.unseenWarnings.value)
        vm.openLog()
        advanceUntilIdle()
        assertEquals(0, vm.unseenWarnings.value)
        client.push("b", "info", "warn")
        advanceUntilIdle()
        assertEquals(1, vm.unseenWarnings.value)
        vm.openLog()
        advanceUntilIdle()
        assertEquals(0, vm.unseenWarnings.value)
    }

    @Test
    fun aRestartThatEmptiesTheLogNeverGoesNegativeAndCountsPastTheMark() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        client.push("b1", "warn", "warn")
        advanceUntilIdle()
        vm.openLog()
        client.push("b2", "warn") // a restart: the log holds 1 warning, the mark is 2
        advanceUntilIdle()
        assertEquals(0, vm.unseenWarnings.value)
        client.push("b2", "warn", "warn")
        advanceUntilIdle()
        assertEquals(1, vm.unseenWarnings.value)
    }

    @Test
    fun signingOutAndBackInStartsTheMarkOver() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        client.push("b1", "warn", "warn", "warn")
        advanceUntilIdle()
        vm.openLog()
        advanceUntilIdle()
        assertEquals(0, vm.unseenWarnings.value)
        client.log.value = EventLog() // sign-out: RealTetherClient.clearSignInViews()
        advanceUntilIdle()
        client.push("b1", "warn", "warn") // the same server after sign-in replays its tail
        advanceUntilIdle()
        assertEquals(2, vm.unseenWarnings.value)
    }

    @Test
    fun switchingServersStartsTheMarkOver() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        client.push("server-a", "warn", "error", "warn", "warn")
        advanceUntilIdle()
        vm.openLog()
        client.log.value = EventLog() // a new sign-in to another server
        advanceUntilIdle()
        client.push("server-b", "error")
        advanceUntilIdle()
        assertEquals(1, vm.unseenWarnings.value)
    }

    @Test
    fun aServerRestartKeepsTheMark() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        client.push("b1", "warn", "warn", "warn")
        advanceUntilIdle()
        vm.openLog()
        advanceUntilIdle()
        client.push("b2", "warn", "warn") // new bootId, never an empty bootless log
        advanceUntilIdle()
        assertEquals("the restarted log's 2 warnings are under the mark of 3", 0, vm.unseenWarnings.value)
    }

    @Test
    fun clientSideErrorsNoLongerFeedTheBadge() = runTest(dispatcher) {
        val client = LogClient()
        val vm = vm(client)
        vm.reportLocalError("Not connected")
        advanceUntilIdle()
        assertEquals(0, vm.unseenWarnings.value)
        assertEquals("Not connected", vm.activeToast.value)
    }
}
