package com.tether.app.ui.usage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.tether.app.client.ClaudeClaimAnswer
import com.tether.app.client.CodexConsumeAnswer
import com.tether.app.client.FixedRouteHttp
import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import com.tether.app.client.UsageSource
import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.UsageWindow
import com.tether.app.ui.inspector.Inspector
import com.tether.app.ui.inspector.InspectorBoards
import com.tether.app.ui.inspector.codexResetRequest
import com.tether.app.ui.shell.LinkReadout
import com.tether.app.ui.shell.ShellTags
import com.tether.app.ui.shell.TopbarMenu
import com.tether.app.ui.shell.TopBarDestination
import com.tether.app.ui.shell.TopbarActions
import com.tether.app.ui.shell.TopbarState
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.usage.UsageFixtures.ORIGIN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T9.2 behaviour, on the phone and the tablet layouts: the Usage page's fetches (range, Refresh,
 * the 15 s poll, Try again), the Accounts dialog's refreshes (open, all, per card, the bounded
 * auto-retry), both reset confirmations end to end (the request sent, the answer shown, the
 * refresh after), the top bar's live Usage and Accounts, the inspector's Use reset request and the
 * DeepSeek header badge's gate.
 */
abstract class UsageBehaviourBase {
    @get:Rule val rule = createComposeRule()

    private fun setUp(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalUsageEnv provides UsageFixtures.env, content = content)
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    /**
     * Brings the node into its nearest scrolling ancestor's viewport on the paused clock. The
     * library's performScrollTo awaits the suspending scroll, whose animation needs frames that a
     * paused clock never produces, so it blocks forever; the plain ScrollBy action only launches
     * the scroll, and the frames are then driven here.
     */
    private fun SemanticsNodeInteraction.scrollIntoView(): SemanticsNodeInteraction {
        repeat(4) {
            val node = fetchSemanticsNode()
            var container = node.parent
            while (container != null && SemanticsActions.ScrollBy !in container.config) container = container.parent
            checkNotNull(container) { "no scrolling ancestor" }
            val viewport = container.boundsInRoot
            // boundsInRoot is clipped by the scrolling ancestors; the unclipped box is what must come into view.
            val bounds = androidx.compose.ui.geometry.Rect(node.positionInRoot, node.size.toSize())
            val delta = when {
                bounds.top < viewport.top -> bounds.top - viewport.top
                bounds.bottom > viewport.bottom -> minOf(bounds.bottom - viewport.bottom, bounds.top - viewport.top)
                else -> return this
            }
            val scroll = container.config[SemanticsActions.ScrollBy].action!!
            rule.runOnUiThread { scroll(0f, delta) }
            advance(1_000)
        }
        val node = fetchSemanticsNode()
        error("still outside its viewport after scrolling: ${node.boundsInRoot}")
    }

    // ── The Usage page ───────────────────────────────────────────────────────

    @Test fun thePageFetchesTheRangeThenPollsAndRefreshes() {
        val source = FakeUsageSource()
        var console = 0
        setUp { UsagePage(source, ORIGIN, onOpenConsole = { console++ }) }
        assertEquals(listOf(FakeUsageSource.Call("analytics", ORIGIN, "2026-09-12", null)), source.of("analytics"))
        rule.onNodeWithText("Usage analytics").assertExists()
        rule.onNodeWithText("48 sessions · 1,260 turns").assertExists()
        rule.onNodeWithText("28.4M").assertExists()
        rule.onNodeWithText("Updated 07:00 AM · refreshes automatically").assertExists()

        rule.onNodeWithTag(UsageTags.range(UsageRange.Week)).performClick()
        advance(50)
        assertEquals("2026-10-05", source.of("analytics").last().a)
        rule.onNodeWithTag(UsageTags.range(UsageRange.All)).performClick()
        advance(50)
        assertNull("all time sends no since", source.of("analytics").last().a)

        val before = source.of("analytics").size
        rule.onNodeWithTag(UsageTags.Refresh).performClick()
        advance(50)
        assertEquals(before + 1, source.of("analytics").size)
        advance(UsageDashboardModel.POLL_MS)
        assertEquals("the page polls at the server TTL", before + 2, source.of("analytics").size)
        assertEquals(0, console)
    }

    @Test fun aFailedLoadSaysSoAndTryAgainFetches() {
        val source = FakeUsageSource().apply { analyticsAnswers += UsageCall.Failed(UsageFailure.Http(503, null)) }
        setUp { UsagePage(source, ORIGIN, onOpenConsole = {}) }
        rule.onNodeWithText("Could not update usage. HTTP 503").assertExists()
        rule.onNodeWithText("Update unavailable").assertExists()
        rule.onNodeWithTag(UsageTags.TryAgain).performClick()
        advance(50)
        assertEquals(2, source.of("analytics").size)
        rule.onNodeWithTag(UsageTags.Error).assertDoesNotExist()
        rule.onNodeWithText("28.4M").assertExists()
    }

    @Test fun anEmptyRangeOffersTheConsole() {
        val source = FakeUsageSource().apply { analyticsAnswers += UsageCall.Ok(UsageFixtures.analytics(UsageFixtures.EMPTY_JSON), ORIGIN) }
        var console = 0
        setUp { UsagePage(source, ORIGIN, onOpenConsole = { console++ }) }
        rule.onNodeWithText("No usage in this range").assertExists()
        rule.onNodeWithTag(UsageTags.OpenConsole).performClick()
        assertEquals(1, console)
    }

    @Test fun aLongBreakdownShowsEightUntilShowAll() {
        setUp { UsagePage(FakeUsageSource(), ORIGIN, onOpenConsole = {}) }
        // usage-dashboard.tsx:291: past eight rows a breakdown folds; tools are sliced to eight first (:206), so never fold.
        rule.onAllNodesWithTag(UsageTags.showMore("Most-used tools")).assertCountEquals(0)
        val more = rule.onNodeWithTag(UsageTags.showMore("Tokens by model"))
        more.scrollIntoView()
        rule.onNodeWithText("Show all 10").assertExists()
        rule.onAllNodesWithContentDescriptionPrefix("kimi-k2").assertCountEquals(0)
        more.performClick()
        // On the paused clock the press lands in the first step and the page recomposes in the second.
        advance(50)
        advance(50)
        rule.onNodeWithText("Show fewer").assertExists()
        rule.onAllNodesWithContentDescriptionPrefix("kimi-k2").assertCountEquals(1)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionPrefix(prefix: String) =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher("cd starts with $prefix") { node ->
            node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.any { it.startsWith("$prefix:") } == true
        })

    // ── The Accounts dialog ──────────────────────────────────────────────────

    private class Harness(val accounts: UsageAccountsState, val codex: CodexResetState, val claude: ClaudeResetState)

    private fun accountsDialog(source: UsageSource): Harness {
        var harness: Harness? = null
        setUp {
            val scope = rememberCoroutineScope()
            val h = remember {
                Harness(
                    UsageAccountsState(scope, { source }, { ORIGIN }),
                    CodexResetState(scope, { source }, { ORIGIN }),
                    ClaudeResetState(scope, { source }, { ORIGIN }, clock = { UsageFixtures.NOW }),
                )
            }
            harness = h
            UsageAccountsDialog(h.accounts, h.codex, h.claude)
        }
        harness!!.accounts.open()
        advance(100)
        return harness!!
    }

    @Test fun openingReadsCacheFirstThenRefreshesForceAllAndPerCard() {
        val source = FakeUsageSource()
        accountsDialog(source)
        assertEquals(listOf<String?>(null), source.of("accounts").map { it.a })
        rule.onNodeWithText("Host default").assertExists()
        rule.onNodeWithText("Claude accounts").assertExists()
        rule.onNodeWithText("Limit resets: none — not offered on this plan.").scrollIntoView()
        rule.onNodeWithTag(AccountsTags.Refresh).performClick()
        advance(100)
        assertEquals("all", source.of("accounts").last().a)
        rule.onNodeWithContentDescription("Update Work").scrollIntoView().performClick()
        advance(100)
        assertEquals("claude-work", source.of("accounts").last().a)
        rule.onNodeWithTag(AccountsTags.Done).performClick()
        advance(100)
        rule.onNodeWithTag(AccountsTags.Dialog).assertDoesNotExist()
    }

    @Test fun aFailedLoadRetriesThreeTimesThenGivesUp() {
        val fail = UsageCall.Failed(UsageFailure.Unreachable)
        val source = FakeUsageSource().apply { repeat(4) { accountsAnswers += fail } }
        accountsDialog(source)
        rule.onNodeWithText("Could not reach Tether right now. Retrying automatically…").assertExists()
        advance(3_000)
        advance(8_000)
        advance(20_000)
        assertEquals(4, source.of("accounts").size)
        advance(60_000)
        assertEquals("bounded: no fourth retry", 4, source.of("accounts").size)
        rule.onNodeWithText("Could not reach Tether right now.").assertExists()
    }

    @Test fun aBankedResetIsRedeemedThroughItsConfirmation() {
        val source = FakeUsageSource().apply { consumeAnswers += UsageCall.Ok(CodexConsumeAnswer("reset"), ORIGIN) }
        accountsDialog(source)
        rule.onNodeWithTag(AccountsTags.UseCredit).scrollIntoView().performClick()
        advance(100)
        rule.onNodeWithText("Use a banked reset?").assertExists()
        rule.onNodeWithText("Redeeming instantly restores your weekly rate-limit window. This cannot be undone.").assertExists()
        assertTrue("nothing is sent before the confirmation", source.of("consume").isEmpty())
        val reads = source.of("accounts").size
        rule.onNodeWithTag(ResetTags.Confirm).performClick()
        advance(100)
        assertEquals(listOf(FakeUsageSource.Call("consume", ORIGIN, "cr_1", null)), source.of("consume"))
        rule.onNodeWithText("Reset applied. Your weekly window has been restored.").assertExists()
        assertEquals("a settled answer re-reads usage", reads + 1, source.of("accounts").size)
        assertNull(source.of("accounts").last().a)
        rule.onNodeWithTag(ResetTags.Done).performClick()
        advance(100)
        rule.onNodeWithTag(ResetTags.CodexDialog).assertDoesNotExist()
    }

    @Test fun aRefusedRedeemShowsTheServersSentenceAndRefreshesNothing() {
        val source = FakeUsageSource().apply { consumeAnswers += UsageCall.Failed(UsageFailure.Http(400, "Codex is not enabled on this server.")) }
        accountsDialog(source)
        rule.onNodeWithTag(AccountsTags.UseCredit).scrollIntoView().performClick()
        advance(100)
        val reads = source.of("accounts").size
        rule.onNodeWithTag(ResetTags.Confirm).performClick()
        advance(100)
        rule.onNodeWithText("Codex is not enabled on this server.").assertExists()
        rule.onNodeWithTag(ResetTags.Confirm).assertIsEnabled()
        assertEquals(reads, source.of("accounts").size)
    }

    @Test fun aClaudeGrantIsClaimedThroughItsConfirmation() {
        val answer = ClaudeClaimAnswer(
            FixedRouteHttp.parseObject("""{"ok":true,"outcome":"reset","sent":true,"result":"reset","cleared":["five_hour","seven_day"],"resetsLeft":0}""")!!,
            sent = true,
        )
        val source = FakeUsageSource().apply { claimAnswers += UsageCall.Ok(answer, ORIGIN) }
        accountsDialog(source)
        rule.onNodeWithTag(AccountsTags.useGrant("g_1")).scrollIntoView().performClick()
        advance(100)
        rule.onNodeWithText("Use a limit reset on Host default?").assertExists()
        rule.onNodeWithText("At the limit now (weekly).").assertExists()
        assertTrue(source.of("claim").isEmpty())
        rule.onNodeWithTag(ResetTags.Confirm).performClick()
        advance(100)
        assertEquals(listOf(FakeUsageSource.Call("claim", ORIGIN, "default", "g_1")), source.of("claim"))
        rule.onNodeWithText("Reset applied — refilled your 5-hour limit, weekly limit. 0 resets left.").assertExists()
        assertEquals("a reset re-reads that account, forced", "default", source.of("accounts").last().a)
        rule.onNodeWithTag(ResetTags.Done).assertExists()
    }

    @Test fun anUnsettledClaimOffersRetryAndASpentGrantHasNoKey() {
        val answer = ClaudeClaimAnswer(FixedRouteHttp.parseObject("""{"ok":true,"outcome":"unreachable","sent":true}""")!!, sent = true)
        val source = FakeUsageSource().apply { claimAnswers += UsageCall.Ok(answer, ORIGIN) }
        accountsDialog(source)
        rule.onAllNodesWithTag(AccountsTags.useGrant("g_0")).assertCountEquals(0)
        rule.onNodeWithTag(AccountsTags.useGrant("g_1")).scrollIntoView().performClick()
        advance(100)
        rule.onNodeWithTag(ResetTags.Confirm).performClick()
        advance(100)
        rule.onNodeWithText("Retry").assertExists()
        rule.onAllNodesWithText("Anthropic answered", substring = true).assertCountEquals(0)
    }

    @Test fun whileARedeemIsInFlightTheConfirmationCannotBeDismissed() {
        val gate = CompletableDeferred<UsageCall<CodexConsumeAnswer>>()
        val source = object : UsageSource by FakeUsageSource() {
            override suspend fun consumeResetCredit(origin: String?, creditId: String?, sessionId: String?) = gate.await()
        }
        val h = accountsDialog(source)
        rule.onNodeWithTag(AccountsTags.UseCredit).scrollIntoView().performClick()
        advance(100)
        rule.onNodeWithTag(ResetTags.Confirm).performClick()
        advance(100)
        rule.onNodeWithText("Redeeming…").assertExists()
        rule.onNodeWithTag(ResetTags.Cancel).assertIsNotEnabled()
        h.codex.close()
        advance(100)
        rule.onNodeWithTag(ResetTags.CodexDialog).assertExists()
        gate.complete(UsageCall.Ok(CodexConsumeAnswer("nothingToReset"), ORIGIN))
        advance(100)
        rule.onNodeWithText("There was nothing to reset right now.").assertExists()
    }

    @Test fun anEarlierAttemptsAnswerNeverLandsInALaterOpen() {
        val gate = CompletableDeferred<UsageCall<CodexConsumeAnswer>>()
        val source = object : UsageSource by FakeUsageSource() {
            override suspend fun consumeResetCredit(origin: String?, creditId: String?, sessionId: String?) = gate.await()
        }
        val h = accountsDialog(source)
        rule.onNodeWithTag(AccountsTags.UseCredit).scrollIntoView().performClick()
        advance(100)
        rule.onNodeWithTag(ResetTags.Confirm).performClick()
        advance(100)
        // A new open (the inspector's, say) supersedes the pending attempt.
        h.codex.open(CodexResetRequest(1.0, null, null, sessionId = "s9"))
        gate.complete(UsageCall.Ok(CodexConsumeAnswer("reset"), ORIGIN))
        advance(100)
        assertNull(h.codex.outcome)
        // The confirmation is back at its first stage: its key reads "Use reset" (the row's key behind it too).
        rule.onNodeWithTag(ResetTags.Confirm).assertIsEnabled()
        rule.onAllNodesWithText("Use reset").assertCountEquals(2)
    }

    // ── The top bar, the inspector, the header badge ──────────────────────────

    @Test fun theTopBarsUsageAndAccountsAreLive() {
        var usage = 0
        var accounts = 0
        val actions = TopbarActions(
            onOpenDrawer = {}, onLogout = {}, onOpenUsage = { accounts++ }, onOpenUsageAnalytics = { usage++ }, onOpenLog = {}, onNavigate = {},
            views = setOf(com.tether.app.ui.shell.DashboardView.Overview, com.tether.app.ui.shell.DashboardView.Sessions, com.tether.app.ui.shell.DashboardView.Scheduled),
        )
        // The phone bar folds both into its menu (topbar.tsx narrow): each item is live, with no "not available" reason.
        setUp {
            TopbarMenu(actions, TopbarState(current = TopBarDestination.Usage, link = LinkReadout.Connected, wide = false, menuOpen = true), onDismiss = {})
        }
        rule.onAllNodesWithText(com.tether.app.ui.shell.TopbarReasons.NOT_YET).assertCountEquals(0)
        rule.onNodeWithTag(ShellTags.menuNav(TopBarDestination.Usage)).performClick()
        rule.onNodeWithTag(ShellTags.MenuAccounts).performClick()
        assertEquals(1, usage)
        assertEquals(1, accounts)
    }

    @Test fun theInspectorsUseResetCarriesTheSessionAndItsWindows() {
        val metrics = SessionMetrics(
            fiveHour = UsageWindow(20.0, 300, 1L),
            weekly = UsageWindow(60.0, 10_080, 2L),
            codexResetCredits = FixedRouteHttp.parseObject("""{"availableCount":1,"credits":[{"id":"cr_9","status":"available","resetType":"codexRateLimits"}]}"""),
        )
        val request = codexResetRequest(InspectorBoards.session(provider = "codex", metrics = metrics))!!
        assertEquals("s1", request.sessionId)
        assertEquals("cr_9", request.credits!!.single().id)
        assertEquals(20.0, request.windows!!.fiveHour!!.usedPercent)
        assertNull(codexResetRequest(InspectorBoards.session(provider = "claude", metrics = metrics)))
        assertNull(codexResetRequest(InspectorBoards.session(provider = "codex", metrics = metrics.copy(codexResetCredits = JsonPrimitive(0)))))
    }

    @Test fun theLimitsBandsUseResetOpensTheConfirmation() {
        val metrics = SessionMetrics(
            codexResetCredits = FixedRouteHttp.parseObject("""{"availableCount":1,"credits":[{"id":"cr_9","status":"available","resetType":"codexRateLimits"}]}"""),
        )
        val model = com.tether.app.ui.inspector.InspectorBoards.model(InspectorBoards.session(provider = "codex", metrics = metrics))
        var opened = 0
        setUp {
            androidx.compose.foundation.layout.Column {
                Inspector(model, null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, onUseCodexReset = { opened++ })
            }
        }
        rule.onNodeWithTag(com.tether.app.ui.inspector.InspectorTags.UseCodexReset).performClick()
        advance(50)
        assertEquals("one tap opens it, as on the web", 1, opened)
    }

    @Test fun theDeepSeekBadgeShowsOnlyForASessionTheApiBills() {
        setUp {
            androidx.compose.foundation.layout.Column {
                DeepSeekPeakBadge("dsh", null, DeepSeekPeakVariant.Full)
                DeepSeekPeakBadge("claude", "deepseek-flash", DeepSeekPeakVariant.Full)
                DeepSeekPeakBadge("opencode", "openrouter/deepseek-v4-pro", DeepSeekPeakVariant.Full)
            }
        }
        // The tooltip anchor merges its content (long press shows the rates), so the pill's tag sits in the unmerged tree.
        rule.onAllNodesWithTag(DeepSeekTags.Badge, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithContentDescription("DeepSeek API rate: Peak, 2× rate, Off-peak in 3h 0m").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UsageBehaviourPhoneTest : UsageBehaviourBase()

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class UsageBehaviourTabletTest : UsageBehaviourBase()
