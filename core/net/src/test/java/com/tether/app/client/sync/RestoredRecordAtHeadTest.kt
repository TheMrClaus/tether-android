package com.tether.app.client.sync

import com.tether.app.client.snapshotFrame
import com.tether.app.client.type
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 gate, SYNC_DESIGN §3.1 rule 1a (BLOCKER-1): unsent input restored after process death
 * has tries > 0 and may be re-sent only after a snapshot WITH state reconciled it. A mirror at
 * head would get a stateless reply and strand it, so its session attaches WITHOUT afterSeq.
 */
@RunWith(RobolectricTestRunner::class)
class RestoredRecordAtHeadTest {
    private val h = MirrorHarness()
    private val startedAt get() = h.now.get()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private val state = """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"queuedMessages":[]}"""

    private fun ready(pinned: Boolean = false) =
        """{"type":"ready","protocolVersion":129,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[{"id":"s1","provider":"claude","name":"one","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,
            "pinned":$pinned,"runtimeArchived":false,"mode":"headless"}]}"""

    /** Process 1: the session is mirrored at head (cursor 5 = the server's lastSeq). */
    private fun mirrorAtHead() {
        h.boot(ready = ready())
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        assertEquals(5L, h.dbSession("s1")!!.cursor)
    }

    @Test
    fun aRestoredTriedRecordForcesAFullAttachThenIsResentExactlyOnce() {
        mirrorAtHead()
        h.client.send("s1", "deploy the fix")
        val sent = h.expectFrame("send")
        val key = sent["idempotencyKey"]!!.jsonPrimitive.content
        // The ack never came: the process dies with the record persisted (tries 1).
        // The record (tries 1) is on disk before the process dies.
        val deadline = System.currentTimeMillis() + 10_000
        while (kotlinx.coroutines.runBlocking { h.settings.readPendingInput(h.origin) }?.contains(key) != true) {
            assertTrue("the pending record was never persisted", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        h.kill(flushFirst = true)

        h.now.addAndGet(20_000) // well within the 10-minute window (< 30 s)
        h.boot(ready = ready())
        val first = h.framesUntilBarrier()
        val attach = first.single { it.type() == "attach" && it["sessionId"]!!.jsonPrimitive.content == "s1" }
        assertNull("rule 1a: a tries>0 record means a FULL attach, even with a mirror at head", attach["afterSeq"])
        assertTrue("nothing is redelivered before the state reconciles it", first.none { it.type() == "send" })

        // The server would have answered stateless to afterSeq=5; a full attach gets state
        // (the key is not in it: the turn never started), which reconciles and redelivers.
        h.ws.send(snapshotFrame("s1", 5, state))
        val resent = h.expectFrame("send")
        assertEquals("the SAME idempotencyKey: dedupe-safe", key, resent["idempotencyKey"]!!.jsonPrimitive.content)
        assertTrue("exactly once", h.framesUntilBarrier().none { it.type() == "send" })
        assertTrue(h.now.get() - startedAt < 30_000)
        // The mirrored copy was shown meanwhile and the state re-based it.
        assertEquals(5L, h.dbSession("s1")!!.cursor)
    }

    @Test
    fun onlyUntriedRecordsKeepTheCursorAndTheStatelessReply() {
        mirrorAtHead()
        h.kill(flushFirst = true)

        // Process 2: text filed BEFORE the handshake has tries 0 (never transmitted).
        h.boot(ready = ready(), handshake = false)
        h.client.send("s1", "hello")
        h.ws.send(ready())
        h.expectFrame("hello")
        // Connected is published after the ready handler's frames are on the wire.
        h.await(h.client.connection) { it == com.tether.app.client.ConnectionState.Connected }
        val frames = h.framesUntilBarrier()
        val attach = frames.single { it.type() == "attach" && it["sessionId"]!!.jsonPrimitive.content == "s1" }
        println("RestoredRecordAtHeadTest cold-start attach: $attach")
        assertEquals("tries 0 needs no reconcile: the persisted cursor is sent", 5L, attach["afterSeq"]!!.jsonPrimitive.longOrNull)
        assertTrue("a never-sent record goes out at once", frames.any { it.type() == "send" })
        // At head: the stateless reply, and the transcript is the mirrored one.
        h.ws.send(snapshotFrame("s1", 5, state = null))
        h.serverBarrier()
        val shown = h.await(h.client.projectionTrees) { it.containsKey("s1") }.getValue("s1")
        assertEquals(com.tether.app.protocol.tree.JsCodec.parse(state), shown)
        assertFalse(h.client.projectionTrees.value.isEmpty())
    }

    @Test
    fun theGapResyncOfASessionWithATriedRecordIsAFullAttachToo() {
        mirrorAtHead()
        h.client.send("s1", "one")
        h.expectFrame("send")
        // A live gap (seq 9 > cursor 5 + 1) while the record is in flight.
        h.ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t9","seq":9,"ts":9}}""")
        val resync = h.expectFrame("attach")
        assertNull(resync["afterSeq"])
    }
}
