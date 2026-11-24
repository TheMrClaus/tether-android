package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tether.app.client.EngineCard
import com.tether.app.client.ServerSetting
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        assertEquals(RuntimeDeskLevel.Entities, state.route.level)
        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        assertEquals(RuntimeDeskLevel.Destinations, state.route.level)
        compose.onNodeWithText("Accounts").assertExists()
    }

    /** lib/runtime-desk.ts:33 RUNTIME_DESK_ENGINE_IDS (tether 15f74ecd): selecting Antigravity keeps the selection and mounts its own card. */
    @Test fun selectingAntigravityRoutesToItsOwnCard() {
        val state = SettingsDialogState(route = RuntimeDeskRoute.Root)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding()) }
        compose.waitUntil(5_000) { state.draft != null }

        compose.onNodeWithText("Engines").performClick()
        compose.onNode(hasText("Antigravity") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(RuntimeDeskLevel.Detail, state.route.level)
        assertEquals("antigravity", state.route.entity)
        compose.onNodeWithTag(EngineTags.card(EngineCard.Antigravity), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ServerSettingsTags.input(ServerSetting.AntigravityCommand), useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * A server that reports neither Claude nor Antigravity and sends no `antigravityCommand` (an older one): the Antigravity row
     * reads like Claude's unreported row (same status text), its switch is enabled exactly as Claude's (no home gate, unlike
     * Dsh's), selecting it opens its card, and nothing crashes.
     */
    @Test fun onAnOlderServerAntigravityBehavesLikeAnUnreportedClaude() {
        val detected = JsonObject(ServerFixtures.DETECTED - "claude" - "antigravity")
        val settings = ServerFixtures.settingsJson()
        assertEquals(false, settings.containsKey("antigravityCommand"))
        val state = SettingsDialogState(route = RuntimeDeskRoute.Root)
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(view = ServerFixtures.view(settings = settings, detected = detected))) }
        compose.waitUntil(5_000) { state.draft != null }

        compose.onNodeWithText("Engines").performClick()
        val waiting = hasText("Waiting for runtime status")
        compose.onNode(hasText("Claude Code") and hasClickAction()).assert(waiting)
        compose.onNode(hasText("Antigravity") and hasClickAction()).assert(waiting)

        compose.onNode(hasText("Claude Code") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EngineTags.switch(EngineCard.Claude), useUnmergedTree = true).assertIsEnabled()
        compose.onNodeWithContentDescription("Back", substring = true).performClick()

        compose.onNode(hasText("Antigravity") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals("antigravity", state.route.entity)
        compose.onNodeWithTag(EngineTags.card(EngineCard.Antigravity), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(EngineTags.switch(EngineCard.Antigravity), useUnmergedTree = true).assertIsEnabled()
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
        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(ServerFixtures.json("""{"claudeCommand":"claude-preview"}""") to ServerFixtures.ORIGIN),
            writer.patches,
        )
        assertEquals("claude", binding.settings!!.text(ServerSetting.ClaudeCommand))
        compose.onNode(hasText("Codex") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back", substring = true).performClick()
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
        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        compose.onNodeWithText("Preferences").performClick()
        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        compose.onNodeWithText("Engines").performClick()

        assertEquals("codex", state.route.entity)
        compose.onNode(hasText("Codex") and hasClickAction()).assertExists()
    }

    @Test fun routeBodyUpdatesAfterTheDetailMovesFromCompactToExpanded() {
        var width by mutableIntStateOf(412)
        var height by mutableIntStateOf(915)
        val window = object : WindowInfo {
            override val isWindowFocused: Boolean get() = true
            override val containerSize: IntSize get() = IntSize(width, height)
        }
        val state = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.LimitsBalance,
                RuntimeDeskLevel.Detail,
            ),
        )
        val binding = ServerFixtures.binding()
        compose.setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    SettingsUnderTest(store.prefs, state, serverSettings = binding)
                }
            }
        }
        compose.onNodeWithContentDescription("Back", substring = true).assertExists()

        compose.runOnIdle {
            width = 800
            height = 1000
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back", substring = true).assertDoesNotExist()

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
        compose.onNodeWithTag(EngineTags.Section).assertDoesNotExist()
        compose.onNodeWithTag(EngineTags.Section, useUnmergedTree = true)
            .assertExists()
            .assertIsNotDisplayed()

        compose.runOnUiThread {
            state.route = RuntimeDeskRoute(RuntimeDeskDestinations.Engines, "claude", RuntimeDeskLevel.Detail)
        }
        compose.waitForIdle()
        val retained = compose.onNodeWithTag(command, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("claude-retained", retained)
    }

    @Test fun agentSettingsTasksAreDistinctAndCustomProfilesExcludeAccountProfiles() {
        val state = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(RuntimeDeskDestinations.Engines, RuntimeDeskEntities.CustomProfiles, RuntimeDeskLevel.Detail),
        )
        val providers = ProfileFixtures.binding(
            ProfileFixtures.list(
                ProfileFixtures.profiles(
                    """{"id":"work","extends":"claude","label":"Claude Work","env":{"CLAUDE_CONFIG_DIR":"/fake/work"},"enabled":true}""",
                    """{"id":"alternate","extends":"codex","label":"Alternate Codex","homeDir":"/fake/codex","enabled":true}""",
                ),
            ),
        )
        compose.setContent {
            SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(), providers = providers)
        }

        compose.onNodeWithTag(ProfileTags.Section).assertExists()
        compose.onNodeWithTag(ProfileTags.card("alternate")).assertExists()
        compose.onNodeWithTag(ProfileTags.card("work")).assertDoesNotExist()
        compose.onNodeWithTag(EngineTags.Section).assertDoesNotExist()
        compose.onNodeWithTag(EngineTags.HostConfig).assertDoesNotExist()

        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        compose.onNode(hasText("Host config") and hasClickAction()).performClick()
        compose.onNodeWithTag(EngineTags.HostConfig).assertExists()
        compose.onNodeWithTag(ProfileTags.Section).assertDoesNotExist()

        compose.onNodeWithContentDescription("Back", substring = true).performClick()
        compose.onNode(hasText("Claude CLI") and hasClickAction()).performClick()
        compose.onNodeWithTag(ServerSettingsTags.section("cli")).assertExists()
        compose.onNodeWithTag(EngineTags.HostConfig).assertDoesNotExist()
    }

    @Test fun canonicalAccountDetailCombinesAccessUsageAndAdvancedOverrides() {
        val state = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.account("claude", "claude-work"),
                RuntimeDeskLevel.Detail,
            ),
        )
        val accounts = ClaudeAccountsBinding(
            source = com.tether.app.client.ClaudeAccountsSource.Unavailable,
            origin = AccountsFixtures.ORIGIN,
            initial = ClaudeAccountsState(origin = AccountsFixtures.ORIGIN, accounts = AccountsFixtures.LIST),
        )
        val providers = ProfileFixtures.binding(
            ProfileFixtures.list(
                ProfileFixtures.profiles(
                    """{"id":"claude-work","extends":"claude","label":"Claude Code (work)","env":{"CLAUDE_CONFIG_DIR":"/fake/work","FAKE_FLAG":"1"},"homeDir":"/fake/home","dropEnv":["ANTHROPIC"],"disallowedTools":["WebSearch"],"enabled":true,"order":3,"models":[{"id":"opus","isDefault":true}],"additionalModels":[{"id":"sonnet"}]}""",
                    """{"id":"ordinary","extends":"claude","label":"Ordinary","enabled":true}""",
                ),
            ),
        )
        val usage = listOf(RuntimeDeskNavItem(RuntimeDeskEntities.usage("claude", "claude-work"), "Claude Code (work)", "Usage only"))
        compose.setContent {
            SettingsUnderTest(
                store.prefs,
                state,
                claudeAccounts = accounts,
                providers = providers,
                accountLimitEntities = usage,
                accountsLimits = { target ->
                    Text("Selected usage ${target.activeEntity}")
                },
            )
        }

        compose.onNodeWithText("Access").assertExists()
        compose.onNodeWithTag(ClaudeAccountsTags.card("claude-work")).assertExists()
        compose.onNodeWithText("Usage").assertExists()
        compose.onNodeWithText("Selected usage usage:claude:claude-work").assertExists()
        compose.onNodeWithText("Advanced profile settings").assertExists()
        compose.onNodeWithTag(ProfileTags.card("claude-work")).assertExists()
        compose.onNodeWithTag(ProfileTags.card("ordinary")).assertDoesNotExist()
        compose.onNodeWithTag(ProfileTags.remove("claude-work")).assertDoesNotExist()
        compose.onNodeWithTag(ProfileTags.field("claude-work", ProfileTags.ID)).assertDoesNotExist()
    }

    @Test fun connectAccountsLinksOrdinaryProfilesBackToTheirEngineTask() {
        val state = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.Credentials,
                RuntimeDeskLevel.Detail,
            ),
        )
        compose.setContent { SettingsUnderTest(store.prefs, state) }

        compose.onNodeWithText("Open Custom profiles").performClick()

        assertEquals(RuntimeDeskDestinations.Engines, state.route.destination)
        assertEquals(RuntimeDeskEntities.CustomProfiles, state.route.entity)
        assertEquals(RuntimeDeskLevel.Detail, state.route.level)
    }
}
