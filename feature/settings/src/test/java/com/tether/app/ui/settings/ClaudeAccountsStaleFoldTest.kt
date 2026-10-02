package com.tether.app.ui.settings

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ClaudeAccountsJson
import com.tether.app.client.SecurityResult
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-7rh r2 (verifier P2, its probes made permanent): a list re-read in flight across a sync change
 * folds the answer into the state as it is when it lands, so a Sync now result or a saved sync
 * setting is never put back, and the next save carries what the server kept. Each probe keeps its
 * positive control (the change was shown before the held read landed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ClaudeAccountsStaleFoldTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)
    private val state = SettingsDialogState(SettingsTab.Engines)

    private fun everything(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }
    private fun has(t: String) = everything().any { it.contains(t) }
    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    @Test fun aSyncResultSurvivesAListReReadThatWasInFlight() {
        val gate = CompletableDeferred<Unit>()
        var lists = 0
        val reads = FakeAccounts(listGate = { if (++lists == 2) gate.await() })
        val saved = ClaudeAccountsJson.syncSaved(ClaudeAccountsJson.parseObject(
            """{"config":{"mode":"selected","categories":{"plugins":true,"skills":true,"hooks":false,"mcp":true},"primaryAccountId":"claude-default"},"result":{"ranAt":1790000000000,"status":"ok","entries":[]}}""")!!)!!
        val actions = FakeAccountActions(auto = { n ->
            when (n) {
                "add" -> SecurityResult.Ok(Unit, ORIGIN, null)
                "runSync" -> SecurityResult.Ok(saved, ORIGIN, null)
                else -> null
            }
        })
        compose.setContent { SettingsUnderTest(store.prefs, state, claudeAccounts = ClaudeAccountsBinding(reads, ORIGIN, AccountsFixtures.TIME, actions = actions)) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { has("Claude Code (work)") && has("Sync across accounts") }
        // Add -> Ok -> the list is read again (held at the gate)
        tag(ClaudeAccountsTags.Add).performScrollTo().performClick()
        tag(ClaudeAccountsTags.AddField).performTextReplacement("x")
        tag(ClaudeAccountsTags.AddSubmit).performScrollTo().performClick()
        compose.waitUntil(5_000) { lists == 2 && actions.calls.none { it.name == "add" && !it.reply.isCompleted } }
        compose.waitForIdle()
        // Sync now while that read is in flight
        tag(ClaudeAccountsTags.SyncNow).performScrollTo().performClick()
        compose.waitUntil(5_000) { has("0 updated, 0 already current.") }  // positive control: the run's result is shown
        gate.complete(Unit)
        compose.waitUntil(5_000) { reads.calls.count { it == "list" } >= 2 }
        compose.waitForIdle(); compose.waitForIdle()
        val after = everything().filter { it.contains("updated") }
        assertTrue("the run's result must survive the list re-read: $after", has("0 updated, 0 already current."))
    }

    @Test fun aSavedSyncConfigSurvivesAListReReadAndTheNextSaveBuildsOnIt() {
        val gate = CompletableDeferred<Unit>()
        var lists = 0
        val reads = FakeAccounts(listGate = { if (++lists == 2) gate.await() })
        val hooksOn = ClaudeAccountsJson.syncSaved(ClaudeAccountsJson.parseObject(
            """{"config":{"mode":"selected","categories":{"plugins":true,"skills":true,"hooks":true,"mcp":true},"primaryAccountId":"claude-default"},"result":{"ranAt":1790000000000,"status":"ok","entries":[]}}""")!!)!!
        val actions = FakeAccountActions(auto = { n ->
            when (n) {
                "add" -> SecurityResult.Ok(Unit, ORIGIN, null)
                "saveSync" -> SecurityResult.Ok(hooksOn, ORIGIN, null)
                else -> null
            }
        })
        compose.setContent { SettingsUnderTest(store.prefs, state, claudeAccounts = ClaudeAccountsBinding(reads, ORIGIN, AccountsFixtures.TIME, actions = actions)) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { has("Claude Code (work)") && has("Sync across accounts") }
        tag(ClaudeAccountsTags.Add).performScrollTo().performClick()
        tag(ClaudeAccountsTags.AddField).performTextReplacement("x")
        tag(ClaudeAccountsTags.AddSubmit).performScrollTo().performClick()
        compose.waitUntil(5_000) { lists == 2 && actions.calls.none { it.name == "add" && !it.reply.isCompleted } }
        compose.waitForIdle()
        tag(ClaudeAccountsTags.syncCategory("hooks")).performScrollTo().performClick()
        compose.waitUntil(5_000) { actions.calls.count { it.name == "saveSync" } == 1 }
        compose.waitForIdle()
        assertTrue("positive control: the first save turned hooks on", actions.calls.first { it.name == "saveSync" }.arg!!.contains("hooks=true"))
        gate.complete(Unit)
        compose.waitForIdle(); compose.waitForIdle()
        tag(ClaudeAccountsTags.syncCategory("skills")).performScrollTo().performClick()
        compose.waitUntil(5_000) { actions.calls.count { it.name == "saveSync" } == 2 }
        val second = actions.calls.filter { it.name == "saveSync" }[1].arg!!
        assertTrue("the second save must keep hooks on: $second", second.contains("hooks=true"))
    }
}
