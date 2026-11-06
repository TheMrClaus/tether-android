package com.tether.app.client

import com.tether.app.protocol.TetherJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Where an upload is. */
enum class UploadPhase { Pending, Stored, RouteMissing, Failed }

/** One picked photo's upload, as the app-private ledger records it (never the bytes). */
data class UploadState(
    val id: String,
    val origin: String,
    val sessionId: String,
    val name: String,
    val mediaType: String,
    val bytes: Long,
    val phase: UploadPhase,
    /** PUT attempts started (R2: a route-missing answer counts only on the first). */
    val attempts: Int = 0,
    /** The pending record this upload belongs to once Send was tapped; null while still only picked. */
    val holder: String? = null,
    val holderKind: String? = null,
    val index: Int = 0,
    val reason: String? = null,
    val retryable: Boolean = false,
    val status: Int? = null,
    val sha256: String? = null,
)

/** What a pending record's uploads allow its frame to be right now. */
sealed interface UploadGate {
    /** The record has no uploads: its frame is whatever it always was. */
    data object None : UploadGate

    /** Every upload is stored: the frame carries these ids and NO inline bytes. */
    class Ready(val ids: List<String>) : UploadGate

    /** R2: an old server without the route took the first PUT as a 404/405: an idle send goes inline as it always did. */
    data object Inline : UploadGate

    /** Still uploading (or waiting for the network): the record is held, never sent. */
    data object Waiting : UploadGate

    /** An upload failed for good: the record is given up on, with [reason]. */
    class Failed(val reason: String, val retryable: Boolean) : UploadGate
}

enum class UploadRunResult { Done, Retry, Gone }

/**
 * ta-delivery A2: every picked photo's background upload. Each upload has a directory
 * `<root>/<uploadId>/` with `data.bin` (the decoded bytes, streamed by [AttachmentUploader]) and `meta.json`
 * (the ledger row), both written temp + fsync + atomic rename, so process death leaves either nothing or a
 * complete upload that WorkManager resumes with the SAME id (the server's PUT is idempotent per id: the same
 * bytes again are a 200).
 *
 * Lifecycle rules (lead rulings R2, R4):
 *  - the worker uploads only a still-live upload for the origin that created it; a sign-out or a server switch
 *    cancels ([cancelAll], [cancelOtherOrigins]); nothing runs as an always-on service;
 *  - a spool file is deleted only once the pending record that owns it is gone ([sweepBound], called with the keys
 *    still referenced after the server acknowledged a send / queue-add, A1's rule), or on an explicit cancel of a
 *    photo that was only picked; NEVER because an upload finished;
 *  - an upload that was picked but never bound to a record does not survive a restart (the composer's staged set is
 *    memory only): such rows are removed when the ledger is opened.
 */
class AttachmentUploads(
    private val root: File,
    private val scheduler: UploadScheduler,
) {
    private val metas = LinkedHashMap<String, UploadState>()
    private val liveStaged = HashSet<String>()
    private val progressState = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Percent (0..99) of every upload in flight right now; an id leaves the map when its upload ends. */
    val progress: StateFlow<Map<String, Int>> = progressState.asStateFlow()

    private var uploader: AttachmentUploader? = null
    private var authority: (suspend () -> UploadAuthority)? = null
    private var onChange: (String) -> Unit = {}

    private val running = HashMap<String, okhttp3.Call>()

    init {
        root.mkdirs()
        for (dir in root.listFiles().orEmpty()) {
            val state = if (dir.isDirectory && ID.matches(dir.name)) readMeta(dir) else null
            // A leftover with no readable ledger row, or only picked (never sent): the staged set did not survive.
            if (state == null || state.holder == null) {
                dir.deleteRecursively()
                continue
            }
            metas[state.id] = state
        }
        // A tmp file of a write the process died in the middle of.
        for (dir in root.listFiles().orEmpty()) for (f in dir.listFiles().orEmpty()) if (f.name.endsWith(".tmp")) f.delete()
    }

    /** The client wires the network half once it exists (the hub is built before the client that owns the credential). */
    @Synchronized
    fun bind(uploader: AttachmentUploader, authority: suspend () -> UploadAuthority, onChange: (String) -> Unit) {
        this.uploader = uploader
        this.authority = authority
        this.onChange = onChange
    }

    /**
     * A photo was picked: spool its [bytes] and queue its upload. Null = not started (no server known, over the
     * per-file cap, an I/O error): the composer's inline path stays as it is. The returned id is the client UUID
     * the server stores it under.
     */
    @Synchronized
    fun begin(origin: String?, sessionId: String, name: String, mediaType: String, bytes: ByteArray): String? {
        if (origin == null || sessionId.isEmpty() || name.isEmpty() || mediaType.isEmpty()) return null
        if (bytes.isEmpty() || bytes.size > AttachmentUploader.MAX_FILE_BYTES) return null
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        return try {
            if (!dir.mkdirs() && !dir.isDirectory) throw IOException("upload dir")
            writeAtomic(File(dir, DATA), bytes)
            val state = UploadState(id, origin, sessionId, name, mediaType, bytes.size.toLong(), UploadPhase.Pending)
            writeMeta(dir, state)
            metas[id] = state
            liveStaged += id
            scheduler.enqueue(id)
            id
        } catch (_: IOException) {
            dir.deleteRecursively()
            metas.remove(id)
            null
        }
    }

    @Synchronized
    fun state(id: String): UploadState? = metas[id]

    @Synchronized
    fun isEmpty(): Boolean = metas.isEmpty()

    /** True while [id] is a photo that was picked and not yet handed to a pending record. */
    @Synchronized
    fun isStaged(id: String): Boolean = id in liveStaged && metas[id]?.holder == null

    /** Percent of [holder]'s uploads that reached the server (bytes-weighted), or null when none is still uploading. */
    @Synchronized
    fun percentFor(holder: String): Int? {
        val mine = metas.values.filter { it.holder == holder }
        if (mine.none { it.phase == UploadPhase.Pending }) return null
        val total = mine.sumOf { it.bytes }.coerceAtLeast(1L)
        val done = mine.sumOf { m ->
            when (m.phase) {
                UploadPhase.Pending -> m.bytes * (progressState.value[m.id] ?: 0) / 100
                else -> m.bytes
            }
        }
        return (done * 100 / total).toInt().coerceIn(0, 99)
    }

    /** Percent of one upload (0..100); 100 once stored; null for an unknown id. */
    @Synchronized
    fun percent(id: String): Int? = when (metas[id]?.phase) {
        null -> null
        UploadPhase.Pending -> progressState.value[id] ?: 0
        else -> 100
    }

    /**
     * Send was tapped: [ids] (in the message's attachment order) now belong to the pending record [holder]. False
     * (nothing bound) when an id is unknown, belongs to another server or session, or is already another record's:
     * the caller then sends inline.
     */
    @Synchronized
    fun bind(ids: List<String>, holder: String, kind: String, origin: String?, sessionId: String): Boolean {
        if (ids.isEmpty() || ids.size > AttachmentUploader.MAX_MESSAGE_FILES || ids.toSet().size != ids.size) return false
        val states = ids.map { metas[it] ?: return false }
        if (states.any { it.holder != null || it.origin != origin || it.sessionId != sessionId }) return false
        if (states.sumOf { it.bytes } > AttachmentUploader.MAX_MESSAGE_BYTES) return false
        ids.forEachIndexed { index, id ->
            val bound = metas.getValue(id).copy(holder = holder, holderKind = kind, index = index)
            metas[id] = bound
            liveStaged -= id
            runCatching { writeMeta(File(root, id), bound) }
        }
        return true
    }

    /** The send did not reach the wire and was withdrawn (the composer keeps its photos): [holder]'s uploads are the composer's again. */
    @Synchronized
    fun unbind(holder: String) {
        for (state in metas.values.filter { it.holder == holder }) {
            val free = state.copy(holder = null, holderKind = null, index = 0)
            metas[state.id] = free
            liveStaged += state.id
            runCatching { writeMeta(File(root, state.id), free) }
        }
    }

    /** What [holder]'s uploads allow its frame to be now (see [UploadGate]). */
    @Synchronized
    fun gate(holder: String): UploadGate {
        val mine = metas.values.filter { it.holder == holder }.sortedBy { it.index }
        if (mine.isEmpty()) return UploadGate.None
        mine.firstOrNull { it.phase == UploadPhase.Failed }?.let { return UploadGate.Failed(it.reason ?: DEFAULT_FAILURE, it.retryable) }
        if (mine.any { it.phase == UploadPhase.RouteMissing }) {
            // R2 holds for an idle send only; a queued message needs ids, and an old server cannot take them.
            return if (mine.first().holderKind == PendingInput.KIND_SEND) UploadGate.Inline
            else UploadGate.Failed("The server does not accept uploaded attachments yet.", false)
        }
        if (mine.any { it.phase == UploadPhase.Pending }) return UploadGate.Waiting
        return UploadGate.Ready(mine.map { it.id })
    }

    /** The ids bound to [holder], in order (empty: none). */
    @Synchronized
    fun idsFor(holder: String): List<String> = metas.values.filter { it.holder == holder }.sortedBy { it.index }.map { it.id }

    /**
     * A photo only picked (never sent) was removed from the composer: its work is cancelled and its bytes deleted,
     * and a stored copy is deleted from the server too (best effort). A bound upload belongs to its record: untouched.
     */
    fun cancelStaged(id: String, scope: kotlinx.coroutines.CoroutineScope? = null) {
        val state = synchronized(this) {
            val s = metas[id] ?: return
            if (s.holder != null) return
            removeLocked(id)
            s
        }
        scheduler.cancel(id)
        cancelCall(id)
        if (state.phase == UploadPhase.Stored) remoteDelete(state, scope)
        notifyChange(id)
    }

    /** Sign-out: every upload's work is cancelled and every file deleted (the server's own sweep collects what it holds). */
    fun cancelAll() {
        val ids = synchronized(this) {
            val all = metas.keys.toList()
            all.forEach { removeLocked(it) }
            // Anything left in the directory (a write that raced) goes too.
            root.listFiles().orEmpty().forEach { it.deleteRecursively() }
            all
        }
        scheduler.cancelAll()
        ids.forEach(::cancelCall)
        ids.forEach(::notifyChange)
    }

    /**
     * A move to another server: work for an origin other than [origin] is cancelled. A bound upload keeps its
     * ledger row as a failed one (so its set-aside record can never go out inline by accident) without its bytes.
     */
    fun cancelOtherOrigins(origin: String?) {
        val changed = ArrayList<String>()
        synchronized(this) {
            for (state in metas.values.toList()) {
                if (state.origin == origin) continue
                if (state.holder == null) removeLocked(state.id) else failLocked(state.id, CANCELLED, retryable = false)
                changed += state.id
            }
        }
        changed.forEach { scheduler.cancel(it); cancelCall(it); notifyChange(it) }
    }

    /**
     * Delete the uploads of every record that is gone: [referenced] are the keys any record (disk slot, memory,
     * set-aside) still holds. Called after the server acknowledged a send / queue-add, or the record was given up on.
     * Returns how many uploads were removed.
     */
    fun sweepBound(referenced: Set<String>): Int {
        val gone = synchronized(this) {
            val doomed = metas.values.filter { it.holder != null && it.holder !in referenced }.map { it.id }
            doomed.forEach { removeLocked(it) }
            doomed
        }
        gone.forEach { scheduler.cancel(it); cancelCall(it) }
        return gone.size
    }

    /**
     * WorkManager's entry (and the tests'): upload [id] once. [Done]: settled (stored, failed, route missing);
     * [Retry]: try again later with the same id; [Gone]: cancelled or unknown.
     */
    suspend fun run(id: String): UploadRunResult {
        val net = synchronized(this) { uploader to authority }
        val uploader = net.first
        val authorityProvider = net.second
        if (uploader == null || authorityProvider == null) return UploadRunResult.Retry
        val state = synchronized(this) { metas[id] } ?: return UploadRunResult.Gone
        if (state.phase != UploadPhase.Pending) return UploadRunResult.Done
        // R4: a photo that is neither staged in this process nor a pending record's is an orphan.
        val alive = synchronized(this) { state.holder != null || id in liveStaged }
        if (!alive) {
            cancelStaged(id)
            return UploadRunResult.Gone
        }
        val file = File(File(root, id), DATA)
        val auth = authorityProvider()
        // Only a PUT that is really about to start counts as an attempt (R2's "first PUT", the retry cap).
        val putting = auth is UploadAuthority.Paired && auth.origin == state.origin
        val attempt = synchronized(this) {
            val current = metas[id] ?: return UploadRunResult.Gone
            if (!putting) return@synchronized current.attempts
            val next = current.copy(attempts = current.attempts + 1)
            metas[id] = next
            runCatching { writeMeta(File(root, id), next) }
            next.attempts
        }
        val outcome = try {
            withContext(Dispatchers.IO) {
                uploader.upload(
                    authority = auth,
                    expectedOrigin = state.origin,
                    sessionId = state.sessionId,
                    uploadId = id,
                    name = state.name,
                    mediaType = state.mediaType,
                    file = file,
                    onProgress = { sent, total -> setProgress(id, if (total <= 0) 0 else (sent * 100 / total).toInt().coerceIn(0, 99)) },
                    registerCall = { call -> synchronized(this@AttachmentUploads) { running[id] = call } },
                )
            }
        } catch (e: CancellationException) {
            synchronized(this) { running.remove(id) }
            throw e
        } finally {
            synchronized(this) { running.remove(id) }
        }
        val result = apply(id, attempt, outcome)
        notifyChange(id)
        return result
    }

    private fun apply(id: String, attempt: Int, outcome: UploadOutcome): UploadRunResult = synchronized(this) {
        val current = metas[id] ?: return UploadRunResult.Gone
        when (outcome) {
            is UploadOutcome.Stored -> {
                settleLocked(id, current.copy(phase = UploadPhase.Stored, sha256 = outcome.sha256, reason = null))
                UploadRunResult.Done
            }
            UploadOutcome.RouteMissing -> {
                // R2: only the FIRST PUT's 404/405 means "an old server"; later it is a plain failure.
                if (attempt == 1) settleLocked(id, current.copy(phase = UploadPhase.RouteMissing, status = 404))
                else settleLocked(id, current.copy(phase = UploadPhase.Failed, reason = "The server stopped accepting uploads.", retryable = true, status = 404))
                UploadRunResult.Done
            }
            is UploadOutcome.Refused -> {
                settleLocked(id, current.copy(phase = UploadPhase.Failed, reason = outcome.reason, retryable = outcome.retryable, status = outcome.status))
                UploadRunResult.Done
            }
            is UploadOutcome.Transient -> {
                if (attempt >= MAX_ATTEMPTS) {
                    settleLocked(id, current.copy(phase = UploadPhase.Failed, reason = outcome.reason, retryable = true))
                    UploadRunResult.Done
                } else {
                    UploadRunResult.Retry
                }
            }
            UploadOutcome.NotReady -> UploadRunResult.Retry
            UploadOutcome.WrongServer -> {
                if (current.holder == null) removeLocked(id) else failLocked(id, CANCELLED, retryable = false)
                UploadRunResult.Gone
            }
        }
    }

    private fun settleLocked(id: String, next: UploadState) {
        metas[id] = next
        runCatching { writeMeta(File(root, id), next) }
        progressState.update { it - id }
    }

    /** A bound upload that is over without a result: keep the ledger row (no bytes) so the gate reads Failed. */
    private fun failLocked(id: String, reason: String, retryable: Boolean) {
        val current = metas[id] ?: return
        File(File(root, id), DATA).delete()
        settleLocked(id, current.copy(phase = UploadPhase.Failed, reason = reason, retryable = retryable))
    }

    private fun removeLocked(id: String) {
        metas.remove(id)
        liveStaged -= id
        progressState.update { it - id }
        File(root, id).deleteRecursively()
    }

    private fun cancelCall(id: String) {
        val call = synchronized(this) { running.remove(id) }
        call?.cancel()
    }

    private fun setProgress(id: String, percent: Int) {
        progressState.update { current -> if (current[id] == percent) current else current + (id to percent) }
    }

    private fun notifyChange(id: String) {
        val cb = synchronized(this) { onChange }
        cb(id)
    }

    private fun remoteDelete(state: UploadState, scope: kotlinx.coroutines.CoroutineScope?) {
        val net = synchronized(this) { uploader to authority }
        val uploader = net.first ?: return
        val provider = net.second ?: return
        scope?.launch {
            runCatching { withContext(Dispatchers.IO) { uploader.delete(provider(), state.origin, state.sessionId, state.id) } }
        }
    }

    private fun writeMeta(dir: File, s: UploadState) {
        val json = buildJsonObject {
            put("id", s.id)
            put("origin", s.origin)
            put("sessionId", s.sessionId)
            put("name", s.name)
            put("mediaType", s.mediaType)
            put("bytes", s.bytes)
            put("phase", s.phase.name)
            put("attempts", s.attempts)
            put("holder", s.holder)
            put("holderKind", s.holderKind)
            put("index", s.index)
            put("reason", s.reason)
            put("retryable", s.retryable)
            put("status", s.status)
            put("sha256", s.sha256)
        }
        writeAtomic(File(dir, META), TetherJson.encodeToString(JsonObject.serializer(), json).toByteArray(Charsets.UTF_8))
    }

    private fun readMeta(dir: File): UploadState? = try {
        val o = TetherJson.parseToJsonElement(File(dir, META).readText()) as JsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val id = str("id") ?: return null
        if (id != dir.name) return null
        UploadState(
            id = id,
            origin = str("origin") ?: return null,
            sessionId = str("sessionId") ?: return null,
            name = str("name") ?: return null,
            mediaType = str("mediaType") ?: return null,
            bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull ?: return null,
            phase = UploadPhase.entries.firstOrNull { it.name == str("phase") } ?: return null,
            attempts = (o["attempts"] as? JsonPrimitive)?.intOrNull ?: 0,
            holder = str("holder"),
            holderKind = str("holderKind"),
            index = (o["index"] as? JsonPrimitive)?.intOrNull ?: 0,
            reason = str("reason"),
            retryable = (o["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            status = (o["status"] as? JsonPrimitive)?.intOrNull,
            sha256 = str("sha256"),
        )
    } catch (_: Exception) {
        null
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DATA = "data.bin"
        const val META = "meta.json"
        const val MAX_ATTEMPTS = 5
        const val CANCELLED = "The upload was cancelled."
        /** No reason sentence: the label already says "Not sent — the upload failed." (§4.1b B.1, AW.4). */
        const val DEFAULT_FAILURE = ""
        private val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
