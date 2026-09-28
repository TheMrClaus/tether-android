package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.NodeSummary
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the protocol/data layer and the Compose UI.
 * The protocol worker implements this (RealTetherClient); the UI consumes it.
 * Session methods are fire-and-forget: results surface through the flows
 * (session/projection updates, error toasts) exactly like the web client. The
 * v109 node requests also surface that way ([nodes], [nodeResult]) and in
 * addition return their own correlated outcome.
 */
interface TetherClient {

    val connection: StateFlow<ConnectionState>

    /** Session rows from ready + session broadcasts, keyed newest-first by updatedAt. */
    val sessions: StateFlow<List<AgentSession>>

    val providers: StateFlow<List<ProviderInfo>>

    /** Default folder reported by the server's ready frame (not a boundary). */
    val workspaceRoot: StateFlow<String?>

    /**
     * Folded projections for every attached session, keyed by tetherSessionId: the legacy
     * typed view, adapted (memoized per turn/block) from [projectionTrees].
     */
    val projections: StateFlow<Map<String, SessionProjection>>

    /**
     * T2.1D: the v128 projection trees themselves — the source of truth the client folds live
     * events onto — keyed by tetherSessionId. T5/T6 screens read them through the
     * `protocol.model` views (SessionView / TurnView / BlockView).
     */
    val projectionTrees: StateFlow<Map<String, JsObj>>

    /** Discovered resumable conversations for the current workspace. */
    val histories: StateFlow<List<HistorySession>>

    /** Latest directory listing reply (folder picker). */
    val directories: StateFlow<DirectoryListing?>

    /** Latest session-controls reply per session (models + slash commands for the composer). */
    val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>>

    /** Uncorrelated server error frames + client-side failures — show as toasts. */
    val errors: SharedFlow<String>

    /**
     * Validate + persist server config from the first-launch screen:
     * probes /healthz (the native compatibility window), performs the password login, stores
     * the base URL and session cookie, then starts the connection loop.
     *
     * [username] is sent as the server's login form does (`{username, password}`);
     * a server without a configured username ignores it (see [signInRequirements]).
     */
    suspend fun login(baseUrl: String, password: String, username: String = ""): LoginResult

    /**
     * The unauthenticated sign-in probe (`GET /api/auth/session`, no credential
     * sent): what the login screen needs to choose its fields, like the web's
     * use-login-flow. Null when the server cannot be asked (bad URL, unreachable,
     * local network blocked): the screen then offers the password path only.
     */
    suspend fun signInRequirements(baseUrl: String): SignInRequirements? = null

    /**
     * Sign out (user action). Cookie session: `POST /api/auth/logout`, which
     * REVOKES the session server-side for every holder of the cookie. Device
     * token: local forget only — the server refuses device-management routes to
     * device tokens by design, so a paired device cannot revoke itself; the owner
     * revokes it from a browser (Settings → Paired devices).
     *
     * Either way the local credential is cleared FIRST (a network failure or a
     * process death mid-call still leaves the phone signed out), the socket is
     * closed, reconnects stop, and the server URL is kept to prefill the login
     * screen.
     */
    suspend fun logout(): LogoutResult {
        stop()
        return LogoutResult.LocalOnly
    }

    /**
     * Why the client last landed in [ConnectionState.AuthRequired] when the
     * SERVER ended the sign-in (expired cookie, revoked session/device). Null
     * after a user logout, a fresh install, or a successful login/pair.
     */
    val signedOutReason: StateFlow<SignedOutReason?> get() = NO_SIGNED_OUT_REASON

    /** The persisted server URL — kept across expiry and logout (login-screen prefill). */
    val serverUrl: StateFlow<String?> get() = NO_SERVER_URL

    /**
     * True once [configured] and [serverUrl] reflect the stored settings. Before the store is
     * first read they say "signed out, no server", which a cold-start deep link (T4.4) must not
     * mistake for the truth.
     */
    val storedSettingsLoaded: StateFlow<Boolean> get() = SETTINGS_LOADED

    /**
     * Sign-in security (`GET /api/auth/sessions`): the owner's browser/passkey
     * sessions. Owner-grade only — a device token gets
     * [SignInSessionsResult.OwnerGradeRequired] without a request being made
     * (the server would 403). The UI is T10.4; T1.4 ships the call + model.
     */
    suspend fun listSignInSessions(): SignInSessionsResult = SignInSessionsResult.Failed("Not supported.")

    /** `DELETE /api/auth/sessions/<id>`: sign one session out. */
    suspend fun revokeSignInSession(id: String): SignInSessionsResult = SignInSessionsResult.Failed("Not supported.")

    /** `DELETE /api/auth/sessions`: sign out everywhere except this session. */
    suspend fun revokeOtherSignInSessions(): SignInSessionsResult = SignInSessionsResult.Failed("Not supported.")

    /**
     * The SSO-friendly alternative to [login]: exchange the 8-character pairing
     * code the owner minted in a desktop browser ("Pair a device") for a
     * long-lived device bearer token, persist it, then start the connection loop.
     *
     * [code] is passed to the server with nothing but whitespace trimming — the
     * server owns pairing-code normalisation (case, spaces, hyphens, dots,
     * underscores, U→V), and a second implementation here could only disagree
     * with it. [label] names the device in the browser's device list (e.g. the
     * handset model); the server normalises and caps it.
     */
    suspend fun pair(baseUrl: String, code: String, label: String): PairResult

    /** True once a server URL + a credential are persisted (skip the setup screen). */
    val configured: StateFlow<Boolean>

    /** Open (or re-open) the connection loop. Idempotent. */
    fun start()

    /** Tear down permanently (logout / settings change). */
    fun stop()

    fun attach(sessionId: String)

    /**
     * Durable send with a client-minted idempotencyKey (at-most-once, see
     * specs/protocol-spec.md §5.6). Attachments (v15) ride an idle send only
     * and are held in memory, never persisted to disk.
     */
    fun send(sessionId: String, text: String, attachments: List<Attachment> = emptyList())

    fun queueAdd(sessionId: String, text: String)
    fun queueEdit(sessionId: String, queueId: String, text: String)
    fun queueRemove(sessionId: String, queueId: String)

    fun interrupt(sessionId: String)

    /**
     * T6.3: the operator's decision on a pending approval. Call it ONLY from a UI tap (I2: nothing
     * received may ever produce one). Exactly one of [choiceId] or [decision] ("allow"|"deny");
     * [grantedPermissions] only with a permission-granting [choiceId].
     *
     * Sent at most once per (session, request) in this process, only on a live connection, only
     * while the request is pending in the session's current state, and only with a choice the
     * request offered ([ConsentGuard]). Anything else is refused: nothing is transmitted or held.
     */
    fun approval(
        sessionId: String,
        requestId: String,
        choiceId: String? = null,
        decision: String? = null,
        grantedPermissions: GrantedPermissions? = null,
    ): ConsentResult

    /**
     * T6.3: the operator's answer to a pending question, under the same rules as [approval].
     * [answers] maps the EXACT question text to the chosen option label(s) (comma-joined for
     * multi-select, the "Other" text appended); [response] is the joined free text.
     */
    fun answerQuestion(sessionId: String, requestId: String, answers: Map<String, String>, response: String? = null): ConsentResult

    /**
     * T6.3: sessions whose projection a snapshot has confirmed on the CURRENT connection
     * (SYNC_DESIGN §4.1 "Live"). Outside it a session is shown from a saved copy or is catching
     * up: its approval and question cards render but are not actionable.
     */
    val liveSessions: StateFlow<Set<String>>

    /** T6.3: [consentKey]s of the requests this process already decided on the current server. */
    val decidedRequests: StateFlow<Set<String>>

    fun createSession(provider: String, cwd: String? = null, name: String? = null)
    fun resumeHistory(historyId: String, cwd: String)
    fun discover(cwd: String)
    fun browse(cwd: String? = null)

    fun setMode(sessionId: String, permissionMode: String)

    /**
     * Claude only: switch the session's model ("" or "default" resets to the CLI default).
     * Returns false when the frame could not be sent — callers must not confirm then.
     */
    fun setModel(sessionId: String, model: String): Boolean

    /** Ask for the session's available models + slash-command list. Cheap + idempotent. */
    fun requestSessionControls(sessionId: String)
    fun pin(sessionId: String, pinned: Boolean)
    fun rename(sessionId: String, name: String)
    fun archive(sessionId: String)
    fun kill(sessionId: String)

    /**
     * Called by network observers (and the local-network grant) to reconnect now
     * if idle. With a socket that reads open it sends a liveness `ping` instead,
     * because an open socket cannot be trusted after a network change (the web's
     * reconnectIfIdle). A no-op in a terminal state.
     */
    fun reconnectIfIdle()

    /**
     * Process lifecycle (ProcessLifecycleOwner ON_START / ON_STOP), the native
     * twin of the web's `visibilitychange`. Foreground: re-check the link at once
     * (ping an open socket, reconnect a dead one). Background: after
     * [ConnectionTimings.BACKGROUND_GRACE_MS] the socket is closed and reconnects
     * stop until the next foreground; FCM covers the background.
     */
    fun setAppForeground(foreground: Boolean)

    /**
     * User action out of a terminal [ConnectionState.VersionMismatch] (the
     * banner's retry): clear the halt and connect now. Nothing automatic ever
     * leaves that state, so a server that stays incompatible cannot loop.
     */
    fun retryConnection()

    /**
     * v115 bounded snapshots: per session, the turn index below which the last
     * snapshot stripped `blocksById` (fetch them with `fetch-turns`, T6.1).
     * Absent = nothing trimmed.
     */
    val trimmedBefore: StateFlow<Map<String, Int>>

    /**
     * v115 `fetch-turns` (use-tether.ts:1528): ask for the full projections of turns
     * [fromIndex] until [toIndex] (0-based, exclusive end) of a bounded snapshot; the server's
     * `turns-detail` replaces the trimmed stubs (T6.1's "Load N earlier turns"). No-op offline.
     * The default does nothing: only a client that receives bounded snapshots needs it.
     */
    fun fetchTurns(sessionId: String, fromIndex: Int, toIndex: Int) {}

    /**
     * T11.1: the workspace file browser's `/api/files` routes on the paired server, with the
     * credential in force and never following a redirect (see [WorkspaceFiles]). The default
     * refuses every call without touching the network.
     */
    val files: WorkspaceFiles get() = WorkspaceFiles.Unavailable

    /**
     * T6.2: `/api/tool-media/<sha256>.<ext>` (v94 tool results, v112 attachments, v122 spawned
     * runs) on the paired server, with the credential in force and never following a redirect
     * (see [HttpToolMedia]). The default refuses every call without touching the network.
     */
    val toolMedia: ToolMediaSource get() = ToolMediaSource.Unavailable

    /**
     * T6.2 (#159 #2, v110): the per-file hunks the `git-diff-file` replies carried, per session then
     * per path (use-tether.ts `fileDiffs`). A fresh `worktree-diff` summary for a session drops that
     * session's cached hunks, so an expanded file refetches. Emptied with the other server views.
     */
    val gitFileDiffs: StateFlow<Map<String, Map<String, ServerMessage.GitDiffFile>>> get() = NO_GIT_FILE_DIFFS

    /** T6.2: the latest `worktree-diff` summary per session (raw `WorktreeDiffSummary | null`). */
    val worktreeDiffs: StateFlow<Map<String, kotlinx.serialization.json.JsonObject?>> get() = NO_WORKTREE_DIFFS

    /** `git-diff-file` (use-tether.ts:1478): ask for one path's hunks. False when not sent. */
    fun requestGitFileDiff(sessionId: String, path: String): Boolean = false

    /** `worktree-diff`: ask for the session's diff summary (the host panel is T8.3's). False when not sent. */
    fun requestWorktreeDiff(sessionId: String): Boolean = false

    // ------------------------------------------------------------------
    // v109 multi-host node registry (Settings -> Nodes, UI in T10.3). See NodeRegistry.kt.
    // ------------------------------------------------------------------

    /**
     * The console's peer nodes, replaced wholesale by every `nodes` frame (sent
     * after each accepted `hello` and broadcast after any node-add/remove/probe
     * from any client). Public identity + last probe result only: never a
     * bearer. Kept across a reconnect until the new connection's list arrives,
     * as on the web; emptied on logout, on a server-side sign-out and on a new
     * sign-in (another server's registry must never show).
     */
    val nodes: StateFlow<List<NodeSummary>> get() = NO_NODES

    /** The last `node-result`, whichever request it answers (the web's `nodeResult`). Null until one arrives. */
    val nodeResult: StateFlow<NodeActionResult?> get() = NO_NODE_RESULT

    /**
     * `node-add`: register a peer from its credential bundle. [label] and
     * [baseUrl] are trimmed and omitted when blank (the web form + hook);
     * [baseUrl] overrides the bundle's own hint. The credential is sent once and
     * never kept, logged or persisted (see [NodeCredential]).
     */
    suspend fun addNode(credential: NodeCredential, label: String? = null, baseUrl: String? = null): NodeRequestOutcome =
        NodeRequestOutcome.NotSent

    /** `node-remove`: forget a peer (the server also deletes its stored bearer). */
    suspend fun removeNode(nodeId: String): NodeRequestOutcome = NodeRequestOutcome.NotSent

    /** `node-probe`: re-check a peer now; its new status arrives in [nodes]. */
    suspend fun probeNode(nodeId: String): NodeRequestOutcome = NodeRequestOutcome.NotSent

    // ------------------------------------------------------------------
    // v7 in-UI operational event log + /api/stats (the Health & Event Log dialog, T4.5).
    // ------------------------------------------------------------------

    /**
     * The server's operational records (`log` frames) folded by [EventLog]: oldest first, deduped
     * by seq, emptied on a server restart (new bootId), capped at 500. Kept across a reconnect
     * (the replayed tail dedupes), emptied with the other per-server views on sign-out / sign-in.
     */
    val eventLog: StateFlow<EventLog> get() = NO_EVENT_LOG

    /** GET /api/stats, the dialog's operational snapshot (fetched on every open and on Refresh). */
    suspend fun fetchStats(): StatsResult = StatsResult.Failed(STATS_FALLBACK_ERROR)

    // ------------------------------------------------------------------
    // T5.1 sidebar sync: v93 watched workspaces, v63 seen, v67 order, v128 pinned workspaces.
    // Defaults keep other implementations (test doubles) compiling; RealTetherClient and the
    // debug FakeTetherClient implement them.
    // ------------------------------------------------------------------

    /** Discovered conversations per workspace root, replaced per `histories` frame (use-tether.ts historiesByCwd). */
    val historiesByCwd: StateFlow<Map<String, List<HistorySession>>> get() = NO_HISTORIES_BY_CWD

    /** v67: the server's explicit per-workspace row order (`session-order`), keyed by cwd. */
    val sessionOrders: StateFlow<Map<String, List<String>>> get() = NO_SESSION_ORDERS

    /** v63: the newest `seen` broadcast per historyId (another device saw the conversation). */
    val remoteSeen: StateFlow<Map<String, Long>> get() = NO_REMOTE_SEEN

    /** v50: the last `server-settings` frame (null until one arrives on this server). */
    val serverSettings: StateFlow<ServerMessage.ServerSettings?> get() = NO_SERVER_SETTINGS

    /**
     * v93 `discover {cwd, lastSeen, watch}`: [watch] is the COMPLETE set of sidebar workspaces
     * (it replaces the server's per-socket subscription); [lastSeen] merges into the server's
     * global seen store. Returns false when the frame could not be sent.
     */
    fun discoverWorkspace(cwd: String, lastSeen: Map<String, Long>, watch: List<String>): Boolean = false

    /** v63 `mark-seen`. Fire-and-forget, like the web (a lost one self-heals on the next discover). */
    fun markSeen(historyId: String, seenAt: Long): Boolean = false

    /**
     * v67 `set-session-order` (empty [order] resets to recency). Applied to [sessionOrders]
     * optimistically when the frame was sent (use-tether.ts setSessionOrder); returns whether it was.
     */
    fun setSessionOrder(cwd: String, order: List<String>): Boolean = false

    /** v50 `server-settings` request. */
    fun requestServerSettings(): Boolean = false

    /** v128 `set-server-settings {pinnedWorkspaces}`: the owner-level kept sidebar workspaces. */
    fun setPinnedWorkspaces(pinned: List<String>): Boolean = false

    // ------------------------------------------------------------------
    // T5.2 resume: defaults keep other implementations (test doubles) compiling.
    // ------------------------------------------------------------------

    /**
     * v89 `resume {historyId, cwd, profileId?}` for a discovered conversation (use-tether.ts
     * resumeHistory: the profile rides along only when the history names one). Returns whether
     * the frame was sent; the web's reopen applies its opening state only then.
     */
    fun resume(history: HistorySession): Boolean {
        resumeHistory(history.historyId, history.cwd)
        return true
    }

    /**
     * The server's unicast `created` reply to THIS socket's own create/resume (use-tether.ts
     * createdSession): the session the operator just deliberately opened. [CreatedReply.seq] is
     * monotonic, so a resume dedup-hit returning the same session twice is still a fresh reply.
     */
    val createdSessions: StateFlow<CreatedReply?> get() = NO_CREATED

    // ------------------------------------------------------------------
    // T5.3 search (SearchSync.kt): defaults keep other implementations (test doubles) compiling.
    // ------------------------------------------------------------------

    /** The sidebar's workspace content search: the last `search-results` (use-tether.ts searchResults). */
    val searchResults: StateFlow<SearchResults> get() = NO_SEARCH_RESULTS

    /**
     * use-tether.ts `search(cwd, query)`: `search {cwd, query}`, or a local clear for a query
     * shorter than two characters. Returns whether a frame went out.
     */
    fun search(cwd: String, query: String): Boolean = false

    /** use-tether.ts selectWorkspace: the previous workspace's content hits are dropped. */
    fun clearSearchResults() {}

    /** The cross-harness global search (use-tether.ts globalSearchResults). */
    val globalSearchResults: StateFlow<GlobalSearchResults> get() = NO_GLOBAL_SEARCH_RESULTS

    /**
     * use-tether.ts runGlobalSearch: `global-search` under a new monotonic request id (a reply to
     * an older one is dropped), or a clear for a too-short query. Returns whether a frame went out.
     */
    fun runGlobalSearch(params: GlobalSearchParams): Boolean = false

    /** use-tether.ts clearGlobalSearch: empty the results and invalidate any in-flight reply. */
    fun clearGlobalSearch() {}
}

private val NO_SEARCH_RESULTS: StateFlow<SearchResults> = MutableStateFlow(SearchResults())
private val NO_GLOBAL_SEARCH_RESULTS: StateFlow<GlobalSearchResults> = MutableStateFlow(GlobalSearchResults())

/** One `created` reply (use-tether.ts:291 `{session, seq, requestId?}`). */
data class CreatedReply(val session: AgentSession, val seq: Long, val requestId: String? = null)

private val NO_CREATED: StateFlow<CreatedReply?> = MutableStateFlow(null)

private val NO_EVENT_LOG: StateFlow<EventLog> = MutableStateFlow(EventLog())
private val NO_HISTORIES_BY_CWD: StateFlow<Map<String, List<HistorySession>>> = MutableStateFlow(emptyMap())
private val NO_SESSION_ORDERS: StateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
private val NO_REMOTE_SEEN: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
private val NO_SERVER_SETTINGS: StateFlow<ServerMessage.ServerSettings?> = MutableStateFlow(null)

private val NO_NODES: StateFlow<List<NodeSummary>> = MutableStateFlow(emptyList())
private val NO_NODE_RESULT: StateFlow<NodeActionResult?> = MutableStateFlow(null)
private val NO_GIT_FILE_DIFFS: StateFlow<Map<String, Map<String, ServerMessage.GitDiffFile>>> = MutableStateFlow(emptyMap())
private val NO_WORKTREE_DIFFS: StateFlow<Map<String, kotlinx.serialization.json.JsonObject?>> = MutableStateFlow(emptyMap())

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState

    /** No valid credentials — surface the login/setup screen. */
    data object AuthRequired : ConnectionState

    /**
     * The server is outside this app's native compatibility window (D5):
     * [Incompatibility.reason] says which side must update. Terminal until user
     * action ([TetherClient.retryConnection]) or an app restart: no automatic
     * reconnect, because every retry would fail the same way.
     */
    data class VersionMismatch(val incompatibility: Incompatibility) : ConnectionState

    /**
     * The server is on the local network and the OS blocks that traffic until the
     * user grants local-network access (Android 17+, see [LocalNetworkAccess]).
     * No automatic reconnect is scheduled, because every retry would fail the same
     * way. [TetherClient.reconnectIfIdle] (after the grant, on resume, on network
     * change) re-evaluates.
     */
    data object LocalNetworkBlocked : ConnectionState
}

private val NO_SIGNED_OUT_REASON: StateFlow<SignedOutReason?> = MutableStateFlow(null)
private val NO_SERVER_URL: StateFlow<String?> = MutableStateFlow(null)
private val SETTINGS_LOADED: StateFlow<Boolean> = MutableStateFlow(true)

/** Why the server ended the sign-in; the login screen explains it. */
enum class SignedOutReason {
    /** The cookie session expired or was revoked (auth probe said `authenticated:false`, or close 4002). */
    SessionExpired,

    /** The device token is no longer accepted (close 4001, or the probe refused it). */
    DeviceUnpaired,

    /**
     * Something in front of the server refused the credential probe (HTTP 401/403
     * or a redirect on `/api/auth/session` — a sign-in gateway). The credential is
     * kept: it may be fine once the gateway lets the probe through.
     */
    GatewayRefused,
}

/** The unauthenticated `/api/auth/session` reading (see [TetherClient.signInRequirements]). */
data class SignInRequirements(
    val usernameRequired: Boolean,
    val passwordLoginEnabled: Boolean,
    val passkeyCount: Int,
    val passkeysUsable: Boolean,
)

sealed interface LogoutResult {
    /** Cookie session revoked on the server and forgotten locally. */
    data object Revoked : LogoutResult

    /** Device token (or nothing) — forgotten locally; only a browser can revoke a device. */
    data object LocalOnly : LogoutResult

    /**
     * Forgotten locally, but the server could not be told: the cookie session
     * stays valid server-side until it expires or is signed out from a browser.
     */
    data object ServerNotReached : LogoutResult
}

/** One owner sign-in session, as `GET /api/auth/sessions` lists it (hooks/use-sign-in-security.ts). */
data class SignInSession(
    val id: String,
    /** "password" | "passkey" | "service" (anything else reads as "password", as on the web). */
    val method: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val expiresAt: Long,
    val userAgent: String,
    val current: Boolean,
)

sealed interface SignInSessionsResult {
    data class Sessions(val sessions: List<SignInSession>) : SignInSessionsResult

    /** Revoke done; [count] is how many sessions the server signed out (1 for a single revoke). */
    data class Revoked(val count: Int) : SignInSessionsResult

    /** A paired device (device token) cannot manage sign-in sessions — by server design. */
    data object OwnerGradeRequired : SignInSessionsResult

    data object NotSignedIn : SignInSessionsResult

    data object NotFound : SignInSessionsResult

    data class Failed(val message: String) : SignInSessionsResult
}

sealed interface LoginResult {
    data object Success : LoginResult

    /** Tether's own 401 (`{error}` from /api/auth/login): the username or password did not match. */
    data class BadPassword(val message: String) : LoginResult

    /**
     * A 401/403 that did not come from Tether's login handler: a `WWW-Authenticate` challenge
     * or a body without Tether's `{error}` (a proxy, basic auth, an SSO gateway in front of it;
     * Tether's README has SSO setups keep /api/auth/login gated). The password was never
     * checked. [scheme] is the challenge's auth scheme ("Basic"), [server] the `Server`
     * header's first product ("nginx/1.27.1"), each only when present and a plain token.
     */
    data class GatewayRefused(val status: Int, val scheme: String?, val server: String? = null) : LoginResult
    data class RateLimited(val message: String) : LoginResult

    /** 403 `password_login_disabled`: this console accepts passkeys only (passkeys → T10.5). */
    data class PasswordDisabled(val message: String) : LoginResult
    /** /healthz shows the server is outside the native window (see [Compatibility]). */
    data class VersionMismatch(val incompatibility: Incompatibility) : LoginResult
    data class Unreachable(val message: String) : LoginResult

    /** See [ConnectionState.LocalNetworkBlocked]: ask for local-network access, then retry. */
    data object LocalNetworkBlocked : LoginResult
}

/** Outcome of [TetherClient.pair]. Sibling of [LoginResult]; see specs/protocol-spec.md §1.3. */
sealed interface PairResult {
    data object Success : PairResult

    /** 401: unknown, expired or already-claimed code — the server does not say which. */
    data class Rejected(val message: String) : PairResult

    /** 429: the claim endpoint's own (tighter than login) rate limit. */
    data class RateLimited(val message: String) : PairResult

    /** /healthz did not report `pairing: true` — this server predates device pairing. */
    data class NotSupported(val message: String) : PairResult

    data class VersionMismatch(val incompatibility: Incompatibility) : PairResult
    data class Unreachable(val message: String) : PairResult

    /** See [ConnectionState.LocalNetworkBlocked]: ask for local-network access, then retry. */
    data object LocalNetworkBlocked : PairResult
}
