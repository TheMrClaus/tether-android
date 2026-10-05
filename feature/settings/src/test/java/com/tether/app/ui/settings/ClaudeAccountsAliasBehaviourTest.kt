package com.tether.app.ui.settings

import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ClaudeAccountAlias
import com.tether.app.client.ClaudeAccountsResult
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-89k: the Terminal alias row of each Claude account card (settings-dialog.tsx 90fbb9f
 * :1810-1832, `toggleAlias` :1630-1643, `copyAlias` :1645-1651): Show fetches the alias once
 * ("Loading alias…" meanwhile), shows the shell line with Copy and the `.bashrc`/`.zshrc` snippet;
 * Copy puts the raw line on the clipboard and says "Copied" for 1.5 s; Hide closes it and Show again
 * asks nothing; a failure is the web's one sentence, and Show after it asks again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ClaudeAccountsAliasBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)

    private val line = """alias claude-work='CLAUDE_CONFIG_DIR="/srv/tether/state/claude-accounts/claude-work" claude'"""
    private val snippet = """[ -f "/srv/tether/state/claude-aliases.sh" ] && . "/srv/tether/state/claude-aliases.sh""""

    private fun show(fake: FakeAccounts) {
        compose.setContent { SettingsUnderTest(store.prefs, state, claudeAccounts = fake.binding()) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { runCatching { tag(ClaudeAccountsTags.alias("claude-work")).assertExists() }.isSuccess }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = runCatching { tag(t).assertExists() }.isSuccess

    private fun texts(t: String): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out.append(it.text) }
            node.children.forEach(::walk)
        }
        walk(tag(t).fetchSemanticsNode())
        return out.toString()
    }

    private fun tap(t: String) {
        tag(t).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun clip(): String? {
        val manager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test fun showFetchesTheLineCopyCopiesItAndHideClosesIt() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeAccounts(
            aliases = mapOf("claude-work" to ClaudeAccountsResult.Ok(ClaudeAccountAlias(line, snippet), ORIGIN)),
            aliasGate = { gate.await() },
        )
        show(fake)
        val toggle = ClaudeAccountsTags.alias("claude-work")
        assertEquals(ClaudeAccountsCopy.ALIAS_SHOW, texts(toggle))
        assertTrue(texts(ClaudeAccountsTags.card("claude-work")).contains(ClaudeAccountsCopy.ALIAS_CAPTION))
        assertTrue("nothing is fetched before Show", fake.calls.none { it.startsWith("alias") })

        tap(toggle)
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.aliasLoading("claude-work")) }
        assertEquals(ClaudeAccountsCopy.ALIAS_LOADING, texts(ClaudeAccountsTags.aliasLoading("claude-work")))
        assertEquals(ClaudeAccountsCopy.ALIAS_HIDE, texts(toggle))
        gate.complete(Unit)
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.aliasLine("claude-work")) }
        assertEquals(line, texts(ClaudeAccountsTags.aliasLine("claude-work")))
        assertEquals(
            "Add this once to your .bashrc/.zshrc to keep aliases in sync automatically: $snippet",
            texts(ClaudeAccountsTags.aliasSnippet("claude-work")),
        )
        assertEquals(listOf("alias:claude-work"), fake.calls.filter { it.startsWith("alias") })

        // Copy: the raw line on the clipboard, "Copied" for 1.5 s.
        val copy = ClaudeAccountsTags.aliasCopy("claude-work")
        assertEquals(ClaudeAccountsCopy.COPY, texts(copy))
        tap(copy)
        compose.waitUntil(5_000) { texts(copy) == ClaudeAccountsCopy.COPIED }
        assertEquals(line, clip())
        compose.mainClock.advanceTimeBy(ClaudeAccountsCopy.COPIED_MS + 200)
        compose.waitUntil(5_000) { texts(copy) == ClaudeAccountsCopy.COPY }

        // Hide closes; Show again draws the kept alias and asks nothing more (the web's `existing` check).
        tap(toggle)
        compose.waitUntil(5_000) { !exists(ClaudeAccountsTags.aliasLine("claude-work")) }
        assertEquals(ClaudeAccountsCopy.ALIAS_SHOW, texts(toggle))
        tap(toggle)
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.aliasLine("claude-work")) }
        assertEquals(1, fake.calls.count { it.startsWith("alias") })

        // Opening another account's closes this one (one `aliasOpenId`).
        tap(ClaudeAccountsTags.alias("claude-fresh"))
        compose.waitUntil(5_000) { !exists(ClaudeAccountsTags.aliasLine("claude-work")) }
    }

    @Test fun aFailureIsTheWebsSentenceAndShowAsksAgain() {
        // The fake answers an unknown id 404 "No such Claude account.".
        val fake = FakeAccounts()
        show(fake)
        val toggle = ClaudeAccountsTags.alias("claude-work")
        tap(toggle)
        compose.waitUntil(5_000) { exists(ClaudeAccountsTags.aliasFailed("claude-work")) }
        assertEquals(ClaudeAccountsCopy.ALIAS_FAILED, texts(ClaudeAccountsTags.aliasFailed("claude-work")))
        tap(toggle)
        tap(toggle)
        compose.waitUntil(5_000) { fake.calls.count { it == "alias:claude-work" } == 2 }
    }
}
