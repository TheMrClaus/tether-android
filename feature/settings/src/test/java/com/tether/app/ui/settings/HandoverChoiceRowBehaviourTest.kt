package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.client.ServerSetting
import com.tether.app.ui.settings.ServerFixtures.json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Advanced tab's "Agent model choice on handover" row reads the server's state and sends its own key. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverChoiceRowBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var settings = ServerFixtures.settingsJson()

    private fun show(writer: ServerSettingsWriter) {
        binding = ServerFixtures.binding(view = ServerFixtures.view(settings), writer = writer)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.runOnUiThread { state.route = RuntimeDeskRoute(RuntimeDeskDestinations.SessionsRouting, level = RuntimeDeskLevel.Detail) }
        compose.waitForIdle()
    }

    private fun row() = compose.onNodeWithTag(ServerSettingsTags.row(ServerSetting.AgentHandoverChoice), useUnmergedTree = true)

    @Test fun theRowShowsTheServersStateAndFlipsIt() {
        val writer = RecordingWriter { patch ->
            settings = ServerFixtures.applied(settings, patch)
            binding = binding.copy(settings = ServerFixtures.view(settings), replies = binding.replies + 1)
        }
        show(writer)
        row().performScrollTo().assertIsOn()
        row().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { writer.patches.isNotEmpty() }
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"agentHandoverChoice":false}}""")), writer.frames())
        compose.waitForIdle()
        row().assertIsOff()
    }

    @Test fun anOffSettingShowsOff() {
        settings = ServerFixtures.settingsJson(overrides = mapOf("agentHandoverChoice" to false))
        show(RecordingWriter())
        row().performScrollTo().assertIsOff()
    }
}
