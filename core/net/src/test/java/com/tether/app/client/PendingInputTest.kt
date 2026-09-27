package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.conformance.CanonicalJson
import com.tether.app.protocol.conformance.CanonicalJson.TaggedSet
import com.tether.app.protocol.conformance.CanonicalJson.Undefined
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.str
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.tether.app.protocol.helpers.PendingInput as Web

/**
 * T1.3: the socket's PendingInput is the helper port of lib/pending-input.mjs.
 * Proven two ways: every recorded case of parity-corpus/helpers/pending-input.json
 * whose arguments the typed API can express replays through the typed facade to
 * the web's exact result, and the facade's constants ARE the port's (no copies).
 */
class PendingInputTest {

    // ------------------------------------------------------------------
    // Corpus parity through the typed facade
    // ------------------------------------------------------------------

    private class Unexpressible(reason: String) : Exception(reason)

    private fun corpus(): JsObj =
        CanonicalJson.read(File(File(CanonicalJson.corpusDir, "helpers"), "pending-input.json")) as JsObj

    private fun store(v: Any?): PendingStore = PendingStore(v as? JsObj ?: throw Unexpressible("store is not an object"))

    private fun string(v: Any?): String = (v as? JsStr)?.value ?: throw Unexpressible("not a string: $v")

    private fun optString(v: Any?): String? = if (v === Undefined) null else string(v)

    private fun long(v: Any?): Long {
        val d = (v as? JsNum)?.value ?: throw Unexpressible("not a number: $v")
        if (d != Math.rint(d) || d.isInfinite()) throw Unexpressible("not integral: $d")
        return d.toLong()
    }

    private fun strings(v: Any?): List<String> = when (v) {
        null, Undefined -> emptyList()
        is JsArr -> v.map { string(it) }
        is TaggedSet -> v.values.map { string(it) }
        else -> throw Unexpressible("not a string collection: $v")
    }

    private fun tree(records: List<PendingRecord>) = JsArr.of(records.map { it.tree })

    private fun addRecordArgs(store: PendingStore, input: Any?, now: Long): Any {
        val obj = input as? JsObj ?: throw Unexpressible("input is not an object")
        // The socket never files a P2.4 @Agent mention; the facade has no mention parameter.
        if (obj.has("mention")) throw Unexpressible("mention")
        val attachments = (obj["attachments"] as? JsArr)?.map { a ->
            val at = a as JsObj
            Attachment(string(at["name"]), string(at["mediaType"]), string(at["data"]))
        }
        val r = PendingInput.addRecord(
            store, string(obj["key"]), string(obj["kind"]), string(obj["sessionId"]), string(obj["text"]), now,
            attachments,
        )
        return JsObj.of("store" to r.store.tree, "evicted" to tree(r.evicted))
    }

    /** One corpus call through the TYPED facade, in the web's result shape. */
    private fun replay(fn: String, a: List<Any?>): Any? = when (fn) {
        "emptyStore" -> PendingInput.emptyStore().tree
        "acceptedKeys" -> PendingInput.acceptedKeys(a[0] as? JsValue)
        "addRecord" -> addRecordArgs(store(a[0]), a[1], long(a[2]))
        "ackKey" -> PendingInput.ackKey(store(a[0]), optString(a.getOrElse(1) { Undefined })).let {
            JsObj.of("store" to it.store.tree, "acked" to js(it.removed))
        }
        "discardKey" -> PendingInput.discardKey(store(a[0]), optString(a.getOrElse(1) { Undefined })).let {
            JsObj.of("store" to it.store.tree, "discarded" to js(it.removed))
        }
        "editText" -> PendingInput.editText(store(a[0]), string(a[1]), string(a[2])).tree
        "reconcileWithSnapshot" -> PendingInput.reconcileWithSnapshot(store(a[0]), string(a[1]), a[2] as? JsValue).let {
            JsObj.of("store" to it.store.tree, "cleared" to JsArr.of(it.cleared.map(::js)))
        }
        "dueRecords" -> tree(PendingInput.dueRecords(store(a[0]), optString(a.getOrElse(1) { Undefined })))
        "sendableRecords" -> tree(PendingInput.sendableRecords(store(a[0]), strings(a[1]).toSet()))
        "markSent" -> PendingInput.markSent(store(a[0]), strings(a[1]), long(a[2])).tree
        "resetInFlight" -> PendingInput.resetInFlight(store(a[0])).tree
        "oldestInFlightAge" -> js(PendingInput.oldestInFlightAge(store(a[0]), long(a[1])))
        "expireRecords" -> PendingInput.expireRecords(store(a[0]), long(a[1])).let {
            JsObj.of("store" to it.store.tree, "unsent" to tree(it.unsent))
        }
        "toPersistable" -> js(PendingInput.toPersistable(store(a[0]), strings(a.getOrNull(1))))
        "fromPersisted" -> PendingInput.fromPersisted(optString(a[0])).tree
        "clearedFromPersisted" -> PendingInput.clearedFromPersisted(optString(a[0]))
        "forgetSession" -> PendingInput.forgetSession(store(a[0]), string(a[1])).tree
        "mergeStores" -> PendingInput.mergeStores(store(a[0]), store(a[1]), strings(a.getOrNull(2))).tree
        // The composer's status rows are the UI's (T7.1), not the socket's.
        "describePending" -> throw Unexpressible("UI projection (T7.1)")
        else -> error("pending-input.json has a function the facade does not know: $fn")
    }

    @Test
    fun everyExpressibleCorpusCaseReplaysThroughTheTypedFacade() {
        val cases = corpus()["cases"] as JsArr
        var replayed = 0
        val skipped = mutableListOf<String>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw as JsObj
            val fn = case["fn"].str!!
            val args = (case["args"] as JsArr).map { CanonicalJson.decodeTagged(it) }
            val actual = try {
                replay(fn, args)
            } catch (e: Unexpressible) {
                skipped += "$fn#$index (${e.message})"
                continue
            }
            assertEquals(
                "pending-input $fn#$index",
                CanonicalJson.write(case["result"]!!),
                CanonicalJson.write(CanonicalJson.encodeTagged(actual)),
            )
            replayed++
        }
        println("PendingInputTest corpus: replayed=$replayed of ${cases.size}; skipped=$skipped")
        // Only what the typed API cannot express is skipped (a non-string key or raw,
        // a mention, the UI-only describePending) — never a gate/reconcile case.
        assertTrue("replayed=$replayed skipped=$skipped", replayed >= 45)
        assertTrue(skipped.toString(), skipped.none { it.startsWith("sendableRecords") || it.startsWith("reconcileWithSnapshot") })
    }

    @Test
    fun facadeConstantsAreThePortsAndMatchTheCorpus() {
        val constants = corpus()["constants"] as JsObj
        assertEquals(constants["UNACKED_CLOSE_MS"].num!!.toLong(), PendingInput.UNACKED_CLOSE_MS)
        assertEquals(constants["MAX_TOMBSTONES"].num!!.toInt(), PendingInput.MAX_TOMBSTONES)
        assertEquals(Web.UNACKED_CLOSE_MS.toLong(), ConnectionTimings.PING_TIMEOUT_MS)
        for ((name, value) in mapOf(
            "MAX_TRIES" to Web.MAX_TRIES, "MAX_AGE_MS" to Web.MAX_AGE_MS, "MAX_RECORDS" to Web.MAX_RECORDS,
            "MAX_PERSISTED_BYTES" to Web.MAX_PERSISTED_BYTES,
        )) {
            assertEquals(name, constants[name].num!!.toInt(), value)
        }
    }

    // ------------------------------------------------------------------
    // Limits (MAX_RECORDS, MAX_TRIES, MAX_AGE_MS, MAX_PERSISTED_BYTES)
    // ------------------------------------------------------------------

    private fun add(store: PendingStore, key: String, sessionId: String = "s1", now: Long = 1_000, text: String = "text-$key") =
        PendingInput.addRecord(store, key, PendingInput.KIND_SEND, sessionId, text, now)

    @Test
    fun theStoreCapEvictsOldestFirstAndSurfacesIt() {
        var store = PendingInput.emptyStore()
        for (i in 0 until Web.MAX_RECORDS) store = add(store, "k$i").store
        val result = add(store, "overflow")
        assertEquals(Web.MAX_RECORDS, result.store.records.size)
        assertEquals(listOf("k0"), result.evicted.map { it.key })
        assertEquals("k1", result.store.records.first().key)
        assertEquals("overflow", result.store.records.last().key)
    }

    @Test
    fun triesAndAgeBoundsAbandonAndReport() {
        var store = add(PendingInput.emptyStore(), "k").store
        repeat(Web.MAX_TRIES) { store = PendingInput.resetInFlight(PendingInput.markSent(store, listOf("k"), 2_000)) }
        // Exhausted: never sendable again, even when reconciled...
        assertTrue(PendingInput.sendableRecords(store, setOf("s1")).isEmpty())
        // ...and abandoned (surfaced) by the sweep.
        assertEquals(listOf("k"), PendingInput.expireRecords(store, 3_000).unsent.map { it.key })

        val young = add(PendingInput.emptyStore(), "y", now = 1_000).store
        assertTrue(PendingInput.expireRecords(young, 1_000L + Web.MAX_AGE_MS - 1).unsent.isEmpty())
        assertEquals(listOf("y"), PendingInput.expireRecords(young, 1_000L + Web.MAX_AGE_MS).unsent.map { it.key })
    }

    @Test
    fun persistedPayloadIsTheWebShapeAndBounded() {
        val store = add(add(PendingInput.emptyStore(), "old", text = "x".repeat(200_000)).store, "new", text = "y".repeat(100_000)).store
        val raw = PendingInput.toPersistable(store, listOf("gone"))
        assertTrue(raw.length <= Web.MAX_PERSISTED_BYTES)
        val parsed = JsCodec.parse(raw) as JsObj
        assertEquals(listOf("v", "records", "cleared"), parsed.keys.toList())
        assertEquals(2.0, parsed["v"].num)
        // Oldest dropped first to fit.
        assertEquals(listOf("new"), PendingInput.fromPersisted(raw).records.map { it.key })
        assertEquals(setOf("gone"), PendingInput.clearedFromPersisted(raw))
        // A torn / truncated payload restores nothing rather than something half-read.
        assertTrue(PendingInput.fromPersisted(raw.substring(0, raw.length / 2)).records.isEmpty())
    }

    // ------------------------------------------------------------------
    // Socket-facing behaviour
    // ------------------------------------------------------------------

    @Test
    fun restoredRecordsCountAPossibleAttemptAndWaitForReconcile() {
        var store = add(PendingInput.emptyStore(), "never-sent").store
        store = add(store, "tried").store
        store = PendingInput.markSent(store, listOf("tried"), 2_000)
        val restored = PendingInput.restoredFromPreviousProcess(PendingInput.fromPersisted(PendingInput.toPersistable(store)), 5_000)
        assertEquals(listOf(1, 1), restored.records.map { it.tries })
        assertTrue(restored.records.all { it.sentAt == 0L })
        // Nothing restored goes out before its session's snapshot on this connection.
        assertTrue(PendingInput.sendableRecords(restored, emptySet()).isEmpty())
        assertEquals(listOf("never-sent", "tried"), PendingInput.sendableRecords(restored, setOf("s1")).map { it.key })
        val clean = PendingInput.emptyStore()
        assertSame(clean, PendingInput.restoredFromPreviousProcess(clean, 5_000))
    }

    @Test
    fun ackMatchesTheKeyAloneSoAFlushedQueueAddIsAcked() {
        val store = PendingInput.addRecord(PendingInput.emptyStore(), "q-1", PendingInput.KIND_QUEUE, "s1", "later", 1_000).store
        // A queue-add into an idle session becomes a turn whose idempotencyKey is the queueId.
        val acked = PendingInput.ackKey(store, "q-1")
        assertTrue(acked.removed)
        assertTrue(acked.store.records.isEmpty())
        assertFalse(PendingInput.ackKey(acked.store, "q-1").removed)
        assertSame(acked.store, PendingInput.ackKey(acked.store, null).store)
    }

    @Test
    fun snapshotReconcileReadsTheRawTreeForBothKinds() {
        var store = add(PendingInput.emptyStore(), "k-accepted").store
        store = add(store, "k-lost").store
        store = PendingInput.addRecord(store, "q-9", PendingInput.KIND_QUEUE, "s1", "queued", 1_000).store
        store = add(store, "k-other", sessionId = "s2").store
        val tree = JsCodec.parse(
            """{"turnsById":{"t1":{"idempotencyKey":"k-accepted","status":"outcome_unknown"},"t2":{"idempotencyKey":"k-other"}},
               "queuedMessages":[{"queueId":"q-9","text":"queued"}]}""",
        )
        val result = PendingInput.reconcileWithSnapshot(store, "s1", tree)
        assertEquals(listOf("k-accepted", "q-9"), result.cleared)
        // A snapshot proves nothing about another session's records.
        assertEquals(listOf("k-lost", "k-other"), result.store.records.map { it.key })
    }

    @Test
    fun attachmentRecordsDrainWithTheirPayloadButAreNeverPersisted() {
        var store = add(PendingInput.emptyStore(), "plain").store
        store = PendingInput.addRecord(
            store, "with-file", PendingInput.KIND_SEND, "s1", "see attached", 1_500,
            listOf(Attachment("a.txt", "text/plain", "aGVsbG8=")),
        ).store
        val due = PendingInput.dueRecords(store)
        assertEquals(listOf("plain", "with-file"), due.map { it.key })
        assertEquals(listOf(Attachment("a.txt", "text/plain", "aGVsbG8=")), due.last().attachments)
        assertNull(due.first().attachments)
        val restored = PendingInput.fromPersisted(PendingInput.toPersistable(store))
        assertEquals(listOf("plain"), restored.records.map { it.key })
    }

    @Test
    fun keysAreFreshV4Uuids() {
        val a = PendingInput.newKey()
        val b = PendingInput.newKey()
        assertFalse(a == b)
        assertTrue(a, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a))
    }
}
