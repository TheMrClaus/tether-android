package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RuntimeAccountCorrectionsTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun syntheticHostDefaultIgnoresConflictingEditableProfileMetadata() {
        val providers = accountProfile("claude-default")
        show(
            provider = "claude",
            accountId = "claude-default",
            accounts = AccountsFixtures.LIST,
            providers = providers,
            usage = listOf(usage("claude", "default")),
        )

        compose.onNodeWithTag(ClaudeAccountsTags.card("claude-default")).assertExists()
        compose.onNodeWithTag(ClaudeAccountsTags.rename("claude-default")).assertDoesNotExist()
        compose.onNodeWithTag(ClaudeAccountsTags.remove("claude-default")).assertDoesNotExist()
        compose.onNodeWithText("Remove account").assertDoesNotExist()
        compose.onNodeWithText("Advanced profile settings").assertDoesNotExist()
        compose.onNodeWithTag(ProfileTags.card("claude-default")).assertDoesNotExist()
    }

    @Test fun persistedImportedHostDefaultRemainsEditableAndRemovable() {
        val accounts = AccountsFixtures.decode(
            """{"accounts":[{"id":"host-login","label":"Host login","hasConfigDir":true,"managed":false,"imported":true,"syncEligible":true}]}""",
        )
        show(
            provider = "claude",
            accountId = "host-login",
            accounts = accounts,
            providers = accountProfile("host-login"),
            usage = listOf(usage("claude", "default")),
        )

        compose.onNodeWithTag(ClaudeAccountsTags.rename("host-login")).assertExists()
        compose.onNodeWithTag(ClaudeAccountsTags.remove("host-login")).assertExists()
        compose.onNodeWithText("Advanced profile settings").assertExists()
        compose.onNodeWithTag(ProfileTags.card("host-login")).assertExists()
        compose.onNodeWithTag(ProfileTags.field("host-login", ProfileTags.HOME), useUnmergedTree = true).assertExists()
    }

    @Test fun onlyClaudeSelectionCanExposeAClaudeProfileWithTheSameId() {
        val providers = accountProfile("shared")
        val usage = listOf(usage("codex", "shared"), usage("deepseek", "shared"))

        show("codex", "shared", emptyList(), providers, usage)
        compose.onNodeWithText("Advanced profile settings").assertDoesNotExist()
        compose.onNodeWithTag(ProfileTags.card("shared")).assertDoesNotExist()

        compose.runOnUiThread {
            currentState.route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.account("deepseek", "shared"),
                RuntimeDeskLevel.Detail,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Advanced profile settings").assertDoesNotExist()
        compose.onNodeWithTag(ProfileTags.card("shared")).assertDoesNotExist()

        compose.runOnUiThread {
            currentState.route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.account("claude", "shared"),
                RuntimeDeskLevel.Detail,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Advanced profile settings").assertExists()
        compose.onNodeWithTag(ProfileTags.card("shared")).assertExists()
    }

    private lateinit var currentState: SettingsDialogState

    private fun show(
        provider: String,
        accountId: String,
        accounts: List<com.tether.app.client.ClaudeAccount>,
        providers: ProvidersBinding,
        usage: List<RuntimeDeskNavItem>,
    ) {
        currentState = SettingsDialogState(
            draft = GeneralDraft.of(com.tether.app.ui.prefs.TetherPreferences.Default),
            route = RuntimeDeskRoute(
                RuntimeDeskDestinations.Accounts,
                RuntimeDeskEntities.account(provider, accountId),
                RuntimeDeskLevel.Detail,
            ),
        )
        val binding = ClaudeAccountsBinding(
            source = com.tether.app.client.ClaudeAccountsSource.Unavailable,
            origin = AccountsFixtures.ORIGIN,
            initial = ClaudeAccountsState(origin = AccountsFixtures.ORIGIN, accounts = accounts),
        )
        compose.setContent {
            SettingsUnderTest(
                prefs = store.prefs,
                state = currentState,
                claudeAccounts = binding,
                providers = providers,
                accountLimitEntities = usage,
            )
        }
    }

    private fun accountProfile(id: String) = ProfileFixtures.binding(
        ProfileFixtures.list(
            ProfileFixtures.profiles(
                """{"id":"$id","extends":"claude","label":"Profile $id","env":{"CLAUDE_CONFIG_DIR":"/fake/$id"},"homeDir":"/fake/home","enabled":true}""",
            ),
        ),
    )

    private fun usage(provider: String, id: String) =
        RuntimeDeskNavItem(RuntimeDeskEntities.usage(provider, id), "$provider $id", "Usage only")
}
