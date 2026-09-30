package com.tether.app.ui.chat

import com.tether.app.ui.TetherViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.InputStream

/**
 * T7.4 r2 (verifier M1 = maker L4a): the composer's scope survives a drawer selection (ChatScreen
 * is not keyed by session), so a FIRST pick can still be being read when its session stops being
 * the one it was picked for. Every drop trigger drops it, staged or not: it is never staged into
 * the session selected since, nor into its own session once that is read-only.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentStagingDropTest {
    /** A pick whose provider answers only when [gate] opens. */
    private fun slowPick(gate: CompletableDeferred<Unit>) = object : AttachmentSource {
        override val displayName = "slow.txt"
        override val reportedSize: Long? = null
        override val declaredType = "text/plain"
        override fun open(): InputStream {
            runBlocking { gate.await() }
            return "hi".byteInputStream()
        }
    }

    private fun composerStager(vm: TetherViewModel) =
        AttachmentStager(vm.stagedAttachments, { vm.attachmentOrigin() }, Dispatchers.IO, allowed = vm::attachmentsAllowed)

    @Test
    fun aSessionSwitchWhileAFirstPickIsBeingReadDropsThePick() = runBlocking {
        val client = ChatTestClient()
        client.sessions.value = listOf(chatSession("s1", null), chatSession("s2", null))
        val vm = TetherViewModel(client)
        ShadowLooper.idleMainLooper()
        vm.selectSession("s1")
        val gate = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) { composerStager(vm).stage("s1", listOf(slowPick(gate))) }
        Thread.sleep(150)
        vm.selectSession("s2") // nothing staged yet
        gate.complete(Unit)
        withTimeout(5_000) { pending.await() }
        assertNull("staged after a session switch: ${vm.stagedAttachments.current.value}", vm.stagedAttachments.current.value)
    }

    @Test
    fun aSessionThatLocksWhileAFirstPickIsBeingReadDropsThePick() = runBlocking {
        val client = ChatTestClient()
        client.sessions.value = listOf(chatSession("s1", null))
        val vm = TetherViewModel(client)
        ShadowLooper.idleMainLooper()
        vm.selectSession("s1")
        val gate = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) { composerStager(vm).stage("s1", listOf(slowPick(gate))) }
        Thread.sleep(150)
        client.sessions.value = listOf(chatSession("s1", null).copy(readOnly = true, updatedAt = 2))
        ShadowLooper.idleMainLooper()
        gate.complete(Unit)
        withTimeout(5_000) { pending.await() }
        ShadowLooper.idleMainLooper()
        assertNull("staged into a locked session: ${vm.stagedAttachments.current.value}", vm.stagedAttachments.current.value)
    }

    @Test
    fun aFirstPickForTheStillSelectedUnlockedSessionIsStaged() = runBlocking {
        val client = ChatTestClient()
        client.sessions.value = listOf(chatSession("s1", null), chatSession("s2", null))
        val vm = TetherViewModel(client)
        ShadowLooper.idleMainLooper()
        vm.selectSession("s1")
        val gate = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) { composerStager(vm).stage("s1", listOf(slowPick(gate))) }
        Thread.sleep(150)
        vm.selectSession("s1") // a rotation re-selects it
        gate.complete(Unit)
        withTimeout(5_000) { pending.await() }
        assertEquals(listOf("slow.txt"), vm.stagedAttachments.items(vm.attachmentOrigin(), "s1").map { it.attachment.name })
    }
}
