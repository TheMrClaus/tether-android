package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import kotlin.math.max

/** T2.2: faithful port of lib/sidebar-workspaces.mjs — v93 per-workspace sidebar blocks. */
object SidebarWorkspaces {

    // lib/sidebar-workspaces.mjs:10 — IS the root or sits strictly below it (separator-guarded).
    private fun isInside(path: String?, root: String?): Boolean {
        if (path.isNullOrEmpty() || root.isNullOrEmpty()) return false
        if (path == root) return true
        val base = if (root.endsWith("/")) root else "$root/"
        return path.startsWith(base)
    }

    /** The distinct non-empty strings of `list ?? []`, in order. */
    private fun distinctRoots(list: JsValue?): MutableList<String> {
        val out = ArrayList<String>()
        if (isNullish(list)) return out
        for (item in jsIterate(list)) {
            val s = (item as? JsStr)?.value
            if (!s.isNullOrEmpty() && s !in out) out.add(s)
        }
        return out
    }

    // lib/sidebar-workspaces.mjs:25 — pinned in order, then the current one when not pinned.
    fun sidebarWorkspaceList(pinned: JsValue?, current: JsValue?): List<String> {
        val list = distinctRoots(pinned)
        val cur = (current as? JsStr)?.value
        if (!cur.isNullOrEmpty() && cur !in list) list.add(cur)
        return list
    }

    // lib/sidebar-workspaces.mjs:44 — append to the kept list; idempotent, order-preserving.
    fun pinWorkspace(pinned: JsValue?, cwd: JsValue?): List<String> = sidebarWorkspaceList(pinned, cwd)

    // lib/sidebar-workspaces.mjs:65 — the server's list when it has one, else this device's.
    fun effectivePinnedWorkspaces(serverPinned: JsValue?, localPinned: JsValue?): List<String> =
        distinctRoots(if (serverPinned is JsArr) serverPinned else localPinned)

    // lib/sidebar-workspaces.mjs:81 — the DEEPEST listed root containing cwd, or null.
    fun workspaceGroupFor(cwd: JsValue?, workspaces: JsValue?): String? {
        var best: String? = null
        if (isNullish(workspaces)) return null
        for (root in jsIterate(workspaces)) {
            val r = (root as? JsStr)?.value
            if (!isInside((cwd as? JsStr)?.value, r)) continue
            if (best == null || r!!.length > best.length) best = r
        }
        return best
    }

    // lib/sidebar-workspaces.mjs:96 — per-block pagination that never hides the selected row.
    fun paginateWorkspaceRows(
        rows: JsValue?,
        visibleCount: JsValue?,
        pageSize: Double,
        isSelected: ((JsValue) -> Boolean)? = null,
    ): JsObj {
        val list = if (isNullish(rows)) JsArr.EMPTY else rows as JsArr
        val limit = max(pageSize, (visibleCount as? JsNum)?.value?.takeIf { it != 0.0 && !it.isNaN() } ?: 0.0)
        if (list.size <= limit) {
            return JsObj.of("visible" to list, "remaining" to js(0), "expanded" to js(limit > pageSize))
        }
        val visible = ArrayList<JsValue>(list.slice(0, limit.toInt()))
        if (isSelected != null) {
            for (index in limit.toInt() until list.size) {
                if (isSelected(list[index])) {
                    visible.add(list[index])
                    break
                }
            }
        }
        return JsObj.of(
            "visible" to JsArr.of(visible),
            "remaining" to js(list.size - visible.size),
            "expanded" to js(limit > pageSize),
        )
    }

    /** lib/sidebar-workspaces.mjs:113 SIDEBAR_RECENT_MS — the "Older" band's default horizon (issue #244): 7 days. */
    const val SIDEBAR_RECENT_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** The two bands [splitRecentOlder] returns (lib/sidebar-workspaces.mjs:126 `{ recent, older }`). */
    data class RecentOlder<T>(val recent: List<T>, val older: List<T>)

    /**
     * lib/sidebar-workspaces.mjs:126 splitRecentOlder — pure and non-destructive: nothing is archived or
     * hidden irreversibly. A row is never folded when [isKeep] (pinned, working, waiting, unread,
     * selected) or when its activity time is unknown ([activityAt] returns epoch ms, null/NaN = unknown).
     */
    fun <T> splitRecentOlder(
        rows: List<T>,
        now: Long,
        horizonMs: Long,
        activityAt: (T) -> Double?,
        isKeep: ((T) -> Boolean)? = null,
    ): RecentOlder<T> {
        val recent = ArrayList<T>()
        val older = ArrayList<T>()
        for (row in rows) {
            val at = activityAt(row)
            val stale = at != null && at.isFinite() && at > 0 && now - at >= horizonMs
            if (stale && !(isKeep != null && isKeep(row))) older.add(row) else recent.add(row)
        }
        return RecentOlder(recent, older)
    }
}
