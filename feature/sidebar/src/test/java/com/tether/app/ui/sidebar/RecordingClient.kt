package com.tether.app.ui.sidebar

import com.tether.app.client.ConnectionState
import com.tether.app.client.CreatedReply
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A [TetherClient] that records every frame the sidebar makes it send, as the wire JSON the real
 * client would put on the socket (the ClientMessage encodings SidebarSyncTest pins to the corpus).
 */
class RecordingClient(
    sessions: List<AgentSession> = emptyList(),
    connected: Boolean = true,
) : TetherClient {
    val frames = mutableListOf<JsonObject>()
    fun types(): List<String> = frames.map { (it["type"] as JsonPrimitive).content }
    private fun record(message: ClientMessage) {
        frames += TetherJson.parseToJsonElement(message.encode()) as JsonObject
    }

    override val connection = MutableStateFlow<ConnectionState>(if (connected) ConnectionState.Connected else ConnectionState.Disconnected)
    override val sessions = MutableStateFlow(sessions)
    override val providers: StateFlow<List<ProviderInfo>> = MutableStateFlow(emptyList())
    override val workspaceRoot = MutableStateFlow<String?>(SidebarFixtures.ROOT)
    override val projections: StateFlow<Map<String, SessionProjection>> = MutableStateFlow(emptyMap())
    override val projectionTrees: StateFlow<Map<String, JsObj>> = MutableStateFlow(emptyMap())
    override val histories: StateFlow<List<HistorySession>> = MutableStateFlow(emptyList())
    override val directories: StateFlow<DirectoryListing?> = MutableStateFlow(null)
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = MutableStateFlow(emptyMap())
    override val errors: SharedFlow<String> = MutableSharedFlow()
    override val configured: StateFlow<Boolean> = MutableStateFlow(true)
    override val trimmedBefore: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    override val historiesByCwd = MutableStateFlow<Map<String, List<HistorySession>>>(emptyMap())
    override val sessionOrders = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    override val remoteSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val serverSettings = MutableStateFlow<ServerMessage.ServerSettings?>(null)

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = error("unused")
    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = error("unused")
    override fun start() = Unit
    override fun stop() = Unit
    override fun attach(sessionId: String) = record(ClientMessage.Attach(sessionId, null))
    override fun send(sessionId: String, text: String, attachments: List<Attachment>) = Unit
    override fun queueAdd(sessionId: String, text: String) = Unit
    override fun queueEdit(sessionId: String, queueId: String, text: String) = Unit
    override fun queueRemove(sessionId: String, queueId: String) = Unit
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
    override val liveSessions: kotlinx.coroutines.flow.StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    /** T13.2 r3: keeps the interface's empty syncStates, so it reports no freshness. */
    override val reportsFreshness: Boolean = false
    override val decidedRequests: kotlinx.coroutines.flow.StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    override fun createSession(provider: String, cwd: String?, name: String?) = record(ClientMessage.Create(provider = provider, cwd = cwd, name = name))
    override fun resumeHistory(historyId: String, cwd: String) = record(ClientMessage.Resume(historyId, cwd))
    override fun discover(cwd: String) = record(ClientMessage.Discover(cwd))
    override fun browse(cwd: String?) = record(ClientMessage.Browse(cwd))
    override fun requestSessionControls(sessionId: String) = Unit
    override fun pin(sessionId: String, pinned: Boolean) = record(ClientMessage.Pin(sessionId, pinned))
    override fun rename(sessionId: String, name: String) = record(ClientMessage.Rename(sessionId, name))
    override fun archive(sessionId: String) = record(ClientMessage.Archive(sessionId))
    override fun kill(sessionId: String, expectedOrigin: String?, requireLive: Boolean) {
        killScopes += requireLive
        killOrigins += expectedOrigin
        record(ClientMessage.Kill(sessionId))
    }

    /** T13.2 r2: [kill]'s requireLive, per call (a sidebar row passes false). */
    val killScopes = mutableListOf<Boolean>()
    /** T13.2 r3: [kill]'s expectedOrigin, per call (the origin the row was armed for). */
    val killOrigins = mutableListOf<String?>()
    override fun reconnectIfIdle() = Unit
    override fun setAppForeground(foreground: Boolean) = Unit
    override fun retryConnection() = Unit

    override fun discoverWorkspace(cwd: String, lastSeen: Map<String, Long>, watch: List<String>): Boolean {
        record(ClientMessage.Discover(cwd, lastSeen = lastSeen, watch = watch))
        return true
    }

    override fun markSeen(historyId: String, seenAt: Long): Boolean {
        record(ClientMessage.MarkSeen(historyId, seenAt))
        return true
    }

    override fun setSessionOrder(cwd: String, order: List<String>): Boolean {
        record(ClientMessage.SetSessionOrder(cwd, order))
        sessionOrders.value = sessionOrders.value + (cwd to order)
        return true
    }

    override fun requestServerSettings(): Boolean {
        record(ClientMessage.ServerSettingsRequest)
        return true
    }

    /** T5.2: false models a socket that refused the frame (nothing recorded). */
    var resumeSends = true

    override fun resume(history: HistorySession): Boolean {
        if (!resumeSends) return false
        record(ClientMessage.Resume(history.historyId, history.cwd, history.profileId?.takeIf { it.isNotEmpty() }))
        return true
    }

    override val createdSessions = MutableStateFlow<CreatedReply?>(null)

    /** The server's unicast `created` reply: the session joins the list, then the reply lands (RealTetherClient order). */
    fun created(session: AgentSession) {
        sessions.value = listOf(session) + sessions.value.filter { it.id != session.id }
        createdSessions.value = CreatedReply(session, (createdSessions.value?.seq ?: 0L) + 1)
    }

    // T5.3 search: the frames are recorded; replies are pushed by the test (request ids as use-tether.ts).
    override val searchResults = MutableStateFlow(com.tether.app.client.SearchResults())
    override val globalSearchResults = MutableStateFlow(com.tether.app.client.GlobalSearchResults())
    private var globalSearchId = 0L

    override fun search(cwd: String, query: String): Boolean {
        if (query.trim().length < 2) {
            searchResults.value = com.tether.app.client.SearchResults()
            return false
        }
        record(ClientMessage.Search(cwd, query))
        return true
    }

    override fun clearSearchResults() {
        searchResults.value = com.tether.app.client.SearchResults()
    }

    override fun runGlobalSearch(params: com.tether.app.client.GlobalSearchParams): Boolean {
        val query = params.query.trim()
        if (query.length < 2) {
            clearGlobalSearch()
            return false
        }
        val id = ++globalSearchId
        globalSearchResults.value = globalSearchResults.value.copy(requestId = id, query = query, pending = true)
        record(ClientMessage.GlobalSearch(id, query, params.providers?.takeIf { it.isNotEmpty() }, params.since, params.until, params.cwd?.takeIf { it.isNotEmpty() }))
        return true
    }

    override fun clearGlobalSearch() {
        globalSearchId += 1
        globalSearchResults.value = com.tether.app.client.GlobalSearchResults(requestId = globalSearchId)
    }

    /** The server's `global-search-results`; a reply to a superseded request is dropped. */
    fun globalReply(requestId: Long, query: String, hits: List<com.tether.app.protocol.SearchHit>) {
        if (requestId == globalSearchId) globalSearchResults.value = com.tether.app.client.GlobalSearchResults(requestId, query, hits, pending = false)
    }

    override fun setPinnedWorkspaces(pinned: List<String>): Boolean {
        record(ClientMessage.SetServerSettings(buildJsonObject { put("pinnedWorkspaces", JsonArray(pinned.map(::JsonPrimitive))) }))
        return true
    }
}

fun frame(json: String): JsonObject = TetherJson.parseToJsonElement(json) as JsonObject
