package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.tether.app.client.ServerSetting
import com.tether.app.client.RESTART_RESUME_MIN_PROTOCOL
import com.tether.app.client.restartResumeSupported
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

/** v167: the "Message agents after a restart" row shows the server's value, writes its own key, and is absent below 167 or when the key is absent. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RestartResumeRowBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var settings = ServerFixtures.settingsJson(overrides = mapOf("restartResumeMessage" to true))

    private fun show(writer: ServerSettingsWriter, protocol: Int?) {
        binding = ServerFixtures.binding(view = ServerFixtures.view(settings), writer = writer, serverProtocol = protocol)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.runOnUiThread { state.route = RuntimeDeskRoute(RuntimeDeskDestinations.SessionsRouting, level = RuntimeDeskLevel.Detail) }
        compose.waitForIdle()
    }

    private fun row() = compose.onNodeWithTag(ServerSettingsTags.row(ServerSetting.RestartResumeMessage), useUnmergedTree = true)

    @Test fun theRowShowsTheServersStateAndFlipsIt() {
        val writer = RecordingWriter { patch ->
            settings = ServerFixtures.applied(settings, patch)
            binding = binding.copy(settings = ServerFixtures.view(settings), replies = binding.replies + 1)
        }
        show(writer, RESTART_RESUME_MIN_PROTOCOL)
        row().performScrollTo().assertIsOn()
        row().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { writer.patches.isNotEmpty() }
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"restartResumeMessage":false}}""")), writer.frames())
        compose.waitForIdle()
        row().assertIsOff()
    }

    @Test fun anOffSettingShowsOff() {
        settings = ServerFixtures.settingsJson(overrides = mapOf("restartResumeMessage" to false))
        show(RecordingWriter(), RESTART_RESUME_MIN_PROTOCOL)
        row().performScrollTo().assertIsOff()
    }

    @Test fun aServerBelow167DrawsNoRow() {
        show(RecordingWriter(), protocol = RESTART_RESUME_MIN_PROTOCOL - 1)
        compose.onAllNodesWithTag(ServerSettingsTags.row(ServerSetting.RestartResumeMessage), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aServerThatSendsNoKeyDrawsNoRowEvenAt167() {
        settings = ServerFixtures.settingsJson()
        show(RecordingWriter(), RESTART_RESUME_MIN_PROTOCOL)
        compose.onAllNodesWithTag(ServerSettingsTags.row(ServerSetting.RestartResumeMessage), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theGateIsTheKeyAndTheProtocol() {
        val with = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("restartResumeMessage" to true)))
        val without = ServerFixtures.view(ServerFixtures.settingsJson())
        assertTrue(restartResumeSupported(167, with))
        assertTrue(restartResumeSupported(168, with))
        assertFalse(restartResumeSupported(166, with))
        assertFalse(restartResumeSupported(null, with))
        assertFalse(restartResumeSupported(167, without))
    }

    @Test fun theStringsAreTheSpecsBytes() {
        val r = AdvancedRows.restartResumeMessage
        assertEquals("Message agents after a restart", r.label)
        assertEquals("Send one short message to each session a restart cut off so the agent carries on; off only shows a notice", r.description)
        assertEquals(
            "When on (default), each session whose turn was cut off by a Tether restart gets one short automated message once Tether is back, so the agent checks what already happened and carries on. The cut-off message is never sent again, and a cut turn gets at most one message. Delegated child sessions, and sessions that are archived, read-only, in plan mode, handed over or that already have a message waiting, only get the notice. When off, no new turn starts: the turn shows a notice and you send the next message yourself. Applies at the next restart.",
            r.tip,
        )
    }
}
