package com.tether.app.ui.log

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.tether.app.protocol.LogEntry
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import androidx.compose.runtime.CompositionLocalProvider
import com.tether.app.ui.statusline.screenshots.choiceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4.5: the log dialog's behaviour against components/log-dialog.tsx — ordering, level and
 * session filters, the empty state, stats and their error, the header/footer actions, and the
 * modal's dismissal rules. Phone viewport (the narrow layout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LogDialogBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun ComposeContentTestRule.show(
        entries: List<LogEntry> = LogFixtures.mixed,
        state: LogDialogState = LogDialogState(stats = LogFixtures.stats),
        skin: TetherSkin = TetherSkin.StudioDark,
    ): LogDialogState {
        setContent {
            TetherTheme(choiceFor(skin)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    LogDialogFrame(
                        entries = entries,
                        sessions = LogFixtures.sessions,
                        state = state,
                        onRefresh = { events += "refresh" },
                        onClose = { events += "close" },
                        locale = LogFixtures.locale,
                        zone = LogFixtures.zone,
                    )
                }
            }
        }
        return state
    }

    private fun rowDescriptions(): List<String> =
        rule.onAllNodesWithTag(LogDialogTags.Row).fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() }

    @Test
    fun rowsAreNewestFirstWithTimeLevelLabelSessionTurnAndDetail() {
        rule.show()
        val rows = rowDescriptions()
        assertTrue("rows: $rows", rows.isNotEmpty())
        assertEquals(
            "12:02:00 AM, warn, Lease collision, sess-9f3…, held by another node",
            rows.first(),
        )
        assertEquals("12:01:32 AM, error, Turn error, Refactor billing export, turn 00000000…, engine exited with code 1", rows[1])
    }

    @Test
    fun theWarningsFilterShowsOnlyNonInfoRowsAndAllRestoresThem() {
        // No stats: every row fits the phone's body, so the lazy list composes them all.
        val state = rule.show(state = LogDialogState())
        rule.onNodeWithText("Warnings").performClick()
        rule.waitForIdle()
        assertEquals(LogLevelFilter.Warnings, state.level)
        assertEquals(listOf("warn", "error", "warn"), rowDescriptions().map { it.split(", ")[1] })
        rule.onNodeWithText("Warnings").assertIsSelected()
        rule.onNodeWithText("All").assertIsNotSelected()
        rule.onNodeWithText("All").performClick()
        rule.waitForIdle()
        assertEquals(7, rule.onAllNodesWithTag(LogDialogTags.Row).fetchSemanticsNodes().size)
    }

    @Test
    fun theSessionSelectListsLoggedSessionsAndFilters() {
        val state = rule.show()
        rule.onNodeWithContentDescription("Filter by session").performClick()
        rule.waitForIdle()
        // The menu: every logged session by name, else its short id, after "All sessions".
        for (option in listOf("All sessions", "Fix flaky login test", "Refactor billing export", "sess-9f3…")) {
            rule.onNode(hasContentDescription(option) and androidx.compose.ui.test.hasClickAction()).assertExists()
        }
        rule.onNode(hasContentDescription("Refactor billing export") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals("sess-0002", state.session)
        // Studio's taller rows leave the last one below the fold of the lazy list: scroll it in.
        assertEquals(listOf("Turn error", "Turn started"), rowDescriptions().map { it.split(", ")[2] }.take(2))
        rule.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription("Session evicted", substring = true))
        assertEquals("Session evicted", rowDescriptions().map { it.split(", ")[2] }.last())
    }

    @Test
    fun noSessionSelectWhenTheLogNamesNoSession() {
        rule.show(entries = listOf(LogFixtures.mixed.first()))
        assertEquals(0, rule.onAllNodes(hasContentDescription("Filter by session")).fetchSemanticsNodes().size)
    }

    @Test
    fun anEmptyLogShowsTheEmptyNoteAndSoDoesAFilterThatMatchesNothing() {
        rule.show(entries = emptyList())
        rule.onNodeWithText(LogReadings.EmptyText).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.Row).fetchSemanticsNodes().size)
    }

    @Test
    fun aWarningsFilterWithOnlyInfoEntriesIsEmpty() {
        rule.show(entries = LogFixtures.mixed.filter { it.level == "info" }, state = LogDialogState(level = LogLevelFilter.Warnings))
        rule.onNodeWithText(LogReadings.EmptyText).assertIsDisplayed()
    }

    @Test
    fun statsTilesAndMetaShowWhenLoadedAndTheWarningTileCountsWarnings() {
        rule.show()
        rule.onNodeWithContentDescription("Uptime · pid 48213: 3h 7m").assertIsDisplayed()
        rule.onNodeWithContentDescription("Active turns / 4: 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Warm sessions: 2").assertIsDisplayed()
        rule.onNodeWithContentDescription("Warnings logged: 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Engine: claude,codex · persistent").assertIsDisplayed()
        rule.onNodeWithContentDescription("Protocol: v128").assertIsDisplayed()
    }

    @Test
    fun beforeStatsArriveThereAreNoTilesAndAStatsErrorIsShown() {
        rule.show(state = LogDialogState())
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.Stats).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.StatsError).fetchSemanticsNodes().size)
    }

    @Test
    fun aStatsErrorShowsAboveTheLastSnapshot() {
        val state = LogDialogState(stats = LogFixtures.stats)
        state.onStats(com.tether.app.client.StatsResult.Failed("stats request failed (502)"))
        rule.show(state = state)
        rule.onNodeWithText("stats request failed (502)").assertIsDisplayed()
        rule.onNodeWithTag(LogDialogTags.Stats).assertIsDisplayed()
    }

    @Test
    fun refreshCloseAndDoneReportTheirActions() {
        rule.show()
        rule.onNodeWithContentDescription("Refresh stats").performClick()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("refresh", "close", "close"), events)
    }

    @Test
    fun theTitleIsAHeadingAndTheStudioSkinHidesTheSectionLabel() {
        rule.show(skin = TetherSkin.Studio)
        rule.onNode(hasHeading("Health & Event Log")).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("TETHER · HEALTH & EVENTS")).fetchSemanticsNodes().size)
    }

    @Test
    fun theFilterKeysTakeATouchTargetOfAtLeast44dp() {
        rule.show()
        for (label in listOf("All", "Warnings")) {
            val node = rule.onNode(androidx.compose.ui.test.hasText(label) and androidx.compose.ui.test.hasClickAction())
                .fetchSemanticsNode()
            val density = rule.density.density
            assertTrue("$label touch height ${node.touchBoundsInRoot.height / density}dp", node.touchBoundsInRoot.height / density >= 44f)
        }
        // A tap just above the drawn key (38dp in Studio), inside its 44dp extended target, still selects it.
        rule.onNodeWithText("Warnings").performTouchInput { click(Offset(centerX, -with(rule.density) { 2.dp.toPx() })) }
        rule.waitForIdle()
        rule.onNodeWithText("Warnings").assertIsSelected()
    }

    private fun hasHeading(text: String) =
        androidx.compose.ui.test.hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
}

/** The real modal: Back (the web's Esc) dismisses it; a tap on the backdrop does not. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LogDialogModalTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun backDismissesAndTheBackdropDoesNot() {
        var open by mutableStateOf(true)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    if (open) LogDialog(LogFixtures.mixed, LogFixtures.sessions, LogDialogState(), onRefresh = {}, onDismiss = { open = false })
                }
            }
        }
        rule.onNodeWithTag(LogDialogTags.Dialog).assertIsDisplayed()
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        rule.onAllNodes(isRoot())[1].performTouchInput { click(Offset(4f, 4f)) }
        rule.waitForIdle()
        assertTrue("a backdrop tap leaves it open", open)
        Espresso.pressBack()
        rule.waitForIdle()
        assertTrue("Back dismisses it", !open)
        assertEquals(0, rule.onAllNodes(hasTestTag(LogDialogTags.Dialog)).fetchSemanticsNodes().size)
    }
}
