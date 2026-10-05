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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-coik.42: the web's two selection slots (dashboard.tsx 90fbb9f :274 `activeId`, :292
 * `pendingSessionId`, resolved by `selectedSession` :710-717). A pending target (a link :1125-1139, a
 * `created` :771-785, a live search hit :1261-1270, the remembered chat :736-748) outranks the pick only
 * once it is listed, so the chat on screen stays until then; a link waits with no limit, and only the
 * remembered chat's boot restore gives a target up (:856-886).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelPendingTargetTest {
    private val main = StandardTestDispatcher()
    private val vms = TestViewModels()

    private class Client : StubClient() {
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val url = MutableStateFlow<String?>("https://a.example")
        override val serverUrl: kotlinx.coroutines.flow.StateFlow<String?> get() = url
        val mounted = mutableListOf<String>()
        override fun attachMounted(sessionId: String) {
            mounted += sessionId
        }
        override fun resumeHistory(historyId: String, cwd: String) = Unit
        val replies = MutableSharedFlow<CreatedReply>(extraBufferCapacity = 4)
        override val createdReplies: Flow<CreatedReply> get() = replies
    }

    private fun chat(id: String, cwd: String = "/w", status: String = "ready") =
        AgentSession(id = id, provider = "claude", name = id, cwd = cwd, status = status, startedAt = 1, updatedAt = 1, historyId = "h-$id")

    private fun history(id: String) = HistorySession(historyId = id, provider = "claude", name = id, cwd = "/w", updatedAt = 1)

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

    private fun list(vararg chats: AgentSession) {
        client.sessions.value = chats.toList()
        main.scheduler.advanceUntilIdle()
    }

    /** Chat "a" picked from the sidebar and on screen (its mount reported, as the shell does). */
    private fun aOnScreen() {
        list(chat("a"), chat("b"))
        vm.selectSession("a")
        vm.chatViewShown("a")
    }

    private fun attaches(): List<String> = client.attached + client.mounted

    // --- ta-coik.43: Back onto an entry naming a chat (dashboard.tsx 90fbb9f :1110-1118) ---------------

    @Test
    fun backOntoAnEntryNamingAChatMakesItPendingAndShowsItWhenListed() {
        aOnScreen()
        vm.selectSession("b")
        val before = attaches()
        var opened = 0
        val watcher = kotlinx.coroutines.CoroutineScope(main).launch { vm.openRequests.collect { opened++ } }
        main.scheduler.advanceUntilIdle()
        // The entry names "x", not listed yet: the chat on screen stays until it is.
        vm.returnToSession("x")
        main.scheduler.advanceUntilIdle()
        assertEquals("x", vm.pendingSessionId.value)
        assertEquals("the pick stays", "b", vm.activeId.value)
        assertEquals("b", vm.selectedSessionId.value)
        list(chat("a"), chat("b"), chat("x"))
        assertEquals("x", vm.selectedSessionId.value)
        // A listed one at once (the popstate's setPendingSessionId and nothing else).
        vm.returnToSession("a")
        main.scheduler.advanceUntilIdle()
        assertEquals("a", vm.selectedSessionId.value)
        assertEquals("b", vm.activeId.value)
        assertEquals("nothing attached: the mount does", before, attaches())
        assertEquals("no open request: popstate pushes no view and closes no drawer", 0, opened)
        watcher.cancel()
    }

    // --- a link arrives ----------------------------------------------------------------------------

    @Test
    fun aLinkToAChatNotListedYetKeepsTheChatOnScreenAndAttachesNothing() {
        aOnScreen()
        val before = attaches()
        vm.openSession("x")
        assertEquals("x", vm.pendingSessionId.value)
        assertEquals("the pick stays", "a", vm.activeId.value)
        assertEquals("the chat on screen stays", "a", vm.selectedSessionId.value)
        assertEquals("no attach for a target not on screen", before, attaches())
    }

    @Test
    fun theTargetIsShownOnceListedAndItsMountIsTheAttach() {
        aOnScreen()
        vm.openSession("x")
        list(chat("a"), chat("b"), chat("x"))
        assertEquals("x", vm.selectedSessionId.value)
        assertEquals("the pick is still behind it", "a", vm.activeId.value)
        vm.chatViewShown("x")
        assertEquals("the last attach is the chat on screen", "x", attaches().last())
    }

    @Test
    fun aLinkToAListedChatShowsItAtOnce() {
        aOnScreen()
        vm.openSession("b")
        assertEquals("b", vm.selectedSessionId.value)
        assertEquals("b", vm.pendingSessionId.value)
    }

    @Test
    fun aLinkThatNeverAppearsIsNeverGivenUpAndTheChatStays() {
        aOnScreen()
        client.connection.value = ConnectionState.Connected
        vm.openSession("x")
        main.scheduler.advanceTimeBy(RESTORE_GRACE_MS * 10)
        main.scheduler.runCurrent()
        // The boot restore concerns the remembered chat only (another one here): it settles, the link stays.
        assertNull(vm.bootRestoreStep(true, emptyList(), LastOpenedSession("/w", "other", "h-other")))
        list(chat("a"), chat("b"), chat("c"))
        assertEquals("x", vm.pendingSessionId.value)
        assertEquals("a", vm.selectedSessionId.value)
        // Much later the target appears: it is shown then, as on the web.
        list(chat("a"), chat("x"))
        assertEquals("x", vm.selectedSessionId.value)
    }

    @Test
    fun aLinkLeavesTheOpeningRowAndTheNewSessionSheetAlone() {
        aOnScreen()
        assertTrue(vm.resumeHistory(history("h1")))
        vm.openDraft()
        vm.openSession("x")
        assertEquals("h1", vm.openingHistoryId.value)
        assertTrue(vm.draftOpen.value)
    }

    @Test
    fun aPendingTargetBlocksTheOneTimePick() {
        client.connection.value = ConnectionState.Connected
        main.scheduler.runCurrent()
        vm.onBootView(sessionsView = true, remembered = null)
        vm.openSession("x")
        vm.pickIfNothingSelected(true, listOf(chat("a")), "/w")
        assertNull(vm.activeId.value)
        assertEquals("x", vm.pendingSessionId.value)
    }

    // --- the target listed, then gone again -------------------------------------------------------

    @Test
    fun aShownTargetThatLeavesTheListFallsBackToThePickAndComesBack() {
        aOnScreen()
        vm.openSession("x")
        list(chat("a"), chat("x"))
        assertEquals("x", vm.selectedSessionId.value)
        list(chat("a"))
        assertEquals("selectedSession falls through to activeId", "a", vm.selectedSessionId.value)
        list(chat("a"), chat("x"))
        assertEquals("x", vm.selectedSessionId.value)
    }

    @Test
    fun anEndedTargetIsShownOnlyWithShowEndedOn() {
        list(chat("a"), chat("x", status = "exited"))
        vm.setShowEnded(false)
        vm.selectSession("a")
        vm.openSession("x")
        assertEquals("not in visibleSessions: the pick stays", "a", vm.selectedSessionId.value)
        vm.setShowEnded(true)
        assertEquals("x", vm.selectedSessionId.value)
    }

    @Test
    fun theChatOnScreenChangingDropsWhatWasStagedForThePreviousOne() {
        aOnScreen()
        vm.openSession("x")
        val generation = vm.stagedAttachments.generation
        list(chat("a"), chat("b")) // no change on screen
        assertEquals(generation, vm.stagedAttachments.generation)
        list(chat("a"), chat("x"))
        assertNotEquals("the target replaced the chat on screen", generation, vm.stagedAttachments.generation)
        assertFalse(vm.attachmentsAllowed("a"))
        assertTrue(vm.attachmentsAllowed("x"))
    }

    // --- the user picks another chat while a target is pending ------------------------------------

    @Test
    fun anExplicitSelectionRetiresThePendingTarget() {
        aOnScreen()
        vm.openSession("x")
        vm.selectSession("b")
        assertNull(vm.pendingSessionId.value)
        assertEquals("b", vm.activeId.value)
        list(chat("a"), chat("b"), chat("x"))
        assertEquals("the retired target never springs back", "b", vm.selectedSessionId.value)
    }

    @Test
    fun endSessionCommitsTheChatOnScreenAsThePick() {
        aOnScreen()
        vm.openSession("x")
        list(chat("a"), chat("x"))
        val before = attaches()
        vm.commitSelection("x")
        assertEquals("x", vm.activeId.value)
        assertNull(vm.pendingSessionId.value)
        assertEquals("the same chat stays mounted: nothing attached", before, attaches())
        list(chat("a"))
        assertEquals("no fallback to the old pick any more (kept, not listed: nothing on screen)", "x", vm.selectedSessionId.value)
    }

    // --- created, search hit, resume --------------------------------------------------------------

    @Test
    fun aCreatedReplyKeepsTheChatOnScreenUntilTheNewSessionIsListed() {
        aOnScreen()
        vm.openDraft()
        client.replies.tryEmit(CreatedReply(chat("new"), seq = 1, origin = null))
        main.scheduler.advanceUntilIdle()
        assertEquals("new", vm.pendingSessionId.value)
        assertEquals("a", vm.activeId.value)
        assertEquals("a", vm.selectedSessionId.value)
        assertFalse("every created closes the sheet", vm.draftOpen.value)
        list(chat("a"), chat("new"))
        assertEquals("new", vm.selectedSessionId.value)
    }

    @Test
    fun aLiveSearchHitClearsThePickAndBecomesThePendingTarget() {
        aOnScreen()
        vm.openSearchHit("b")
        assertNull("the pick clears (dashboard.tsx :1266)", vm.activeId.value)
        assertTrue(vm.resumeHistory(history("h1")))
        vm.openDraft()
        vm.openSearchHit("b")
        assertNull(vm.openingHistoryId.value)
        assertEquals("b", vm.pendingSessionId.value)
        assertEquals("b", vm.selectedSessionId.value)
        assertFalse(vm.draftOpen.value)
        list(chat("a"))
        assertEquals("nothing picked behind it (not listed: nothing on screen)", "b", vm.selectedSessionId.value)
    }

    @Test
    fun aResumeClearsBothSlots() {
        aOnScreen()
        vm.openSession("x")
        assertTrue(vm.resumeHistory(history("h1")))
        assertNull(vm.activeId.value)
        assertNull(vm.pendingSessionId.value)
        assertNull(vm.selectedSessionId.value)
        assertEquals("h1", vm.openingHistoryId.value)
    }

    // --- the remembered chat, and a history reopen of an exited target ----------------------------

    @Test
    fun anExitedRememberedTargetIsReopenedFromHistoryAndBothSlotsClear() {
        val remembered = LastOpenedSession("/w", "gone", "h-gone")
        vm.onBootView(true, remembered)
        assertEquals("gone", vm.pendingSessionId.value)
        val hit = vm.bootRestoreStep(true, listOf(history("h-gone")), remembered)
        assertEquals("h-gone", hit?.historyId)
        assertTrue(vm.resumeHistory(hit!!))
        assertNull(vm.pendingSessionId.value)
        assertEquals("h-gone", vm.openingHistoryId.value)
    }

    @Test
    fun aLinkToTheRememberedChatIsRestoredLikeIt() {
        // dashboard.tsx :868: the restore keys on pendingSessionId === remembered.sessionId, whoever set it.
        val remembered = LastOpenedSession("/w", "gone", "h-gone")
        vm.setBootLinkPending(true)
        vm.onBootView(true, remembered) // a link wins the boot: nothing seeded
        vm.setBootLinkPending(false)
        vm.openSession("gone")
        assertEquals("h-gone", vm.bootRestoreStep(true, listOf(history("h-gone")), remembered)?.historyId)
    }

    @Test
    fun aGivenUpRememberedTargetLeavesThePickAlone() {
        val remembered = LastOpenedSession("/w", "gone", "h-gone")
        vm.onBootView(true, remembered)
        list(chat("a"))
        assertNull(vm.bootRestoreStep(true, listOf(history("h-other")), remembered))
        assertNull(vm.pendingSessionId.value)
    }

    // --- sign-out and another server --------------------------------------------------------------

    @Test
    fun aSignOutClearsBothSlots() {
        aOnScreen()
        vm.openSession("x")
        vm.logout()
        main.scheduler.advanceUntilIdle()
        assertNull(vm.activeId.value)
        assertNull(vm.pendingSessionId.value)
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun anotherServerClearsBothSlots() {
        aOnScreen()
        vm.openSession("x")
        client.url.value = "https://b.example"
        main.scheduler.runCurrent()
        assertNull(vm.activeId.value)
        assertNull(vm.pendingSessionId.value)
        assertNull(vm.selectedSessionId.value)
    }
}
