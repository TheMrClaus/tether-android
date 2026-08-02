package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.SessionProjection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable at-most-once operator input — pure Kotlin port of the core of
 * aidash lib/pending-input.mjs (specs/protocol-spec.md §5.6).
 *
 * Every prompt is recorded here BEFORE transmission and dropped only once the
 * server is known to have journaled it (live ack or snapshot reconciliation).
 * Pure: callers thread the clock; nothing here does I/O.
 *
 * Deviation from the browser module: the cross-tab tombstone/merge machinery
 * is omitted — an Android app is a single instance over one store.
 */
object PendingInput {
    const val MAX_TRIES = 5
    const val MAX_AGE_MS = 600_000L
    const val MAX_RECORDS = 200
    const val UNACKED_CLOSE_MS = 8_000L

    const val KIND_SEND = "send"
    const val KIND_QUEUE = "queue"

    private val json = Json { ignoreUnknownKeys = true }

    fun emptyStore(): PendingStore = PendingStore(emptyList())

    /** Client-minted keys a projection proves were accepted. */
    fun acceptedKeys(projection: SessionProjection): Set<String> {
        val keys = HashSet<String>()
        for (turn in projection.turnsById.values) {
            turn.idempotencyKey?.let { keys.add(it) }
        }
        for (message in projection.queuedMessages) keys.add(message.queueId)
        return keys
    }

    /** Record an outbound input before transmission; overflow evicts oldest-first. */
    fun addRecord(
        store: PendingStore,
        key: String,
        kind: String,
        sessionId: String,
        text: String,
        now: Long,
        attachments: List<Attachment>? = null,
    ): AddResult {
        val record = PendingRecord(
            key = key,
            kind = kind,
            sessionId = sessionId,
            text = text,
            sentAt = 0,
            tries = 0,
            firstQueuedAt = now,
            attachments = attachments,
        )
        val records = store.records + record
        val overflow = records.size - MAX_RECORDS
        if (overflow <= 0) return AddResult(PendingStore(records), emptyList())
        return AddResult(PendingStore(records.drop(overflow)), records.take(overflow))
    }

    /** Live ack: matches on KEY ALONE, never kind (queue-add can flush into a turn). */
    fun ackKey(store: PendingStore, key: String?): RemoveResult = removeByKey(store, key)

    /** Operator withdrawal ("never deliver this") — mechanically identical to ack. */
    fun discardKey(store: PendingStore, key: String?): RemoveResult = removeByKey(store, key)

    private fun removeByKey(store: PendingStore, key: String?): RemoveResult {
        if (key == null) return RemoveResult(store, false)
        val records = store.records.filter { it.key != key }
        if (records.size == store.records.size) return RemoveResult(store, false)
        return RemoveResult(PendingStore(records), true)
    }

    /** Rewrite a still-pending record's text (queue-edit against an unaccepted queueId). */
    fun editText(store: PendingStore, key: String, text: String): PendingStore {
        if (store.records.none { it.key == key }) return store
        return PendingStore(store.records.map { if (it.key == key) it.copy(text = text) else it })
    }

    /**
     * Reconcile one session's records against an authoritative snapshot — the
     * durable acknowledgement path. A record whose key the snapshot contains
     * was accepted; whatever it does NOT contain was never accepted.
     */
    fun reconcileWithSnapshot(
        store: PendingStore,
        sessionId: String,
        projection: SessionProjection,
    ): ReconcileResult {
        val accepted = acceptedKeys(projection)
        if (accepted.isEmpty()) return ReconcileResult(store, emptyList())
        val cleared = ArrayList<String>()
        val records = store.records.filter { record ->
            if (record.sessionId != sessionId) return@filter true
            if (record.key !in accepted) return@filter true
            cleared.add(record.key)
            false
        }
        if (cleared.isEmpty()) return ReconcileResult(store, cleared)
        return ReconcileResult(PendingStore(records), cleared)
    }

    /** Records awaiting transmission, oldest-first (FIFO order is load-bearing). */
    fun dueRecords(store: PendingStore, sessionId: String? = null): List<PendingRecord> =
        store.records.filter { it.sentAt == 0L && (sessionId == null || it.sessionId == sessionId) }

    /**
     * Records that may go out over THIS connection: an already-transmitted
     * record may be re-sent only after its session was reconciled against a
     * snapshot on this connection; freshly minted keys (tries == 0) are exempt.
     */
    fun sendableRecords(store: PendingStore, reconciledSessions: Set<String>): List<PendingRecord> =
        dueRecords(store).filter {
            it.tries < MAX_TRIES && (it.tries == 0 || it.sessionId in reconciledSessions)
        }

    /** Stamp records as in flight and count the attempt. */
    fun markSent(store: PendingStore, keys: Collection<String>, now: Long): PendingStore {
        val target = keys.toSet()
        if (target.isEmpty()) return store
        return PendingStore(
            store.records.map {
                if (it.key in target) it.copy(sentAt = now, tries = it.tries + 1) else it
            },
        )
    }

    /** Clear in-flight stamps so the next drain re-sends (socket open / restore). */
    fun resetInFlight(store: PendingStore): PendingStore {
        if (store.records.none { it.sentAt != 0L }) return store
        return PendingStore(store.records.map { if (it.sentAt == 0L) it else it.copy(sentAt = 0) })
    }

    /** Age of the oldest in-flight record, or 0 — drives half-open detection. */
    fun oldestInFlightAge(store: PendingStore, now: Long): Long {
        var oldest = 0L
        for (record in store.records) {
            if (record.sentAt == 0L) continue
            val age = now - record.sentAt
            if (age > oldest) oldest = age
        }
        return oldest
    }

    /** Abandon records that exhausted retries or aged out; abandonments are surfaced. */
    fun expireRecords(store: PendingStore, now: Long): ExpireResult {
        val unsent = ArrayList<PendingRecord>()
        val records = store.records.filter { record ->
            val exhausted = record.tries >= MAX_TRIES &&
                (record.sentAt == 0L || now - record.sentAt >= UNACKED_CLOSE_MS)
            val stale = now - record.firstQueuedAt >= MAX_AGE_MS
            if (!exhausted && !stale) return@filter true
            unsent.add(record)
            false
        }
        if (unsent.isEmpty()) return ExpireResult(store, unsent)
        return ExpireResult(PendingStore(records), unsent)
    }

    /** Drop every record for a deleted/archived session. */
    fun forgetSession(store: PendingStore, sessionId: String): PendingStore {
        val records = store.records.filter { it.sessionId != sessionId }
        if (records.size == store.records.size) return store
        return PendingStore(records)
    }

    /**
     * Records carrying attachments are OMITTED (mirrors lib/pending-input.mjs):
     * base64 payloads stay in memory for the life of the process and are lost
     * on restart rather than written to disk.
     */
    fun toPersisted(store: PendingStore): String =
        json.encodeToString(
            PersistedPendingStore.serializer(),
            PersistedPendingStore(1, store.records.filter { it.attachments == null }),
        )

    /** Restored records come back not-in-flight so the first drain re-sends them. */
    fun fromPersisted(raw: String?): PendingStore {
        if (raw.isNullOrEmpty()) return emptyStore()
        val parsed = try {
            json.decodeFromString(PersistedPendingStore.serializer(), raw)
        } catch (_: Exception) {
            return emptyStore()
        }
        val records = parsed.records
            .filter { it.key.isNotEmpty() && (it.kind == KIND_SEND || it.kind == KIND_QUEUE) }
            .takeLast(MAX_RECORDS)
            .map { it.copy(sentAt = 0) }
        return PendingStore(records)
    }

    data class AddResult(val store: PendingStore, val evicted: List<PendingRecord>)
    data class RemoveResult(val store: PendingStore, val removed: Boolean)
    data class ReconcileResult(val store: PendingStore, val cleared: List<String>)
    data class ExpireResult(val store: PendingStore, val unsent: List<PendingRecord>)
}

data class PendingStore(val records: List<PendingRecord>)

@Serializable
data class PendingRecord(
    val key: String,
    val kind: String,
    val sessionId: String,
    val text: String,
    val sentAt: Long = 0,
    val tries: Int = 0,
    val firstQueuedAt: Long = 0,
    /** In-memory only: never persisted (see PendingInput.toPersisted). */
    val attachments: List<Attachment>? = null,
)

@Serializable
private data class PersistedPendingStore(val v: Int, val records: List<PendingRecord>)
