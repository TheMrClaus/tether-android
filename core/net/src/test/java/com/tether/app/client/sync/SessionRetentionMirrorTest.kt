package com.tether.app.client.sync

import com.tether.app.client.snapshotFrame
import com.tether.app.client.type
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonObject
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
 * ta-2vm7 with the journal mirror on: a released session reopens at once from its saved copy and then
 * catches up with a full attach; a cold start loads nothing into memory by itself; a seeded cursor
 * (restored from the mirror) never brings a session's projection back behind the open chat's back.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRetentionMirrorTest {
    private val h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private fun sessionJson(id: String) =
        """{"id":"$id","provider":"claude","name":"n-$id","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,
            "pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private fun ready(vararg ids: String) =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[${ids.joinToString(",") { sessionJson(it) }}]}"""

    private fun state(id: String) =
        """{"tetherSessionId":"$id","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"activeTurnId":null,
            "queuedMessages":[]}"""

    private fun event(id: String, seq: Long, body: String) =
        """{"type":"event","sessionId":"$id","event":{$body,"seq":$seq,"ts":$seq}}"""

    private fun attachesFor(frames: List<JsonObject>, id: String) =
        frames.filter { it.type() == "attach" && it["sessionId"]!!.jsonPrimitive.content == id }

    private fun openWithSnapshot(id: String) {
        h.now.addAndGet(1_000)
        h.client.attach(id)
        h.expectFrame("attach")
        h.ws.send(snapshotFrame(id, 1, state(id)))
        h.await(h.client.projectionTrees) { it.containsKey(id) }
    }

    @Test
    fun aReleasedSessionReopensAtOnceFromItsSavedCopyThenCatchesUpInFull() {
        val all = (1..6).map { "m$it" }
        h.boot(ready = ready(*all.toTypedArray()))
        h.now.addAndGet(1_000)
        h.client.attach("m1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("m1", 3, state("m1")))
        h.ws.send(event("m1", 4, """"type":"turn_started","turnId":"t1""""))
        h.ws.send(event("m1", 5, """"type":"message_delta","turnId":"t1","blockId":"b1","text":"saved text""""))
        h.await(h.client.projectionTrees) { JsCodec.canonical(it["m1"] ?: return@await false).contains("saved text") }
        val reference = JsCodec.canonical(h.client.projectionTrees.value.getValue("m1"))
        all.drop(1).forEach { openWithSnapshot(it) } // m1 released
        assertFalse(h.client.projectionTrees.value.containsKey("m1"))
        h.dbSession("m1") // flush: the copy is on disk

        h.now.addAndGet(1_000)
        h.client.attach("m1")
        // The saved copy shows with no word from the server, identical to the fold that never released it...
        val shown = h.await(h.client.projectionTrees) { it.containsKey("m1") }.getValue("m1")
        assertEquals(reference, JsCodec.canonical(shown))
        // ...while the catch-up goes out in full (the cursor was reset with the release).
        val attach = attachesFor(h.framesUntilBarrier(), "m1").single()
        assertNull(attach["afterSeq"])
        h.ws.send(snapshotFrame("m1", 6, state("m1").replace("\"turnOrder\":[]", "\"turnOrder\":[],\"marker\":\"server\"")))
        h.await(h.client.projectionTrees) { it["m1"]?.get("marker") != null }
    }

    @Test
    fun aColdStartLoadsAtMostTheRetainedSetIntoMemory() {
        val all = (0 until 15).map { "m$it" }
        h.boot(ready = ready(*all.toTypedArray()))
        all.forEach { openWithSnapshot(it) }
        h.serverBarrier()
        h.dbSession("m0")
        h.kill(flushFirst = true)
        h.boot(ready = ready(*all.toTypedArray()))
        val frames = h.framesUntilBarrier()
        val attached = frames.filter { it.type() == "attach" }.map { it["sessionId"]!!.jsonPrimitive.content }
        assertEquals("four re-attached, not fifteen", 4, attached.size)
        h.await(h.client.projectionTrees) { it.size >= 4 }
        h.serverBarrier()
        assertTrue("trees ${h.client.projectionTrees.value.keys}", h.client.projectionTrees.value.size <= 4)
        assertTrue(h.client.memoryCensus().adapters <= 4)
        // Opening one that was left out attaches it from its saved cursor, as before.
        h.client.attach("m3")
        assertEquals(1L, attachesFor(h.framesUntilBarrier(), "m3").single()["afterSeq"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun aGapOnASeededSessionNeitherAttachesItNorKeepsItsProjection() {
        val all = (0 until 8).map { "m$it" }
        h.boot(ready = ready(*all.toTypedArray()))
        all.forEach { openWithSnapshot(it) }
        h.dbSession("m0")
        h.kill(flushFirst = true)
        h.boot(ready = ready(*all.toTypedArray()))
        h.framesUntilBarrier()
        // m0 holds a cursor restored from the mirror and is not open: an event far ahead of it is a gap.
        h.ws.send(event("m0", 9, """"type":"turn_started","turnId":"t9""""))
        h.serverBarrier()
        assertEquals(emptyList<JsonObject>(), attachesFor(h.framesUntilBarrier(), "m0"))
        h.ws.send(snapshotFrame("m0", 9, state("m0")))
        h.serverBarrier()
        assertFalse(h.client.projectionTrees.value.containsKey("m0"))
    }
}
