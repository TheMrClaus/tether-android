package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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

/*
 * T6.3 round 3: where an attention card's state lives, and under which identity.
 *
 * Identity. A card's state belongs to ONE request of ONE session: ConsentGuard.cardIdentity =
 * sha256(canonical {"kind":"card", sessionId, activeTurnId, request}), WITHOUT the server origin. A socket drop and a reconnect to the same server keep it (the
 * origin-bound fingerprint the client checks is only computed at tap time); a request re-raised
 * under the same id with different content, or in another turn, gets a new one. The lazy row's key
 * carries it too (ChatItem.Approval / Question), so a new request never reuses a row's saved slot.
 *
 * Storage. Not in the lazy row (a row scrolled off screen, or off screen when the app is
 * backgrounded, loses its saved state: LazySaveableStateHolder.performSave) but here, one store per
 * chat screen, saved with the screen, keyed by that identity. Only indices and the operator's own
 * "Other" text are saved, never server text (a huge question cannot overflow the Bundle). What is NOT
 * stored: the "Confirm these permissions" tick and the send latch (see ApprovalCard).
 *
 * Round 4: every grant needs that unsaved confirmation, keyed on the record's generation, so a lost,
 * evicted, created or changed record always also clears it: no state loss can become a silent grant.
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
) {
    constructor() : this(emptyMap(), emptyMap())

    private val grantStates = mutableStateMapOf<String, GrantSelection>().apply { putAll(grants) }
    private val questionStates = mutableStateMapOf<String, QuestionSelection>().apply { putAll(questions) }

    // Round 4: every write, creation or loss of a grant record gets a new generation; the card keys
    // its (unsaved) confirmation on it, so no change to what is ticked can keep an old confirmation.
    private var counter = 0L
    private val generations = mutableStateMapOf<String, Long>().apply { grants.keys.forEach { put(it, ++counter) } }

    internal fun grant(contentFp: String): GrantSelection = grantStates[contentFp] ?: GrantSelection()

    /** The record's generation; 0 = no record (never written, or lost). */
    internal fun grantGeneration(contentFp: String): Long = generations[contentFp] ?: 0L

    internal fun setGrant(contentFp: String, value: GrantSelection) {
        put(grantStates, contentFp, value)
        generations[contentFp] = ++counter
        // An evicted record's generation goes too (0: "no record"), so its card's confirmation resets.
        generations.keys.filter { it !in grantStates }.forEach { generations.remove(it) }
    }

    internal fun question(contentFp: String): QuestionSelection = questionStates[contentFp] ?: QuestionSelection()

    internal fun setQuestion(contentFp: String, value: QuestionSelection) =
        put(questionStates, contentFp, value.copy(other = value.other.mapValues { it.value.take(ConsentGuard.MAX_OTHER_CHARS) }))

    /** Test seam: forget everything (what an eviction does to a record). */
    internal fun clear() {
        grantStates.clear()
        questionStates.clear()
        generations.clear()
        order.clear()
    }

    // Write order per map (a snapshot map is NOT insertion-ordered): the eviction is oldest first.
    private val order = HashMap<MutableMap<*, *>, LinkedHashSet<String>>()

    private fun <T> put(map: MutableMap<String, T>, key: String, value: T) {
        val keys = order.getOrPut(map) { LinkedHashSet(map.keys) }
        keys.remove(key)
        keys.add(key) // newest last
        map[key] = value
        while (keys.size > MAX_RECORDS) {
            val oldest = keys.first()
            keys.remove(oldest)
            map.remove(oldest)
        }
    }

    /** [map]'s records oldest first (so a restored store evicts in the same order). */
    private fun <T> ordered(map: Map<String, T>): List<Pair<String, T>> {
        val keys: Collection<String> = order[map as MutableMap<*, *>] ?: map.keys
        return keys.mapNotNull { k -> map[k]?.let { v -> k to v } }
    }

    internal fun encode(): String = buildJsonObject {
        put("g", buildJsonArray {
            ordered(grantStates).forEach { (fp, g) ->
                add(buildJsonArray { add(JsonPrimitive(fp)); add(ints(g.offRead)); add(ints(g.offWrite)); add(JsonPrimitive(g.networkOff)) })
            }
        })
        put("q", buildJsonArray {
            ordered(questionStates).forEach { (fp, q) ->
                add(
                    buildJsonArray {
                        add(JsonPrimitive(fp))
                        add(JsonPrimitive(q.page))
                        add(buildJsonObject { q.picks.forEach { (i, l) -> put(i.toString(), ints(l)) } })
                        add(buildJsonObject { q.other.forEach { (i, t) -> put(i.toString(), JsonPrimitive(t.take(ConsentGuard.MAX_OTHER_CHARS))) } })
                        add(ints(q.skipped))
                        add(JsonPrimitive(q.attempted))
                    },
                )
            }
        })
    }.toString()

    companion object {
        /** Bound on remembered cards per kind (oldest first out; see the eviction note above). */
        const val MAX_RECORDS = 64

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
            CardStateStore(grants, questions)
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
