package com.tether.app.protocol.helpers

/**
 * ta-z0k0 (S3a): the pure decisions behind the redesigned sidebar rows, ported from tether ac67c5e4 lib/sidebar-row-model.ts
 * (rowBadge, newTurnsText, rowStatusKind, handoverAgeAt, workspaceOpenCounts, rowMenuGroups). No Compose, no I/O, no clock.
 * Oracle: [SidebarRowModelTest], a hand copy of tests/sidebar-row-model.test.mjs @ ac67c5e4ffdb1fcfa3b4176929cdecb407b7b19c.
 */
object SidebarRowModel {
    /** The ONE badge slot of a row. Priority: "N new turns" > "New" > "Current" (D-L34-15). */
    sealed interface RowBadge {
        data class Turns(val n: Int) : RowBadge
        data object New : RowBadge
        data object Current : RowBadge
    }

    /** `digest` = the row has a digest at all; [newTurns] is its count (null = none). */
    data class RowDigestFacts(val newTurns: Int? = null)

    fun rowBadge(unread: Boolean = false, digest: RowDigestFacts? = null, isGroupHead: Boolean = false): RowBadge? {
        val n = digest?.newTurns ?: 0
        if (n > 0) return RowBadge.Turns(n)
        if (unread || digest != null) return RowBadge.New
        if (isGroupHead) return RowBadge.Current
        return null
    }

    /** `1 new turn` / `N new turns` / `99+ new turns` (the visible text). */
    fun newTurnsText(n: Int): String {
        val shown = if (n > 99) "99+" else n.toString()
        return "$shown new turn${if (n == 1) "" else "s"}"
    }

    enum class RowStatusKind { Waiting, Active, Handed, Ready, Exited, History }

    /** The two live fields the status word reads: [status] is the wire word (ready/active/waiting/exited). */
    data class RowLiveFacts(val status: String, val handedOffTo: String? = null)

    /** D-L34-20: Needs you > Active > Handed over > Ready / Ended; a row with no live session is History. */
    fun rowStatusKind(live: RowLiveFacts?): RowStatusKind {
        if (live == null) return RowStatusKind.History
        if (live.status == "waiting") return RowStatusKind.Waiting
        if (live.status == "active") return RowStatusKind.Active
        if (!live.handedOffTo.isNullOrEmpty()) return RowStatusKind.Handed
        return if (live.status == "exited") RowStatusKind.Exited else RowStatusKind.Ready
    }

    const val HANDED_OVER_WORD = "Handed over"

    /** The instant "Handed over · age" counts from: the successor's start, else the source's last update. */
    fun handoverAgeAt(sourceUpdatedAt: Long, successorStartedAt: Long? = null): Long = successorStartedAt ?: sourceUpdatedAt

    /** [runtimeArchived] is `live.runtimeArchived` (false for a history row). */
    data class CountableRow(val workspace: String?, val runtimeArchived: Boolean = false)

    data class WorkspaceCounts(val byWorkspace: Map<String, Int>, val placed: Int, val unplaced: Int, val total: Int)

    /** D-L34-5/22: every non-archived session of a block, lens-independent; a row outside every block is `unplaced`, not dropped. */
    fun workspaceOpenCounts(rows: List<CountableRow>, workspaces: List<String>): WorkspaceCounts {
        val by = LinkedHashMap<String, Int>()
        for (w in workspaces) by[w] = 0
        var placed = 0
        var unplaced = 0
        for (row in rows) {
            if (row.runtimeArchived) continue
            val w = row.workspace
            if (w != null && by.containsKey(w)) {
                by[w] = (by[w] ?: 0) + 1
                placed += 1
            } else {
                unplaced += 1
            }
        }
        return WorkspaceCounts(by, placed, unplaced, placed + unplaced)
    }

    enum class RowMenuItemId { Rename, Pin, Handover, CopyId, CopyPath, CopyResume, End }

    data class RowMenuFacts(
        val hasLive: Boolean,
        val endable: Boolean,
        val cwd: String,
        val resumeCommand: String? = null,
        val handoverAvailable: Boolean,
    )

    /** D-L34-16: each item keeps its source's gate; groups manage / copy / end, an empty group is dropped. */
    fun rowMenuGroups(facts: RowMenuFacts): List<List<RowMenuItemId>> {
        val manage = ArrayList<RowMenuItemId>()
        if (facts.hasLive) { manage += RowMenuItemId.Rename; manage += RowMenuItemId.Pin }
        if (facts.hasLive && facts.handoverAvailable) manage += RowMenuItemId.Handover
        val copy = ArrayList<RowMenuItemId>()
        if (facts.hasLive) copy += RowMenuItemId.CopyId
        if (facts.cwd.isNotEmpty()) copy += RowMenuItemId.CopyPath
        if (facts.hasLive && !facts.resumeCommand.isNullOrEmpty()) copy += RowMenuItemId.CopyResume
        val end = if (facts.endable) listOf(RowMenuItemId.End) else emptyList()
        return listOf<List<RowMenuItemId>>(manage, copy, end).filter { it.isNotEmpty() }
    }
}
