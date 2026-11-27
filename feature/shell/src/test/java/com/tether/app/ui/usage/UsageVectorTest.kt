package com.tether.app.ui.usage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.UsageCall
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.usage.UsageFixtures.ORIGIN
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8E (Vector m04 / m05) on a 412dp phone: the tabbed breakdown (one panel at a time, tab semantics as the web's
 * PHONE_TABS), the leaders line, and the Limits engine chips, "N% 5h" badges and subtitle + Refresh row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h4000dp-420dpi")
class UsageVectorTest {
    @get:Rule val rule = createComposeRule()

    private fun setUp(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme(ThemeMode.Light) { CompositionLocalProvider(LocalUsageEnv provides UsageFixtures.env, content = content) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    /** On the paused clock the press lands in the first step and composes in the second. */
    private fun advance(ms: Long = 100) {
        repeat(2) {
            rule.mainClock.advanceTimeBy(ms)
            rule.waitForIdle()
        }
    }

    private fun page() = setUp {
        val state = remember { UsageDashboardState().apply { onResult(range, UsageCall.Ok(UsageFixtures.analytics(), ORIGIN)) } }
        UsageDashboard(state, onOpenConsole = {})
    }

    @Test fun theBreakdownIsOneTabPanelAtATimeStartingOnHarness() {
        page()
        UsageTab.entries.forEach { rule.onNodeWithTag(UsageTags.tab(it)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)) }
        rule.onNodeWithTag(UsageTags.tab(UsageTab.Harness)).assertIsSelected()
        rule.onAllNodesWithTag(UsageTags.card("Tokens by harness")).assertCountEquals(1)
        rule.onAllNodesWithTag(UsageTags.card("Most-used agents")).assertCountEquals(0)
        rule.onAllNodesWithTag(UsageTags.Heatmap).assertCountEquals(0)

        rule.onNodeWithTag(UsageTags.tab(UsageTab.Agents)).performClick()
        advance()
        rule.onNodeWithTag(UsageTags.tab(UsageTab.Agents)).assertIsSelected()
        rule.onAllNodesWithTag(UsageTags.card("Tokens by harness")).assertCountEquals(0)
        rule.onAllNodesWithTag(UsageTags.card("Most-used agents")).assertCountEquals(1)
        rule.onAllNodesWithTag(UsageTags.card("Most-used tools")).assertCountEquals(1)

        rule.onNodeWithTag(UsageTags.tab(UsageTab.Heatmap)).performClick()
        advance()
        rule.onAllNodesWithTag(UsageTags.Heatmap).assertCountEquals(1)
        rule.onAllNodesWithTag(UsageTags.card("Most-used agents")).assertCountEquals(0)

        rule.onNodeWithTag(UsageTags.tab(UsageTab.Sessions)).performClick()
        advance()
        rule.onAllNodesWithTag(UsageTags.Table).assertCountEquals(1)
        rule.onAllNodesWithTag(UsageTags.Heatmap).assertCountEquals(0)
    }

    @Test fun theLeadersLineNamesHarnessModelAgentAndProviderOnThePhone() {
        page()
        rule.onNodeWithTag(UsageTags.Leaders).assertExists()
        listOf("Harness:", "Model:", "Agent:", "Provider:").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithTag(UsageTags.Subtitle).assertExists()
    }

    @Test fun theTileFiguresAreSetInTheMonoFamily() {
        var mono: androidx.compose.ui.text.font.FontFamily? = null
        setUp {
            mono = com.tether.app.ui.theme.LocalTetherTypography.current.mono
            val state = remember { UsageDashboardState().apply { onResult(range, UsageCall.Ok(UsageFixtures.analytics(), ORIGIN)) } }
            UsageDashboard(state, onOpenConsole = {})
        }
        val styles = rule.onAllNodes(useUnmergedTree = true, matcher = SemanticsMatcher("tile figure") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == "28.4M"
        }).fetchSemanticsNodes().mapNotNull { n ->
            val out = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
            out.firstOrNull()?.layoutInput?.style
        }
        assertEquals(true, styles.isNotEmpty())
        styles.forEach { assertEquals(mono, it.fontFamily) }
    }

    private class Harness(val accounts: UsageAccountsState)

    private fun limits(activeEntity: String? = null, source: FakeUsageSource = FakeUsageSource()): Harness {
        var h: Harness? = null
        setUp {
            val scope = rememberCoroutineScope()
            val state = remember { UsageAccountsState(scope, { source }, { ORIGIN }) }
            h = remember { Harness(state) }
            LaunchedEffect(state) { state.refresh() }
            UsageAccountsContent(
                state,
                onUseCredit = {},
                onUseGrant = {},
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                activeEntity = activeEntity,
            )
        }
        advance()
        return h!!
    }

    @Test fun engineChipsFilterTheCardsAndAllBringsThemBack() {
        limits()
        rule.onNodeWithTag(AccountsTags.chip("all")).assertIsOn()
        rule.onNodeWithText("All (6)").assertExists()
        rule.onNodeWithText("Claude (3)").assertExists()
        rule.onNodeWithText("Codex (1)").assertExists()
        rule.onNodeWithText("DeepSeek (2)").assertExists()
        rule.onAllNodesWithTag(AccountsTags.card("default")).assertCountEquals(1)
        rule.onAllNodesWithTag(AccountsTags.card("codex")).assertCountEquals(1)

        rule.onNodeWithTag(AccountsTags.chip("codex")).performClick()
        advance()
        rule.onNodeWithTag(AccountsTags.chip("codex")).assertIsOn()
        rule.onAllNodesWithTag(AccountsTags.card("default")).assertCountEquals(0)
        rule.onAllNodesWithTag(AccountsTags.card("claude-work")).assertCountEquals(0)
        rule.onAllNodesWithTag(AccountsTags.card("codex")).assertCountEquals(1)

        rule.onNodeWithTag(AccountsTags.chip("claude")).performClick()
        advance()
        rule.onAllNodesWithTag(AccountsTags.card("default")).assertCountEquals(1)
        rule.onAllNodesWithTag(AccountsTags.card("codex")).assertCountEquals(0)

        rule.onNodeWithTag(AccountsTags.chip("all")).performClick()
        advance()
        rule.onAllNodesWithTag(AccountsTags.card("default")).assertCountEquals(1)
        rule.onAllNodesWithTag(AccountsTags.card("codex")).assertCountEquals(1)
    }

    @Test fun anAccountsOwnPageHasNoChipsAndTheListKeepsItsSubtitleAndRefresh() {
        limits(activeEntity = "usage:claude:default")
        rule.onAllNodesWithTag(AccountsTags.Chips).assertCountEquals(0)
        rule.onAllNodesWithTag(AccountsTags.Subtitle).assertCountEquals(0)
    }

    @Test fun theListsFirstRowIsTheSubtitleAndALabelledRefresh() {
        val source = FakeUsageSource()
        limits(source = source)
        rule.onNodeWithTag(AccountsTags.Subtitle).assertExists()
        rule.onNodeWithText("Accounts & reset grants across engines").assertExists()
        rule.onNodeWithText("Refresh").assertExists()
        rule.onNodeWithTag(AccountsTags.Refresh).performClick()
        advance()
        assertEquals("all", source.of("accounts").last().a)
    }

    @Test fun aWindowAtOrPastTheUsageToneGetsAHeadBadgeAndAQuietOneGetsNone() {
        limits()
        rule.onNodeWithTag(AccountsTags.badge("default")).assertExists()
        rule.onNodeWithText("91% weekly").assertExists()
        rule.onNodeWithText("78% 5h").assertExists()
        rule.onAllNodesWithTag(AccountsTags.badge("codex")).assertCountEquals(0)
        rule.onAllNodesWithTag(AccountsTags.badge("claude-old")).assertCountEquals(0)
    }
}
