package com.tether.app.ui

import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ta-coik.39 r2: the web attaches a chat on every mount of its ChatView (use-tether.ts 90fbb9f
 * :1568). The shell reports the chat view on screen ([TetherViewModel.chatViewShown]); a real mount
 * attaches through [com.tether.app.client.TetherClient.attachMounted], an open's attach is the
 * one of the mount it causes, and the same report again (recomposition, rotation) is nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelChatMountTest {
    private val main = StandardTestDispatcher()
    private val vms = TestViewModels()

    private class Recording : StubClient() {
        val calls = mutableListOf<String>()
        override fun attach(sessionId: String) {
            calls += "attach:$sessionId"
        }
        override fun attachMounted(sessionId: String) {
            calls += "mount:$sessionId"
        }
        val replies = kotlinx.coroutines.flow.MutableSharedFlow<com.tether.app.client.CreatedReply>(extraBufferCapacity = 4)
        override val createdReplies: kotlinx.coroutines.flow.Flow<com.tether.app.client.CreatedReply> get() = replies
        override fun attachIfConfigured(sessionId: String, origin: String): Boolean {
            calls += "attach-if:$sessionId"
            return true
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        vms.clear()
        main.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun onlyARealMountAttachesAndAnOpenIsItsOwnMount() {
        val client = Recording()
        val vm = vms.track(TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { 0 }))

        vm.selectSession("s1")
        assertEquals(listOf("mount:s1"), client.calls)
        vm.chatViewShown("s1") // the mount that open caused
        vm.chatViewShown("s1") // a recomposition / rotation reports it again
        assertEquals(listOf("mount:s1"), client.calls)

        vm.selectSession("s1") // re-selected while on screen: no mount
        assertEquals(listOf("mount:s1", "attach:s1"), client.calls)

        vm.chatViewShown(null) // Overview / Scheduled / Usage / a create in flight
        vm.chatViewShown("s1") // back to the same chat: mounted again
        assertEquals(listOf("mount:s1", "attach:s1", "mount:s1"), client.calls)

        vm.chatViewShown(null)
        vm.selectSession("s2") // opened from the Overview: one attach for its mount
        vm.chatViewShown("s2")
        assertEquals(listOf("mount:s1", "attach:s1", "mount:s1", "mount:s2"), client.calls)

        vm.selectSession("s1") // switching chats on screen
        vm.chatViewShown("s1")
        assertEquals(listOf("mount:s1", "attach:s1", "mount:s1", "mount:s2", "mount:s1"), client.calls)
    }

    @Test
    fun aCreatedSessionOpenedFromItsReplyIsAttachedOnceNotAgainByItsMount() {
        val client = Recording()
        val vm = vms.track(TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { 0 }))
        main.scheduler.advanceUntilIdle() // the reply collector is subscribed
        vm.selectSession("s1")
        vm.chatViewShown("s1")
        val created = com.tether.app.protocol.model.AgentSession(id = "s9", provider = "claude", name = "s9", cwd = "/w", status = "ready", startedAt = 1, updatedAt = 1)
        client.replies.tryEmit(com.tether.app.client.CreatedReply(created, seq = 1, origin = "https://example.test"))
        main.scheduler.advanceUntilIdle()
        assertEquals("s9", vm.selectedSessionId.value)
        vm.chatViewShown("s9") // the mount that reply caused
        assertEquals(listOf("mount:s1", "attach-if:s9"), client.calls)
    }
}
