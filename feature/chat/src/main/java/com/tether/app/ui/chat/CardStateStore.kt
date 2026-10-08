package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.client.ConsentGuard
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** [s] cut to at most [max] UTF-16 units without splitting a surrogate pair (I-3). */
internal fun cutCodePoints(s: String, max: Int): String = ConsentGuard.cutCodePoints(s, max)

/*
 * T6.3: where an attention card's state lives, and under which identity.
 *
 * Identity. A card's state belongs to ONE request of ONE session: ConsentGuard.cardIdentity =
 * sha256(canonical {"kind":"card", sessionId, activeTurnId, request}), WITHOUT the server origin. A socket drop and a reconnect to the same server keep it (the
 * origin-bound fingerprint the client checks is only computed at tap time); a request re-raised
 * under the same id with different content, or in another turn, gets a new one. The lazy row's key
 * carries it too (ChatItem.Approval / Question), so a new request never reuses a row's saved slot.
 *
 * Storage. Not in the lazy row (a row scrolled off screen, or off screen when the app is
 * backgrounded, loses its saved state: LazySaveableStateHolder.performSave) but here: ONE store per
 * app window, created and saved by MainShell above the phone / expanded layout switch (round 4, H1)
 * and bound to the configured server (round 5, I-2), keyed by that identity; ChatScreen falls back to
 * its own only when nobody provides one. Only indices and the operator's own
 * "Other" text are saved, never server text (a huge question cannot overflow the Bundle). What is NOT
 * saved: the web's "exact" confirmation tick and the send latch (see ApprovalLocal; in memory only).
 *
 * A grant key re-reads the store at tap time (round 5, F1), so it sends the ticks as they are then.
 */

/**
 * Unticked permissions of one grant card, by CANONICAL index into the requested lists (a path's first
 * index, `read.indexOf(path)`: the reducer does not dedupe paths, and a path listed twice is one
 * permission). Default: nothing unticked.
 */
internal data class GrantSelection(val offRead: Set<Int> = emptySet(), val offWrite: Set<Int> = emptySet(), val networkOff: Boolean = false)

/**
 * One question card, per answer SLOT (ConsentGuard.questionSlots: prompts sharing a text share a
 * slot): the page, the picks as indices into the slot's label union (so a pick is a LABEL, whichever
 * page it was made on), the "Other" text (at most ConsentGuard.MAX_OTHER_CHARS), the skipped slots,
 * and whether Submit was tried.
 */
internal data class QuestionSelection(
    val page: Int = 0,
    val picks: Map<Int, List<Int>> = emptyMap(),
    val other: Map<Int, String> = emptyMap(),
    val skipped: Set<Int> = emptySet(),
    val attempted: Boolean = false,
)

@Stable
class CardStateStore internal constructor(
    grants: Map<String, GrantSelection> = emptyMap(),
    questions: Map<String, QuestionSelection> = emptyMap(),
    boundTo: String? = null,
) {
    constructor() : this(emptyMap(), emptyMap())

    // Write order, oldest first (a snapshot map iterates in hash order, not insertion order). Seeded
    // from the constructor's ordered maps (a decoded store keeps its saved order, L-2); declared
    // before the maps so it exists when they are filled.
    private val grantOrder = LinkedHashSet(grants.keys)
    private val questionOrder = LinkedHashSet(questions.keys)

    private val grantStates = mutableStateMapOf<String, GrantSelection>().apply { putAll(grants) }
    private val questionStates = mutableStateMapOf<String, QuestionSelection>().apply { putAll(questions) }

    /** The configured server this store's records belong to (I-2); a different one empties it. */
    internal var boundTo: String? = boundTo
        private set

    // Every write, creation or loss of a grant record gets a new generation; the card binds its
    // (unsaved) confirmation to the generation it was made at. Monotonic, never reused (I-6): a
    // record's generation is a fresh positive number per write, and "no record" is the NEGATIVE
    // eviction epoch, which moves on every eviction or clear, so a lost record never looks like the
    // state a confirmation was made in.
    private var counter = 0L
    private val generations = mutableStateMapOf<String, Long>().apply { grants.keys.forEach { put(it, ++counter) } }
    private var absentEpoch by androidx.compose.runtime.mutableLongStateOf(0L)

    // ta-4za3: the approval card's local state (the confirmation tick, the send latch, the overlay notice) is never
    // saved; it lives here, in memory, under the card's contentFp, shared by all the segments the host list draws it
    // in, so a segment scrolling out of composition clears nothing. Newest use last; the oldest go past MAX_RECORDS.
    private val locals = LinkedHashMap<String, ApprovalLocal>(16, 0.75f, true)

    internal fun local(contentFp: String): ApprovalLocal = synchronized(locals) {
        val held = locals.getOrPut(contentFp) { ApprovalLocal() }
        while (locals.size > MAX_RECORDS) locals.remove(locals.keys.first())
        held
    }

    internal fun grant(contentFp: String): GrantSelection = grantStates[contentFp] ?: GrantSelection()

    /** The record's generation: > 0 for a record, the (<= 0) eviction epoch when there is none. */
    internal fun grantGeneration(contentFp: String): Long = generations[contentFp] ?: -absentEpoch

    internal fun setGrant(contentFp: String, value: GrantSelection) {
        val evicted = put(grantStates, grantOrder, contentFp, value)
        generations[contentFp] = ++counter
        if (evicted.isNotEmpty()) {
            evicted.forEach { generations.remove(it) }
            absentEpoch += 1
        }
    }

    internal fun question(contentFp: String): QuestionSelection = questionStates[contentFp] ?: QuestionSelection()

    internal fun setQuestion(contentFp: String, value: QuestionSelection) {
        put(questionStates, questionOrder, contentFp, value.copy(other = value.other.mapValues { cutCodePoints(it.value, ConsentGuard.MAX_OTHER_CHARS) }))
        trimOtherBudget(keep = contentFp)
    }

    /**
     * I-5: the saved "Other" text across the store stays under [MAX_OTHER_TOTAL] characters; past it,
     * the OLDEST records' text goes first (never the card being typed in).
     */
    private fun trimOtherBudget(keep: String) {
        var total = questionStates.values.sumOf { q -> q.other.values.sumOf { it.length } }
        for (key in questionOrder.toList()) {
            if (total <= MAX_OTHER_TOTAL) break
            if (key == keep) continue
            val q = questionStates[key] ?: continue
            val size = q.other.values.sumOf { it.length }
            if (size == 0) continue
            questionStates[key] = q.copy(other = emptyMap())
            total -= size
        }
    }

    /**
     * I-2: the records belong to [serverUrl] (the CONFIGURED server, not the socket's origin, so a drop
     * keeps them); switching to another server empties the store.
     */
    fun bindTo(serverUrl: String?) {
        if (serverUrl == boundTo) return
        if (boundTo != null) clear()
        boundTo = serverUrl
    }

    /** Forget everything (a server switch; and what an eviction does to one record). */
    internal fun clear() {
        grantStates.clear()
        questionStates.clear()
        generations.clear()
        grantOrder.clear()
        questionOrder.clear()
        synchronized(locals) { locals.clear() }
        absentEpoch += 1
    }

    /** Write [value] as the newest record of [map]; returns the keys evicted past [MAX_RECORDS]. */
    private fun <T> put(map: MutableMap<String, T>, order: LinkedHashSet<String>, key: String, value: T): List<String> {
        order.remove(key)
        order.add(key) // newest last
        map[key] = value
        val evicted = ArrayList<String>()
        while (order.size > MAX_RECORDS) {
            val oldest = order.first()
            order.remove(oldest)
            map.remove(oldest)
            evicted.add(oldest)
        }
        return evicted
    }

    /** [map]'s records oldest first (so a restored store evicts in the same order). */
    private fun <T> ordered(map: Map<String, T>, order: Collection<String>): List<Pair<String, T>> =
        order.mapNotNull { k -> map[k]?.let { v -> k to v } }

    internal fun encode(): String = buildJsonObject {
        put("g", buildJsonArray {
            ordered(grantStates, grantOrder).forEach { (fp, g) ->
                add(buildJsonArray { add(JsonPrimitive(fp)); add(ints(g.offRead)); add(ints(g.offWrite)); add(JsonPrimitive(g.networkOff)) })
            }
        })
        put("q", buildJsonArray {
            ordered(questionStates, questionOrder).forEach { (fp, q) ->
                add(
                    buildJsonArray {
                        add(JsonPrimitive(fp))
                        add(JsonPrimitive(q.page))
                        add(buildJsonObject { q.picks.forEach { (i, l) -> put(i.toString(), ints(l)) } })
                        add(buildJsonObject { q.other.forEach { (i, t) -> put(i.toString(), JsonPrimitive(cutCodePoints(t, ConsentGuard.MAX_OTHER_CHARS))) } })
                        add(ints(q.skipped))
                        add(JsonPrimitive(q.attempted))
                    },
                )
            }
        })
        boundTo?.let { put("b", JsonPrimitive(it)) }
    }.toString()

    companion object {
        /** Bound on remembered cards per kind (oldest first out; see the eviction note above). */
        const val MAX_RECORDS = 64

        /** I-5: the saved "Other" text across every question record, in characters. */
        const val MAX_OTHER_TOTAL = 64_000

        private fun ints(values: Collection<Int>) = JsonArray(values.map { JsonPrimitive(it) })

        private fun intList(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.int }

        internal fun decode(raw: String): CardStateStore = runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            val grants = o["g"]!!.jsonArray.associate { e ->
                val a = e.jsonArray
                a[0].jsonPrimitive.content to GrantSelection(intList(a[1]).toSet(), intList(a[2]).toSet(), a[3].jsonPrimitive.content == "true")
            }
            val questions = o["q"]!!.jsonArray.associate { e ->
                val a = e.jsonArray
                a[0].jsonPrimitive.content to QuestionSelection(
                    page = a[1].jsonPrimitive.int,
                    picks = (a[2] as JsonObject).entries.associate { (k, v) -> k.toInt() to intList(v) },
                    other = (a[3] as JsonObject).entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content },
                    skipped = intList(a[4]).toSet(),
                    attempted = a[5].jsonPrimitive.content == "true",
                )
            }
            CardStateStore(grants, questions, (o["b"] as? JsonPrimitive)?.content)
        }.getOrElse { CardStateStore() } // an unreadable record is a fresh (fully ticked, confirm-needing) card

        val Saver: Saver<CardStateStore, String> = Saver(save = { it.encode() }, restore = { decode(it) })
    }
}

/**
 * The store in scope. Round 4 (H1): MainShell provides ONE, above the phone / expanded layout switch
 * and the no-session branch, so a rotation, a window resize or a session switch keeps it; ChatScreen
 * falls back to its own only when nobody provides one.
 */
val LocalCardStates = staticCompositionLocalOf<CardStateStore?> { null }

/** The store in scope, or one saved here when no screen provides it (tests, previews). */
@Composable
internal fun rememberCardStates(): CardStateStore =
    LocalCardStates.current ?: rememberSaveable(saver = CardStateStore.Saver) { CardStateStore() }

/**
 * The content fingerprint of a pending request (origin-free), cached per request OBJECT: the reducer
 * keeps an untouched request's identity across deltas, so SHA-256 runs once per request, not once
 * per transcript rebuild.
 */
internal object ContentFingerprints {
    private const val SIZE = 128
    private val cache = object : LinkedHashMap<Key, String>(SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean = size > SIZE
    }

    /** Identity key: the request object itself (compared by ===), its turn and its session. */
    private class Key(val request: JsObj, val turnId: String, val sessionId: String) {
        override fun equals(other: Any?): Boolean = other is Key && other.request === request && other.turnId == turnId && other.sessionId == sessionId
        override fun hashCode(): Int = (System.identityHashCode(request) * 31 + turnId.hashCode()) * 31 + sessionId.hashCode()
    }

    fun of(sessionId: String, activeTurnId: String, request: JsObj): String = synchronized(cache) {
        cache.getOrPut(Key(request, activeTurnId, sessionId)) { ConsentGuard.cardIdentity(sessionId, activeTurnId, request) }
    }
}
