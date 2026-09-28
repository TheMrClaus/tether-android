package com.tether.app.mirror

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An AgentSession row to mirror: [json] is its wire JSON (sealed at rest). */
class SessionRowInput(
    val sessionId: String,
    val json: String,
    val updatedAt: Long,
    val lastMessageAt: Long?,
    val pinned: Boolean,
    val runtimeArchived: Boolean,
    val serverLastSeq: Long? = null,
)

/** A mirrored session row as read back at bind time. */
class StoredSession(
    val sessionId: String,
    val json: String,
    val pinned: Boolean,
    val lastOpenedAt: Long?,
    val goneFromServer: Boolean,
)

/** What a cold start learns from the mirror before any network: the list and the cursors. */
class MirrorIndex(
    val sessions: List<StoredSession>,
    /** Sessions whose base + tail are usable for a delta attach (a non-null persisted cursor). */
    val cursors: Map<String, Long>,
)

/** One session's persisted base, turn details and tail (§2.4 hydration). */
class HydratedSession(
    val sessionId: String,
    val base: JsObj,
    val throughSeq: Long,
    val trimmedBefore: Int?,
    val origin: String,
    /** `turns-detail` turns, by turnId, to splice into the base's `turnsById`. */
    val details: Map<String, JsObj>,
    /** Events after the base, in seq order. */
    val tail: List<JsObj>,
    /** The persisted cursor; null = displayable, but the next attach must be a full one. */
    val cursor: Long?,
    val lastVerifiedAt: Long?,
)

sealed interface Hydration {
    data class Loaded(val session: HydratedSession) : Hydration

    /** No base for the session (or no mirror bound). */
    data object None : Hydration

    /** A blob failed to decrypt or decode: the session's mirror data was dropped (§2.4 corruption). */
    data object Corrupt : Hydration
}

/**
 * T13.1: the per-server-origin journal mirror (SYNC_DESIGN §2.2–§2.4, §8).
 *
 * One actor per process serializes every DB operation on one thread, so per-session order is
 * the order of the calls. Writes are write-behind: the actor commits one transaction per batch
 * of at most [batchMaxOps] operations, collected for at most [batchWindowMs]. Losing an
 * uncommitted batch (process death) is a performance cost only: the persisted cursor is written
 * in the same transaction as the rows it covers, so the next attach asks from an older cursor
 * and the server answers with state (§2.3 crash safety).
 *
 * The DB is self-consistent by construction, whatever the caller does: an event is persisted
 * only when it extends the persisted cursor by exactly one ([recordEvent]), so the tail is
 * always contiguous from its base and `fold(base, tail)` is exactly what the server held at the
 * cursor.
 *
 * Every write names the [origin] it belongs to and lands only in that origin's DB; a write for
 * another origin (a late frame of a socket let go) is dropped.
 *
 * Logs carry exception types only: never blob contents, ids, origins or key material.
 */
class JournalMirror(
    private val dbFactory: MirrorDbFactory,
    private val keyStore: MirrorKeyStore,
    private val reducerVersion: String,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchWindowMs: Long = 100,
    private val batchMaxOps: Int = 64,
    /** Blobs sealed under one data key before it is rotated (§8.1: far below 2^32). */
    val rotateAfterWrites: Long = 1L shl 28,
    /** Local checkpoint (§2.4): a tail this long becomes the new base... */
    val checkpointEvery: Int = 2_000,
    /** ...or this long at a `turn_end`. */
    val checkpointAtTurnEnd: Int = 500,
    private val log: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    /**
     * Interim caps until T13.5's eviction (security review M2 / L3). A blob whose plaintext is
     * larger than [maxBlobPlaintextBytes], or whose sealed form is larger than
     * [maxStoredBlobBytes] (Android's CursorWindow cannot read a row much above 2 MB), is never
     * stored: its session's copy is dropped instead, so the mirror never claims to cover it.
     */
    val maxBlobPlaintextBytes: Int = 8 * 1024 * 1024,
    val maxStoredBlobBytes: Int = 1536 * 1024,
    /** At most this many sessions hold a base per origin... */
    val maxSessions: Int = 500,
    /** ...and at most this many bytes of base + tail blobs in total (SYNC_DESIGN §7's 200 MB default). */
    val maxOriginBytes: Long = 200L * 1024 * 1024,
) {
    // ---- the queue (any thread) ----
    private val queue = ArrayDeque<Op>()
    private var controlsQueued = 0
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val job: Job

    /**
     * The writer died of an Error (e.g. an OutOfMemoryError on a huge state). Everything after
     * is a no-op that answers at once: bind() -> null, hydrate -> None. Set under [queue].
     */
    @Volatile
    var dead: Boolean = false
        private set

    /**
     * Bumped by [wipe] under [queue], BEFORE the keys are shredded (ta-hra R1). An op dequeued
     * under an older epoch belongs to the wiped sign-in: a data key it mints after the bump is
     * destroyed again ([createKey]) and a bind it answers is answered with null.
     */
    @Volatile
    private var wipeEpoch = 0L

    // ---- actor state (actor thread only) ----
    private var db: MirrorDatabase? = null
    private var dao: MirrorDao? = null
    private var cipher: MirrorCipher? = null
    private var boundOrigin: String? = null
    private var originKey: String = ""
    private var writes = 0L

    /** [wipeEpoch] when the op in hand was dequeued (set under [queue] by the actor). */
    private var opEpoch = 0L

    /**
     * Test seam: runs on the actor right before a hydration reads the base, so a test can hold
     * the writer (or fail the read) while live events arrive. Null in production.
     */
    @Volatile
    var beforeHydrateRead: (() -> Unit)? = null

    init {
        job = scope.launch(dispatcher) { loop() }
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    /**
     * Open [origin]'s mirror (deleting any other origin's: one origin at a time, §8.3) and read
     * its index. Null = no mirror this process (the Keystore is unavailable right now).
     */
    suspend fun bind(origin: String): MirrorIndex? = control { Op.Bind(origin, it) }

    /**
     * Logout / revocation (§8.3): the data key and its KEK are destroyed NOW, on the calling
     * thread ([shredKeys]), so a process death before the writer gets to it still leaves nothing
     * readable (security review M1); the writer then closes and deletes every mirror DB file.
     * If the writer is dead, the files are deleted here too.
     *
     * ta-hra R1: everything still queued belongs to the sign-in being wiped. Under the queue
     * lock the wipe epoch moves, queued writes are dropped, queued controls are answered with
     * their fallbacks (a bind with null, a rotation, flush or read with "nothing"), and the
     * Wipe goes first, so no queued bind or rotation can mint a key after the shred and no
     * pre-wipe write can land under one. The op the writer already holds sees the epoch move.
     *
     * Never waits for the writer or its locks (ta-hra R3); callers on the main thread should
     * still call it off main (the Keystore delete is an IPC).
     */
    fun wipe(): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        val cancelled = ArrayList<Op>()
        val writerDead = synchronized(queue) {
            wipeEpoch++
            if (!dead) {
                val it = queue.iterator()
                while (it.hasNext()) {
                    val op = it.next()
                    if (op is Op.Close) continue // a test's process death stays queued
                    it.remove()
                    if (op !is Op.Write) {
                        controlsQueued--
                        cancelled += op
                    }
                }
                queue.addFirst(Op.Wipe(done))
                controlsQueued++
            }
            dead
        }
        shredKeys()
        // Answered after the shred: whoever waited sees the wipe's effect.
        cancelled.forEach(::answer)
        if (writerDead) {
            deleteFiles()
            done.complete(Unit)
        } else {
            signal.trySend(Unit)
        }
        return done
    }

    /**
     * Destroy the wrapped data key and the Keystore key-encryption key, synchronously and
     * without taking any lock a writer may hold ([MirrorKeyStore.destroy]): the key file goes
     * first, so every blob on disk is unreadable at once; the next bind finds no key and
     * deletes the DB.
     */
    fun shredKeys() {
        try {
            keyStore.destroy()
        } catch (e: Exception) {
            log("mirror key destroy failed (${e.javaClass.simpleName})")
        }
    }

    /** Delete every mirror DB file, best effort (the keys are already gone). Any thread. */
    private fun deleteFiles() {
        try {
            for (name in dbFactory.existing()) dbFactory.delete(name)
        } catch (t: Throwable) {
            log("mirror delete failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * Clear cache (§7, §8.1; T13.5 wires the setting): delete the DB and rotate the data key.
     * The mirror stays bound to the same origin, empty.
     */
    suspend fun clearAndRotate(): Unit = control { Op.Rotate(it) }

    /** Wait until every write enqueued before this call is committed. */
    suspend fun flush(): Unit = control { Op.Flush(it) }

    /** The persisted base + details + tail of [sessionId], read after every write enqueued before. */
    suspend fun hydrate(origin: String, sessionId: String): Hydration = hydrateAsync(origin, sessionId).await()

    /**
     * [hydrate], enqueued NOW (non-suspending), so a caller can order it against its own writes:
     * the read sees every write enqueued before this call and none enqueued after.
     */
    fun hydrateAsync(origin: String, sessionId: String): CompletableDeferred<Hydration> =
        CompletableDeferred<Hydration>().also { enqueue(Op.Hydrate(origin, sessionId, it)) }

    /** A snapshot WITH state: replace the base, clear the tail, cursor := [throughSeq] (§2.3). */
    fun recordState(origin: String, sessionId: String, throughSeq: Long, trimmedBefore: Int?, state: JsObj, keepTurnIds: Set<String>) =
        enqueue(Op.State(origin, sessionId, throughSeq, trimmedBefore, state, keepTurnIds, clock()))

    /** A stateless (at head) snapshot: the server confirmed [throughSeq]. */
    fun recordVerified(origin: String, sessionId: String, throughSeq: Long) =
        enqueue(Op.Verified(origin, sessionId, throughSeq, clock()))

    /** A live event the cursor folded. Persisted only if it extends the persisted cursor by one. */
    fun recordEvent(origin: String, sessionId: String, seq: Long, type: String, ts: Long?, json: String) =
        enqueue(Op.Event(origin, sessionId, seq, type, ts, json))

    /**
     * A folded event with no seq: never persisted; the persisted cursor is cleared (§2.3).
     * Returns when the clear is COMMITTED (it ends the batch at once): the caller folds the event
     * only after that, so a process death can never leave a cursor that claims to cover a view
     * the UI already showed with the seqless fold. (A lost batch of seq'd events is harmless:
     * the cursor is then behind and the server answers with state.)
     */
    fun recordSeqless(origin: String, sessionId: String): CompletableDeferred<Unit> {
        enqueue(Op.Seqless(origin, sessionId))
        return CompletableDeferred<Unit>().also { enqueue(Op.Flush(it)) }
    }

    fun recordTurnDetails(origin: String, sessionId: String, turns: Map<String, Pair<Int?, JsObj>>) =
        enqueue(Op.TurnDetails(origin, sessionId, turns, clock()))

    /** [full] = a `ready` list: rows missing from it become `gone_from_server`, never deleted. */
    fun recordSessions(origin: String, rows: List<SessionRowInput>, full: Boolean) =
        enqueue(Op.Sessions(origin, rows, full))

    fun recordOpened(origin: String, sessionId: String) = enqueue(Op.Opened(origin, sessionId, clock()))

    /** A fold exception / undecodable base: forget the session's base, tail, details and cursor. */
    fun dropSession(origin: String, sessionId: String) = enqueue(Op.Drop(origin, sessionId))

    /** A local checkpoint (§2.4): [state] = fold(base, tail) through [throughSeq] becomes the base. */
    fun checkpoint(origin: String, sessionId: String, throughSeq: Long, state: JsObj) =
        enqueue(Op.Checkpoint(origin, sessionId, throughSeq, state, clock()))

    /**
     * Tests: process death. Everything not yet committed is lost, the DB is closed, the actor
     * stops. A new [JournalMirror] over the same files is the next process.
     */
    suspend fun abandon() {
        if (!job.isActive) return
        synchronized(queue) {
            queue.clear()
            controlsQueued = 0
        }
        // Bounded: a writer that never ran (or is stuck) must not hang the test's "death".
        withTimeoutOrNull(5_000) { control<Unit> { Op.Close(it) } }
        job.cancel()
    }

    // ------------------------------------------------------------------
    // Queue + actor loop
    // ------------------------------------------------------------------

    private sealed interface Op {
        /** A write: batched into one transaction with its neighbours. */
        sealed interface Write : Op {
            val origin: String
        }

        class State(
            override val origin: String,
            val sessionId: String,
            val throughSeq: Long,
            val trimmedBefore: Int?,
            val state: JsObj,
            val keepTurnIds: Set<String>,
            val at: Long,
        ) : Write

        class Verified(override val origin: String, val sessionId: String, val throughSeq: Long, val at: Long) : Write
        class Event(
            override val origin: String,
            val sessionId: String,
            val seq: Long,
            val type: String,
            val ts: Long?,
            val json: String,
        ) : Write

        class Seqless(override val origin: String, val sessionId: String) : Write
        class TurnDetails(override val origin: String, val sessionId: String, val turns: Map<String, Pair<Int?, JsObj>>, val at: Long) : Write
        class Sessions(override val origin: String, val rows: List<SessionRowInput>, val full: Boolean) : Write
        class Opened(override val origin: String, val sessionId: String, val at: Long) : Write
        class Drop(override val origin: String, val sessionId: String) : Write
        class Checkpoint(override val origin: String, val sessionId: String, val throughSeq: Long, val state: JsObj, val at: Long) : Write

        class Bind(val origin: String, val reply: CompletableDeferred<MirrorIndex?>) : Op
        class Wipe(val reply: CompletableDeferred<Unit>) : Op
        class Rotate(val reply: CompletableDeferred<Unit>) : Op
        class Flush(val reply: CompletableDeferred<Unit>) : Op
        class Hydrate(val origin: String, val sessionId: String, val reply: CompletableDeferred<Hydration>) : Op
        class Close(val reply: CompletableDeferred<Unit>) : Op
    }

    private suspend fun <T> control(build: (CompletableDeferred<T>) -> Op): T {
        val reply = CompletableDeferred<T>()
        enqueue(build(reply))
        return reply.await()
    }

    private fun enqueue(op: Op) {
        synchronized(queue) {
            if (dead) {
                answer(op)
                return
            }
            queue.addLast(op)
            if (op !is Op.Write) controlsQueued++
        }
        signal.trySend(Unit)
    }

    /** Complete [op]'s reply with its fallback (the mirror answers "nothing"). */
    private fun answer(op: Op) {
        when (op) {
            is Op.Bind -> op.reply.complete(null)
            is Op.Wipe -> op.reply.complete(Unit)
            is Op.Rotate -> op.reply.complete(Unit)
            is Op.Flush -> op.reply.complete(Unit)
            is Op.Hydrate -> op.reply.complete(Hydration.None)
            is Op.Close -> op.reply.complete(Unit)
            is Op.Write -> Unit
        }
    }

    /**
     * The writer caught an Error (an Exception is a cache miss, handled where it happens): close
     * the DB, answer every waiting caller, and refuse everything after (security review M2).
     */
    private fun die(t: Throwable) {
        log("mirror writer stopped (${t.javaClass.simpleName})")
        val waiting = synchronized(queue) {
            dead = true
            controlsQueued = 0
            queue.toList().also { queue.clear() }
        }
        try {
            closeDb()
        } catch (_: Throwable) {
            // Nothing more to do for a cache.
        }
        boundOrigin = null
        // ta-hra R4: a queued Wipe is not only answered: its files go first (the keys went on
        // the wiping thread already).
        if (waiting.any { it is Op.Wipe }) deleteFiles()
        waiting.forEach(::answer)
    }

    private suspend fun loop() {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            die(t)
        }
    }

    private suspend fun run() {
        while (true) {
            signal.receive()
            // The batch window: let writes accumulate, unless a control op or a full batch waits.
            val started = System.nanoTime()
            while (true) {
                val (size, urgent) = synchronized(queue) { queue.size to (controlsQueued > 0) }
                if (size == 0 || urgent || size >= batchMaxOps) break
                val remaining = batchWindowMs - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) break
                withTimeoutOrNull(remaining) { signal.receive() }
            }
            drain()
        }
    }

    private fun drain() {
        while (true) {
            val batch = ArrayList<Op.Write>()
            var control: Op? = null
            synchronized(queue) {
                opEpoch = wipeEpoch
                while (queue.isNotEmpty() && batch.size < batchMaxOps) {
                    val op = queue.first()
                    if (op is Op.Write) {
                        batch += op
                        queue.removeFirst()
                    } else {
                        if (batch.isEmpty()) {
                            control = queue.removeFirst()
                            controlsQueued--
                        }
                        break
                    }
                }
            }
            if (batch.isNotEmpty()) commit(batch)
            control?.let(::handle)
            if (batch.isEmpty() && control == null) return
        }
    }

    private fun handle(op: Op) {
        try {
            handleNow(op)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // An Error (guarded() turns every Exception into a fallback): answer this caller, then die.
            if (op is Op.Wipe) deleteFiles() // R4: a wipe that failed half-way still deletes
            answer(op)
            throw t
        }
    }

    private fun handleNow(op: Op) {
        when (op) {
            is Op.Bind -> op.reply.complete(guarded(null) { bindNow(op.origin) })
            is Op.Wipe -> {
                guarded(Unit) { wipeNow() }
                op.reply.complete(Unit)
            }
            is Op.Rotate -> {
                guarded(Unit) { rotateNow() }
                op.reply.complete(Unit)
            }
            is Op.Flush -> op.reply.complete(Unit)
            is Op.Hydrate -> op.reply.complete(guarded(Hydration.None) { hydrateNow(op.origin, op.sessionId) })
            is Op.Close -> {
                closeDb()
                op.reply.complete(Unit)
            }
            is Op.Write -> error("writes are batched")
        }
    }

    /** Any failure of the mirror is a cache miss, never a crash. */
    private inline fun <T> guarded(fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        log("mirror operation failed (${e.javaClass.simpleName})")
        fallback
    }

    // ------------------------------------------------------------------
    // Actor-side operations
    // ------------------------------------------------------------------

    private fun bindNow(origin: String): MirrorIndex? {
        closeDb()
        boundOrigin = null
        val key = originKeyOf(origin)
        val name = dbName(key)
        // One origin at a time (§8.3): any other origin's transcripts go now.
        for (other in dbFactory.existing()) if (other != name) dbFactory.delete(other)
        val dataKey = when (val loaded = keyStore.load()) {
            is MirrorKeyStore.Loaded.Present -> loaded.key
            // Nothing on disk can be read without the key it was sealed under (§8.2).
            MirrorKeyStore.Loaded.Absent -> {
                dbFactory.delete(name)
                createKey() ?: return null
            }
            MirrorKeyStore.Loaded.Lost -> {
                dbFactory.delete(name)
                keyStore.destroy()
                createKey() ?: return null
            }
            MirrorKeyStore.Loaded.Unavailable -> return null
        }
        try {
            openDb(name, key, dataKey)
        } finally {
            dataKey.wipe() // the cipher holds its own copy
        }
        boundOrigin = origin
        val d = dao!!
        val previousReducer = d.meta(META_REDUCER)
        if (previousReducer != reducerVersion) {
            // §2.4: a local base depends on the reducer that folded it. It stays displayable,
            // but its cursor goes, so the next attach fetches a fresh server base.
            if (previousReducer != null) {
                db!!.runInTransaction {
                    for (id in d.baseIdsWithOrigin(SessionBaseEntity.ORIGIN_LOCAL)) d.clearCursor(id)
                }
            }
            d.putMeta(MetaEntity(META_REDUCER, reducerVersion))
        }
        if (writes >= rotateAfterWrites) rotateNow() // leaves it unbound if a wipe landed
        val index = if (dao != null && wipeEpoch == opEpoch) readIndex() else null
        if (index == null || wipeEpoch != opEpoch) {
            // A wipe landed during this bind (R1): its index is the wiped sign-in's.
            closeDb()
            boundOrigin = null
            return null
        }
        return index
    }

    /**
     * [MirrorKeyStore.create], unless a wipe landed since the op in hand was dequeued (R1): then
     * the key just minted (after, or racing, the wipe's shred) is destroyed again and null is
     * returned. The epoch is read AFTER the key file is written: a wipe whose bump comes later
     * shreds that file itself.
     */
    private fun createKey(): MirrorDataKey? {
        val fresh = keyStore.create()
        if (wipeEpoch == opEpoch) return fresh
        fresh.wipe()
        keyStore.destroy()
        log("mirror key minted during a wipe: destroyed")
        return null
    }

    private fun openDb(name: String, key: String, dataKey: MirrorDataKey) {
        var database = dbFactory.open(name)
        var d = database.dao()
        val storedKeyId = try {
            d.meta(META_KEY_ID)
        } catch (e: Exception) {
            // Unopenable (corrupt) file: it is a cache.
            log("mirror open failed (${e.javaClass.simpleName})")
            database.close()
            dbFactory.delete(name)
            database = dbFactory.open(name)
            d = database.dao()
            null
        }
        val storedBlobVersion = if (storedKeyId == null) null else d.meta(META_BLOB_VERSION)
        if (storedKeyId != dataKey.id || storedBlobVersion != MirrorCipher.VERSION.toString()) {
            // Sealed under another key or another blob format (or a fresh file): nothing in it
            // is readable. Start over.
            if (storedKeyId != null) {
                database.close()
                dbFactory.delete(name)
                database = dbFactory.open(name)
                d = database.dao()
            }
            d.putMeta(MetaEntity(META_KEY_ID, dataKey.id))
            d.putMeta(MetaEntity(META_BLOB_VERSION, MirrorCipher.VERSION.toString()))
            d.putMeta(MetaEntity(META_WRITES, "0"))
            d.putMeta(MetaEntity(META_SCHEMA, SCHEMA_VERSION.toString()))
        }
        db = database
        dao = d
        cipher = MirrorCipher(dataKey.bytes)
        originKey = key
        writes = d.meta(META_WRITES)?.toLongOrNull() ?: 0L
    }

    private fun readIndex(): MirrorIndex {
        val d = dao!!
        val c = cipher!!
        val sessions = ArrayList<StoredSession>()
        for (row in d.sessionRows()) {
            val json = try {
                c.openText(row.blob, aad(TABLE_SESSION_ROW, row.sessionId, ""))
            } catch (_: MirrorBlobException) {
                d.deleteSessionRow(row.sessionId)
                continue
            }
            sessions += StoredSession(row.sessionId, json, row.pinned, row.lastOpenedAt, row.goneFromServer)
        }
        val cursors = HashMap<String, Long>()
        for (state in d.syncStates()) state.cursor?.let { cursors[state.sessionId] = it }
        return MirrorIndex(sessions, cursors)
    }

    private fun closeDb() {
        try {
            db?.close()
        } catch (_: Exception) {
            // Closing a cache.
        }
        db = null
        dao = null
        cipher = null
    }

    private fun wipeNow() {
        closeDb()
        boundOrigin = null
        for (name in dbFactory.existing()) dbFactory.delete(name)
        keyStore.destroy()
    }

    /** Delete the DB, mint a new data key, reopen empty for the same origin (§8.1 rotation). */
    private fun rotateNow() {
        val origin = boundOrigin ?: return
        closeDb()
        val key = originKeyOf(origin)
        dbFactory.delete(dbName(key))
        keyStore.deleteDataKey()
        val fresh = createKey() ?: run {
            boundOrigin = null // unbound until the next bind: later writes land nowhere
            return
        }
        try {
            openDb(dbName(key), key, fresh)
        } finally {
            fresh.wipe()
        }
    }

    private fun hydrateNow(origin: String, sessionId: String): Hydration {
        if (origin != boundOrigin) return Hydration.None
        val d = dao ?: return Hydration.None
        val c = cipher ?: return Hydration.None
        return try {
            // Inside the try (verifier F3): a row that cannot even be read (e.g. a blob too big
            // for the cursor window) is Corrupt, not "no copy", so the restored cursor goes too.
            beforeHydrateRead?.invoke()
            val base = d.base(sessionId) ?: return Hydration.None
            val state = JsCodec.parse(c.openCompressed(base.state, baseAad(sessionId, base.throughSeq, base.origin))) as JsObj
            val details = LinkedHashMap<String, JsObj>()
            for (row in d.turnDetails(sessionId)) {
                details[row.turnId] = JsCodec.parse(c.openText(row.payload, aad(TABLE_TURN_DETAIL, sessionId, row.turnId))) as JsObj
            }
            val tail = d.events(sessionId).map { row ->
                JsCodec.parse(c.openText(row.payload, aad(TABLE_JOURNAL_EVENT, sessionId, row.seq.toString()))) as JsObj
            }
            val sync = d.syncState(sessionId)
            Hydration.Loaded(
                HydratedSession(
                    sessionId = sessionId,
                    base = state,
                    throughSeq = base.throughSeq,
                    trimmedBefore = base.trimmedBefore,
                    origin = base.origin,
                    details = details,
                    tail = tail,
                    cursor = sync?.cursor,
                    lastVerifiedAt = sync?.lastVerifiedAt,
                ),
            )
        } catch (e: Exception) {
            // MirrorBlobException (tamper, wrong key), a parse error, a non-object: drop it.
            log("mirror session unreadable (${e.javaClass.simpleName})")
            guarded(Unit) { db!!.runInTransaction { dropNow(d, sessionId) } }
            Hydration.Corrupt
        }
    }

    private fun commit(batch: List<Op.Write>) {
        val database = db ?: return
        val d = dao ?: return
        val c = cipher ?: return
        val origin = boundOrigin ?: return
        var sealed = 0L
        try {
            database.runInTransaction {
                for (op in batch) {
                    if (op.origin != origin) continue
                    sealed += apply(d, c, op)
                }
                if (sealed > 0) d.putMeta(MetaEntity(META_WRITES, (writes + sealed).toString()))
            }
            writes += sealed
        } catch (e: Exception) {
            // A cache that cannot be written (disk full, corrupt file): start it over.
            log("mirror batch failed (${e.javaClass.simpleName})")
            guarded(Unit) { rotateNow() }
            return
        }
        if (writes >= rotateAfterWrites) guarded(Unit) { rotateNow() }
    }

    /** Apply one write inside the batch transaction. Returns the number of blobs sealed. */
    private fun apply(d: MirrorDao, c: MirrorCipher, op: Op.Write): Int = when (op) {
        is Op.State -> {
            val blob = sealWithinCaps(JsCodec.stringify(op.state), compressed = true) { c.sealCompressed(it, baseAad(op.sessionId, op.throughSeq, SessionBaseEntity.ORIGIN_SERVER)) }
            val previous = d.syncState(op.sessionId)
            if (blob == null || !roomFor(d, previous, blob.size)) {
                // Too big for one row, or the origin is full: keep no copy of this session at
                // all rather than an older one that looks current.
                dropNow(d, op.sessionId)
                return 0
            }
            d.upsertBase(
                SessionBaseEntity(
                    op.sessionId, op.throughSeq, op.trimmedBefore, SessionBaseEntity.ORIGIN_SERVER, reducerVersion, blob, op.at,
                ),
            )
            d.deleteEvents(op.sessionId)
            // Details the new state already holds in full are superseded (§2.3).
            for (turnId in d.turnDetailIds(op.sessionId)) if (turnId !in op.keepTurnIds) d.deleteTurnDetail(op.sessionId, turnId)
            d.upsertSyncState(SyncStateEntity(op.sessionId, op.throughSeq, op.at, LEVEL_FULL, blob.size.toLong()))
            1
        }
        is Op.Verified -> {
            // Deviation from §2.3's "cursor := throughSeq" (verifier F4): a stateless reply only
            // VERIFIES a persisted cursor it equals and never moves one, because a DB cursor that
            // differs (cleared by a seqless event or a gap, or absent) does not cover throughSeq.
            val state = d.syncState(op.sessionId)
            if (state?.cursor == op.throughSeq) d.setVerified(op.sessionId, op.at)
            0
        }
        is Op.Event -> appendEvent(d, c, op)
        is Op.Seqless -> {
            d.clearCursor(op.sessionId)
            0
        }
        is Op.TurnDetails -> {
            if (d.syncState(op.sessionId) == null) {
                0
            } else {
                val rows = op.turns.mapNotNull { (turnId, entry) ->
                    val payload = sealWithinCaps(JsCodec.stringify(entry.second), compressed = false) {
                        c.sealText(it, aad(TABLE_TURN_DETAIL, op.sessionId, turnId))
                    } ?: return@mapNotNull null // re-fetchable: simply not kept
                    TurnDetailEntity(op.sessionId, turnId, entry.first, payload, op.at)
                }
                d.upsertTurnDetails(rows)
                rows.size
            }
        }
        is Op.Sessions -> {
            val lastOpened = HashMap<String, Long?>()
            for (chunk in op.rows.map { it.sessionId }.chunked(500)) {
                for (row in d.sessionRowsById(chunk)) lastOpened[row.sessionId] = row.lastOpenedAt
            }
            if (op.full) d.markAllGone()
            val kept = op.rows.mapNotNull { row ->
                sealWithinCaps(row.json, compressed = false) { c.sealText(it, aad(TABLE_SESSION_ROW, row.sessionId, "")) }?.let { row to it }
            }
            d.upsertSessionRows(
                kept.map { (row, blob) ->
                    SessionRowEntity(
                        sessionId = row.sessionId,
                        blob = blob,
                        updatedAt = row.updatedAt,
                        lastMessageAt = row.lastMessageAt,
                        pinned = row.pinned,
                        runtimeArchived = row.runtimeArchived,
                        serverLastSeq = row.serverLastSeq,
                        goneFromServer = false,
                        lastOpenedAt = lastOpened[row.sessionId],
                    )
                },
            )
            kept.size
        }
        is Op.Opened -> {
            d.markOpened(op.sessionId, op.at)
            0
        }
        is Op.Drop -> {
            dropNow(d, op.sessionId)
            0
        }
        is Op.Checkpoint -> {
            val state = d.syncState(op.sessionId)
            val blob = if (state?.cursor != op.throughSeq) {
                null
            } else {
                sealWithinCaps(JsCodec.stringify(op.state), compressed = true) {
                    c.sealCompressed(it, baseAad(op.sessionId, op.throughSeq, SessionBaseEntity.ORIGIN_LOCAL))
                }
            }
            if (state == null || blob == null) {
                0 // the DB does not cover exactly this fold (or it is too big): keep the tail
            } else {
                val previous = d.base(op.sessionId)
                d.upsertBase(
                    SessionBaseEntity(
                        op.sessionId, op.throughSeq, previous?.trimmedBefore, SessionBaseEntity.ORIGIN_LOCAL, reducerVersion, blob, op.at,
                    ),
                )
                d.deleteEventsThrough(op.sessionId, op.throughSeq)
                d.upsertSyncState(SyncStateEntity(op.sessionId, op.throughSeq, state.lastVerifiedAt, LEVEL_FULL, blob.size.toLong()))
                1
            }
        }
    }

    /** The tail stays contiguous: only `seq == cursor + 1` is persisted (and moves the cursor). */
    private fun appendEvent(d: MirrorDao, c: MirrorCipher, op: Op.Event): Int {
        val state = d.syncState(op.sessionId) ?: return 0 // no base: nothing to extend
        val cursor = state.cursor ?: return 0 // not covered (seqless / gap): wait for the next state
        if (op.seq <= cursor) return 0 // duplicate
        if (op.seq != cursor + 1) {
            // The DB cannot cover this fold: stop claiming coverage.
            d.clearCursor(op.sessionId)
            return 0
        }
        val payload = sealWithinCaps(op.json, compressed = false) { c.sealText(it, aad(TABLE_JOURNAL_EVENT, op.sessionId, op.seq.toString())) }
        if (payload == null || d.totalBytes() + payload.size > maxOriginBytes) {
            // Cannot be covered (too big, or the origin is full): stop claiming coverage.
            d.clearCursor(op.sessionId)
            return 0
        }
        if (d.insertEvent(JournalEventEntity(op.sessionId, op.seq, op.type, op.ts, payload)) == -1L) return 0
        d.upsertSyncState(SyncStateEntity(op.sessionId, op.seq, state.lastVerifiedAt, state.level, state.bytes + payload.size))
        return 1
    }

    /** Seal [plaintext] unless it (or its sealed form) is over the per-blob caps. Null = refused. */
    private inline fun sealWithinCaps(plaintext: String, compressed: Boolean, seal: (String) -> ByteArray): ByteArray? {
        // UTF-8 is at least one byte per char: a string longer than the cap is over it.
        if (plaintext.length > maxBlobPlaintextBytes) return refused()
        if (!compressed && plaintext.toByteArray(Charsets.UTF_8).size > maxBlobPlaintextBytes) return refused()
        val blob = seal(plaintext)
        return if (blob.size > maxStoredBlobBytes) refused() else blob
    }

    private fun refused(): ByteArray? {
        log("mirror blob over the size cap: not stored")
        return null
    }

    /** May [sessionId]'s base (re)become [bytes] big under the per-origin caps? */
    private fun roomFor(d: MirrorDao, previous: SyncStateEntity?, bytes: Int): Boolean {
        if (previous == null && d.sessionCount() >= maxSessions) return false
        return d.totalBytes() - (previous?.bytes ?: 0L) + bytes <= maxOriginBytes
    }

    private fun dropNow(d: MirrorDao, sessionId: String) {
        d.deleteBase(sessionId)
        d.deleteEvents(sessionId)
        d.deleteTurnDetails(sessionId)
        d.deleteSyncState(sessionId)
    }

    private fun aad(table: String, sessionId: String, rowKey: String) = MirrorCipher.aad(originKey, table, sessionId, rowKey)

    private fun baseAad(sessionId: String, throughSeq: Long, origin: String) =
        aad(TABLE_SESSION_BASE, sessionId, "$throughSeq/$origin")

    companion object {
        const val SCHEMA_VERSION = 1
        const val LEVEL_FULL = "full"
        const val LEVEL_LIST = "list"
        const val META_KEY_ID = "data_key_id"
        const val META_WRITES = "writes"
        const val META_REDUCER = "reducer_version"
        const val META_SCHEMA = "schema_version"
        const val META_BLOB_VERSION = "blob_version"
        const val TABLE_SESSION_ROW = "session_row"
        const val TABLE_SESSION_BASE = "session_base"
        const val TABLE_JOURNAL_EVENT = "journal_event"
        const val TABLE_TURN_DETAIL = "turn_detail"

        /** First 16 hex of sha256(canonical origin) (§2.2): the DB file never names the server. */
        fun originKeyOf(origin: String): String =
            MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)

        fun dbName(originKey: String): String = "mirror-$originKey.db"

        /**
         * The rollback path (`mirrorEnabled=false`): a mirror left by a build that had it on is
         * deleted with its keys, so turning the mirror off never strands transcripts on disk.
         */
        fun purge(dbFactory: MirrorDbFactory, keyStore: MirrorKeyStore) {
            keyStore.destroy() // first: whatever is left of the files is unreadable from here on
            for (name in dbFactory.existing()) dbFactory.delete(name)
        }
    }
}
