package com.tether.app.client

import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * ta-2uq (T8.1 slice 3): the operator's hand-added model ids (issue #45, lib/draft-form.ts
 * addCustomModelPref). They are client-only: kept in the draft preferences of ONE server origin
 * ([com.tether.app.ui.prefs.DraftStore] keys them per origin), merged into that server's catalog and
 * sent as the create's `model` like a discovered pick.
 *
 * The web accepts any trimmed, non-empty string. The app holds an id to the server's own bound and
 * to what can be shown as itself, so a stored id can never send a value the server refuses or draw
 * as another:
 * - trimmed (ECMAScript `trim`, as the web), not empty;
 * - at most [MAX_BYTES] UTF-8 bytes (protocol-validate.mjs: `create.model` is `isBoundedString(…, 200)`);
 * - one word of visible characters: no whitespace or separator, no control or format character
 *   (so no bidi control, mark or zero-width character), no default-ignorable or blank-looking code
 *   point ([LabelText.invisibleCodePoint]), no lone surrogate;
 * - never the legacy group sentinel ([LEGACY_GROUP_VALUE]).
 *
 * The same rule cleans what is read back from storage ([sanitize]): an invalid stored id is dropped,
 * never drawn and never sent.
 */
object CustomModelId {
    /** protocol-validate.mjs: `create.model` must be a bounded string (200 UTF-8 bytes). */
    const val MAX_BYTES = 200

    /** Most custom ids kept per catalog row, and most rows that carry any (the catalog's own bound). */
    const val MAX_PER_ENTRY = LabelText.MAX_ITEMS
    const val MAX_ENTRIES = LabelText.MAX_ITEMS

    /** Why an id cannot be added (null from [problem]: it can). */
    enum class Problem(val copy: String) {
        Empty(""),
        TooLong("A model id is at most 200 bytes."),
        NotOneWord("A model id is one word of visible characters (no spaces, line breaks or hidden characters)."),
        Reserved("That id is reserved."),
    }

    /** What the web stores for [raw] (its trim). */
    fun normalize(raw: String): String = jsTrim(raw)

    /** Null when [raw] (trimmed) may be stored and sent; otherwise why not. */
    fun problem(raw: String): Problem? {
        val id = normalize(raw)
        if (id.isEmpty()) return Problem.Empty
        if (id.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Problem.TooLong
        var i = 0
        while (i < id.length) {
            val cp = id.codePointAt(i)
            i += Character.charCount(cp)
            if (!visible(cp)) return Problem.NotOneWord
        }
        if (id == LEGACY_GROUP_VALUE) return Problem.Reserved
        return null
    }

    fun valid(raw: String): Boolean = problem(raw) == null

    private fun visible(cp: Int): Boolean {
        val type = Character.getType(cp)
        if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt()) return false
        if (type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt()) return false
        if (type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.SPACE_SEPARATOR.toInt()) return false
        return !LabelText.invisibleCodePoint(cp)
    }

    /**
     * The draft preferences' `customModels` as the app keeps them: an object of row key → list of
     * valid, distinct ids (stored trimmed), each list and the map bounded. Anything else (not an
     * object, a key too long, a non-string or invalid id) is dropped. Null when nothing is left.
     */
    fun sanitize(customModels: JsValue?): JsObj? {
        val map = customModels as? JsObj ?: return null
        val out = LinkedHashMap<String, JsValue>()
        for ((key, value) in map.entries) {
            if (out.size >= MAX_ENTRIES) break
            if (key.isEmpty() || key.length > NewSessionGuard.KEY_MAX) continue
            val ids = (value as? JsArr)?.asSequence().orEmpty()
                .mapNotNull { (it as? JsStr)?.value?.let(::normalize) }
                .filter(::valid)
                .distinct()
                .take(MAX_PER_ENTRY)
                .toList()
            if (ids.isNotEmpty()) out[key] = JsArr.of(ids.map(::JsStr))
        }
        return if (out.isEmpty()) null else JsObj.from(out)
    }

    /** [sanitize] as plain lists (the browser's Custom section). */
    fun asMap(customModels: JsValue?): Map<String, List<String>> =
        sanitize(customModels)?.entries?.associate { (k, v) -> k to (v as JsArr).mapNotNull { (it as? JsStr)?.value } }.orEmpty()

    /** [preferences] with its `customModels` replaced by [sanitize]'s (removed when nothing is left). */
    fun cleanPreferences(preferences: JsObj): JsObj {
        val raw = preferences["customModels"] ?: return preferences
        val clean = sanitize(raw)
        if (clean == raw) return preferences
        return if (clean == null) preferences.remove("customModels") else preferences.put("customModels", clean)
    }
}

/** ta-2uq: what became of a model browser's Retry or Refresh. */
enum class ProviderRefreshResult {
    /** `refresh-providers` went out on the live socket for that row. */
    Sent,

    /** A refresh of that row is still in flight on this socket, or one went out a moment ago: nothing sent. */
    Throttled,

    /** No live, handshaken socket, or not the one the browser was drawn for: nothing sent. */
    NotConnected,

    /** The row is not in the catalog the current socket delivered: nothing sent. */
    NotOffered,
}

/**
 * ta-2uq: the client's own throttle for `refresh-providers`. The server answers every one it gets
 * by re-running that provider's model fetch (server.mjs `providerCatalog.refresh`, fire-and-forget,
 * no rate limit), so the client keeps it to one in flight per row and ignores repeated taps:
 *
 * - **one in flight per row, per socket**: a refresh is in flight from the send until the catalog
 *   this socket delivers shows the row settled again (its `fetchedAt` changed, or the row is gone),
 *   or [inFlightTimeoutMs] passes with no word (a row the server would not refresh: unavailable);
 * - **taps are debounced**: a row refreshed less than [debounceMs] ago is not refreshed again, even
 *   once the first has settled;
 * - **a socket change drops it**: [clear] forgets every flight (the reply of a refresh sent on a
 *   socket that is gone never arrives here; the new socket's own catalog is what counts), and
 *   nothing is ever queued or resent on a later socket.
 *
 * Pure bookkeeping, no I/O and no clock of its own; RealTetherClient calls it under its lock.
 */
class ProviderRefreshThrottle(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val inFlightTimeoutMs: Long = IN_FLIGHT_TIMEOUT_MS,
) {
    private class Flight(val epoch: Long, val sentAt: Long, val fetchedAt: Long?, var settled: Boolean = false)

    private val flights = HashMap<String, Flight>()

    /** True when [key] may be refreshed now on socket [epoch] (and records it as sent at [now]). */
    fun admit(key: String, epoch: Long, now: Long, entry: ProviderCatalogEntry): Boolean {
        val last = flights[key]
        if (last != null && last.epoch == epoch) {
            if (!last.settled && now - last.sentAt < inFlightTimeoutMs) return false
            if (now - last.sentAt < debounceMs) return false
        }
        flights[key] = Flight(epoch, now, entry.fetchedAt)
        return true
    }

    /** A catalog delivered on socket [epoch]: every row it shows settled again ends its flight. */
    fun onCatalog(epoch: Long, entries: List<ProviderCatalogEntry>) {
        for ((key, flight) in flights) {
            if (flight.epoch != epoch || flight.settled) continue
            val entry = entries.firstOrNull { it.key == key }
            if (entry == null || entry.fetchedAt != flight.fetchedAt) flight.settled = true
        }
    }

    /** True while a refresh of [key] sent on socket [epoch] has not settled (tests, and the browser's spinner). */
    fun inFlight(key: String, epoch: Long, now: Long): Boolean {
        val flight = flights[key] ?: return false
        return flight.epoch == epoch && !flight.settled && now - flight.sentAt < inFlightTimeoutMs
    }

    /** The socket changed (closed, replaced, another server): every flight is dropped. */
    fun clear() = flights.clear()

    companion object {
        /** Repeated taps on Retry / Refresh within this window send once. */
        const val DEBOUNCE_MS = 2_000L

        /**
         * A refresh with no word back is let go after this (provider-catalog.mjs bounds a fetch at its
         * deadline, then pushes the error; a row it will not refresh pushes nothing).
         */
        const val IN_FLIGHT_TIMEOUT_MS = 30_000L
    }
}
