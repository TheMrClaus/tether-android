package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.client.BROWSER_TOOLS_MIN_PROTOCOL
import com.tether.app.client.ServerSetting
import com.tether.app.client.browserToolsSupported
import com.tether.app.ui.settings.ServerFixtures.json
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

/** v168: the Browser section's "Browser tools for agents" row shows the server's value, writes its own key, and is absent below 168 or when the key is absent. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserToolsRowBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var settings = ServerFixtures.settingsJson(overrides = mapOf("browserAgentTools" to true))

    private fun show(writer: ServerSettingsWriter, protocol: Int?) {
        binding = ServerFixtures.binding(view = ServerFixtures.view(settings), writer = writer, serverProtocol = protocol)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { BrowserRuntimeController(FakeBrowser(), ServerFixtures.ORIGIN, scope, seed = BrowserSeed(status = BrowserFixtures.FOUND)) }
            SettingsUnderTest(store.prefs, state, serverSettings = binding, browser = BrowserBinding(controller))
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun row() = compose.onNodeWithTag(ServerSettingsTags.row(ServerSetting.BrowserAgentTools), useUnmergedTree = true)

    private fun rows() = compose.onAllNodesWithTag(ServerSettingsTags.row(ServerSetting.BrowserAgentTools), useUnmergedTree = true)

    @Test fun theRowShowsTheServersStateAndFlipsIt() {
        val writer = RecordingWriter { patch ->
            settings = ServerFixtures.applied(settings, patch)
            binding = binding.copy(settings = ServerFixtures.view(settings), replies = binding.replies + 1)
        }
        show(writer, BROWSER_TOOLS_MIN_PROTOCOL)
        row().performScrollTo().assertIsOn()
        row().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { writer.patches.isNotEmpty() }
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"browserAgentTools":false}}""")), writer.frames())
        compose.waitForIdle()
        row().assertIsOff()
    }

    @Test fun aRefusedWriteKeepsTheOldValue() {
        val writer = RecordingWriter()
        show(writer, BROWSER_TOOLS_MIN_PROTOCOL)
        row().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { writer.patches.isNotEmpty() }
        compose.waitForIdle()
        row().assertIsOn()
    }

    @Test fun anOffSettingShowsOff() {
        settings = ServerFixtures.settingsJson(overrides = mapOf("browserAgentTools" to false))
        show(RecordingWriter(), BROWSER_TOOLS_MIN_PROTOCOL)
        row().performScrollTo().assertIsOff()
    }

    @Test fun aServerBelow168DrawsNoRow() {
        show(RecordingWriter(), protocol = BROWSER_TOOLS_MIN_PROTOCOL - 1)
        rows().assertCountEquals(0)
    }

    @Test fun aServerThatSendsNoKeyDrawsNoRowEvenAt168() {
        settings = ServerFixtures.settingsJson()
        show(RecordingWriter(), BROWSER_TOOLS_MIN_PROTOCOL)
        rows().assertCountEquals(0)
    }

    @Test fun theGateIsTheKeyAndTheProtocol() {
        val with = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("browserAgentTools" to true)))
        val without = ServerFixtures.view(ServerFixtures.settingsJson())
        assertTrue(browserToolsSupported(168, with))
        assertTrue(browserToolsSupported(169, with))
        assertFalse(browserToolsSupported(167, with))
        assertFalse(browserToolsSupported(null, with))
        assertFalse(browserToolsSupported(168, without))
    }

    @Test fun theStringsAreTheSpecsBytes() {
        val r = AdvancedRows.browserAgentTools
        assertEquals("Browser tools for agents", r.label)
        assertEquals("Applies to each session\u2019s next start; running sessions keep their tools.", r.description)
        assertEquals(
            "When on (default), agents can open pages and act in the in-console browser through the browser tools. When off, no agent session on any engine is offered them. This applies to each session\u2019s next start (a new session, or an existing one after its process ends); a session that is already running keeps the tools it has. Your own browser pane keeps working either way.",
            r.tip,
        )
    }
}
