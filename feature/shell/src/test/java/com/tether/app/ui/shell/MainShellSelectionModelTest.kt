package com.tether.app.ui.shell

import com.tether.app.testsupport.runPrefsWrite

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.TetherClient
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.LastOpenedSession
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.41: the web's selection model through the real shell (dashboard.tsx 90fbb9f). The chat on
 * screen is remembered (:829-835); a Sessions boot restores the remembered chat (:736-748) or reopens
 * it from history (:856-886), else picks the current workspace's first chat once (:752-763); a chat
 * that leaves the list leaves the empty workspace and its selection behind (:709-717), and is back on
 * screen (attached again by its mount) when it returns; an ended chat is listed only with "Show ended
 * sessions" on (:609-611); the top bar's Sessions takes the remembered chat (:1352-1364).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellSelectionModelTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(1200, 1000)
    }

    private val prefs = UiPrefs(ApplicationProvider.getApplicationContext())

    /** The preview client with discovered history and a record of `resume`. */
    private class Client(val base: ShellConsentClient = ShellConsentClient()) : TetherClient by base {
        val discovered = MutableStateFlow<Map<String, List<HistorySession>>>(emptyMap())
        override val historiesByCwd: StateFlow<Map<String, List<HistorySession>>> get() = discovered
        val resumed = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun resumeHistory(historyId: String, cwd: String) {
            resumed += historyId
        }
        // Delegation would hand `resume` (an interface default) to the base, past this record.
        override fun resume(history: HistorySession): Boolean {
            resumeHistory(history.historyId, history.cwd)
            return true
        }
    }

    private fun chat(id: String, cwd: String = "/w", status: String = "ready") =
        AgentSession(id = id, provider = "claude", name = id, cwd = cwd, status = status, startedAt = 1, updatedAt = 1, historyId = "h-$id")

    /** The singleton preferences start from this test's own state (the DataStore outlives a test). */
    private fun storedState(view: String, remembered: LastOpenedSession?, showEnded: Boolean = true, origin: String? = null) = runPrefsWrite {
        prefs.updatePreferences {
            it.copy(
                lastOpenedSession = null,
                lastOpenedByOrigin = remembered?.let { r -> mapOf("" to r) } ?: emptyMap(),
                defaultWorkspace = "",
                showEndedSessions = showEnded,
                pinnedProjects = emptyList(),
                preferencesByOrigin = emptyMap(),
            )
        }
        prefs.setLastView(origin, view)
    }

    private fun list(client: Client, vararg sessions: AgentSession) {
        for (s in sessions.reversed()) client.base.show(s, freshTree())
        client.base.sessions.value = sessions.toList()
    }

    private fun compose(vm: TetherViewModel) {
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
    }

    private fun go(destination: TopBarDestination) {
        rule.onNodeWithTag(ShellTags.nav(destination)).performClick()
        rule.waitForIdle()
    }

    private fun remembered(origin: String? = null): LastOpenedSession? = runBlocking { prefs.preferences.first().lastOpenedFor(origin) }

    private fun reopeningShown() = rule.onAllNodesWithText("Reopening your session.").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSessionsBootWithNothingRememberedPicksTheCurrentWorkspacesFirstChatAndRemembersIt() {
        storedState("sessions", remembered = null)
        val client = Client()
        list(client, chat("elsewhere", cwd = "/x"), chat("mine"), chat("mine-2"))
        val vm = TetherViewModel(client)
        compose(vm)
        rule.waitUntil(5_000) { vm.selectedSessionId.value != null }
        rule.waitForIdle()
        assertEquals("mine", vm.selectedSessionId.value)
        assertEquals("the chat view's mount attaches it", listOf("mine"), client.base.mountCalls.toList())
        rule.waitUntil(5_000) { remembered() != null }
        assertEquals(LastOpenedSession("/w", "mine", "h-mine"), remembered())
    }

    @Test
    fun nothingIsPickedFromTheSavedListBeforeTheServersFirstSnapshot() {
        storedState("sessions", remembered = null)
        val client = Client()
        client.base.link.value = com.tether.app.client.ConnectionState.Connecting
        list(client, chat("cached-first"), chat("b")) // the device's saved copy of the list
        val vm = TetherViewModel(client)
        compose(vm)
        assertNull(vm.selectedSessionId.value)
        // The server's `ready` lists its sessions, then the link is up.
        client.base.sessions.value = listOf(chat("b"))
        client.base.link.value = com.tether.app.client.ConnectionState.Connected
        rule.waitUntil(5_000) { vm.selectedSessionId.value != null }
        rule.waitForIdle()
        assertEquals("b", vm.selectedSessionId.value)
        assertEquals(listOf("b"), client.base.mountCalls.toList())
    }

    @Test
    fun theServersSnapshotListingTheSameChatsAsTheSavedCopyStillPicksOnceItLands() {
        storedState("sessions", remembered = null)
        val client = Client()
        client.base.link.value = com.tether.app.client.ConnectionState.Connecting
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        compose(vm)
        assertNull(vm.selectedSessionId.value)
        rule.runOnIdle { client.base.link.value = com.tether.app.client.ConnectionState.Connected } // same list
        rule.waitUntil(5_000) { vm.selectedSessionId.value != null }
        assertEquals("a", vm.selectedSessionId.value)
    }

    @Test
    fun eachServerRestoresItsOwnRememberedChatAndRemembersUnderItsOrigin() {
        // Remembered under the canonical origin (SettingsStore serverOrigin), configured as typed.
        val serverA = "https://a.example:443"
        val serverB = "https://b.example:443"
        storedState("sessions", remembered = null, origin = serverB)
        runPrefsWrite {
            prefs.updatePreferences {
                it.copy(lastOpenedByOrigin = mapOf(serverA to LastOpenedSession("/w", "a", "h-a"), serverB to LastOpenedSession("/w", "b", "h-b")))
            }
        }
        val client = Client()
        client.base.server.value = "https://b.example"
        list(client, chat("a"), chat("b"), chat("c"))
        val vm = TetherViewModel(client)
        compose(vm)
        assertEquals("server B's own chat", "b", vm.selectedSessionId.value)
        vm.selectSession("c")
        rule.waitUntil(5_000) { remembered(serverB)?.sessionId == "c" }
        assertEquals("server A's memory untouched", "a", remembered(serverA)?.sessionId)
    }

    @Test
    fun theSingleRememberedChatOfAnOlderVersionBecomesTheCurrentServersOnce() {
        val serverA = "https://a.example:443"
        storedState("sessions", remembered = null, origin = serverA)
        runPrefsWrite { prefs.updatePreferences { it.copy(lastOpenedSession = LastOpenedSession("/w", "b", "h-b")) } }
        val client = Client()
        client.base.server.value = "https://a.example"
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        compose(vm)
        assertEquals("b", vm.selectedSessionId.value)
        rule.waitUntil(5_000) { runBlocking { prefs.preferences.first().lastOpenedSession } == null }
        assertEquals(LastOpenedSession("/w", "b", "h-b"), remembered(serverA))
        assertNull("not another server's", remembered("https://other.example"))
    }

    /** ta-coik.47: the folded blocks and seen stamps an older version kept device-wide become the current server's, once. */
    @Test
    fun theDeviceWideFoldedBlocksAndSeenStampsBecomeTheCurrentServersOnce() {
        val serverA = "https://a.example:443"
        storedState("overview", remembered = null, origin = serverA)
        runPrefsWrite {
            prefs.updatePreferences {
                it.copy(
                    collapsedWorkspaces = listOf("/w/folded"),
                    lastSeenSessions = mapOf("h-old" to 3L),
                    collapsedByOrigin = emptyMap(),
                    preferencesByOrigin = emptyMap(),
                    lastSeenByOrigin = emptyMap(),
                )
            }
        }
        val client = Client()
        client.base.server.value = "https://a.example"
        val vm = TetherViewModel(client)
        compose(vm)
        rule.waitUntil(5_000) { !runBlocking { prefs.preferences.first() }.hasDeviceWideServerRecords }
        val stored = runBlocking { prefs.preferences.first() }
        assertEquals(listOf("/w/folded"), stored.forServer(serverA).collapsedWorkspaces)
        assertEquals(mapOf("h-old" to 3L), stored.forServer(serverA).lastSeenSessions)
        assertTrue("not another server's", stored.forServer("https://other.example:443").collapsedWorkspaces.isEmpty())
        assertTrue(stored.forServer("https://other.example:443").lastSeenSessions.isEmpty())
    }

    @Test
    fun anotherServerSettlesItsOwnCurrentWorkspace() {
        storedState("sessions", remembered = null, origin = "https://a.example:443")
        runPrefsWrite { prefs.updatePreferences { it.copy(defaultWorkspace = "/w/default-a") } }
        val client = Client()
        client.base.server.value = "https://a.example"
        val vm = TetherViewModel(client)
        compose(vm)
        rule.waitUntil(5_000) { vm.currentWorkspace.value != null }
        assertEquals("/w/default-a", vm.currentWorkspace.value)
        runPrefsWrite { prefs.updatePreferences { it.copy(defaultWorkspace = "/w/default-b") } }
        rule.runOnIdle { client.base.server.value = "https://b.example" }
        rule.waitUntil(5_000) { vm.currentWorkspace.value == "/w/default-b" }
    }

    @Test
    fun anOverviewBootPicksNothing() {
        storedState("overview", remembered = null)
        val client = Client()
        list(client, chat("mine"))
        val vm = TetherViewModel(client)
        compose(vm)
        assertNull(vm.selectedSessionId.value)
        assertTrue(client.base.mountCalls.isEmpty())
    }

    @Test
    fun aSessionsBootRestoresTheRememberedChatNotTheFirstListed() {
        storedState("sessions", remembered = LastOpenedSession("/w", "b", "h-b"))
        val client = Client()
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        compose(vm)
        rule.waitForIdle()
        assertEquals("b", vm.selectedSessionId.value)
        assertEquals(listOf("b"), client.base.mountCalls.toList())
    }

    @Test
    fun anExitedRememberedChatIsReopenedFromItsHistoryAndNothingIsPickedMeanwhile() {
        storedState("sessions", remembered = LastOpenedSession("/w", "gone", "h-gone"))
        val client = Client()
        list(client, chat("a"))
        val vm = TetherViewModel(client)
        compose(vm)
        assertEquals("still waiting for discovery: no pick", "gone", vm.selectedSessionId.value)
        assertTrue(client.resumed.isEmpty())
        client.discovered.value = mapOf("/w" to listOf(HistorySession("h-gone", "claude", "gone", "/w", 1)))
        rule.waitUntil(5_000) { client.resumed.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf("h-gone"), client.resumed.toList())
        assertEquals("h-gone", vm.openingHistoryId.value)
        assertNull(vm.selectedSessionId.value)
        assertTrue("nothing picked while the row opens", client.base.mountCalls.isEmpty())
    }

    /** ta-coik.47: the reopened chat is stamped seen on the server it was reopened on (dashboard.tsx `reopen`). */
    @Test
    fun aReopenedRememberedChatIsSeenOnItsServer() {
        val serverA = "https://a.example:443"
        storedState("sessions", remembered = null, origin = serverA)
        runPrefsWrite {
            prefs.updatePreferences {
                it.copy(
                    lastOpenedByOrigin = mapOf(serverA to LastOpenedSession("/w", "gone", "h-gone")),
                    lastSeenSessions = emptyMap(),
                    lastSeenByOrigin = emptyMap(),
                )
            }
        }
        val client = Client()
        client.base.server.value = "https://a.example"
        list(client, chat("a"))
        val vm = TetherViewModel(client)
        compose(vm)
        client.discovered.value = mapOf("/w" to listOf(HistorySession("h-gone", "claude", "gone", "/w", 1)))
        rule.waitUntil(5_000) { client.resumed.isNotEmpty() }
        rule.waitUntil(5_000) { "h-gone" in runBlocking { prefs.preferences.first() }.forServer(serverA).lastSeenSessions }
        val stored = runBlocking { prefs.preferences.first() }
        assertTrue("not with no server", stored.lastSeenByOrigin[""].orEmpty().isEmpty())
        assertTrue("not another server's", stored.forServer("https://other.example:443").lastSeenSessions.isEmpty())
    }

    @Test
    fun aChatThatLeavesTheListLeavesTheWelcomeAndComesBackAttachedWhenItReturns() {
        storedState("sessions", remembered = null)
        val client = Client()
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        vm.selectSession("a")
        compose(vm)
        assertEquals(listOf("a"), client.base.mountCalls.toList())

        client.base.sessions.value = listOf(chat("b")) // archived, handed off, filtered out
        rule.waitForIdle()
        assertEquals("kept, and nothing picked over it", "a", vm.selectedSessionId.value)
        assertTrue("the welcome stage, not 'reopening'", !reopeningShown())
        client.base.sessions.value = emptyList()
        rule.waitForIdle()
        assertTrue("a picked chat gone with the list is not 'reopening' either", !reopeningShown())

        client.base.sessions.value = listOf(chat("a"), chat("b"))
        rule.waitForIdle()
        assertEquals("a", vm.selectedSessionId.value)
        assertEquals("its chat view mounts again", listOf("a", "a"), client.base.mountCalls.toList())
    }

    @Test
    fun aPendingChatNotListedYetReadsAsReopening() {
        storedState("sessions", remembered = LastOpenedSession("/w", "b", "h-b"))
        val client = Client()
        val vm = TetherViewModel(client)
        compose(vm)
        assertEquals("b", vm.selectedSessionId.value)
        assertTrue(reopeningShown())
    }

    @Test
    fun aChatThatEndsWithShowEndedOffLeavesTheScreenAndIsBackWhenTheSettingIsOn() {
        storedState("sessions", remembered = null, showEnded = false)
        val client = Client()
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        vm.selectSession("a")
        compose(vm)
        assertEquals(listOf("a"), client.base.mountCalls.toList())
        client.base.sessions.value = listOf(chat("a", status = "exited"), chat("b")) // End session
        rule.waitForIdle()
        assertEquals("kept, nothing picked over it", "a", vm.selectedSessionId.value)
        runPrefsWrite { prefs.updatePreferencesFor(com.tether.app.client.serverOrigin(client.serverUrl.value)) { it.copy(showEndedSessions = true) } }
        rule.waitUntil(5_000) { client.base.mountCalls.size == 2 }
        assertEquals("listed again: mounted again", listOf("a", "a"), client.base.mountCalls.toList())
    }

    /** ta-coik.52: "Show ended sessions" is the signed-in server's own (the web's preferences are per origin). */
    @Test
    fun showEndedSessionsIsTheServersOwn() {
        val a = "https://a.example:443"
        storedState("sessions", remembered = null, showEnded = true, origin = a)
        runPrefsWrite { prefs.updatePreferencesFor(a) { it.copy(showEndedSessions = false) } }
        val client = Client()
        client.base.server.value = "https://a.example"
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        vm.selectSession("a")
        compose(vm)
        assertEquals(listOf("a"), client.base.mountCalls.toList())
        client.base.sessions.value = listOf(chat("a", status = "exited"), chat("b")) // End session
        rule.waitForIdle()
        // Another server's setting changes nothing here.
        runPrefsWrite { prefs.updatePreferencesFor("https://b.example:443") { it.copy(showEndedSessions = true) } }
        rule.waitForIdle()
        assertEquals("off on this server: the ended chat stays off screen", listOf("a"), client.base.mountCalls.toList())
        runPrefsWrite { prefs.updatePreferencesFor(a) { it.copy(showEndedSessions = true) } }
        rule.waitUntil(5_000) { client.base.mountCalls.size == 2 }
    }

    @Test
    fun anEndedChatNeverFlashesOnScreenBeforeTheShowEndedSettingIsRead() {
        storedState("sessions", remembered = null, showEnded = false)
        val client = Client()
        list(client, chat("ended", status = "exited"), chat("b"))
        val vm = TetherViewModel(client)
        vm.selectSession("ended") // its open's attach waits for the chat view it causes
        compose(vm)
        assertEquals(listOf("ended"), client.base.mountCalls.toList())
        runPrefsWrite { prefs.updatePreferencesFor(com.tether.app.client.serverOrigin(client.serverUrl.value)) { it.copy(showEndedSessions = true) } }
        rule.waitForIdle()
        // Listed now: the chat view mounts for the first time, and that mount is the open's.
        assertEquals("never mounted (and unmounted) before the setting was read", listOf("ended"), client.base.mountCalls.toList())
    }

    @Test
    fun theTopBarsSessionsTakesTheRememberedChatWhenNothingIsSelected() {
        storedState("overview", remembered = LastOpenedSession("/w", "b", "h-b"))
        val client = Client()
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        compose(vm)
        assertNull(vm.selectedSessionId.value)
        go(TopBarDestination.Sessions)
        assertEquals("b", vm.selectedSessionId.value)
        assertEquals(listOf("b"), client.base.mountCalls.toList())
    }

    @Test
    fun theTopBarsSessionsWithNothingRememberedPicksOnce() {
        storedState("overview", remembered = null)
        val client = Client()
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        compose(vm)
        go(TopBarDestination.Sessions)
        assertEquals("a", vm.selectedSessionId.value)
        assertEquals(listOf("a"), client.base.mountCalls.toList())
    }
}
