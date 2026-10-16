package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/** T2.2: faithful port of lib/sidebar-order.mjs. */
object SidebarOrder {

    // lib/sidebar-order.mjs:2 — explicit ranks first, then the caller's fallback order (stable).
    fun applySidebarOrder(entries: JsArr, order: JsValue?): JsArr {
        if (order !is JsArr || order.isEmpty()) return entries
        val rank = HashMap<JsValue, Int>()
        order.forEachIndexed { index, key -> rank[key] = index } // a later duplicate wins, like `new Map`
        val ranked = entries.mapIndexed { fallback, entry -> Triple(entry, fallback, entry["key"]?.let { rank[it] }) }
        return JsArr.of(
            ranked.sortedWith { left, right ->
                val l = left.third
                val r = right.third
                when {
                    l != null && r != null -> l - r
                    l != null -> -1
                    r != null -> 1
                    else -> left.second - right.second
                }
            }.map { it.first },
        )
    }

    // The stamps a sidebar row is sorted by, as numbers: the single place the row model (SidebarModel) and the
    // held-session-list check (SessionDisplay) take them from, so the two can never drift.

    /** A row's `updatedAt` / `lastMessageAt` when its live session is linked to a history row: the live stamp unless it is absent or 0, else the history's. */
    fun linkedStamp(live: Long?, historyUpdatedAt: Long): Long = live?.takeIf { it != 0L } ?: historyUpdatedAt

    /** The "last active" key ([lastActiveAt]'s numeric twin): the message stamp when the row carries one, else `updatedAt`. */
    fun lastActive(lastMessageAt: Long?, updatedAt: Long): Long = lastMessageAt ?: updatedAt

    // lib/sidebar-order.mjs:27 — issue #17: a message-based stamp when the entry carries one.
    private fun lastActiveAt(entry: JsValue): JsValue? =
        entry["lastMessageAt"] as? JsNum ?: entry["updatedAt"]

    // lib/sidebar-order.mjs:32 — newest first; ties by updatedAt desc, then key (localeCompare).
    // The web sorts IN PLACE and returns the same array; the sorted copy is returned here.
    // [collator] is `localeCompare` (see [JsCollator]).
    fun sortSidebarEntries(entries: JsArr, mode: JsValue?, collator: JsCollator): JsArr {
        val key = { entry: JsValue -> jsToNumber(if (mode.isStr("last-active")) lastActiveAt(entry) else entry["createdAt"]) }
        return JsArr.of(
            entries.sortedWith { left, right ->
                sortSign(
                    orNumber(key(right) - key(left)) {
                        orNumber(jsToNumber(right["updatedAt"]) - jsToNumber(left["updatedAt"])) {
                            collator.compare((left["key"] as JsStr).value, (right["key"] as JsStr).value).toDouble()
                        }
                    },
                )
            },
        )
    }

    // lib/sidebar-order.mjs:40 — the full explicit order after moving one row before another.
    fun moveSidebarEntry(entries: JsArr, draggedKey: JsValue?, targetKey: JsValue?): JsArr {
        val keys = entries.map { it["key"] ?: com.tether.app.protocol.tree.JsNull }.toMutableList()
        val from = keys.indexOfFirst { strictEquals(it, draggedKey) }
        val to = keys.indexOfFirst { strictEquals(it, targetKey) }
        if (from < 0 || to < 0 || from == to) return JsArr.of(keys)
        val dragged = keys.removeAt(from)
        keys.add(to, dragged)
        return JsArr.of(keys)
    }

    /**
     * lib/sidebar-order.mjs placeSuccessors (GH #273): a handover successor sits IMMEDIATELY ABOVE its source,
     * recursively (a chain stacks newest-first). Render-only, never persisted. A row whose `live.handedOffFrom` names
     * another row of the SAME list is lifted out of its own rank and placed above that source, unless its key is in
     * [keepKeys] (the operator dragged it, so its explicit rank wins). Rows with no listed source, and any row caught
     * in a cycle, keep their place.
     */
    fun placeSuccessors(entries: List<JsValue>, keepKeys: Set<String> = emptySet()): List<JsValue> {
        fun keyOf(entry: JsValue): String? = (entry["key"] as? JsStr)?.value
        val keyByLiveId = HashMap<String, String>()
        for (entry in entries) {
            val id = (entry["live"]["id"] as? JsStr)?.value?.takeIf { it.isNotEmpty() } ?: continue
            keyOf(entry)?.let { keyByLiveId[id] = it }
        }
        val successorsOf = LinkedHashMap<String, MutableList<JsValue>>()
        val lifted = HashSet<String>()
        for (entry in entries) {
            val key = keyOf(entry) ?: continue
            val from = (entry["live"]["handedOffFrom"] as? JsStr)?.value?.takeIf { it.isNotEmpty() } ?: continue
            val sourceKey = keyByLiveId[from] ?: continue
            if (sourceKey == key || key in keepKeys) continue
            lifted += key
            successorsOf.getOrPut(sourceKey) { ArrayList() }.add(entry)
        }
        if (lifted.isEmpty()) return entries
        val out = ArrayList<JsValue>()
        val emitted = HashSet<String>()
        fun emit(entry: JsValue) {
            val key = keyOf(entry) ?: run { out.add(entry); return }
            if (!emitted.add(key)) return
            // Marked first so a cycle cannot recurse forever; successors render before the source.
            successorsOf[key]?.forEach(::emit)
            out.add(entry)
        }
        for (entry in entries) if (keyOf(entry) !in lifted) emit(entry)
        // Rows only reachable through a cycle: kept rather than lose a session.
        for (entry in entries) emit(entry)
        return out
    }

    // ---- HG (tth-0bs) handover-chain fold: lib/sidebar-order.ts handoverChains .. chainSuffixText @ tether 7cc0f38c ----

    /** [head] is the active lead (nothing was handed off from it); [older] is newest first, the chain root last. Never empty. */
    data class HandoverChain(val head: JsValue, val older: List<JsValue>)

    /** [rows] with each chain folded into its head; [olderByHeadKey] is insertion-ordered (the web's Map). */
    data class FoldedChains(val rows: List<JsValue>, val olderByHeadKey: Map<String, List<JsValue>>)

    /** Key-level chain shape used by the drag order (the head stands for its whole group). */
    data class HandoverChainKeys(val headKey: String, val olderKeys: List<String>)

    data class ChainMemberFacts(val key: String, val selected: Boolean, val waiting: Boolean, val active: Boolean, val unread: Boolean)

    enum class ChainSuffixKind { Waiting, Active, New }
    data class ChainSuffix(val kind: ChainSuffixKind, val count: Int)
    data class ChainSummary(val reasonKeys: List<String>, val suffix: ChainSuffix?)

    private fun rowKey(entry: JsValue): String? = (entry["key"] as? JsStr)?.value

    /** handoverChains: order-independent chain membership over one block's rows (same list, neither side archived). */
    fun handoverChains(entries: List<JsValue>): List<HandoverChain> {
        val byLiveId = HashMap<String, JsValue>()
        for (entry in entries) {
            val id = (entry["live"]["id"] as? JsStr)?.value?.takeIf { it.isNotEmpty() } ?: continue
            if (!truthy(entry["live"]["runtimeArchived"])) byLiveId[id] = entry
        }
        val sourceOf = HashMap<String, JsValue>()
        val hasNext = HashSet<String>()
        for (entry in entries) {
            val live = entry["live"]
            if (!truthy(live) || truthy(live["runtimeArchived"])) continue
            val from = (live["handedOffFrom"] as? JsStr)?.value?.takeIf { it.isNotEmpty() }
            val source = from?.let { byLiveId[it] } ?: continue
            val key = rowKey(entry) ?: continue
            val sourceKey = rowKey(source) ?: continue
            if (sourceKey == key || sourceKey in hasNext) continue
            sourceOf[key] = source
            hasNext += sourceKey
        }
        val chains = ArrayList<HandoverChain>()
        for (entry in entries) {
            val key = rowKey(entry) ?: continue
            if (key in hasNext || key !in sourceOf) continue
            val older = ArrayList<JsValue>()
            val seen = hashSetOf(key)
            var cursor = sourceOf[key]
            while (cursor != null && seen.add(rowKey(cursor)!!)) {
                older += cursor
                cursor = sourceOf[rowKey(cursor)!!]
            }
            if (older.isNotEmpty()) chains += HandoverChain(entry, older)
        }
        return chains
    }

    /** foldHandoverChains: each chain becomes its head at the slot of the first member met. keepKeys play no part. */
    fun foldHandoverChains(entries: List<JsValue>): FoldedChains {
        val chains = handoverChains(entries)
        if (chains.isEmpty()) return FoldedChains(entries.toList(), emptyMap())
        val olderByHeadKey = LinkedHashMap<String, List<JsValue>>()
        val chainOfKey = HashMap<String, HandoverChain>()
        for (chain in chains) {
            olderByHeadKey[rowKey(chain.head)!!] = chain.older
            chainOfKey[rowKey(chain.head)!!] = chain
            for (member in chain.older) chainOfKey[rowKey(member)!!] = chain
        }
        val rows = ArrayList<JsValue>()
        val placed = HashSet<HandoverChain>()
        for (entry in entries) {
            val chain = rowKey(entry)?.let { chainOfKey[it] }
            if (chain == null) rows += entry else if (placed.add(chain)) rows += chain.head
        }
        return FoldedChains(rows, olderByHeadKey)
    }

    /** The chains' key shape for the drag order, from foldHandoverChains' (or handoverChains') result. */
    fun chainKeys(chains: List<HandoverChain>): List<HandoverChainKeys> =
        chains.map { chain -> HandoverChainKeys(rowKey(chain.head)!!, chain.older.map { rowKey(it)!! }) }

    /** collapseChainOrder: one key per chain, the head, at the chain root's position. */
    fun collapseChainOrder(order: List<String>, chains: List<HandoverChainKeys>): List<String> {
        val headByRoot = HashMap<String, String>()
        val chainKeys = HashSet<String>()
        for (chain in chains) {
            headByRoot[chain.olderKeys.last()] = chain.headKey
            chainKeys += chain.headKey
            chainKeys += chain.olderKeys
        }
        val out = ArrayList<String>()
        for (key in order) {
            val head = headByRoot[key]
            if (head != null) out += head else if (key !in chainKeys) out += key
        }
        return out
    }

    /** expandChainOrder: each head key is followed by its older members, newest first, contiguous. */
    fun expandChainOrder(order: List<String>, chains: List<HandoverChainKeys>): List<String> {
        val olderByHead = chains.associate { it.headKey to it.olderKeys }
        val out = ArrayList<String>()
        for (key in order) {
            out += key
            olderByHead[key]?.let { out += it }
        }
        return out
    }

    /** summarizeChainMembers: reasonKeys (selected, waiting or active) open the group; ONE suffix, needs you > active > new. */
    fun summarizeChainMembers(members: List<ChainMemberFacts>): ChainSummary {
        val waiting = members.count { it.waiting }
        val active = members.count { it.active && !it.waiting }
        val unread = members.count { it.unread }
        val suffix = when {
            waiting > 0 -> ChainSuffix(ChainSuffixKind.Waiting, waiting)
            active > 0 -> ChainSuffix(ChainSuffixKind.Active, active)
            unread > 0 -> ChainSuffix(ChainSuffixKind.New, unread)
            else -> null
        }
        return ChainSummary(members.filter { it.selected || it.waiting || it.active }.map { it.key }, suffix)
    }

    fun chainToggleLabel(count: Int): String = "$count earlier session${if (count == 1) "" else "s"}"

    fun chainSuffixText(suffix: ChainSuffix?): String = if (suffix == null) "" else
        "${suffix.count} ${when (suffix.kind) { ChainSuffixKind.Waiting -> "needs you"; ChainSuffixKind.Active -> "active"; ChainSuffixKind.New -> "new" }}"
}
