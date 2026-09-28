package com.tether.app.ui

import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T2.3 + T7.1: the view-model side of the per-session draft (the web's `tether:draft:<id>`,
 * chat-view.tsx:1561-1584 / 1731-1737): switching sessions surfaces each session's own
 * stored draft, edits are written through, "" (a send) removes the stored entry, and every
 * draft belongs to the server origin it was typed for (the web's localStorage is per origin;
 * T1.3/ta-s8q keys unsent input the same way).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class ServerClient(url: String?) : StubClient() {
        val url = MutableStateFlow(url)
        override val serverUrl: StateFlow<String?> get() = url
    }

    private fun TestScope.vm(store: DraftStore, client: ServerClient = ServerClient(URL_A)): TetherViewModel =
        TetherViewModel(client, store, monotonicClock = { testScheduler.currentTime })

    @Test
    fun switchingSessionsLoadsEachSessionsOwnDraft() = runTest(dispatcher) {
        val store = InMemoryDraftStore().apply {
            write(A, "a", "draft for A")
            write(A, "b", "draft for B")
        }
        val vm = vm(store)

        vm.selectSession("a")
        assertEquals("draft for A", vm.awaitDraft("a"))
        vm.selectSession("b")
        assertEquals("draft for B", vm.awaitDraft("b"))
        assertEquals("", vm.awaitDraft("c")) // never drafted
        // Back to A: still A's text, not B's.
        vm.selectSession("a")
        assertEquals("draft for A", vm.drafts.value["a"])
    }

    @Test
    fun editsAreWrittenThroughPerSessionAndSendClearsOnlyThatSession() = runTest(dispatcher) {
        val store = InMemoryDraftStore()
        val vm = vm(store)
        vm.selectSession("a")
        vm.awaitDraft("a")
        vm.setDraft("a", "h")
        vm.setDraft("a", "hello")
        vm.selectSession("b")
        vm.awaitDraft("b")
        vm.setDraft("b", "other")
        advanceUntilIdle()
        assertEquals("hello", store.read(A, "a"))
        assertEquals("other", store.read(A, "b"))

        vm.setDraft("a", "") // the composer clears on send
        advanceUntilIdle()
        assertEquals("", store.read(A, "a"))
        assertEquals("other", store.read(A, "b"))

        // A fresh view-model (process death) reads B's draft back from the store.
        val revived = vm(store)
        assertEquals("other", revived.awaitDraft("b"))
        assertEquals("", revived.awaitDraft("a"))
    }

    /** Text typed while the stored draft is still being read is never overwritten by it. */
    @Test
    fun aLateLoadNeverOverwritesTypedText() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val backing = InMemoryDraftStore().apply { write(A, "a", "stale") }
        val slow = object : DraftStore by backing {
            override suspend fun read(origin: String, sessionId: String): String {
                // Capture BEFORE waiting, like a real read that started before the user typed:
                // it must deliver the old "stale" value late (verifier: reading after the gate
                // returned the typed text and hid an overwrite bug).
                val captured = backing.read(origin, sessionId)
                gate.await()
                return captured
            }
        }
        val vm = vm(slow)
        vm.selectSession("a")
        advanceUntilIdle()
        assertNull("not loaded yet", vm.drafts.value["a"])
        vm.setDraft("a", "typed first")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("typed first", vm.drafts.value["a"])
        assertEquals("typed first", backing.read(A, "a"))
    }

    /**
     * T7.1 (per-origin isolation): a sign-in to another server empties the in-memory drafts;
     * the same session id there reads that server's own draft, writes land in its namespace,
     * and server A's text is back (from the store) when A is configured again.
     */
    @Test
    fun anotherServerSeesOnlyItsOwnDrafts() = runTest(dispatcher) {
        val store = InMemoryDraftStore().apply { write(B, "s", "B's own draft") }
        val client = ServerClient(URL_A)
        val vm = vm(store, client)
        vm.selectSession("s")
        vm.awaitDraft("s")
        vm.setDraft("s", "private text for A")
        advanceUntilIdle()

        client.url.value = URL_B
        advanceUntilIdle()
        assertNull("A's drafts leave with A", vm.drafts.value["s"])
        vm.selectSession("s")
        assertEquals("B's own draft", vm.awaitDraft("s"))
        vm.setDraft("s", "") // sent on B
        advanceUntilIdle()
        assertEquals("", store.read(B, "s"))
        assertEquals("private text for A", store.read(A, "s"))

        client.url.value = URL_A
        advanceUntilIdle()
        vm.selectSession("s")
        assertEquals("private text for A", vm.awaitDraft("s"))
    }

    /** A read that started for server A and finishes after the switch to B never lands in B. */
    @Test
    fun aLoadStartedForTheOldServerIsDropped() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val backing = InMemoryDraftStore().apply { write(A, "s", "A's text") }
        val slow = object : DraftStore by backing {
            override suspend fun read(origin: String, sessionId: String): String {
                val captured = backing.read(origin, sessionId)
                if (origin == A) gate.await()
                return captured
            }
        }
        val client = ServerClient(URL_A)
        val vm = vm(slow, client)
        vm.selectSession("s")
        advanceUntilIdle()
        client.url.value = URL_B
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.drafts.value["s"])
        assertEquals("", vm.awaitDraft("s"))
    }

    /** No server configured: a draft is kept in memory only, never attributed to an origin. */
    @Test
    fun withoutAServerADraftIsNeverPersisted() = runTest(dispatcher) {
        val writes = mutableListOf<String>()
        val store = object : DraftStore by InMemoryDraftStore() {
            override suspend fun write(origin: String, sessionId: String, text: String) {
                writes += origin
            }
        }
        val vm = vm(store, ServerClient(null))
        vm.selectSession("s")
        assertEquals("", vm.awaitDraft("s"))
        vm.setDraft("s", "nowhere to go")
        advanceUntilIdle()
        assertEquals("nowhere to go", vm.drafts.value["s"])
        assertEquals(emptyList<String>(), writes)
    }

    private companion object {
        const val URL_A = "https://tether-a.example"
        const val URL_B = "http://192.168.1.20:4173/"
        const val A = "https://tether-a.example:443"
        const val B = "http://192.168.1.20:4173"
    }
}
