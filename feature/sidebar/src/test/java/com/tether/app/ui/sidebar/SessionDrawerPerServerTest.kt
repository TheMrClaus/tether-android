package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.47: the drawer reads and writes the seen stamps of the server it is drawn for (the web's
 * localStorage is per origin). ta-coik.51: the chat it marks seen is looked up among the LISTED
 * sessions, as the web's `activeSession` is (dashboard.tsx 90fbb9f :709-717, :725, :966-973).
 * (One test class per process-wide DataStore file.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionDrawerPerServerTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures
    private val prefs = UiPrefs(ApplicationProvider.getApplicationContext())

    private val serverA = "https://a.example:443"
    private val serverB = "https://b.example:443"

    @Before
    fun freshPreferences() = runBlocking {
        prefs.updatePreferences {
            it.copy(
                showEndedSessions = true,
                lastSeenSessions = emptyMap(),
                collapsedWorkspaces = emptyList(),
                lastSeenByOrigin = emptyMap(),
                collapsedByOrigin = emptyMap(),
                preferencesByOrigin = emptyMap(),
            )
        }
    }

    private fun stored(): TetherPreferences = runBlocking(Dispatchers.IO) { prefs.preferences.first() }

    private fun markSeenFrames(client: RecordingClient) = client.frames.filter { (it["type"] as JsonPrimitive).content == "mark-seen" }

    private fun drawer(client: RecordingClient, sessions: List<AgentSession>, selectedId: String?) {
        val vm = TetherViewModel(client)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                SessionDrawer(
                    vm = vm,
                    prefs = prefs,
                    sessions = sessions,
                    selectedId = selectedId,
                    workspaceRoot = F.ROOT,
                    onSelect = vm::selectSession,
                    onClose = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun ended() = F.live("gone", "Ended job", cwd = F.APP, status = "exited", ago = 5, historyId = "hist-gone")

    /** ta-coik.51: a link to an ended chat, nothing picked, "Show ended sessions" off: not on screen, not seen. */
    @Test
    fun anEndedTargetThatIsNotListedIsNotMarkedSeen() {
        runBlocking { prefs.setShowEnded(null, false) }
        val target = ended()
        val client = RecordingClient(sessions = listOf(target))
        drawer(client, listOf(target), selectedId = target.id)
        // The stored setting is read (the drawer's discover went out), and still nothing is stamped.
        rule.waitUntil(5_000) { "discover" in client.types() }
        rule.waitForIdle()
        assertTrue(markSeenFrames(client).toString(), markSeenFrames(client).isEmpty())
        assertFalse("hist-gone" in stored().forServer(null).lastSeenSessions)
    }

    /** The same target with "Show ended sessions" on is listed, so on screen, and its settled report is seen. */
    @Test
    fun theSameTargetListedIsMarkedSeen() {
        val target = ended()
        val client = RecordingClient(sessions = listOf(target))
        drawer(client, listOf(target), selectedId = target.id)
        rule.waitUntil(5_000) { markSeenFrames(client).isNotEmpty() }
        assertEquals("hist-gone", (markSeenFrames(client).single()["historyId"] as JsonPrimitive).content)
        rule.waitUntil(5_000) { "hist-gone" in stored().forServer(null).lastSeenSessions }
    }

    @Test
    fun eachServerHasItsOwnSeenStamps() {
        runBlocking {
            prefs.updatePreferencesFor(serverA) { it.copy(lastSeenSessions = mapOf("hist-a" to 11L)) }
            prefs.updatePreferencesFor(serverB) { it.copy(lastSeenSessions = mapOf("hist-b" to 22L)) }
        }
        val live = F.live("s1", "Finished job", cwd = F.APP, ago = 5, historyId = "hist-1").copy(updatedAt = System.currentTimeMillis() - 60_000)
        val client = RecordingClient(sessions = listOf(live))
        client.server.value = "https://A.example/"
        drawer(client, listOf(live), selectedId = null)
        // Once the stored stamps are read, a connection's discover carries server A's only.
        rule.waitUntil(5_000) { "discover" in client.types() }
        rule.runOnIdle { client.connection.value = com.tether.app.client.ConnectionState.Disconnected }
        rule.waitForIdle()
        rule.runOnIdle { client.connection.value = com.tether.app.client.ConnectionState.Connected }
        rule.waitForIdle()
        val discover = client.frames.last { (it["type"] as JsonPrimitive).content == "discover" }
        assertEquals(frame("""{"hist-a":11}"""), discover["lastSeen"] as JsonObject)

        // A tap on server A stamps A's record, not B's.
        rule.onNode(SemanticsMatcher("row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Finished job") } == true }).performClick()
        rule.waitUntil(5_000) { "hist-1" in stored().forServer(serverA).lastSeenSessions }
        assertEquals(mapOf("hist-b" to 22L), stored().forServer(serverB).lastSeenSessions)
    }
}
