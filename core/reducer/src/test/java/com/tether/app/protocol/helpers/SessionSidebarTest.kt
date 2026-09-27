package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.2: the sidebar folds inside components/session-sidebar.tsx (no helper-corpus table: S0.2
 * skippedModules). Each test names the component line it pins, at PARITY_BASE (tether 7d65611,
 * exporter branch 157b87d).
 */
class SessionSidebarTest {

    private fun s(v: String) = JsStr(v)

    private fun row(
        key: String,
        workspace: String? = "/w",
        live: JsObj? = null,
        history: JsObj? = null,
        unread: Boolean? = null,
        digest: JsValue? = null,
    ): JsObj = JsObj.of(
        "key" to s(key),
        "workspace" to workspace?.let { s(it) },
        "createdAt" to com.tether.app.protocol.tree.js(1),
        "updatedAt" to com.tether.app.protocol.tree.js(1),
        "live" to live,
        "history" to history,
        "unread" to unread?.let { JsBool.of(it) },
        "digest" to digest,
    )

    private fun live(vararg entries: Pair<String, JsValue?>) = JsObj.of(*entries)

    private fun keys(rows: List<JsValue>) = rows.map { (it["key"] as JsStr).value }

    // session-sidebar.tsx:418 — "Active Only" is work in progress: streaming/executing or waiting on you.
    @Test
    fun hasWorkInProgressIsActiveOrWaitingOnly() {
        assertTrue(SessionSidebar.hasWorkInProgress(row("a", live = live("status" to s("active")))))
        assertTrue(SessionSidebar.hasWorkInProgress(row("w", live = live("status" to s("waiting")))))
        assertFalse(SessionSidebar.hasWorkInProgress(row("r", live = live("status" to s("ready")))))
        assertFalse(SessionSidebar.hasWorkInProgress(row("e", live = live("status" to s("exited")))))
        // a warm session that merely carries an unread report is NOT in progress (issue #104)
        assertFalse(SessionSidebar.hasWorkInProgress(row("u", live = live("status" to s("ready")), unread = true)))
        assertFalse(SessionSidebar.hasWorkInProgress(row("h", history = JsObj.of("origin" to s("tether")))))
    }

    // session-sidebar.tsx:437 — "Unread": the client unread flag OR the server's changed-while-away digest.
    @Test
    fun hasUnseenWorkIsUnreadFlagOrDigest() {
        assertTrue(SessionSidebar.hasUnseenWork(row("u", unread = true)))
        assertTrue(SessionSidebar.hasUnseenWork(row("d", digest = JsObj.of("turns" to com.tether.app.protocol.tree.js(2)))))
        assertFalse(SessionSidebar.hasUnseenWork(row("n")))
        assertFalse(SessionSidebar.hasUnseenWork(row("f", unread = false, digest = JsNull)))
    }

    // session-sidebar.tsx:429 — the Archived bucket is runtimeArchived only (a handed-off source stays inline).
    @Test
    fun isArchivedLikeIsRuntimeArchived() {
        assertTrue(SessionSidebar.isArchivedLike(row("x", live = live("runtimeArchived" to JsBool.TRUE))))
        assertFalse(SessionSidebar.isArchivedLike(row("h", live = live("handedOffTo" to s("other")))))
        assertFalse(SessionSidebar.isArchivedLike(row("n")))
    }

    // session-sidebar.tsx:551 — the Active and Unread toggles are independent: a row must pass EVERY enabled one.
    @Test
    fun passesAttentionRequiresEveryEnabledToggle() {
        val activeUnread = row("au", live = live("status" to s("active")), unread = true)
        val activeOnly = row("a", live = live("status" to s("active")))
        assertTrue(SessionSidebar.passesAttention(activeUnread, activeOnly = true, unreadOnly = true))
        assertFalse(SessionSidebar.passesAttention(activeOnly, activeOnly = true, unreadOnly = true))
        assertTrue(SessionSidebar.passesAttention(activeOnly, activeOnly = true, unreadOnly = false))
        assertTrue(SessionSidebar.passesAttention(row("idle"), activeOnly = false, unreadOnly = false))
    }

    // session-sidebar.tsx:566-575 — with no toggle on, archived rows leave the main list for the Archived group.
    @Test
    fun archivedRowsSplitOutWhenNoAttentionToggle() {
        val rows = JsArr.of(row("a"), row("x", live = live("runtimeArchived" to JsBool.TRUE)), row("b"))
        val out = SessionSidebar.sidebarRows(rows, "", activeOnly = false, unreadOnly = false, hideAgentRuns = true, harnessFilter = null)
        assertEquals(listOf("a", "b"), keys(out.visibleRows))
        assertEquals(listOf("x"), keys(out.archivedRows))
        assertFalse(out.filtering)
    }

    // session-sidebar.tsx:568-573 — while a toggle is on, an archived row that passes stays in the main list.
    @Test
    fun archivedRowPassingAToggleStaysInTheMainList() {
        val rows = JsArr.of(row("x", live = live("runtimeArchived" to JsBool.TRUE), unread = true), row("b"))
        val out = SessionSidebar.sidebarRows(rows, "", activeOnly = false, unreadOnly = true, hideAgentRuns = true, harnessFilter = null)
        assertEquals(listOf("x"), keys(out.visibleRows))
        assertEquals(listOf("x"), keys(out.archivedRows))
        assertTrue(out.filtering)
    }

    private fun agentChild(key: String, workspace: String, linked: Boolean = false) = row(
        key,
        workspace = workspace,
        history = JsObj.of(
            "origin" to s("agent-cli-child"),
            "spawnedBy" to (if (linked) JsObj.of("tetherSessionId" to s("parent")) else null),
        ),
    )

    // session-sidebar.tsx:559 — the provenance lens runs first; hidden unlinked children are counted per
    // workspace only when the other lenses would show them (here: not archived); linked children never count.
    @Test
    fun agentCliLensHidesAndCountsPerWorkspace() {
        val rows = JsArr.of(row("a"), agentChild("c1", "/w"), agentChild("c2", "/w"), agentChild("c3", "/v"), agentChild("l1", "/w", linked = true))
        val out = SessionSidebar.sidebarRows(rows, "", activeOnly = false, unreadOnly = false, hideAgentRuns = true, harnessFilter = null)
        assertEquals(listOf("a"), keys(out.visibleRows))
        assertEquals(mapOf<JsValue, Int>(s("/w") to 2, s("/v") to 1), out.hiddenByWorkspace)
        // with Unread on, a hidden child is counted only if it would pass Unread
        val unread = SessionSidebar.sidebarRows(rows, "", activeOnly = false, unreadOnly = true, hideAgentRuns = true, harnessFilter = null)
        assertEquals(emptyMap<JsValue, Int>(), unread.hiddenByWorkspace)
    }

    // session-sidebar.tsx:549,559 — a (JS-trimmed) search query bypasses the lens: every row already matched.
    @Test
    fun aQueryBypassesTheAgentCliLens() {
        val rows = JsArr.of(row("a"), agentChild("c1", "/w"))
        val out = SessionSidebar.sidebarRows(rows, " x ", activeOnly = false, unreadOnly = false, hideAgentRuns = true, harnessFilter = null)
        assertEquals(listOf("a", "c1"), keys(out.visibleRows))
        assertTrue(out.hiddenByWorkspace.isEmpty())
        assertTrue(out.filtering)
        val blank = SessionSidebar.sidebarRows(rows, "   ", activeOnly = false, unreadOnly = false, hideAgentRuns = true, harnessFilter = null)
        assertFalse("a whitespace-only query is not a query", blank.filtering)
        val off = SessionSidebar.sidebarRows(rows, "", activeOnly = false, unreadOnly = false, hideAgentRuns = false, harnessFilter = "codex")
        assertEquals(listOf("a", "c1"), keys(off.visibleRows))
        assertTrue("a harness filter forces blocks open", off.filtering)
    }

    // session-sidebar.tsx:605 — rows grouped by block (`workspace ?? ""`), each block in row order.
    @Test
    fun rowsGroupByWorkspaceInOrder() {
        val grouped = SessionSidebar.rowsByWorkspace(listOf(row("a", "/w"), row("b", "/v"), row("c", "/w"), row("d", workspace = null)))
        assertEquals(listOf("/w", "/v", ""), grouped.keys.toList())
        assertEquals(listOf("a", "c"), keys(grouped.getValue("/w")))
        assertEquals(listOf("d"), keys(grouped.getValue("")))
    }

    // session-sidebar.tsx:454 — a delegate child nests under its parent only when the parent is also listed.
    @Test
    fun delegateChildrenGroupUnderAListedParent() {
        val parent = row("P", live = live("id" to s("sess-p")))
        val child = row("C", live = live("id" to s("sess-c"), "parentSessionId" to s("sess-p")))
        val orphan = row("O", live = live("id" to s("sess-o"), "parentSessionId" to s("sess-gone")))
        val self = row("S", live = live("id" to s("sess-s"), "parentSessionId" to s("sess-s")))
        val grouped = SessionSidebar.groupDelegateChildren(listOf(parent, child, orphan, self))
        assertEquals(listOf("P", "O", "S"), keys(grouped.rows))
        assertEquals(listOf("C"), keys(grouped.childrenByKey.getValue("P")))
        assertEquals(setOf("P"), grouped.childrenByKey.keys)
    }

    // session-sidebar.tsx:631 — while filtering every hit is laid flat (no child grouping).
    @Test
    fun filteringLaysDelegateChildrenFlat() {
        val parent = row("P", live = live("id" to s("sess-p")))
        val child = row("C", live = live("id" to s("sess-c"), "parentSessionId" to s("sess-p")))
        val byWorkspace = mapOf("/w" to listOf(parent, child))
        assertEquals(listOf("P", "C"), keys(SessionSidebar.groupedByWorkspace(byWorkspace, filtering = true).getValue("/w").rows))
        assertEquals(listOf("P"), keys(SessionSidebar.groupedByWorkspace(byWorkspace, filtering = false).getValue("/w").rows))
    }

    // session-sidebar.tsx:641 — child keys (parent listed anywhere) never join a drag order.
    @Test
    fun delegateChildKeysNeedAListedParent() {
        val rows = listOf(
            row("P", live = live("id" to s("sess-p"))),
            row("C", live = live("id" to s("sess-c"), "parentSessionId" to s("sess-p"))),
            row("O", live = live("parentSessionId" to s("sess-gone"))),
        )
        assertEquals(setOf("C"), SessionSidebar.delegateChildKeys(rows))
    }

    // session-sidebar.tsx:117 — the discovered title wins unless renamed in Tether; then live name; then a label.
    @Test
    fun sessionNamePrecedence() {
        val history = JsObj.of("name" to s("  Native title "), "provider" to s("codex"))
        assertEquals("Native title", SessionSidebar.sidebarSessionName(row("a", live = live("name" to s("Live")), history = history)))
        assertEquals("Live", SessionSidebar.sidebarSessionName(row("b", live = live("name" to s("Live"), "nameIsCustom" to JsBool.TRUE), history = history)))
        assertEquals("Codex session", SessionSidebar.sidebarSessionName(row("c", live = live("name" to s("  "), "provider" to s("codex")))))
        // HARNESS_LABELS has no "pi": the web prints `${undefined} session`
        assertEquals("undefined session", SessionSidebar.sidebarSessionName(row("d", live = live("provider" to s("pi")))))
        assertEquals("Chat", SessionSidebar.sidebarSessionName(row("e")))
    }

    // session-sidebar.tsx:113 / :137 — mode and the block badge letter.
    @Test
    fun sessionModeAndWorkspaceInitial() {
        assertEquals(s("sdk"), SessionSidebar.sidebarSessionMode(row("a", live = live("mode" to s("sdk")))))
        assertEquals(s("headless"), SessionSidebar.sidebarSessionMode(row("h", history = JsObj.of("name" to s("x")))))
        assertEquals(null, SessionSidebar.sidebarSessionMode(row("n")))
        assertEquals("T", SessionSidebar.workspaceInitial("/home/u/git/tether"))
        assertEquals("F", SessionSidebar.workspaceInitial("/srv/_foo1/"))
        assertEquals("R", SessionSidebar.workspaceInitial("/")) // projectName → "Root"
        assertEquals("_", SessionSidebar.workspaceInitial("/srv/__"))
        assertEquals("É", SessionSidebar.workspaceInitial("/srv/école"))
    }
}
