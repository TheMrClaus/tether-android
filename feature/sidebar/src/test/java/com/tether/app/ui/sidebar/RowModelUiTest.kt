package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.client.Handover
import com.tether.app.protocol.helpers.SidebarRowModel
import com.tether.app.protocol.helpers.SessionSidebar as Web
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S3a: the row's badge, "Handed over · age", kebab a11y and the workspace counts, on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RowModelUiTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun show(state: SidebarState, seed: SidebarUiSeed = SidebarUiSeed()) {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state, TetherLayoutClass.Phone, seed, SidebarActions()) }
        rule.waitForIdle()
    }

    private fun description(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    /** The row's nav button node (the merged description lives there, not on the row's tagged box): the one starting with the session's name. */
    private fun rowNode(name: String) = rule.onNode(
        androidx.compose.ui.test.SemanticsMatcher("row description of $name") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { it == name || it.startsWith("$name, ") } == true
        },
    ).fetchSemanticsNode()
    private fun rowDescription(name: String) = rowNode(name).config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    private fun state(node: String, prop: androidx.compose.ui.semantics.SemanticsPropertyKey<String>) = rule.onNodeWithTag(node).fetchSemanticsNode().config.getOrNull(prop)

    @Test fun aDigestRowWearsItsCountBadgeAndReadsItInItsDescription() {
        show(F.state(F.statusSessions, histories = F.statusHistories))
        rule.onAllNodesWithText("3 new turns", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        val d = rowDescription("Finished while you were away")!!
        assertTrue(d, d.contains(", 3 new turns since you left"))
        assertTrue("the digest's snippet is the end of the description: $d", d.endsWith(". All four tests pass; the backoff is 250ms."))
        assertFalse("the old cue is replaced by the badge text: $d", d.contains("changed since you last looked"))
    }

    @Test fun aGroupHeadWearsCurrentAndItsDescriptionSaysSoWithoutSelecting() {
        show(F.state(F.chainSessions, current = F.APP, activeId = "solo"))
        rule.onAllNodesWithText("Current", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals("two chains, two heads", 2, it.size) }
        val d = rowDescription("Ship the importer")!!
        assertTrue(d, d.endsWith("Current — latest session in this group"))
        assertNull("Current never selects the row", rowNode("Ship the importer").config.getOrNull(SemanticsProperties.Selected))
    }

    @Test fun theBadgeAppearsExactlyWhereTheOldUnseenDotDid() {
        val s = F.state(F.statusSessions, histories = F.statusHistories)
        for (e in s.sidebarSessions) {
            val facts = if (androidx.compose.runtime.snapshots.Snapshot.current.let { true } && com.tether.app.protocol.fold.truthy(e.js["digest"])) SidebarRowModel.RowDigestFacts(e.history?.digest?.newTurns ?: 0) else null
            val badge = SidebarRowModel.rowBadge(unread = e.unread, digest = facts, isGroupHead = false)
            assertEquals("${e.key}: badge in {turns,new} <=> hasUnseenWork", Web.hasUnseenWork(e.js), badge != null)
        }
    }

    @Test fun aHandedOverMemberSaysHandedOverAndItsAgeWithOneSharedSeparator() {
        show(F.state(F.chainSessions, current = F.APP, activeId = "c3"), SidebarUiSeed(openChains = setOf("live:c0")))
        // c2 was handed off to c3, which started 5 minutes before now: age = the successor's start
        rule.onAllNodesWithText("Handed over · 5m", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        val d = rowDescription("Build the importer")!!
        assertTrue(d, d.contains(", handed off to Ship the importer"))
        assertTrue(d, d.contains(", Handed over, 5m"))
        assertTrue("no glyph after the title any more", rule.onAllNodesWithTag("sidebar-handoff-glyph", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theKebabIsOneNamedButtonWithItsMenuState() {
        show(F.state(F.statusSessions, histories = F.statusHistories))
        val node = rule.onNodeWithTag(SidebarTags.kebab("live:a1")).fetchSemanticsNode().config
        assertEquals("Actions for Refactor the retry loop", node.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.getOrNull(SemanticsProperties.Role))
        assertEquals("Menu closed", node.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithTag(SidebarTags.kebab("live:a1")).performClick()
        rule.waitForIdle()
        assertEquals("Menu open", rule.onNodeWithTag(SidebarTags.kebab("live:a1")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        val pane = rule.onNodeWithTag(SidebarTags.RowMenu, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.PaneTitle)
        assertEquals("Actions for Refactor the retry loop", pane)
        // every item is a Button; the digest block is not actionable
        for (id in listOf(SidebarRowModel.RowMenuItemId.Rename, SidebarRowModel.RowMenuItemId.End)) {
            val c = rule.onNodeWithTag(SidebarTags.menuItem(id), useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals(androidx.compose.ui.semantics.Role.Button, c.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test fun aDisabledMenuItemStaysFocusableAndSaysDisabled() {
        show(F.state(F.statusSessions, histories = F.statusHistories, activeId = "a1").copy(activeHandover = Handover.MenuState.Blocked("Read-only session.")))
        rule.onNodeWithTag(SidebarTags.kebab("live:a1")).performClick()
        rule.waitForIdle()
        val c = rule.onNodeWithTag(SidebarTags.menuItem(SidebarRowModel.RowMenuItemId.Handover), useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue("disabled", c.contains(SemanticsProperties.Disabled))
        assertTrue("still a click target (focusable, does nothing)", c.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null)
    }

    @Test fun theWorkspaceCountFollowsTheNameAndMergesIntoItsToggle() {
        show(F.state(F.groupSessions, pinned = listOf(F.DOCS), current = F.APP))
        // DOCS: g1, g2 (g8 is archived, not counted); APP: g3, g4, g5 (g7 archived)
        assertEquals("2 sessions", description(SidebarTags.workspaceCount(F.DOCS)))
        assertEquals("3 sessions", description(SidebarTags.workspaceCount(F.APP)))
    }

    @Test fun theTitleCountIsTheSumOfTheBlocksAndNoLensMovesAnyCount() {
        val base = F.state(F.groupSessions, pinned = listOf(F.DOCS), current = F.APP, histories = mapOf(F.APP to listOf(F.history("h1", "Old thread", ago = 5000, seenAgo = 4000))))
        val all = SidebarViewModel.view(base)
        assertEquals("title = sum of the blocks", all.openCount, all.blocks.sumOf { it.openCount })
        assertEquals("archived rows are not counted (DOCS 2, APP 3 + 1 history)", 6, all.openCount)
        val lenses = listOf(base.copy(activeOnly = true), base.copy(unreadOnly = true), base.copy(query = "docs"), base.copy(hideAgentRuns = false), base.copy(collapsed = listOf(F.DOCS)))
        for (s in lenses) {
            val view = SidebarViewModel.view(s)
            assertEquals("the title does not move", all.openCount, view.openCount)
            for (b in view.blocks) assertEquals("${b.workspace} count is lens-independent", all.blocks.single { it.workspace == b.workspace }.openCount, b.openCount)
        }
    }
}
