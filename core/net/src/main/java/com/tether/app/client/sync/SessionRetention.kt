package com.tether.app.client.sync

/**
 * ta-2vm7: which sessions keep their projection (tree, typed twin, adapter, cursor) in memory.
 *
 * The web never detaches a session and keeps every projection it ever folded, because a browser tab
 * has a heap to spare; a phone's is 256 MB, and one full tail-50 snapshot of a busy session is tens
 * of MB. So the app keeps the open session and the [maxOthers] opened before it, as long as their
 * estimated weights fit [budgetBytes] together, and releases the rest, oldest first. Releasing is
 * local to the client (no frame leaves): the session comes back, from the saved copy and a full
 * attach, when it is opened again.
 *
 * Pure: it decides, the caller releases what it returns. Not thread-safe by itself (the client calls
 * it under its lock).
 */
class SessionRetention(
    private val maxOthers: Int = DEFAULT_OTHERS,
    private val budgetBytes: Long,
) {
    // Oldest first; the last entry is the open session.
    private val order = LinkedHashMap<String, Long>()

    /** The session opened last: never released by anything but a [forget] or a [clear]. */
    val open: String? get() = order.keys.lastOrNull()

    val retained: Set<String> get() = order.keys.toSet()

    fun isRetained(sessionId: String): Boolean = order.containsKey(sessionId)

    fun totalBytes(): Long = order.values.sum()

    /** [sessionId] is opened now: the freshest one. Returns the sessions to release, oldest first. */
    fun open(sessionId: String): List<String> {
        val size = order.remove(sessionId) ?: 0L
        order[sessionId] = size
        return evict()
    }

    /**
     * Sessions the previous process had open last (cold start), oldest first, join the front of the
     * order: older than whatever is open now, so they are the first to go, and they never become the
     * open session when one is. Returns what to release.
     */
    fun adopt(sessionIds: List<String>): List<String> {
        val fresh = sessionIds.filter { it !in order }
        if (fresh.isEmpty()) return emptyList()
        val rest = LinkedHashMap(order)
        order.clear()
        fresh.forEach { order[it] = 0L }
        order.putAll(rest)
        return evict()
    }

    /** How many more sessions fit by count before one is released. */
    fun room(): Int = (maxOthers + 1 - order.size).coerceAtLeast(0)

    /** [sessionId]'s projection now weighs [bytes] (a snapshot replaced it). Not retained: ignored. */
    fun resize(sessionId: String, bytes: Long): List<String> {
        if (!order.containsKey(sessionId)) return emptyList()
        order[sessionId] = bytes.coerceAtLeast(0)
        return evict()
    }

    /** [sessionId]'s projection grew by [bytes] (an event or turn details folded in). Not retained: ignored. */
    fun grew(sessionId: String, bytes: Long): List<String> {
        val size = order[sessionId] ?: return emptyList()
        order[sessionId] = size + bytes.coerceAtLeast(0)
        return evict()
    }

    /** The system is short of memory: everything but the open session goes. Returns them, oldest first. */
    fun trim(): List<String> {
        val keep = open ?: return emptyList()
        val gone = order.keys.filter { it != keep }
        gone.forEach { order.remove(it) }
        return gone
    }

    fun forget(sessionId: String) {
        order.remove(sessionId)
    }

    fun clear() = order.clear()

    private fun evict(): List<String> {
        val released = ArrayList<String>()
        val keep = open
        // Count first, then weight; the open session is never a candidate.
        while (order.size - 1 > maxOthers || (totalBytes() > budgetBytes && order.size > 1)) {
            val oldest = order.keys.first { it != keep }
            order.remove(oldest)
            released += oldest
        }
        return released
    }

    companion object {
        /** The sessions kept besides the open one. */
        const val DEFAULT_OTHERS = 3

        /**
         * Heap bytes per character of a JSON frame: a tree of persistent maps and wrappers plus its typed
         * twin weighs a few times its text (measured at ~4.4 MB heap per ~3 MB of tool output).
         */
        const val HEAP_PER_JSON_CHAR = 3L
    }
}
