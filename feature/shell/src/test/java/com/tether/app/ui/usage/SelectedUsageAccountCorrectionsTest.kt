package com.tether.app.ui.usage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.AccountsUsage
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SelectedUsageAccountCorrectionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedStaleCodexCanRefreshWithoutAnUnscopedResetAction() {
        val fresh = UsageFixtures.accounts()
        val stale = fresh.copy(
            codex = fresh.codex.map { it.copy(at = fresh.generatedAt - 60 * 60_000) },
        )
        val source = show("usage:codex:codex", stale)

        compose.onNodeWithText("showing last reading", substring = true).assertExists()
        compose.onNodeWithTag(AccountsTags.Refresh).assertExists().performClick()
        compose.waitUntil(5_000) { source.of("accounts").isNotEmpty() }

        assertEquals("all", source.of("accounts").single().a)
        compose.onNodeWithTag(AccountsTags.BankedResets).assertDoesNotExist()
        compose.onNodeWithTag(AccountsTags.UseCredit).assertDoesNotExist()
    }

    @Test fun selectedStaleDeepSeekCanRefresh() {
        val fresh = UsageFixtures.accounts()
        val stale = fresh.copy(
            deepseek = fresh.deepseek?.let { deepseek ->
                deepseek.copy(accounts = deepseek.accounts.map { it.copy(stale = true) })
            },
        )
        val source = show("usage:deepseek:ds-1", stale)

        compose.onNodeWithText("Showing last reading", substring = true).assertExists()
        compose.onNodeWithTag(AccountsTags.Refresh).assertExists().performClick()
        compose.waitUntil(5_000) { source.of("accounts").isNotEmpty() }

        assertEquals("all", source.of("accounts").single().a)
    }

    private fun show(activeEntity: String, payload: AccountsUsage): FakeUsageSource {
        val source = FakeUsageSource()
        compose.setContent {
            TetherTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalUsageEnv provides UsageFixtures.env) {
                    val scope = rememberCoroutineScope()
                    val state = remember { UsageAccountsState(scope, { source }, { UsageFixtures.ORIGIN }).also { it.seed(payload) } }
                    UsageAccountsContent(
                        state = state,
                        onUseCredit = { error("selected account detail must not expose Codex reset actions") },
                        onUseGrant = { error("this fixture does not select Claude") },
                        modifier = Modifier.fillMaxSize(),
                        activeEntity = activeEntity,
                        accountDetail = true,
                    )
                }
            }
        }
        return source
    }
}
