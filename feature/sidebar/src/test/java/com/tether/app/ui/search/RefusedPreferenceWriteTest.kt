package com.tether.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.RecordingClient
import com.tether.app.ui.sidebar.SidebarFixtures
import com.tether.app.ui.sidebar.choiceFor
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A preferences store whose disk refuses every write (the edit itself still runs). */
private class RefusingPrefsStore : DataStore<Preferences> {
    private val disk = MutableStateFlow(emptyPreferences())
    @Volatile var attempts = 0
    override val data: Flow<Preferences> = disk
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        attempts++
        transform(disk.value)
        throw IOException("No space left on device")
    }
}

/**
 * ta-8yn9: the drawer's and global search's preference writes (the local seen stamp) on a disk
 * that refuses them: the tap does what it always does, the stamp still shows (in memory, like the
 * web's best-effort localStorage save), and nothing crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RefusedPreferenceWriteTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures
    private val disk = RefusingPrefsStore()
    private val prefs = UiPrefs.on(disk)

    // Off the main looper: the write the composition launched runs on it.
    private fun seen() = runBlocking(Dispatchers.IO) { prefs.preferences.first() }.forServer(null).lastSeenSessions

    @Test fun aDrawerTapMarksSeenWhenTheDiskRefusesTheStamp() {
        val live = F.live("s1", "Finished job", cwd = F.APP, ago = 5, historyId = "hist-1").copy(updatedAt = System.currentTimeMillis() - 60_000)
        val client = RecordingClient(sessions = listOf(live))
        client.historiesByCwd.value = mapOf(
            F.ROOT to listOf(F.history("hist-1", "Finished job", cwd = F.APP, ago = 5).copy(updatedAt = live.updatedAt)),
        )
        val vm = TetherViewModel(client)
        val selected = mutableListOf<String>()
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                SessionDrawer(vm = vm, prefs = prefs, sessions = listOf(live), selectedId = null, workspaceRoot = F.ROOT, onSelect = { selected += it }, onClose = {})
            }
        }
        rule.waitForIdle()
        rule.onNode(
            SemanticsMatcher("row") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Finished job, changed since you last looked") } == true },
        ).performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1"), selected)
        rule.waitUntil(5_000) { "hist-1" in seen() }
        assertTrue("the stamp was written, and refused", disk.attempts > 0)
    }

    @Test fun aGlobalSearchHitMarksSeenWhenTheDiskRefusesTheStamp() {
        val client = RecordingClient()
        val vm = TetherViewModel(client)
        rule.setContent {
            val list by client.sessions.collectAsStateWithLifecycle()
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                GlobalSearchHost(vm = vm, prefs = prefs, sessions = list, workspaceRoot = F.ROOT, onCloseDrawer = {})
            }
        }
        rule.waitForIdle()
        vm.openGlobalSearch()
        rule.waitForIdle()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val hit = SearchHit(historyId = "h-9", provider = "codex", name = "Old parity run", cwd = F.APP, updatedAt = System.currentTimeMillis() - 60_000, snippet = "…", matchCount = 1)
        client.globalReply(client.globalSearchResults.value.requestId, "parity", listOf(hit))
        rule.waitForIdle()
        rule.onNodeWithTag(GlobalSearchTags.hit("h-9")).performClick()
        rule.waitForIdle()
        assertTrue(client.types().toString(), "resume" in client.types())
        rule.waitUntil(5_000) { "h-9" in seen() }
        assertTrue("the stamp was written, and refused", disk.attempts > 0)
    }
}
