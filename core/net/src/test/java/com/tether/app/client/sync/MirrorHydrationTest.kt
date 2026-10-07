package com.tether.app.client.sync

import com.tether.app.client.snapshotFrame
import com.tether.app.client.type
import com.tether.app.mirror.JournalMirror
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsStr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 step 2 (SYNC_DESIGN §2.4, §3.1; §11 CursorPersistenceTest + AttachReplyTest): a cold
 * start with a mirror shows the saved list and transcript before (and without) any network,
 * re-attaches from the persisted cursor, and handles every attach reply.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorHydrationTest {
    private var h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private fun state(extra: String = "") =
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"activeTurnId":null,
            "queuedMessages":[]$extra}"""

    private fun sessionJson(id: String, pinned: Boolean = false) =
        """{"id":"$id","provider":"claude","name":"n-$id","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,
            "pinned":$pinned,"runtimeArchived":false,"mode":"headless"}"""

    private fun ready(vararg sessions: String) =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[${sessions.joinToString(",")}]}"""

    private fun event(seq: Long, body: String) = """{"type":"event","sessionId":"s1","event":{$body,"seq":$seq,"ts":$seq}}"""

    private fun attachesFor(frames: List<JsonObject>, id: String) =
        frames.filter { it.type() == "attach" && it["sessionId"]!!.jsonPrimitive.content == id }

    private fun shown(): String = JsCodec.canonical(h.await(h.client.projectionTrees) { it.containsKey("s1") }.getValue("s1"))

    /** Process 1 leaves s1 mirrored: state through 3, then events 4 and 5 (cursor 5). */
    private fun firstProcess() {
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 3, state()))
        h.ws.send(event(4, """"type":"turn_started","turnId":"t1""""))
        h.ws.send(event(5, """"type":"message_delta","turnId":"t1","blockId":"b1","text":"saved text""""))
        h.serverBarrier()
    }

    @Test
    fun offlineColdStartShowsTheSavedListAndTranscript() {
        firstProcess()
        val before = shown()
        h.kill(flushFirst = true)
        // No handshake at all: nothing from the server this process.
        h.boot(handshake = false)
        h.await(h.client.sessions) { list -> list.any { it.id == "s1" && it.name == "n-s1" } }
        h.client.attach("s1") // the UI opens it
        assertEquals(before, shown())
    }

    @Test
    fun aColdStartAtHeadAttachesFromThePersistedCursorAndGetsTheStatelessReply() {
        firstProcess()
        h.kill(flushFirst = true)
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        val attach = attachesFor(h.framesUntilBarrier(), "s1").single()
        println("MirrorHydrationTest cold-start attach: $attach -> stateless reply")
        assertEquals(5L, attach["afterSeq"]!!.jsonPrimitive.longOrNull)
        h.ws.send(snapshotFrame("s1", 5, state = null))
        assertTrue(shown().contains("saved text"))
        // Live events keep folding onto the hydrated copy, and the mirror follows.
        h.ws.send(event(6, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" and more""""))
        h.serverBarrier()
        val tree = h.client.projectionTrees.value.getValue("s1")
        assertTrue(JsCodec.canonical(tree).contains("saved text and more"))
        assertEquals(JsCodec.canonical(tree), JsCodec.canonical(h.dbFold("s1")!!))
        assertEquals(6L, h.dbSession("s1")!!.cursor)
    }

    @Test
    fun aLostBatchMeansAnOlderCursorAndTheServerAnswersWithState() {
        h.close()
        h = MirrorHarness(batchWindowMs = 60_000) // nothing commits until a flush
        h.startServer()
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 3, state()))
        h.serverBarrier()
        h.dbSession("s1") // flushed: cursor 3
        // Hold the writer (a saved-copy read of another session blocks it), so event 4's write
        // is still queued, deterministically, when the process dies (verifier F1: without the
        // hold, the writer may drain it right after an earlier control op).
        val gate = CountDownLatch(1)
        h.mirror.beforeHydrateRead = { gate.await(10, TimeUnit.SECONDS) }
        h.client.attach("s2")
        h.ws.send(event(4, """"type":"turn_started","turnId":"t1""""))
        h.serverBarrier()
        Thread { Thread.sleep(300); gate.countDown() }.start() // after abandon() emptied the queue
        h.kill(flushFirst = false) // event 4 never reached the disk
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        val attach = attachesFor(h.framesUntilBarrier(), "s1").single()
        assertEquals("the cursor never claims more than the DB holds", 3L, attach["afterSeq"]!!.jsonPrimitive.longOrNull)
        h.ws.send(snapshotFrame("s1", 4, state(""","marker":"server"""")))
        h.await(h.client.projectionTrees) { it["s1"]?.get("marker") == JsStr("server") }
    }

    @Test
    fun aSeqlessFoldIsNeverRestoredAsCoveredAfterADeath() {
        // Regression (found by the conformance gate): the seqless event's cursor clear must be
        // durable BEFORE the fold is shown. With a batch that never commits by itself, a death
        // right after the fold must still leave no cursor, so the restart re-fetches state
        // instead of restoring a copy without the fold under a stateless at-head reply.
        h.close()
        h = MirrorHarness(batchWindowMs = 60_000)
        h.startServer()
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 3, state()))
        h.serverBarrier()
        h.dbSession("s1") // flushed: cursor 3
        h.ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1"}}""")
        h.serverBarrier()
        assertEquals(JsStr("t1"), h.client.projectionTrees.value.getValue("s1")["activeTurnId"])
        h.kill(flushFirst = false)
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        val attach = attachesFor(h.framesUntilBarrier(), "s1").single()
        assertNull("the restored copy must not claim to cover the seqless fold", attach["afterSeq"])
    }

    @Test
    fun aSeqlessFoldIsShownOnlyOnceItsCursorClearIsCommitted() {
        h.boot(ready = ready(sessionJson("s1"), sessionJson("s2")))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 3, state()))
        h.serverBarrier()
        h.dbSession("s1")
        // Hold the mirror's writer (a saved-copy read of another session blocks it).
        val gate = CountDownLatch(1)
        h.mirror.beforeHydrateRead = { gate.await(10, TimeUnit.SECONDS) }
        h.client.attach("s2")
        h.ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1"}}""")
        Thread.sleep(300) // well under the frame thread's bounded wait
        assertEquals("not shown before the clear is durable", JsNull, h.client.projectionTrees.value.getValue("s1")["activeTurnId"])
        gate.countDown()
        h.await(h.client.projectionTrees) { it["s1"]?.get("activeTurnId") == JsStr("t1") }
        assertNull(h.dbSession("s1")!!.cursor)
    }

    @Test
    fun aResetMovesTheRestoredCursorDown() {
        firstProcess()
        h.kill(flushFirst = true)
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        attachesFor(h.framesUntilBarrier(), "s1").single()
        // The journal was replaced (torn tail): the server is BEHIND the cursor.
        h.ws.send(snapshotFrame("s1", 2, state(""","marker":"reset""""), reset = true))
        h.await(h.client.projectionTrees) { it["s1"]?.get("marker") == JsStr("reset") }
        assertEquals(2L, h.dbSession("s1")!!.cursor)
        h.ws.send(event(3, """"type":"turn_started","turnId":"t3""""))
        h.serverBarrier()
        assertEquals(JsStr("t3"), h.client.projectionTrees.value.getValue("s1")["activeTurnId"])
    }

    @Test
    fun anUnsolicitedStateReplacesTheSavedCopy() {
        firstProcess()
        h.kill(flushFirst = true)
        h.boot(ready = ready(sessionJson("s1")))
        h.ws.send(snapshotFrame("s1", 9, state(""","marker":"pushed"""")))
        h.await(h.client.projectionTrees) { it["s1"]?.get("marker") == JsStr("pushed") }
        assertEquals(9L, h.dbSession("s1")!!.cursor)
    }

    @Test
    fun boundedSnapshotsKeepFetchedDetailsAcrossARestart() {
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        h.expectFrame("attach")
        val trimmed = state().replace(
            """"turnOrder":[],"turnsById":{}""",
            """"turnOrder":["t0","t1"],"turnsById":{"t0":{"turnId":"t0","status":"done","blocks":[],"blocksById":{}},
               "t1":{"turnId":"t1","status":"done","blocks":["y"],"blocksById":{"y":{"text":"new"}}}}""",
        )
        h.ws.send(snapshotFrame("s1", 3, trimmed, trimmedBefore = 1))
        h.ws.send(
            """{"type":"turns-detail","sessionId":"s1","fromIndex":0,"toIndex":1,
               "turns":{"t0":{"turnId":"t0","status":"done","blocks":["x"],"blocksById":{"x":{"text":"old"}}}}}""",
        )
        h.serverBarrier()
        // A reconnect's bounded state still trims t0: the fetched detail stays (memory == DB).
        h.ws.send(snapshotFrame("s1", 3, trimmed, trimmedBefore = 1))
        h.serverBarrier()
        val tree = h.client.projectionTrees.value.getValue("s1")
        assertTrue(JsCodec.canonical(tree).contains("\"old\""))
        assertEquals(JsCodec.canonical(tree), JsCodec.canonical(h.dbFold("s1")!!))
        val before = JsCodec.canonical(tree)
        h.kill(flushFirst = true)
        h.boot(handshake = false)
        h.client.attach("s1")
        assertEquals(before, shown())
        assertEquals(1, h.await(h.client.trimmedBefore) { it.containsKey("s1") }["s1"])
    }

    @Test
    fun aNewStateThatHoldsTheTurnInFullSupersedesItsDetail() {
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        h.expectFrame("attach")
        val order = """"turnOrder":["t0"],"turnsById":{"t0":{"turnId":"t0","status":"done","blocks":[],"blocksById":{}}}"""
        h.ws.send(snapshotFrame("s1", 3, state().replace(""""turnOrder":[],"turnsById":{}""", order), trimmedBefore = 1))
        h.ws.send(
            """{"type":"turns-detail","sessionId":"s1","fromIndex":0,"toIndex":1,
               "turns":{"t0":{"turnId":"t0","status":"done","blocks":["x"],"blocksById":{"x":{"text":"old"}}}}}""",
        )
        h.serverBarrier()
        // Untrimmed: the state is authoritative for t0 (§10 C1).
        h.ws.send(snapshotFrame("s1", 4, state().replace(""""turnOrder":[],"turnsById":{}""", order)))
        h.serverBarrier()
        val tree = h.client.projectionTrees.value.getValue("s1")
        assertTrue(!JsCodec.canonical(tree).contains("\"old\""))
        assertEquals(JsCodec.canonical(tree), JsCodec.canonical(h.dbFold("s1")!!))
        assertTrue(h.dbSession("s1")!!.details.isEmpty())
    }

    @Test
    fun eventsArrivingWhileTheSavedCopyLoadsAreFoldedOnceOnTopOfIt() {
        firstProcess()
        h.kill(flushFirst = true)
        val gate = CountDownLatch(1)
        h.boot(ready = ready(sessionJson("s1"))) { p -> p.mirror.beforeHydrateRead = { gate.await(10, TimeUnit.SECONDS) } }
        h.client.attach("s1")
        attachesFor(h.framesUntilBarrier(), "s1").single()
        h.ws.send(snapshotFrame("s1", 5, state = null))
        h.ws.send(event(6, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" +6""""))
        h.ws.send(event(7, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" +7""""))
        h.serverBarrier()
        assertTrue("nothing shown while the read is held", !h.client.projectionTrees.value.containsKey("s1"))
        gate.countDown()
        val tree = shown()
        assertTrue(tree.contains("saved text +6 +7"))
        assertEquals(tree, JsCodec.canonical(h.dbFold("s1")!!))
    }

    // ta-705 (4): the event JSON -> tree walk is not done under the client lock for an event
    // nothing is waiting to buffer (every live event of a normal session).
    @Test
    fun noFoldedEventIsConvertedUnderTheClientLockWhenNothingHydrates() {
        firstProcess() // snapshot + events 4 and 5 over a live session, nothing hydrating
        h.ws.send(event(6, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" +6""""))
        h.ws.send(event(7, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" +7""""))
        h.serverBarrier()
        assertTrue(shown().contains("saved text +6 +7"))
        assertEquals(
            "an event was converted while the client lock was held with no hydration in flight",
            0,
            h.client.eventConversionsUnderLock.get(),
        )
    }

    @Test
    fun anEventBufferedForAHydrationIsStillConvertedAndFoldedOnce() {
        firstProcess()
        h.kill(flushFirst = true)
        val gate = CountDownLatch(1)
        h.boot(ready = ready(sessionJson("s1"))) { p -> p.mirror.beforeHydrateRead = { gate.await(10, TimeUnit.SECONDS) } }
        h.client.attach("s1")
        attachesFor(h.framesUntilBarrier(), "s1").single()
        h.ws.send(snapshotFrame("s1", 5, state = null))
        h.ws.send(event(6, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" +6""""))
        h.serverBarrier()
        // The probe sees the lock: the one place an event IS converted under it is the buffer.
        assertEquals(1, h.client.eventConversionsUnderLock.get())
        gate.countDown()
        assertTrue(shown().contains("saved text +6"))
    }

    @Test
    fun anUnreadableSavedCopyIsDroppedAndTheSessionFullyReattached() {
        firstProcess()
        h.kill(flushFirst = true)
        // Swap two event rows (seq is in each blob's AAD): the copy no longer opens.
        val db = h.dbFactory.open(JournalMirror.dbName(JournalMirror.originKeyOf(h.origin)))
        db.openHelper.writableDatabase.apply {
            execSQL("UPDATE journal_event SET seq = -1 WHERE seq = 4")
            execSQL("UPDATE journal_event SET seq = 4 WHERE seq = 5")
            execSQL("UPDATE journal_event SET seq = 5 WHERE seq = -1")
        }
        db.close()
        h.boot(ready = ready(sessionJson("s1")))
        h.client.attach("s1")
        val attaches = mutableListOf<JsonObject>()
        val deadline = System.currentTimeMillis() + 10_000
        while (attaches.none { it["afterSeq"] == null } && System.currentTimeMillis() < deadline) {
            attaches += attachesFor(h.framesUntilBarrier(), "s1")
        }
        assertEquals("first the delta attach from the restored cursor", 5L, attaches.first()["afterSeq"]!!.jsonPrimitive.longOrNull)
        assertNull("then, the copy being unreadable, a full one", attaches.last()["afterSeq"])
        assertNull(h.dbSession("s1"))
    }

    @Test
    fun theReadyReattachIsCappedToPinnedAndTheTenMostRecentlyOpened() {
        val list = (0 until 15).map { sessionJson("m$it", pinned = it == 0 || it == 1) }.toTypedArray()
        h.boot(ready = ready(*list))
        for (i in 0 until 15) {
            h.now.addAndGet(1_000)
            h.client.attach("m$i") // opened in order: m14 is the most recent
            h.ws.send(snapshotFrame("m$i", 1, state().replace("s1", "m$i")))
        }
        h.serverBarrier()
        h.dbSession("m0")
        h.kill(flushFirst = true)
        h.boot(ready = ready(*list))
        val attached = h.framesUntilBarrier().filter { it.type() == "attach" }.map { it["sessionId"]!!.jsonPrimitive.content }.toSet()
        // Pinned m0, m1 + the 10 most recently opened (m5..m14); m2..m4 keep their saved copy.
        assertEquals((listOf("m0", "m1") + (5 until 15).map { "m$it" }).toSet(), attached)
        // Opening a capped-out one attaches it, from its cursor.
        h.client.attach("m3")
        val m3 = h.framesUntilBarrier().single { it.type() == "attach" }
        assertEquals(1L, m3["afterSeq"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun aSignOutDropsTheSavedCopyAndItsCursors() {
        firstProcess()
        h.kill(flushFirst = true)
        h.boot(handshake = false)
        h.client.stop() // sign-out: the mirror and its keys go
        val deadline = System.currentTimeMillis() + 10_000
        while (h.dbFactory.existing().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(h.dbFactory.existing().isEmpty())
        assertTrue(h.client.projectionTrees.value.isEmpty())
    }
}
