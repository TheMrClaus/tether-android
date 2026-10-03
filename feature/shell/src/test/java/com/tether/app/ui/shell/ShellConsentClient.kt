package com.tether.app.ui.shell

import com.tether.app.client.ConnectionState
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** T6.3 round 4: ChatTestClient's twin for MainShell tests (feature:chat test sources are not visible here). */
class ShellConsentClient : TetherClient {
    /** T6.3: the link state the consent cards read. */
    val link = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    // T9.3: the scheduled actions the Scheduled destination draws, and the controls it sent.
    val scheduled = MutableStateFlow(com.tether.app.client.ScheduledActionsState())
    override val scheduledActions: StateFlow<com.tether.app.client.ScheduledActionsState> get() = scheduled
    val scheduleControls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    override fun controlSchedule(scheduleId: String, action: String): Boolean = scheduleControls.add(scheduleId to action)
    override val connection: StateFlow<ConnectionState> get() = link
    override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    override val providers: StateFlow<List<ProviderInfo>> = MutableStateFlow(emptyList())
    override val workspaceRoot: StateFlow<String?> = MutableStateFlow("/w")
    override val projections = MutableStateFlow<Map<String, SessionProjection>>(emptyMap())
    override val projectionTrees = MutableStateFlow<Map<String, JsObj>>(emptyMap())
    override val histories: StateFlow<List<HistorySession>> = MutableStateFlow(emptyList())
    override val directories: StateFlow<DirectoryListing?> = MutableStateFlow(null)
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = MutableStateFlow(emptyMap())
    override val errors: SharedFlow<String> = MutableSharedFlow()
    override val configured: StateFlow<Boolean> = MutableStateFlow(true)
    override val trimmedBefore: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    /** T7.1: the configured server (drafts are kept per its origin); null = none, as before. */
    val server = MutableStateFlow<String?>(null)
    override val serverUrl: StateFlow<String?> get() = server

    /** T7.1: what the composer put on the wire path: `send:<text>`, `queue-add:<text>`, … */
    val outbox = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** T6.3: sessions confirmed live, and the ledger's decided keys (tests drive both). */
    val live = MutableStateFlow<Set<String>>(emptySet())
    val decided = MutableStateFlow<Set<String>>(emptySet())
    override val liveSessions: StateFlow<Set<String>> get() = live
    /** T13.2 r3: keeps the interface's empty syncStates, so it reports no freshness (the T6.3 rule). */
    override val reportsFreshness: Boolean = false
    override val decidedRequests: StateFlow<Set<String>> get() = decided
    val unconfirmed = MutableStateFlow<Set<String>>(emptySet())
    override val unconfirmedRequests: StateFlow<Set<String>> get() = unconfirmed

    /** The test "server" origin the cards fingerprint for. */
    val origin = MutableStateFlow<String?>(SHELL_TEST_ORIGIN)
    override val consentOrigin: StateFlow<String?> get() = origin

    /**
     * T6.3: every consent call the UI made, in order, NOT de-duplicated (the UI's own send-once
     * guard is what the behaviour tests prove): `approval:<session>:<request>:<choiceId|decision>[:<grant>]`,
     * `question:<session>:<request>:<answers>[:<response>]`.
     */
    val consentCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** What the next consent call returns (the real client's verdict). */
    var consentResult: com.tether.app.client.ConsentResult = com.tether.app.client.ConsentResult.Sent

    fun show(session: AgentSession, tree: JsObj) {
        live.value = live.value + session.id
        sessions.value = listOf(session) + sessions.value.filter { it.id != session.id }
        projections.value = projections.value + (session.id to com.tether.app.protocol.model.LegacyProjectionAdapter.adaptOnce(tree)!!)
        projectionTrees.value = projectionTrees.value + (session.id to tree)
    }

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() = Unit
    override fun stop() = Unit
    override fun attach(sessionId: String) {
        attachCalls += sessionId
    }
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
        outbox += "send:$text"
    }
    override fun queueAdd(sessionId: String, text: String) {
        outbox += "queue-add:$text"
    }
    override fun queueEdit(sessionId: String, queueId: String, text: String) {
        outbox += "queue-edit:$queueId:$text"
    }
    override fun queueRemove(sessionId: String, queueId: String) {
        outbox += "queue-remove:$queueId"
    }
    override fun approval(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        choiceId: String?,
        decision: String?,
        grantedPermissions: com.tether.app.protocol.GrantedPermissions?,
    ): com.tether.app.client.ConsentResult {
        consentCalls += "approval:$sessionId:$requestId:${choiceId ?: decision}" + (grantedPermissions?.let { ":" + it.toJsonObject() } ?: "")
        return settle(sessionId, requestId, expectedFingerprint)
    }
    override fun answerQuestion(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        picks: List<com.tether.app.client.ConsentGuard.QuestionPick>,
        skipped: Set<Int>,
    ): com.tether.app.client.ConsentResult {
        // Like the real client: the guard builds the answer from the request (and refuses bad indices).
        val tree = projectionTrees.value[sessionId]
        val request = com.tether.app.client.ConsentGuard.pendingQuestion(tree, requestId)
        val reply = request?.let { com.tether.app.client.ConsentGuard.buildAnswers(it, picks, skipped) }
        consentCalls += "question:$sessionId:$requestId:" + (reply?.let { "${it.answers}" + (it.response?.let { r -> ":$r" } ?: "") } ?: "<invalid $picks>")
        return settle(sessionId, requestId, expectedFingerprint)
    }

    /** Like the real client: a sent decision is published in [decided]. */
    private fun settle(sessionId: String, requestId: String, fingerprint: String): com.tether.app.client.ConsentResult {
        val result = consentResult
        if (result == com.tether.app.client.ConsentResult.Sent) decided.value = decided.value + com.tether.app.client.consentKey(sessionId, requestId, fingerprint)
        return result
    }
    override fun resumeHistory(historyId: String, cwd: String) = Unit
    override fun discover(cwd: String) = Unit
    override fun browse(cwd: String?) = Unit
    override fun requestSessionControls(sessionId: String) = Unit
    override fun pin(sessionId: String, pinned: Boolean) = Unit
    override fun rename(sessionId: String, name: String) = Unit
    override fun archive(sessionId: String) = Unit
    /** T13.2 r2/r3: every End session the shell asked for (`<session>@<origin>:<requireLive>`). */
    /** T15.4 r2: every sign-out the UI asked for. */
    val logoutCalls = java.util.concurrent.atomic.AtomicInteger(0)
    override suspend fun logout(): com.tether.app.client.LogoutResult {
        logoutCalls.incrementAndGet()
        return com.tether.app.client.LogoutResult.LocalOnly
    }

    val killCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun kill(sessionId: String, expectedOrigin: String?, requireLive: Boolean) {
        killCalls += "$sessionId@$expectedOrigin:$requireLive"
    }
    /** T6.7 r2: every interrupt the shell asked for (`<session>@<origin>#<turn>`). */
    val interruptCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun interrupt(sessionId: String, expectedOrigin: String?, expectedTurnId: String): com.tether.app.client.InterruptResult {
        interruptCalls += "$sessionId@$expectedOrigin#$expectedTurnId"
        return com.tether.app.client.InterruptResult.Sent
    }
    /** T15.2: the Overview feed the tests drive, and every call the UI made to it or to mark-seen. */
    val overviewState = MutableStateFlow(com.tether.app.protocol.overview.OverviewClientState())
    override val overview: StateFlow<com.tether.app.protocol.overview.OverviewClientState> get() = overviewState
    val feedCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun subscribeOverview(subscription: com.tether.app.protocol.overview.OverviewSubscription): Boolean {
        feedCalls += "overview-subscribe"
        return true
    }
    override fun unsubscribeOverview() {
        feedCalls += "overview-unsubscribe"
    }
    val seenCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun markSeen(historyId: String, seenAt: Long): Boolean {
        seenCalls += historyId
        return true
    }
    val attachCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun reconnectIfIdle() = Unit
    override fun setAppForeground(foreground: Boolean) = Unit
    override fun retryConnection() = Unit
}

const val SHELL_TEST_ORIGIN = "http://127.0.0.1:4290"
