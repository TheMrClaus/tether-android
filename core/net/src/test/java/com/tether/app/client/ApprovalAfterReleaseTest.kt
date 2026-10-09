package com.tether.app.client

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ta-2vm7: a session released from memory while it holds three pending approvals. A tap made on a
 * card of the old tree is refused, and once the session is opened again (a full attach, a fresh
 * snapshot) the approval is answered with a fresh fingerprint and goes out.
 */
class ApprovalAfterReleaseTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    @Test
    fun anApprovalInAReleasedSessionIsAnsweredAfterItIsOpenedAgain() {
        val ids = (1..6).map { "s$it" }
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyWithSessions(*ids.toTypedArray()))
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        val staleFp = consentFp(client, "s1", "r-plain")
        assertFalse(staleFp.isEmpty())
        for (id in ids.drop(1)) {
            h.now.addAndGet(1)
            client.attach(id)
            h.expectFrame("attach")
            ws.send(snapshotFrame(id, 1))
            h.await(client.projectionTrees) { it.containsKey(id) }
        }
        assertFalse(client.projectionTrees.value.containsKey("s1"))

        // A tap on a card from before the release: nothing is pending in memory, nothing goes out.
        assertEquals(ConsentResult.NotPending, client.approval("s1", "r-plain", staleFp, decision = "deny"))
        assertEquals(emptyList<Any>(), h.framesUntilBarrier().filter { it.type() == "approval" })

        // Opened again: a full attach, the snapshot brings the three approvals back.
        h.now.addAndGet(1)
        client.attach("s1")
        val attach = h.expectFrame("attach")
        assertEquals(null, attach["afterSeq"]?.jsonPrimitive?.longOrNull)
        ws.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.projectionTrees) { it.containsKey("s1") }
        for (request in listOf("r-choice", "r-plain", "r-grant")) {
            assertFalse("$request pending again", consentFp(client, "s1", request).isEmpty())
        }
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-plain", consentFp(client, "s1", "r-plain"), decision = "deny"))
        val sent = h.framesUntilBarrier().filter { it.type() == "approval" }
        assertEquals(listOf("r-plain"), sent.map { it["requestId"]!!.jsonPrimitive.content })
    }
}
