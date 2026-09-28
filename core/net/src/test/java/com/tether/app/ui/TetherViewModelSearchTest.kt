package com.tether.app.ui

import com.tether.app.client.LogoutResult
import com.tether.app.ui.prefs.InMemoryDraftStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T5.3: the view-model half of dashboard.tsx's search state (421-427, 1157-1194): the modal's
 * open flag and form, the find request with its nonce, and the resets the web gets for free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelSearchTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class SearchClient : StubClient() {
        val calls = mutableListOf<String>()
        override fun clearGlobalSearch() {
            calls += "clearGlobalSearch"
        }
        override fun clearSearchResults() {
            calls += "clearSearchResults"
        }
        override fun discover(cwd: String) {
            calls += "discover $cwd"
        }
        override suspend fun logout(): LogoutResult = LogoutResult.Revoked
    }

    private fun TestScope.vm(client: SearchClient) =
        TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { testScheduler.currentTime }).also { advanceUntilIdle() }

    @Test fun closingTheModalClearsTheResultsButKeepsTheForm() = runTest(dispatcher) {
        val client = SearchClient()
        val vm = vm(client)
        assertFalse(vm.globalSearchOpen.value)
        vm.openGlobalSearch()
        vm.updateGlobalSearchForm(GlobalSearchForm(text = "parity", providers = listOf("codex"), timeWindow = "7d", scopeToWorkspace = true))
        assertTrue(vm.globalSearchOpen.value)
        assertEquals(emptyList<String>(), client.calls)
        vm.closeGlobalSearch()
        assertFalse(vm.globalSearchOpen.value)
        assertEquals(listOf("clearGlobalSearch"), client.calls)
        // global-search.tsx keeps its own state while closed (the component stays mounted).
        vm.openGlobalSearch()
        assertEquals("parity", vm.globalSearchForm.value.text)
        assertEquals("7d", vm.globalSearchForm.value.timeWindow)
    }

    @Test fun everyFindRequestTakesANewNonce() = runTest(dispatcher) {
        val vm = vm(SearchClient())
        assertNull(vm.findRequest.value)
        vm.requestFind("parity", "hist-1")
        val first = vm.findRequest.value!!
        vm.requestFind("parity", "hist-1")
        val second = vm.findRequest.value!!
        assertEquals(FindRequest("parity", first.nonce, "hist-1"), first)
        assertTrue("the same query on the same conversation re-triggers", second.nonce > first.nonce)
        assertTrue(second != first)
    }

    @Test fun switchingWorkspaceDropsTheContentHitsBeforeTheDiscover() = runTest(dispatcher) {
        val client = SearchClient()
        val vm = vm(client)
        vm.selectWorkspace("/w/app")
        assertEquals(listOf("clearSearchResults", "discover /w/app"), client.calls)
        vm.selectWorkspace("/w/app")
        assertEquals("the same workspace is a no-op", 2, client.calls.size)
    }

    @Test fun signingOutForgetsTheSearchState() = runTest(dispatcher) {
        val vm = vm(SearchClient())
        vm.openGlobalSearch()
        vm.updateGlobalSearchForm(GlobalSearchForm(text = "secret"))
        vm.requestFind("secret", "h")
        vm.logout()
        advanceUntilIdle()
        assertFalse(vm.globalSearchOpen.value)
        assertEquals(GlobalSearchForm(), vm.globalSearchForm.value)
        assertNull(vm.findRequest.value)
    }
}
