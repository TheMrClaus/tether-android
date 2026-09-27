package com.tether.app.ui

import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * T2.3: the view-model side of the per-session draft (the web's `tether:draft:<id>`,
 * chat-view.tsx:1561-1584 / 1731-1737): switching sessions surfaces each session's own
 * stored draft, edits are written through, and "" (a send) removes the stored entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(store: DraftStore): TetherViewModel =
        TetherViewModel(StubClient(), store, monotonicClock = { testScheduler.currentTime })

    @Test
    fun switchingSessionsLoadsEachSessionsOwnDraft() = runTest(dispatcher) {
        val store = InMemoryDraftStore().apply {
            write("a", "draft for A")
            write("b", "draft for B")
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
        assertEquals("hello", store.read("a"))
        assertEquals("other", store.read("b"))

        vm.setDraft("a", "") // the composer clears on send
        advanceUntilIdle()
        assertEquals("", store.read("a"))
        assertEquals("other", store.read("b"))

        // A fresh view-model (process death) reads B's draft back from the store.
        val revived = vm(store)
        assertEquals("other", revived.awaitDraft("b"))
        assertEquals("", revived.awaitDraft("a"))
    }

    /** Text typed while the stored draft is still being read is never overwritten by it. */
    @Test
    fun aLateLoadNeverOverwritesTypedText() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val backing = InMemoryDraftStore().apply { write("a", "stale") }
        val slow = object : DraftStore by backing {
            override suspend fun read(sessionId: String): String {
                // Capture BEFORE waiting, like a real read that started before the user typed:
                // it must deliver the old "stale" value late (verifier: reading after the gate
                // returned the typed text and hid an overwrite bug).
                val captured = backing.read(sessionId)
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
        assertEquals("typed first", backing.read("a"))
    }
}
