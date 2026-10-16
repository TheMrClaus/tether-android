package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W35 (ta-b6m9): the chain toggle's behaviour in the phone drawer (web components/session-sidebar.tsx @7cc0f38c). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarChainsBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures
    private val head = SidebarTags.chainToggle("live:c3")

    private fun state(active: String? = "c3", sessions: List<com.tether.app.protocol.model.AgentSession> = F.chainSessions, query: String = "") =
        F.state(sessions, current = F.APP, activeId = active, query = query)

    private var input: androidx.compose.ui.input.InputModeManager? = null

    private fun setSidebar(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            input = androidx.compose.ui.platform.LocalInputModeManager.current
            content()
        }
    }

    /** A clickable only takes focus in keyboard mode, as on a device with a keyboard. */
    private fun focusToggle() {
        rule.runOnIdle { input!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        rule.onNodeWithTag(head).requestFocus()
    }

    private fun show(state: SidebarState, seed: SidebarUiSeed = SidebarUiSeed(), layout: TetherLayoutClass = TetherLayoutClass.Phone) {
        setSidebar { SidebarUnderTest(TetherSkin.StudioDark, state, layout, seed) }
        rule.waitForIdle()
    }

    private fun rowShown(key: String) = rule.onAllNodesWithTag(SidebarTags.row(key), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun description(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    private fun expandedState(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun aTapOpensAndClosesTheEarlierSessionsAndClosedRowsAreNotInSemantics() {
        show(state())
        assertEquals("Collapsed", expandedState(head))
        assertTrue(!rowShown("live:c2") && !rowShown("live:c0"))
        rule.onNodeWithTag(head).performClick()
        assertEquals("Expanded", expandedState(head))
        assertTrue(rowShown("live:c2") && rowShown("live:c1") && rowShown("live:c0"))
        rule.onNodeWithTag(head).performClick()
        assertTrue(!rowShown("live:c2"))
    }

    @Test fun theAccessibleNameIsTheLabelOfTheHeadAndAnyHiddenSuffix() {
        show(state())
        assertEquals("3 earlier sessions of Ship the importer", description(head))
        assertEquals("1 earlier session of Polish the guide", description(SidebarTags.chainToggle("live:p1")))
    }

    @Test fun aWaitingHiddenMemberSaysSoInTheNameAndOpensOnMount() {
        show(F.state(F.chain(3, "w", ago = 5, status = { if (it == 0) "waiting" else "ready" }), current = F.APP))
        assertTrue("a hidden member that needs you opens the chain on mount", rowShown("live:w0"))
        assertEquals("Expanded", expandedState(SidebarTags.chainToggle("live:w2")))
    }

    @Test fun aSeededClosedChainShowsOneSuffixAndPrecedenceNeedsYouActiveNew() {
        val sessions = F.chain(3, "w", ago = 5, status = { if (it == 0) "waiting" else if (it == 1) "active" else "ready" })
        show(
            F.state(sessions, current = F.APP),
            SidebarUiSeed(seenChains = mapOf("live:w0" to listOf("live:w0", "live:w1"))),
        )
        assertEquals("2 earlier sessions of Chain w2, 1 needs you", description(SidebarTags.chainToggle("live:w2")))
    }

    @Test fun enterAndSpaceToggleAndFocusStaysOnTheToggle() {
        show(state())
        focusToggle()
        rule.onNodeWithTag(head).assertIsFocused()
        rule.onNodeWithTag(head).performKeyInput { pressKey(Key.Enter) }
        assertEquals("Expanded", expandedState(head))
        rule.onNodeWithTag(head).assertIsFocused()
        rule.onNodeWithTag(head).performKeyInput { pressKey(Key.Spacebar) }
        assertEquals("Collapsed", expandedState(head))
        rule.onNodeWithTag(head).assertIsFocused()
    }

    @Test fun anOlderMemberBecomingSelectedOpensTheChainOnTheRisingEdgeAndMovesNoFocus() {
        var current by mutableStateOf(state(active = "c3"))
        setSidebar { SidebarUnderTest(TetherSkin.StudioDark, current) }
        rule.waitForIdle()
        focusToggle()
        assertTrue(!rowShown("live:c1"))
        current = state(active = "c1")
        rule.waitForIdle()
        assertTrue(rowShown("live:c1"))
        rule.onNodeWithTag(head).assertIsFocused()
        // Falling edge: nothing happens, the rows stay.
        current = state(active = "c3")
        rule.waitForIdle()
        assertTrue(rowShown("live:c1"))
    }

    @Test fun aUserCollapseHoldsWhileAReasonHoldsUntilANewKeyRises() {
        var current by mutableStateOf(state(active = "c1"))
        setSidebar { SidebarUnderTest(TetherSkin.StudioDark, current) }
        rule.waitForIdle()
        assertTrue(rowShown("live:c1"))
        rule.onNodeWithTag(head).performClick()
        assertTrue(!rowShown("live:c1"))
        current = state(active = "c1") // same reasons: respected
        rule.waitForIdle()
        assertTrue(!rowShown("live:c1"))
        current = state(active = "c0") // a new key rises
        rule.waitForIdle()
        assertTrue(rowShown("live:c0"))
    }

    @Test fun theOpenStateSurvivesANewHandoverThatChangesTheHead() {
        var current by mutableStateOf(state())
        setSidebar { SidebarUnderTest(TetherSkin.StudioDark, current) }
        rule.waitForIdle()
        rule.onNodeWithTag(head).performClick()
        assertTrue(rowShown("live:c2"))
        current = state(sessions = F.chainSessions.map { it } + F.live("c4", "Chain c4", ago = 1, handedOffFrom = "c3"), active = "c4")
        rule.waitForIdle()
        val newHead = SidebarTags.chainToggle("live:c4")
        assertEquals("Expanded", expandedState(newHead))
        assertTrue(rowShown("live:c3") && rowShown("live:c0"))
    }

    @Test fun aFilterFlattensEveryMatchWithNoToggle() {
        show(state(query = "importer"))
        assertEquals(0, rule.onAllNodesWithTag(head, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(rowShown("live:c3") && rowShown("live:c2") && rowShown("live:c1"))
    }

    @Test fun anArchivedMiddleSplitsTheChain() {
        val sessions = F.chain(4, "x", ago = 5, archivedAt = setOf(1))
        show(F.state(sessions, current = F.APP))
        assertEquals("1 earlier session of Chain x3", description(SidebarTags.chainToggle("live:x3")))
        assertTrue(rowShown("live:x0") && rule.onAllNodesWithTag(SidebarTags.chainToggle("live:x0"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aDragPreviewKeepsTheToggleAndTheHeadMovesAsOneUnit() {
        show(state(), SidebarUiSeed(drag = DragPreview("live:c3", listOf("live:solo", "live:c3", "live:p1"))))
        rule.onNodeWithTag(head).assertExists()
        val tops = listOf("live:solo", "live:c3").map { rule.onNodeWithTag(SidebarTags.row(it)).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("the head sits below the solo row", tops[0] < tops[1])
        assertTrue(rule.onNodeWithTag(head).fetchSemanticsNode().boundsInRoot.top > tops[1])
    }

    @Test fun theToggleIsAtLeast44dpInTheRailAnd48dpOnThePhone() {
        show(state())
        val phone = rule.onNodeWithTag(head).fetchSemanticsNode().size.height
        assertTrue(phone >= with(rule.density) { 48.dp.toPx() } - 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SidebarChainsRailTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theRailToggleIsAtLeast44dp() {
        rule.setContent {
            SidebarUnderTest(TetherSkin.StudioDark, SidebarFixtures.state(SidebarFixtures.chainSessions, current = SidebarFixtures.APP, activeId = "c3"), TetherLayoutClass.Expanded)
        }
        rule.waitForIdle()
        val h = rule.onNodeWithTag(SidebarTags.chainToggle("live:c3")).fetchSemanticsNode().size.height
        assertTrue("toggle is $h px", h >= with(rule.density) { 44.dp.toPx() } - 0.5f)
    }
}
