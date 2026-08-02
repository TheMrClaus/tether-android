package com.tether.app.client

import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.fold
import com.tether.app.protocol.reduce.freshState
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInputTest {

    private fun storeWith(vararg records: Triple<String, String, Int>): PendingStore {
        // Triple(key, sessionId, tries)
        var store = PendingInput.emptyStore()
        for ((key, sessionId, tries) in records) {
            store = PendingInput.addRecord(store, key, PendingInput.KIND_SEND, sessionId, "text-$key", 1_000).store
            if (tries > 0) store = PendingInput.markSent(store, listOf(key), 2_000)
        }
        return store
    }

    @Test
    fun freshlyMintedKeysAreSendableWithoutReconciliation() {
        val store = storeWith(Triple("k1", "s1", 0))
        val sendable = PendingInput.sendableRecords(store, emptySet())
        assertEquals(listOf("k1"), sendable.map { it.key })
    }

    @Test
    fun transmittedKeyIsNotResentUntilItsSessionIsReconciled() {
        var store = storeWith(Triple("k1", "s1", 0))
        store = PendingInput.markSent(store, listOf("k1"), 2_000)
        store = PendingInput.resetInFlight(store) // reconnect: due again, but...
        // ...an already-tried record may NOT go out before a snapshot reconciled s1.
        assertTrue(PendingInput.sendableRecords(store, emptySet()).isEmpty())
        // Once the session is reconciled on this connection, it is sendable again.
        assertEquals(
            listOf("k1"),
            PendingInput.sendableRecords(store, setOf("s1")).map { it.key },
        )
    }

    @Test
    fun snapshotReconciliationClearsOnlyProvenAcceptedKeys() {
        var store = storeWith(Triple("k-accepted", "s1", 1), Triple("k-lost", "s1", 1), Triple("k-other", "s2", 1))
        store = PendingInput.resetInFlight(store)

        // Snapshot proves k-accepted landed (as a turn idempotencyKey) and a
        // queued message q-9 exists; k-lost is absent -> stays pending.
        val projection = fold(
            freshState(),
            ev("turn_started", turnId = "t1", seq = 1, ts = 1_000) { put("idempotencyKey", "k-accepted") },
            evNullTurn("queued_message_added", seq = 2, ts = 1_100) {
                put("queueId", "q-9")
                put("text", "queued")
            },
        )
        val result = PendingInput.reconcileWithSnapshot(store, "s1", projection)
        assertEquals(listOf("k-accepted"), result.cleared)
        assertEquals(listOf("k-lost", "k-other"), result.store.records.map { it.key })
        // k-lost is now redeliverable (session reconciled); k-other is NOT (s2 unproven).
        assertEquals(
            listOf("k-lost"),
            PendingInput.sendableRecords(result.store, setOf("s1")).map { it.key },
        )
    }

    @Test
    fun liveAckMatchesOnKeyAloneNeverKind() {
        var store = PendingInput.addRecord(
            PendingInput.emptyStore(), "q-1", PendingInput.KIND_QUEUE, "s1", "queued text", 1_000,
        ).store
        // A queue-add flushed into a turn is acked by turn_started's idempotencyKey.
        val acked = PendingInput.ackKey(store, "q-1")
        assertTrue(acked.removed)
        assertTrue(acked.store.records.isEmpty())
        // An acked key is gone: nothing to re-send ever.
        assertTrue(PendingInput.sendableRecords(acked.store, setOf("s1")).isEmpty())
        // Unknown key ack is a no-op.
        val noop = PendingInput.ackKey(acked.store, "missing")
        assertFalse(noop.removed)
    }

    @Test
    fun expiryBoundsTriesAndAge() {
        // MAX_TRIES exhausted + past the unacked window -> abandoned, surfaced.
        var store = storeWith(Triple("k1", "s1", 0))
        repeat(PendingInput.MAX_TRIES) { store = PendingInput.markSent(store, listOf("k1"), 10_000) }
        assertEquals(PendingInput.MAX_TRIES, store.records.single().tries)
        val early = PendingInput.expireRecords(store, 10_000 + PendingInput.UNACKED_CLOSE_MS - 1)
        assertTrue(early.unsent.isEmpty()) // final attempt gets its full window
        val expired = PendingInput.expireRecords(store, 10_000 + PendingInput.UNACKED_CLOSE_MS)
        assertEquals("k1", expired.unsent.single().key)
        assertEquals("text-k1", expired.unsent.single().text) // original text surfaced
        assertTrue(expired.store.records.isEmpty())

        // Wall-clock age alone also expires (10 min).
        val aged = PendingInput.expireRecords(storeWith(Triple("k2", "s1", 0)), 1_000 + PendingInput.MAX_AGE_MS)
        assertEquals("k2", aged.unsent.single().key)

        // Exhausted records are refused by the drain even before expiry.
        assertTrue(PendingInput.sendableRecords(PendingInput.resetInFlight(store), setOf("s1")).isEmpty())
    }

    @Test
    fun recordCapEvictsOldestFirstAndSurfacesThem() {
        var store = PendingInput.emptyStore()
        for (i in 1..PendingInput.MAX_RECORDS) {
            store = PendingInput.addRecord(store, "k$i", PendingInput.KIND_SEND, "s1", "t$i", i.toLong()).store
        }
        val result = PendingInput.addRecord(store, "k-next", PendingInput.KIND_SEND, "s1", "t", 999_999)
        assertEquals(PendingInput.MAX_RECORDS, result.store.records.size)
        assertEquals("k1", result.evicted.single().key)
    }

    @Test
    fun editAndDiscardOperateOnPendingRecords() {
        var store = PendingInput.addRecord(
            PendingInput.emptyStore(), "q-1", PendingInput.KIND_QUEUE, "s1", "original", 1_000,
        ).store
        store = PendingInput.editText(store, "q-1", "edited")
        assertEquals("edited", store.records.single().text)
        // Editing an unknown key returns the same instance.
        assertSame(store, PendingInput.editText(store, "missing", "x"))

        val discarded = PendingInput.discardKey(store, "q-1")
        assertTrue(discarded.removed)
        assertTrue(discarded.store.records.isEmpty())
    }

    @Test
    fun persistRoundTripResetsInFlight() {
        var store = storeWith(Triple("k1", "s1", 1), Triple("k2", "s2", 0))
        val raw = PendingInput.toPersisted(store)
        val restored = PendingInput.fromPersisted(raw)
        assertEquals(listOf("k1", "k2"), restored.records.map { it.key })
        assertTrue(restored.records.all { it.sentAt == 0L }) // restored not-in-flight
        assertEquals(1, restored.records.first { it.key == "k1" }.tries) // tries survive
        // Corrupt payloads restore to empty.
        assertTrue(PendingInput.fromPersisted("{broken").records.isEmpty())
        assertTrue(PendingInput.fromPersisted(null).records.isEmpty())
    }

    @Test
    fun fifoOrderIsPreservedForDrain() {
        var store = PendingInput.emptyStore()
        store = PendingInput.addRecord(store, "first", PendingInput.KIND_SEND, "s1", "a", 1_000).store
        store = PendingInput.addRecord(store, "second", PendingInput.KIND_SEND, "s1", "b", 2_000).store
        assertEquals(listOf("first", "second"), PendingInput.dueRecords(store).map { it.key })
    }

    @Test
    fun forgetSessionDropsItsRecordsOnly() {
        val store = storeWith(Triple("k1", "s1", 0), Triple("k2", "s2", 0))
        val next = PendingInput.forgetSession(store, "s1")
        assertEquals(listOf("k2"), next.records.map { it.key })
        assertSame(next, PendingInput.forgetSession(next, "s1"))
    }
}
