package com.tether.app.client

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.1D: the client keeps the v128 projection TREE as its source of truth — snapshot state,
 * live events folded by the v128 reducer, `turns-detail` merges — and publishes the typed
 * projection adapted from it.
 */
class RealTetherClientTreeTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun fullState(turns: String = "", order: String = "", active: String = "null") =
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","nativeSessionId":null,"cliCapabilities":[],
            "cliVersion":null,"cliInventory":null,"mcpHealth":{},"rateLimit":null,"rateLimitResume":null,
            "fastModeState":null,"fastModeDisabledReason":null,"accountAuth":null,"todo":null,"todoTasks":[],
            "status":"ready","lastTurnOutcome":null,"lastError":null,"unattributedPermissionDenials":[],
            "providerNotices":[],"lastModelFallback":null,"notices":[],"dismissedNotices":[],
            "backgroundCommands":[],"backgroundTasks":[],"spawnedRuns":[{"runId":"r1","future":true}],
            "spawnedRunKeys":[],"turnOrder":[$order],"turnsById":{$turns},"activeTurnId":$active,"queuedMessages":[]}"""

    private fun stubTurn(id: String) =
        """"$id":{"turnId":"$id","status":"done","outcome":"ok","blocks":[],"blocksById":{}}"""

    @Test
    fun snapshotTreeIsKeptAndLiveEventsFoldOntoIt() {
        val ws = connected()
        h.client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, fullState()))
        val tree = h.await(h.client.projectionTrees) { it.containsKey("s1") }.getValue("s1")
        // A v128-only key the typed model does not know survives in the tree.
        assertEquals(JsStr("r1"), ((tree["spawnedRuns"] as JsArr)[0] as JsObj)["runId"])
        assertEquals("claude", h.await(h.client.projections) { it.containsKey("s1") }.getValue("s1").provider)

        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1","seq":2,"ts":2000}}""")
        ws.send(
            """{"type":"event","sessionId":"s1","event":{"type":"message_delta","turnId":"t1","blockId":"b1","text":"hi","seq":3,"ts":2100}}""",
        )
        val typed = h.await(h.client.projections) { it["s1"]?.turnsById?.get("t1")?.blocksById?.get("b1")?.text == "hi" }
        assertEquals("t1", typed.getValue("s1").activeTurnId)
        val folded = h.client.projectionTrees.value.getValue("s1")
        assertEquals(JsStr("t1"), folded["activeTurnId"])
        // The fold spread the untouched v128 subtree forward by identity.
        assertSame(tree["spawnedRuns"], folded["spawnedRuns"])

        // An event the reducer ignores publishes nothing new: same tree, same typed projection.
        val typedBefore = h.client.projections.value.getValue("s1")
        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"totally_unknown_type","turnId":"t1","seq":4,"ts":2200}}""")
        h.serverBarrier(ws)
        assertSame(folded, h.client.projectionTrees.value.getValue("s1"))
        assertSame(typedBefore, h.client.projections.value.getValue("s1"))
    }

    @Test
    fun turnsDetailReplacesTrimmedStubs() {
        val ws = connected()
        h.client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, fullState(turns = stubTurn("t0") + "," + stubTurn("t1"), order = "\"t0\",\"t1\""), trimmedBefore = 1))
        h.await(h.client.projections) { it["s1"]?.turnsById?.size == 2 }
        assertEquals(mapOf("s1" to 1), h.client.trimmedBefore.value)

        ws.send(
            """{"type":"turns-detail","sessionId":"s1","fromIndex":0,"toIndex":1,"turns":{"t0":{"turnId":"t0","status":"done",
               "outcome":"ok","blocks":["m1"],"blocksById":{"m1":{"blockId":"m1","kind":"message","text":"full","done":true}}}}}""",
        )
        val typed = h.await(h.client.projections) { it["s1"]?.turnsById?.get("t0")?.blocks == listOf("m1") }.getValue("s1")
        assertEquals("full", typed.turnsById.getValue("t0").blocksById.getValue("m1").text)
        assertEquals(listOf("t0", "t1"), typed.turnOrder)
        assertTrue(typed.turnsById.getValue("t1").blocks.isEmpty())
    }

    @Test
    fun aFoldThatCannotRunOnAMalformedBaseAsksForAFullSnapshot() {
        val ws = connected()
        h.client.attach("s1")
        h.expectFrame("attach")
        // No turnsById at all: events.mjs itself would throw folding a turn onto this.
        ws.send(snapshotFrame("s1", 1))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }

        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1","seq":2,"ts":2000}}""")
        val attach = h.expectFrame("attach")
        assertEquals("s1", attach["sessionId"]!!.jsonPrimitive.content)
        assertNull("full snapshot requested", attach["afterSeq"])
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))
        assertFalse(h.client.projections.value.containsKey("s1"))

        // Bounded: further events for the dropped session send NO more attaches until the
        // snapshot arrives (T2.1D verifier gap). The reader handles frames in order, so once the
        // trailing error frame is observed, both events before it have been processed.
        val errors = java.util.concurrent.LinkedBlockingQueue<String>()
        val errorJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch(
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
        ) { h.client.serverErrors.collect { errors.put(it.text) } }
        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"message_delta","turnId":"t1","blockId":"b1","text":"x","seq":3,"ts":3000}}""")
        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_end","turnId":"t1","seq":4,"ts":4000}}""")
        ws.send("""{"type":"error","message":"barrier"}""")
        assertEquals("barrier", errors.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        errorJob.cancel()
        assertTrue(
            "no second attach before the healing snapshot",
            h.framesUntilBarrier().none { it.type() == "attach" },
        )

        // The full snapshot heals it.
        ws.send(snapshotFrame("s1", 2, fullState()))
        h.await(h.client.projections) { it.containsKey("s1") }
    }
}
