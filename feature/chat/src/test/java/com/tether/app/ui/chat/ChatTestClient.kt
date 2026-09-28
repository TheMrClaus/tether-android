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
    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
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

    /** Publish [session] with [folded]'s projection and tree. */
    fun show(session: AgentSession, folded: ChatFixtures.Folded) {
        sessions.value = listOf(session) + sessions.value.filter { it.id != session.id }
        projections.value = projections.value + (session.id to folded.projection)
        projectionTrees.value = projectionTrees.value + (session.id to folded.tree)
    }

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() = Unit
    override fun stop() = Unit
    override fun attach(sessionId: String) = Unit
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) = Unit
    override fun queueAdd(sessionId: String, text: String) = Unit
    override fun queueEdit(sessionId: String, queueId: String, text: String) = Unit
    override fun queueRemove(sessionId: String, queueId: String) = Unit
    override fun interrupt(sessionId: String) = Unit
    override fun approval(sessionId: String, requestId: String, choiceId: String?, decision: String?) = Unit
    override fun answerQuestion(sessionId: String, requestId: String, answers: Map<String, String>, response: String?) = Unit
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

fun chatSession(id: String, historyId: String?, name: String = id) = AgentSession(
    id = id, provider = "claude", name = name, cwd = "/w", status = "ready",
    startedAt = 1, updatedAt = 1, historyId = historyId,
)
