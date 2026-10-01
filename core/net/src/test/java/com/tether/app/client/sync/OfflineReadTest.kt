package com.tether.app.client.sync

import com.tether.app.client.ConnectionState
import com.tether.app.client.Freshness
import com.tether.app.client.snapshotFrame
import com.tether.app.protocol.tree.JsCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.2 (SYNC_DESIGN §4.2, §11 OfflineReadTest): a DB seeded by one process, then the network
 * REFUSED (the server is gone: every connect fails), then a new process starts. The list and
 * projection flows emit the saved copy with no network at all, and freshness is Saved with the
 * age of the snapshot that last verified it; a listed session with no copy is NotDownloaded.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineReadTest {
    private val h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private fun sessionJson(id: String, status: String) =
        """{"id":"$id","provider":"claude","name":"n-$id","cwd":"/w","status":"$status","startedAt":1,"updatedAt":1,
            "pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private fun ready(vararg sessions: String) =
        """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[${sessions.joinToString(",")}]}"""

    private val state = """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}"""

    private fun event(seq: Long, body: String) = """{"type":"event","sessionId":"s1","event":{$body,"seq":$seq,"ts":$seq}}"""

    @Test
    fun theSavedCopyIsReadWithNoNetworkAndSaysHowOldItIs() {
        // Process 1 mirrors s1 (verified by its snapshot at T1) and lists s2 without a copy.
        val t1 = 5_000_000L
        h.boot(ready = ready(sessionJson("s1", "active"), sessionJson("s2", "waiting")))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.now.set(t1)
        h.ws.send(snapshotFrame("s1", 3, state))
        h.ws.send(event(4, """"type":"turn_started","turnId":"t1""""))
        h.ws.send(event(5, """"type":"message_delta","turnId":"t1","blockId":"b1","text":"saved text""""))
        h.serverBarrier()
        val before = JsCodec.canonical(h.client.projectionTrees.value.getValue("s1"))
        h.kill(flushFirst = true)

        // The network is refused from now on, and twelve minutes pass.
        h.server.shutdown()
        val twelveMin = 12 * 60_000L
        h.now.set(t1 + twelveMin)
        h.bootStartOnly()

        // The list comes from the mirror, before (and without) any network.
        h.await(h.client.sessions) { list -> list.map { it.id }.containsAll(listOf("s1", "s2")) }
        val saved = h.await(h.client.syncStates) { it["s1"]?.freshness == Freshness.Saved && it["s2"] != null }
        assertEquals("verified by process 1's snapshot", t1, saved.getValue("s1").lastVerifiedAt)
        assertEquals(twelveMin, h.now.get() - saved.getValue("s1").lastVerifiedAt!!)
        assertEquals(Freshness.NotDownloaded, saved.getValue("s2").freshness)

        // The UI opens it: the projection is the saved copy, still Saved, never live.
        h.client.attach("s1")
        val tree = h.await(h.client.projectionTrees) { it.containsKey("s1") }.getValue("s1")
        assertEquals(before, JsCodec.canonical(tree))
        assertTrue(JsCodec.canonical(tree).contains("saved text"))
        // ta-x9c: SessionStore.publish sets the tree, then its typed view (two flows), so the typed
        // view is awaited too: read right after the tree, it could still be the previous map.
        assertTrue(h.await(h.client.projections) { it.containsKey("s1") }.containsKey("s1"))
        val after = h.client.syncStates.value.getValue("s1")
        assertEquals(Freshness.Saved, after.freshness)
        assertEquals(t1, after.lastVerifiedAt)
        assertTrue("nothing is live offline", h.client.liveSessions.value.isEmpty())
        assertNotEquals(ConnectionState.Connected, h.client.connection.value)
    }
}
