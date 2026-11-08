package com.tether.app.client

import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * tth-9hvv (#5): a refused consent tap is no longer silent. Each local refusal site selects its
 * FINAL operator line on the `errors` flow (the public ConsentResult is unchanged); the refusals that
 * prove the card disagrees with this device's tree ask for a FULL resync (`attach` with no afterSeq,
 * the store dropped), at most once per session per 10 s, and never resend the answer.
 */
class ConsentRefusalLinesTest {

    private val h = ConnectionHarness()
    private val errors = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() = h.close()

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions("s1"))
        h.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { client.errors.collect { errors += it } }
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun frames(): List<JsonObject> = h.framesUntilBarrier()
    private fun attaches() = frames().filter { it.type() == "attach" }
    private fun consent(f: List<JsonObject>) = f.filter { it.type() == "approval" || it.type() == "question" }

    private fun approve(c: RealTetherClient, id: String = "r-choice", fp: String = consentFp(c, "s1", id)) =
        c.approval("s1", id, fp, choiceId = "accept")

    private fun answer(c: RealTetherClient, fp: String = consentFp(c, "s1", "q1", question = true)) =
        c.answerQuestion("s1", "q1", fp, listOf(ConsentGuard.QuestionPick(0, listOf(0), "")))

    private val refresh = " Refreshing this session."

    @Test
    fun anIncompleteApprovalSaysDecisionNotSent() {
        val (c, _) = connected()
        assertEquals(ConsentResult.InvalidChoice, c.approval("s1", "r-plain", consentFp(c, "s1", "r-plain")))
        assertEquals(listOf("Your decision was not sent. Pick an option and try again."), errors.toList())
        assertTrue(attaches().isEmpty())
    }

    @Test
    fun anAlreadyAnsweredQuestionSaysSoAndRefreshes() {
        val (c, ws) = connected()
        val fp = consentFp(c, "s1", "q1", question = true)
        ws.send(eventFrame("s1", 6, "question_answered", "t1", ""","requestId":"q1","toolId":"ask-1","items":[]"""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, answer(c, fp))
        assertEquals(listOf("That question was already answered. Your answer was not sent.$refresh"), errors.toList())
        val f = frames()
        assertTrue("no answer resent", consent(f).isEmpty())
        val attach = f.single { it.type() == "attach" }
        assertNull("a FULL resync, not a cursor attach", attach["afterSeq"])
    }

    @Test
    fun noRunningTurnLocallyDecisionNoun() {
        val (c, ws) = connected()
        val fp = consentFp(c, "s1", "r-choice")
        ws.send(eventFrame("s1", 6, "turn_end", "t1", ""","outcome":"completed""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, approve(c, fp = fp))
        assertEquals("This device has no running turn. Your decision was not sent.$refresh", errors.last())
    }

    @Test
    fun noRunningTurnLocallyAnswerNoun() {
        val (c, ws) = connected()
        val fp = consentFp(c, "s1", "q1", question = true)
        ws.send(eventFrame("s1", 6, "turn_end", "t1", ""","outcome":"completed""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, answer(c, fp))
        assertEquals("This device has no running turn. Your answer was not sent.$refresh", errors.last())
    }

    @Test
    fun aRequestNotOpenLocallyDecisionNoun() {
        val (c, ws) = connected()
        ws.send(eventFrame("s1", 6, "approval_resolved", "t1", ""","requestId":"r-choice","choiceId":"decline""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, approve(c, fp = "x"))
        assertEquals("That request is no longer open here. Your decision was not sent.$refresh", errors.last())
    }

    @Test
    fun aRequestNotOpenLocallyAnswerNoun() {
        val (c, ws) = connected()
        ws.send(eventFrame("s1", 6, "question_resolved", "t1", ""","requestId":"q1""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, answer(c, "x"))
        assertEquals("That request is no longer open here. Your answer was not sent.$refresh", errors.last())
    }

    @Test
    fun driftNeverResyncs() {
        val (c, _) = connected()
        assertEquals(ConsentResult.NotPending, approve(c, fp = "stale"))
        assertEquals("The request on screen is out of date. Your decision was not sent. Check it and try again.", errors.last())
        assertEquals(ConsentResult.NotPending, answer(c, "stale"))
        assertEquals("The request on screen is out of date. Your answer was not sent. Check it and try again.", errors.last())
        assertTrue(attaches().isEmpty())
    }

    @Test
    fun aDoubleTapSaysTheEarlierOneWasSentAndNeverResyncs() {
        val (c, _) = connected()
        assertEquals(ConsentResult.Sent, approve(c))
        assertEquals(ConsentResult.AlreadyDecided, approve(c))
        assertEquals("Your earlier decision was already sent. This one was not sent.", errors.last())
        assertEquals(ConsentResult.Sent, answer(c))
        assertEquals(ConsentResult.AlreadyDecided, answer(c))
        assertEquals("Your earlier answer was already sent. This one was not sent.", errors.last())
        val f = frames()
        assertEquals(2, consent(f).size)
        assertTrue(f.none { it.type() == "attach" })
    }

    @Test
    fun theResyncIsDebouncedAndTheSentenceFollowsIt() {
        val (c, ws) = connected()
        ws.send(eventFrame("s1", 6, "approval_resolved", "t1", ""","requestId":"r-choice","choiceId":"decline""""))
        h.serverBarrier(ws)
        assertEquals(ConsentResult.NotPending, approve(c, fp = "x"))
        assertEquals("That request is no longer open here. Your decision was not sent.$refresh", errors.last())
        // The full resync dropped the store: a second tap inside 10 s is refused without a second attach.
        h.now.addAndGet(9_000)
        assertEquals(ConsentResult.NotPending, approve(c, fp = "x"))
        assertEquals("This device has no running turn. Your decision was not sent.", errors.last())
        assertFalse(errors.last().endsWith("Refreshing this session."))
        h.now.addAndGet(1_001)
        assertEquals(ConsentResult.NotPending, approve(c, fp = "x"))
        assertTrue(errors.last().endsWith(refresh))
        assertEquals(2, attaches().size)
    }

    @Test
    fun aFullResyncFoldsTheStaleCardAndNeverResendsTheAnswer() {
        val (c, ws) = connected()
        // This device still shows q1 pending; the server's truth is that the turn moved on (q1 gone).
        ws.send(eventFrame("s1", 6, "question_answered", "t1", ""","requestId":"q1","toolId":"ask-1","items":[]"""))
        h.serverBarrier(ws)
        assertTrue(consentFp(c, "s1", "q1", question = true).isNotEmpty())
        assertEquals(ConsentResult.NotPending, answer(c))
        // The store is gone at once (a cursor attach would have kept the stale card), the frame has no cursor...
        assertFalse("s1" in c.projectionTrees.value)
        val f = frames()
        assertNull(f.single { it.type() == "attach" }["afterSeq"])
        assertTrue(consent(f).isEmpty())
        // ...and the server's full snapshot folds the card away.
        val fresh = JsCodec.toJson(foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1) { put("idempotencyKey", "k1") })).toString()
        ws.send(snapshotFrame("s1", 7, fresh))
        h.await(c.liveSessions) { "s1" in it }
        assertEquals("", consentFp(c, "s1", "q1", question = true))
        assertTrue("nothing was resent after the resync", consent(frames()).isEmpty())
    }
}
