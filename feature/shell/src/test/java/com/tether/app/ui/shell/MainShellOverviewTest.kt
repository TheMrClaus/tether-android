package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewData
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.overview.OverviewTags
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.SidebarTags
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.2 through MainShell (OVERVIEW_STUDIO_PLAN.md §4, dashboard.tsx:725): showing the Overview,
 * and opening or reviewing from it, never marks a conversation seen. While the Overview shows, the
 * selected session is not on screen, so its settling is not "seen". The hand-offs select the
 * session (attach, as a sidebar pick does) and send no mark-seen of their own. Only once the
 * session view shows a settled session does the session view's own rule (dashboard.tsx:1034-1038)
 * apply — that is the boundary this test pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellOverviewTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(1200, 1000)
    }

    private fun session(id: String, status: String, updatedAt: Long = 1) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = status, startedAt = 1, updatedAt = updatedAt, historyId = "h-$id")

    private val ws = OverviewWorkspace("/w", "w")
    private val request = OverviewPending("s3", "r1", "approval", createdAt = 1, title = "s3", provider = "claude", summary = "Bash")

    private fun feed() = OverviewClientState(
        phase = OverviewPhase.Live, feedId = "f", cursor = 1, awaitingSnapshot = false,
        data = OverviewData(
            page = 0, pageSize = 24, pageCount = 1, totalCards = 2,
            facets = OverviewFacets(workspaces = listOf(OverviewWorkspaceFacet("/w", "w", 3))),
            cards = listOf(
                OverviewCard("s3", title = "s3", provider = "claude", workspace = ws, status = "waiting", attention = OverviewAttention("approval", "Approval needed: Bash"), pending = listOf(request)),
                OverviewCard("s2", title = "s2", provider = "claude", workspace = ws, status = "running"),
                OverviewCard("s1", title = "s1", provider = "claude", workspace = ws, status = "ready"),
            ),
            pending = OverviewPendingPanel(listOf(request), 1, 0),
        ),
    )

    @Test
    fun theOverviewAndItsHandOffsNeverMarkASessionSeen() {
        val client = ShellConsentClient()
        client.show(session("s2", "active"), freshTree())
        client.show(session("s3", "active"), freshTree())
        // s1 is mid-turn (status "active"): its report is not settled, so nothing is seen yet.
        client.show(session("s1", "active"), freshTree())
        client.overviewState.value = feed()
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), client.seenCalls)

        // Open the Overview from the rail: it subscribes, and the selected session leaves the screen.
        rule.onNodeWithTag(SidebarTags.Overview).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(OverviewTags.Root).assertExists()
        assertEquals(listOf("overview-subscribe"), client.feedCalls)

        // s1 settles while only the Overview is on screen: displaying its card is not seeing it.
        client.sessions.value = client.sessions.value.map { if (it.id == "s1") it.copy(status = "idle", updatedAt = 5) else it }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), client.seenCalls)

        // Review a request: the session is opened (attached), nothing is answered or marked seen.
        rule.onNodeWithTag(OverviewTags.review("s3")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("s3", vm.selectedSessionId.value)
        assertEquals(emptyList<String>(), client.seenCalls)
        assertEquals(emptyList<String>(), client.consentCalls)
        assertEquals("leaving the Overview stops its pushes", "overview-unsubscribe", client.feedCalls.last())
        // dashboard.tsx:1406-1414: the session's confirmed projection no longer holds r1: said so.
        assertEquals(com.tether.app.ui.overview.OverviewPresentation.REVIEW_RESOLVED, vm.toast.value?.text)
        vm.dismissToast()

        // Back to the Overview; open a running session: opened, not marked seen.
        rule.onNodeWithTag(SidebarTags.Overview).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(OverviewTags.open("s2")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("s2", vm.selectedSessionId.value)
        assertEquals(listOf("s1", "s3", "s2"), client.attachCalls.toList())
        assertEquals(emptyList<String>(), client.seenCalls)

        // The boundary: once the session VIEW shows a settled session, its own rule marks it seen.
        rule.onNodeWithTag(SidebarTags.Overview).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(OverviewTags.open("s1")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("h-s1"), client.seenCalls.toList())
    }
}
