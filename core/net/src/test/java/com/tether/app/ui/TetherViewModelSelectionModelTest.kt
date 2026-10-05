package com.tether.app.ui

import com.tether.app.client.ConnectionState
import com.tether.app.client.CreatedReply
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.prefs.LastOpenedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-coik.41: the web's selection model (dashboard.tsx 90fbb9f). A selection whose chat left the
 * list is kept (nothing clears `activeId`); the one-time pick (:752-763) runs only on Sessions with
 * nothing selected, pending or opening and a non-empty list; the remembered chat is seeded at boot
 * (:736-748) and restored or given up (:856-886); navigating to Sessions with nothing selected takes
 * the remembered chat when it is listed (:1352-1364). Nothing here attaches: the chat view's mount does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelSelectionModelTest {
    private val main = StandardTestDispatcher()
    private val vms = TestViewModels()

    private class Client : StubClient() {
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val url = MutableStateFlow<String?>("https://a.example")
        override val serverUrl: kotlinx.coroutines.flow.StateFlow<String?> get() = url
        val mounted = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        override fun attachMounted(sessionId: String) {
            mounted += sessionId
        }
        override fun resumeHistory(historyId: String, cwd: String) {
            resumed += historyId
        }
        val replies = MutableSharedFlow<CreatedReply>(extraBufferCapacity = 4)
        override val createdReplies: Flow<CreatedReply> get() = replies
    }

    private fun chat(id: String, cwd: String = "/w", status: String = "ready") =
        AgentSession(id = id, provider = "claude", name = id, cwd = cwd, status = status, startedAt = 1, updatedAt = 1, historyId = "h-$id")

    private fun history(id: String) = HistorySession(historyId = id, provider = "claude", name = id, cwd = "/w", updatedAt = 1)

    private val remembered = LastOpenedSession("/w", "gone", "h-gone")

    private lateinit var client: Client
    private lateinit var vm: TetherViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        client = Client()
        vm = vms.track(TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { 0 }))
        main.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        vms.clear()
        main.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    /** r2: the server's `ready` has listed the sessions (Connected follows it). */
    private fun goLive() {
        client.connection.value = ConnectionState.Connected
        main.scheduler.runCurrent()
    }

    private fun noAttach() {
        assertEquals("nothing attached by the model", emptyList<String>(), client.attached + client.mounted)
    }

    // --- the selection is kept when its chat leaves the list -------------------------------------

    @Test
    fun aSelectionWhoseChatLeavesTheListIsKeptAndIsBackWhenItReturns() {
        client.sessions.value = listOf(chat("a"), chat("b"))
        main.scheduler.advanceUntilIdle()
        vm.selectSession("a")
        client.sessions.value = listOf(chat("b")) // archived / handed off / gone from the list
        main.scheduler.advanceUntilIdle()
        assertEquals("a", vm.selectedSessionId.value)
        client.sessions.value = emptyList() // a transient empty list (a reconnect) changes nothing
        main.scheduler.advanceUntilIdle()
        assertEquals("a", vm.selectedSessionId.value)
        client.sessions.value = listOf(chat("a"), chat("b")) // back in the list: still the selection
        main.scheduler.advanceUntilIdle()
        assertEquals("a", vm.selectedSessionId.value)
    }

    @Test
    fun noPickOverASelectionWhoseChatLeftTheList() {
        goLive()
        vm.onBootView(sessionsView = true, remembered = null)
        vm.selectSession("a")
        vm.pickIfNothingSelected(sessionsView = true, visible = listOf(chat("b")), currentWorkspace = "/w")
        assertEquals("a", vm.selectedSessionId.value)
    }

    // --- the one-time pick -----------------------------------------------------------------------

    @Test
    fun thePickTakesTheFirstChatInTheCurrentWorkspaceElseTheFirstListedAndAttachesNothing() {
        goLive()
        vm.onBootView(sessionsView = true, remembered = null)
        vm.pickIfNothingSelected(true, listOf(chat("x", cwd = "/other"), chat("y", cwd = "/w"), chat("z", cwd = "/w")), currentWorkspace = "/w")
        assertEquals("y", vm.selectedSessionId.value)
        assertNull("a pick is not a pending target", vm.pendingSessionId.value)
        assertEquals("a pick is the web's activeId", "y", vm.activeId.value)
        noAttach()
        // Once committed it is not re-derived (another chat moving to the top changes nothing).
        vm.pickIfNothingSelected(true, listOf(chat("z", cwd = "/w"), chat("y", cwd = "/w")), currentWorkspace = "/w")
        assertEquals("y", vm.selectedSessionId.value)
    }

    @Test
    fun withNoChatInTheCurrentWorkspaceThePickIsTheFirstListed() {
        goLive()
        vm.onBootView(sessionsView = true, remembered = null)
        vm.pickIfNothingSelected(true, listOf(chat("x", cwd = "/a"), chat("y", cwd = "/b")), currentWorkspace = "/w")
        assertEquals("x", vm.selectedSessionId.value)
    }

    @Test
    fun thePickLeavesTheNewSessionSheetUp() {
        goLive()
        vm.onBootView(sessionsView = true, remembered = null)
        vm.openDraft()
        vm.pickIfNothingSelected(true, listOf(chat("x")), currentWorkspace = "/w")
        assertEquals("x", vm.selectedSessionId.value)
        assertTrue(vm.draftOpen.value)
    }

    @Test
    fun thePickDoesNotFireOffSessionsOnAnEmptyListWhileOpeningOrALinkWaitsOrBeforeTheBootView() {
        goLive()
        val list = listOf(chat("x"))
        vm.pickIfNothingSelected(true, list, "/w") // the boot view is not resolved yet
        assertNull(vm.selectedSessionId.value)
        vm.onBootView(sessionsView = false, remembered = null)
        vm.pickIfNothingSelected(false, list, "/w") // Overview / Scheduled / Usage
        assertNull(vm.selectedSessionId.value)
        vm.pickIfNothingSelected(true, emptyList(), "/w") // no list yet
        assertNull(vm.selectedSessionId.value)
        vm.setBootLinkPending(true) // a launch link still settling
        vm.pickIfNothingSelected(true, list, "/w")
        assertNull(vm.selectedSessionId.value)
        vm.setBootLinkPending(false)
        assertTrue(vm.resumeHistory(history("h1"))) // a history row opening
        vm.pickIfNothingSelected(true, list, "/w")
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun aReturnToSessionsWithNothingSelectedPicks() {
        goLive()
        vm.onBootView(sessionsView = false, remembered = null) // the boot landed on the Overview
        vm.onNavigateToSessions(listOf(chat("x")), remembered = null)
        assertNull(vm.selectedSessionId.value)
        vm.pickIfNothingSelected(true, listOf(chat("x")), "/w")
        assertEquals("x", vm.selectedSessionId.value)
    }

    // --- the remembered chat: seed, restore, give up ---------------------------------------------

    @Test
    fun aSessionsBootSeedsTheRememberedChatAsPendingWithoutAttaching() {
        vm.onBootView(sessionsView = true, remembered = LastOpenedSession("/w", "a", "h-a"))
        assertEquals("a", vm.selectedSessionId.value)
        assertEquals("a", vm.pendingSessionId.value)
        assertNull(vm.activeId.value)
        noAttach()
        // Once per view model: a second report (a rotation) seeds nothing.
        vm.selectSession("b")
        vm.onBootView(sessionsView = true, remembered = LastOpenedSession("/w", "a", "h-a"))
        assertEquals("b", vm.selectedSessionId.value)
    }

    @Test
    fun noSeedOnAnOverviewBootOrWhenALinkOrASelectionCameFirst() {
        vm.onBootView(sessionsView = false, remembered = remembered)
        assertNull(vm.selectedSessionId.value)

        val linked = vms.track(TetherViewModel(Client(), InMemoryDraftStore(), monotonicClock = { 0 }))
        linked.setBootLinkPending(true)
        linked.onBootView(sessionsView = true, remembered = remembered)
        assertNull(linked.selectedSessionId.value)

        val opened = vms.track(TetherViewModel(Client(), InMemoryDraftStore(), monotonicClock = { 0 }))
        opened.openSession("n")
        opened.onBootView(sessionsView = true, remembered = remembered)
        assertEquals("n", opened.selectedSessionId.value)
    }

    @Test
    fun aRememberedChatStillListedNeedsNoRestore() {
        client.sessions.value = listOf(chat("a"))
        vm.onBootView(true, LastOpenedSession("/w", "a", "h-a"))
        assertNull(vm.bootRestoreStep(true, listOf(history("h-a")), LastOpenedSession("/w", "a", "h-a")))
        assertTrue(client.resumed.isEmpty())
        assertEquals("a", vm.selectedSessionId.value)
    }

    @Test
    fun anExitedRememberedChatIsReopenedByItsHistoryIdOnce() {
        vm.onBootView(true, remembered)
        // Nothing discovered and no grace yet: wait.
        assertNull(vm.bootRestoreStep(true, emptyList(), remembered))
        assertEquals("gone", vm.selectedSessionId.value)
        // Not on Sessions: wait.
        assertNull(vm.bootRestoreStep(false, listOf(history("h-gone")), remembered))
        val hit = vm.bootRestoreStep(true, listOf(history("h-x"), history("h-gone")), remembered)
        assertEquals("h-gone", hit?.historyId)
        // At most once.
        assertNull(vm.bootRestoreStep(true, listOf(history("h-gone")), remembered))
    }

    @Test
    fun withNoConversationToReopenTheRememberedChatIsGivenUpAndThePickRuns() {
        goLive()
        vm.onBootView(true, remembered)
        assertNull(vm.bootRestoreStep(true, listOf(history("h-other")), remembered))
        assertNull(vm.selectedSessionId.value)
        assertNull(vm.pendingSessionId.value)
        vm.pickIfNothingSelected(true, listOf(chat("x")), "/w")
        assertEquals("x", vm.selectedSessionId.value)
    }

    @Test
    fun theGraceAloneGivesTheRememberedChatUp() {
        client.connection.value = ConnectionState.Connected
        vm.onBootView(true, remembered)
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS - 1)
        main.scheduler.runCurrent()
        assertFalse(vm.restoreGraceElapsed.value)
        assertNull(vm.bootRestoreStep(true, emptyList(), remembered))
        assertEquals("gone", vm.selectedSessionId.value)
        main.scheduler.advanceTimeBy(2)
        main.scheduler.runCurrent()
        assertTrue(vm.restoreGraceElapsed.value)
        assertNull(vm.bootRestoreStep(true, emptyList(), remembered))
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun theGraceIsArmedPerConnection() {
        client.connection.value = ConnectionState.Connected
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS - 1)
        client.connection.value = ConnectionState.Connecting
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS)
        main.scheduler.runCurrent()
        assertFalse(vm.restoreGraceElapsed.value)
        client.connection.value = ConnectionState.Connected
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS + 1)
        main.scheduler.runCurrent()
        assertTrue(vm.restoreGraceElapsed.value)
    }

    @Test
    fun aRememberedChatGivenUpIsNotSeededAgainByALaterBootReport() {
        vm.onBootView(true, remembered)
        assertNull(vm.bootRestoreStep(true, listOf(history("h-other")), remembered)) // given up
        assertNull(vm.selectedSessionId.value)
        vm.onBootView(true, remembered) // a rotation reports the view again
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun onceTheGraceHasPassedWithTheChatListedTheRestoreIsOverEvenIfTheChatLeavesLater() {
        client.connection.value = ConnectionState.Connected
        client.sessions.value = listOf(chat("a"))
        vm.onBootView(true, LastOpenedSession("/w", "a", "h-a"))
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS + 1)
        main.scheduler.runCurrent()
        assertNull(vm.bootRestoreStep(true, listOf(history("h-a")), LastOpenedSession("/w", "a", "h-a"))) // listed: nothing to restore, and done
        client.sessions.value = emptyList() // the chat ends later
        main.scheduler.advanceUntilIdle()
        assertNull("the boot restore ran once (bootRestoreRef)", vm.bootRestoreStep(true, listOf(history("h-a")), LastOpenedSession("/w", "a", "h-a")))
        assertEquals("a", vm.selectedSessionId.value)
    }

    @Test
    fun anExplicitSelectionBeforeTheRestoreEndsIt() {
        vm.onBootView(true, remembered)
        vm.selectSession("b")
        assertNull(vm.bootRestoreStep(true, listOf(history("h-gone")), remembered))
        assertEquals("b", vm.selectedSessionId.value)
    }

    @Test
    fun aSignInAfterASignOutBootsTheRememberedChatAgain() {
        vm.onBootView(true, LastOpenedSession("/w", "a", "h-a"))
        vm.logout()
        main.scheduler.advanceUntilIdle()
        assertNull(vm.selectedSessionId.value)
        vm.onBootView(true, LastOpenedSession("/w", "a", "h-a"))
        assertEquals("a", vm.selectedSessionId.value)
    }

    // --- r2: the live list, sign-out and another server -----------------------------------------

    @Test
    fun noPickFromTheSavedListBeforeTheServersFirstSnapshot() {
        vm.onBootView(sessionsView = true, remembered = null)
        vm.pickIfNothingSelected(true, listOf(chat("cached")), "/w") // read back from the device
        assertNull(vm.selectedSessionId.value)
        assertFalse(vm.listLive.value)
        goLive()
        assertTrue(vm.listLive.value)
        vm.pickIfNothingSelected(true, listOf(chat("live")), "/w")
        assertEquals("live", vm.selectedSessionId.value)
    }

    @Test
    fun aSignOutReArmsTheGraceAndTheLiveList() {
        client.connection.value = ConnectionState.Connected
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS + 1)
        main.scheduler.runCurrent()
        assertTrue(vm.restoreGraceElapsed.value)
        assertTrue(vm.listLive.value)
        vm.logout()
        main.scheduler.runCurrent()
        assertFalse("the next sign-in waits for its own discovery", vm.restoreGraceElapsed.value)
        assertFalse(vm.listLive.value)
    }

    @Test
    fun aSignOutClearsTheCurrentWorkspaceSoTheNextServersReadyFixesItsOwn() {
        vm.settleWorkspace("/serverA/root")
        vm.logout()
        main.scheduler.runCurrent()
        assertNull(vm.currentWorkspace.value)
        vm.settleWorkspace("/serverB/root")
        assertEquals("/serverB/root", vm.currentWorkspace.value)
    }

    @Test
    fun anotherServerStartsTheConsoleAfresh() {
        goLive()
        vm.onBootView(true, null)
        vm.selectSession("a")
        vm.settleWorkspace("/serverA/root")
        client.url.value = "https://b.example"
        main.scheduler.runCurrent()
        assertNull(vm.selectedSessionId.value)
        assertNull(vm.currentWorkspace.value)
        assertFalse(vm.listLive.value)
        vm.settleWorkspace("/serverB/root")
        assertEquals("/serverB/root", vm.currentWorkspace.value)
        vm.onBootView(true, LastOpenedSession("/serverB/root", "b1", "h-b1")) // its own remembered chat
        assertEquals("b1", vm.selectedSessionId.value)
    }

    @Test
    fun theFirstServerLearntAtStartIsNoSwitch() {
        val fresh = Client().also { it.url.value = null }
        val cold = vms.track(TetherViewModel(fresh, InMemoryDraftStore(), monotonicClock = { 0 }))
        main.scheduler.runCurrent()
        cold.onBootView(true, LastOpenedSession("/w", "a", "h-a"))
        fresh.url.value = "https://a.example" // the stored server read after the boot view
        main.scheduler.runCurrent()
        assertEquals("a", cold.selectedSessionId.value)
    }

    // --- the top bar's Sessions ------------------------------------------------------------------

    @Test
    fun goingToSessionsWithNothingSelectedTakesTheRememberedChatWhenListed() {
        vm.onBootView(sessionsView = false, remembered = null)
        val back = LastOpenedSession("/w", "a", "h-a")
        vm.onNavigateToSessions(listOf(chat("b")), back) // not listed: nothing
        assertNull(vm.selectedSessionId.value)
        vm.onNavigateToSessions(listOf(chat("b"), chat("a")), back)
        assertEquals("a", vm.selectedSessionId.value)
        assertEquals("a", vm.pendingSessionId.value)
        noAttach()
    }

    @Test
    fun goingToSessionsKeepsAListedOrPendingSelectionAndReplacesAPickedOneThatLeft() {
        vm.onBootView(sessionsView = false, remembered = null)
        val back = LastOpenedSession("/w", "a", "h-a")
        vm.selectSession("b")
        vm.onNavigateToSessions(listOf(chat("a"), chat("b")), back)
        assertEquals("listed: kept", "b", vm.selectedSessionId.value)
        vm.openSession("link")
        vm.onNavigateToSessions(listOf(chat("a")), back)
        assertEquals("pending: kept", "link", vm.pendingSessionId.value)
        vm.selectSession("c")
        vm.onNavigateToSessions(listOf(chat("a")), back)
        assertEquals("picked and gone: the remembered chat", "a", vm.pendingSessionId.value)
    }

    // --- pending vs picked -----------------------------------------------------------------------

    @Test
    fun linksAndCreatedRepliesArePendingAndAnExplicitSelectionOrAResumeIsNot() {
        vm.openSession("a")
        assertEquals("a", vm.pendingSessionId.value)
        assertNull(vm.activeId.value)
        vm.selectSession("b")
        assertNull(vm.pendingSessionId.value)
        assertEquals("b", vm.activeId.value)
        client.replies.tryEmit(CreatedReply(chat("c"), seq = 1, origin = null))
        main.scheduler.advanceUntilIdle()
        assertEquals("c", vm.pendingSessionId.value)
        assertEquals("the pick stays behind it", "b", vm.activeId.value)
        assertTrue(vm.resumeHistory(history("h1")))
        assertNull(vm.pendingSessionId.value)
        assertNull(vm.activeId.value)
    }

    // --- the current workspace -------------------------------------------------------------------

    @Test
    fun theCurrentWorkspaceIsSettledOnceAndNeverOverAPick() {
        vm.settleWorkspace(null)
        assertNull(vm.currentWorkspace.value)
        vm.settleWorkspace("/w/docs")
        assertEquals("/w/docs", vm.currentWorkspace.value)
        vm.settleWorkspace("/elsewhere")
        assertEquals("/w/docs", vm.currentWorkspace.value)
        vm.selectWorkspace("/picked")
        vm.settleWorkspace("/elsewhere")
        assertEquals("/picked", vm.currentWorkspace.value)
    }
}
