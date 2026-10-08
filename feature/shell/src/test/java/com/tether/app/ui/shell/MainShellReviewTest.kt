package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.OverviewAttention
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewFacets
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.protocol.model.OverviewWorkspace
import com.tether.app.protocol.model.OverviewWorkspaceFacet
import com.tether.app.protocol.model.ProviderCapabilities
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewData
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.testsupport.runPrefsWrite
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.overview.OverviewPresentation
import com.tether.app.ui.overview.OverviewTags
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-4711 (W22) through MainShell: the hand-off of the Overview's "Review request" (dashboard.tsx:1411-1461 at 29537e0).
 * Every update of the session's projection re-checks the request until the card is shown; one 8 s bound runs from the tap.
 * T4 a request no longer pending says so and focuses nothing; T5 a session that is not live at the tap but is within 8 s
 * lands on the card with no notice; T6 a session that never confirms, and a card that is never drawn, say "Couldn't find"
 * at 8 s and not before. (The centring itself is ReviewFocusTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellReviewTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(1200, 1000)
    }

    private val s3 = AgentSession(id = "s3", provider = "claude", name = "s3", cwd = "/w", status = "waiting", startedAt = 1, updatedAt = 1, historyId = "h-s3")
    private val ws = OverviewWorkspace("/w", "w")
    private val request = OverviewPending("s3", "r1", "approval", createdAt = 1, title = "s3", provider = "claude", summary = "Write")

    private val pendingTree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Add a version file.") },
        ev("approval_request", "t1", ts = 1) {
            put("requestId", "r1"); put("toolId", "w1"); put("name", "Write")
            putJsonObject("input") { put("file_path", "src/version.ts"); put("content", "export const V = 1;\n") }
        },
    )
    private val resolvedTree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Add a version file.") },
    )

    private fun feed() = OverviewClientState(
        phase = OverviewPhase.Live, feedId = "f", cursor = 1, awaitingSnapshot = false,
        data = OverviewData(
            page = 0, pageSize = 24, pageCount = 1, totalCards = 1,
            facets = OverviewFacets(workspaces = listOf(OverviewWorkspaceFacet("/w", "w", 1))),
            cards = listOf(OverviewCard("s3", title = "s3", provider = "claude", workspace = ws, status = "waiting", attention = OverviewAttention("approval", "Approval needed: Write"), pending = listOf(request))),
            pending = OverviewPendingPanel(listOf(request), 1, 0),
        ),
    )

    private fun start(client: ShellConsentClient): TetherViewModel {
        client.overviewState.value = feed()
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        runPrefsWrite { prefs.updatePreferences { com.tether.app.ui.prefs.TetherPreferences.Default } }
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).performClick()
        rule.waitForIdle()
        return vm
    }

    private fun review() {
        rule.onNodeWithTag(OverviewTags.review("s3")).performScrollTo().performClick()
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    /** T4: the confirmed projection no longer holds r1: said so at once; nothing is focused or moved. */
    @Test fun aResolvedRequestSaysSoAndFocusesNothing() {
        val client = ShellConsentClient()
        client.show(s3, resolvedTree)
        val vm = start(client)
        review()
        rule.waitForIdle()
        assertEquals("s3", vm.selectedSessionId.value)
        assertEquals(OverviewPresentation.REVIEW_RESOLVED, vm.toast.value?.text)
        rule.onAllNodes(isFocused()).assertCountEquals(0)
        assertEquals(emptyList<String>(), client.consentCalls)
    }

    /** T5: not live at the tap, live with r1 pending at 3 s: the card is focused, no notice then or at 8 s. */
    @Test fun aSessionThatArrivesWithinEightSecondsLandsOnTheCardWithNoNotice() {
        val client = ShellConsentClient()
        client.sessions.value = listOf(s3)
        val vm = start(client)
        rule.mainClock.autoAdvance = false
        review()
        advance(3_000)
        assertNull("nothing yet", vm.toast.value)
        client.show(s3, pendingTree)
        advance(600)
        rule.onNodeWithTag("approval-card", useUnmergedTree = true).assertIsFocused()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        advance(8_000)
        assertNull("still no notice at 8 s", vm.toast.value)
        assertEquals(emptyList<String>(), client.consentCalls)
        rule.onAllNodesWithTag("approval-allow", useUnmergedTree = true).fetchSemanticsNodes().forEach {
            assertEquals(false, it.config.getOrNull(SemanticsProperties.Focused) == true)
        }
    }

    /** T6: the session never confirms: nothing at 7.9 s, "Couldn't find" at 8 s. */
    @Test fun aSessionThatNeverConfirmsIsNotFoundAtEightSeconds() {
        val client = ShellConsentClient()
        client.sessions.value = listOf(s3)
        val vm = start(client)
        rule.mainClock.autoAdvance = false
        review()
        advance(7_900)
        assertNull("no notice at 7.9 s", vm.toast.value)
        advance(300)
        assertEquals(OverviewPresentation.REVIEW_NOT_FOUND, vm.toast.value?.text)
        rule.onAllNodes(isFocused()).assertCountEquals(0)
    }

    /** T6: pending but never drawn (a provider without interactive approvals draws no card): not found at 8 s. */
    @Test fun aPendingRequestThatIsNeverDrawnIsNotFoundAtEightSeconds() {
        val client = ShellConsentClient()
        client.providerList.value = listOf(ProviderInfo("claude", "Claude", "C", available = true, capabilities = ProviderCapabilities(interactiveApprovals = false)))
        client.show(s3, pendingTree)
        val vm = start(client)
        rule.mainClock.autoAdvance = false
        review()
        advance(7_900)
        assertNull("no notice at 7.9 s", vm.toast.value)
        advance(300)
        assertEquals(OverviewPresentation.REVIEW_NOT_FOUND, vm.toast.value?.text)
        rule.onAllNodes(isFocused()).assertCountEquals(0)
    }
}
