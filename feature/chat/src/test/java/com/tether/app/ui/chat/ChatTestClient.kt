package com.tether.app.ui.chat

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

/** T5.3: an inert [TetherClient] for ChatScreen behaviour tests: sessions + projections only. */
class ChatTestClient : TetherClient {
    /** T6.7 r3: the turns whose interrupt the server reported failed, per session. */
    val failed = MutableStateFlow<Map<String, String>>(emptyMap())
    override val failedInterrupts: StateFlow<Map<String, String>> get() = failed

    /** ta-coik.19: the client's unresolved and given-up sends (tests drive both). */
    val pendingRows = MutableStateFlow<List<com.tether.app.client.PendingSendRow>>(emptyList())
    override val pendingSends: StateFlow<List<com.tether.app.client.PendingSendRow>> get() = pendingRows
    val failedRows = MutableStateFlow<List<com.tether.app.client.FailedSend>>(emptyList())
    override val failedSends: StateFlow<List<com.tether.app.client.FailedSend>> get() = failedRows

    /** ta-coik.19: every failed bubble the UI dismissed, in order; like the real client, it is dropped. */
    val dismissedSends = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun dismissFailedSend(key: String) {
        dismissedSends += key
        failedRows.value = failedRows.value.filter { it.key != key }
    }

    /** T6.3: the link state the consent cards read. */
    val link = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    override val connection: StateFlow<ConnectionState> get() = link
    override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    override val providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    override val workspaceRoot: StateFlow<String?> = MutableStateFlow("/w")
    override val projections = MutableStateFlow<Map<String, SessionProjection>>(emptyMap())
    override val projectionTrees = MutableStateFlow<Map<String, JsObj>>(emptyMap())
    override val histories: StateFlow<List<HistorySession>> = MutableStateFlow(emptyList())
    override val directories: StateFlow<DirectoryListing?> = MutableStateFlow(null)
    override val sessionControls = MutableStateFlow<Map<String, ServerMessage.SessionControls>>(emptyMap())
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
    /** T13.2: per-session freshness (empty = none reported, as before). */
    val sync = MutableStateFlow<Map<String, com.tether.app.client.SessionSync>>(emptyMap())
    override val syncStates: StateFlow<Map<String, com.tether.app.client.SessionSync>> get() = sync

    /**
     * T13.2 r2: whether this client reports freshness at all. Off by default, so every earlier test
     * keeps T6.3's rule (the live set alone); on, a session with no [sync] entry is NOT live.
     */
    var reports = false
    override val reportsFreshness: Boolean get() = reports
    override val decidedRequests: StateFlow<Set<String>> get() = decided
    val unconfirmed = MutableStateFlow<Set<String>>(emptySet())
    override val unconfirmedRequests: StateFlow<Set<String>> get() = unconfirmed

    /** The test "server" origin the cards fingerprint for. */
    val origin = MutableStateFlow<String?>(TEST_ORIGIN)
    override val consentOrigin: StateFlow<String?> get() = origin

    /**
     * T6.3: every consent call the UI made, in order, NOT de-duplicated (the UI's own send-once
     * guard is what the behaviour tests prove): `approval:<session>:<request>:<choiceId|decision>[:<grant>]`,
     * `question:<session>:<request>:<answers>[:<response>]`.
     */
    val consentCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** T6.4: every stop-command the UI asked for (`<session>:<commandId>`), NOT de-duplicated. */
    val stopCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** What the next stop returns (the real client's verdict). */
    var stopResult: com.tether.app.client.StopCommandResult = com.tether.app.client.StopCommandResult.Sent

    /** The origin each stop was bound to (L3), in call order. */
    val stopOrigins = java.util.concurrent.CopyOnWriteArrayList<String?>()

    override fun stopCommand(sessionId: String, commandId: String, expectedOrigin: String?): com.tether.app.client.StopCommandResult {
        stopCalls += "$sessionId:$commandId"
        stopOrigins += expectedOrigin
        return stopResult
    }

    /** ta-coik.22: every `!` run (`<session>:<command>[:bg]`) and Background (`<session>#<turn>`) the UI asked for. */
    val runCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    val backgroundCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun runCommand(sessionId: String, command: String, background: Boolean, expectedOrigin: String?): com.tether.app.client.RunCommandResult {
        runCalls += "$sessionId:$command" + if (background) ":bg" else ""
        return com.tether.app.client.RunCommandResult.Sent
    }

    override fun backgroundCommand(sessionId: String, expectedOrigin: String?, expectedTurnId: String): com.tether.app.client.BackgroundCommandResult {
        backgroundCalls += "$sessionId#$expectedTurnId"
        return com.tether.app.client.BackgroundCommandResult.Sent
    }

    /** What the next consent call returns (the real client's verdict). */
    var consentResult: com.tether.app.client.ConsentResult = com.tether.app.client.ConsentResult.Sent

    /** Publish [session] with [folded]'s projection and tree ([live]: confirmed on this connection). */
    fun show(session: AgentSession, folded: ChatFixtures.Folded, live: Boolean = true) {
        if (live) this.live.value = this.live.value + session.id
        sessions.value = listOf(session) + sessions.value.filter { it.id != session.id }
        projections.value = projections.value + (session.id to folded.projection)
        projectionTrees.value = projectionTrees.value + (session.id to folded.tree)
    }

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() = Unit
    override fun stop() = Unit
    override fun attach(sessionId: String) = Unit
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
    /** T13.2 r2 / T6.7: every interrupt the UI asked for (`<session>@<origin>#<turn>`), NOT de-duplicated. */
    val interruptCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** T6.7: what [interrupt] answers (the real client's refusals are its own tests). */
    @Volatile var interruptResult: com.tether.app.client.InterruptResult = com.tether.app.client.InterruptResult.Sent
    override fun interrupt(sessionId: String, expectedOrigin: String?, expectedTurnId: String): com.tether.app.client.InterruptResult {
        interruptCalls += "$sessionId@$expectedOrigin#$expectedTurnId"
        return interruptResult
    }

    /** T6.6 r3: every notice dismissal the UI asked for (`<session>:<key>@<origin>`), NOT de-duplicated. */
    val dismissCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun dismissNotice(sessionId: String, dismissKey: String, expectedOrigin: String?): com.tether.app.client.NoticeResult {
        dismissCalls += "$sessionId:$dismissKey@$expectedOrigin"
        return com.tether.app.client.NoticeResult.Sent
    }

    /** T13.2 r2: every session control the UI asked for (`<session>:<control>`). */
    val controlCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun sessionControl(sessionId: String, control: com.tether.app.client.SessionControl, expectedOrigin: String?): com.tether.app.client.ControlResult {
        controlCalls += "$sessionId:$control"
        return com.tether.app.client.ControlResult.Sent
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
    /** T13.2 r2/r3: every End session the UI asked for (`<session>@<origin>`). */
    val killCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun kill(sessionId: String, expectedOrigin: String?) {
        killCalls += "$sessionId@$expectedOrigin"
    }
    override fun reconnectIfIdle() = Unit
    override fun setAppForeground(foreground: Boolean) = Unit
    override fun retryConnection() = Unit
}

fun chatSession(id: String, historyId: String?, name: String = id) = AgentSession(
    id = id, provider = "claude", name = name, cwd = "/w", status = "ready",
    startedAt = 1, updatedAt = 1, historyId = historyId,
)

/** T6.3: the origin ChatTestClient reports for its live socket. */
const val TEST_ORIGIN = "http://127.0.0.1:4290"
