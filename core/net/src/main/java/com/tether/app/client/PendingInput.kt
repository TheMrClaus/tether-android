package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.bool
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.str
import java.util.UUID
import com.tether.app.protocol.helpers.PendingInput as Web

/**
 * T1.3: durable at-most-once operator input for the socket — typed glue over
 * [com.tether.app.protocol.helpers.PendingInput], the corpus-verified port of
 * lib/pending-input.mjs. Every rule (the gate, FIFO, caps, expiry, ack by key,
 * snapshot reconcile, the persisted `{v:2, records, cleared}` shape of
 * `tether:pendingInput`) lives in that port; this file only converts between the
 * web store (a JS tree) and typed values, and owns no constant or rule of its own
 * except [restoredFromPreviousProcess] (see there).
 */
object PendingInput {
    const val KIND_SEND = "send"
    const val KIND_QUEUE = "queue"

    /** The half-open watchdog window, lib/pending-input.mjs UNACKED_CLOSE_MS. */
    const val UNACKED_CLOSE_MS: Long = Web.UNACKED_CLOSE_MS.toLong()

    /** Tombstones kept for removed keys, lib/pending-input.mjs MAX_TOMBSTONES. */
    const val MAX_TOMBSTONES: Int = Web.MAX_TOMBSTONES

    /** use-tether.ts newIdempotencyKey: a v4 UUID (the send's idempotencyKey / queue-add's queueId). */
    fun newKey(): String = UUID.randomUUID().toString()

    fun emptyStore(): PendingStore = PendingStore(Web.emptyStore())

    /** Client-minted keys a (raw, journal-folded) projection proves were accepted. */
    fun acceptedKeys(projection: JsValue?): Set<String> = Web.acceptedKeys(projection)

    /** Record an outbound input before transmission; overflow (MAX_RECORDS) evicts oldest-first. */
    fun addRecord(
        store: PendingStore,
        key: String,
        kind: String,
        sessionId: String,
        text: String,
        now: Long,
        attachments: List<Attachment>? = null,
    ): AddResult {
        val input = JsObj.of(
            "key" to js(key),
            "kind" to js(kind),
            "sessionId" to js(sessionId),
            "text" to js(text),
            "attachments" to attachments?.let { list -> JsArr.of(list.map(::attachmentTree)) },
        )
        val result = Web.addRecord(store.tree, input, now.toDouble())
        return AddResult(PendingStore(result["store"] as JsObj), records(result["evicted"]))
    }

    /** Live ack (`turn_started.idempotencyKey` / `queued_message_added.queueId`): key alone, never kind. */
    fun ackKey(store: PendingStore, key: String?): RemoveResult {
        val result = Web.ackKey(store.tree, key?.let(::js))
        return RemoveResult(storeOf(result["store"], store), result["acked"].bool == true)
    }

    /** Operator withdrawal ("never deliver this") — mechanically identical to ack. */
    fun discardKey(store: PendingStore, key: String?): RemoveResult {
        val result = Web.discardKey(store.tree, key?.let(::js))
        return RemoveResult(storeOf(result["store"], store), result["discarded"].bool == true)
    }

    fun editText(store: PendingStore, key: String, text: String): PendingStore =
        storeOf(Web.editText(store.tree, js(key), js(text)), store)

    /** The durable acknowledgement path, against the RAW snapshot `state` (as the web reads it). */
    fun reconcileWithSnapshot(store: PendingStore, sessionId: String, projection: JsValue?): ReconcileResult {
        val result = Web.reconcileWithSnapshot(store.tree, js(sessionId), projection)
        return ReconcileResult(storeOf(result["store"], store), strings(result["cleared"]))
    }

    fun dueRecords(store: PendingStore, sessionId: String? = null): List<PendingRecord> =
        records(Web.dueRecords(store.tree, sessionId?.let(::js)))

    /** The exactly-once gate: tries == 0, or the session was reconciled on THIS connection. */
    fun sendableRecords(store: PendingStore, reconciledSessions: Set<String>): List<PendingRecord> =
        records(Web.sendableRecords(store.tree, reconciledSessions.map(::js)))

    fun markSent(store: PendingStore, keys: Collection<String>, now: Long): PendingStore =
        storeOf(Web.markSent(store.tree, keys.map(::js), now.toDouble()), store)

    fun resetInFlight(store: PendingStore): PendingStore = storeOf(Web.resetInFlight(store.tree), store)

    fun oldestInFlightAge(store: PendingStore, now: Long): Long =
        Web.oldestInFlightAge(store.tree, now.toDouble()).toLong()

    fun expireRecords(store: PendingStore, now: Long): ExpireResult {
        val result = Web.expireRecords(store.tree, now.toDouble())
        return ExpireResult(storeOf(result["store"], store), records(result["unsent"]))
    }

    fun forgetSession(store: PendingStore, sessionId: String): PendingStore =
        storeOf(Web.forgetSession(store.tree, js(sessionId)), store)

    /** `tether:pendingInput`'s payload: `{v:2, records (no attachments), cleared}`, ≤ MAX_PERSISTED_BYTES. */
    fun toPersistable(store: PendingStore, cleared: Collection<String> = emptyList()): String =
        Web.toPersistable(store.tree, cleared.map(::js))

    /** Rehydrate (malformed records dropped, nothing in flight); any unreadable payload is empty. */
    fun fromPersisted(raw: String?): PendingStore = PendingStore(Web.fromPersisted(raw?.let(::js)))

    fun clearedFromPersisted(raw: String?): Set<String> = Web.clearedFromPersisted(raw?.let(::js))

    /** Union, [mine] wins, never adopting a [cleared] key, oldest-first overall. */
    fun mergeStores(mine: PendingStore, theirs: PendingStore, cleared: Collection<String>): PendingStore =
        storeOf(Web.mergeStores(mine.tree, theirs.tree, cleared.map(::js)), mine)

    /**
     * The one rule beyond the web: a record restored from disk may already have
     * been transmitted by the process that wrote it — the `tries` bump of
     * `markSent` is persisted asynchronously, so a process killed between the
     * wire write and the store write leaves `tries: 0` on disk. Such a record
     * counts that possible attempt (tries 0 -> 1, not in flight), so
     * [sendableRecords] holds it until its session's snapshot has been reconciled
     * on the new connection. Without this a turn the server already journaled
     * could go out again before any snapshot proved otherwise, and after a server
     * restart (empty dedup slot) start a second turn.
     */
    fun restoredFromPreviousProcess(store: PendingStore, now: Long): PendingStore {
        val untried = store.records.filter { it.tries == 0 }.map { it.key }
        if (untried.isEmpty()) return store
        return resetInFlight(markSent(store, untried, now))
    }

    data class AddResult(val store: PendingStore, val evicted: List<PendingRecord>)
    data class RemoveResult(val store: PendingStore, val removed: Boolean)
    data class ReconcileResult(val store: PendingStore, val cleared: List<String>)
    data class ExpireResult(val store: PendingStore, val unsent: List<PendingRecord>)

    /** The helper returns the SAME store when nothing changed; keep that identity for callers. */
    private fun storeOf(tree: JsValue?, previous: PendingStore): PendingStore =
        if (tree === previous.tree) previous else PendingStore(tree as JsObj)

    private fun records(value: JsValue?): List<PendingRecord> = (value as? JsArr).orEmpty().map { PendingRecord(it as JsObj) }

    private fun strings(value: JsValue?): List<String> = (value as? JsArr).orEmpty().mapNotNull { it.str }

    private fun attachmentTree(a: Attachment): JsObj =
        JsObj.of("name" to js(a.name), "mediaType" to js(a.mediaType), "data" to js(a.data))
}

/** The web store `{ records: [...] }`, immutable. */
class PendingStore internal constructor(val tree: JsObj) {
    val records: List<PendingRecord> by lazy { (tree["records"] as? JsArr).orEmpty().map { PendingRecord(it as JsObj) } }

    override fun equals(other: Any?): Boolean = other is PendingStore && other.tree == tree
    override fun hashCode(): Int = tree.hashCode()
    override fun toString(): String = "PendingStore(${records.size} records)"
}

/** A typed read view of one web pending record. */
class PendingRecord internal constructor(val tree: JsObj) {
    val key: String get() = tree["key"].str.orEmpty()
    val kind: String get() = tree["kind"].str.orEmpty()
    val sessionId: String get() = tree["sessionId"].str.orEmpty()
    val text: String get() = tree["text"].str.orEmpty()
    val sentAt: Long get() = (tree["sentAt"].num ?: 0.0).toLong()
    val tries: Int get() = (tree["tries"].num ?: 0.0).toInt()
    val firstQueuedAt: Long get() = (tree["firstQueuedAt"].num ?: 0.0).toLong()

    /** In-memory only: records carrying attachments are never persisted (toPersistable). */
    val attachments: List<Attachment>? get() = (tree["attachments"] as? JsArr)?.mapNotNull { value ->
        val obj = value as? JsObj ?: return@mapNotNull null
        Attachment(obj["name"].str.orEmpty(), obj["mediaType"].str.orEmpty(), obj["data"].str.orEmpty())
    }

    override fun equals(other: Any?): Boolean = other is PendingRecord && other.tree == tree
    override fun hashCode(): Int = tree.hashCode()
    override fun toString(): String = "PendingRecord($key, $kind, $sessionId, tries=$tries, sentAt=$sentAt)"
}
