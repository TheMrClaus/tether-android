package com.tether.app.ui.overview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tether.app.client.ConnectionState
import com.tether.app.client.ConsentGuard
import com.tether.app.client.ConsentResult
import com.tether.app.client.HostCpu
import com.tether.app.client.HostMetrics
import com.tether.app.client.HostReading
import com.tether.app.client.LoginResult
import com.tether.app.client.OverviewMetricsResult
import com.tether.app.client.OverviewMetricsSource
import com.tether.app.client.OverviewUsage
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.client.UsageCoverage
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.overview.HostUsageFixtures.ORIGIN_A
import com.tether.app.ui.overview.HostUsageFixtures.ORIGIN_B
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.3: overview-host.tsx `useJsonPoll`'s lifecycle on the phone — the two routes are fetched only
 * while the tile is shown AND the app is started AND signed in, at the web's cadence (host 5 s,
 * usage 15 s), at once on return; hiding, ON_STOP, a server switch and a sign-out stop them; and
 * after a switch nothing server A said is shown, even an answer A sends late. Server text reaches
 * the screen and TalkBack only through the label rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2000dp-420dpi")
class HostUsageBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val client = MetricsClient()
    private val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
    private var shown by mutableStateOf(true)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TetherTheme(choiceFor(TetherSkin.Studio)) {
                    if (shown) HostUsageHost(client, clock = { HostUsageFixtures.NOW })
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun meter(label: String) = rule.onNodeWithTag(HostUsageTags.meter(label))

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    /** Every string the tile puts on screen or in front of TalkBack (text, descriptions, states). */
    private fun everything(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @Test fun itPollsOnlyWhileShownAndStartedAtTheWebsCadence() {
        show()
        assertEquals("both at once on showing", listOf(1, 1), client.metrics.counts())
        meter("CPU").assert(stateIs("24%"))
        advance(5_100)
        assertEquals(listOf(2, 1), client.metrics.counts())
        advance(10_000)
        assertEquals("host every 5 s, usage every 15 s", listOf(4, 2), client.metrics.counts())

        // ON_STOP (the app went to the background): nothing more, however long.
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        val stopped = client.metrics.counts()
        advance(60_000)
        assertEquals(stopped, client.metrics.counts())

        // ON_START: both at once again (the web's visibilitychange refetch).
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        settle()
        assertEquals(listOf(stopped[0] + 1, stopped[1] + 1), client.metrics.counts())

        // Hidden (the Overview left the screen): nothing more.
        shown = false
        rule.waitForIdle()
        val hidden = client.metrics.counts()
        advance(60_000)
        assertEquals(hidden, client.metrics.counts())
    }

    @Test fun afterASwitchNothingServerASaidIsShownEvenALateAnswer() {
        show()
        meter("CPU").assert(stateIs("24%"))
        assertTrue(everything().any { it.startsWith("UTC day") })

        // A's next host answer is slow: it is in flight when the operator switches to B.
        val late = CompletableDeferred<OverviewMetricsResult<HostMetrics>>()
        client.metrics.host = { late.await() }
        advance(5_100)
        assertEquals(2, client.metrics.hostCalls)

        // B answers nothing yet.
        val never = CompletableDeferred<OverviewMetricsResult<HostMetrics>>()
        val neverUsage = CompletableDeferred<OverviewMetricsResult<OverviewUsage>>()
        client.metrics.host = { never.await() }
        client.metrics.usage = { neverUsage.await() }
        rule.runOnIdle { client.server.value = "https://b.test" }
        settle()
        assertTrue("B is asked at once", client.metrics.hostCalls >= 3)
        meter("CPU").assert(stateIs("Measuring…"))
        meter("Memory").assert(stateIs("Measuring…"))
        assertEquals("…", tokens())

        // A's late answer lands after the switch: it is never shown.
        late.complete(OverviewMetricsResult.Ok(HostUsageFixtures.host.copy(cpu = HostReading.Value(HostCpu(99.0, 8))), ORIGIN_A))
        advance(100)
        meter("CPU").assert(stateIs("Measuring…"))
        assertFalse(everything().any { "99%" in it || "24%" in it || "1.28M" in it })

        // B's own answer shows.
        never.complete(OverviewMetricsResult.Ok(HostUsageFixtures.hostWarming, ORIGIN_B))
        advance(100)
        meter("Memory").assert(stateIs("10.0 / 16.0 GB"))
    }

    @Test fun signingOutStopsThePollsAndClearsTheReadings() {
        show()
        meter("CPU").assert(stateIs("24%"))
        rule.runOnIdle { client.configured.value = false }
        settle()
        val before = client.metrics.counts()
        advance(60_000)
        assertEquals(before, client.metrics.counts())
        meter("CPU").assert(stateIs("Measuring…"))
        assertFalse(everything().any { "24%" in it || "1.28M" in it || "OS-visible" in it })
    }

    @Test fun aRefusedCredentialDropsTheReadingAndSaysSignedOut() {
        show()
        meter("CPU").assert(stateIs("24%"))
        client.metrics.host = { OverviewMetricsResult.SignedOut(ORIGIN_A) }
        advance(5_100)
        meter("CPU").assert(stateIs("Unavailable"))
        assertEquals("Host readings unavailable: Signed out", text(HostUsageTags.Notice))
    }

    @Test fun aSignInGatewayIsNamed() {
        client.metrics.host = { OverviewMetricsResult.Blocked(302, ORIGIN_A) }
        show()
        assertTrue(everything().any { it.contains("Blocked by a sign-in page") })
        assertTrue(everything().any { it.contains("Exempt /api/overview/ for paired devices") })
    }

    @Test fun hostileServerTextNeverReachesTheScreenOrTalkBackRaw() {
        val rlo = "\u202E"
        val hostile = "OS\u202Egnihton\u200B\u2066\nvisible\u0007"
        client.metrics.host = { OverviewMetricsResult.Ok(HostUsageFixtures.host.copy(scopeLabel = hostile), ORIGIN_A) }
        client.metrics.usage = {
            OverviewMetricsResult.Ok(
                OverviewUsage(
                    tokensToday = 7.0,
                    label = "Tokens\u202E today\n\u200Bfake",
                    partial = true,
                    coverage = listOf(UsageCoverage("open\u202Ecode\n", "partial"), UsageCoverage("\u2067rea\u2069sonix", "not_reported")),
                ),
                ORIGIN_A,
            )
        }
        show()
        val seen = everything()
        val raw = listOf(rlo, "\u200B", "\u2066", "\u2067", "\u2069", "\n", "\u0007")
        for (s in seen) for (c in raw) assertFalse("raw U+%04X in \"%s\"".format(c[0].code, s), c in s)
        assertEquals("OSgnihton visible", text(HostUsageTags.Scope))
        assertTrue(seen.any { it == "Readings are OSgnihton visible, for this Tether node only" })
        assertTrue(seen.any { it == "Tokens today fake" })
        assertEquals("UTC day · partial: opencode · not reported: reasonix · just now", text(HostUsageTags.UsageNote))

        // A label of nothing but invisibles is spelled out, never blank.
        client.metrics.host = { OverviewMetricsResult.Ok(HostUsageFixtures.host.copy(scopeLabel = "\u200B\u202E"), ORIGIN_A) }
        advance(5_100)
        val spelled = text(HostUsageTags.Scope)
        assertTrue(spelled, spelled.isNotBlank() && rlo !in spelled && "\u200B" !in spelled && spelled != "This node")
    }

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun tokens(): String {
        val node = rule.onNodeWithTag(HostUsageTags.Tokens).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text][1].text
    }
}

/** Scripted readings, counting every call (the only network the tile may cause). */
private class FakeMetrics : OverviewMetricsSource {
    var hostCalls = 0
    var usageCalls = 0
    var host: suspend () -> OverviewMetricsResult<HostMetrics> = { OverviewMetricsResult.Ok(HostUsageFixtures.host, ORIGIN_A) }
    var usage: suspend () -> OverviewMetricsResult<OverviewUsage> = { OverviewMetricsResult.Ok(HostUsageFixtures.usageExact, ORIGIN_A) }

    fun counts() = listOf(hostCalls, usageCalls)

    override suspend fun host(): OverviewMetricsResult<HostMetrics> {
        hostCalls++
        return host.invoke()
    }

    override suspend fun usage(): OverviewMetricsResult<OverviewUsage> {
        usageCalls++
        return usage.invoke()
    }
}

/** A client whose only live parts are the tile's inputs; everything else records nothing and answers nothing. */
private class MetricsClient : TetherClient {
    val metrics = FakeMetrics()
    val server = MutableStateFlow<String?>("https://a.test")
    override val overviewMetrics: OverviewMetricsSource get() = metrics
    override val serverUrl: StateFlow<String?> get() = server

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
    override val configured = MutableStateFlow(true)
    override val trimmedBefore: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val liveSessions: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val reportsFreshness: Boolean = false
    override val consentOrigin: StateFlow<String?> = MutableStateFlow(null)
    override val decidedRequests: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() = Unit
    override fun stop() = Unit
    override fun attach(sessionId: String) = error("the tile never attaches")
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) = error("the tile never sends")
    override fun queueAdd(sessionId: String, text: String) = error("unused")
    override fun queueEdit(sessionId: String, queueId: String, text: String) = error("unused")
    override fun queueRemove(sessionId: String, queueId: String) = error("unused")
    override fun approval(sessionId: String, requestId: String, expectedFingerprint: String, choiceId: String?, decision: String?, grantedPermissions: GrantedPermissions?): ConsentResult = error("unused")
    override fun answerQuestion(sessionId: String, requestId: String, expectedFingerprint: String, picks: List<ConsentGuard.QuestionPick>, skipped: Set<Int>): ConsentResult = error("unused")
    override fun createSession(provider: String, cwd: String?, name: String?) = error("unused")
    override fun resumeHistory(historyId: String, cwd: String) = error("unused")
    override fun discover(cwd: String) = error("unused")
    override fun browse(cwd: String?) = error("unused")
    override fun requestSessionControls(sessionId: String) = error("unused")
    override fun pin(sessionId: String, pinned: Boolean) = error("unused")
    override fun rename(sessionId: String, name: String) = error("unused")
    override fun archive(sessionId: String) = error("unused")
    override fun kill(sessionId: String, expectedOrigin: String?, requireLive: Boolean) = error("unused")
    override fun reconnectIfIdle() = Unit
    override fun setAppForeground(foreground: Boolean) = Unit
    override fun retryConnection() = Unit
}
