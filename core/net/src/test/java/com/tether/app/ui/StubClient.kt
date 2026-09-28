package com.tether.app.ui

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

/** An inert [TetherClient] for view-model tests: only the members the view-model touches do anything. */
internal open class StubClient : TetherClient {
    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
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
    val attached = mutableListOf<String>()

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() = Unit
    override fun stop() = Unit
    override fun attach(sessionId: String) {
        attached += sessionId
    }
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) = Unit
    override fun queueAdd(sessionId: String, text: String) = Unit
    override fun queueEdit(sessionId: String, queueId: String, text: String) = Unit
    override fun queueRemove(sessionId: String, queueId: String) = Unit
    override fun interrupt(sessionId: String) = Unit
    override fun approval(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        choiceId: String?,
        decision: String?,
        grantedPermissions: com.tether.app.protocol.GrantedPermissions?,
    ) = com.tether.app.client.ConsentResult.NotConnected
    override fun answerQuestion(sessionId: String, requestId: String, expectedFingerprint: String, picks: List<com.tether.app.client.ConsentGuard.QuestionPick>, skipped: Set<Int>) =
        com.tether.app.client.ConsentResult.NotConnected
    override val consentOrigin: kotlinx.coroutines.flow.StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override val liveSessions: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val decidedRequests: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun createSession(provider: String, cwd: String?, name: String?) = Unit
    override fun resumeHistory(historyId: String, cwd: String) = Unit
    override fun discover(cwd: String) = Unit
    override fun browse(cwd: String?) = Unit
    override fun setMode(sessionId: String, permissionMode: String) = Unit
    override fun setModel(sessionId: String, model: String): Boolean = true
    override fun requestSessionControls(sessionId: String) = Unit
    override fun pin(sessionId: String, pinned: Boolean) = Unit
    override fun rename(sessionId: String, name: String) = Unit
    override fun archive(sessionId: String) = Unit
    override fun kill(sessionId: String) = Unit
    override fun reconnectIfIdle() = Unit
    override fun setAppForeground(foreground: Boolean) = Unit
    override fun retryConnection() = Unit
}
