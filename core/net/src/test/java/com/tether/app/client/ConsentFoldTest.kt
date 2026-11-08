package com.tether.app.client

import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tth-9hvv (#5): a tapped consent card must leave "Answer sent". T0 is the control (the normal stream
 * folds), T1 the dead-downlink probe (C1), T2 the dropped-close heal (C2) with its no-fire controls.
 */
class ConsentFoldTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(state: String = consentStateJson(), throughSeq: Long = 5): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions("s1"))
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", throughSeq, state))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun tree(c: RealTetherClient) = c.projectionTrees.value["s1"]
    private fun attachesNull(f: List<JsonObject>) = f.filter { it.type() == "attach" && it["afterSeq"] == null }

    private fun answer(c: RealTetherClient) =
        c.answerQuestion("s1", "q1", consentFp(c, "s1", "q1", question = true), listOf(ConsentGuard.QuestionPick(0, listOf(0), "")))

    private val q1Resolve = ""","requestId":"q1""""

    // ---- T0 -------------------------------------------------------------------------------------

    @Test
    fun t0QuestionStreamFoldsTheCard() {
        val (c, ws) = connected()
        assertEquals(ConsentResult.Sent, answer(c))
        ws.send(eventFrame("s1", 6, "question_answered", "t1", ""","requestId":"q1","toolId":"ask-1","items":[]"""))
        ws.send(eventFrame("s1", 7, "question_resolved", "t1", q1Resolve))
        ws.send(eventFrame("s1", 8, "turn_end", "t1", ""","outcome":"completed""""))
        h.serverBarrier(ws)
        assertNull(ConsentGuard.pendingQuestion(tree(c), "q1"))
        assertNull(ConsentGuard.activeTurnId(tree(c)))
        assertTrue("no full resync on the normal stream", attachesNull(h.framesUntilBarrier()).isEmpty())
    }

    @Test
    fun t0ApprovalStreamFoldsTheCard() {
        val (c, ws) = connected()
        assertEquals(ConsentResult.Sent, c.approval("s1", "r-choice", consentFp(c, "s1", "r-choice"), choiceId = "accept"))
        ws.send(eventFrame("s1", 6, "approval_resolved", "t1", ""","requestId":"r-choice","choiceId":"accept""""))
        ws.send(eventFrame("s1", 7, "turn_end", "t1", ""","outcome":"completed""""))
        h.serverBarrier(ws)
        assertNull(ConsentGuard.pendingApproval(tree(c), "r-choice"))
        assertNull(ConsentGuard.activeTurnId(tree(c)))
        assertTrue(attachesNull(h.framesUntilBarrier()).isEmpty())
    }

    // ---- T1 (C1) --------------------------------------------------------------------------------

    @Test
    fun t1ADeadDownlinkAfterTheTapIsProbedDroppedAndHealed() {
        val (c, _) = connected()
        // The probe drops only on lastInboundAt < sentAt (strict): the tap is after the last inbound frame.
        h.now.addAndGet(1)
        assertEquals(ConsentResult.Sent, answer(c))
        val sent = h.framesUntilBarrier()
        assertEquals("the tap, then a probe", listOf("question", "ping"), sent.map { it.type() }.filter { it == "question" || it == "ping" })
        // The server stays silent: the probe's deadline passes and the socket is dropped.
        h.enqueueConnect()
        h.scheduler.await { it == ConnectionTimings.PING_TIMEOUT_MS }.fire()
        h.await(c.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        // The cursor attach (behind head) comes back with the resolved state.
        val attach = h.expectFrame("attach")
        assertEquals(5L, attach["afterSeq"]!!.toString().toLong())
        val resolved = foldTree(
            JsCodec.parse(consentStateJson()) as com.tether.app.protocol.tree.JsObj,
            ev("question_answered", "t1", seq = 6, ts = 6) { put("requestId", "q1"); put("toolId", "ask-1"); putJsonArray("items") {} },
            ev("question_resolved", "t1", seq = 7, ts = 7) { put("requestId", "q1") },
            ev("turn_end", "t1", seq = 8, ts = 8) { put("outcome", "completed") },
        )
        ws2.send(snapshotFrame("s1", 8, JsCodec.toJson(resolved).toString()))
        h.await(c.projectionTrees) { ConsentGuard.pendingQuestion(it["s1"], "q1") == null && it["s1"] != null }
    }

    // ---- T2 (C2) --------------------------------------------------------------------------------

    /** Active turn t0 carrying pending q1 and r-choice; the closing event below names turn t1, so the guard drops it. */
    private fun staleStateJson(): String = JsCodec.toJson(
        foldTree(
            freshTree(),
            ev("turn_started", "t0", seq = 1, ts = 1) { put("idempotencyKey", "k0") },
            ev("question_request", "t0", seq = 2, ts = 2) {
                put("requestId", "q1"); put("toolId", "ask-1")
                putJsonArray("questions") {
                    addJsonObject {
                        put("question", "Which DB?"); put("header", "Database"); put("multiSelect", false)
                        putJsonArray("options") { addJsonObject { put("label", "Postgres"); put("description", "") } }
                    }
                }
            },
            ev("approval_request", "t0", seq = 3, ts = 3) {
                put("requestId", "r-choice"); put("toolId", "tool-1"); put("name", "command_execution")
                putJsonArray("choices") { addJsonObject { put("choiceId", "accept"); put("label", "Yes") } }
            },
        ),
    ).toString()

    private val staleClosers = listOf(
        Triple("question_resolved", q1Resolve, "q1"),
        Triple("question_cancelled", ""","requestId":"q1"""", "q1"),
        Triple("approval_resolved", ""","requestId":"r-choice","choiceId":"accept"""", "r-choice"),
        Triple("approval_expired", ""","requestId":"r-choice"""", "r-choice"),
    )

    @Test
    fun t2EachDroppedClosingEventAsksForOneFullResyncAndTheReplyFoldsTheCard() {
        for ((type, fields, id) in staleClosers) {
            val hh = ConnectionHarness()
            try {
                val client = hh.newClient()
                hh.enqueueConnect(); client.start()
                val ws = hh.nextSocket(); hh.handshake(ws, readyWithSessions("s1"))
                client.attach("s1"); hh.expectFrame("attach")
                ws.send(snapshotFrame("s1", 3, staleStateJson()))
                hh.await(client.liveSessions) { "s1" in it }
                // N2: this event folds WITHOUT throwing (the fold-exception path is not what asks), and is dropped.
                val before = client.projectionTrees.value["s1"]!!
                val evt = ev(type, "t1", seq = 4, ts = 4) { put("requestId", id) }
                assertTrue("the reducer drops it, unchanged", foldTree(before, evt) === before)
                ws.send(eventFrame("s1", 4, type, "t1", fields))
                val attach = hh.expectFrame("attach")
                assertNull("a FULL attach, not a cursor attach ($type)", attach["afterSeq"])
                assertEquals(0, attachesNull(hh.framesUntilBarrier()).size)
                val healed = foldTree(freshTree(), ev("turn_started", "t0", seq = 1, ts = 1) { put("idempotencyKey", "k0") })
                ws.send(snapshotFrame("s1", 4, JsCodec.toJson(healed).toString()))
                hh.await(client.projectionTrees) { it["s1"] != null && !requestPending(it["s1"], id) }
            } finally {
                hh.close()
            }
        }
    }

    private fun requestPending(t: com.tether.app.protocol.tree.JsObj?, id: String) =
        ConsentGuard.pendingQuestion(t, id) != null || ConsentGuard.pendingApproval(t, id) != null

    @Test
    fun noFireAResolveForARequestThisDeviceNeverSaw() {
        val (c, ws) = connected(staleStateJson(), 3)
        ws.send(eventFrame("s1", 4, "question_resolved", "t1", ""","requestId":"never-seen""""))
        h.serverBarrier(ws)
        assertTrue(attachesNull(h.framesUntilBarrier()).isEmpty())
        assertNotNull(tree(c))
    }

    @Test
    fun noFireADuplicateResolve() {
        val (c, ws) = connected()
        ws.send(eventFrame("s1", 6, "question_resolved", "t1", q1Resolve))
        ws.send(eventFrame("s1", 6, "question_resolved", "t1", q1Resolve))
        h.serverBarrier(ws)
        assertTrue(attachesNull(h.framesUntilBarrier()).isEmpty())
        assertNull(ConsentGuard.pendingQuestion(tree(c), "q1"))
    }

    @Test
    fun noFireATurnEndOfANonActiveTurn() {
        val (_, ws) = connected(staleStateJson(), 3)
        ws.send(eventFrame("s1", 4, "turn_end", "t1", ""","outcome":"completed""""))
        h.serverBarrier(ws)
        assertTrue(attachesNull(h.framesUntilBarrier()).isEmpty())
    }

    @Test
    fun noFireAClosingEventForASessionTheAppNoLongerKeeps() {
        val client = h.newClient(retainedOthers = 0)
        h.enqueueConnect(); client.start()
        val ws = h.nextSocket(); h.handshake(ws, readyWithSessions("s1", "s2"))
        client.attach("s1"); h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 3, staleStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        // Opening s2 releases s1 (retainedOthers = 0): it is no longer kept.
        client.attach("s2"); h.expectFrame("attach")
        ws.send(snapshotFrame("s2", 1, """{"tetherSessionId":"s2","provider":"claude","cwd":"/w"}"""))
        h.await(client.liveSessions) { "s2" in it }
        ws.send(eventFrame("s1", 4, "question_resolved", "t1", q1Resolve))
        h.serverBarrier(ws)
        assertTrue(attachesNull(h.framesUntilBarrier()).filter { it["sessionId"].toString().contains("s1") }.isEmpty())
    }
}
