package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.jsonStringify
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import kotlin.math.floor
import kotlin.math.max

/**
 * T2.2: faithful port of lib/pending-input.mjs — durable exactly-once operator input, the pure
 * core. A store is `{ records: [...] }` as the web persists it; every function takes the clock and
 * returns a new store. Persistence and transmission live with the client state (T2.3).
 *
 * The existing core/net PendingInput.kt (typed records, used by the socket today) keeps its own
 * copy of the constants; T2.3 moves it onto this port.
 */
object PendingInput {

    // lib/pending-input.mjs:57
    const val MAX_TRIES = 5
    const val MAX_AGE_MS = 10 * 60 * 1000
    const val MAX_RECORDS = 200
    const val MAX_PERSISTED_BYTES = 256 * 1024
    const val UNACKED_CLOSE_MS = 8000
    const val MAX_TOMBSTONES = 500

    // lib/pending-input.mjs:109 — a P2.4 @Agent mention, shape-checked at every entry into the store.
    private fun isDelegateMention(value: JsValue?): Boolean {
        if (value !is JsObj) return false
        val provider = value["provider"]
        val model = value["model"]
        val effort = value["reasoningEffort"]
        return value["kind"].isStr("delegate") &&
            provider is JsStr && provider.value.isNotEmpty() &&
            (value["mode"].isStr("review") || value["mode"].isStr("build")) &&
            (model == null || model is JsStr) &&
            (effort == null || effort is JsStr)
    }

    // lib/pending-input.mjs:123
    fun emptyStore(): JsObj = JsObj.of("records" to JsArr.EMPTY)

    private fun records(store: JsValue?): JsArr = store["records"] as? JsArr ?: throw JsError("TypeError", "store.records is not iterable")

    private fun storeOf(records: List<JsValue>): JsObj = JsObj.of("records" to JsArr.of(records))

    // lib/pending-input.mjs:135 — the client-minted keys a projection proves were accepted.
    fun acceptedKeys(projection: JsValue?): Set<String> {
        val keys = LinkedHashSet<String>()
        val turns = projection["turnsById"]
        if (turns is JsObj) {
            for (turn in turns.values) (turn["idempotencyKey"] as? JsStr)?.let { keys.add(it.value) }
        } else if (turns is JsArr) {
            for (turn in turns) (turn["idempotencyKey"] as? JsStr)?.let { keys.add(it.value) }
        }
        val queued = projection["queuedMessages"]
        if (queued is JsArr) {
            for (message in queued) (message["queueId"] as? JsStr)?.let { keys.add(it.value) }
        }
        return keys
    }

    // lib/pending-input.mjs:162 — `{ store, evicted }`; eviction is oldest-first and surfaced.
    fun addRecord(store: JsValue?, input: JsValue?, now: Double): JsObj {
        val attachments = input["attachments"]
        val mention = input["mention"]
        val record = JsObj.of(
            "key" to input["key"],
            "kind" to input["kind"],
            "sessionId" to input["sessionId"],
            "text" to input["text"],
            // `...(attachments && attachments.length ? { attachments } : {})`
            "attachments" to (if (truthy(attachments) && ((attachments as? JsArr)?.isNotEmpty() ?: truthy(attachments["length"]))) attachments else null),
            "mention" to (if (isDelegateMention(mention)) mention else null),
            "sentAt" to js(0),
            "tries" to js(0),
            "firstQueuedAt" to js(now),
        )
        val all = records(store) + record
        val overflow = all.size - MAX_RECORDS
        if (overflow <= 0) return JsObj.of("store" to storeOf(all), "evicted" to JsArr.EMPTY)
        return JsObj.of("store" to storeOf(all.drop(overflow)), "evicted" to JsArr.of(all.take(overflow)))
    }

    // lib/pending-input.mjs:200 — clear a record acknowledged by a LIVE event (by key alone).
    fun ackKey(store: JsValue?, key: JsValue?): JsObj {
        val (next, removed) = removeByKey(store, key)
        return JsObj.of("store" to next, "acked" to js(removed))
    }

    // lib/pending-input.mjs:206
    private fun removeByKey(store: JsValue?, key: JsValue?): Pair<JsValue?, Boolean> {
        if (key !is JsStr) return store to false
        val all = records(store)
        val kept = all.filter { !strictEquals(it["key"], key) }
        if (kept.size == all.size) return store to false
        return storeOf(kept) to true
    }

    // lib/pending-input.mjs:228 — drop a record the OPERATOR withdrew.
    fun discardKey(store: JsValue?, key: JsValue?): JsObj {
        val (next, removed) = removeByKey(store, key)
        return JsObj.of("store" to next, "discarded" to js(removed))
    }

    // lib/pending-input.mjs:243 — rewrite a still-pending record's text; same store once it is gone.
    fun editText(store: JsValue?, key: JsValue?, text: JsValue?): JsValue? {
        if (key !is JsStr) return store
        val all = records(store)
        if (all.none { strictEquals(it["key"], key) }) return store
        return storeOf(all.map { record -> if (strictEquals(record["key"], key)) (record as JsObj).put("text", text) else record })
    }

    // lib/pending-input.mjs:257 — the durable acknowledgement path: reconcile one session's records.
    fun reconcileWithSnapshot(store: JsValue?, sessionId: JsValue?, projection: JsValue?): JsObj {
        val accepted = acceptedKeys(projection)
        if (accepted.isEmpty()) return JsObj.of("store" to store, "cleared" to JsArr.EMPTY)
        val cleared = ArrayList<JsValue>()
        val kept = records(store).filter { record ->
            if (!strictEquals(record["sessionId"], sessionId)) return@filter true
            val key = record["key"]
            if (key !is JsStr || key.value !in accepted) return@filter true
            cleared.add(key)
            false
        }
        if (cleared.isEmpty()) return JsObj.of("store" to store, "cleared" to JsArr.EMPTY)
        return JsObj.of("store" to storeOf(kept), "cleared" to JsArr.of(cleared))
    }

    private fun sentAtIsZero(record: JsValue): Boolean = (record["sentAt"] as? JsNum)?.value == 0.0

    // lib/pending-input.mjs:281 — records awaiting transmission, oldest-first (FIFO is load-bearing).
    fun dueRecords(store: JsValue?, sessionId: JsValue? = null): JsArr = JsArr.of(
        records(store).filter { record -> sentAtIsZero(record) && (sessionId == null || strictEquals(record["sessionId"], sessionId)) },
    )

    // lib/pending-input.mjs:310 — records that may go out over THIS connection (the exactly-once gate).
    fun sendableRecords(store: JsValue?, reconciledSessions: Collection<JsValue>): JsArr = JsArr.of(
        dueRecords(store).filter { record ->
            val tries = jsToNumber(record["tries"])
            tries < MAX_TRIES && (tries == 0.0 || reconciledSessions.any { strictEquals(it, record["sessionId"]) })
        },
    )

    // lib/pending-input.mjs:318 — stamp records as in flight and count the attempt.
    fun markSent(store: JsValue?, keys: Collection<JsValue>, now: Double): JsValue? {
        if (keys.isEmpty()) return store
        return storeOf(
            records(store).map { record ->
                if (keys.any { strictEquals(it, record["key"]) }) {
                    (record as JsObj).with("sentAt" to js(now), "tries" to js(jsToNumber(record["tries"]) + 1))
                } else {
                    record
                }
            },
        )
    }

    // lib/pending-input.mjs:336 — clear every in-flight stamp so the next drain re-sends.
    fun resetInFlight(store: JsValue?): JsValue? {
        val all = records(store)
        if (all.none { !strictEquals(it["sentAt"], JsNum(0.0)) }) return store
        return storeOf(all.map { record -> if (strictEquals(record["sentAt"], JsNum(0.0))) record else (record as JsObj).put("sentAt", js(0)) })
    }

    // lib/pending-input.mjs:347 — age of the oldest in-flight record (0 when none): the half-open signal.
    fun oldestInFlightAge(store: JsValue?, now: Double): Double {
        var oldest = 0.0
        for (record in records(store)) {
            if (strictEquals(record["sentAt"], JsNum(0.0))) continue
            val age = now - jsToNumber(record["sentAt"])
            if (age > oldest) oldest = age
        }
        return oldest
    }

    // lib/pending-input.mjs:365 — the raw byte size behind a base64 string (issue #135).
    private fun base64Bytes(data: JsValue?): Double {
        if (data !is JsStr || data.value.isEmpty()) return 0.0
        val padding = if (data.value.endsWith("==")) 2 else if (data.value.endsWith("=")) 1 else 0
        return max(0.0, floor((data.value.length * 3.0) / 4) - padding)
    }

    // lib/pending-input.mjs:400 — one session's records as the composer's `sending` / `waiting` rows.
    fun describePending(store: JsValue?, options: JsValue? = null): JsArr {
        val sessionId = options["sessionId"]
        val now = jsToNumber(options["now"])
        val socketOpen = truthy(options["socketOpen"])
        val lastServerFrameAt = options["lastServerFrameAt"]?.let { jsToNumber(it) } ?: 0.0
        val linkStale = now - lastServerFrameAt > UNACKED_CLOSE_MS
        val rows = ArrayList<JsValue>()
        for (record in records(store)) {
            if (sessionId != null && !strictEquals(record["sessionId"], sessionId)) continue
            val sentAt = jsToNumber(record["sentAt"])
            val status = when {
                !socketOpen || sentAt == 0.0 -> "waiting"
                now - sentAt > UNACKED_CLOSE_MS && linkStale -> "waiting"
                else -> "sending"
            }
            val attachments = record["attachments"].arrOrEmpty()
            var imageCount = 0
            var bytes = 0.0
            for (att in attachments) {
                val mediaType = att["mediaType"]
                if (mediaType is JsStr && mediaType.value.startsWith("image/")) imageCount += 1
                bytes += base64Bytes(att["data"])
            }
            rows.add(
                JsObj.of(
                    "key" to record["key"],
                    "sessionId" to record["sessionId"],
                    "kind" to record["kind"],
                    "text" to record["text"],
                    "status" to JsStr(status),
                    "attachmentCount" to js(attachments.size),
                    "imageCount" to js(imageCount),
                    "bytes" to js(bytes),
                ),
            )
        }
        return JsArr.of(rows)
    }

    // lib/pending-input.mjs:449 — abandon exhausted or aged-out records; `{ store, unsent }`.
    fun expireRecords(store: JsValue?, now: Double): JsObj {
        val unsent = ArrayList<JsValue>()
        val kept = records(store).filter { record ->
            val sentAt = jsToNumber(record["sentAt"])
            val exhausted = jsToNumber(record["tries"]) >= MAX_TRIES && (sentAt == 0.0 || now - sentAt >= UNACKED_CLOSE_MS)
            val stale = now - jsToNumber(record["firstQueuedAt"]) >= MAX_AGE_MS
            if (!exhausted && !stale) return@filter true
            unsent.add(record)
            false
        }
        if (unsent.isEmpty()) return JsObj.of("store" to store, "unsent" to JsArr.EMPTY)
        return JsObj.of("store" to storeOf(kept), "unsent" to JsArr.of(unsent))
    }

    // lib/pending-input.mjs:487 — the persistable JSON (records without attachments + tombstones),
    // dropping oldest records until it fits MAX_PERSISTED_BYTES (UTF-16 length, as the web measures).
    fun toPersistable(store: JsValue?, cleared: Collection<JsValue> = emptyList()): String {
        val tombstones = JsArr.of(cleared.toList().takeLast(MAX_TOMBSTONES))
        var durable = records(store).filter { !truthy(it["attachments"]) }
        while (durable.isNotEmpty()) {
            val payload = jsonStringify(JsObj.of("v" to js(2), "records" to JsArr.of(durable), "cleared" to tombstones))
            if (payload.length <= MAX_PERSISTED_BYTES) return payload
            durable = durable.drop(1)
        }
        return jsonStringify(JsObj.of("v" to js(2), "records" to JsArr.EMPTY, "cleared" to tombstones))
    }

    /** `JSON.parse(raw)`, or null where the web's parse would throw. */
    private fun parseOrNull(raw: String): JsValue? = try {
        JsCodec.parse(raw)
    } catch (_: IllegalArgumentException) {
        null // kotlinx SerializationException is an IllegalArgumentException
    }

    // lib/pending-input.mjs:507 — rehydrate a persisted store, dropping anything malformed; nothing in flight.
    fun fromPersisted(raw: JsValue?): JsObj {
        if (raw !is JsStr || raw.value.isEmpty()) return emptyStore()
        val parsed = parseOrNull(raw.value) ?: return emptyStore()
        val version = parsed["v"]
        if ((!strictEquals(version, JsNum(1.0)) && !strictEquals(version, JsNum(2.0))) || parsed["records"] !is JsArr) return emptyStore()
        val kept = (parsed["records"] as JsArr).filter { record ->
            record["key"] is JsStr &&
                (record["kind"].isStr("send") || record["kind"].isStr("queue")) &&
                record["sessionId"] is JsStr &&
                record["text"] is JsStr &&
                record["tries"].finite != null &&
                record["firstQueuedAt"].finite != null
        }.takeLast(MAX_RECORDS)
        return storeOf(
            kept.map { record ->
                JsObj.of(
                    "key" to record["key"],
                    "kind" to record["kind"],
                    "sessionId" to record["sessionId"],
                    "text" to record["text"],
                    "mention" to (if (isDelegateMention(record["mention"])) record["mention"] else null),
                    "sentAt" to js(0),
                    "tries" to record["tries"],
                    "firstQueuedAt" to record["firstQueuedAt"],
                )
            },
        )
    }

    // lib/pending-input.mjs:540 — the tombstone set a persisted payload carries.
    fun clearedFromPersisted(raw: JsValue?): Set<String> {
        if (raw !is JsStr || raw.value.isEmpty()) return emptySet()
        val parsed = parseOrNull(raw.value) ?: return emptySet()
        val cleared = parsed["cleared"] as? JsArr ?: return emptySet()
        return LinkedHashSet(cleared.mapNotNull { (it as? JsStr)?.value }.takeLast(MAX_TOMBSTONES))
    }

    // lib/pending-input.mjs:553 — drop every record for a session (deleted / archived server-side).
    fun forgetSession(store: JsValue?, sessionId: JsValue?): JsValue? {
        val all = records(store)
        val kept = all.filter { !strictEquals(it["sessionId"], sessionId) }
        if (kept.size == all.size) return store
        return storeOf(kept)
    }

    // lib/pending-input.mjs:583 — union two tabs' stores, mine wins, never adopting a cleared key.
    fun mergeStores(mine: JsValue?, theirs: JsValue?, cleared: Collection<JsValue> = emptyList()): JsValue? {
        val held = records(mine).map { it["key"] }
        val extra = records(theirs).filter { record ->
            held.none { strictEquals(it, record["key"]) } && cleared.none { strictEquals(it, record["key"]) }
        }
        if (extra.isEmpty()) return mine
        val merged = (records(mine) + extra).sortedWith { a, b ->
            sortSign(jsToNumber(a["firstQueuedAt"]) - jsToNumber(b["firstQueuedAt"]))
        }
        val overflow = merged.size - MAX_RECORDS
        return storeOf(if (overflow > 0) merged.drop(overflow) else merged)
    }
}
