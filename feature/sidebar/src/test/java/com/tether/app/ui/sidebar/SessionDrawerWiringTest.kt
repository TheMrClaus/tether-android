package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.1 end to end: the SessionDrawer the shell hosts, over the view model, a recording client and
 * the real DataStore preferences — the frames a connected drawer sends, and what a tap sends.
 * (One test class per process-wide DataStore file.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionDrawerWiringTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    @Test fun aConnectedDrawerSyncsAndATapSelectsAttachesAndMarksSeen() {
        val live = F.live("s1", "Finished job", cwd = F.APP, ago = 5, historyId = "hist-1").copy(updatedAt = System.currentTimeMillis() - 60_000)
        val client = RecordingClient(sessions = listOf(live))
        client.historiesByCwd.value = mapOf(
            F.ROOT to listOf(F.history("hist-1", "Finished job", cwd = F.APP, ago = 5).copy(updatedAt = live.updatedAt)),
        )
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        val selected = mutableListOf<String>()
        val sessions = listOf(live)

        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                SessionDrawer(
                    vm = vm,
                    prefs = prefs,
                    sessions = sessions,
                    selectedId = null,
                    workspaceRoot = F.ROOT,
                    onSelect = { id ->
                        selected += id
                        vm.selectSession(id)
                    },
                    onClose = {},
                )
            }
        }
        rule.waitForIdle()

        // dashboard.tsx:142-144 + use-tether.ts:771-782 — settings, then every watched block.
        assertTrue(client.types().toString(), client.types().containsAll(listOf("server-settings", "discover")))
        val discover = client.frames.first { (it["type"] as JsonPrimitive).content == "discover" }
        assertEquals(frame("""{"type":"discover","cwd":"${F.ROOT}","lastSeen":{},"watch":["${F.ROOT}"]}"""), discover)

        // The unread row reads so in words, then a tap opens it.
        rule.onNode(
            SemanticsMatcher("row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Finished job, changed since you last looked") } == true },
        ).performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1"), selected)
        val types = client.types()
        assertTrue(types.toString(), "attach" in types)
        val markSeen = client.frames.last { (it["type"] as JsonPrimitive).content == "mark-seen" }
        assertEquals("hist-1", (markSeen["historyId"] as JsonPrimitive).content)
        // The local stamp lands in tether.preferences.v1 lastSeenSessions (the unread badge clears here at once).
        // Poll off the main looper: the DataStore write the drawer launched completes on it, so a
        // suspending wait on this (the main) thread would deadlock.
        fun stored() = runBlocking(Dispatchers.IO) { prefs.preferences.first() }.lastSeenSessions
        rule.waitUntil(5_000) { "hist-1" in stored() }
        val stamp = stored().getValue("hist-1")
        assertEquals((markSeen["seenAt"] as JsonPrimitive).content.toLong(), stamp)
    }
}
