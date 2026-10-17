package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.util.Locale

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

    /**
     * Key-level chain shape used by the drag order (the head stands for its whole group). SB-2 (D-L34-24): [anchorKey] is the member
     * whose slot the collapsed head takes; null = the chain root (the last older key).
     */
    data class HandoverChainKeys(val headKey: String, val olderKeys: List<String>, val anchorKey: String? = null)

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
    fun foldHandoverChains(entries: List<JsValue>, groups: List<HandoverChain>? = null): FoldedChains {
        // SB-2: a caller that already derived [sidebarGroups] passes them (as chains); the default stays the handover chains alone.
        val chains = groups ?: handoverChains(entries)
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
            headByRoot[chain.anchorKey ?: chain.olderKeys.last()] = chain.headKey
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

    // ---- SB-2 name-sequence groups: lib/sidebar-order.ts parseSequenceName .. dragChainKeysByWorkspace @ tether ac67c5e4 ----

    /** parseSequenceName's result: the lowercase [base] and the trailing number [n] (1-3 ASCII digits). */
    data class SequenceName(val base: String, val n: Int)

    // JS `^(.*?)([0-9]+)$`: `.` excludes exactly the four JS line terminators (Java's `.` also drops U+0085); matched with matchEntire
    // (Java `$` would also accept a final line terminator).
    private val SEQUENCE_NAME = Regex("([^\\n\\r\\u2028\\u2029]*?)([0-9]+)")
    private val LETTER = Regex("\\p{L}")
    private fun isSequenceSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || c == '\u000B'

    /** Canonical name parser (D-L34-28): both ports run exactly this. null = the name carries no sequence key (a null name too). */
    fun parseSequenceName(name: String?): SequenceName? {
        if (name == null) return null
        var start = 0
        var end = name.length
        while (start < end && isSequenceSpace(name[start])) start++
        while (end > start && isSequenceSpace(name[end - 1])) end--
        val s = name.substring(start, end).replace(Regex("[ \\t]+"), " ")
        val m = SEQUENCE_NAME.matchEntire(s) ?: return null
        val digits = m.groupValues[2]
        if (digits.length > 3) return null
        if (isIdPrefix(m.groupValues[1])) return null // W28 (D-L41-19): "Fix issue 234", "PR#12" are IDs, not sequences
        val base = m.groupValues[1].trimEnd { it == ' ' || it == '-' || it == '_' || it == '#' || it == '.' }.lowercase(Locale.ROOT)
        if (!LETTER.containsMatchIn(base)) return null
        return SequenceName(base, digits.toInt())
    }

    private val ID_WORDS = setOf("issue", "issues", "pr", "prs", "pull", "pulls", "bug", "bugs", "ticket", "tickets", "task", "tasks", "gh")
    private fun isIdSeparator(c: Char) = c == ' ' || c == '-' || c == '_' || c == ':' || c == '.'

    /** JS `text.replace(/[A-Z]/g, ...)`: ASCII-only lowercasing (never a Locale or Unicode fold). */
    private fun asciiLower(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    /** JS trailingLetterRun: letters per UTF-16 code unit (a lone surrogate or any non-letter is a boundary). */
    private fun trailingLetterRun(text: String): String {
        var start = text.length
        while (start > 0 && text[start - 1].isLetter()) start--
        return text.substring(start)
    }

    /**
     * W28 isIdPrefix (web lib/sidebar-order.ts @ 1415dfaf :201-213): the prefix before the trailing digits names an ID
     * ("issue 3", "PR#12", "gh-273", "pull request 5"), not a sequence.
     */
    private fun isIdPrefix(prefix: String): Boolean {
        val p = prefix.trimEnd(::isIdSeparator)
        if (p.endsWith("#")) return true
        val w = trailingLetterRun(p)
        val lower = asciiLower(w)
        if (lower in ID_WORDS) return true
        if (lower == "pullrequest" || lower == "pullrequests") return true
        if (lower == "request" || lower == "requests") {
            val rest = p.substring(0, p.length - w.length).trimEnd(::isIdSeparator)
            return asciiLower(trailingLetterRun(rest)) == "pull"
        }
        return false
    }

    /**
     * W28 nestsByName (web :266-271, D-L41-11): only a name Tether did not generate nests. A row needs a live session AND
     * (live.nameIsCustom === true OR (live.nameGenerated !== true AND no non-blank native history name)).
     */
    private fun nestsByName(entry: JsValue): Boolean {
        val live = entry["live"]
        if (!truthy(live)) return false
        if (live["nameIsCustom"] == JsBool.TRUE) return true
        if (live["nameGenerated"] == JsBool.TRUE) return false
        val historyName = entry["history"]["name"] as? JsStr
        return historyName == null || jsTrim(historyName.value).isEmpty()
    }

    /** A handover chain's shape + whether it merges several units, and the key whose slot the group takes (D-L34-24/26). */
    data class SidebarGroup(val head: JsValue, val older: List<JsValue>, val multiUnit: Boolean, val anchorKey: String) {
        fun asChain() = HandoverChain(head, older)
    }

    /** The corpus adapter's default `nameOf` (`e.name ?? ""`); a non-string name yields null (parses to no key). */
    fun nameField(entry: JsValue): String? = entry["name"].let { if (it == null || it is JsNull) "" else (it as? JsStr)?.value }

    /** Recency of a unit's head (D-L34-27): `lastMessageAt ?? updatedAt ?? 0`. */
    private fun headRecency(entry: JsValue): Double {
        (entry["lastMessageAt"] as? JsNum)?.let { return it.value }
        val updated = entry["updatedAt"]
        return if (updated == null || updated is JsNull) 0.0 else jsToNumber(updated)
    }

    /**
     * Keys of rows that carry a handover link (same linking rule as handoverChains) yet sit in no chain: caught in a cycle (no head),
     * so they stay flat (D-L34-38).
     */
    private fun handoverCycleKeys(entries: List<JsValue>, chained: Set<String>): Set<String> {
        val byLiveId = HashMap<String, JsValue>()
        for (entry in entries) {
            val id = entry["live"]["id"].nonEmptyStr ?: continue
            if (!truthy(entry["live"]["runtimeArchived"])) byLiveId[id] = entry
        }
        val linked = LinkedHashSet<String>()
        val hasNext = HashSet<String>()
        for (entry in entries) {
            val live = entry["live"]
            if (!truthy(live) || truthy(live["runtimeArchived"])) continue
            val source = live["handedOffFrom"].nonEmptyStr?.let { byLiveId[it] } ?: continue
            val key = rowKey(entry) ?: continue
            val sourceKey = rowKey(source) ?: continue
            if (sourceKey == key || sourceKey in hasNext) continue
            linked += key
            linked += sourceKey
            hasNext += sourceKey
        }
        return linked.filterTo(HashSet()) { it !in chained }
    }

    private class GroupUnit(val head: JsValue, val older: List<JsValue>, val anchorKey: String, val parsed: SequenceName?)

    /**
     * Handover chains UNION name-sequence groups over ONE block's rows. A lone ungrouped row is not a group. Unit order inside a
     * group: heads with `handedOffTo` set last, then n desc, then head recency desc, then key ascending (UTF-16 code units).
     */
    fun sidebarGroups(entries: List<JsValue>, nameOf: (JsValue) -> String? = ::nameField): List<SidebarGroup> {
        val chains = handoverChains(entries)
        val chainByHeadKey = HashMap<String, HandoverChain>()
        val chained = HashSet<String>()
        for (chain in chains) {
            chainByHeadKey[rowKey(chain.head)!!] = chain
            chained += rowKey(chain.head)!!
            for (member in chain.older) chained += rowKey(member)!!
        }
        val cycleKeys = handoverCycleKeys(entries, chained)
        val units = ArrayList<GroupUnit>()
        for (entry in entries) {
            val key = rowKey(entry) ?: continue
            val chain = chainByHeadKey[key]
            if (chain == null && key in chained) continue
            if (chain == null && key in cycleKeys) continue
            val older = chain?.older ?: emptyList()
            units += GroupUnit(entry, older, if (older.isNotEmpty()) rowKey(older.last())!! else key, if (nestsByName(entry)) parseSequenceName(nameOf(entry)) else null)
        }
        val buckets = LinkedHashMap<String, MutableList<GroupUnit>>()
        for (unit in units) unit.parsed?.let { buckets.getOrPut(it.base) { ArrayList() } += unit }
        val out = ArrayList<SidebarGroup>()
        for (unit in units) {
            val bucket = unit.parsed?.let { buckets[it.base] }
            if (bucket == null || bucket.size < 2) {
                if (unit.older.isNotEmpty()) out += SidebarGroup(unit.head, unit.older, false, unit.anchorKey)
                continue
            }
            if (bucket[0] !== unit) continue // emitted once, at the bucket's first unit in entry order
            val ordered = bucket.sortedWith { a, b ->
                val aOut = if (truthy(a.head["live"]["handedOffTo"])) 1.0 else 0.0
                val bOut = if (truthy(b.head["live"]["handedOffTo"])) 1.0 else 0.0
                sortSign(
                    orNumber(aOut - bOut) {
                        orNumber((b.parsed?.n ?: 0).toDouble() - (a.parsed?.n ?: 0).toDouble()) {
                            orNumber(headRecency(b.head) - headRecency(a.head)) {
                                a.head.let { rowKey(it)!! }.compareTo(rowKey(b.head)!!).toDouble()
                            }
                        }
                    },
                )
            }
            val first = ordered.first()
            out += SidebarGroup(first.head, first.older + ordered.drop(1).flatMap { listOf(it.head) + it.older }, true, first.anchorKey)
        }
        return out
    }

    /**
     * placeSuccessors' analogue for name groups (D-L34-24): gather every multi-unit group's members into ONE contiguous run at the
     * anchor member's slot (head first, then older in group order); a group whose anchor is not in [rows] takes its first-met member's slot.
     */
    fun placeGroupMembers(rows: List<JsValue>, groups: List<SidebarGroup>): List<JsValue> {
        val multi = groups.filter { it.multiUnit }
        if (multi.isEmpty()) return rows.toList()
        val present = rows.mapNotNullTo(HashSet()) { rowKey(it) }
        val groupOfKey = HashMap<String, SidebarGroup>()
        for (group in multi) for (member in listOf(group.head) + group.older) groupOfKey[rowKey(member)!!] = group
        val placed = HashSet<SidebarGroup>()
        val out = ArrayList<JsValue>()
        for (row in rows) {
            val key = rowKey(row)
            val group = key?.let { groupOfKey[it] }
            if (group == null) { out += row; continue }
            if (group in placed || (group.anchorKey in present && key != group.anchorKey)) continue
            placed += group
            for (member in listOf(group.head) + group.older) if (rowKey(member) in present) out += member
        }
        return out
    }

    /** D-L34-26 band recency of a multi-unit group: max `lastMessageAt ?: updatedAt` over head + older (delegates excluded). */
    fun groupBandRecency(head: JsValue, older: List<JsValue>): Double {
        var best = headRecency(head)
        for (member in older) best = Math.max(best, headRecency(member))
        return best
    }

    /**
     * D-L34-26 band recency of one top-level row: a multi-unit group takes the max over head + older; every other row (a lone
     * handover chain included) keeps the head-only `lastMessageAt ?: updatedAt`.
     */
    fun bandRecencyOf(entry: JsValue, multiUnitHeadKeys: Set<String>, olderByHeadKey: Map<String, List<JsValue>>): Double? {
        val key = rowKey(entry)
        if (key != null && key in multiUnitHeadKeys) return groupBandRecency(entry, olderByHeadKey[key].orEmpty())
        return ((entry["lastMessageAt"] as? JsNum) ?: (entry["updatedAt"] as? JsNum))?.value
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
