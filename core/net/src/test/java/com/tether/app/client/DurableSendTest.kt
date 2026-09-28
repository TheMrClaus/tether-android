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
 * The pending store's disk, one slot per server origin: what a process leaves
 * behind is exactly what the last COMPLETED write put there. [tear] makes every
 * later write die before it lands (the process is killed mid-write) — the
 * previous payload stays, whole. [disk], [written] and [history] are the slot of
 * [homeUrl]'s origin (the server these tests start configured for).
 */
class DiskSettings(private val inner: InMemorySettings, homeUrl: String) : SettingsStore by inner {
    val home: String = serverOrigin(homeUrl)!!

    /** Runs before a slot is read / written (a test can hold the call there). */
    @Volatile var beforeRead: (suspend (origin: String) -> Unit)? = null
    @Volatile var beforeWrite: (suspend (origin: String) -> Unit)? = null

    /** Every slot by origin. */
    val slots = java.util.concurrent.ConcurrentHashMap<String, String>()

    var disk: String?
        get() = slots[home]
        set(value) {
            if (value == null) slots.remove(home) else slots[home] = value
        }

    @Volatile var tear = false
    private val writes = MutableStateFlow<String?>(null)

    /** The newest completed write to the home slot (a flow to await on — no sleeps). */
    val written: StateFlow<String?> = writes

    /** Every completed write to the home slot, in order: what it held at each moment. */
    val history = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Every completed write to any slot, in order, with its origin. */
    val allWrites = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    /** Like the real store's: every server's slot goes with the configuration. */
    override suspend fun clear() {
        inner.clear()
        slots.clear()
    }

    override suspend fun readPendingInput(origin: String): String? {
        PendingSlots.keyFor(origin) // the client only ever names canonical origins
        beforeRead?.invoke(origin)
        return slots[origin]
    }

    override suspend fun pendingInputOrigins(): Set<String> = slots.keys.toSet()

    override suspend fun removePendingInput(origin: String) {
        PendingSlots.keyFor(origin)
        if (tear) throw IOException("killed mid-write")
        slots.remove(origin)
        allWrites += origin to "<removed>"
    }

    override suspend fun writePendingInput(origin: String, raw: String) {
        PendingSlots.keyFor(origin)
        beforeWrite?.invoke(origin)
        if (tear) throw IOException("killed mid-write")
        slots[origin] = raw
        allWrites += origin to raw
        if (origin == home) {
            history += raw
            writes.value = raw
        }
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

    private fun disk(ipv6: Boolean = false): DiskSettings {
        if (!::settings.isInitialized) {
            if (ipv6) h.server.start(java.net.InetAddress.getByName("::1"), 0) else h.server.start()
            // An IPv6 literal URL, as a user types it (url() would use a host name).
            val url = if (ipv6) "http://[::1]:${h.server.port}" else h.server.url("/").toString().trimEnd('/')
            settings = DiskSettings(InMemorySettings(url, initialCookie = "cookie"), url)
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

    /**
     * ta-s8q round 2: a server on an IPv6 literal (`http://[::1]:port`). Its
     * origin keeps the brackets, so its slot is written and read back; a
     * turn survives process death exactly as on any other server. The WS
     * upgrade's Origin header keeps them too (the server parses it as a URL).
     */
    @Test
    fun anIpv6ServerKeepsDurableSendAcrossProcessDeath() {
        disk(ipv6 = true)
        val (first, ws) = firstProcess()
        first.send("s1", "sent to an IPv6 server")
        val sent = key(h.expectFrame("send"))
        persistedMatches { s -> s.records.any { it.key == sent && it.tries == 1 } }
        assertEquals("http://[::1]:${h.server.port}", settings.home)
        kill(first, ws)

        val second = process()
        val ws2 = startConnected(second)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        ws2.send(snapshotFrame("s1", 1, turnState("s1")))
        h.serverBarrier(ws2)
        assertEquals(listOf(sent), h.framesUntilBarrier().map { key(it) })

        val upgrades = generateSequence { h.server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .filter { it.path == "/ws" }.toList()
        assertTrue(upgrades.isNotEmpty())
        upgrades.forEach { assertEquals("http://[::1]:${h.server.port}", it.getHeader("Origin")) }
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

    @Test
    fun oneLogicalChangeIsOneCompleteWriteNeverAnIntermediateOne() {
        // Whatever the disk holds at ANY moment must be a complete store: a process
        // killed between two writes of one change must not find an empty or partial
        // one. k-held is pending throughout (its session is never snapshotted), so
        // every write must carry it; the fresh key appears once and, once acked,
        // never comes back.
        seedDisk("k-held" to 1)
        val client = process()
        val ws = startConnected(client)
        client.attach("s2")
        h.framesUntilBarrier()
        ws.send(snapshotFrame("s2", 1, turnState("s2")))
        h.await(client.projections) { it.containsKey("s2") }
        client.send("s2", "fresh")
        val fresh = key(h.expectFrame("send"))
        persistedMatches { s -> s.records.any { it.key == fresh && it.tries == 1 } }
        ws.send(turnStartedEvent("s2", "t1", 2, fresh))
        persistedMatches { s -> s.records.none { it.key == fresh } }

        val states = settings.history.map { PendingInput.fromPersisted(it).records.map { r -> r.key } }
        assertTrue("states=$states", states.size >= 3)
        assertTrue("every write is a complete store: $states", states.all { "k-held" in it })
        val presence = states.map { fresh in it }
        val firstIn = presence.indexOf(true)
        val lastIn = presence.lastIndexOf(true)
        assertTrue("fresh never persisted: $states", firstIn >= 0)
        assertTrue("an acked key reappeared: $states", presence.subList(firstIn, lastIn + 1).all { it })
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

    /** [turnState] with a v130 (S13.1-C) `removedQueueIds` list. */
    private fun stateWithRemovedQueueIds(sessionId: String, vararg removed: String): String =
        turnState(sessionId).trimEnd().removeSuffix("}") +
            ""","removedQueueIds":[${removed.joinToString(",") { "\"$it\"" }}]}"""

    /**
     * ta-srn (T13.3b, SYNC_DESIGN §5.5 / §11 "with S13.1-C"): a queue-add is accepted, its ack is
     * lost, and it is removed on another device while this one is away. The reconnect snapshot
     * no longer queues it, but its `removedQueueIds` proves it was accepted: no resend.
     */
    @Test
    fun aQueueAddWithdrawnElsewhereIsNotResentWhenTheSnapshotCarriesRemovedQueueIds() {
        val (client, ws) = connectedProcess()
        client.queueAdd("s1", "withdrawn on another device")
        val queued = key(h.expectFrame("queue-add"), "queueId")
        // The ack (queued_message_added) never arrives; the link drops.
        val ws2 = reconnectAfterDrop(ws, h.scheduler)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        ws2.send(snapshotFrame("s1", 3, stateWithRemovedQueueIds("s1", "other-device-q", queued)))
        h.serverBarrier(ws2)
        assertTrue("re-sent a withdrawn queue item", h.framesUntilBarrier().isEmpty())
        persistedMatches { it.records.isEmpty() }
    }

    /**
     * The documented residual on a pre-v130 server (no `removedQueueIds`): the snapshot cannot tell
     * "withdrawn elsewhere" from "never accepted", so the record is re-sent — once, same key.
     */
    @Test
    fun withoutRemovedQueueIdsAWithdrawnQueueAddIsTheDocumentedResidual() {
        val (client, ws) = connectedProcess()
        client.queueAdd("s1", "withdrawn on another device")
        val queued = key(h.expectFrame("queue-add"), "queueId")
        val ws2 = reconnectAfterDrop(ws, h.scheduler)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        ws2.send(snapshotFrame("s1", 3, turnState("s1")))
        h.serverBarrier(ws2)
        assertEquals(listOf(queued), h.framesUntilBarrier().map { key(it, "queueId") })
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
        val client = process()
        val ws = startConnected(client)
        client.attach("s1")
        h.expectFrame("attach")
        // T6.3: real pending requests (a decision is refused for anything else).
        ws.send(snapshotFrame("s1", 5, consentStateJson()))
        h.await(client.liveSessions) { "s1" in it }
        assertEquals(ConsentResult.Sent, client.approval("s1", "r-choice", choiceId = "accept"))
        assertEquals(ConsentResult.Sent, client.answerQuestion("s1", "q1", mapOf("Which DB?" to "Postgres")))
        assertEquals(listOf("approval", "question"), h.framesUntilBarrier().map { it.type() })

        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        // Filed while the link is down: refused, not stored for later.
        assertEquals(ConsentResult.NotConnected, client.approval("s1", "r-plain", decision = "deny"))
        h.enqueueConnect()
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2)
        // The same requests are still pending in the new snapshot: nothing is sent again.
        ws2.send(snapshotFrame("s1", 5, consentStateJson()))
        h.serverBarrier(ws2)
        assertEquals(listOf("attach"), h.framesUntilBarrier().map { it.type() })
        assertTrue("approvals/questions never enter the durable store", persisted().records.isEmpty())
    }
}
