package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** v165: the "Messages sent during a turn" row reads the server's mode (absent = automatic), writes its own key, and is absent below 165. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class QueueSteerModeRowBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var settings = ServerFixtures.settingsJson()

    private fun show(writer: ServerSettingsWriter, protocol: Int?) {
        binding = ServerFixtures.binding(view = ServerFixtures.view(settings), writer = writer, serverProtocol = protocol)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.runOnUiThread { state.route = RuntimeDeskRoute(RuntimeDeskDestinations.SessionsRouting, level = RuntimeDeskLevel.Detail) }
        compose.waitForIdle()
    }

    private fun row() = compose.onNodeWithTag(ServerSettingsTags.row(ServerSetting.QueueSteerMode), useUnmergedTree = true)

    @Test fun theRowWritesHoldWhenAnotherOptionIsPicked() {
        val writer = RecordingWriter()
        show(writer, protocol = 165)
        row().performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Messages sent during a turn").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hold until I press Steer").performClick()
        compose.waitUntil(5_000) { writer.patches.isNotEmpty() }
        assertEquals(listOf(json("""{"type":"set-server-settings","settings":{"queueSteerMode":"hold"}}""")), writer.frames())
    }

    @Test fun aServerBelow165DrawsNoRow() {
        show(RecordingWriter(), protocol = 164)
        compose.onAllNodesWithTag(ServerSettingsTags.row(ServerSetting.QueueSteerMode), useUnmergedTree = true).assertCountEquals(0)
    }
}
