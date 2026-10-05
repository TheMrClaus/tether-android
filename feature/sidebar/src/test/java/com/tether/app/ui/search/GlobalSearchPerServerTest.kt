package com.tether.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.RecordingClient
import com.tether.app.ui.sidebar.SidebarFixtures
import com.tether.app.ui.sidebar.choiceFor
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-coik.47: a global search hit opened on a server stamps that server's seen record only. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GlobalSearchPerServerTest {
    val tmp = TemporaryFolder()
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    private fun stored() = runBlocking(Dispatchers.IO) { prefs.preferences.first() }

    @Test fun aHitOpenedOnAServerIsSeenOnThatServerOnly() {
        val client = RecordingClient()
        client.server.value = "https://a.example"
        val vm = TetherViewModel(client)
        rule.setContent {
            val list by client.sessions.collectAsStateWithLifecycle()
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                GlobalSearchHost(vm = vm, prefs = prefs, sessions = list, workspaceRoot = SidebarFixtures.ROOT, onCloseDrawer = {})
            }
        }
        rule.waitForIdle()
        vm.openGlobalSearch()
        rule.waitForIdle()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val hit = SearchHit(historyId = "h-9", provider = "codex", name = "Old parity run", cwd = SidebarFixtures.APP, updatedAt = System.currentTimeMillis() - 60_000, snippet = "…", matchCount = 1)
        client.globalReply(client.globalSearchResults.value.requestId, "parity", listOf(hit))
        rule.waitForIdle()
        rule.onNodeWithTag(GlobalSearchTags.hit("h-9")).performClick()
        rule.waitUntil(5_000) { "h-9" in stored().forServer("https://a.example:443").lastSeenSessions }
        assertTrue("not another server's", stored().forServer("https://b.example:443").lastSeenSessions.isEmpty())
        assertTrue("not device-wide", stored().lastSeenSessions.isEmpty())
    }
}
