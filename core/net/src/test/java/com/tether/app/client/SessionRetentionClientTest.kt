package com.tether.app.client

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-2vm7: the client keeps the projection of the open session and the three opened before it (and
 * only while their estimated size fits the budget); every other one is released, and nothing puts it
 * back by itself. The web never detaches and never prunes (its tab simply has a larger heap), so a
 * release is client-local: no frame leaves, the sidebar and the other frame readers are untouched.
 */
class SessionRetentionClientTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun sessionJson(id: String, status: String = "ready", updatedAt: Long = 1) =
        """{"id":"$id","provider":"claude","name":"$id","cwd":"/w","status":"$status","startedAt":1,"updatedAt":$updatedAt,""" +
            """"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private fun readyListing(vararg ids: String) =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"sessions":[${ids.joinToString(",") { sessionJson(it) }}],""" +
            """"providers":[],"workspaceRoot":null}"""

    /** A projection of [chars] characters of padding, one turn. */
    private fun turnState(sessionId: String, chars: Int = 0, extraTurn: Boolean = false) =
        """{"tetherSessionId":"$sessionId","provider":"claude","cwd":"/w","status":"ready",
           "turnOrder":[${if (extraTurn) """"t1"""" else ""}],"turnsById":{${if (extraTurn) """"t1":{"turnId":"t1","blocks":[]}""" else ""}},
           "activeTurnId":null,"queuedMessages":[],"pad":"${"x".repeat(chars)}"}"""

    private fun attaches(frames: List<JsonObject>): List<Pair<String, Long?>> =
        frames.filter { it.type() == "attach" }.map {
            it["sessionId"]!!.jsonPrimitive.content to it["afterSeq"]?.jsonPrimitive?.longOrNull
        }

    private fun connected(ids: List<String>, budget: Long? = null, deviceToken: String? = null): WebSocket {
        h.enqueueConnect()
        h.newClient(retentionBudgetBytes = budget, deviceToken = deviceToken)
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyListing(*ids.toTypedArray()))
        return ws
    }

    /** Opens [id] (one clock tick after the last) and answers with a snapshot through [seq]. */
    private fun open(ws: WebSocket, id: String, chars: Int = 0, seq: Long = 1, state: String = turnState(id, chars)) {
        h.now.addAndGet(1)
        h.client.attach(id)
        h.expectFrame("attach")
        ws.send(snapshotFrame(id, seq, state))
        h.await(h.client.projections) { it.containsKey(id) }
    }

    private fun reconnect(ws: WebSocket, ready: String): WebSocket {
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next, ready)
        return next
    }

    private fun ids(n: Int) = (1..n).map { "s$it" }

    @Test
    fun openingTwentySessionsKeepsAtMostFourInMemory() {
        val all = ids(20)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        val census = h.client.memoryCensus()
        val last4 = setOf("s17", "s18", "s19", "s20")
        assertEquals(last4, h.client.projectionTrees.value.keys)
        assertEquals(last4, h.client.projections.value.keys)
        assertTrue("adapters ${census.adapters}", census.adapters <= 4)
        assertEquals(last4, census.subscribed)
        assertEquals(last4, census.cursors)
        assertEquals(last4, census.attached)
        assertTrue(census.live.all { it in last4 })
    }

    @Test
    fun theSizeBudgetBoundsWhatIsKept() {
        // 400k characters weigh about 1.2 MB estimated (x3): a 3 MB budget holds two of them.
        val ws = connected(ids(6), budget = 3_000_000)
        ids(6).forEach { open(ws, it, chars = 400_000) }
        assertEquals(setOf("s5", "s6"), h.client.projectionTrees.value.keys)
        assertTrue(h.client.memoryCensus().adapters <= 2)
    }

    @Test
    fun oneHugeSessionReleasesTheOthersButNeverItselfWhileOpen() {
        val ws = connected(ids(3), budget = 3_000_000)
        open(ws, "s1", chars = 1_000)
        open(ws, "s2", chars = 1_000)
        open(ws, "s3", chars = 1_500_000) // ~4.5 MB estimated: alone over the budget
        assertEquals(setOf("s3"), h.client.projectionTrees.value.keys)
        // Events keep landing on it: it stays.
        ws.send(turnStartedEvent("s3", "t1", 2))
        h.await(h.client.projections) { it["s3"]?.turnsById?.containsKey("t1") == true }
        assertEquals(setOf("s3"), h.client.projectionTrees.value.keys)
    }

    @Test
    fun aReleasedSessionReopensWithTheTranscriptAFoldThatNeverReleasedItHas() {
        val ws = connected(ids(6))
        open(ws, "s1", state = turnState("s1"))
        ws.send(turnStartedEvent("s1", "t2", 2))
        h.await(h.client.projections) { it["s1"]?.turnsById?.containsKey("t2") == true }
        val reference: JsObj = h.client.projectionTrees.value.getValue("s1")
        val referenceTyped = h.client.projections.value.getValue("s1")
        ids(6).drop(1).forEach { open(ws, it) } // s1 is released (s2..s5 retained, s6 open)
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))
        assertFalse(h.client.projections.value.containsKey("s1"))

        h.now.addAndGet(1)
        h.client.attach("s1")
        // The cursor was reset with the release: a FULL attach (a cursor at head would come back blank).
        assertEquals(listOf("s1" to null), attaches(h.framesUntilBarrier()))
        ws.send(snapshotFrame("s1", 2, JsCodec.stringify(reference)))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        assertEquals(reference, h.client.projectionTrees.value.getValue("s1"))
        assertEquals(referenceTyped, h.client.projections.value.getValue("s1"))
        // And it is a live, attached session again, with a cursor.
        h.await(h.client.liveSessions) { "s1" in it }
        assertTrue("s1" in h.client.memoryCensus().cursors)
    }

    @Test
    fun theReconnectReattachesTheRetainedSetOnly() {
        val all = ids(8)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        val ws2 = reconnect(ws, readyListing(*all.toTypedArray()))
        val first = attaches(h.framesUntilBarrier()).map { it.first }
        // The open chat first, then (after its snapshot) the other retained ones: never s1..s4.
        assertEquals(listOf("s8"), first)
        ws2.send(snapshotFrame("s8", 1, turnState("s8")))
        h.serverBarrier(ws2)
        val rest = attaches(h.framesUntilBarrier()).map { it.first }
        assertEquals(setOf("s5", "s6", "s7", "s8"), (first + rest).toSet())
        assertTrue((first + rest).none { it in setOf("s1", "s2", "s3", "s4") })
    }

    @Test
    fun aGapOnAReleasedSessionNeitherAttachesNorBringsItsProjectionBack() {
        val all = ids(6)
        val ws = connected(all)
        all.forEach { open(ws, it) } // s1, s2 released
        ws.send(turnStartedEvent("s1", "t9", 7)) // seq far ahead of any cursor it had
        h.serverBarrier(ws)
        assertEquals(emptyList<Pair<String, Long?>>(), attaches(h.framesUntilBarrier()))
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))
        assertFalse(h.client.memoryCensus().cursors.contains("s1"))
    }

    @Test
    fun aSnapshotNobodyAskedForIsNotKeptForAReleasedSession() {
        val all = ids(6)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        ws.send(snapshotFrame("s1", 9, turnState("s1", extraTurn = true)))
        h.serverBarrier(ws)
        val census = h.client.memoryCensus()
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))
        assertFalse(h.client.projections.value.containsKey("s1"))
        assertFalse("s1" in census.cursors)
        assertFalse("s1" in census.live)
        assertEquals(setOf("s3", "s4", "s5", "s6"), h.client.projectionTrees.value.keys)
        assertTrue(census.adapters <= 4)
        // Opened later it is a full attach (no cursor was left by the stray frame).
        h.now.addAndGet(1)
        h.client.attach("s1")
        assertEquals(listOf("s1" to null), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun nothingOfAReleasedSessionIsLeftBehind() {
        val all = ids(6)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        val census = h.client.memoryCensus()
        for (gone in listOf("s1", "s2")) {
            assertFalse("$gone subscribed", gone in census.subscribed)
            assertFalse("$gone attached", gone in census.attached)
            assertFalse("$gone cursor", gone in census.cursors)
            assertFalse("$gone live", gone in census.live)
            assertFalse("$gone live flow", gone in h.client.liveSessions.value)
        }
        assertEquals(4, census.adapters)
    }

    @Test
    fun aMemoryTrimKeepsOnlyTheOpenSession() {
        val all = ids(4)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        assertEquals(4, h.client.projectionTrees.value.size)
        h.client.trimMemory(5) // TRIM_MEMORY_RUNNING_MODERATE: not yet
        assertEquals(4, h.client.projectionTrees.value.size)
        h.client.trimMemory(10) // TRIM_MEMORY_RUNNING_LOW
        assertEquals(setOf("s4"), h.client.projectionTrees.value.keys)
        assertEquals(setOf("s4"), h.client.projections.value.keys)
        assertEquals(1, h.client.memoryCensus().adapters)
        assertEquals(setOf("s4"), h.client.memoryCensus().cursors)
        // And a released one opens again, full.
        h.now.addAndGet(1)
        h.client.attach("s2")
        assertEquals(listOf("s2" to null), attaches(h.framesUntilBarrier()))
    }

    @Test
    fun aSignOutClearsTheAdaptersToo() {
        val all = ids(3)
        val ws = connected(all, deviceToken = "tthr_device")
        all.forEach { open(ws, it) }
        assertEquals(3, h.client.memoryCensus().adapters)
        runBlocking { h.client.logout() }
        val census = h.client.memoryCensus()
        assertEquals(0, census.trees)
        assertEquals(0, census.adapters)
        assertTrue(census.subscribed.isEmpty())
    }

    @Test
    fun theFramesTheSidebarReadsStillUpdateForAReleasedSession() {
        val all = ids(6)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))
        ws.send("""{"type":"session","session":${sessionJson("s1", status = "running", updatedAt = 99)}}""")
        ws.send("""{"type":"seen","historyId":"h-s1","seenAt":77}""")
        h.await(h.client.sessions) { list -> list.any { it.id == "s1" && it.status == "running" && it.updatedAt == 99L } }
        h.await(h.client.remoteSeen) { it["h-s1"] == 77L }
        // An event for it moves nothing in memory and breaks nothing.
        ws.send(turnStartedEvent("s1", "t2", 2))
        h.serverBarrier(ws)
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))
        assertEquals("running", h.client.sessions.value.single { it.id == "s1" }.status)
    }

    @Test
    fun reopeningAReleasedSessionOfflineShowsTheLoadingStateThenTheTranscriptAfterReconnect() {
        val all = ids(6)
        val ws = connected(all)
        open(ws, "s1", state = turnState("s1", extraTurn = true))
        val reference = h.client.projectionTrees.value.getValue("s1")
        all.drop(1).forEach { open(ws, it) }
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.client.attach("s1")
        // Offline and nothing saved: no projection (the screen's loading state), no crash.
        assertNull(h.client.projectionTrees.value["s1"])
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next, readyListing(*all.toTypedArray()))
        val frames = attaches(h.framesUntilBarrier())
        assertTrue("s1 attached in full: $frames", frames.contains("s1" to null))
        next.send(snapshotFrame("s1", 1, JsCodec.stringify(reference)))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        assertEquals(reference, h.client.projectionTrees.value.getValue("s1"))
    }

    @Test
    fun pendingInputInAReleasedSessionStillReconcilesAndRedeliversWithoutBringingItsProjectionBack() {
        val all = ids(6)
        val ws = connected(all)
        open(ws, "s1")
        h.client.send("s1", "typed before release")
        val sent = h.expectFrame("send")
        val key = sent["idempotencyKey"]!!.jsonPrimitive.content
        all.drop(1).forEach { open(ws, it) }
        assertFalse(h.client.projectionTrees.value.containsKey("s1"))

        val ws2 = reconnect(ws, readyListing(*all.toTypedArray()))
        val frames = h.framesUntilBarrier()
        // The session holding input is attached in full (its redelivery waits for a snapshot with state).
        assertTrue(attaches(frames).contains("s1" to null))
        // The snapshot lacks the key: it was not accepted, so it is redelivered, once, same key.
        ws2.send(snapshotFrame("s1", 3, turnState("s1")))
        h.serverBarrier(ws2)
        val resent = h.framesUntilBarrier().filter { it.type() == "send" }
        assertEquals(listOf(key), resent.map { it["idempotencyKey"]!!.jsonPrimitive.content })
        // The reconcile used the frame's state; the tree itself was not kept.
        assertNull(h.client.projectionTrees.value["s1"])
        assertNotNull(h.client.projectionTrees.value["s6"])
    }

    /** The most trees the client published at once since [watchTrees] began. */
    private class TreePeak(val job: kotlinx.coroutines.Job, private val peak: java.util.concurrent.atomic.AtomicInteger) {
        fun max() = peak.get()
        fun stop() = job.cancel()
    }

    /** Sees EVERY published map (an unconfined collector runs inside each update), so the peak is exact. */
    private fun watchTrees(): TreePeak {
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            h.client.projectionTrees.collect { peak.accumulateAndGet(it.size) { a, b -> maxOf(a, b) } }
        }
        return TreePeak(job, peak)
    }

    /** Backgrounds past the grace, then comes back: the socket is replaced and the ready re-attaches. */
    private fun backgroundPastTheGraceAndResume(readyIds: List<String>): WebSocket {
        h.client.setAppForeground(false)
        h.now.addAndGet(ConnectionTimings.BACKGROUND_GRACE_MS + 1)
        h.enqueueConnect()
        h.client.setAppForeground(true)
        val next = h.nextSocket()
        h.handshake(next, readyListing(*readyIds.toTypedArray()))
        return next
    }

    @Test
    fun aResumeAfterTheBackgroundGraceReattachesTheRetainedSetAndNeverHoldsMoreThanOneTreeOverIt() {
        val all = ids(20)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        val retained = setOf("s17", "s18", "s19", "s20")
        assertEquals(retained, h.client.projectionTrees.value.keys)
        val watch = watchTrees()

        val next = backgroundPastTheGraceAndResume(all)
        // The open chat first; the others after its snapshot. Each answer is a FULL state (a long absence).
        val attached = attaches(h.framesUntilBarrier()).map { it.first }.toMutableList()
        assertEquals(listOf("s20"), attached)
        next.send(snapshotFrame("s20", 5, turnState("s20", chars = 2_000)))
        h.serverBarrier(next)
        attached += attaches(h.framesUntilBarrier()).map { it.first }
        for (id in attached.filter { it != "s20" }) next.send(snapshotFrame(id, 5, turnState(id, chars = 2_000)))
        h.serverBarrier(next)
        watch.stop()

        assertEquals("the re-attach set is the retained set", retained, attached.toSet())
        assertEquals(retained, h.client.projectionTrees.value.keys)
        assertTrue("peak ${watch.max()} trees", watch.max() <= retained.size + 1)
        assertTrue(h.client.memoryCensus().adapters <= retained.size + 1)
    }

    @Test
    fun aResumeAfterTheMemoryWasTrimmedReattachesTheOpenSessionAlone() {
        val all = ids(20)
        val ws = connected(all)
        all.forEach { open(ws, it) }
        // The system trims a hidden app (UI_HIDDEN) long before the grace ends.
        h.client.trimMemory(20)
        assertEquals(setOf("s20"), h.client.projectionTrees.value.keys)
        val watch = watchTrees()

        val next = backgroundPastTheGraceAndResume(all)
        assertEquals(listOf("s20"), attaches(h.framesUntilBarrier()).map { it.first })
        next.send(snapshotFrame("s20", 5, turnState("s20", chars = 2_000)))
        h.serverBarrier(next)
        assertEquals("nothing else is attached", emptyList<Pair<String, Long?>>(), attaches(h.framesUntilBarrier()))
        watch.stop()
        assertEquals(setOf("s20"), h.client.projectionTrees.value.keys)
        assertTrue("peak ${watch.max()} trees", watch.max() <= 2)
    }
}
