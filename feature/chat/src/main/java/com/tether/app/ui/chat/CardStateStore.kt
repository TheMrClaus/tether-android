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
 * Identity. A card's state belongs to ONE request: its content fingerprint [contentFingerprint] =
 * ConsentGuard.fingerprint(origin = "", activeTurnId, request), i.e. the canonical request plus its
 * turn, WITHOUT the server origin. A socket drop and a reconnect to the same server keep it (the
 * origin-bound fingerprint the client checks is only computed at tap time); a request re-raised
 * under the same id with different content, or in another turn, gets a new one. The lazy row's key
 * carries it too (ChatItem.Approval / Question), so a new request never reuses a row's saved slot.
 *
 * Storage. Not in the lazy row (a row scrolled off screen, or off screen when the app is
 * backgrounded, loses its saved state: LazySaveableStateHolder.performSave) but here, one store per
 * chat screen, saved with the screen, keyed by that identity. Only indices and the operator's own
 * "Other" text are saved, never server text (a huge question cannot overflow the Bundle). What is NOT
 * stored: the "confirm the complete expansion" tick and the send latch (see ApprovalCard).
 *
 * Losing a record (the bounded store evicting it) only ever returns the card to its first state: every
 * requested permission ticked, which is the full expansion and needs the confirmation (I5).
 */

/** Unticked permissions of one grant card, by index into the requested lists. Default: nothing unticked. */
internal data class GrantSelection(val offRead: Set<Int> = emptySet(), val offWrite: Set<Int> = emptySet(), val networkOff: Boolean = false)

/**
 * One question card: the page, the picks (option indices in pick order) and "Other" text per prompt
 * index, the skipped prompt indices, and whether Submit was tried.
 */
internal data class QuestionSelection(
    val page: Int = 0,
    val picks: Map<Int, List<Int>> = emptyMap(),
    val other: Map<Int, String> = emptyMap(),
    val skipped: Set<Int> = emptySet(),
    val attempted: Boolean = false,
)

@Stable
internal class CardStateStore(
    grants: Map<String, GrantSelection> = emptyMap(),
    questions: Map<String, QuestionSelection> = emptyMap(),
) {
    private val grantStates = mutableStateMapOf<String, GrantSelection>().apply { putAll(grants) }
    private val questionStates = mutableStateMapOf<String, QuestionSelection>().apply { putAll(questions) }

    fun grant(contentFp: String): GrantSelection = grantStates[contentFp] ?: GrantSelection()

    fun setGrant(contentFp: String, value: GrantSelection) = put(grantStates, contentFp, value)

    fun question(contentFp: String): QuestionSelection = questionStates[contentFp] ?: QuestionSelection()

    fun setQuestion(contentFp: String, value: QuestionSelection) = put(questionStates, contentFp, value)

    /** Test seam: forget everything (what an eviction does to one record). */
    fun clear() {
        grantStates.clear()
        questionStates.clear()
    }

    private fun <T> put(map: MutableMap<String, T>, key: String, value: T) {
        map.remove(key)
        map[key] = value // newest last
        while (map.size > MAX_RECORDS) map.remove(map.keys.first())
    }

    fun encode(): String = buildJsonObject {
        put("g", buildJsonArray {
            grantStates.forEach { (fp, g) ->
                add(buildJsonArray { add(JsonPrimitive(fp)); add(ints(g.offRead)); add(ints(g.offWrite)); add(JsonPrimitive(g.networkOff)) })
            }
        })
        put("q", buildJsonArray {
            questionStates.forEach { (fp, q) ->
                add(
                    buildJsonArray {
                        add(JsonPrimitive(fp))
                        add(JsonPrimitive(q.page))
                        add(buildJsonObject { q.picks.forEach { (i, l) -> put(i.toString(), ints(l)) } })
                        add(buildJsonObject { q.other.forEach { (i, t) -> put(i.toString(), JsonPrimitive(t)) } })
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

        fun decode(raw: String): CardStateStore = runCatching {
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

/** The chat screen's store (ChatScreen provides one for the transcript and every run tab). */
internal val LocalCardStates = staticCompositionLocalOf<CardStateStore?> { null }

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

    /** Identity key: the request object itself (compared by ===) and its turn. */
    private class Key(val request: JsObj, val turnId: String) {
        override fun equals(other: Any?): Boolean = other is Key && other.request === request && other.turnId == turnId
        override fun hashCode(): Int = System.identityHashCode(request) * 31 + turnId.hashCode()
    }

    fun of(activeTurnId: String, request: JsObj): String = synchronized(cache) {
        cache.getOrPut(Key(request, activeTurnId)) { ConsentGuard.fingerprint("", activeTurnId, request) }
    }
}
