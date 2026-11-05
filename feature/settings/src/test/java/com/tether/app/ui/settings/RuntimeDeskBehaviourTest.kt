package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.tether.app.client.ServerSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RuntimeDeskBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun compactHierarchyUsesOneBackStepPerLevel() {
        val state = SettingsDialogState(route = RuntimeDeskRoute.Root)
        compose.setContent { SettingsUnderTest(store.prefs, state) }
        compose.waitUntil(5_000) { state.draft != null }

        compose.onNodeWithText("Engines").performClick()
        assertEquals(RuntimeDeskLevel.Entities, state.route.level)
        assertEquals(RuntimeDeskDestinations.Engines, state.route.destination)
        compose.onNode(hasText("Codex") and hasClickAction()).performClick()
        assertEquals(RuntimeDeskLevel.Detail, state.route.level)
        assertEquals("codex", state.route.entity)

        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(RuntimeDeskLevel.Entities, state.route.level)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(RuntimeDeskLevel.Destinations, state.route.level)
        compose.onNodeWithText("Accounts").assertExists()
    }

    @Test fun nativeBackUsesTheSameHierarchyAndDismissesOnlyAtTheRoot() {
        val state = SettingsDialogState(route = RuntimeDeskRoute.Root)
        var closes = 0
        compose.setContent { SettingsUnderTest(store.prefs, state, onClose = { closes++ }) }
        compose.waitUntil(5_000) { state.draft != null }

        compose.onNodeWithText("Engines").performClick()
        compose.onNode(hasText("Codex") and hasClickAction()).performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(RuntimeDeskLevel.Entities, state.route.level)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(RuntimeDeskLevel.Destinations, state.route.level)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test fun changingEngineSelectionRetainsTheUncommittedEditor() {
        val state = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(RuntimeDeskDestinations.Engines, "claude", RuntimeDeskLevel.Detail),
        )
        val writer = RecordingWriter()
        val binding = ServerFixtures.binding(writer = writer)
        compose.setContent {
            SettingsUnderTest(store.prefs, state, serverSettings = binding)
        }
        val tag = ServerSettingsTags.input(ServerSetting.ClaudeCommand)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement("claude-preview")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(ServerFixtures.json("""{"claudeCommand":"claude-preview"}""") to ServerFixtures.ORIGIN),
            writer.patches,
        )
        assertEquals("claude", binding.settings!!.text(ServerSetting.ClaudeCommand))
        compose.onNode(hasText("Codex") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Claude Code") and hasClickAction()).performClick()
        compose.waitForIdle()

        val value = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("claude-preview", value)
        assertEquals("the blur commit is not repeated by mounted navigation", 1, writer.patches.size)
    }

    @Test fun returningToADestinationRestoresItsRealEntitySelection() {
        val state = SettingsDialogState(route = RuntimeDeskRoute.Root)
        compose.setContent { SettingsUnderTest(store.prefs, state) }
        compose.waitUntil(5_000) { state.draft != null }

        compose.onNodeWithText("Engines").performClick()
        compose.onNode(hasText("Codex") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Preferences").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Engines").performClick()

        assertEquals("codex", state.route.entity)
        compose.onNode(hasText("Codex") and hasClickAction()).assertExists()
    }

    @Test fun routeBodyUpdatesAfterTheDetailMovesFromCompactToExpanded() {
        val state = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.LimitsBalance,
                RuntimeDeskLevel.Detail,
            ),
        )
        val binding = ServerFixtures.binding()
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = binding) }
        compose.onNodeWithContentDescription("Back").assertExists()

        RuntimeEnvironment.setQualifiers("w800dp-h1000dp-mdpi")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()

        compose.runOnUiThread {
            state.route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.Credentials,
                RuntimeDeskLevel.Detail,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            state.route = RuntimeDeskRoute(RuntimeDeskDestinations.Engines, "claude", RuntimeDeskLevel.Detail)
        }
        compose.waitForIdle()
        val command = ServerSettingsTags.input(ServerSetting.ClaudeCommand)
        compose.onNodeWithTag(command, useUnmergedTree = true).performTextReplacement("claude-retained")

        compose.runOnUiThread {
            state.route = RuntimeDeskRoute(RuntimeDeskDestinations.Preferences, level = RuntimeDeskLevel.Detail)
        }
        compose.waitForIdle()
        compose.onNode(
            hasText("Preferences") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        ).assertExists()
        compose.onNode(hasText("Preferences") and isHeading()).assertExists()
        compose.onNodeWithText("Startup").assertExists()
        compose.onNodeWithTag(EngineTags.Section, useUnmergedTree = true).assertDoesNotExist()

        compose.runOnUiThread {
            state.route = RuntimeDeskRoute(RuntimeDeskDestinations.Engines, "claude", RuntimeDeskLevel.Detail)
        }
        compose.waitForIdle()
        val retained = compose.onNodeWithTag(command, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("claude-retained", retained)
    }
}
