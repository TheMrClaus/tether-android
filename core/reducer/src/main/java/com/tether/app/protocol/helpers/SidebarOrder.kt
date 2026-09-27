package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.strictEquals
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
}
