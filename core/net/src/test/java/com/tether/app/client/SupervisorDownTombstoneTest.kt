package com.tether.app.client

import java.io.IOException
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tether-android#6 (PP-1 S4): a `supervisor_down` refusal tombstones its key (use-tether.ts forget). The
 * tombstone matters where the pending store is MERGED with another copy: a write torn by the process
 * dying leaves the refused record on disk, and a sign-in away and back restores that disk copy; the
 * tombstone is what keeps the refused key from coming back and being redelivered.
 */
class SupervisorDownTombstoneTest {

    /** Delegates to the in-memory store; a pending write fails while [tear] (the write the refusal makes). */
    private class TearableSettings(private val inner: SettingsStore) : SettingsStore by inner {
        @Volatile var tear = false
        val lastWrite = java.util.concurrent.atomic.AtomicReference<String?>(null)
        override suspend fun writePendingInput(origin: String, raw: String) {
            if (tear) throw IOException("killed mid-write")
            inner.writePendingInput(origin, raw)
            lastWrite.set(raw)
        }
    }

    private var tearable: TearableSettings? = null
    private val f = TwoOriginFixture { TearableSettings(it).also { t -> tearable = t } }

    @After
    fun tearDown() = f.close()

    @Test
    fun aRefusedKeyIsNotRestoredFromAStaleDiskCopyOnTheWayBack() {
        val disk = tearable!!
        val aws = f.connectedToA()
        f.client.send("s1", "refused words")
        val sent = f.framesUntilBarrier(f.a).single { it.type() == "send" }["idempotencyKey"]!!.jsonPrimitive.content
        f.awaitCondition("the record is on disk") { disk.lastWrite.get()?.contains(sent) == true }

        // The refusal's own write is torn: the disk keeps the record, and no tombstone for it.
        disk.tear = true
        aws.send("""{"type":"error","message":"x","code":"supervisor_down","idempotencyKey":"$sent"}""")
        val failed = f.await(f.client.failedSends) { it.isNotEmpty() }
        assertEquals(FailedSendReason.SupervisorDown, failed.single().reason)

        // Away and back: A's slot (the stale copy) is read again; the tombstone must veto the key.
        f.loginTo(f.b)
        f.handshake(f.b, f.b.nextSocket())
        f.framesUntilBarrier(f.b)
        f.loginTo(f.a)
        val aws2 = f.a.nextSocket()
        f.handshake(f.a, aws2)
        aws2.send(snapshotFrame("s1", 1))
        f.framesUntilBarrier(f.a).let { frames ->
            assertTrue("the refused key was redelivered: $frames", frames.none { it.type() == "send" || it.type() == "queue-add" })
        }
        assertTrue("restored as pending", f.client.pendingSends.value.isEmpty())
    }
}
