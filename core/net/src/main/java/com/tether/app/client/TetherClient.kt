package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.NodeSummary
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewSubscription
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

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
     * v135 (issue #227 part 2): `ready.hiddenAgentSessionCount` — how many agent-created /
     * derived sessions the server kept out of [sessions]. Display only; null until a ready
     * carries it (a pre-v135 server never does) and cleared with the server's other views.
     */
    val hiddenAgentSessionCount: StateFlow<Int?> get() = NO_HIDDEN_AGENT_SESSION_COUNT

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

    /** The client's own failures and notices, in its own words — show as toasts. */
    val errors: SharedFlow<String>

    /**
     * T6.7: text a SERVER wrote that the web shows as its global error (`{type:"error"}` frames, a
     * failed `interrupt_result`), from the live socket only, already cleaned ([LabelText.error]: no
     * line breaks, bidi controls or invisible code points, bounded). Kept apart from [errors] so it
     * is shown attributed to the server and can never pass for the app's own words. r2: each carries
     * the server origin of the socket it came in on (checked current in the same step it was
     * emitted), so a view can drop it once that server is no longer the one it shows.
     */
    val serverErrors: SharedFlow<ServerErrorText> get() = NoServerErrors

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
     * mistake for the truth. A store that fails to read also sets it, with [configured] false
     * (ta-exi: fail closed, and nothing waits on it forever).
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
     * specs/protocol-spec.md §5.6). T7.4: text only. A message with attachments goes through
     * [sendAttachments] alone; a non-empty [attachments] here is refused (nothing is recorded or
     * sent), so no attachment is ever filed in the durable outbox, redelivered or queued.
     */
    fun send(sessionId: String, text: String, attachments: List<Attachment> = emptyList())

    /**
     * T7.4: the one path a message WITH attachments takes to the wire (v15 `send.attachments`, and
     * an optional v103 [mention]). Call it ONLY from an explicit Send (a tap or the submit key), never
     * in answer to anything received. Under the lock, in order: a live, handshaken socket of a running
     * client; the composer drawn for THIS server ([expectedOrigin] = the socket's origin and the
     * outbox's); the session confirmed live on it; listed, and neither read-only, handed off nor
     * archived; idle (no active turn); nothing of this session still waiting in the outbox; the
     * mention, if any, offered by the current catalog; the encoded frame within
     * [AttachmentFrame.MAX_SEND_FRAME_BYTES] and within what the socket's queue can take now; then
     * enqueued on that socket, once, under a fresh idempotency key. It is never recorded in the
     * durable outbox, retried, queued or persisted: offline it is refused, and if the link drops
     * before the server confirms the turn the operator is told it may not have arrived.
     */
    fun sendAttachments(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
        mention: com.tether.app.protocol.DelegateMention?,
        expectedOrigin: String?,
    ): AttachmentSendResult = AttachmentSendResult.NotConnected

    fun queueAdd(sessionId: String, text: String)
    fun queueEdit(sessionId: String, queueId: String, text: String)
    fun queueRemove(sessionId: String, queueId: String)

    /**
     * T13.2 r2: INTERRUPT the session's current turn (`interrupt`). An operator control: call it ONLY
     * from a tap on the composer's Interrupt key or a queued row's "Interrupt now", never in answer to
     * anything received. Sent only on a live, handshaken socket of the server that drew the key
     * ([expectedOrigin], the [consentOrigin] the key was composed with), for a session confirmed live
     * on it ([liveSessions]) that is neither read-only nor handed off. Otherwise nothing is sent or
     * held: no retry, no queue.
     *
     * T6.7: bound to the turn the key was drawn for. The wire `interrupt` names no turn (the server
     * stops whichever turn is running when it arrives), so the client refuses
     * ([InterruptResult.NotCurrentTurn]) unless the session's current projection still has
     * [expectedTurnId] as its open active turn: it never stops a turn the client has already seen
     * replace it. It cannot know of a turn the server started that has not reached it yet (a queued
     * message flushed at the boundary): that window is the server's to close (ta-yw0); until then
     * "Interrupt now" stays locked while its turn is already being interrupted.
     */
    fun interrupt(sessionId: String, expectedOrigin: String?, expectedTurnId: String): InterruptResult = InterruptResult.NotConnected

    /**
     * T6.7 r3: per session, the turn whose interrupt the server reported `failed` (the turn stays
     * "cancelling" in the projection). While a turn is cancelling every interrupt control of its
     * session is locked; a failure recorded here for THAT turn unlocks them so the operator can retry.
     */
    val failedInterrupts: StateFlow<Map<String, String>> get() = NoFailedInterrupts

    /**
     * T6.3: the operator's decision on a pending approval. Call it ONLY from a UI tap (I2: nothing
     * received may ever produce one). Exactly one of [choiceId] or [decision] ("allow"|"deny");
     * [grantedPermissions] only with a permission-granting [choiceId]. [expectedFingerprint] is the
     * [ConsentGuard.fingerprint] of the request as the card rendered it.
     *
     * Sent at most once per (server, session, turn, request, fingerprint) in this process, only on a
     * live connection, only while that exact request is pending in the session's current state, and
     * only with a choice the request offered ([ConsentGuard]). Anything else is refused: nothing is
     * transmitted or held.
     */
    fun approval(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        choiceId: String? = null,
        decision: String? = null,
        grantedPermissions: GrantedPermissions? = null,
    ): ConsentResult

    /**
     * T6.3: the operator's answer to a pending question, under the same rules as [approval]. The
     * operator's picks are INDICES ([ConsentGuard.QuestionPick], per answer slot) plus their own
     * "Other" text, and [skipped] slots; the answer strings and `response` are built by
     * [ConsentGuard.buildAnswers] from the request itself (round 4, L2: nothing is parsed).
     */
    fun answerQuestion(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        picks: List<ConsentGuard.QuestionPick>,
        skipped: Set<Int> = emptySet(),
    ): ConsentResult

    /**
     * T6.3: sessions attached on the CURRENT connection whose projection a snapshot on it has
     * confirmed (SYNC_DESIGN §4.1 "Live"). Outside it a session is shown from a saved copy or is
     * catching up: its approval and question cards render but are not actionable.
     */
    val liveSessions: StateFlow<Set<String>>

    /**
     * T13.2 (SYNC_DESIGN §2.5, §4.1): per session, how current the shown copy is (Live /
     * CatchingUp / Saved / NotDownloaded), derived from the connection, attach and verify state.
     * An absent session means "nothing known": screens then show no freshness mark, and a client
     * that [reportsFreshness] never treats it as live ([LiveCopy.isLive]). The default knows nothing.
     */
    val syncStates: StateFlow<Map<String, SessionSync>> get() = NO_SYNC_STATES

    /**
     * T13.2: whether this client reports freshness at all. True for a client that derives its own
     * [syncStates] (a missing entry is then NOT live); false only for one that keeps the interface's
     * empty default, whose locks follow [liveSessions] alone (the T6.3 rule, [LiveCopy.isLive]).
     * r3: declared by every client, never inferred, so a new client cannot get it wrong by default.
     */
    val reportsFreshness: Boolean

    /** T6.3: the server origin of the live, handshaken socket (the fingerprints' origin); null when there is none. */
    val consentOrigin: StateFlow<String?>

    /** T6.3: [consentKey]s of the requests this process already decided on the current server. */
    val decidedRequests: StateFlow<Set<String>>

    /**
     * T6.3: the subset of [decidedRequests] sent on an EARLIER socket: the socket accepted the frame,
     * but the link dropped before the request was seen resolved. Shown as "delivery unconfirmed";
     * never re-sent.
     */
    val unconfirmedRequests: StateFlow<Set<String>> get() = NO_UNCONFIRMED

    /**
     * T6.4: STOP a running background `!` command (`stop-command`, v54; the server fully kills it).
     * An operator control: call it ONLY from a tap on the command's Stop key, never in answer to
     * anything received. Sent only on a live, handshaken socket, for a session confirmed live on it
     * that is neither read-only nor handed off (the server refuses those too), and only while the
     * session's CURRENT projection lists [commandId] as running, on the server that drew the key
     * ([expectedOrigin], the [consentOrigin] the row was composed with: a stop tapped on another
     * server's row is refused). Otherwise nothing is sent or held: no retry, no queue.
     */
    fun stopCommand(sessionId: String, commandId: String, expectedOrigin: String?): StopCommandResult = StopCommandResult.NotConnected

    /**
     * T7.3: RUN the composer's `!` command (`run-command`, v53/v54): in the foreground (the turn slot;
     * the agent watches and comments) or, [background], detached. An operator control: call it ONLY
     * from a tap on the command keys or an explicit composer submit, never in answer to anything
     * received. Sent only on a live, handshaken socket of the server that drew the composer
     * ([expectedOrigin]), for a session confirmed live on it that is neither read-only, handed off
     * nor archived, whose provider the server offers command mode for (`capabilities.commandRunner`),
     * with a non-empty command within the server's limit, and — in the foreground — only while no turn
     * runs. Each run carries a fresh idempotency key. Otherwise nothing is sent or held: no retry, no
     * queue ([CommandGuard]).
     */
    fun runCommand(sessionId: String, command: String, background: Boolean, expectedOrigin: String?): RunCommandResult = RunCommandResult.NotConnected

    /**
     * T7.3: move the running FOREGROUND command to the background (`background-command`, v54; the
     * web's Background key and Ctrl+B). An operator control under [runCommand]'s link rules, bound to
     * the turn the key was drawn for: sent only while [expectedTurnId] is still the session's open
     * foreground command turn in its current projection ([CommandGuard.foregroundCommandTurn]).
     */
    fun backgroundCommand(sessionId: String, expectedOrigin: String?, expectedTurnId: String): BackgroundCommandResult = BackgroundCommandResult.NotConnected

    /**
     * T7.3: the `providers-snapshot` catalog the server pushed on this connection (the composer's
     * `@` Agents). Emptied with the other per-server views on a sign-in switch.
     */
    val providerCatalog: StateFlow<List<ProviderCatalogEntry>> get() = NO_PROVIDER_CATALOG

    /** T7.3: ask for the catalog (a read; reply `providers-snapshot`). False when not sent. */
    fun requestProviderCatalog(): Boolean = false

    /**
     * ta-895: [providerCatalog] was delivered by the CURRENT socket (this connection, this server).
     * False from the moment the socket goes, a new one opens or the server changes, until that
     * socket's own `providers-snapshot` lands. The New session picker draws profile rows only from
     * a live catalog.
     */
    val providerCatalogLive: StateFlow<Boolean> get() = NO_CATALOG_LIVE

    /**
     * ta-2uq: the model browser's Retry / Refresh for one catalog row ([key]): sends
     * `{type:"refresh-providers", providers:[key]}` (use-tether.ts refreshProviders) only from an
     * explicit tap, and only when, under the client's lock, the socket is live, handshaken and still
     * the one the browser was drawn on ([expectedEpoch] = [linkEpoch]), the row is in the catalog that
     * socket delivered, and [ProviderRefreshThrottle] admits it (one in flight per row, taps
     * debounced, dropped on a socket change). Never queued, never resent.
     */
    fun refreshProviders(key: String, expectedEpoch: Long): ProviderRefreshResult = ProviderRefreshResult.NotConnected

    /**
     * ta-895 / ta-8cv: start a new session as the draft composer submitted it ([request]: the row
     * drawn, the draft form, a fresh `requestId`). Call it ONLY from an explicit submit (a tap on a
     * row, the composer's Send), never in answer to anything received, and never again for the
     * same attempt: a create is never retried, held, queued or persisted (SYNC_DESIGN §5.2).
     *
     * Under the client's lock, in order: a live, handshaken socket of a running client, and the
     * one the draft was composed on ([NewSessionRequest.linkEpoch] = [linkEpoch]); the row drawn
     * for THIS server ([expectedOrigin], the [consentOrigin] the picker was composed with); the row
     * resolved again against the catalog THIS socket delivered ([NewSessionGuard.resolveEntry]: the
     * same key, engine and profile, offered and submittable; with no live catalog, a base
     * provider's default row only); then the web's `create` frame for it ([CreateFrame.build]:
     * permission mode, sandbox, approvals, worktree, profile, picked model/effort, requestId)
     * enqueued on that socket. Otherwise nothing is sent: never another profile, and never the
     * default profile in place of one that went.
     */
    fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult = NewSessionResult.NotConnected

    /**
     * ta-23f (v98): ask what [cwd]'s repo offers an isolated session (`worktree-inspect`, a read; the
     * server answers `worktree-source` echoing [requestId] and refreshes the repo's remote refs in
     * the background). Sent only when, under the client's lock, the socket is live, handshaken and
     * still the one the composer was drawn on ([expectedEpoch] = [linkEpoch]), with a non-empty
     * [cwd] and a [requestId] of at most 64 characters (the server's bound). Never queued or resent.
     * False when not sent.
     */
    fun inspectWorktree(cwd: String, requestId: String, expectedEpoch: Long): Boolean = false

    /**
     * ta-23f: every `worktree-source` of the live socket, stamped with that socket's [linkEpoch] and
     * its echo, in order. The draft composer takes only the one answering its own inspect, for the
     * folder it asked about, on the socket it asked on.
     */
    val worktreeSources: Flow<WorktreeSourceReply> get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * ta-8cv: every `error` frame of the live socket, as the web's `createError` holds it
     * (use-tether.ts: `{message, seq, requestId?}`, seq monotonic): the draft composer acts only on
     * the one whose `requestId` echoes its in-flight create. The message is cleaned by the error
     * text rule ([LabelText.error]). The toast ([serverErrors]) still shows every error, as the web's
     * `setError` does.
     */
    val createErrors: StateFlow<CreateErrorReply?> get() = NO_CREATE_ERRORS

    /**
     * ta-8cv r2 (security F2): every `created` of the live socket, in order and never conflated away
     * (two replies in one burst both arrive), for the view model and the draft composer.
     * [createdSessions] keeps only the latest. The default reads [createdSessions] (a client without
     * its own stream), which can conflate.
     */
    val createdReplies: Flow<CreatedReply> get() = createdSessions.filterNotNull()

    /** ta-8cv r2 (security F2): every `error` frame of the live socket, in order ([createErrors] keeps the latest). */
    val createErrorReplies: Flow<CreateErrorReply> get() = createErrors.filterNotNull()

    /**
     * ta-8cv r2 (security F2): the reply the server already sent to the create with [requestId] (a
     * `created` or an `error` echoing it), if it is one of the recent ones, else null. Recorded when
     * the frame is handled, before the socket it came on can be reported gone, so a create whose
     * reply landed just before a drop is never taken for one that was not answered.
     */
    fun createReply(requestId: String): CreateReplyRecord? = null

    /**
     * ta-8cv r2 (security F1): the draft composer's first message for the session its create just
     * made. Durable like [send], but recorded only when, in the same step under the client's lock,
     * the outbox's server, the live socket's server and [expectedOrigin] are the same, and the live
     * socket is still the one the create went out on ([expectedEpoch] = [linkEpoch]); the session is
     * listed and neither read-only, handed off nor archived. Otherwise nothing is recorded (false):
     * the caller keeps the prompt as that session's draft and never resends it.
     */
    fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean = false

    /**
     * ta-8cv: which socket is live, as a number that moves each time a new one opens (never back).
     * A create composed on one socket is refused on the next ([NewSessionRequest.linkEpoch]), and a
     * create in flight on a socket that went is over (its reply can never arrive on another one).
     */
    val linkEpoch: StateFlow<Long> get() = NO_LINK_EPOCH

    /**
     * T7.3: a send that DELEGATES (v103 `send.mention`): durable like [send], but only when drawn for
     * the server the outbox belongs to ([expectedOrigin]; r2), for a listed session that is neither
     * read-only, handed off nor archived, with a mention the current catalog offers it
     * ([CommandGuard.mentionOffered]), all checked in the same step that records it. Otherwise
     * nothing is recorded.
     */
    fun sendDelegated(sessionId: String, text: String, attachments: List<Attachment>, mention: com.tether.app.protocol.DelegateMention, expectedOrigin: String?): MentionResult = MentionResult.NotOffered

    /**
     * T6.6: dismiss ONE notice instance for every device (`dismiss-notice`, v119; the server journals
     * `notice_dismissed`, and the notice goes when that folds). Call it ONLY from a tap on the notice's
     * X, never in answer to anything received. Sent only on a live, handshaken socket of the server
     * that drew the X ([expectedOrigin]), for a listed session confirmed live on it, and only while its
     * CURRENT projection still shows [dismissKey]. A read-only or handed-off session may dismiss (the
     * server allows it: dismissal is presentation-only). At most once per key per connection; nothing
     * is retried, queued or persisted.
     */
    fun dismissNotice(sessionId: String, dismissKey: String, expectedOrigin: String?): NoticeResult = NoticeResult.NotConnected

    fun resumeHistory(historyId: String, cwd: String)
    fun discover(cwd: String)
    fun browse(cwd: String? = null)

    /**
     * T7.2: the ONE path an operator's session-control choice takes to the wire (`set-mode`,
     * `set-model`, `set-reasoning-effort`, `set-fast-mode`, `codex-control-action`,
     * `opencode-control-action`). Call it ONLY from a tap or an accessibility action on the control,
     * never in answer to anything received, restored or recomposed. Sent only on a live, handshaken
     * socket of the server that drew the control ([expectedOrigin], the [consentOrigin] the row was
     * composed with), for a session confirmed live on it that is neither read-only nor handed off,
     * and only with a value the session's CURRENT state offers ([SessionControlsGuard]); the most
     * permissive postures also need their confirmation. Otherwise nothing is sent or held: no retry,
     * no queue, nothing persisted.
     */
    fun sessionControl(sessionId: String, control: SessionControl, expectedOrigin: String?): ControlResult = ControlResult.NotConnected

    /** T7.2: per session, the Codex v2 provider-control snapshot received on the current socket. */
    val codexControls: StateFlow<Map<String, ProviderControlsState<CodexSnapshot>>> get() = NO_CODEX_CONTROLS

    /** T7.2: per session, the opencode-serve v2 provider-control snapshot received on the current socket. */
    val opencodeControls: StateFlow<Map<String, ProviderControlsState<OpencodeSnapshot>>> get() = NO_OPENCODE_CONTROLS

    /** T7.2: ask for a Codex v2 session's catalogs (a read; reply `codex-controls`). False when not sent. */
    fun requestCodexControls(sessionId: String): Boolean = false

    /** T7.2: ask for an opencode-serve v2 session's catalogs (a read; reply `opencode-controls`). */
    fun requestOpencodeControls(sessionId: String): Boolean = false

    /** Ask for the session's available models + slash-command list. Cheap + idempotent. */
    fun requestSessionControls(sessionId: String)

    /**
     * T7.3 (v96, chat-view.tsx:2707-2710): the same read with `warm: true` — the slash palette opened,
     * so a cold persistent engine's built-in fallback list is replaced by the CLI's real catalog.
     */
    fun requestWarmSessionControls(sessionId: String) = requestSessionControls(sessionId)
    fun pin(sessionId: String, pinned: Boolean)
    fun rename(sessionId: String, name: String)
    fun archive(sessionId: String)

    /**
     * End the session (`kill`). An operator control, called only from a confirmed tap. Sent only on a
     * live, handshaken socket of the server that drew the control ([expectedOrigin], the
     * [consentOrigin] captured when the key was armed or its confirmation opened: an End drawn for
     * another server is refused, even for a same-id session), for a session the server listed.
     * [requireLive] (the default: a session header's End session, drawn from the session's own copy)
     * also needs the session confirmed live on this connection ([liveSessions]); a sidebar row, drawn
     * from the live session LIST, passes false. Otherwise nothing is sent or held (T13.2 r2, r3).
     */
    fun kill(sessionId: String, expectedOrigin: String?, requireLive: Boolean = true)

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
     * T15.3: the Overview's `GET /api/overview/host` and `/api/overview/usage` on the paired server,
     * with the credential in force and never following a redirect (see [HttpOverviewMetrics]). The
     * default refuses every call without touching the network.
     */
    val overviewMetrics: OverviewMetricsSource get() = OverviewMetricsSource.Unavailable

    /**
     * ta-9q2: Settings' Claude accounts, READ ONLY: `GET /api/claude-accounts`, `/sync` and
     * `/<id>/status` on the paired server, with the credential in force and never following a
     * redirect (see [HttpClaudeAccounts]). The owner-grade writes are not reachable through it. The
     * default refuses every call without touching the network.
     */
    val claudeAccounts: ClaudeAccountsSource get() = ClaudeAccountsSource.Unavailable

    /**
     * T10.4: Settings → Devices (paired devices, passkeys, signed-in sessions), over the fixed routes
     * of [DeviceSecuritySource], each call bound to the server it names and sent with the credential
     * in force, never following a redirect (see [HttpDeviceSecurity]).
     */
    val deviceSecurity: DeviceSecuritySource get() = DeviceSecuritySource.Unavailable

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

    /**
     * T9.1 (use-tether.ts:918-921): the latest `worktree-scripts` snapshot per session (raw
     * `WorktreeScriptsSnapshot`, keyed by its own `sessionId`). Also arrives UNSOLICITED (a service
     * exits, a setup hook finishes). Emptied with the other server views.
     */
    val worktreeScripts: StateFlow<Map<String, kotlinx.serialization.json.JsonObject>> get() = NO_WORKTREE_SCRIPTS

    /**
     * T9.1 (use-tether.ts:940-941): the latest `change-request` reply per session. [ChangeRequestReading.unknown]
     * marks a failed lookup, distinct from "no pull request" (a null state).
     */
    val changeRequests: StateFlow<Map<String, ChangeRequestReading>> get() = NO_CHANGE_REQUESTS

    /** `worktree-scripts` (use-tether.ts:1496): ask for the session's scripts snapshot. False when not sent. */
    fun requestWorktreeScripts(sessionId: String): Boolean = false

    /** `change-request` (use-tether.ts:1508), a read; [refresh] asks the server to look again. False when not sent. */
    fun requestChangeRequest(sessionId: String, refresh: Boolean = false): Boolean = false

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
     * T10.3: the PROTOCOL_VERSION the server named in this connection's `ready` (the console's
     * own version, which the web's Nodes panel compares a peer's against). Null until a ready of
     * the current sign-in arrives; emptied with the node registry.
     */
    val serverProtocolVersion: StateFlow<Int?> get() = NO_SERVER_PROTOCOL

    /**
     * `node-add`: register a peer from its credential bundle. [label] and
     * [baseUrl] are trimmed and omitted when blank (the web form + hook);
     * [baseUrl] overrides the bundle's own hint. The credential is sent once and
     * never kept, logged or persisted (see [NodeCredential]).
     *
     * T10.3: [origin] is the server the caller drew its screen from ([serverOrigin]); the frame
     * goes out only on a live socket opened for it (checked under the lock the send takes), else
     * [NodeRequestOutcome.NotSent]: a credential typed for one server never reaches another.
     */
    suspend fun addNode(origin: String, credential: NodeCredential, label: String? = null, baseUrl: String? = null): NodeRequestOutcome =
        NodeRequestOutcome.NotSent

    /** `node-remove`: forget a peer (the server also deletes its stored bearer). Bound to [origin] like [addNode]. */
    suspend fun removeNode(origin: String, nodeId: String): NodeRequestOutcome = NodeRequestOutcome.NotSent

    /** `node-probe`: re-check a peer now; its new status arrives in [nodes]. Bound to [origin] like [addNode]. */
    suspend fun probeNode(origin: String, nodeId: String): NodeRequestOutcome = NodeRequestOutcome.NotSent

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

    /** v16: the last `advanced-settings` frame (null until one arrives on this server). */
    val advancedSettings: StateFlow<ServerMessage.AdvancedSettings?> get() = NO_ADVANCED_SETTINGS

    /** v16 `advanced-settings` request (Settings opening asks for it, dashboard.tsx:1333). */
    fun requestAdvancedSettings(): Boolean = false

    /**
     * ta-t7l: v50 `set-server-settings {settings: patch}` (a Partial<ServerSettings>: only the keys
     * that changed, see [ServerSettingsPatch]), sent only when the live socket was opened for
     * [origin], the server the patch was drawn from: an edit made against one server is never
     * delivered to another. Returns whether it was sent; the server answers with `server-settings`.
     */
    fun setServerSettings(patch: JsonObject, origin: String): Boolean = false

    /**
     * ta-dh1 r2: a confirmed engine home / command / launch command ([ConfirmedEngineWrite], made only
     * by [ServerSettingsPatch.engineValue]), bound to [origin] like [setServerSettings]. The ONE
     * way such a key reaches the server: [setServerSettings] refuses a plain patch naming one.
     */
    fun setConfirmedEngineValue(write: ConfirmedEngineWrite, origin: String): Boolean = false

    /** ta-t7l: v16 `set-advanced-settings` (the Claude CLI picker), bound to [origin] like [setServerSettings]. */
    fun setAdvancedSettings(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean = false

    /**
     * ta-dh1: v73 `detect-engines` (the Engines tab's "Scan again", use-tether.ts:1848): the server
     * re-runs its host-CLI scan and answers with `server-settings`. Bound to [origin] like
     * [setServerSettings]. Returns whether it was sent.
     */
    fun detectEngines(origin: String): Boolean = false

    /**
     * ta-dh1: how many `server-settings` frames have arrived (a reply equal to the last one counts
     * too, though [serverSettings] does not change then): "Scan again" is busy until the next.
     */
    val serverSettingsReplies: StateFlow<Long> get() = NO_SERVER_SETTINGS_REPLIES

    /**
     * ta-q6p: v84 the custom-providers registry, the last `providers` frame (the reply to
     * [requestProviders] and the broadcast after every `set-providers`, from any client), null
     * until one arrives on this server. Dropped on a server switch, a sign-out and an auth-required
     * state: each profile's env values are plaintext secrets.
     */
    val providerProfiles: StateFlow<ProvidersList?> get() = NO_PROVIDER_PROFILES

    /**
     * ta-q6p: v84 `providers` request (Settings opening asks for it). r2 (security F1): once asked,
     * the client asks again after every handshake (a reconnect, however fast), until a sign-out.
     */
    fun requestProviders(): Boolean = false

    /**
     * ta-q6p: v84 `set-providers`, the WHOLE registry ([ProvidersWrite], made only by
     * [ProvidersPatch]), sent only when the live socket was opened for [origin] AND, at the moment
     * of the send, the write passes [ProvidersPatch.refusal] against the newest list, that list came
     * on THIS socket (r2: never one from before a reconnect), and no earlier write is still waiting
     * for its broadcast ([ProvidersInFlight]). The only way the app sends `set-providers`.
     * Returns null when it was sent, else why not.
     */
    fun setProviders(write: ProvidersWrite, origin: String): ProvidersRefusal? = ProvidersRefusal.NotConnected

    /** ta-q6p r4: what became of the last [setProviders] write, read against the newest list (the client's guard). */
    fun providersWriteStatus(): ProvidersWriteStatus = ProvidersWriteStatus.Idle

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

    // ------------------------------------------------------------------
    // T15.1 v131 Overview feed (OverviewSync.kt): defaults keep other implementations compiling.
    // ------------------------------------------------------------------

    /**
     * use-tether.ts `overview`: the opt-in, read-only Overview feed folded by
     * [com.tether.app.protocol.overview.OverviewClient]. Idle until [subscribeOverview]; Offline
     * (the last data, stale) while the socket is down; emptied with the other per-server views.
     */
    val overview: StateFlow<OverviewClientState> get() = NO_OVERVIEW

    /**
     * use-tether.ts subscribeOverview: opt in while the Overview is on screen. Re-sending replaces
     * the subscription (filters, page) and yields a fresh snapshot. Sent only on a live, handshaken
     * socket; otherwise the wish is recorded and the next `ready` sends it. Returns whether a frame
     * went out. Viewing is read-only: nothing here attaches a session, marks one seen or sends a turn.
     */
    fun subscribeOverview(subscription: OverviewSubscription): Boolean = false

    /** use-tether.ts unsubscribeOverview: leaving the Overview (or the app going to the background) stops its pushes. */
    fun unsubscribeOverview() {}
}

private val NO_OVERVIEW: StateFlow<OverviewClientState> = MutableStateFlow(OverviewClientState())

private val NO_SEARCH_RESULTS: StateFlow<SearchResults> = MutableStateFlow(SearchResults())
private val NO_GLOBAL_SEARCH_RESULTS: StateFlow<GlobalSearchResults> = MutableStateFlow(GlobalSearchResults())

/** One `created` reply (use-tether.ts:291 `{session, seq, requestId?}`). */
/**
 * ta-8cv r2: [linkEpoch] is the [TetherClient.linkEpoch] of the socket the reply came on (null: a
 * client that does not say).
 */
data class CreatedReply(val session: AgentSession, val seq: Long, val requestId: String? = null, val linkEpoch: Long? = null)

/** ta-8cv: one `error` frame of the live socket ([TetherClient.createErrors]); [message] already cleaned. */
data class CreateErrorReply(val message: String, val seq: Long, val requestId: String? = null, val linkEpoch: Long? = null)

/** ta-8cv r2: the recorded answer to one create ([TetherClient.createReply]). */
sealed interface CreateReplyRecord {
    data class Created(val reply: CreatedReply) : CreateReplyRecord
    data class Failed(val reply: CreateErrorReply) : CreateReplyRecord
}

private val NO_CREATED: StateFlow<CreatedReply?> = MutableStateFlow(null)
private val NO_CREATE_ERRORS: StateFlow<CreateErrorReply?> = MutableStateFlow(null)
private val NO_LINK_EPOCH: StateFlow<Long> = MutableStateFlow(0L)

private val NO_EVENT_LOG: StateFlow<EventLog> = MutableStateFlow(EventLog())
private val NO_HISTORIES_BY_CWD: StateFlow<Map<String, List<HistorySession>>> = MutableStateFlow(emptyMap())
private val NO_SESSION_ORDERS: StateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
private val NO_REMOTE_SEEN: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
private val NO_SERVER_SETTINGS: StateFlow<ServerMessage.ServerSettings?> = MutableStateFlow(null)
private val NO_ADVANCED_SETTINGS: StateFlow<ServerMessage.AdvancedSettings?> = MutableStateFlow(null)
private val NO_PROVIDER_PROFILES: StateFlow<ProvidersList?> = MutableStateFlow(null)
private val NO_SERVER_SETTINGS_REPLIES: StateFlow<Long> = MutableStateFlow(0L)
private val NO_HIDDEN_AGENT_SESSION_COUNT: StateFlow<Int?> = MutableStateFlow(null)

private val NO_NODES: StateFlow<List<NodeSummary>> = MutableStateFlow(emptyList())
private val NO_NODE_RESULT: StateFlow<NodeActionResult?> = MutableStateFlow(null)
private val NO_SERVER_PROTOCOL: StateFlow<Int?> = MutableStateFlow(null)
private val NO_GIT_FILE_DIFFS: StateFlow<Map<String, Map<String, ServerMessage.GitDiffFile>>> = MutableStateFlow(emptyMap())
private val NO_WORKTREE_DIFFS: StateFlow<Map<String, kotlinx.serialization.json.JsonObject?>> = MutableStateFlow(emptyMap())
private val NO_WORKTREE_SCRIPTS: StateFlow<Map<String, kotlinx.serialization.json.JsonObject>> = MutableStateFlow(emptyMap())
private val NO_CHANGE_REQUESTS: StateFlow<Map<String, ChangeRequestReading>> = MutableStateFlow(emptyMap())

/** One `change-request` reply: the raw `ChangeRequestState | null`, and whether the lookup failed. */
data class ChangeRequestReading(val changeRequest: kotlinx.serialization.json.JsonObject?, val unknown: Boolean)

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
private val NO_UNCONFIRMED: StateFlow<Set<String>> = MutableStateFlow(emptySet())
private val NO_SYNC_STATES: StateFlow<Map<String, SessionSync>> = MutableStateFlow(emptyMap())
private val NO_PROVIDER_CATALOG: StateFlow<List<ProviderCatalogEntry>> = MutableStateFlow(emptyList())
private val NO_CATALOG_LIVE: StateFlow<Boolean> = MutableStateFlow(false)
private val NO_CODEX_CONTROLS: StateFlow<Map<String, ProviderControlsState<CodexSnapshot>>> = MutableStateFlow(emptyMap())
private val NO_OPENCODE_CONTROLS: StateFlow<Map<String, ProviderControlsState<OpencodeSnapshot>>> = MutableStateFlow(emptyMap())
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

/** T13.2 r2: what [TetherClient.interrupt] did. Only [Sent] put a frame on the wire. */
enum class InterruptResult {
    Sent,

    /** No live, handshaken socket (or the client is halted). */
    NotConnected,

    /** Connected, but the session is not confirmed live on this connection, or the key was drawn for another server. */
    NotLive,

    /** The session is read-only or handed off (or unknown): the server would refuse it. */
    Locked,

    /**
     * T6.7: the turn the key was drawn for is no longer the session's open active turn (it ended,
     * or another turn replaced it, or none is running): the turn running now is not interrupted.
     */
    NotCurrentTurn,
}

/** T6.7: a server's error words, cleaned ([LabelText.error]), and the origin of the socket they came in on. */
data class ServerErrorText(val text: String, val origin: String)

/** T6.7 r3: [TetherClient.failedInterrupts] of a client that records none. */
private val NoFailedInterrupts: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

/** T6.7: [TetherClient.serverErrors] of a client that has none. */
private val NoServerErrors: SharedFlow<ServerErrorText> = kotlinx.coroutines.flow.MutableSharedFlow()

/** T6.6: what [TetherClient.dismissNotice] did. Only [Sent] put a frame on the wire. */
enum class NoticeResult {
    Sent,

    /** No live, handshaken socket. */
    NotConnected,

    /** Connected, but the session is not confirmed live on this connection, or the X was drawn for another server. */
    NotLive,

    /** The session is not listed (fail closed). */
    Locked,

    /** The current projection no longer shows this notice (already dismissed, replaced, or never there). */
    NotShown,

    /** This connection already carried the dismissal; it is not sent twice. */
    AlreadySent,
}

/** T6.4: what [TetherClient.stopCommand] did. Only [Sent] put a frame on the wire. */
enum class StopCommandResult {
    Sent,

    /** No live, handshaken socket. */
    NotConnected,

    /** Connected, but the session is not confirmed live on this connection yet, or the key was drawn for another server. */
    NotLive,

    /** The session is read-only or handed off (or unknown): the server would refuse it. */
    Locked,

    /** The current projection holds no RUNNING command with this id (finished, evicted, unknown). */
    NotRunning,
}
