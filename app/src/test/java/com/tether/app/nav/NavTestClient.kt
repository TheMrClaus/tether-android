package com.tether.app.nav

import com.tether.app.client.ConnectionState
import com.tether.app.client.LoginResult
import com.tether.app.client.LogoutResult
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.fake.FakeTetherClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The preview client with the sign-in state under the test's control, recording every attach
 * (a selection's side effect) and every call that would change state on the server or sign in.
 * A link may cause attaches; it may never cause a [stateChanges] entry.
 */
class NavTestClient(
    configured: Boolean = true,
    connection: ConnectionState = ConnectionState.Connected,
    serverUrl: String? = PAIRED,
    loaded: Boolean = true,
    private val inner: FakeTetherClient = FakeTetherClient(),
) : TetherClient by inner {
    val configuredFlow = MutableStateFlow(configured)
    val connectionFlow = MutableStateFlow(connection)
    val serverUrlFlow = MutableStateFlow(serverUrl)
    val loadedFlow = MutableStateFlow(loaded)
    val sessionsFlow = MutableStateFlow(inner.sessions.value)

    override val configured: StateFlow<Boolean> = configuredFlow
    override val connection: StateFlow<ConnectionState> = connectionFlow
    override val serverUrl: StateFlow<String?> = serverUrlFlow
    override val storedSettingsLoaded: StateFlow<Boolean> = loadedFlow
    override val sessions: StateFlow<List<AgentSession>> = sessionsFlow

    val attached = CopyOnWriteArrayList<String>()
    val stateChanges = CopyOnWriteArrayList<String>()

    override fun start() {}
    override fun attach(sessionId: String) { attached += sessionId }

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult {
        stateChanges += "login"
        return LoginResult.Unreachable("test")
    }
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult {
        stateChanges += "pair"
        return PairResult.Unreachable("test")
    }
    override suspend fun logout(): LogoutResult { stateChanges += "logout"; return LogoutResult.LocalOnly }
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) { stateChanges += "send" }
    override fun queueAdd(sessionId: String, text: String) { stateChanges += "queueAdd" }
    override fun queueEdit(sessionId: String, queueId: String, text: String) { stateChanges += "queueEdit" }
    override fun queueRemove(sessionId: String, queueId: String) { stateChanges += "queueRemove" }
    override fun interrupt(sessionId: String) { stateChanges += "interrupt" }
    override fun approval(sessionId: String, requestId: String, choiceId: String?, decision: String?) { stateChanges += "approval" }
    override fun answerQuestion(sessionId: String, requestId: String, answers: Map<String, String>, response: String?) {
        stateChanges += "answerQuestion"
    }
    override fun createSession(provider: String, cwd: String?, name: String?) { stateChanges += "createSession" }
    override fun resumeHistory(historyId: String, cwd: String) { stateChanges += "resumeHistory" }
    override fun setMode(sessionId: String, permissionMode: String) { stateChanges += "setMode" }
    override fun setModel(sessionId: String, model: String): Boolean { stateChanges += "setModel"; return true }
    override fun pin(sessionId: String, pinned: Boolean) { stateChanges += "pin" }
    override fun rename(sessionId: String, name: String) { stateChanges += "rename" }
    override fun archive(sessionId: String) { stateChanges += "archive" }
    override fun kill(sessionId: String) { stateChanges += "kill" }
    override fun setSessionOrder(cwd: String, order: List<String>): Boolean { stateChanges += "setSessionOrder"; return true }
    override fun setPinnedWorkspaces(pinned: List<String>): Boolean { stateChanges += "setPinnedWorkspaces"; return true }

    /** Simulates a completed sign-in to [url]: stored server, credential, then the `ready` snapshot. */
    fun signIn(url: String) {
        serverUrlFlow.value = url
        configuredFlow.value = true
        connectionFlow.value = ConnectionState.Connecting
        connectionFlow.value = ConnectionState.Connected
    }

    companion object {
        const val PAIRED = "https://tether.example.com"

        /** Sessions FakeTetherClient lists. */
        const val LISTED = "s-approve"
        const val OTHER_LISTED = "s-active"
    }
}
