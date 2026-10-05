package com.tether.app.client.sync

import com.tether.app.client.ConnectionState
import com.tether.app.client.isReconnectDelay
import com.tether.app.client.snapshotFrame
import com.tether.app.client.type
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-coik.32 (R3 r2): a chat opened while a new socket's re-attach waits for the open chat is an
 * open like any other: the mirror records it (§3.1 rule 5 orders the capped re-attach by it).
 */
@RunWith(RobolectricTestRunner::class)
class DeferredAttachMirrorTest {
    private val h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private fun session(id: String) =
        """{"id":"$id","provider":"claude","name":"$id","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,""" +
            """"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private val listing =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"sessions":[${session("s1")},${session("s2")}],""" +
            """"providers":[],"workspaceRoot":null}"""

    private fun attaches() = h.framesUntilBarrier().filter { it.type() == "attach" }.map { it["sessionId"]!!.jsonPrimitive.content }

    @Test
    fun openingAChatWaitingInTheDeferredReattachRecordsTheOpen() {
        h.boot(ready = listing)
        for (id in listOf("s1", "s2")) {
            h.now.addAndGet(1)
            h.client.attach(id)
            h.expectFrame("attach")
            h.ws.send(snapshotFrame(id, 1))
            h.await(h.client.projectionTrees) { it.containsKey(id) }
        }
        // A new socket: s2 (on screen) goes first, s1 waits for s2's snapshot.
        val p = h.process!!
        h.enqueueConnect()
        h.ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        p.scheduler.await(::isReconnectDelay).fire()
        p.ws = h.sockets.poll(10, TimeUnit.SECONDS).also { assertNotNull("no new socket", it) }
        h.ws.send(listing)
        h.expectFrame("hello")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        assertEquals(listOf("s2"), attaches())

        val openedAt = h.now.addAndGet(1_000)
        h.client.attach("s1") // the reader opens s1 while it waits
        assertEquals(listOf("s1"), attaches())
        val index = runBlocking { h.mirror.flush(); h.mirror.bind(h.origin)!! }
        assertEquals("the open is recorded", openedAt, index.sessions.single { it.sessionId == "s1" }.lastOpenedAt)
    }
}
