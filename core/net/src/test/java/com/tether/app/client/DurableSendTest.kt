package com.tether.app.client

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending store's disk: what a process leaves behind is exactly what the
 * last COMPLETED write put there. [tear] makes every later write die before it
 * lands (the process is killed mid-write) — the previous payload stays, whole.
 */
class DiskSettings(private val inner: InMemorySettings) : SettingsStore by inner {
    @Volatile var disk: String? = null
    @Volatile var tear = false
    private val writes = MutableStateFlow<String?>(null)

    /** Every completed write, newest last (as a flow to await on — no sleeps). */
    val written: StateFlow<String?> = writes

    override suspend fun readPendingInput(): String? = disk

    override suspend fun writePendingInput(raw: String) {
        if (tear) throw IOException("killed mid-write")
        disk = raw
        writes.value = raw
    }
}

/**
 * T1.3 durable send, end to end on a MockWebServer: the record survives process
 * death, a cold start restores it before anything drains, and PLAN §0.3 holds —
 * a turn is only ever redelivered under its SAME idempotencyKey, only after its
 * session's state-bearing snapshot on the current socket, never when the journal
 * (or a live ack) shows it started, whatever its outcome. Approvals and answers
 * are never replayed. A "process" is a RealTetherClient on its own scope and
 * scheduler over the same [DiskSettings]; death = its scope cancelled + its socket gone.
 */
class DurableSendTest {

    private val h = ConnectionHarness()
    private lateinit var settings: DiskSettings
    private val processes = LinkedHashMap<RealTetherClient, CoroutineScope>()

    @After
    fun tearDown() {
        processes.values.forEach { it.cancel() }
        h.close()
    }

    private fun disk(): DiskSettings {
        if (!::settings.isInitialized) {
            h.server.start()
            settings = DiskSettings(InMemorySettings(h.server.url("/").toString().trimEnd('/'), initialCookie = "cookie"))
        }
        return settings
    }

    /** A new app process over the same disk. The last one started is [ConnectionHarness.client]. */
    private fun process(scheduler: ManualScheduler = h.scheduler): RealTetherClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = RealTetherClient(
            settings = disk(),
            httpClient = OkHttpClient(),
            scope = scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = scheduler,
        )
        processes[client] = scope
        h.client = client
        return client
    }

    private fun startConnected(client: RealTetherClient): WebSocket {
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    /** The first process: connected, s1 attached and snapshotted (empty journal). */
    private fun firstProcess(): Pair<RealTetherClient, WebSocket> {
        val client = process(scheduler = ManualScheduler())
        val ws = startConnected(client)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, turnState("s1")))
        h.await(client.projections) { it.containsKey("s1") }
        return client to ws
    }

    /** Process death: nothing of it runs any more and its socket is gone. */
    private fun kill(client: RealTetherClient, ws: WebSocket) {
        processes.getValue(client).cancel()
        ws.close(1001, null)
    }

    private fun turnState(sessionId: String, vararg turns: Triple<String, String?, String>): String {
        val byId = turns.joinToString(",") { (id, key, status) ->
            val k = if (key != null) ""","idempotencyKey":"$key"""" else ""
            """"$id":{"turnId":"$id","status":"$status"$k,"blocks":[],"blocksById":{}}"""
        }
        val order = turns.joinToString(",") { "\"${it.first}\"" }
        return """{"tetherSessionId":"$sessionId","provider":"claude","cwd":"/w","status":"ready",
                   "turnOrder":[$order],"turnsById":{$byId},"activeTurnId":null,"queuedMessages":[]}"""
    }

    private fun key(frame: JsonObject, field: String = "idempotencyKey") = frame[field]!!.jsonPrimitive.content

    private fun persistedMatches(predicate: (PendingStore) -> Boolean) =
        h.await(settings.written) { it != null && predicate(PendingInput.fromPersisted(it)) }

    private fun persisted(): PendingStore = PendingInput.fromPersisted(settings.disk)

    private fun seedDisk(vararg records: Pair<String, Int>) {
        var store = PendingInput.emptyStore()
        for ((key, tries) in records) {
            store = PendingInput.addRecord(store, key, PendingInput.KIND_SEND, "s1", "text $key", h.now.get()).store
            if (tries > 0) store = PendingInput.resetInFlight(PendingInput.markSent(store, listOf(key), h.now.get()))
        }
        disk().disk = PendingInput.toPersistable(store)
    }

    // ------------------------------------------------------------------
    // Process death
    // ------------------------------------------------------------------

    @Test
    fun processDeathThenColdStartRedeliversExactlyOnceUnderTheSameKeyAfterTheSnapshot() {
        val (first, ws) = firstProcess()
        first.send("s1", "lost with the process")
        val sent = key(h.expectFrame("send"))
        persistedMatches { s -> s.records.any { it.key == sent && it.tries == 1 } }
        kill(first, ws)

        val second = process()
        val ws2 = startConnected(second)
        // Restored before the first drain; the session with pending input is
        // re-attached, and NOTHING is re-sent before its snapshot.
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })

        // The journal has no such turn: exactly one redelivery, the SAME key.
        ws2.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws2)
        val resent = h.framesUntilBarrier()
        assertEquals(listOf("send"), resent.map { it.type() })
        assertEquals(sent, key(resent.single()))
        assertEquals("lost with the process", resent.single()["text"]!!.jsonPrimitive.content)

        // A second state-bearing snapshot on the same socket: in flight, not re-sent.
        ws2.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws2)
        assertTrue(h.framesUntilBarrier().isEmpty())

        // The live ack clears it for good — on disk too.
        ws2.send(turnStartedEvent("s1", "t1", 2, sent))
        persistedMatches { it.records.isEmpty() }
    }

    @Test
    fun coldStartNeverResendsATurnTheJournalShowsStartedWhateverItsOutcome() {
        for (outcome in listOf("outcome_unknown", "interrupted", "completed")) {
            seedDisk("k-$outcome" to 1)
            val client = process()
            val ws = startConnected(client)
            assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
            ws.send(snapshotFrame("s1", 3, turnState("s1", Triple("t1", "k-$outcome", outcome))))
            h.serverBarrier(ws)
            assertTrue("a started turn ($outcome) is never re-sent", h.framesUntilBarrier().isEmpty())
            persistedMatches { it.records.isEmpty() }
            kill(client, ws)
        }
    }

    @Test
    fun aTriesBumpThatNeverReachedDiskStillWaitsForTheSnapshot() {
        // The previous process put the frame on the wire and died before the
        // markSent write landed: disk says tries 0. It may be journaled already.
        seedDisk("k-maybe-sent" to 0)
        val client = process()
        val ws = startConnected(client)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        // ...and it WAS: the snapshot proves it, so it is never sent again.
        ws.send(snapshotFrame("s1", 2, turnState("s1", Triple("t1", "k-maybe-sent", "running"))))
        h.serverBarrier(ws)
        assertTrue(h.framesUntilBarrier().isEmpty())
    }

    @Test
    fun restartDuringATornWriteNeitherDuplicatesNorLosesAnEntry() {
        val (first, ws) = firstProcess()
        first.send("s1", "accepted")
        val accepted = key(h.expectFrame("send"))
        first.send("s1", "never reached the server")
        val lost = key(h.expectFrame("send"))
        persistedMatches { s -> s.records.map { it.key } == listOf(accepted, lost) && s.records.all { it.tries == 1 } }

        // The ack's write is torn by the process dying: disk keeps the previous store, whole.
        settings.tear = true
        ws.send(turnStartedEvent("s1", "t1", 2, accepted))
        h.serverBarrier(ws)
        kill(first, ws)
        assertEquals(listOf(accepted, lost), persisted().records.map { it.key })

        settings.tear = false
        val second = process()
        val ws2 = startConnected(second)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        ws2.send(snapshotFrame("s1", 2, turnState("s1", Triple("t1", accepted, "running"))))
        h.serverBarrier(ws2)
        // The accepted turn is not duplicated; the lost one is delivered, once, same key.
        val resent = h.framesUntilBarrier()
        assertEquals(listOf(lost), resent.map { key(it) })
        persistedMatches { s -> s.records.map { it.key } == listOf(lost) }
    }

    @Test
    fun inputFiledBeforeStartIsMergedWithTheRestoredStoreNeverOverwritingIt() {
        seedDisk("k-restored" to 1)
        h.now.addAndGet(1_000)
        val client = process()
        client.send("s1", "typed while starting")
        assertNull("nothing is written before the persisted store is restored", settings.written.value)

        val ws = startConnected(client)
        // The fresh key goes straight out (it cannot exist server-side); the restored
        // one waits for the snapshot.
        val first = h.framesUntilBarrier()
        assertEquals(listOf("attach", "send"), first.map { it.type() })
        val fresh = key(first[1])
        assertNotEquals("k-restored", fresh)
        persistedMatches { s -> s.records.map { it.key } == listOf("k-restored", fresh) }

        ws.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws)
        assertEquals(listOf("k-restored"), h.framesUntilBarrier().map { key(it) })
    }

    // ------------------------------------------------------------------
    // Same process, across reconnects
    // ------------------------------------------------------------------

    private fun reconnectAfterDrop(ws: WebSocket, scheduler: ManualScheduler): WebSocket {
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        return next
    }

    private fun connectedProcess(): Pair<RealTetherClient, WebSocket> {
        val client = process()
        val ws = startConnected(client)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, turnState("s1")))
        h.await(client.projections) { it.containsKey("s1") }
        return client to ws
    }

    @Test
    fun aLiveAckedTurnIsNeverResentEvenIfALaterSnapshotLacksIt() {
        val (client, ws) = connectedProcess()
        client.send("s1", "acked live")
        val sent = key(h.expectFrame("send"))
        ws.send(turnStartedEvent("s1", "t1", 2, sent))
        persistedMatches { it.records.isEmpty() }

        val ws2 = reconnectAfterDrop(ws, h.scheduler)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        // e.g. a bounded snapshot that trimmed the turn away: still never re-sent.
        ws2.send(snapshotFrame("s1", 2, turnState("s1"), trimmedBefore = 2))
        h.serverBarrier(ws2)
        assertTrue(h.framesUntilBarrier().isEmpty())
    }

    @Test
    fun queueAddIsAckedByItsQueueIdOrByTheTurnItFlushedInto() {
        val (client, ws) = connectedProcess()
        client.queueAdd("s1", "queued while busy")
        val queued = key(h.expectFrame("queue-add"), "queueId")
        client.queueAdd("s1", "flushed straight into a turn")
        val flushed = key(h.expectFrame("queue-add"), "queueId")
        ws.send(
            """{"type":"event","sessionId":"s1","event":{"type":"queued_message_added","turnId":null,
               "queueId":"$queued","text":"queued while busy","seq":2,"ts":2}}""",
        )
        ws.send(turnStartedEvent("s1", "t1", 3, flushed))
        persistedMatches { it.records.isEmpty() }

        val ws2 = reconnectAfterDrop(ws, h.scheduler)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        ws2.send(snapshotFrame("s1", 3, turnState("s1")))
        h.serverBarrier(ws2)
        assertTrue(h.framesUntilBarrier().isEmpty())
    }

    @Test
    fun aStatelessSnapshotDoesNotAuthoriseRedelivery() {
        val (client, ws) = connectedProcess()
        client.send("s1", "unacked")
        val sent = key(h.expectFrame("send"))
        val ws2 = reconnectAfterDrop(ws, h.scheduler)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        // Cursor at head (v115): no state, so no proof of what was accepted.
        ws2.send(snapshotFrame("s1", 1, state = null))
        h.serverBarrier(ws2)
        assertTrue(h.framesUntilBarrier().isEmpty())
        ws2.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws2)
        assertEquals(listOf(sent), h.framesUntilBarrier().map { key(it) })
    }

    @Test
    fun approvalsAndAnswersAreNeverReplayed() {
        val (client, ws) = connectedProcess()
        client.approval("s1", "req-1", choiceId = "allow")
        client.answerQuestion("s1", "q-1", mapOf("a" to "b"))
        assertEquals(listOf("approval", "question"), h.framesUntilBarrier().map { it.type() })

        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        // Filed while the link is down: dropped, not stored for later.
        client.approval("s1", "req-2", decision = "deny")
        h.enqueueConnect()
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2)
        ws2.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws2)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        assertTrue("approvals/questions never enter the durable store", persisted().records.isEmpty())
    }
}
