package com.tether.app.ui.overview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tether.app.client.ConnectionState
import com.tether.app.client.ConsentGuard
import com.tether.app.client.ConsentResult
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewSubscription
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.2: the Overview hands off and never acts. Its buttons call the shell's handlers with the
 * right ids; [OverviewHost] subscribes while shown and started, re-subscribes on a filter change
 * (page 0), unsubscribes on ON_STOP and on leaving; and the client sees NOTHING else from it: no
 * attach, mark-seen, approval, answer, send or anything an agent would act on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2600dp-420dpi")
class OverviewBehaviourTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theButtonsHandOffWithTheRightIds() {
        val calls = mutableListOf<String>()
        val actions = OverviewActions(
            onOpenSession = { calls += "open:$it" },
            onReviewRequest = { s, r -> calls += "review:$s:$r" },
            onNewSession = { calls += "new" },
            onOpenEventLog = { calls += "log" },
        )
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated, actions = actions) }
        rule.onNodeWithTag(OverviewTags.review("s-sync")).performScrollTo().performClick()
        rule.onNodeWithTag(OverviewTags.reviewPending("s-sync", "r-1")).performScrollTo().performClick()
        rule.onNodeWithTag(OverviewTags.open("s-proto")).performScrollTo().performClick()
        rule.onNodeWithTag(OverviewTags.activityRow(OverviewFixtures.activity[2].id)).performScrollTo().performClick()
        rule.onNodeWithTag(OverviewTags.NewSession).performClick()
        assertEquals(listOf("review:s-sync:r-1", "review:s-sync:r-1", "open:s-proto", "open:s-game", "new"), calls)
    }

    @Test fun aStatusTabIsAFilterChoice() {
        var choice by mutableStateOf(OverviewChoice())
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, OverviewFixtures.populated, choice = choice, onChoice = { choice = it }) }
        rule.onNodeWithTag(OverviewTags.tab(StatusTab.Waiting)).performClick()
        assertEquals(OverviewChoice(status = StatusTab.Waiting), choice)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun theHostSubscribesOnlyWhileShownAndStartedAndDoesNothingElse() {
        val client = RecordingClient()
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var shown by mutableStateOf(true)
        var choice by mutableStateOf(OverviewChoice())
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TetherTheme(choiceFor(TetherSkin.Studio)) {
                    if (shown) OverviewHost(client, choice, { choice = it }, OverviewActions())
                }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf("subscribe:{}:0"), client.calls)
        // A filter re-subscribes from the first page.
        rule.onNodeWithTag(OverviewTags.tab(StatusTab.Ready)).performClick()
        rule.waitForIdle()
        assertEquals("subscribe:{\"statuses\":[\"ready\"]}:0", client.calls.last())
        // ON_STOP (the app went to the background): one unsubscribe. ON_START: subscribe again.
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals("unsubscribe", client.calls.last())
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals("subscribe:{\"statuses\":[\"ready\"]}:0", client.calls.last())
        // Leaving the Overview: one unsubscribe.
        shown = false
        rule.waitForIdle()
        assertEquals("unsubscribe", client.calls.last())
        assertEquals("nothing but the feed's own two frames", emptyList<String>(), client.calls.filterNot { it.startsWith("subscribe:") || it == "unsubscribe" })
        assertEquals(5, client.calls.size)
    }
}

/** A client that records every call the Overview could make; only the overview two may appear. */
private class RecordingClient : TetherClient {
    val calls = mutableListOf<String>()
    val state = MutableStateFlow(OverviewFixtures.populated)
    override val overview: StateFlow<OverviewClientState> get() = state
    override fun subscribeOverview(subscription: OverviewSubscription): Boolean {
        calls += "subscribe:${subscription.filters?.toJsonObject()}:${subscription.page}"
        return true
    }
    override fun unsubscribeOverview() {
        calls += "unsubscribe"
    }
    override fun markSeen(historyId: String, seenAt: Long): Boolean { calls += "mark-seen:$historyId"; return true }

    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
    override val sessions: StateFlow<List<AgentSession>> = MutableStateFlow(emptyList())
    override val providers: StateFlow<List<ProviderInfo>> = MutableStateFlow(emptyList())
    override val workspaceRoot: StateFlow<String?> = MutableStateFlow(null)
    override val projections: StateFlow<Map<String, SessionProjection>> = MutableStateFlow(emptyMap())
    override val projectionTrees: StateFlow<Map<String, JsObj>> = MutableStateFlow(emptyMap())
    override val histories: StateFlow<List<HistorySession>> = MutableStateFlow(emptyList())
    override val directories: StateFlow<DirectoryListing?> = MutableStateFlow(null)
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = MutableStateFlow(emptyMap())
    override val errors: SharedFlow<String> = MutableSharedFlow()
    override val configured: StateFlow<Boolean> = MutableStateFlow(true)
    override val trimmedBefore: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val liveSessions: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val reportsFreshness: Boolean = false
    override val consentOrigin: StateFlow<String?> = MutableStateFlow(null)
    override val decidedRequests: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun attach(sessionId: String) { calls += "attach:$sessionId" }
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) { calls += "send" }
    override fun queueAdd(sessionId: String, text: String) { calls += "queue-add" }
    override fun queueEdit(sessionId: String, queueId: String, text: String) { calls += "queue-edit" }
    override fun queueRemove(sessionId: String, queueId: String) { calls += "queue-remove" }
    override fun approval(sessionId: String, requestId: String, expectedFingerprint: String, choiceId: String?, decision: String?, grantedPermissions: GrantedPermissions?): ConsentResult {
        calls += "approval"
        return ConsentResult.Sent
    }
    override fun answerQuestion(sessionId: String, requestId: String, expectedFingerprint: String, picks: List<ConsentGuard.QuestionPick>, skipped: Set<Int>): ConsentResult {
        calls += "question"
        return ConsentResult.Sent
    }
    override fun resumeHistory(historyId: String, cwd: String) { calls += "resume" }
    override fun discover(cwd: String) { calls += "discover" }
    override fun browse(cwd: String?) { calls += "browse" }
    override fun requestSessionControls(sessionId: String) { calls += "session-controls" }
    override fun pin(sessionId: String, pinned: Boolean) { calls += "pin" }
    override fun rename(sessionId: String, name: String) { calls += "rename" }
    override fun archive(sessionId: String) { calls += "archive" }
    override fun kill(sessionId: String, expectedOrigin: String?) { calls += "kill" }
    override fun reconnectIfIdle() = Unit
    override fun setAppForeground(foreground: Boolean) = Unit
    override fun retryConnection() = Unit
}
