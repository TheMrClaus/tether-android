package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.overview.OverviewTags
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared set-up: a MainShell on its own preference store, so each test's boot inputs are its own. */
@OptIn(ExperimentalTestApi::class)
abstract class NavigationBase(private val width: Int, private val height: Int) {
    // ta-9dpl: the folder is the outer rule, deleted only once the composition is gone: a preference
    // write still on the disk at the end can no longer fail (and fail the test) under a live screen.
    val tmp = TemporaryFolder()
    val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

    private val job = Job()
    protected val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    protected val client = ShellConsentClient()
    protected val vm by lazy { TetherViewModel(client) }

    protected val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(width, height)
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    protected fun session(id: String, status: String = "ready", updatedAt: Long = 1) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = status, startedAt = 1, updatedAt = updatedAt, historyId = "h-$id")

    protected fun launch(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
    }

    protected fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    protected fun awaitTag(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    protected fun selected(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    protected fun back() {
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    protected val backLeavesTheApp: Boolean get() {
        rule.waitForIdle()
        return !rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()
    }

    protected fun storedView(): String? = runBlocking { prefs.viewBoot(null).storedView }

    protected fun onOverview() = awaitTag(OverviewTags.Root)

    /**
     * ta-coik.5 (topbar.tsx 90fbb9f :257 `onClick={runItem(onLogout)}`): Lock signs out on the first
     * tap, with no confirmation, at the tablet and the phone width alike.
     */
    @Test fun lockSignsOutAtOnceLikeTheWeb() {
        launch()
        onOverview()
        assertEquals(0, client.logoutCalls.get())
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        rule.onNodeWithTag(ShellTags.LockKey).performClick()
        rule.waitUntil(5_000) { client.logoutCalls.get() == 1 }
        rule.waitForIdle()
        rule.onNodeWithText("Sign out of this server?").assertDoesNotExist()
        assertEquals("one tap, one sign-out", 1, client.logoutCalls.get())
    }
}

/**
 * T15.4 through MainShell at a tablet width (the expanded shell): the default view for a fresh
 * client, the remembered view and last-session restoration, a notification deep link, Back
 * between the views (Android Back is the web's Back/Forward), rotation, and mark-seen gated by
 * the visible view (lib/dashboard-view.mjs, dashboard.tsx:52-116, 725, 1351-1366).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class MainShellNavigationTest : NavigationBase(1200, 1000) {

    @Test fun aFreshClientStartsOnTheOverviewFullWidth() {
        client.show(session("s1"), freshTree())
        launch()
        onOverview()
        assertTrue(selected(ShellTags.nav(TopBarDestination.Overview)))
        assertFalse(selected(ShellTags.nav(TopBarDestination.Sessions)))
        // dashboard.tsx:1446: no rail (and no dock) on the Overview.
        assertFalse(exists(ShellTags.Sidebar))
        assertFalse(exists(ShellTags.ExpandDock))
        rule.waitUntil(5_000) { storedView() == "overview" }
        assertTrue("the first view has nothing behind it", backLeavesTheApp)
    }

    @Test fun anInstallThatKeptPreferencesKeepsLastSessionRestoration() {
        runBlocking { prefs.updatePreferences { it.copy(showThinking = true) } }
        client.show(session("s1"), freshTree())
        launch()
        awaitTag(ShellTags.Sidebar)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        assertFalse(exists(OverviewTags.Root))
        rule.waitUntil(5_000) { storedView() == "sessions" }
    }

    /**
     * ta-coik.47: each server boots on its own remembered view (the web's `tether:lastView` lives in
     * its per-origin localStorage), a server never used is a fresh origin (the Overview), and a
     * sign-out keeps every server's records, folded blocks and seen stamps included (localStorage
     * survives /login; the shell leaves composition while signed out, as UiRoot shows the sign-in).
     */
    @Test fun eachServerBootsOnItsOwnViewAndASignOutKeepsTheServersRecords() {
        val a = "https://a.example:443"
        val b = "https://b.example:443"
        runBlocking {
            prefs.updatePreferences { it.copy(showThinking = true) }
            prefs.updatePreferencesFor(a) { it.copy(collapsedWorkspaces = listOf("/w/folded"), lastSeenSessions = mapOf("h-seen" to 7L)) }
            prefs.setLastView(a, "scheduled")
            prefs.setLastView(b, "sessions")
        }
        val records = runBlocking { prefs.preferences.first() }
        var signedIn by mutableStateOf(true)
        client.server.value = "https://A.example/"
        rule.setContent {
            if (signedIn) TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        rule.waitUntil(5_000) { exists(ShellTags.nav(TopBarDestination.Scheduled)) && selected(ShellTags.nav(TopBarDestination.Scheduled)) }

        // Sign out: nothing of any server's is forgotten.
        vm.logout()
        rule.waitUntil(5_000) { client.logoutCalls.get() == 1 }
        rule.runOnIdle { signedIn = false }
        rule.waitForIdle()
        assertEquals(records, runBlocking { prefs.preferences.first() })
        assertEquals("scheduled", runBlocking { prefs.viewBoot(a) }.storedView)

        // A server never used here starts on the Overview, though this install kept preferences.
        client.server.value = "https://c.example"
        rule.runOnIdle { signedIn = true }
        onOverview()
        assertTrue(selected(ShellTags.nav(TopBarDestination.Overview)))
        rule.runOnIdle { signedIn = false }
        rule.waitForIdle()

        // Server B boots on its own view; server A's records are as they were.
        client.server.value = "https://b.example"
        rule.runOnIdle { signedIn = true }
        rule.waitUntil(5_000) { exists(ShellTags.nav(TopBarDestination.Sessions)) && selected(ShellTags.nav(TopBarDestination.Sessions)) }
        val after = runBlocking { prefs.preferences.first() }
        assertEquals(listOf("/w/folded"), after.forServer(a).collapsedWorkspaces)
        assertEquals(mapOf("h-seen" to 7L), after.forServer(a).lastSeenSessions)
        assertTrue(after.forServer(b).collapsedWorkspaces.isEmpty())
        assertEquals("scheduled", runBlocking { prefs.viewBoot(a) }.storedView)
        assertEquals("overview", runBlocking { prefs.viewBoot("https://c.example:443") }.storedView)
    }

    @Test fun theRememberedViewIsRestored() {
        runBlocking {
            prefs.updatePreferences { it.copy(showThinking = true) }
            prefs.setLastView(null, "overview")
        }
        launch()
        onOverview()
        assertTrue(selected(ShellTags.nav(TopBarDestination.Overview)))
    }

    @Test fun aRestoredLastSessionIsShownInSessions() {
        // The selection this app keeps (the view model survives the shell) outranks a remembered Overview.
        runBlocking { prefs.setLastView(null, "overview") }
        client.show(session("s1"), freshTree())
        vm.selectSession("s1")
        launch()
        awaitTag(ShellTags.WorkspaceHeader)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        assertFalse(exists(OverviewTags.Root))
    }

    @Test fun aNotificationDeepLinkShowsItsSessionAndBackReturnsToTheOverview() {
        client.show(session("s1"), freshTree())
        launch()
        onOverview()
        // UiRoot applies a notification / deep link as vm.openSession (T4.4).
        rule.runOnIdle { vm.openSession("s1") }
        awaitTag(ShellTags.WorkspaceHeader)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        assertEquals("s1", vm.selectedSessionId.value)
        back()
        onOverview()
        assertTrue(selected(ShellTags.nav(TopBarDestination.Overview)))
        assertTrue(backLeavesTheApp)
    }

    @Test fun aColdStartDeepLinkShowsItsSessionAndBackLeaves() {
        client.show(session("s1"), freshTree())
        vm.openSession("s1") // applied before the shell composed
        launch()
        awaitTag(ShellTags.WorkspaceHeader)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        assertTrue("T4.4: a session opened by a link leaves the app on Back", backLeavesTheApp)
    }

    @Test fun backStepsBetweenTheViewsAndThenLeaves() {
        client.show(session("s1"), freshTree())
        launch()
        onOverview()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Sessions)).performClick()
        awaitTag(ShellTags.Sidebar)
        // Sessions again is not a new step (the web's `if (readView() === next) return`).
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Sessions)).performClick()
        rule.onNodeWithTag(ShellTags.Brand).performClick() // the brand is the Overview link
        onOverview()
        back()
        awaitTag(ShellTags.Sidebar)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        back()
        onOverview()
        assertTrue(backLeavesTheApp)
    }

    @Test fun backFromSessionsReturnsToTheOverviewAndItsSessionComesBack() {
        client.show(session("s1"), freshTree())
        vm.selectSession("s1")
        launch()
        awaitTag(ShellTags.WorkspaceHeader)
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).performClick()
        onOverview()
        assertFalse("the session is not on screen behind the Overview", exists(ShellTags.WorkspaceHeader))
        back()
        // dashboard.tsx:1357: Sessions comes back to the conversation still selected.
        awaitTag(ShellTags.WorkspaceHeader)
        assertEquals("s1", vm.selectedSessionId.value)
        assertTrue(backLeavesTheApp)
    }

    @Test fun usageAndAccountsAreLiveAndFilesNeedsASession() {
        launch()
        onOverview()
        // T9.2: Usage and Accounts are live, as on the web (T9.3's Scheduled too); Files needs a session.
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Scheduled)).assertIsEnabled()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Usage)).assertIsEnabled()
        rule.onNodeWithTag(ShellTags.AccountsKey).assertIsEnabled()
        rule.onNodeWithTag(ShellTags.FilesKey).assertIsNotEnabled()
        assertTrue(selected(ShellTags.nav(TopBarDestination.Overview)))
    }

    /**
     * T9.3 (dashboard.tsx:223, 387-392, 1446, 1587-1600): Scheduled is a live destination: the bar
     * and the rail's key open it beside the rail (the key reads current), its actions reach the
     * client, Open last session shows Sessions, and Back steps back.
     */
    @Test fun scheduledOpensFromTheBarAndTheRailBesideTheRail() {
        client.show(session("s1"), freshTree())
        client.scheduled.value = com.tether.app.client.ScheduledActionsState(
            schedules = listOf(
                com.tether.app.client.ScheduledAction(
                    id = "sch", name = "Triage", prompt = "p", cwd = "/w", provider = "claude", profileId = null, model = null,
                    reasoningEffort = null, permissionMode = null, sandboxPolicy = null, useWorktree = false, cron = "0 9 * * 1-5",
                    timeZone = "UTC", maxRuns = null, status = "active", createdAt = 1, updatedAt = 1, nextRunAt = null, lastRunAt = 2,
                    runs = listOf(com.tether.app.client.ScheduledActionRun("r", "succeeded", 1, 2, "s1", null, false)),
                ),
            ),
            loaded = true,
        )
        launch()
        onOverview()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Scheduled)).performClick()
        awaitTag(com.tether.app.ui.scheduled.ScheduledTags.Root)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Scheduled)))
        assertTrue("the rail stays beside Scheduled", exists(ShellTags.Sidebar))
        assertTrue("the rail's key reads current", selected(com.tether.app.ui.sidebar.SidebarTags.Scheduled))
        rule.waitUntil(5_000) { storedView() == "scheduled" }
        rule.onNodeWithText("Run now").performClick()
        rule.waitForIdle()
        assertEquals(listOf("sch" to "run"), client.scheduleControls.toList())
        rule.onNodeWithText("Open last session").performClick()
        awaitTag(ShellTags.WorkspaceHeader)
        assertEquals("s1", vm.selectedSessionId.value)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        // The rail's own key opens it too; Back returns to the session.
        rule.onNodeWithTag(com.tether.app.ui.sidebar.SidebarTags.Scheduled).performClick()
        awaitTag(com.tether.app.ui.scheduled.ScheduledTags.Root)
        back()
        awaitTag(ShellTags.WorkspaceHeader)
    }

    @Test fun theViewAndWhatIsBehindItSurviveRotation() {
        val restoration = StateRestorationTester(rule)
        client.show(session("s1"), freshTree())
        launch(restoration)
        onOverview()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Sessions)).performClick()
        awaitTag(ShellTags.Sidebar)
        restoration.emulateSavedInstanceStateRestore()
        awaitTag(ShellTags.Sidebar)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Sessions)))
        back()
        onOverview()
        assertTrue(backLeavesTheApp)
    }

    @Test fun nothingIsMarkedSeenWhileTheOverviewShows() {
        // s1 is mid-turn: nothing settled, nothing seen.
        client.show(session("s1", status = "active"), freshTree())
        vm.selectSession("s1")
        launch()
        awaitTag(ShellTags.WorkspaceHeader)
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).performClick()
        onOverview()
        // s1 settles while only the Overview is on screen: displaying it is not seeing it.
        rule.runOnIdle { client.sessions.value = client.sessions.value.map { it.copy(status = "idle", updatedAt = 5) } }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), client.seenCalls.toList())
        // Back in Sessions the settled session is on screen: now the session view's own rule applies.
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Sessions)).performClick()
        awaitTag(ShellTags.WorkspaceHeader)
        rule.waitUntil(5_000) { client.seenCalls.isNotEmpty() }
        assertEquals(listOf("h-s1"), client.seenCalls.toList())
    }

    @Test fun settingsOpensFromTheBarOnEveryView() {
        launch()
        onOverview()
        val sheetTitle = androidx.compose.ui.test.hasText("Settings")
        assertEquals(0, rule.onAllNodes(sheetTitle).fetchSemanticsNodes().size)
        rule.onNodeWithTag(ShellTags.SettingsKey).performClick()
        rule.waitForIdle()
        assertEquals("the interim settings sheet (the rail footer's) is open", 1, rule.onAllNodes(sheetTitle).fetchSemanticsNodes().size)
    }
}

/** T15.4 at the phone layout: the navigation lives in the menu, and the drawer key only where the rail exists. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
@OptIn(ExperimentalTestApi::class)
class MainShellPhoneNavigationTest : NavigationBase(1082, 2402) {

    /** T9.3: from the phone's menu Scheduled shows with the drawer key (the rail exists there). */
    @Test fun theMenuOpensScheduledWithTheDrawerKey() {
        launch()
        onOverview()
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        rule.onNodeWithTag(ShellTags.menuNav(TopBarDestination.Scheduled)).performClick()
        awaitTag(com.tether.app.ui.scheduled.ScheduledTags.Root)
        assertTrue(exists(ShellTags.MenuKey))
        back()
        onOverview()
    }

    @Test fun theMenuNavigatesAndTheDrawerKeyBelongsToSessions() {
        client.show(session("s1"), freshTree())
        launch()
        onOverview()
        assertFalse("no rail, so no drawer key on the Overview", exists(ShellTags.MenuKey))
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        assertTrue(selected(ShellTags.menuNav(TopBarDestination.Overview)))
        rule.onNodeWithTag(ShellTags.menuNav(TopBarDestination.Usage)).assertIsEnabled() // T9.2: live, as on the web
        rule.onNodeWithTag(ShellTags.menuNav(TopBarDestination.Sessions)).performClick()
        awaitTag(ShellTags.MenuKey)
        assertFalse("an item closes the menu", exists(ShellTags.ToolsMenu))
        // Back closes an open menu before it steps back a view.
        rule.onNodeWithTag(ShellTags.ToolsMenuKey).performClick()
        back()
        assertFalse(exists(ShellTags.ToolsMenu))
        assertTrue(exists(ShellTags.MenuKey))
        back()
        onOverview()
        assertTrue(backLeavesTheApp)
    }
}
