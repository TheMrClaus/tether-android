package com.tether.app.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v141 (tether #244 part C) over a MockWebServer socket: `archive-stale` out (use-tether.ts requestArchiveStale)
 * and `archive-stale-result` in, each reply numbered so two equal ones are still two.
 */
class ArchiveStaleClientTest {
    private val h = ConnectionHarness()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After
    fun tearDown() {
        scope.cancel()
        h.close()
    }

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return client to ws
    }

    private fun framesOf(type: String) = h.framesUntilBarrier().filter { it.type() == type }

    private fun reply(mode: String, days: Int = 30, eligible: Int = 0, archived: Int = 0, failed: Int = 0, remaining: Int = 0, extra: String = "") =
        """{"type":"archive-stale-result","mode":"$mode","days":$days,"eligible":$eligible,"archived":$archived,"failed":$failed,"remaining":$remaining$extra}"""

    @Test
    fun aPreviewGoesOutAsTheWebSendsItWithTheOpenSessionExempted() {
        val (client, _) = connected()
        assertTrue(client.requestArchiveStale("preview", 30, "s1", client.consentOrigin.value))
        val frame = framesOf("archive-stale").single()
        assertEquals("preview", frame["mode"]!!.jsonPrimitive.content)
        assertEquals(30, frame["days"]!!.jsonPrimitive.content.toInt())
        assertEquals("s1", frame["exceptSessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun noOpenSessionMeansNoExceptSessionIdKey() {
        val (client, _) = connected()
        assertTrue(client.requestArchiveStale("run", 7, null, null))
        val frame = framesOf("archive-stale").single()
        assertEquals("run", frame["mode"]!!.jsonPrimitive.content)
        assertFalse("exceptSessionId" in frame)
        assertTrue(client.requestArchiveStale("run", 15, "", null))
        assertFalse("exceptSessionId" in framesOf("archive-stale").single())
    }

    @Test
    fun aFrameForAnotherServerThanTheLiveSocketIsNotSent() {
        val (client, _) = connected()
        assertFalse(client.requestArchiveStale("run", 30, null, "https://another.example"))
        assertTrue(framesOf("archive-stale").isEmpty())
    }

    @Test
    fun withoutALiveSocketNothingIsSent() {
        val client = h.newClient()
        assertFalse(client.requestArchiveStale("preview", 30, null, null))
    }

    @Test
    fun aReplyIsPublishedAndEqualRepliesAreStillNew() {
        val (client, ws) = connected()
        assertNull(client.archiveStale.value)
        ws.send(reply("preview", eligible = 3, extra = ""","skipped":{"pinned":1,"inFlight":0,"pendingRequest":0,"background":0,"viewing":0},"retention":{"cap":100,"retiredNow":2,"willBePruned":0}"""))
        val first = h.await(client.archiveStale) { it != null }!!
        assertEquals("preview", first.result.mode)
        assertEquals(3, first.result.eligible)
        assertEquals(1, first.result.skipped.pinned)
        assertEquals(100, first.result.retention.cap)

        ws.send(reply("run", archived = 25, remaining = 5))
        val second = h.await(client.archiveStale) { it != null && it.seq > first.seq }!!
        ws.send(reply("run", archived = 25, remaining = 5))
        val third = h.await(client.archiveStale) { it != null && it.seq > second.seq }!!
        assertEquals(second.result, third.result)
        assertTrue(third.seq > second.seq)
    }

    @Test
    fun clearingDropsTheLastReply() {
        val (client, ws) = connected()
        ws.send(reply("preview", eligible = 1))
        h.await(client.archiveStale) { it != null }
        client.clearArchiveStale()
        assertNull(client.archiveStale.value)
    }
}
