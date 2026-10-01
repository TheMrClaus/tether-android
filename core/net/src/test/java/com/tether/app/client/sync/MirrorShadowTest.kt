package com.tether.app.client.sync

import com.tether.app.client.snapshotFrame
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 step 1 (SYNC_DESIGN §2.6, T13.1a): the client records snapshots, events, turn details and
 * the session list into the mirror, and `fold(DB.base, DB.tail) == the in-memory tree` once the
 * write queue is empty (§2.1). Wipes on logout and revocation (§8.3).
 */
@RunWith(RobolectricTestRunner::class)
class MirrorShadowTest {
    private val h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private fun fullState(turns: String = "", order: String = "", active: String = "null") =
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","nativeSessionId":null,"cliCapabilities":[],
            "cliVersion":null,"cliInventory":null,"mcpHealth":{},"rateLimit":null,"rateLimitResume":null,
            "fastModeState":null,"fastModeDisabledReason":null,"accountAuth":null,"todo":null,"todoTasks":[],
            "status":"ready","lastTurnOutcome":null,"lastError":null,"unattributedPermissionDenials":[],
            "providerNotices":[],"lastModelFallback":null,"notices":[],"dismissedNotices":[],
            "backgroundCommands":[],"backgroundTasks":[],"spawnedRuns":[],
            "spawnedRunKeys":[],"turnOrder":[$order],"turnsById":{$turns},"activeTurnId":$active,"queuedMessages":[]}"""

    private fun event(seq: Long, body: String) = """{"type":"event","sessionId":"s1","event":{$body,"seq":$seq,"ts":${seq * 1000}}}"""

    private fun tree(): JsObj = h.client.projectionTrees.value.getValue("s1")

    private fun assertMirrorMatchesMemory() {
        val db = h.dbFold("s1")
        assertNotNull("no mirrored base", db)
        assertEquals(JsCodec.canonical(tree()), JsCodec.canonical(db!!))
    }

    private fun attachWithState(throughSeq: Long = 1, state: String = fullState(), trimmedBefore: Int? = null) {
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", throughSeq, state, trimmedBefore = trimmedBefore))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
    }

    @Test
    fun snapshotEventsAndTurnDetailsAreMirroredExactly() {
        h.boot()
        attachWithState(trimmedBefore = 1, state = fullState(turns = """"t0":{"turnId":"t0","status":"done","blocks":[],"blocksById":{}}""", order = "\"t0\""))
        h.ws.send(event(2, """"type":"turn_started","turnId":"t1""""))
        h.ws.send(event(3, """"type":"message_delta","turnId":"t1","blockId":"b1","text":"hello""""))
        h.ws.send(event(4, """"type":"message_delta","turnId":"t1","blockId":"b1","text":" world""""))
        h.ws.send(
            """{"type":"turns-detail","sessionId":"s1","fromIndex":0,"toIndex":1,
               "turns":{"t0":{"turnId":"t0","status":"done","blocks":["x"],"blocksById":{"x":{"text":"old"}}}}}""",
        )
        h.serverBarrier()
        assertMirrorMatchesMemory()
        val stored = h.dbSession("s1")!!
        assertEquals(4L, stored.cursor)
        assertEquals(1L, stored.throughSeq)
        assertEquals(1, stored.trimmedBefore)
        assertEquals(setOf("t0"), stored.details.keys)
        assertEquals(3, stored.tail.size)
    }

    @Test
    fun aStatelessReplyVerifiesAndAResetReplacesTheBase() {
        h.boot()
        attachWithState(throughSeq = 5)
        h.ws.send(snapshotFrame("s1", 5, state = null))
        h.serverBarrier()
        assertEquals(5L, h.dbSession("s1")!!.cursor)
        h.ws.send(event(6, """"type":"turn_started","turnId":"t1""""))
        h.serverBarrier()
        // Reset: cursor goes DOWN, base replaced, tail gone.
        h.ws.send(snapshotFrame("s1", 2, fullState(), reset = true))
        h.serverBarrier()
        val stored = h.dbSession("s1")!!
        assertEquals(2L, stored.cursor)
        assertTrue(stored.tail.isEmpty())
        assertMirrorMatchesMemory()
    }

    @Test
    fun duplicatesAndGapsNeverReachTheMirror() {
        h.boot()
        attachWithState()
        h.ws.send(event(2, """"type":"turn_started","turnId":"t1""""))
        h.ws.send(event(2, """"type":"turn_started","turnId":"t1""""))
        h.ws.send(event(5, """"type":"turn_started","turnId":"t9""""))
        h.serverBarrier()
        assertEquals(2L, h.dbSession("s1")!!.cursor)
        assertMirrorMatchesMemory()
    }

    @Test
    fun aSeqlessEventFoldsInMemoryButClearsThePersistedCursor() {
        h.boot()
        attachWithState()
        h.ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1"}}""")
        h.serverBarrier()
        assertEquals(com.tether.app.protocol.tree.JsStr("t1"), tree()["activeTurnId"])
        val stored = h.dbSession("s1")!!
        assertNull("a seqless fold is not covered by the DB", stored.cursor)
        assertTrue(stored.tail.isEmpty())
    }

    @Test
    fun aFoldExceptionDropsTheMirroredSession() {
        h.boot()
        // A base the reducer cannot fold onto (no turnsById / turnOrder).
        attachWithState(state = """{"tetherSessionId":"s1","provider":"claude","cwd":"/w"}""")
        h.ws.send(event(2, """"type":"turn_started","turnId":"t1""""))
        val full = h.expectFrame("attach")
        assertFalse("full attach after a fold throw", full.containsKey("afterSeq"))
        assertNull(h.dbSession("s1"))
    }

    @Test
    fun theSessionListIsMirrored() {
        h.boot(
            ready = """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
                "sessions":[{"id":"a","provider":"claude","name":"Alpha","cwd":"/w","status":"ready","startedAt":1,"updatedAt":5,
                "pinned":true,"runtimeArchived":false,"mode":"headless"}]}""",
        )
        h.serverBarrier()
        val index = runBlocking { h.mirror.flush(); h.mirror.bind(h.origin)!! }
        val a = index.sessions.single { it.sessionId == "a" }
        assertTrue(a.pinned)
        assertEquals("Alpha", MirrorLink.decodeSession(a.json)!!.name)
        // The barrier session arrived as `created`: an upsert.
        assertTrue(index.sessions.any { it.sessionId.startsWith("barrier-") })
    }

    @Test
    fun logoutWipesTheMirrorAndDestroysItsKeys() {
        h.boot()
        attachWithState()
        h.dbSession("s1")
        runBlocking { h.client.logout() }
        awaitWiped()
    }

    @Test
    fun stopWipesTheMirror() {
        h.boot()
        attachWithState()
        h.dbSession("s1")
        h.client.stop()
        awaitWiped()
    }

    @Test
    fun deviceRevocationWipesTheMirror() {
        h.boot()
        attachWithState()
        h.dbSession("s1")
        h.ws.close(4001, "device revoked")
        awaitWiped()
    }

    @Test
    fun cookieRevocationWipesTheMirror() {
        h.boot()
        attachWithState()
        h.dbSession("s1")
        h.ws.close(4002, "session revoked")
        awaitWiped()
    }

    private fun awaitWiped() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (h.dbFactory.existing().isEmpty() && !h.keyFile.exists() && h.kek.destroyed > 0) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "mirror not wiped: dbs=${h.dbFactory.existing().size} keyFile=${h.keyFile.exists()} kekDestroyed=${h.kek.destroyed}",
        )
    }
}
