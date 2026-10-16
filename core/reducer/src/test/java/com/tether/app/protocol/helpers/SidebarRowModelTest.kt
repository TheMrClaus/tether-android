package com.tether.app.protocol.helpers

import com.tether.app.protocol.helpers.SidebarRowModel.CountableRow
import com.tether.app.protocol.helpers.SidebarRowModel.RowBadge
import com.tether.app.protocol.helpers.SidebarRowModel.RowDigestFacts
import com.tether.app.protocol.helpers.SidebarRowModel.RowLiveFacts
import com.tether.app.protocol.helpers.SidebarRowModel.RowMenuFacts
import com.tether.app.protocol.helpers.SidebarRowModel.RowMenuItemId
import com.tether.app.protocol.helpers.SidebarRowModel.RowStatusKind
import com.tether.app.protocol.helpers.SidebarRowModel.handoverAgeAt
import com.tether.app.protocol.helpers.SidebarRowModel.newTurnsText
import com.tether.app.protocol.helpers.SidebarRowModel.rowBadge
import com.tether.app.protocol.helpers.SidebarRowModel.rowMenuGroups
import com.tether.app.protocol.helpers.SidebarRowModel.rowStatusKind
import com.tether.app.protocol.helpers.SidebarRowModel.workspaceOpenCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ta-z0k0: hand copy of all 14 tests of tether tests/sidebar-row-model.test.mjs @ ac67c5e4ffdb1fcfa3b4176929cdecb407b7b19c
 * (4 describes: badge slot 3, status word 3, workspace counts 2, kebab matrix 6). Written from the web test, not from the port.
 */
class SidebarRowModelTest {
    // ---- SB-1 badge slot priority (D-L34-15) ----
    @Test fun nNewTurnsBeatsNewBeatsCurrentALoneRowHasNone() {
        assertEquals(RowBadge.Turns(2), rowBadge(digest = RowDigestFacts(2), unread = true, isGroupHead = true))
        assertEquals(RowBadge.New, rowBadge(unread = true, isGroupHead = true))
        assertEquals(RowBadge.Current, rowBadge(isGroupHead = true))
        assertNull(rowBadge())
    }

    @Test fun newIsUnreadOrDigestAndNoCountAboveZero() {
        assertEquals(RowBadge.New, rowBadge(digest = RowDigestFacts(0)))
        assertEquals(RowBadge.New, rowBadge(unread = true, digest = RowDigestFacts(0)))
        assertNull(rowBadge(unread = false, digest = null))
    }

    @Test fun countTextSingularPluralNinetyNinePlus() {
        assertEquals("1 new turn", newTurnsText(1))
        assertEquals("2 new turns", newTurnsText(2))
        assertEquals("99+ new turns", newTurnsText(250))
    }

    // ---- SB-1 status word priority (D-L34-20) ----
    @Test fun needsYouAndActiveStayAheadOfHandedOverWhichReplacesOnlyReadyEnded() {
        assertEquals(RowStatusKind.Waiting, rowStatusKind(RowLiveFacts("waiting", "x")))
        assertEquals(RowStatusKind.Active, rowStatusKind(RowLiveFacts("active", "x")))
        assertEquals(RowStatusKind.Handed, rowStatusKind(RowLiveFacts("ready", "x")))
        assertEquals(RowStatusKind.Handed, rowStatusKind(RowLiveFacts("exited", "x")))
        assertEquals(RowStatusKind.Ready, rowStatusKind(RowLiveFacts("ready")))
        assertEquals(RowStatusKind.Exited, rowStatusKind(RowLiveFacts("exited")))
        assertEquals(RowStatusKind.History, rowStatusKind(null))
    }

    @Test fun handedOverOnlyWithARealHandedOffTo() {
        assertEquals(RowStatusKind.Ready, rowStatusKind(RowLiveFacts("ready", null)))
        assertEquals(RowStatusKind.Ready, rowStatusKind(RowLiveFacts("ready", "")))
    }

    @Test fun handoverAgeIsTheSuccessorStartedAtElseTheSourceUpdatedAt() {
        assertEquals(500L, handoverAgeAt(100, 500))
        assertEquals(100L, handoverAgeAt(100, null))
        assertEquals(100L, handoverAgeAt(100))
    }

    // ---- SB-1 workspace counts (D-L34-5/22) ----
    private fun live(workspace: String, runtimeArchived: Boolean = false) = CountableRow(workspace, runtimeArchived)

    @Test fun perBlockCountsSumToTheTitleCountArchivedExcluded() {
        val rows = listOf(live("/a"), live("/a"), live("/b"), live("/b", true), CountableRow("/a"), live("/c"))
        val counts = workspaceOpenCounts(rows, listOf("/a", "/b", "/c"))
        assertEquals(listOf("/a" to 3, "/b" to 1, "/c" to 1), counts.byWorkspace.toList())
        assertEquals(counts.total, counts.byWorkspace.values.sum())
        assertEquals(0, counts.unplaced)
    }

    @Test fun aCollapsedOrEmptyBlockStillCountsARowOutsideEveryBlockIsUnplacedNotDropped() {
        val counts = workspaceOpenCounts(listOf(live("/a"), live("/zzz")), listOf("/a", "/empty"))
        assertEquals(0, counts.byWorkspace["/empty"])
        assertEquals(1, counts.byWorkspace["/a"])
        assertEquals(1, counts.unplaced)
        assertEquals(2, counts.total)
    }

    // ---- SB-1 kebab visibility matrix (D-L34-16) ----
    private val base = RowMenuFacts(hasLive = true, endable = true, cwd = "/w", resumeCommand = "claude --resume x", handoverAvailable = true)
    private fun flat(f: RowMenuFacts) = rowMenuGroups(f).flatten()

    @Test fun aLiveEndableActiveRowShowsEverythingInSpecOrder() {
        assertEquals(
            listOf(RowMenuItemId.Rename, RowMenuItemId.Pin, RowMenuItemId.Handover, RowMenuItemId.CopyId, RowMenuItemId.CopyPath, RowMenuItemId.CopyResume, RowMenuItemId.End),
            flat(base),
        )
        assertEquals(listOf(3, 3, 1), rowMenuGroups(base).map { it.size })
    }

    @Test fun rowEndIsEndableAnArchivedLiveRowHasNoEnd() {
        assertFalse(flat(base.copy(endable = false)).contains(RowMenuItemId.End))
    }

    @Test fun handOverOnlyWhereTheHeaderShowsItTheOpenSession() {
        assertFalse(flat(base.copy(handoverAvailable = false)).contains(RowMenuItemId.Handover))
    }

    @Test fun copyResumeCommandNeedsResumeCommand() {
        assertFalse(flat(base.copy(resumeCommand = null)).contains(RowMenuItemId.CopyResume))
    }

    @Test fun historyOnlyRowCopyPathOnly() {
        assertEquals(
            listOf(RowMenuItemId.CopyPath),
            flat(RowMenuFacts(hasLive = false, endable = false, cwd = "/w", resumeCommand = null, handoverAvailable = false)),
        )
    }

    @Test fun aRowWithNoVisibleItemHasNoKebab() {
        assertEquals(emptyList<List<RowMenuItemId>>(), rowMenuGroups(RowMenuFacts(hasLive = false, endable = false, cwd = "", handoverAvailable = false)))
    }
}
