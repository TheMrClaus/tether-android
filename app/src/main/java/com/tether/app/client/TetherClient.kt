package com.tether.app.client

import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the protocol/data layer and the Compose UI.
 * The protocol worker implements this (RealTetherClient); the UI consumes it.
 * All methods are fire-and-forget: results surface through the flows
 * (session/projection updates, error toasts) exactly like the web client.
 */
interface TetherClient {

    val connection: StateFlow<ConnectionState>

    /** Session rows from ready + session broadcasts, keyed newest-first by updatedAt. */
    val sessions: StateFlow<List<AgentSession>>

    val providers: StateFlow<List<ProviderInfo>>

    /** Default folder reported by the server's ready frame (not a boundary). */
    val workspaceRoot: StateFlow<String?>

    /** Folded projections for every attached session, keyed by tetherSessionId. */
    val projections: StateFlow<Map<String, SessionProjection>>

    /** Discovered resumable conversations for the current workspace. */
    val histories: StateFlow<List<HistorySession>>

    /** Latest directory listing reply (folder picker). */
    val directories: StateFlow<DirectoryListing?>

    /** Uncorrelated server error frames + client-side failures — show as toasts. */
    val errors: SharedFlow<String>

    /** Open (or re-open) the connection loop. Idempotent. */
    fun start()

    /** Tear down permanently (logout / settings change). */
    fun stop()

    fun attach(sessionId: String)

    /** Durable send with a client-minted idempotencyKey (at-most-once, see specs/protocol-spec.md §5.6). */
    fun send(sessionId: String, text: String)

    fun queueAdd(sessionId: String, text: String)
    fun queueEdit(sessionId: String, queueId: String, text: String)
    fun queueRemove(sessionId: String, queueId: String)

    fun interrupt(sessionId: String)

    /** Exactly one of [choiceId] or [decision] ("allow"|"deny") must be non-null. */
    fun approval(sessionId: String, requestId: String, choiceId: String? = null, decision: String? = null)

    /**
     * [answers] maps the EXACT question text to the chosen option label
     * (comma-separated for multi-select); [response] is optional free text.
     */
    fun answerQuestion(sessionId: String, requestId: String, answers: Map<String, String>, response: String? = null)

    fun createSession(provider: String, cwd: String? = null, name: String? = null)
    fun resumeHistory(historyId: String, cwd: String)
    fun discover(cwd: String)
    fun browse(cwd: String? = null)

    fun setMode(sessionId: String, permissionMode: String)
    fun pin(sessionId: String, pinned: Boolean)
    fun rename(sessionId: String, name: String)
    fun archive(sessionId: String)
    fun kill(sessionId: String)

    /** Called by lifecycle/network observers to trigger an immediate reconnect if idle. */
    fun reconnectIfIdle()
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState

    /** No valid credentials — surface the login/setup screen. */
    data object AuthRequired : ConnectionState

    /** Server speaks a different PROTOCOL_VERSION; reconnect is disabled. */
    data class VersionMismatch(val requiredVersion: Int) : ConnectionState
}
