package com.tether.app.client

import com.tether.app.client.sync.MirrorLink
import com.tether.app.client.sync.SessionStore
import com.tether.app.mirror.Hydration
import com.tether.app.mirror.JournalMirror
import com.tether.app.mirror.MirrorIndex
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.HELLO_CLIENT_ANDROID
import com.tether.app.protocol.NodeSummary
import com.tether.app.protocol.PROTOCOL_VERSION
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.fold.isOpenCurrentTurn
import com.tether.app.protocol.fold.projectionHasNoticeKey
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.str
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * `host`, or `host:port` for a non-default port: what the user typed, minus
 * the scheme; an IPv6 literal in brackets. When that reads the same as
 * [versus] (the server now in force, e.g. after an http -> https switch on
 * one host) the scheme is kept, so the notice never names the server the
 * user is on as the one the records were kept for.
 */
internal fun displayHost(origin: String, versus: String? = null): String {
    val url = origin.toHttpUrlOrNull() ?: return origin
    val host = bracketedHost(url.host)
    val short = if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    return if (versus != null && versus != origin && displayHost(versus) == short) "${url.scheme}://$short" else short
}

/** See RealTetherClient.raceHook (tests only). */
internal enum class RacePoint {
    FrameAdmitted, FrameHandled, DrainComputed, VerdictChecked, SignInStarted, OriginSwitched,

    /** ta-2ew: a sign-in moved the URL to another origin; its views (the create answers too) are not cleared yet. */
    ServerMoved,
}

/** T7.2: a `*-control-result` message is shown in one status line; a longer one is cut. */
private const val MAX_CONTROL_MESSAGE = 500

/** protocol-validate.mjs "dismiss-notice": `isNonEmptyString(dismissKey, 512)`. */
private const val DISMISS_KEY_MAX = 512

/** T6.6: how many sent dismissals the client remembers (per-connection dedupe). */
private const val DISMISSALS_REMEMBERED = 200

/** T6.7: how many sent interrupts per session a socket remembers the turns of. */
private const val INTERRUPTS_REMEMBERED = 8

/** T7.4: how many unconfirmed messages with attachments a socket remembers (each is megabytes: few). */
private const val ATTACHMENTS_IN_FLIGHT_REMEMBERED = 16

/** ta-8cv r2: create replies buffered for a slow collector, and create answers remembered by requestId. */
private const val CREATE_REPLIES_BUFFERED = 64

/** ta-23f: lib/protocol-validate.mjs `worktree-inspect.requestId`: a bounded non-empty string (<=64 chars). */
private const val INSPECT_REQUEST_ID_MAX = 64

private const val CREATE_REPLIES_REMEMBERED = 32

/** T7.4: a message with attachments offered to the durable path (never used for them). */
internal const val ATTACHMENTS_NOT_SENT_COPY = "Not connected — the message and its attachments were not sent."

/** T7.4: the link went before the server confirmed [count] message(s) with attachments. */
internal fun attachmentsUnconfirmedCopy(count: Int): String =
    if (count == 1) {
        "The connection dropped before the server confirmed your message with attachments. It was not resent: check the conversation and send it again if it is missing."
    } else {
        "The connection dropped before the server confirmed $count messages with attachments. They were not resent: check the conversation and send them again if they are missing."
    }

/** T7.4: every `idempotencyKey` a projection's turns carry (the durable acknowledgement). */
internal fun turnKeysOf(tree: JsObj): Set<String> {
    val turns = tree["turnsById"] as? JsObj ?: return emptySet()
    val keys = HashSet<String>()
    for ((_, turn) in turns.entries) {
        val key = (turn as? JsObj)?.get("idempotencyKey") as? JsStr ?: continue
        keys.add(key.value)
    }
    return keys
}

/** use-tether.ts:1169: a failed `interrupt_result` without the server's own words. */
internal const val INTERRUPT_NOT_DELIVERED = "The interrupt request could not be delivered."

/** T6.7: the server stopped a turn other than the one the Interrupt key was drawn for. */
internal const val INTERRUPT_REACHED_LATER_TURN =
    "The interrupt reached a turn that started after you tapped: the turn you tapped for had already ended."

/** Application close code: the server revoked this device (see server.mjs §disconnectDeviceSockets). */
private const val CLOSE_DEVICE_REVOKED = 4001

/** Application close code: the cookie session was revoked (server.mjs §disconnectRevokedSessionSockets). */
private const val CLOSE_SESSION_REVOKED = 4002

/** A hydration read that has not landed by then counts as "no saved copy" (T13.1): recovered by a full attach. */
private const val MIRROR_HYDRATE_TIMEOUT_MS = 15_000L

/** A mirror bind that has not answered by then means no mirror for this process (T13.1, M2). */
private const val MIRROR_BIND_TIMEOUT_MS = 5_000L

/** Upper bound on the frame thread's wait for a seqless event's mirror cursor clear (T13.1). */
private const val SEQLESS_CLEAR_WAIT_MS = 2_000L

/** ta-exi: start()'s reads of the stored settings before it fails closed (see readSessionForStart). */
private const val SETTINGS_READ_ATTEMPTS = 3

/** ta-exi: the pause between those reads. */
private const val SETTINGS_READ_RETRY_MS = 250L

/** §3.1 rule 5: mirror-restored sessions re-attached on `ready`, beyond pinned ones. */
private const val MIRROR_REATTACH_RECENT = 10

/**
 * Upper bound on the best-effort server calls made while signing out. Public so the logout
 * hook's own blocking call (the push unregister, ta-jt9 I-B) is bounded by the same value.
 */
const val LOGOUT_CALL_TIMEOUT_MS = 5_000L

/** An RFC 9110 auth-scheme token, short enough to show on the login screen. */
private val AUTH_SCHEME = Regex("[A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]{0,31}")

/** The first product of a `Server` header ("nginx/1.27.1"), short enough to show. */
private val SERVER_PRODUCT = Regex("[A-Za-z][A-Za-z0-9._/-]{0,39}")

internal const val PASSKEY_LOGIN_OPTIONS_PATH = "/api/auth/passkey/login/options"
internal const val PASSKEY_LOGIN_VERIFY_PATH = "/api/auth/passkey/login/verify"

/** The most a passkey route's answer may be: a challenge is a few hundred bytes. */
private const val PASSKEY_BODY_CAP: Long = 64L * 1024L

/** The app's words for a passkey sign-in that went wrong on the phone (the server's own are shown as they come). */
object PasskeyLoginCopy {
    /** r2 (security F1): the app's phrase wherever a passkey is refused for an http address. */
    const val NEEDS_HTTPS = "Passkeys need an https:// address."
    const val WRONG_RP = "This server asked for a passkey for another address, so none was offered. Sign in with the address the console itself uses."
    const val UNREADABLE = "The server sent an unusable passkey challenge."
    const val UNREADABLE_ANSWER = "The passkey answer could not be read, so nothing was sent."
    const val NO_CREDENTIAL = "This phone has no passkey for this console. Add one in Settings → Devices, or sign in another way."
    const val UNSUPPORTED = "No passkey provider on this phone can sign in. Turn one on in Android Settings, or sign in another way."
    const val FAILED = "Passkey sign-in failed."
}

private const val REDIRECT_MESSAGE =
    "The server redirected this request instead of answering it. Check the URL (https:// or http://). " +
        "If a sign-in gateway (SSO) is in front of Tether, pair this device with a code instead."

/**
 * Production [TetherClient]: OkHttp WebSocket + cookie/device-token auth + the
 * attach / reconnect / durable-send discipline of specs/protocol-spec.md §5.
 *
 * Connection manager (T1.2), per connection EPOCH (one socket):
 * ```
 * Disconnected/AuthRequired --start/login/pair--> Connecting
 * Connecting --auth probe ok, upgrade--> (socket open, NOT yet live)
 *   --ready--> window check --> send hello --> Connected --> attach every
 *   subscribed / pending session with afterSeq = its cursor
 *   --ready outside the window / version_mismatch--> VersionMismatch (terminal)
 * any socket loss / ping timeout / half-open sweep --> Disconnected
 *   --> reconnect after Backoff.next() (reset by the next accepted ready)
 * close 4001 / 4002, or auth probe authenticated:false --> AuthRequired,
 *   credential cleared, URL kept, signedOutReason set (terminal)
 * auth probe redirect / 401 / 403 (a gateway) --> AuthRequired, credential kept
 * logout() --> AuthRequired, credential cleared (+ POST /api/auth/logout for a cookie)
 * restricted local network (+ repeated timeouts) --> LocalNetworkBlocked (no loop)
 * background > BACKGROUND_GRACE_MS --> socket closed, reconnects stop until foreground
 * ```
 * Nothing but `ping`/`hello` goes out before `ready`: every other frame waits for
 * the handshake of the current epoch.
 *
 * v109 node registry (T1.5): `nodes` replaces [nodes]; every `node-result`
 * becomes [nodeResult]. addNode/removeNode/probeNode send ONE frame tagged with
 * a fresh requestId and end on its `node-result` (or a correlated `error`), on
 * the socket going away (LinkLost, from detachSocketLocked), or on
 * [nodeRequestTimeoutMs]. Never queued, persisted or retried. T10.3: each is
 * drawn for an origin and goes out only on a socket opened for it. The registry is
 * kept across a reconnect (the web never clears it) and emptied on logout, a
 * server-side sign-out and a new sign-in.
 *
 * Durable send is per server ORIGIN (ta-s8q), as the web's localStorage is.
 * The origin is [serverOrigin]: `scheme://host:port`, OkHttp-canonical (case,
 * default port and trailing slash do not matter; another port, scheme or host
 * is another server). The in-memory pending store is tagged with the origin
 * it belongs to ([pendingOrigin]) and persisted in that origin's slot only
 * ([SettingsStore.readPendingInput]). Invariants:
 * - a record, and the attach of its session, only ever go out on a socket
 *   opened for the store's origin (drainPending / onReady check [socketOrigin],
 *   and pending frames go out on the socket they were computed for);
 * - a sign-in to ANOTHER origin sets the current store aside under its own
 *   origin (kept in memory for this process, exact, and merged into that
 *   origin's slot on disk), surfaces a notice, and clears everything else that
 *   is per server: subscriptions, cursors, reconciled sessions, tombstones,
 *   attached sessions, sessions and projections. Signing in to that origin
 *   again brings its records back under the ordinary T1.3 rules;
 * - a sign-in to the SAME origin (a re-login after expiry) changes nothing:
 *   the T1.3 guarantees hold exactly as for a reconnect;
 * - set-aside records do not expire while away. On return the T1.3 10-minute
 *   rule (and the retry cap) is applied the moment the store is restored,
 *   BEFORE anything can drain: expired records are dropped with the sweeper's
 *   "could not be delivered" notice and never transmitted. The same holds for
 *   a store restored after process death, and it is what the web does to an
 *   origin whose page was closed: expiring them while signed in elsewhere
 *   would only move that notice out of the context it is about;
 * - slots are not wiped by logout (a re-login to the same origin delivers
 *   them). A slot of another origin whose records have ALL passed that age
 *   (or that holds none) is pruned when a store is bound (start / sign-in),
 *   with the same notice for any records it held; nothing else prunes slots.
 *
 * Origin keying of the staged outbox (SYNC_DESIGN §5.3, T13.3) must follow the
 * same slots: the outbox key sits next to its origin's pending slot, in the
 * same edit.
 *
 * Construction (integrator):
 * ```
 * val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
 * val client = RealTetherClient(
 *     settings = DataStoreSettings.create(context.filesDir, scope),
 *     httpClient = OkHttpClient(),
 *     scope = scope,
 * )
 * client.start()
 * ```
 * Wire [reconnectIfIdle] to ConnectivityManager.NetworkCallback.onAvailable and
 * to Activity/Process lifecycle onResume. Call [stop] only on logout.
 *
 * [localNetworkAccess] reports the Android 17 local-network block. When it
 * applies to the server, login/pair/connect report LocalNetworkBlocked instead of
 * touching the network. A local host is known from the URL before any traffic;
 * a name that turns out to resolve to a LAN address is known after a transport
 * failure. The UI then asks for the permission.
 */
class RealTetherClient(
    private val settings: SettingsStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: Backoff = Backoff(),
    private val sweepIntervalMs: Long = 2_000,
    private val localNetworkAccess: LocalNetworkAccess = LocalNetworkAccess.Unrestricted,
    private val scheduler: Scheduler = CoroutineScheduler(scope),
    /**
     * Runs during a user [logout] with the credential that was just forgotten
     * (already gone from [settings]), e.g. to unregister push for a device token.
     * Bounded by [LOGOUT_CALL_TIMEOUT_MS] at its suspension points; failures are ignored.
     * That bound cannot interrupt a blocking call inside the hook, which must carry its
     * own: the push unregister's HTTP call has a [LOGOUT_CALL_TIMEOUT_MS] call timeout.
     * So the hook can take about twice the bound (~10 s), and a whole logout, with the
     * server revoke's own call timeout, about three times (~15 s).
     */
    private val onLogout: suspend (baseUrl: String, credential: Credential) -> Unit = { _, _ -> },
    /** How long a node-add/remove/probe waits for its `node-result` (timed on [scheduler]). */
    private val nodeRequestTimeoutMs: Long = NodeRegistryRules.REQUEST_TIMEOUT_MS,
    /**
     * T13.1 journal mirror (SYNC_DESIGN §2): null = off (the `mirrorEnabled` rollback flag).
     * Bound to the origin in force; wiped with its data key on logout and revocation.
     */
    mirror: JournalMirror? = null,
    /**
     * ta-exi: how long the stored-settings collector ([configured], [serverUrl],
     * [storedSettingsLoaded]) waits before it resubscribes after a store error. Reset by its
     * next successful read.
     */
    private val settingsBackoff: Backoff = Backoff(),
) : TetherClient {

    // T13.1: frame -> mirror writes (null when the mirror is off).
    // Null again once the mirror failed to bind in time (M2): no mirror for this process. The
    // wipe path keeps [mirrorForWipe], so a logout still destroys whatever is on disk.
    @Volatile
    private var mirrorLink: MirrorLink? = mirror?.let(::MirrorLink)
    private val mirrorForWipe: JournalMirror? = mirror

    // The origin the mirror is bound to (or being bound to); null = unbound. Guarded by lock.
    private var mirrorOrigin: String? = null

    // Bumped by every mirror bind, wipe and server switch: a hydration started under an older
    // binding never publishes (security review L1). Guarded by lock.
    private var mirrorGeneration = 0L

    // Session ids the server listed (ready / created / session-update). With subscribed and
    // pending sessions these are the only ones mirrored until T13.5 bounds the cache (L3).
    private val listedSessionIds = HashSet<String>()

    // --- T13.1 attach policy for mirror-restored cursors (guarded by lock; empty while off) ---
    // Cursors restored from the mirror and not yet refreshed by a snapshot in this process:
    // the only ones the ready re-attach caps (§3.1 rule 5).
    private val seededFromMirror = HashSet<String>()
    // Last time the UI opened (attached) a session: orders the capped re-attach.
    private val lastOpenedAt = HashMap<String, Long>()

    /**
     * Every request that carries (or obtains) a credential goes through this
     * client, which NEVER follows redirects: OkHttp strips `Authorization` on a
     * cross-host redirect but not a hand-set `Cookie` header, and a redirected
     * login/claim could hand the password or the minted token to another host.
     */
    private val authHttp: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val lock = Any()

    // --- connection state (guarded by lock) ---
    // Logout or 4001: nothing reconnects until a new credential is adopted.
    private var stopped = false
    // Outside the native window: terminal until retryConnection() (user action).
    private var versionHalt: Incompatibility? = null
    // Backgrounded past the grace period: no socket, no reconnects, until foreground.
    private var backgroundSuspended = false
    private var inForeground = true
    private var backgroundTask: Cancellable? = null
    private var connecting = false
    // Connect-attempt generation: bumped by every sign-in, stop() and logout(),
    // which also free [connecting]. An attempt carries the generation it started
    // in; once that is stale the attempt does NOTHING (no state, no socket, and
    // it never owns or releases [connecting]), so neither an old credential nor
    // an old attempt's bookkeeping can outlive the change.
    private var connectGeneration = 0L
    private var socket: WebSocket? = null
    private var socketListener: SocketListener? = null
    private var socketOpen = false
    // The current socket's `ready` was accepted (window ok, hello sent): the
    // connection is live and ordinary frames may go out.
    private var handshakeDone = false
    // Connection epoch = one socket. Sessions attached during the current epoch;
    // an attach() for one of them is a no-op (T0.3: attach idempotent per epoch).
    private var epoch = 0L
    private val attachedThisEpoch = HashSet<String>()
    private var reconnectTask: Cancellable? = null
    private var pingTask: Cancellable? = null
    private var consecutiveTimeouts = 0
    private var sweeperJob: Job? = null
    private var baseUrlValue: HttpUrl? = null
    // start() has read the persisted server + credential at least once.
    private var settingsLoaded = false
    // ta-jt9 L-B: bumped by every sign-out (logout(), stop(), a credential the server rejected).
    // A start() adopts what it read from the store only if no sign-out happened since it began:
    // its snapshot may predate the sign-out and still hold the credential being forgotten.
    private var signOutEpoch = 0L
    // ta-jt9 L-B1: the credential the last sign-out forgot. A start() never adopts it from the
    // store, whenever it read its snapshot: the store may still hold it until the clear lands.
    private var forgottenCredential: Credential? = null
    // ta-jt9 L-1: sign-outs whose store clear has not landed yet (logout, stop(), a rejection).
    // While any is in flight a start() adopts nothing (the store may still hold what it clears,
    // also when nothing was in memory to remember as forgotten), and a sign-in waits for them
    // before it writes the store (a clear landing after it would delete the new credential).
    // Written under [lock]; the flow mirrors it so a sign-in can wait for 0.
    private val signOutClearsInFlight = MutableStateFlow(0)

    // The ONE credential in force. Cookie (password login) and device token
    // (pairing) differ only in the header they add, so the connect loop below
    // never branches on the auth mode.
    private var credentialValue: Credential? = null

    @Volatile
    private var lastInboundAt = 0L

    // --- protocol state (guarded by lock) ---
    private val tracker = CursorTracker()
    private val subscribed = LinkedHashSet<String>()
    private var pendingStore = PendingInput.emptyStore()
    // The canonical origin [pendingStore] belongs to. Null = not bound to a
    // server yet (a fresh process before its first start(), or after stop()).
    private var pendingOrigin: String? = null
    // The origin [socket] was opened for (set with its listener, cleared on detach).
    private var socketOrigin: String? = null
    // Stores of OTHER origins set aside by a sign-in switch in this process:
    // exact (tries, attachments), so returning restores them as they were.
    private val setAside = HashMap<String, SetAsideStore>()
    // Bumped by stop(): a set-aside write queued before the wipe must not land after it.
    private var pendingWipe = 0L
    // The newest wipe whose settings.clear() completed. Until it catches up
    // with [pendingWipe] the disk slots are dead: a binding reads them as empty.
    private var wipeLanded = 0L
    // The unattributed 0.6.0 slot was checked (and its notice shown) in this process.
    private var unattributedChecked = false
    // [pendingOrigin]'s persisted store was read and merged in (start()/a sign-in);
    // nothing drains or is written before that, so a cold start never overwrites
    // what the last process left.
    private var pendingLoaded = false
    private val reconciledSessions = HashSet<String>()
    // use-tether.ts clearedRef: keys removed for any reason (acked, withdrawn,
    // expired, evicted), persisted with the records; bounded by MAX_TOMBSTONES.
    private var clearedKeys = LinkedHashSet<String>()
    // Every store change bumps this; the writer only ever moves the file forward.
    private var pendingVersion = 0L
    // v109 node requests awaiting their `node-result`, by requestId. Only the
    // requestId and the waiter live here, never the frame (node-add's credential
    // is not retained past the send). Emptied (LinkLost) whenever the socket goes.
    private val nodeRequests = HashMap<String, CompletableDeferred<NodeRequestOutcome>>()
    // T6.3: sessions a snapshot confirmed on the CURRENT socket (SYNC_DESIGN §4.1 "Live"); a
    // decision is transmitted only for one of them. Emptied with the socket, the epoch, the server.
    private val liveThisEpoch = HashSet<String>()
    // T7.4: messages with attachments handed to the CURRENT socket and not yet confirmed (their
    // idempotencyKey -> session). Never resent: if the socket goes first the operator is told.
    private val attachmentsInFlight = LinkedHashMap<String, String>()
    // T6.3: every (origin, session, request) this process decided, so none is ever sent twice.
    private val consentLedger = ConsentLedger()

    // --- flows ---
    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val sessionsState = MutableStateFlow<List<AgentSession>>(emptyList())
    private val providersState = MutableStateFlow<List<ProviderInfo>>(emptyList())

    /** T7.3: the `providers-snapshot` catalog of the current server (the `@` Agents). */
    private val providerCatalogState = MutableStateFlow<List<ProviderCatalogEntry>>(emptyList())

    /**
     * ta-895: the connection [epoch] and origin whose socket delivered [providerCatalogState] (-1 /
     * null: none). A profile id goes out on `create` only from the catalog of the CURRENT socket
     * ([liveCatalogLocked]). Guarded by [lock].
     */
    private var catalogEpoch = -1L
    private var catalogOrigin: String? = null
    private val providerCatalogLiveState = MutableStateFlow(false)

    /** ta-2uq: `refresh-providers` bookkeeping (one in flight per row and socket, taps debounced). Guarded by [lock]. */
    private val refreshThrottle = ProviderRefreshThrottle()
    private val workspaceRootState = MutableStateFlow<String?>(null)
    private val hiddenAgentSessionCountState = MutableStateFlow<Int?>(null)

    // T2.1D: the v128 trees are the source of truth; the typed projections are adapted from
    // them by one memoized adapter per session. T13.1 (c): owned by [sessionStore] (SYNC_DESIGN §2.6
    // step 3), which the mirror hydrates and the connection feeds. Guarded by [lock].
    private val sessionStore = SessionStore()
    private val historiesState = MutableStateFlow<List<HistorySession>>(emptyList())
    private val directoriesState = MutableStateFlow<DirectoryListing?>(null)
    private val sessionControlsState = MutableStateFlow<Map<String, ServerMessage.SessionControls>>(emptyMap())
    // T7.2: provider-control snapshots received on the CURRENT socket (cleared with the live set).
    private val codexControlsState = MutableStateFlow<Map<String, ProviderControlsState<CodexSnapshot>>>(emptyMap())
    private val opencodeControlsState = MutableStateFlow<Map<String, ProviderControlsState<OpencodeSnapshot>>>(emptyMap())
    // T6.2: per-file git hunks (git-diff-file) and diff summaries (worktree-diff), per session.
    private val gitFileDiffsState = MutableStateFlow<Map<String, Map<String, ServerMessage.GitDiffFile>>>(emptyMap())
    private val worktreeDiffsState = MutableStateFlow<Map<String, JsonObject?>>(emptyMap())
    // T9.1: worktree-scripts snapshots and change-request replies, per session (the inspector reads both).
    private val worktreeScriptsState = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    private val changeRequestsState = MutableStateFlow<Map<String, ChangeRequestReading>>(emptyMap())
    // L5: the (sessionId, path) pairs this client asked for; a git-diff-file reply for anything
    // else is dropped (a server cannot fill the card with hunks nobody requested).
    private val requestedGitFileDiffs = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val errorsFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    // T6.7: the server's own error text, cleaned, apart from the client's words (TetherClient.serverErrors).
    private val serverErrorsFlow = MutableSharedFlow<ServerErrorText>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val configuredState = MutableStateFlow(false)
    private val signedOutReasonState = MutableStateFlow<SignedOutReason?>(null)
    private val serverUrlState = MutableStateFlow<String?>(null)
    private val nodesState = MutableStateFlow<List<NodeSummary>>(emptyList())
    private val nodeResultState = MutableStateFlow<NodeActionResult?>(null)
    private val eventLogState = MutableStateFlow(EventLog())

    override val nodes: StateFlow<List<NodeSummary>> = nodesState
    override val eventLog: StateFlow<EventLog> = eventLogState
    override val nodeResult: StateFlow<NodeActionResult?> = nodeResultState
    // T10.3: the console's own protocol version (its `ready`), for the Nodes panel's skew warning.
    private val serverProtocolVersionState = MutableStateFlow<Int?>(null)
    override val serverProtocolVersion: StateFlow<Int?> = serverProtocolVersionState
    override val signedOutReason: StateFlow<SignedOutReason?> = signedOutReasonState
    override val serverUrl: StateFlow<String?> = serverUrlState
    override val connection: StateFlow<ConnectionState> = connectionState
    override val sessions: StateFlow<List<AgentSession>> = sessionsState
    override val providers: StateFlow<List<ProviderInfo>> = providersState
    override val providerCatalog: StateFlow<List<ProviderCatalogEntry>> = providerCatalogState
    override val providerCatalogLive: StateFlow<Boolean> = providerCatalogLiveState
    override val workspaceRoot: StateFlow<String?> = workspaceRootState
    override val hiddenAgentSessionCount: StateFlow<Int?> = hiddenAgentSessionCountState
    override val projections: StateFlow<Map<String, SessionProjection>> = sessionStore.projections
    override val projectionTrees: StateFlow<Map<String, JsObj>> = sessionStore.trees
    override val histories: StateFlow<List<HistorySession>> = historiesState
    override val directories: StateFlow<DirectoryListing?> = directoriesState
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = sessionControlsState
    override val codexControls: StateFlow<Map<String, ProviderControlsState<CodexSnapshot>>> = codexControlsState
    override val opencodeControls: StateFlow<Map<String, ProviderControlsState<OpencodeSnapshot>>> = opencodeControlsState
    override val gitFileDiffs: StateFlow<Map<String, Map<String, ServerMessage.GitDiffFile>>> = gitFileDiffsState
    override val worktreeDiffs: StateFlow<Map<String, JsonObject?>> = worktreeDiffsState
    override val worktreeScripts: StateFlow<Map<String, JsonObject>> = worktreeScriptsState
    override val changeRequests: StateFlow<Map<String, ChangeRequestReading>> = changeRequestsState
    override val errors: SharedFlow<String> = errorsFlow
    override val serverErrors: SharedFlow<ServerErrorText> = serverErrorsFlow

    // T6.7 r3: per session, the turn whose interrupt the server reported failed (TetherClient.failedInterrupts).
    private val failedInterruptsState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val failedInterrupts: StateFlow<Map<String, String>> = failedInterruptsState
    override val configured: StateFlow<Boolean> = configuredState
    override val trimmedBefore: StateFlow<Map<String, Int>> = sessionStore.trimmedBefore

    private val liveSessionsState = MutableStateFlow<Set<String>>(emptySet())
    private val decidedState = MutableStateFlow<Set<String>>(emptySet())
    private val unconfirmedState = MutableStateFlow<Set<String>>(emptySet())
    private val consentOriginState = MutableStateFlow<String?>(null)
    override val unconfirmedRequests: StateFlow<Set<String>> = unconfirmedState
    override val consentOrigin: StateFlow<String?> = consentOriginState
    override val liveSessions: StateFlow<Set<String>> = liveSessionsState
    override val decidedRequests: StateFlow<Set<String>> = decidedState

    // T13.2 (SYNC_DESIGN §4.1): the freshness inputs no other flow carries. Written under [lock].
    // [attachedState] mirrors [attachedThisEpoch]; [savedCopiesState] = sessions with a copy in the
    // mirror (their persisted last_verified_at); [verifiedAtState] = this process's own verification
    // times (a snapshot that made a session live, or the moment a live session stopped being live).
    private val attachedState = MutableStateFlow<Set<String>>(emptySet())
    private val savedCopiesState = MutableStateFlow<Map<String, Long?>>(emptyMap())
    private val verifiedAtState = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun freshnessInputs() = com.tether.app.client.sync.FreshnessRules.Inputs(
        connected = connectionState.value == ConnectionState.Connected,
        listed = sessionsState.value.map { it.id },
        trees = sessionStore.trees.value,
        trimmedBefore = sessionStore.trimmedBefore.value,
        saved = savedCopiesState.value,
        attached = attachedState.value,
        live = liveSessionsState.value,
        verifiedAt = verifiedAtState.value,
    )

    /** T13.2 r3: this client derives [syncStates], so a session without an entry is never live. */
    override val reportsFreshness: Boolean = true

    /**
     * Derived, not stored: recomputed whenever any input changes. The consent gate never reads it
     * (it re-checks [liveThisEpoch] under the lock), so the one-dispatch lag of this derivation can
     * only delay an indicator, never let a decision through.
     */
    override val syncStates: StateFlow<Map<String, SessionSync>> = kotlinx.coroutines.flow.combine(
        listOf<kotlinx.coroutines.flow.Flow<Any?>>(
            connectionState, sessionsState, sessionStore.trees, sessionStore.trimmedBefore,
            savedCopiesState, attachedState, liveSessionsState, verifiedAtState,
        ),
    ) { com.tether.app.client.sync.FreshnessRules.derive(freshnessInputs()) }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.tether.app.client.sync.FreshnessRules.derive(freshnessInputs()))

    /** T13.2: [attachedThisEpoch] changed. Caller holds [lock]. */
    private fun publishAttachedLocked() {
        attachedState.value = attachedThisEpoch.toSet()
    }

    // T5.1 sidebar sync (SidebarSync.kt).
    private val sidebarSync = SidebarSync()
    override val historiesByCwd: StateFlow<Map<String, List<HistorySession>>> = sidebarSync.historiesByCwd
    override val sessionOrders: StateFlow<Map<String, List<String>>> = sidebarSync.sessionOrders
    override val remoteSeen: StateFlow<Map<String, Long>> = sidebarSync.remoteSeen
    override val serverSettings: StateFlow<ServerMessage.ServerSettings?> = sidebarSync.serverSettings
    override val advancedSettings: StateFlow<ServerMessage.AdvancedSettings?> = sidebarSync.advancedSettings

    // T5.2: the unicast `created` reply to this socket's own create/resume (use-tether.ts:291).
    private val createdState = MutableStateFlow<CreatedReply?>(null)
    private var createdSeq = 0L
    override val createdSessions: StateFlow<CreatedReply?> = createdState

    // ta-8cv: the web's `createError` (use-tether.ts:300), and the live socket's epoch, published.
    private val createErrorsState = MutableStateFlow<CreateErrorReply?>(null)
    private var createErrorSeq = 0L
    override val createErrors: StateFlow<CreateErrorReply?> = createErrorsState
    private val linkEpochState = MutableStateFlow(0L)
    override val linkEpoch: StateFlow<Long> = linkEpochState

    // ta-8cv r2 (security F2): the same replies, never conflated away, and the recent create answers
    // by requestId (written under [lock] with the frame, before its socket can be reported gone).
    private val createdRepliesFlow = MutableSharedFlow<CreatedReply>(
        extraBufferCapacity = CREATE_REPLIES_BUFFERED,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val createdReplies: Flow<CreatedReply> = createdRepliesFlow
    private val createErrorRepliesFlow = MutableSharedFlow<CreateErrorReply>(
        extraBufferCapacity = CREATE_REPLIES_BUFFERED,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val createErrorReplies: Flow<CreateErrorReply> = createErrorRepliesFlow

    // ta-23f: every `worktree-source` of the live socket, stamped with its epoch (the composer matches its own).
    private val worktreeSourcesFlow = MutableSharedFlow<WorktreeSourceReply>(
        extraBufferCapacity = CREATE_REPLIES_BUFFERED,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val worktreeSources: Flow<WorktreeSourceReply> = worktreeSourcesFlow
    /** Guarded by [lock]; bounded (oldest dropped), emptied with the other per-server views. */
    private val createRepliesByRequest = LinkedHashMap<String, CreateReplyRecord>()

    // ta-2ew (R1): an answer recorded on another server is never returned, even in the window
    // between a sign-in switch moving the URL and clearServerViews emptying the record; r2: nor
    // while signed out.
    override fun createReply(requestId: String): CreateReplyRecord? = synchronized(lock) {
        createRepliesByRequest[requestId]?.takeIf { record -> record.origin?.let { originStandingLocked(it) } == OriginStanding.Configured }
    }

    override fun originStanding(origin: String): OriginStanding = synchronized(lock) { originStandingLocked(origin) }

    /**
     * ta-2ew r2 (security P4-1): a logout keeps the URL (the login screen's prefill), so the URL alone
     * does not make [origin] the server in force: a live credential does too. logout(), stop() and a
     * rejected credential null [credentialValue] under this lock; a sign-in sets it with the URL.
     * Caller holds [lock].
     */
    private fun originStandingLocked(origin: String): OriginStanding = when {
        credentialValue == null -> OriginStanding.SignedOut
        currentOriginLocked() == origin -> OriginStanding.Configured
        else -> OriginStanding.OtherServer
    }

    /** Caller holds [lock]. */
    private fun recordCreateReplyLocked(requestId: String, record: CreateReplyRecord) {
        createRepliesByRequest.remove(requestId)
        createRepliesByRequest[requestId] = record
        while (createRepliesByRequest.size > CREATE_REPLIES_REMEMBERED) createRepliesByRequest.remove(createRepliesByRequest.keys.first())
    }

    private val storedSettingsLoadedState = MutableStateFlow(false)
    override val storedSettingsLoaded: StateFlow<Boolean> = storedSettingsLoadedState

    // T5.3 search (SearchSync.kt): both searches' state, on the current handshaken socket.
    private val searchSync = SearchSync { message ->
        sendFrame(message).also { sent -> if (!sent) emitError(NodeRegistryRules.NOT_SENT_MESSAGE) }
    }
    override val searchResults: StateFlow<SearchResults> = searchSync.searchResults
    override val globalSearchResults: StateFlow<GlobalSearchResults> = searchSync.globalSearchResults

    // T15.1 v131 Overview feed (OverviewSync.kt). Every call is made under [lock]; its frames go out
    // on the live, handshaken socket of that moment only ([sendOverviewLocked]).
    private val overviewSync = OverviewSync()
    override val overview: StateFlow<com.tether.app.protocol.overview.OverviewClientState> = overviewSync.state

    init {
        // One writer for all three, in this order: whoever sees storedSettingsLoaded sees the stored
        // server URL and sign-in state with it (T4.4 cold-start deep links).
        scope.launch {
            // ta-exi: never fatal (this runs on the app scope: an escape is a crash) and never
            // dead. A store that fails to read reads as signed out: the login screen, never a
            // signed-in UI the store can no longer back, and never a UI still waiting for the
            // first read. The server URL keeps its last stored value (a prefill, not a sign-in).
            // Then the collector resubscribes with a backoff, so a store that reads again
            // brings the stored sign-in back.
            while (true) {
                try {
                    combine(settings.baseUrl, settings.credential) { base, credential ->
                        base to (!base.isNullOrEmpty() && credential != null)
                    }.collect { (base, configured) ->
                        settingsBackoff.reset()
                        serverUrlState.value = base
                        configuredState.value = configured
                        storedSettingsLoadedState.value = true
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    configuredState.value = false
                    storedSettingsLoadedState.value = true
                }
                delay(settingsBackoff.next())
            }
        }
    }

    // ------------------------------------------------------------------
    // Auth / lifecycle
    // ------------------------------------------------------------------

    override suspend fun login(baseUrl: String, password: String, username: String): LoginResult = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
            ?: return@withContext LoginResult.Unreachable("That server URL is not valid.")
        if (blockedBeforeConnect(normalized)) return@withContext LoginResult.LocalNetworkBlocked

        // 1. Cheapest pre-flight: /healthz carries the native window unauthenticated.
        val health = try {
            probeHealth(normalized)
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext LoginResult.LocalNetworkBlocked
            return@withContext LoginResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        health.incompatibility()?.let { return@withContext LoginResult.VersionMismatch(it) }

        // 2. Password login, the same JSON body the web's login form posts. A
        //    server without a configured username ignores the field.
        val body = buildJsonObject {
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
        }.toString()
        val loginResponse = try {
            authHttp.newCall(
                Request.Builder()
                    .url(normalized.resolve("/api/auth/login")!!)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext LoginResult.LocalNetworkBlocked
            return@withContext LoginResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        loginResponse.use { response ->
            when (response.code) {
                200 -> {
                    // ta-96z: either session cookie name (`__Host-` preferred), stored with the
                    // name it came under and sent back under that name alone.
                    val cookie = when (val issued = sessionCookieFrom(response.request.url, response.headers("set-cookie"))) {
                        is SessionCookieResult.Found -> issued.cookie
                        SessionCookieResult.Missing ->
                            return@withContext LoginResult.Unreachable("The server did not return a session cookie.")
                        SessionCookieResult.Ambiguous ->
                            return@withContext LoginResult.Unreachable("The server returned conflicting session cookies.")
                    }
                    // ta-jt9 I-3: the server has minted a session: adopt it whatever happens
                    // to the caller, or it is never stored nor revoked.
                    withContext(NonCancellable) { adoptCredential(normalized, cookie) }
                    return@withContext LoginResult.Success
                }
                // Tether's own refusal is JSON `{error}` and never carries a challenge
                // header. Anything else is something in front of Tether (basic auth, a
                // proxy, an SSO gateway: Tether's README tells SSO setups to keep
                // /api/auth/login gated while /healthz and /api/auth/session are exempt)
                // and must not read as a wrong password (ta-s4r).
                401 -> {
                    val obj = parseJsonObject(response)
                    val error = obj.stringField("error")
                    return@withContext if (response.header("WWW-Authenticate") == null && error != null) {
                        LoginResult.BadPassword(error)
                    } else {
                        gatewayRefusal(response)
                    }
                }
                // lib/login-guard.mjs: failed attempts only, so a correct password
                // is never throttled.
                429 -> return@withContext LoginResult.RateLimited(
                    parseJsonField(response, "error") ?: "Too many attempts. Try again in a few minutes.",
                )
                403 -> {
                    val obj = parseJsonObject(response)
                    val error = obj.stringField("error")
                    return@withContext when {
                        obj.stringField("code") == "password_login_disabled" ->
                            LoginResult.PasswordDisabled(error ?: "Password sign-in is turned off for this console.")
                        // Not Tether's JSON: a gateway's refusal, reported as one.
                        error == null -> gatewayRefusal(response)
                        else -> LoginResult.Unreachable(error)
                    }
                }
                in 300..399 -> return@withContext LoginResult.Unreachable(REDIRECT_MESSAGE)
                else -> return@withContext LoginResult.Unreachable("login returned HTTP ${response.code}")
            }
        }
    }

    /**
     * T10.5 (use-login-flow.ts runPasskeyCeremony): three uncredentialed steps, each through [authHttp]
     * (never a redirect) to the server typed here and nowhere else.
     *  0. r2 (security F1): an https server, or nothing is sent at all.
     *  1. /healthz: the native window, as for a password.
     *  2. `POST /api/auth/passkey/login/options {}` (JSON, no Origin: a native caller, so the server
     *     issues the legacy cookie name the app reads) -> `{ challengeId, options }`. The options go to
     *     the authenticator ONLY when their rpId is this server's host ([PasskeyRules.rpIdMatches]).
     *  3. `POST /api/auth/passkey/login/verify {challengeId, response}` -> 200 + the session cookie,
     *     adopted exactly like a password sign-in's (sealed by the store, sent with the console Origin).
     * Nothing is retried: a challenge is single use, and a dismissed prompt sends nothing more.
     */
    override suspend fun passkeyLogin(baseUrl: String, passkeys: PasskeyAuthenticator): LoginResult = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
            ?: return@withContext LoginResult.Unreachable("That server URL is not valid.")
        // r2 (security F1): https only, decided before anything is sent (see Passkeys.kt).
        if (!PasskeyRules.ceremonyAllowed(normalized)) return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.NEEDS_HTTPS)
        if (blockedBeforeConnect(normalized)) return@withContext LoginResult.LocalNetworkBlocked
        if (!passkeys.available) return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.UNSUPPORTED)

        val health = try {
            probeHealth(normalized)
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext LoginResult.LocalNetworkBlocked
            return@withContext LoginResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        health.incompatibility()?.let { return@withContext LoginResult.VersionMismatch(it) }

        // 2. The challenge.
        val challenge = try {
            authHttp.newCall(
                Request.Builder()
                    .url(normalized.resolve(PASSKEY_LOGIN_OPTIONS_PATH)!!)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { response ->
                when (response.code) {
                    200 -> readCappedJson(response)?.let { PasskeyRules.challenge(it, PasskeyPurpose.Login) }
                        ?: return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.UNREADABLE)
                    else -> return@withContext passkeyRefusal(response)
                }
            }
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext LoginResult.LocalNetworkBlocked
            return@withContext LoginResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        // The anti-relay guard: only this server's own relying party is ever asked for.
        if (!PasskeyRules.rpIdMatches(challenge.rpId, normalized)) {
            return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.WRONG_RP)
        }

        // The ceremony (the authenticator moves to the main thread itself).
        val answer = when (val ceremony = passkeys.authenticate(challenge.optionsJson())) {
            is PasskeyCeremony.Done -> PasskeyRules.response(ceremony)
                ?: return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.UNREADABLE_ANSWER)
            PasskeyCeremony.Dismissed -> return@withContext LoginResult.PasskeyDismissed
            PasskeyCeremony.NoCredential -> return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.NO_CREDENTIAL)
            PasskeyCeremony.Unsupported -> return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.UNSUPPORTED)
            PasskeyCeremony.Duplicate, PasskeyCeremony.Failed -> return@withContext LoginResult.PasskeyFailed(PasskeyLoginCopy.FAILED)
        }

        // 3. The signed answer.
        val body = buildJsonObject {
            put("challengeId", JsonPrimitive(challenge.challengeId))
            put("response", answer)
        }.toString()
        try {
            authHttp.newCall(
                Request.Builder()
                    .url(normalized.resolve(PASSKEY_LOGIN_VERIFY_PATH)!!)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { response ->
                if (response.code != 200) return@withContext passkeyRefusal(response)
                val cookie = when (val issued = sessionCookieFrom(response.request.url, response.headers("set-cookie"))) {
                    is SessionCookieResult.Found -> issued.cookie
                    SessionCookieResult.Missing -> return@withContext LoginResult.Unreachable("The server did not return a session cookie.")
                    SessionCookieResult.Ambiguous -> return@withContext LoginResult.Unreachable("The server returned conflicting session cookies.")
                }
                // ta-jt9 I-3, as for a password: a minted session is adopted whatever happens to the caller.
                withContext(NonCancellable) { adoptCredential(normalized, cookie) }
                LoginResult.Success
            }
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext LoginResult.LocalNetworkBlocked
            LoginResult.Unreachable(e.message ?: "The server could not be reached.")
        }
    }

    /**
     * A passkey route that did not answer 200: Tether's own `{error}` (400 "No passkey is registered on
     * this console." / needs HTTPS, 401 "That passkey could not be verified.", 429) as the web shows it;
     * a 401/403 that is not Tether's is a gateway in front of it (T1.4's rule); a redirect is not followed.
     */
    private fun passkeyRefusal(response: Response): LoginResult {
        if (response.code in 300..399) return LoginResult.Unreachable(REDIRECT_MESSAGE)
        val obj = readCappedJson(response)
        val error = obj.stringField("error")?.let { TextCut.cut(it, DeviceSecurityJson.MAX_ERROR) }?.takeIf { it.isNotBlank() }
        return when {
            (response.code == 401 || response.code == 403) && (error == null || response.header("WWW-Authenticate") != null) -> gatewayRefusal(response)
            response.code == 429 -> LoginResult.RateLimited(error ?: "Too many attempts. Try again in a few minutes.")
            error != null && response.code in 400..499 -> LoginResult.PasskeyFailed(error)
            else -> LoginResult.Unreachable("passkey sign-in returned HTTP ${response.code}")
        }
    }

    /** A JSON body read up to [PASSKEY_BODY_CAP] bytes (nothing past it is buffered) and no deeper than the frame cap; null otherwise. */
    private fun readCappedJson(response: Response): JsonObject? = try {
        val source = response.body.source()
        if (response.body.contentLength() > PASSKEY_BODY_CAP || source.request(PASSKEY_BODY_CAP + 1)) {
            null
        } else {
            val text = source.buffer.readUtf8()
            if (ServerMessage.nestsDeeperThan(text, ServerMessage.MAX_FRAME_DEPTH)) null
            else com.tether.app.protocol.TetherJson.parseToJsonElement(text) as? JsonObject
        }
    } catch (_: Exception) {
        null
    }

    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
            ?: return@withContext PairResult.Unreachable("That server URL is not valid.")
        if (blockedBeforeConnect(normalized)) return@withContext PairResult.LocalNetworkBlocked
        // Trim only. Case folding, separator stripping and U→V are the server's
        // job (lib/device-tokens.mjs normalizePairingCode) — a second copy here
        // could only drift out of agreement with it.
        val typedCode = code.trim()
        if (typedCode.isEmpty()) {
            return@withContext PairResult.Rejected("Enter the pairing code shown in your browser.")
        }

        // 1. /healthz doubles as the capability probe: `pairing: true` is how a
        //    native client learns this server can pair at all (it is deliberately
        //    NOT part of PROTOCOL_VERSION).
        val health = try {
            probeHealth(normalized)
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext PairResult.LocalNetworkBlocked
            return@withContext PairResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        health.incompatibility()?.let { return@withContext PairResult.VersionMismatch(it) }
        if (!health.pairing) {
            return@withContext PairResult.NotSupported(
                "This server does not support device pairing. Update the server, or connect with the password.",
            )
        }

        // 2. Claim the code. This endpoint needs NO prior credential — the
        //    short-lived single-use code IS the credential.
        val body = buildJsonObject {
            put("code", JsonPrimitive(typedCode))
            put("label", JsonPrimitive(label))
        }.toString()
        val claimResponse = try {
            authHttp.newCall(
                Request.Builder()
                    .url(normalized.resolve("/api/devices/claim")!!)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            if (blockedAfterFailure(normalized, e)) return@withContext PairResult.LocalNetworkBlocked
            return@withContext PairResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        claimResponse.use { response ->
            when (response.code) {
                200 -> {
                    val token = parseJsonField(response, "token")
                    if (token.isNullOrEmpty()) {
                        return@withContext PairResult.Unreachable("The server did not return a device token.")
                    }
                    // ta-jt9 I-3: the single-use code is spent: a token not adopted now is orphaned.
                    withContext(NonCancellable) { adoptCredential(normalized, Credential.DeviceToken(token)) }
                    return@withContext PairResult.Success
                }
                // One message for unknown / expired / already-claimed: the server
                // deliberately does not distinguish them, so neither do we.
                401 -> return@withContext PairResult.Rejected(
                    parseJsonField(response, "error") ?: "That pairing code is not valid or has expired.",
                )
                429 -> return@withContext PairResult.RateLimited(
                    parseJsonField(response, "error") ?: "Too many pairing attempts. Try again in a few minutes.",
                )
                // DeviceTokenError (e.g. the device limit): the server's message says why.
                409 -> return@withContext PairResult.Rejected(
                    parseJsonField(response, "error") ?: "The server could not pair this device.",
                )
                in 300..399 -> return@withContext PairResult.Unreachable(REDIRECT_MESSAGE)
                else -> return@withContext PairResult.Unreachable("claim returned HTTP ${response.code}")
            }
        }
    }

    /** Persist a freshly-obtained credential and (re)start the connection loop. */
    private suspend fun adoptCredential(base: HttpUrl, credential: Credential) {
        // ta-jt9 L-A2: the boot purge decides on the store as the boot found it, so no sign-in
        // may write the store (or bind the mirror) before it has decided and wiped.
        if (!awaitBootPurge()) {
            // It failed or is overdue, and this sign-in is about to change the store it decides
            // on: a late decision could then keep the previous sign-in's copy for the next boot.
            // So that copy is shredded now (the mirror is off for this process), off this
            // thread but BEFORE setServer (ta-jt9 L-2): a death right after the new credential
            // is sealed must not find the old copy readable, with a boot that reads Present.
            // Bounded: the key file goes first, so a shred stuck in the Keystore is unreadable.
            unbindMirrorForWipe()?.let { m ->
                val shred = scope.async(Dispatchers.IO) { runCatching { m.wipe() } }
                withTimeoutOrNull(mirrorBindTimeoutMs) { shred.await() }
            }
        }
        // ta-jt9 L-1: a sign-out's store clear still in flight would land after setServer and
        // delete this sign-in's credential. Bounded: a clear that never lands leaves it as before.
        withTimeoutOrNull(signOutClearWaitMs) { signOutClearsInFlight.first { it == 0 } }
        // Read BEFORE the URL moves: the server that unsent input filed before
        // the store was bound (a fresh process) was written for.
        val configuredBefore = try {
            settings.baseUrl.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val stored = try {
            settings.setServer(base.toString().trimEnd('/'), credential)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The store could not be written: this sign-in lives in memory for
            // this process only. The store deletes the old credential before it
            // moves the URL, so disk never pairs the new URL with the old one.
            false
        }
        val previous: WebSocket?
        val previousWasOpen: Boolean
        val switch: OriginSwitch?
        synchronized(lock) {
            // Whatever socket is still bound belongs to the PREVIOUS sign-in
            // (possibly another server): let it go now, or connectNow() would
            // keep using it and a node-add would hand a peer bearer to the old
            // server. Its listener is retired, so none of its late frames land.
            previousWasOpen = socketOpen
            previous = detachSocketLocked()
            cancelTimersLocked()
            // An auth probe of the previous sign-in may still be in flight: it
            // is now stale (does nothing when it returns) and no longer owns
            // the connect slot, so this sign-in connects at once.
            endConnectAttemptsLocked()
            baseUrlValue = base
            credentialValue = credential
            // ta-jt9 round 3: the store holds the new credential now, so the forgotten one is
            // not kept in memory for the life of the process (nor matched later).
            if (stored) forgottenCredential = null
            stopped = false
            // A fresh login is a user action: it also clears a version halt.
            versionHalt = null
            backoff.reset()
            // Same origin (a re-login): nothing changes. Another origin: the
            // unsent input and every per-server state of the previous one are
            // set aside / cleared HERE, in the same critical section that
            // moves the URL, so no connection to the new server can see them.
            switch = followServerLocked(fallbackOwner = serverOrigin(configuredBefore), adoptUnbound = false)
        }
        if (switch != null) raceHook?.invoke(RacePoint.ServerMoved, null)
        if (previousWasOpen) previous?.close(1000, "signed in again") else previous?.cancel()
        signedOutReasonState.value = null
        // A new sign-in (possibly to another server): its hello brings its own list.
        clearSignInViews()
        switch?.let(::completeOriginSwitch)
        // T13.1 L1 test seam: the server just switched and the mirror is not re-bound yet.
        if (switch != null) raceHook?.invoke(RacePoint.OriginSwitched, null)
        // The new origin's own unsent input, before the connection comes up.
        bindPendingToCurrentServer()
        start()
        raceHook?.invoke(RacePoint.SignInStarted, null)
        // Connect if start() has not got there yet, but never probe: the lock
        // above let every earlier socket go, so an open one is this sign-in's
        // own, possibly still before its ready, and a ping would go out ahead
        // of the hello (ta-cdh).
        reconnectIfIdle(probeOpen = false)
    }

    /** The canonical origin of the server in force. Caller holds [lock]. */
    private fun currentOriginLocked(): String? = baseUrlValue?.let { serverOrigin(it.toString()) }

    /** A store set aside for [SetAsideStore]'s origin; [cleared] are its tombstones. */
    private class SetAsideStore(val store: PendingStore, val cleared: List<String>)

    /**
     * What an origin switch leaves to do outside the lock: persist [write] (the
     * set-aside store, into its own origin's slot) and tell the user.
     */
    private class OriginSwitch(val write: SetAsideWrite?, val discarded: Int, val target: String)

    private class SetAsideWrite(
        val origin: String,
        val version: Long,
        val wipe: Long,
        val store: PendingStore,
        val cleared: List<String>,
        // The store was not merged with its origin's slot yet: merge on write.
        val mergeWithDisk: Boolean,
    )

    /**
     * Make the pending store and the per-server state belong to the server in
     * force. Caller holds [lock]; returns the follow-up for
     * [completeOriginSwitch], or null when nothing changed.
     *
     * - Same origin: nothing changes (T1.3 unchanged: a re-login keeps it all).
     * - Unbound store ([pendingOrigin] null): it belongs to [fallbackOwner], the
     *   server configured when it was filed. With none known, [adoptUnbound]
     *   decides: start() binds a fresh process to its configured server
     *   (T1.3's cold-start merge); a sign-in DISCARDS it (with a notice)
     *   instead of guessing: it was never proven to be the new server's.
     * - Another origin: the store is set aside under its owner (never
     *   replayed here), and subscriptions, cursors, reconciled sessions,
     *   tombstones and attached sessions are cleared. [pendingLoaded] drops,
     *   so nothing drains until the new origin's slot is merged in.
     */
    private fun followServerLocked(fallbackOwner: String?, adoptUnbound: Boolean): OriginSwitch? {
        val target = currentOriginLocked() ?: return null
        val owner = pendingOrigin ?: fallbackOwner
        if (owner == target || (owner == null && adoptUnbound)) {
            pendingOrigin = target
            return null
        }
        val store = PendingInput.resetInFlight(pendingStore)
        val cleared = clearedKeys.toList()
        var write: SetAsideWrite? = null
        var discarded = 0
        if (owner != null) {
            if (pendingLoaded || store.records.isNotEmpty()) {
                val prior = setAside[owner]
                val keptCleared = ((prior?.cleared ?: emptyList()) + cleared).distinct().takeLast(PendingInput.MAX_TOMBSTONES)
                val kept = if (prior == null) store else PendingInput.mergeStores(store, prior.store, keptCleared)
                setAside[owner] = SetAsideStore(kept, keptCleared)
                pendingVersion++
                write = SetAsideWrite(owner, pendingVersion, pendingWipe, kept, keptCleared, mergeWithDisk = !pendingLoaded)
            }
        } else {
            discarded = store.records.size
        }
        pendingStore = PendingInput.emptyStore()
        clearedKeys = LinkedHashSet()
        pendingLoaded = false
        pendingOrigin = target
        clearServerStateLocked()
        // The mirror is still bound to the origin just left: nothing may hydrate from it or
        // write to it until the new origin's bind (bindMirrorToCurrentServer) (L1).
        mirrorOrigin = null
        mirrorGeneration++
        return OriginSwitch(write, discarded, target)
    }

    /** Everything that is per server and not the pending store. Caller holds [lock]. */
    private fun clearServerStateLocked() {
        subscribed.clear()
        tracker.clear()
        reconciledSessions.clear()
        attachedThisEpoch.clear()
        publishAttachedLocked()
        clearLiveLocked()
        verifiedAtState.value = emptyMap()
        decidedState.value = emptySet()
        unconfirmedState.value = emptySet()
        consentOriginState.value = null
        clearMirrorStateLocked()
    }

    /** T6.3: [sessionId] is (or is no longer) confirmed on the current socket. Caller holds [lock]. */
    private fun setLiveLocked(sessionId: String, live: Boolean) {
        val changed = if (live) liveThisEpoch.add(sessionId) else liveThisEpoch.remove(sessionId)
        if (!changed) return
        // T13.2: confirmed now, or current until now (it stops being live here).
        verifiedAtState.value = verifiedAtState.value + (sessionId to clock())
        liveSessionsState.value = liveThisEpoch.toSet()
    }

    /** T6.3: nothing is live (the socket, its epoch or the server went). Caller holds [lock]. */
    private fun clearLiveLocked() {
        // T7.2: a provider-control snapshot names one engine read on one socket; an action is never
        // bound to a snapshot from a socket that went.
        if (codexControlsState.value.isNotEmpty()) codexControlsState.value = emptyMap()
        if (opencodeControlsState.value.isNotEmpty()) opencodeControlsState.value = emptyMap()
        if (liveThisEpoch.isEmpty()) return
        // T13.2: a live copy was current as of the last frame the socket delivered (every event
        // before it arrived, in order), not as of whenever the drop was noticed.
        val until = lastInboundAt.takeIf { it > 0 }?.coerceAtMost(clock()) ?: clock()
        verifiedAtState.value = verifiedAtState.value + liveThisEpoch.associateWith { until }
        liveThisEpoch.clear()
        liveSessionsState.value = emptySet()
    }

    /** The in-memory T13.1 hydration state: per server, like the cursors. Caller holds [lock]. */
    private fun clearMirrorStateLocked() {
        seededFromMirror.clear()
        lastOpenedAt.clear()
        sessionStore.clearMirrorState()
        savedCopiesState.value = emptyMap()
    }

    /** T13.2: [sessionId]'s saved copy is gone (dropped, corrupt, or unreadable). Caller holds [lock]. */
    private fun forgetSavedCopyLocked(sessionId: String) {
        if (savedCopiesState.value.containsKey(sessionId)) savedCopiesState.value = savedCopiesState.value - sessionId
    }

    /** The published per-server views: another server's sessions must never show. */
    private fun clearServerViews() {
        sessionsState.value = emptyList()
        synchronized(lock) { listedSessionIds.clear() }
        providersState.value = emptyList()
        // ta-895: the catalog, its stamp and its liveness go together, under the lock.
        synchronized(lock) {
            providerCatalogState.value = emptyList()
            catalogEpoch = -1L
            catalogOrigin = null
            providerCatalogLiveState.value = false
            refreshThrottle.clear()
        }
        workspaceRootState.value = null
        hiddenAgentSessionCountState.value = null
        synchronized(lock) { sessionStore.clearViews() }
        historiesState.value = emptyList()
        directoriesState.value = null
        sessionControlsState.value = emptyMap()
        gitFileDiffsState.value = emptyMap()
        worktreeDiffsState.value = emptyMap()
        worktreeScriptsState.value = emptyMap()
        changeRequestsState.value = emptyMap()
        failedInterruptsState.value = emptyMap()
        requestedGitFileDiffs.clear()
        sidebarSync.clear()
        createdState.value = null
        createErrorsState.value = null
        synchronized(lock) { createRepliesByRequest.clear() }
        searchSync.clear()
        // T15.1: another server's overview must never show, and its subscription does not carry over.
        synchronized(lock) { overviewSync.clear() }
    }

    /** The outside-the-lock half of an origin switch: views, the set-aside write, the notice. */
    private fun completeOriginSwitch(switch: OriginSwitch) {
        clearServerViews()
        switch.write?.let { write ->
            persistSetAside(write)
            val count = write.store.records.size
            if (count > 0) {
                emitError(
                    "$count unsent message${if (count == 1) " was" else "s were"} not sent to this server. " +
                        "${if (count == 1) "It was" else "They were"} kept for ${displayHost(write.origin, versus = switch.target)} " +
                        "and will only go there if you sign in to it again.",
                )
            }
        }
        if (switch.discarded > 0) {
            emitError(
                "${switch.discarded} unsent message${if (switch.discarded == 1) " was" else "s were"} discarded: " +
                    "the server ${if (switch.discarded == 1) "it was" else "they were"} written for is not known.",
            )
        }
    }


    /**
     * Merge the persisted slot of the current origin into the pending store
     * (once per binding): what this process holds wins, then a store set aside
     * earlier in this process (exact), then the disk copy (restored records
     * count as possibly transmitted, T1.3). A no-op when already loaded, when
     * no server is configured, or when the server changed while reading (the
     * newer binding loads its own). Checks the unattributed 0.6.0 slot once.
     */
    private suspend fun bindPendingToCurrentServer() {
        bindMirrorToCurrentServer()
        val (target, checkUnattributed) = synchronized(lock) {
            val check = !unattributedChecked
            unattributedChecked = true
            val current = currentOriginLocked()
            (if (current == null || pendingLoaded || pendingOrigin != current) null else current) to check
        }
        // Checked once per binding until it is gone, with or without a server
        // configured: it can never be sent, so the user is told once (with the
        // first text, to retype it) and only THEN is it deleted. A payload that
        // cannot be fully read is kept (inert), never deleted unseen.
        if (checkUnattributed && !settleUnattributed()) {
            synchronized(lock) { unattributedChecked = false }
        }
        pruneExpiredSlots()
        val origin = target ?: return
        // A stop() whose disk wipe has not landed yet: what is there is dead.
        val raw = if (synchronized(lock) { wipeLanded != pendingWipe }) null else readQuietly { settings.readPendingInput(origin) }
        val expired = synchronized(lock) {
            if (pendingLoaded || pendingOrigin != origin || currentOriginLocked() != origin) return
            restorePendingLocked(raw, setAside.remove(origin))
        }
        if (expired.isNotEmpty()) emitError(undeliveredMessage(expired))
        persistPending()
        attachPendingSessionsThenDrain()
    }

    /**
     * The unattributed 0.6.0 slot: told, then deleted. True = settled (gone,
     * absent, or unreadable and kept for good); false = retry at the next
     * binding (nobody observed the notice yet, or the store failed).
     */
    private suspend fun settleUnattributed(): Boolean {
        val raw = try {
            settings.readUnattributedPendingInput()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } ?: return true
        val count = PendingInput.persistedRecordCount(raw) ?: return true
        val records = PendingInput.fromPersisted(raw).records
        if (records.size != count) return true
        if (records.isNotEmpty() && !deliverNotice(unattributedMessage(records))) return false
        return try {
            settings.removeUnattributedPendingInput()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // shown again by the next binding: a repeat, never a loss
        }
    }

    /**
     * A one-time notice whose data is deleted right after: emitted only when
     * someone observes [errors] (the flow has no replay, so an emission with no
     * collector is lost). False = not delivered: keep the data and try again
     * later, e.g. on a start with the UI up rather than a headless one. The
     * ViewModel subscribes before it starts the client, so on an app start this
     * delivers at once.
     */
    private fun deliverNotice(message: String): Boolean {
        if (errorsFlow.subscriptionCount.value == 0) return false
        return errorsFlow.tryEmit(message)
    }

    private fun unattributedMessage(records: List<PendingRecord>): String {
        val first = records.first().text
        val preview = if (first.length > 120) first.take(120) + "…" else first
        val n = records.size
        return "$n unsent message${if (n == 1) "" else "s"} saved by an earlier version could not be matched to a " +
            "server and ${if (n == 1) "was" else "were"} not sent. The first was: \"$preview\""
    }

    /**
     * Slots are never wiped by logout; this is what bounds them. A slot of any
     * origin other than the bound one (and with no store set aside in this
     * process) whose records have ALL expired by the T1.3 rules, or that holds
     * none, is deleted, with the undelivered notice for what it held. Only a
     * slot that was READ and fully UNDERSTOOD is ever deleted: a read error, a
     * payload of an unknown shape or version, or records dropped as malformed
     * keep it. A slot with records is deleted only once its notice was
     * delivered ([deliverNotice]). Runs on every binding (start / sign-in),
     * serialized with the pending writes.
     */
    private fun pruneExpiredSlots() {
        scope.launch {
            persistMutex.withLock {
                val origins = try {
                    settings.pendingInputOrigins()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withLock
                }
                for (origin in origins) {
                    if (synchronized(lock) { pruneSkippedLocked(origin) }) continue
                    val raw = try {
                        settings.readPendingInput(origin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        continue // unreadable: kept
                    } ?: continue
                    val count = PendingInput.persistedRecordCount(raw) ?: continue // not understood: kept
                    val store = PendingInput.fromPersisted(raw)
                    if (store.records.size != count) continue // malformed records: kept
                    val expiry = PendingInput.expireRecords(store, clock())
                    if (expiry.store.records.isNotEmpty()) continue
                    if (synchronized(lock) { pruneSkippedLocked(origin) }) continue
                    if (expiry.unsent.isNotEmpty()) {
                        val n = expiry.unsent.size
                        val first = expiry.unsent.first().text
                        val preview = if (first.length > 120) first.take(120) + "…" else first
                        val delivered = deliverNotice(
                            "$n unsent message${if (n == 1) "" else "s"} for ${displayHost(origin)} expired before you " +
                                "signed in there again and ${if (n == 1) "was" else "were"} dropped. " +
                                "The first was: \"$preview\"",
                        )
                        if (!delivered) continue // kept until someone can be told
                    }
                    try {
                        settings.removePendingInput(origin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        continue // told again next time: a repeat, never a loss
                    }
                }
            }
        }
    }

    /** The bound origin, a store set aside in memory, or a wipe in progress: not pruned. Caller holds [lock]. */
    private fun pruneSkippedLocked(origin: String): Boolean =
        origin == pendingOrigin || origin in setAside || wipeLanded != pendingWipe

    /** An unreadable store is "nothing there", never a failed start or sign-in. */
    private suspend fun readQuietly(read: suspend () -> String?): String? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * T13.1: bind the mirror to the origin in force (once per origin; a new origin's bind
     * deletes the previous origin's DB). Runs before the first connect of a binding, so the
     * mirror is open before any frame of that origin arrives.
     */
    private suspend fun bindMirrorToCurrentServer() {
        if (mirrorLink == null || !awaitBootPurge()) return
        val link = mirrorLink ?: return
        var generation = 0L
        val origin = synchronized(lock) {
            val current = currentOriginLocked() ?: return
            // No credential (signed out, revoked, or a cold start after either): the saved copy
            // of the last sign-in must not be shown or kept. Purge instead of binding (M1).
            if (credentialValue == null) null else {
                if (current == mirrorOrigin) return
                mirrorOrigin = current
                generation = ++mirrorGeneration
                current
            }
        }
        if (origin == null) {
            wipeMirror()
            return
        }
        // Bounded (M2): a writer that is stuck or dead must never hang a start or a sign-in.
        val index = withTimeoutOrNull(mirrorBindTimeoutMs) { link.mirror.bind(origin) }
        if (index == null) {
            // Unavailable (Keystore) or no answer in time: no mirror for this process. Unless a
            // wipe or a switch already superseded this bind (ta-hra L-2): its null says nothing
            // about the binding in force now.
            synchronized(lock) {
                if (mirrorGeneration != generation) return
                if (mirrorOrigin == origin) mirrorOrigin = null
                mirrorGeneration++
            }
            mirrorLink = null
            return
        }
        applyMirrorIndex(origin, generation, index)
    }

    /**
     * Cold start (§2.4): publish the mirrored session list at once (a live `ready` replaces
     * it) and seed [tracker] with the persisted cursors, so the ready re-attach asks for a delta
     * and a mirror at head gets the stateless reply. Projections are hydrated lazily
     * ([requestHydration]).
     */
    private fun applyMirrorIndex(origin: String, generation: Long, index: MirrorIndex) {
        val sessions = index.sessions.filter { !it.goneFromServer }.mapNotNull { MirrorLink.decodeSession(it.json) }
        synchronized(lock) {
            // Only into the binding that asked (ta-hra L-2): a late pre-wipe bind never publishes
            // the old sign-in's list or cursors into a new binding of the same origin.
            if (mirrorOrigin != origin || currentOriginLocked() != origin || mirrorGeneration != generation) return
            for ((sessionId, cursor) in index.cursors) {
                if (tracker.cursorFor(sessionId) != null) continue
                tracker.seed(sessionId, cursor)
                seededFromMirror.add(sessionId)
            }
            for (stored in index.sessions) stored.lastOpenedAt?.let { lastOpenedAt.putIfAbsent(stored.sessionId, it) }
            // T13.2: what offline reading can show before any hydration (§4.1 Saved vs NotDownloaded).
            if (index.saved.isNotEmpty()) savedCopiesState.value = index.saved + savedCopiesState.value
            if (sessionsState.value.isEmpty() && sessions.isNotEmpty()) {
                sessionsState.value = sessions.sortedByDescending { it.updatedAt }
                sessions.mapTo(listedSessionIds) { it.id }
            }
        }
    }

    /**
     * §3.1 rule 1a: the afterSeq of an attach. Null (a FULL attach) while [sessionId] holds a
     * pending record with tries > 0: only a snapshot WITH state may authorise its redelivery
     * (reconcile, then reconciledSessions), and a cursor at head would get a stateless reply
     * and strand it until it expires. Records with only tries == 0 keep the cursor. Caller
     * holds [lock].
     */
    private fun afterSeqForLocked(sessionId: String): Long? {
        if (pendingStore.records.any { it.sessionId == sessionId && it.tries > 0 }) return null
        return tracker.cursorFor(sessionId)
    }

    /**
     * Lazy hydration (§2.4): [sessionId]'s mirrored base + details + tail, folded and
     * published, when it has no projection yet. The mirror read is enqueued in the same
     * critical section that starts buffering the session's live events, so nothing is folded
     * twice or lost. A no-op without a mirror, or once tried in this binding.
     */
    private fun requestHydration(sessionId: String) {
        val link = mirrorLink ?: return
        val generation: Long
        val (origin, read) = synchronized(lock) {
            val origin = mirrorOrigin ?: return
            // The mirror is bound to the server in force, not one a sign-in just left (L1).
            if (origin != currentOriginLocked()) return
            if (!sessionStore.beginHydration(sessionId)) return
            generation = mirrorGeneration
            origin to link.mirror.hydrateAsync(origin, sessionId)
        }
        scope.launch {
            // Bounded: a writer that is stuck (not dead) must not leave the session blank. A read
            // that does not land in time is treated as "no saved copy" (then recovered below).
            val result = withTimeoutOrNull(mirrorHydrateTimeoutMs) { read.await() } ?: Hydration.None
            val built = (result as? Hydration.Loaded)?.session?.let { session ->
                try {
                    session to MirrorLink.rebuild(session)
                } catch (_: RuntimeException) {
                    null // the reducer is the arbiter (§10 C5): drop it, full attach
                }
            }
            completeHydration(origin, generation, sessionId, result, built?.first, built?.second)
        }
    }

    private fun completeHydration(
        origin: String,
        generation: Long,
        sessionId: String,
        result: Hydration,
        session: com.tether.app.mirror.HydratedSession?,
        rebuilt: JsObj?,
    ) {
        var fullAttachOn: WebSocket? = null
        synchronized(lock) {
            // A sign-out, re-bind or origin switch since: this read belongs to a binding that is
            // gone, even if the same origin is bound again (L1). Nothing of it is used; but a
            // session left with a cursor, no tree and no read in flight would stay blank, so it
            // gets a full attach (defensive: wipe and switch forget restored cursors already).
            if (mirrorOrigin != origin || currentOriginLocked() != origin || mirrorGeneration != generation) {
                if (!sessionStore.has(sessionId) && !sessionStore.isHydrating(sessionId) && tracker.cursorFor(sessionId) != null) {
                    tracker.forget(sessionId)
                    seededFromMirror.remove(sessionId)
                    setLiveLocked(sessionId, false)
                    if (sessionId in attachedThisEpoch && socketOpen && handshakeDone) fullAttachOn = socket
                }
                return@synchronized
            }
            when (val outcome = sessionStore.completeHydration(sessionId, session, rebuilt)) {
                // A snapshot with state won, or a projection exists already.
                SessionStore.HydrationOutcome.Cancelled -> return
                SessionStore.HydrationOutcome.Failed -> {
                    // T13.2: whatever the index said, there is no copy this process can show.
                    forgetSavedCopyLocked(sessionId)
                    // No saved copy and no cursor: nothing to recover (the attach is a full one).
                    // A cursor with no tree, though (restored from the mirror, possibly confirmed by
                    // a stateless reply, and the read came back empty: a dead or stuck writer), would
                    // leave the session blank for the whole process: recover it like a corrupt copy.
                    // Keyed on the cursor, not seededFromMirror: a stateless reply removes the
                    // session from that set before the read lands.
                    if (result is Hydration.None && tracker.cursorFor(sessionId) == null) return
                    // Unreadable or unfoldable (Corrupt, or a fold throw): the saved copy goes, and
                    // the restored cursor with it, so the attach is a FULL one.
                    mirrorLink?.drop(origin, sessionId)
                    tracker.forget(sessionId)
                    seededFromMirror.remove(sessionId)
                    sessionStore.forgetMirrored(sessionId)
                    forgetSavedCopyLocked(sessionId)
                    setLiveLocked(sessionId, false)
                    if (sessionId in attachedThisEpoch && socketOpen && handshakeDone && socketOrigin == origin) fullAttachOn = socket
                }
                is SessionStore.HydrationOutcome.Ready -> {
                    // T13.2: the copy's own verification time, unless the index already said.
                    if (!savedCopiesState.value.containsKey(sessionId)) savedCopiesState.value = savedCopiesState.value + (sessionId to session?.lastVerifiedAt)
                    sessionStore.setTrimmedBefore(sessionId, session?.trimmedBefore)
                    sessionStore.publish(sessionId, outcome.tree, sessionStore.adapt(sessionId, outcome.tree))
                }
            }
        }
        fullAttachOn?.let { sendFrameOn(it, ClientMessage.Attach(sessionId, null)) }
    }

    /**
     * T13.1 (SYNC_DESIGN §8.3): logout / revocation. The mirror DB and its data key (and the
     * Keystore key wrapping it) are destroyed: a signed-out or revoked device keeps no
     * transcripts. Unsent input is not in the mirror and is untouched.
     */
    private fun wipeMirror() {
        // The keys are shredded on THIS thread before it returns; the writer deletes the files.
        unbindMirrorForWipe()?.wipe()
    }

    /**
     * ta-jt9 L-A: a boot with no stored credential purges the mirror, whether or not anything
     * calls [start]. The app starts the client only once it is configured (UiRoot), so the
     * purge in [bindMirrorToCurrentServer] never ran on a signed-out boot: a process that died
     * between logout's clearCredential and its shred (or lost the credential another way) kept
     * a readable mirror, and a later sign-in to the same server bound it and read the previous
     * sign-in's index. Once per process, on IO (the shred is a Keystore call).
     */
    private suspend fun purgeMirrorIfSignedOut() {
        // ta-jt9 L-A1: only a store that holds NO credential. A transient Keystore error reads
        // as "no credential" through [SettingsStore.credential] but keeps the sealed credential
        // for a retry: that user is still signed in, and their copy is kept. A read that throws
        // is undecided too (the caller then keeps the mirror off for this process).
        if (settings.storedCredentialState() != StoredCredentialState.Absent) return
        // Checked and unbound in ONE critical section: a credential adopted since (a sign-in, or
        // a start()) owns the mirror now, and its own bind decides.
        val mirror = synchronized(lock) { if (credentialValue != null) null else unbindMirrorForWipe() } ?: return
        mirror.wipe()
    }

    /**
     * ta-jt9 L-A2: no sign-in and no mirror bind before the boot purge has decided (and, if it
     * purged, wiped): otherwise a sign-in racing it binds, and reads, the copy it was about to
     * shred. Bounded like a bind (M2). Undecided in time, or not at all (an error), means no
     * mirror in this process, so nothing can read a copy nobody decided on. True = go ahead.
     *
     * The bound holds only for the wait here. A purge stuck inside the store's own mutex (a
     * Keystore hang while it loads the credential) also holds every other store call, the
     * sign-in's own setServer included, and nothing here can bound that (ta-jt9 I-2).
     */
    private suspend fun awaitBootPurge(): Boolean {
        val decided = withTimeoutOrNull(mirrorBindTimeoutMs) { bootPurge.await() } ?: false
        if (!decided) mirrorLink = null
        return decided
    }

    /** A sign-out's store clear has landed (or failed): see [signOutClearsInFlight]. */
    private fun signOutClearDone() {
        synchronized(lock) {
            signOutClearsInFlight.value--
            signOutEpoch++
        }
    }

    /** [wipeMirror]'s client half: unbind at once. Returns the mirror to wipe (null = none). */
    private fun unbindMirrorForWipe(): JournalMirror? {
        val mirror = mirrorForWipe ?: return null
        synchronized(lock) {
            mirrorOrigin = null
            mirrorGeneration++
            // Cursors restored from the wiped copy are not ours to delta-attach from any more.
            for (sessionId in seededFromMirror) tracker.forget(sessionId)
            clearMirrorStateLocked()
        }
        return mirror
    }

    /** The origin of [webSocket] when it is the current socket. Caller holds [lock]. */
    private fun mirrorOriginLocked(): String? = if (mirrorLink == null) null else socketOrigin

    /**
     * ta-dl4: a session the server listed or this client subscribed to (an open session is both);
     * the per-session inspector replies ([worktreeScripts], [changeRequests]) keep only these, so a
     * flood of server-sent ids cannot grow them. Caller holds [lock].
     */
    private fun knownSessionLocked(sessionId: String): Boolean = sessionId in listedSessionIds || sessionId in subscribed

    /**
     * [mirrorOriginLocked], for a frame about [sessionId]: null unless the session is listed,
     * subscribed, holds pending input or was restored from the mirror (L3). Caller holds [lock].
     */
    private fun mirrorOriginForLocked(sessionId: String): String? {
        val origin = mirrorOriginLocked() ?: return null
        val known = sessionId in listedSessionIds || sessionId in subscribed || sessionId in seededFromMirror ||
            pendingStore.records.any { it.sessionId == sessionId }
        return if (known) origin else null
    }

    /**
     * After a late binding (the socket was already live): attach the sessions
     * with pending input that this epoch has not attached yet, then drain, all
     * on the socket that is live now and only if it is the store's origin.
     */
    private fun attachPendingSessionsThenDrain() {
        val ws: WebSocket
        val toAttach: List<Pair<String, Long?>>
        synchronized(lock) {
            if (!pendingLoaded || !socketOpen || !handshakeDone || pendingOrigin != socketOrigin) return
            ws = socket ?: return
            toAttach = pendingStore.records.map { it.sessionId }.distinct()
                .filter { attachedThisEpoch.add(it) }
                .map { it to afterSeqForLocked(it) }
            publishAttachedLocked()
        }
        for ((sessionId, afterSeq) in toAttach) {
            sendFrameOn(ws, ClientMessage.Attach(sessionId, afterSeq))
            requestHydration(sessionId)
        }
        drainPending()
    }

    override fun start() {
        val startEpoch = synchronized(lock) {
            // Deliberately NOT clearing versionHalt: start() re-runs on every
            // activity (re)creation, which is not a decision to retry.
            stopped = false
            if (sweeperJob?.isActive != true) {
                sweeperJob = scope.launch { sweeperLoop() }
            }
            signOutEpoch
        }
        scope.launch {
            // ONE snapshot of (URL, credential): two separate reads could straddle
            // a server switch and pair URL A with credential B. Whichever
            // credential the install holds — a password cookie from a pre-pairing
            // version still resolves here, so upgrading never logs anyone out.
            val session = readSessionForStart()
            if (session == null) {
                // ta-exi: the store did not read (after a short retry). Fail closed: nothing is
                // adopted from it, not the server either, and nothing is bound to it. Only a pair
                // already in memory (a sign-in in this process that no sign-out has undone) keeps
                // connecting; otherwise the login screen shows (no signed-out reason: no server
                // said anything). The next start() reads again.
                val connect = synchronized(lock) {
                    val signedIn = baseUrlValue != null && credentialValue != null
                    if (!signedIn) enterAuthRequired()
                    signedIn
                }
                if (connect) connectNow()
                return@launch
            }
            val switch = synchronized(lock) {
                val base = session.baseUrl?.toHttpUrlOrNull()
                if (signOutEpoch != startEpoch || signOutClearsInFlight.value > 0 ||
                    (session.credential != null && session.credential == forgottenCredential)
                ) {
                    // ta-jt9 L-B: signed out since this start() began, or (L-B1) the snapshot
                    // holds the credential a sign-out just forgot, read before the store's clear
                    // landed, whenever this start() began, or (L-1) a sign-out's clear is still in
                    // flight. Adopt nothing from it (not the server either, after a stop()), or
                    // the sign-out would be undone in memory.
                } else if (credentialValue == null && session.credential != null && base != null) {
                    // Adopted as a pair, never the credential under another URL.
                    baseUrlValue = base
                    credentialValue = session.credential
                } else if (baseUrlValue == null && base != null) {
                    baseUrlValue = base
                }
                settingsLoaded = true
                // The first start of a process binds the store (and whatever was
                // filed before it) to the configured server; a server that
                // changed under a bound store is an origin switch.
                followServerLocked(fallbackOwner = null, adoptUnbound = true)
            }
            switch?.let(::completeOriginSwitch)
            // Restore before the first drain or write (an unreadable store is
            // "nothing to redeliver", never a failed start).
            bindPendingToCurrentServer()
            if (baseUrlValue == null || credentialValue == null) {
                enterAuthRequired()
            } else {
                connectNow()
            }
        }
    }

    override fun stop() {
        val ws = synchronized(lock) {
            stopped = true
            versionHalt = null
            cancelTimersLocked()
            backgroundTask?.cancel()
            backgroundTask = null
            sweeperJob?.cancel()
            sweeperJob = null
            baseUrlValue = null
            credentialValue?.let { forgottenCredential = it }
            credentialValue = null
            signOutEpoch++
            signOutClearsInFlight.value++
            // A probe in flight is over: its verdict does nothing and it keeps no
            // slot. (A start() before the async settings.clear() lands does NOT
            // reload the forgotten credential, ta-jt9 L-B1: only a sign-in does.)
            endConnectAttemptsLocked()
            // The store's disk copy goes with settings.clear() below; the
            // memory goes too, set-aside stores included, so nothing of this
            // configuration can be replayed after a later sign-in.
            val dropped = pendingStore.records.size
            pendingStore = PendingInput.emptyStore()
            clearedKeys = LinkedHashSet()
            pendingLoaded = false
            pendingOrigin = null
            setAside.clear()
            pendingWipe++
            clearServerStateLocked()
            // ta-2ew r2 (security P4-2): no create answer outlives the sign-out, even if the store's
            // clear fails and the next sign-in to the same server is not seen as a switch.
            createRepliesByRequest.clear()
            detachSocketLocked() to dropped
        }.let { (socketGone, dropped) ->
            if (dropped > 0) {
                emitError("$dropped unsent message${if (dropped == 1) " was" else "s were"} discarded when you signed out.")
            }
            socketGone
        }
        ws?.cancel()
        clearSignInViews()
        wipeMirror()
        connectionState.value = ConnectionState.Disconnected
        // stop() is logout: drop the persisted base URL + credential so the UI's
        // `configured` flow flips false and the setup screen returns. A later
        // successful login()/pair() resets `stopped` and restarts the loop.
        // Serialized with the pending writes (it queues on the lock at once, so
        // no write queued after this call lands before it, and none queued
        // before it resurrects anything: they find the store unloaded).
        val wipe = synchronized(lock) { pendingWipe }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                persistMutex.withLock {
                    // ta-8lg: never fatal (this runs on the app scope: an escape is a crash). The
                    // store retries its own delete and falls back to a tombstone; one more attempt
                    // here covers a store that threw before getting that far (as logoutNow() does),
                    // since a clear that never lands lets the next process sign back in. Only a
                    // clear that returned is a landed wipe: until then the disk slots stay dead.
                    if (clearSettingsQuietly() || clearSettingsQuietly()) {
                        synchronized(lock) { if (wipe > wipeLanded) wipeLanded = wipe }
                    }
                }
            } finally {
                // ta-jt9 L-B1 / L-1: a start() that began after the first bump may have read the
                // store before this clear; the epoch moves again once it has landed (or failed).
                signOutClearDone()
            }
        }
    }

    /**
     * start()'s snapshot, tried [SETTINGS_READ_ATTEMPTS] times [settingsReadRetryMs] apart (a
     * transient I/O error). Null = the store did not read: never a partial or guessed snapshot.
     */
    private suspend fun readSessionForStart(): Session? {
        repeat(SETTINGS_READ_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(settingsReadRetryMs)
            try {
                return settings.session()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tried again, then null.
            }
        }
        return null
    }

    /** stop()'s store clear: true = it landed. A failure is reported only by the result. */
    private suspend fun clearSettingsQuietly(): Boolean = try {
        settings.clear()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * ta-hra M-1: once begun, a logout runs to the end even if its caller is cancelled (the UI
     * calls it from viewModelScope, which may be cleared mid-wipe): otherwise the saved
     * credential, the push unregister and the server revoke could be skipped and the next
     * start() would sign back in. Every step is bounded (the wipe never waits for the writer;
     * the hook and the revoke have [LOGOUT_CALL_TIMEOUT_MS]).
     */
    override suspend fun logout(): LogoutResult = withContext(NonCancellable) { logoutNow() }

    private suspend fun logoutNow(): LogoutResult {
        val base: HttpUrl?
        val credential: Credential?
        val ws: WebSocket?
        synchronized(lock) {
            stopped = true
            versionHalt = null
            cancelTimersLocked()
            backgroundTask?.cancel()
            backgroundTask = null
            base = baseUrlValue
            credential = credentialValue
            credentialValue = null
            signOutEpoch++
            signOutClearsInFlight.value++
            if (credential != null) forgottenCredential = credential
            ws = detachSocketLocked()
            // Nothing is connected from here on: never Connected while signing out.
            connectionState.value = ConnectionState.Disconnected
            endConnectAttemptsLocked()
            // T6.3: nothing is live and nothing is shown as decided once signed out (the detach
            // cleared the live set and consentOrigin). The in-memory ledger itself is kept: a
            // re-sign-in in this process must still never send a decision twice (at most once).
            decidedState.value = emptySet()
            unconfirmedState.value = emptySet()
            // ta-2ew (R2): no create answer outlives the sign-out (the URL stays, so a sign-in to the
            // same server is no switch, and the filter alone would return it again then).
            createRepliesByRequest.clear()
        }
        ws?.close(1000, "logout")
        // ta-2ew r2 (security P4-3): the latest replies go with the record; the seq counters keep
        // rising, so a reply after the next sign-in is still newer than any seen before.
        createdState.value = null
        createErrorsState.value = null
        clearSignInViews()
        val mirror = unbindMirrorForWipe()

        // 1. Forget locally FIRST: whatever happens next, this phone is signed out.
        //    The server URL stays (login-screen prefill). Before the mirror's shred too
        //    (ta-hra I-9): a death between the two must not leave a credential that re-signs
        //    in; with no credential, the next start purges whatever of the mirror is left.
        // The store retries its own delete and falls back to a tombstone; one
        // more attempt here covers a store that threw before getting that far.
        // ta-exi: runCatching also takes a CancellationException, on purpose: this runs only
        // NonCancellable (logout()), so one is the store's own, never this logout's (as the hook's,
        // ta-jt9 L-C); rethrowing it would end the logout here, skipping the shred and the revoke (M-1).
        try {
            if (runCatching { settings.clearCredential() }.isFailure) {
                runCatching { settings.clearCredential() }
            }
        } finally {
            // ta-jt9 L-B1 / L-1: again once the store is clear (see stop()).
            signOutClearDone()
        }
        // The shred runs off the caller's thread (ta-hra R3: the UI calls logout from
        // viewModelScope, on main; the Keystore delete is an IPC), but it is awaited: logout
        // still returns only after the keys are gone (M1).
        mirror?.let { withContext(Dispatchers.IO) { it.wipe() } }
        synchronized(lock) {
            // ta-jt9 I-C: only if nobody signed in while the shred ran: a sign-in adopted since
            // owns the state (Connecting / Connected), and this logout must not overwrite it.
            if (credentialValue == null) {
                // A user logout is not a server verdict: no "session expired" copy.
                signedOutReasonState.value = null
                enterAuthRequired()
            }
        }

        if (base == null || credential == null) return LogoutResult.LocalOnly

        // 2. Integrator hook (push unregister for a device token), bounded.
        try {
            withTimeoutOrNull(LOGOUT_CALL_TIMEOUT_MS) { onLogout(base.toString().trimEnd('/'), credential) }
        } catch (_: CancellationException) {
            // ta-jt9 L-C: never this logout's own (it runs NonCancellable, and withTimeoutOrNull
            // keeps its own timeout): the hook's, e.g. a cancelled Firebase Task. Best effort like
            // any other failure, so the server revoke below still runs.
        } catch (_: Exception) {
            // Best effort.
        }

        // 3. Cookie: revoke server-side. Device token: nothing to call — the
        //    server refuses device-management routes to a device token BY DESIGN
        //    (requireOwnerGrade), and /api/auth/logout only revokes cookie
        //    sessions. The owner revokes a device from a browser.
        if (credential !is Credential.Cookie) return LogoutResult.LocalOnly
        if (blockedBeforeConnect(base)) return LogoutResult.ServerNotReached
        return withContext(Dispatchers.IO) {
            try {
                authHttp.newBuilder()
                    .callTimeout(LOGOUT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(
                        Request.Builder()
                            .url(base.resolve("/api/auth/logout")!!)
                            .authorize(credential, base)
                            .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
                            .build(),
                    ).execute().use { response ->
                        if (response.isSuccessful) LogoutResult.Revoked else LogoutResult.ServerNotReached
                    }
            } catch (_: IOException) {
                LogoutResult.ServerNotReached
            }
        }
    }

    override suspend fun signInRequirements(baseUrl: String): SignInRequirements? = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl) ?: return@withContext null
        if (blockedBeforeConnect(normalized)) return@withContext null
        try {
            // Deliberately WITHOUT a credential: this is the login screen asking
            // what a sign-in needs, possibly of a server we hold nothing for.
            authHttp.newCall(Request.Builder().url(normalized.resolve("/api/auth/session")!!).build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val obj = parseJsonObject(response) ?: return@use null
                    fun flag(name: String) = obj[name]?.jsonPrimitive?.content
                    SignInRequirements(
                        usernameRequired = flag("usernameRequired") == "true",
                        // Absent = older server = password on (web: `!== false`).
                        passwordLoginEnabled = flag("passwordLoginEnabled") != "false",
                        passkeyCount = flag("passkeyCount")?.toIntOrNull() ?: 0,
                        passkeysUsable = flag("passkeysUsable") == "true",
                    )
                }
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun listSignInSessions(): SignInSessionsResult =
        ownerGradeCall("GET", listOf("api", "auth", "sessions")) { obj ->
            val list = (obj?.get("sessions") as? JsonArray).orEmpty().mapNotNull { element ->
                val record = element as? JsonObject ?: return@mapNotNull null
                val id = (record["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                fun num(name: String) = (record[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
                val method = (record["method"] as? JsonPrimitive)?.content
                SignInSession(
                    id = id,
                    method = if (method == "passkey" || method == "service" || method == "app-passkey") method else "password",
                    createdAt = num("createdAt"),
                    lastSeenAt = num("lastSeenAt"),
                    expiresAt = num("expiresAt"),
                    userAgent = (record["userAgent"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    current = (record["current"] as? JsonPrimitive)?.content == "true",
                )
            }
            SignInSessionsResult.Sessions(list)
        }

    /**
     * GET /api/stats (log-dialog.tsx refreshStats). Any credential the socket uses is accepted:
     * the route sits behind the ordinary /api/ gate, not the owner-grade one.
     */
    override suspend fun fetchStats(): StatsResult = withContext(Dispatchers.IO) {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        if (base == null || credential == null) return@withContext StatsResult.Failed(STATS_FALLBACK_ERROR)
        if (blockedBeforeConnect(base)) return@withContext StatsResult.Failed("Local network access is blocked.")
        val url = base.newBuilder().encodedPath("/").addPathSegment("api").addPathSegment("stats").build()
        val request = Request.Builder().url(url).authorize(credential, base).header("Cache-Control", "no-store").get().build()
        try {
            authHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use StatsResult.Failed("stats request failed (${response.code})")
                parseJsonObject(response)?.let { StatsResult.Loaded(ServerStats.fromJson(it)) }
                    ?: StatsResult.Failed(STATS_FALLBACK_ERROR)
            }
        } catch (_: IOException) {
            StatsResult.Failed(STATS_FALLBACK_ERROR)
        }
    }

    override suspend fun revokeSignInSession(id: String): SignInSessionsResult =
        ownerGradeCall("DELETE", listOf("api", "auth", "sessions", id)) { SignInSessionsResult.Revoked(1) }

    override suspend fun revokeOtherSignInSessions(): SignInSessionsResult =
        ownerGradeCall("DELETE", listOf("api", "auth", "sessions")) { obj ->
            SignInSessionsResult.Revoked((obj?.get("revoked") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
        }

    /**
     * An owner-grade route (server `requireOwnerGrade`). A device token is
     * refused locally — the server would answer 403 by design, so the token is
     * not even sent. [segments] are path segments (encoded by HttpUrl).
     */
    private suspend fun ownerGradeCall(
        method: String,
        segments: List<String>,
        onSuccess: (JsonObject?) -> SignInSessionsResult,
    ): SignInSessionsResult = withContext(Dispatchers.IO) {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        if (base == null || credential == null) return@withContext SignInSessionsResult.NotSignedIn
        if (credential !is Credential.Cookie) return@withContext SignInSessionsResult.OwnerGradeRequired
        if (blockedBeforeConnect(base)) return@withContext SignInSessionsResult.Failed("Local network access is blocked.")
        val url = base.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url).authorize(credential, base)
            .apply { if (method == "DELETE") delete() else get() }
            .build()
        try {
            authHttp.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> onSuccess(parseJsonObject(response))
                    401 -> SignInSessionsResult.NotSignedIn
                    403 -> SignInSessionsResult.OwnerGradeRequired
                    404 -> SignInSessionsResult.NotFound
                    else -> SignInSessionsResult.Failed(
                        parseJsonField(response, "error") ?: "The server answered HTTP ${response.code}.",
                    )
                }
            }
        } catch (_: IOException) {
            SignInSessionsResult.Failed("The server could not be reached.")
        }
    }

    override fun reconnectIfIdle() = reconnectIfIdle(probeOpen = true)

    /** [probeOpen] false: an open socket is left alone (see adoptCredential). */
    private fun reconnectIfIdle(probeOpen: Boolean) {
        val probe = synchronized(lock) {
            if (haltedLocked()) return
            when {
                // An OPEN socket cannot be trusted after a wake or a network
                // change (the half-open case): probe it instead (web #135).
                socketOpen -> if (probeOpen) true else return
                // An upgrade / auth probe is already in flight.
                connecting || socket != null -> return
                else -> {
                    reconnectTask?.cancel()
                    reconnectTask = null
                    false
                }
            }
        }
        if (probe) probeLink() else connectNow()
    }

    override fun setAppForeground(foreground: Boolean) {
        if (foreground) {
            val resume = synchronized(lock) {
                inForeground = true
                backgroundTask?.cancel()
                backgroundTask = null
                val wasSuspended = backgroundSuspended
                backgroundSuspended = false
                if (wasSuspended) backoff.reset()
                wasSuspended
            }
            // Web visibilitychange -> reconnectIfIdle: ping an open socket,
            // reconnect a dead one immediately.
            if (resume) connectNow() else reconnectIfIdle()
            return
        }
        synchronized(lock) {
            inForeground = false
            backgroundTask?.cancel()
            backgroundTask = scheduler.schedule(ConnectionTimings.BACKGROUND_GRACE_MS) { suspendForBackground() }
        }
    }

    /** Grace period over: close the socket and stop reconnecting until foreground. */
    private fun suspendForBackground() {
        val ws: WebSocket?
        val publish: Boolean
        val wasOpen: Boolean
        synchronized(lock) {
            backgroundTask = null
            if (inForeground || backgroundSuspended) return
            backgroundSuspended = true
            cancelTimersLocked()
            // An auth probe still in flight sees the suspension and gives up by
            // itself (connectNow/openSocket); an upgrade in flight ends here.
            wasOpen = socketOpen
            ws = detachSocketLocked()
            publish = !stopped && versionHalt == null
        }
        if (wasOpen) ws?.close(1000, "app in background") else ws?.cancel()
        if (publish) connectionState.value = ConnectionState.Disconnected
    }

    override fun retryConnection() {
        synchronized(lock) {
            if (stopped) return
            versionHalt = null
            backoff.reset()
            reconnectTask?.cancel()
            reconnectTask = null
        }
        connectNow()
    }

    // ------------------------------------------------------------------
    // Connection loop
    // ------------------------------------------------------------------

    /**
     * Every connect attempt in flight becomes stale and the connect slot is free
     * (sign-in, stop, logout). Caller holds [lock].
     */
    private fun endConnectAttemptsLocked() {
        connectGeneration++
        connecting = false
    }

    /** The attempt started in [generation] was ended since: it must do nothing. Caller holds [lock]. */
    private fun staleLocked(generation: Long): Boolean = generation != connectGeneration

    /** Anything that forbids connecting right now. Caller holds [lock]. */
    private fun haltedLocked(): Boolean = stopped || versionHalt != null || backgroundSuspended

    /** Caller holds [lock]. */
    private fun cancelTimersLocked() {
        reconnectTask?.cancel()
        reconnectTask = null
        pingTask?.cancel()
        pingTask = null
    }

    /**
     * Forget the current socket (the caller closes it). Its listener is retired,
     * so a late onOpen can never re-adopt it; an upgrade still in flight ends
     * the connect attempt. Caller holds [lock].
     */
    private fun detachSocketLocked(): WebSocket? {
        val ws = socket
        // An upgrade still in flight (bound or not yet) ends the connect attempt.
        if (!socketOpen && (ws != null || socketListener != null)) connecting = false
        socketListener?.retired = true
        socketListener = null
        socket = null
        socketOrigin = null
        socketOpen = false
        handshakeDone = false
        clearLiveLocked()
        // ta-895: the catalog the socket delivered is no longer the live one.
        providerCatalogLiveState.value = false
        // T15.1 (use-tether.ts:1212): the overview stays on screen, marked stale; the wish waits for the next ready.
        overviewSync.onSocketGone()
        consentOriginState.value = null
        pingTask?.cancel()
        pingTask = null
        // T7.4: a message with attachments this socket never confirmed is not resent anywhere: say
        // it may not have arrived, so the operator can look and send it again deliberately.
        if (attachmentsInFlight.isNotEmpty()) {
            val lost = attachmentsInFlight.size
            attachmentsInFlight.clear()
            emitError(attachmentsUnconfirmedCopy(lost))
        }
        // Replies to node requests can only come on the socket that carried them:
        // every waiter ends now instead of at its timeout. Safe under the lock,
        // because a waiter resumes on Dispatchers.Default (nodeRequest), never
        // inline on this thread.
        if (nodeRequests.isNotEmpty()) {
            val lost = nodeRequests.values.toList()
            nodeRequests.clear()
            lost.forEach { it.complete(NodeRequestOutcome.LinkLost) }
        }
        return ws
    }

    private fun connectNow() {
        val base: HttpUrl
        val credential: Credential
        val generation: Long
        synchronized(lock) {
            if (haltedLocked() || connecting || socket != null) return
            val b = baseUrlValue
            val c = credentialValue
            if (b == null || c == null) {
                // Before start() read the settings (e.g. an early lifecycle
                // signal) "no credential" is not known yet: stay quiet.
                if (settingsLoaded) enterAuthRequired()
                return
            }
            connecting = true
            base = b
            credential = c
            generation = connectGeneration
        }
        if (blockedBeforeConnect(base)) {
            // Check before any local-network access (the documented pattern).
            // No reconnect is scheduled, because it could only fail the same way.
            // reconnectIfIdle() re-evaluates once access is granted.
            synchronized(lock) { connecting = false }
            connectionState.value = ConnectionState.LocalNetworkBlocked
            return
        }
        connectionState.value = ConnectionState.Connecting
        scope.launch(Dispatchers.IO) {
            // §5.3: check auth before each connect.
            val verdict = try {
                authProbe(base, credential)
            } catch (e: IOException) {
                val blocked = blockedAfterFailure(base, e)
                val restricted = localNetworkAccess.isRestricted()
                val (halted, suspect) = synchronized(lock) {
                    // Ended since (sign-in / stop / logout): touch nothing.
                    if (staleLocked(generation)) return@launch
                    connecting = false
                    // T0.6: while the OS restricts local-network traffic, a run of
                    // connect TIMEOUTS (the documented TCP signature of the block)
                    // is treated as the block even when the host does not look
                    // local. A refused connection or an HTTP error proves the path
                    // works, so it breaks the run.
                    consecutiveTimeouts = if (restricted && isTimeout(e)) consecutiveTimeouts + 1 else 0
                    haltedLocked() to (consecutiveTimeouts >= ConnectionTimings.LOCAL_NETWORK_SUSPECT_TIMEOUTS)
                }
                if (halted) return@launch
                if (blocked || suspect) {
                    // Same as above: no reconnect loop against a blocked network.
                    connectionState.value = ConnectionState.LocalNetworkBlocked
                    return@launch
                }
                connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
                return@launch
            }
            synchronized(lock) {
                // Ended since (login()/pair()/stop()/logout() while this probe
                // was in flight): never act on the verdict, and above all never
                // open a socket with a credential that is no longer in force.
                if (staleLocked(generation)) return@launch
                consecutiveTimeouts = 0
            }
            raceHook?.invoke(RacePoint.VerdictChecked, verdict)
            when (verdict) {
                ProbeVerdict.Authenticated -> openSocket(base, credential, generation)
                ProbeVerdict.Rejected -> handleCredentialRejected(
                    credential,
                    if (credential is Credential.Cookie) SignedOutReason.SessionExpired else SignedOutReason.DeviceUnpaired,
                    generation,
                )
                ProbeVerdict.Refused -> {
                    // A gateway, not Tether, said no: keep the credential (it may be
                    // fine once the probe gets through) and wait for a user action,
                    // a network change or the next foreground — no timer loop.
                    val current = synchronized(lock) {
                        // Staleness is re-checked in the SAME critical section that
                        // releases the slot: a sign-in / stop / logout since the
                        // check above started a newer attempt, whose slot this
                        // verdict must not free.
                        if (staleLocked(generation)) return@launch
                        connecting = false
                        credentialValue === credential && !haltedLocked()
                    }
                    if (current) {
                        // State first: an observer that sees the reason must see the settled state.
                        enterAuthRequired()
                        signedOutReasonState.value = SignedOutReason.GatewayRefused
                    }
                }
            }
        }
    }

    /**
     * Tether itself says [credential] is no longer valid (auth probe
     * `authenticated:false`, close 4001/4002): terminal. Forget the credential,
     * keep the server URL, land on the login screen with [reason]. A no-op when a
     * different credential has been adopted since (a login racing the probe) or,
     * for a probe verdict, when its attempt [generation] ended since; then the
     * connect slot belongs to the newer attempt and is left alone. Unsent input
     * is kept: a re-login to the same origin delivers it (T1.3).
     */
    private fun handleCredentialRejected(credential: Credential, reason: SignedOutReason, generation: Long? = null) {
        val ws = synchronized(lock) {
            if (generation != null && staleLocked(generation)) return
            if (credentialValue !== credential) return
            // Every attempt still in flight for this dead credential is over.
            endConnectAttemptsLocked()
            stopped = true
            cancelTimersLocked()
            credentialValue = null
            signOutEpoch++
            signOutClearsInFlight.value++
            forgottenCredential = credential
            detachSocketLocked()
        }
        ws?.cancel()
        clearSignInViews()
        // A dead credential (4001/4002 revocation, or the probe's authenticated:false): the
        // device must not keep transcripts it can no longer prove it may read (§8.3).
        wipeMirror()
        // State first: an observer that sees the reason must see the settled state.
        enterAuthRequired()
        signedOutReasonState.value = reason
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Only if the store still holds THIS credential: never wipe a
                // newer login that landed while this verdict was in flight. One
                // compare-and-clear under the store's own lock (ta-jt9 L-X): a
                // separate read then clear could delete that newer login's.
                settings.clearCredentialIf(credential)
            } catch (e: CancellationException) {
                // ta-exi: never swallowed (as stop()'s clearSettingsQuietly); the finally still runs.
                throw e
            } catch (_: Exception) {
                // Worst case the dead credential survives a restart; the next
                // probe rejects it again.
            } finally {
                // ta-jt9 L-B1 / L-1: again once the store is clear (see stop()).
                signOutClearDone()
            }
        }
    }

    private fun isTimeout(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 8) {
            if (e is SocketTimeoutException) return true
            e = e.cause
            depth++
        }
        return false
    }

    private enum class ProbeVerdict { Authenticated, Rejected, Refused }

    /**
     * `GET /api/auth/session` with the credential. Tether always answers 200 with
     * `{authenticated: bool}`, so only an explicit `false` from a Tether-shaped
     * body is a verdict on the credential. A redirect / 401 / 403 is something in
     * front of Tether refusing ([ProbeVerdict.Refused]). Anything else (5xx, a
     * body that is not Tether's) throws: transient, reconnect with backoff.
     */
    @Throws(IOException::class)
    private fun authProbe(base: HttpUrl, credential: Credential): ProbeVerdict {
        val request = Request.Builder()
            .url(base.resolve("/api/auth/session")!!)
            .authorize(credential, base)
            .build()
        authHttp.newCall(request).execute().use { response ->
            if (response.code in 300..399 || response.code == 401 || response.code == 403) return ProbeVerdict.Refused
            if (!response.isSuccessful) throw IOException("auth probe returned HTTP ${response.code}")
            val authenticated = parseJsonObject(response)?.get("authenticated") as? JsonPrimitive
            return when {
                authenticated == null || authenticated.isString -> throw IOException("auth probe answered without a verdict")
                authenticated.content == "true" -> ProbeVerdict.Authenticated
                authenticated.content == "false" -> ProbeVerdict.Rejected
                else -> throw IOException("auth probe answered without a verdict")
            }
        }
    }

    private fun openSocket(base: HttpUrl, credential: Credential, generation: Long) {
        // Origin's host(+port) MUST equal the Host header or the server
        // destroys the upgrade with a raw 401. OkHttp never sets it itself. An
        // IPv6 literal keeps its brackets: the server parses this as a URL.
        // Sent with either credential here (a cookie request carries it anyway).
        val request = Request.Builder()
            .url(base.resolve("/ws")!!)
            .authorize(credential, base)
            .header("Origin", consoleOrigin(base))
            .build()
        val listener = SocketListener()
        synchronized(lock) {
            // Ended since the probe (a sign-in / stop / logout in between): a
            // newer attempt owns the slot.
            if (staleLocked(generation)) return
            if (haltedLocked() || socket != null) {
                connecting = false
                return
            }
            // The listener is registered, the upgrade started and its socket
            // bound in ONE critical section: once stop() / logout() / a
            // sign-in has taken the lock no upgrade can start with the old
            // credential, and whichever of them comes next finds the socket
            // bound and cancels it (detachSocketLocked). newWebSocket() only
            // enqueues the call; OkHttp's callbacks run on its own threads and
            // wait for this lock, and bind the socket first if they win
            // (bindLocked is idempotent). The monitor is reentrant, so a
            // callback OkHttp makes inline (a rejected enqueue) is safe too.
            socketListener = listener
            socketOrigin = serverOrigin(base.toString())
            // No redirects on the credential-bearing upgrade either (see authHttp).
            listener.bindLocked(authHttp.newWebSocket(request, listener))
        }
    }

    /** The next attempt after [Backoff.next] — never a fixed-rate or tight loop. */
    private fun scheduleReconnect() {
        synchronized(lock) {
            if (haltedLocked()) return
            reconnectTask?.cancel()
            reconnectTask = scheduler.schedule(backoff.next()) {
                synchronized(lock) { reconnectTask = null }
                connectNow()
            }
        }
    }

    /**
     * App-level liveness probe (v105 `ping`, web issue #135): if NOTHING arrives
     * within [ConnectionTimings.PING_TIMEOUT_MS] of the ping the socket is
     * half-open (OPEN over dead TCP), so it is dropped and the reconnect path
     * takes over. Any inbound frame counts, not only the pong. One probe at a time.
     */
    private fun probeLink() {
        val ws: WebSocket
        val sentAt: Long
        synchronized(lock) {
            if (!socketOpen || pingTask != null) return
            ws = socket ?: return
            sentAt = clock()
            if (!ws.send(ClientMessage.Ping(nonce = UUID.randomUUID().toString()).encode())) return
            pingTask = scheduler.schedule(ConnectionTimings.PING_TIMEOUT_MS) {
                val dead = synchronized(lock) {
                    pingTask = null
                    socket === ws && socketOpen && lastInboundAt < sentAt
                }
                if (dead) dropSocket(ws)
            }
        }
    }

    /** Force a presumed-dead socket down and hand over to the reconnect path. */
    private fun dropSocket(ws: WebSocket) {
        ws.cancel()
        // cancel() normally reports onFailure, but do not depend on it.
        handleSocketGone(ws)
    }

    private inner class SocketListener : WebSocketListener() {
        /** Set (under lock) once the client let go of this socket. */
        var retired = false

        /**
         * The current listener's socket becomes [socket] at its FIRST sign of life,
         * whichever comes first: a callback or newWebSocket() returning. True when
         * [webSocket] is the client's current socket. Caller holds [lock].
         */
        fun bindLocked(webSocket: WebSocket): Boolean {
            if (retired) return false
            if (this === socketListener && socket == null) socket = webSocket
            return socket === webSocket
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val reject = synchronized(lock) {
                if (retired || haltedLocked() || !bindLocked(webSocket)) {
                    if (!retired && this === socketListener) connecting = false
                    retired = true
                    return@synchronized true
                }
                socket = webSocket
                socketListener = this
                socketOpen = true
                handshakeDone = false
                connecting = false
                // A new epoch: nothing is attached on this socket yet.
                epoch++
                // ta-8cv: a create in flight on the previous socket is over; one composed there is refused.
                linkEpochState.value = epoch
                // ta-895: and no catalog of this socket is in yet.
                providerCatalogLiveState.value = false
                // ta-2uq: a refresh sent on the previous socket is dropped with it.
                refreshThrottle.clear()
                attachedThisEpoch.clear()
                // T6.7: interrupts sent on the previous socket are answered there, if at all.
                interruptsBound.clear()
                publishAttachedLocked()
                clearLiveLocked()
                // A probe from the previous socket must not judge this one.
                pingTask?.cancel()
                pingTask = null
                // §5.4: do NOT drain pending sends; reset in-flight and wait for ready.
                pendingStore = PendingInput.resetInFlight(pendingStore)
                reconciledSessions.clear()
                tracker.clearResyncFlags()
                lastInboundAt = clock()
                false
            }
            if (reject) webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!synchronized(lock) { bindLocked(webSocket) }) return
            // Stamped before parsing: even an undecodable frame proves traffic.
            lastInboundAt = clock()
            val message = ServerMessage.parse(text)
            raceHook?.invoke(RacePoint.FrameAdmitted, message)
            handleFrame(webSocket, message)
            raceHook?.invoke(RacePoint.FrameHandled, message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { bindLocked(webSocket) }
            webSocket.close(1000, null)
            if (code == CLOSE_DEVICE_REVOKED || code == CLOSE_SESSION_REVOKED) handleRevokedClose(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { bindLocked(webSocket) }
            if (code == CLOSE_DEVICE_REVOKED || code == CLOSE_SESSION_REVOKED) {
                handleRevokedClose(webSocket, code)
                return
            }
            handleSocketGone(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) { bindLocked(webSocket) }
            handleSocketGone(webSocket)
        }
    }

    /**
     * Close code 4001 = the owner revoked this device from a browser; 4002 = the
     * cookie session was revoked (signed out elsewhere / "sign out everywhere").
     * Terminal, NOT a transient drop: the stored credential is dead, so
     * reconnecting with it would only spin. Drop the credential and fall back to
     * the login/pairing screen (the base URL survives — only the credential is gone).
     */
    private fun handleRevokedClose(webSocket: WebSocket, code: Int) {
        val credential = synchronized(lock) {
            // onClosing then onClosed both carry the code; the first one through
            // wins and clears `socket`, so the second is a no-op.
            if (socket !== webSocket) return
            credentialValue
        } ?: return
        val reason = if (code == CLOSE_DEVICE_REVOKED) SignedOutReason.DeviceUnpaired else SignedOutReason.SessionExpired
        handleCredentialRejected(credential, reason)
        emitError(
            if (reason == SignedOutReason.DeviceUnpaired) {
                "This device was unpaired from the server. Pair it again to reconnect."
            } else {
                "You were signed out of this server. Sign in again to reconnect."
            },
        )
    }

    private fun handleSocketGone(webSocket: WebSocket) {
        synchronized(lock) {
            if (socket !== webSocket) return
            detachSocketLocked()
            connecting = false
            if (haltedLocked()) return
        }
        connectionState.value = ConnectionState.Disconnected
        scheduleReconnect()
    }

    // ------------------------------------------------------------------
    // Frame handling
    // ------------------------------------------------------------------

    private fun handleFrame(webSocket: WebSocket, message: ServerMessage) {
        when (message) {
            is ServerMessage.Ready -> onReady(webSocket, message)
            is ServerMessage.VersionMismatch -> haltForVersion(Compatibility.fromMismatch(message))
            // Liveness confirmed: lastInboundAt was stamped for this frame already,
            // so an outstanding probe sees the link alive. Nothing else to do.
            is ServerMessage.Pong -> Unit
            is ServerMessage.Created -> ifCurrent(webSocket) {
                upsertSessionLocked(message.session)
                createdSeq += 1
                // ta-2ew (R1): stamped with the server this socket was opened for, now, with the frame.
                val reply = CreatedReply(message.session, createdSeq, message.requestId, epoch, socketOrigin)
                createdState.value = reply
                createdRepliesFlow.tryEmit(reply)
                message.requestId?.let { recordCreateReplyLocked(it, CreateReplyRecord.Created(reply)) }
            }
            is ServerMessage.SessionUpdate -> {
                if (message.session.runtimeArchived) {
                    // use-tether.ts:827 — an archived session refuses every send: drop its records.
                    val changed = synchronized(lock) {
                        if (socket !== webSocket) return@synchronized false
                        val next = PendingInput.forgetSession(pendingStore, message.session.id)
                        if (next === pendingStore) return@synchronized false
                        forgetLocked(pendingStore.records.filter { it.sessionId == message.session.id }.map { it.key })
                        pendingStore = next
                        true
                    }
                    if (changed) persistPending()
                }
                ifCurrent(webSocket) { upsertSessionLocked(message.session) }
            }
            is ServerMessage.Histories -> ifCurrent(webSocket) {
                historiesState.value = message.sessions
                sidebarSync.onFrame(message)
            }
            // T5.1: v67 order, v63 seen, v50/v128 server settings (SidebarSync.kt); ta-t7l: v16 advanced settings.
            // ta-q6p: v84 the custom-providers registry (SidebarSync.kt, dropped with the settings frames).
            is ServerMessage.SessionOrder, is ServerMessage.Seen, is ServerMessage.ServerSettings, is ServerMessage.AdvancedSettings ->
                ifCurrent(webSocket) { sidebarSync.onFrame(message) }
            // r2 (security F1): stamped with this socket's epoch: a list from before a reconnect is never written back.
            is ServerMessage.Providers -> ifCurrent(webSocket) { sidebarSync.onProviders(message, epoch) }
            // ta-q6p: v74 `acp-agents` is retired server-side (887c222 never sends it): decoded, routed nowhere.
            is ServerMessage.AcpAgents -> Unit
            is ServerMessage.Directories -> ifCurrent(webSocket) { directoriesState.value = message.listing }
            // T5.3: the two search replies (SearchSync.kt drops a superseded global one).
            is ServerMessage.SearchResults, is ServerMessage.GlobalSearchResults ->
                ifCurrent(webSocket) { searchSync.onFrame(message) }
            // T15.1: use-tether.ts:1010-1017 — fold; out of step re-subscribes on THIS socket only.
            is ServerMessage.OverviewSnapshot, is ServerMessage.OverviewDelta -> ifCurrent(webSocket) {
                overviewSync.onFrame(message, clock()) { sendOverviewLocked(webSocket, it) }
            }
            is ServerMessage.Snapshot -> onSnapshot(webSocket, message)
            is ServerMessage.Event -> onEvent(webSocket, message)
            is ServerMessage.TurnsDetail -> onTurnsDetail(webSocket, message)
            is ServerMessage.InterruptResult -> onInterruptResult(webSocket, message)
            is ServerMessage.ErrorFrame -> {
                // Every error of the live link is shown, as the web does (use-tether.ts setError),
                // T6.7: cleaned and attributed to the server; one that echoes a node request's
                // requestId also ends that request.
                emitServerErrorIfCurrent(webSocket, message.message)
                // ta-8cv: use-tether.ts:1201 setCreateError — every error, with its echo; the draft
                // composer acts only on the one that names its in-flight create.
                ifCurrent(webSocket) {
                    createErrorSeq += 1
                    val reply = CreateErrorReply(LabelText.error(message.message), createErrorSeq, message.requestId, epoch, socketOrigin)
                    createErrorsState.value = reply
                    createErrorRepliesFlow.tryEmit(reply)
                    message.requestId?.let { recordCreateReplyLocked(it, CreateReplyRecord.Failed(reply)) }
                }
                // T10.3 r2 (security F3): only an error of the CURRENT socket ends a node request,
                // checked and ended in one step under the lock, as onNodeResult does.
                message.requestId?.let { completeNodeRequestOn(webSocket, it, NodeRequestOutcome.ServerError(message.message)) }
            }
            // ta-23f (v98): use-tether.ts:916 setWorktreeSource, here stamped with this socket and its echo;
            // the draft composer takes only the reply to its own inspect (DraftComposerModel.onWorktreeSource).
            is ServerMessage.WorktreeSource -> ifCurrent(webSocket) {
                worktreeSourcesFlow.tryEmit(WorktreeSourceReply(WorktreeSourceInfo.parse(message.info), message.requestId, epoch))
            }
            // v109: the registry is replaced wholesale (use-tether.ts setNodes).
            is ServerMessage.Nodes -> ifCurrent(webSocket) { nodesState.value = message.nodes }
            is ServerMessage.NodeResult -> onNodeResult(webSocket, message)
            // use-tether.ts:1047: every batch folds into the one log (seq dedupe, bootId restart).
            is ServerMessage.Log -> ifCurrent(webSocket) { eventLogState.update { it.accept(message) } }
            // T7.3: use-tether.ts:1129 — the catalog is replaced wholesale (the `@` Agents read it).
            // ta-895: stamped with the socket that delivered it (the New session picker's profile rows).
            // ta-2uq: a push (every catalog change is broadcast) replaces it too, so an open model
            // browser follows it; the rows it shows settled end their refresh's flight.
            is ServerMessage.ProvidersSnapshot -> ifCurrent(webSocket) {
                val entries = ProviderCatalogEntry.parse(message.entries)
                providerCatalogState.value = entries
                catalogEpoch = epoch
                catalogOrigin = socketOrigin
                providerCatalogLiveState.value = socketOrigin != null
                refreshThrottle.onCatalog(epoch, entries)
            }
            is ServerMessage.SessionControls -> ifCurrent(webSocket) {
                sessionControlsState.value = sessionControlsState.value + (message.sessionId to message)
            }
            // T7.2: use-tether.ts:1076-1111. A snapshot replaces the catalog and ends "busy"; a result
            // keeps the snapshot and shows its message. Display state only: nothing here sends.
            is ServerMessage.CodexControls -> ifCurrent(webSocket) {
                synchronized(lock) {
                    val current = codexControlsState.value[message.sessionId]
                    codexControlsState.value = codexControlsState.value +
                        (message.sessionId to ProviderControlsState(CodexSnapshot.parse(message.snapshot), false, current?.message))
                }
            }
            is ServerMessage.CodexControlResult -> ifCurrent(webSocket) {
                synchronized(lock) {
                    val current = codexControlsState.value[message.sessionId]
                    codexControlsState.value = codexControlsState.value +
                        (message.sessionId to ProviderControlsState(current?.snapshot, false, LabelText.clean(message.message, MAX_CONTROL_MESSAGE)))
                }
            }
            is ServerMessage.OpencodeControls -> ifCurrent(webSocket) {
                synchronized(lock) {
                    val current = opencodeControlsState.value[message.sessionId]
                    opencodeControlsState.value = opencodeControlsState.value +
                        (message.sessionId to ProviderControlsState(OpencodeSnapshot.parse(message.snapshot), false, current?.message))
                }
            }
            is ServerMessage.OpencodeControlResult -> ifCurrent(webSocket) {
                synchronized(lock) {
                    val current = opencodeControlsState.value[message.sessionId]
                    opencodeControlsState.value = opencodeControlsState.value +
                        (message.sessionId to ProviderControlsState(current?.snapshot, false, LabelText.clean(message.message, MAX_CONTROL_MESSAGE)))
                }
            }
            // T6.2: use-tether.ts:909-921. A fresh summary drops the session's cached hunks.
            is ServerMessage.WorktreeDiff -> ifCurrent(webSocket) {
                worktreeDiffsState.value = worktreeDiffsState.value + (message.sessionId to message.diff)
                if (gitFileDiffsState.value.containsKey(message.sessionId)) {
                    gitFileDiffsState.value = gitFileDiffsState.value + (message.sessionId to emptyMap())
                }
            }
            // T9.1: use-tether.ts:918-921 (keyed by the snapshot's own sessionId) and 940-941.
            // ta-dl4: kept only for a known session (the L3 rule), so server-sent ids cannot grow them.
            is ServerMessage.WorktreeScripts -> ifCurrent(webSocket) {
                val id = (message.snapshot["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                if (!id.isNullOrEmpty() && knownSessionLocked(id)) worktreeScriptsState.value = worktreeScriptsState.value + (id to message.snapshot)
            }
            is ServerMessage.ChangeRequest -> ifCurrent(webSocket) {
                if (knownSessionLocked(message.sessionId)) {
                    changeRequestsState.value = changeRequestsState.value + (message.sessionId to ChangeRequestReading(message.changeRequest, message.unknown))
                }
            }
            is ServerMessage.GitDiffFile -> ifCurrent(webSocket) {
                if (!requestedGitFileDiffs.remove(message.sessionId to message.path)) return@ifCurrent
                val current = gitFileDiffsState.value
                gitFileDiffsState.value = current + (message.sessionId to ((current[message.sessionId] ?: emptyMap()) + (message.path to message)))
            }
            // T1.1 modeled the full v129 union; frames this client does not act on
            // yet (and Unknown) stay inert, exactly as before.
            else -> Unit
        }
    }

    /**
     * Handshake: `ready` (the server's first frame) -> `hello` -> attach. The
     * native window replaces the old strict `protocolVersion` equality: a server
     * whose ready has no `nativeProtocolFloor` (v128 and older) or whose window
     * excludes this app halts here; otherwise the `hello` reply is authoritative
     * (a later `version_mismatch` still halts).
     */
    private fun onReady(webSocket: WebSocket, message: ServerMessage.Ready) {
        // The hello goes out first, before any other frame of this epoch. It is
        // sent directly: ordinary frames wait for handshakeDone.
        webSocket.send(ClientMessage.Hello(PROTOCOL_VERSION, HELLO_CLIENT_ANDROID).encode())
        Compatibility.evaluate(message.protocolVersion, message.nativeProtocolFloor)?.let {
            haltForVersion(it)
            return
        }
        // §5.5: re-attach every subscribed session and every session that has
        // pending outbound input, from its last good cursor.
        val toAttach: List<Pair<String, Long?>>
        synchronized(lock) {
            if (socket !== webSocket) return
            // Published under the same check: a late ready of a socket let go
            // (another server's) can never show its sessions.
            sessionsState.value = message.sessions.sortedByDescending { it.updatedAt }
            listedSessionIds.clear()
            message.sessions.mapTo(listedSessionIds) { it.id }
            // ta-dl4: a session the server no longer lists (and this client is not subscribed to)
            // drops its inspector replies.
            worktreeScriptsState.value = worktreeScriptsState.value.filterKeys(::knownSessionLocked)
            changeRequestsState.value = changeRequestsState.value.filterKeys(::knownSessionLocked)
            mirrorOriginLocked()?.let { mirrorLink?.sessions(it, message.sessions, full = true) }
            providersState.value = message.providers
            workspaceRootState.value = message.workspaceRoot
            hiddenAgentSessionCountState.value = message.hiddenAgentSessionCount
            serverProtocolVersionState.value = message.protocolVersion
            handshakeDone = true
            consentOriginState.value = socketOrigin
            publishConsentLocked()
            // A handshake the server accepted is the success that resets backoff.
            backoff.reset()
            val ids = LinkedHashSet<String>()
            ids.addAll(subscribed)
            // T13.1 §3.1 rule 5: cursors restored from the mirror are capped (pinned + the 10
            // most recently opened); the rest keep their saved copy until opened or until a live
            // event's gap resync. Cursors of this process are all re-attached, as before.
            ids.addAll(tracker.attachedSessions().filter { it !in seededFromMirror })
            ids.addAll(cappedMirrorSessionsLocked(message.sessions))
            // Only the store of THIS socket's server: another origin's session
            // ids never reach it (ta-s8q).
            if (pendingOrigin != null && pendingOrigin == socketOrigin) pendingStore.records.mapTo(ids) { it.sessionId }
            // Claimed for this epoch under the lock: an attach() racing this
            // handler (from any thread) is then a no-op instead of a second
            // attach (T0.3 verify).
            attachedThisEpoch.addAll(ids)
            publishAttachedLocked()
            toAttach = ids.map { it to afterSeqForLocked(it) }
        }
        // On THIS socket only: if it is gone by now, the next one re-attaches.
        for ((sessionId, afterSeq) in toAttach) {
            sendFrameOn(webSocket, ClientMessage.Attach(sessionId, afterSeq))
            requestHydration(sessionId)
        }
        message.workspaceRoot?.let {
            sendFrameOn(webSocket, ClientMessage.Browse(it))
            sendFrameOn(webSocket, ClientMessage.Discover(it))
        }
        // T15.1 (use-tether.ts:802-807): a new socket starts unsubscribed; a standing Overview wish
        // is replaced from a fresh snapshot before any delta is trusted. On THIS socket only.
        synchronized(lock) { if (socket === webSocket) overviewSync.onReady { sendOverviewLocked(webSocket, it) } }
        // ta-q6p r2 (security F1): a registry asked for in this sign-in is asked for again on every
        // new socket, so the list a write is built from is always this socket's. On THIS socket only.
        if (synchronized(lock) { socket === webSocket && providersWanted }) sendFrameOn(webSocket, ClientMessage.ProvidersRequest)
        // ta-2uq (use-tether.ts:798-800): the pre-session catalog for the new-session composer, asked
        // for on every new socket; later changes arrive as the server's pushes. On THIS socket only.
        sendFrameOn(webSocket, ClientMessage.ProvidersSnapshotRequest)
        // Fresh input filed while the socket was not yet live goes out now, right
        // after the re-attach; an already-transmitted record still waits for its
        // session's snapshot.
        drainPending()
        // Published only once the handshake frames are on the wire: whatever a
        // caller sends after observing Connected is ordered after the re-attach.
        connectionState.value = ConnectionState.Connected
    }

    /**
     * §3.1 rule 5: the mirror-restored sessions the ready re-attach includes: pinned ones (per
     * this `ready`) and the [MIRROR_REATTACH_RECENT] most recently opened. Caller holds [lock].
     */
    private fun cappedMirrorSessionsLocked(live: List<AgentSession>): List<String> {
        if (seededFromMirror.isEmpty()) return emptyList()
        val pinned = live.filter { it.pinned }.map { it.id }.toSet()
        val recent = seededFromMirror.filter { lastOpenedAt.containsKey(it) }
            .sortedByDescending { lastOpenedAt.getValue(it) }
            .take(MIRROR_REATTACH_RECENT)
        return seededFromMirror.filter { it in pinned } + recent
    }

    /** Outside the native window: terminal until retryConnection() (user action). */
    private fun haltForVersion(incompatibility: Incompatibility) {
        val ws = synchronized(lock) {
            versionHalt = incompatibility
            cancelTimersLocked()
            detachSocketLocked()
        }
        connectionState.value = ConnectionState.VersionMismatch(incompatibility)
        ws?.close(1000, null)
    }

    /**
     * Snapshot semantics exactly as use-tether.ts: the cursor ALWAYS moves to
     * `throughSeq` and a pending gap resync clears — including a `reset`
     * (cursor was ahead of the journal: throughSeq may be lower) and the v115
     * stateless reply (cursor already at head: no `state`, nothing changed).
     * Only a snapshot WITH state replaces the projection, reconciles pending
     * input and authorises redelivery for its session on this connection.
     * `trimmedBefore` (v115 bounded snapshot) is recorded per session.
     */
    private fun onSnapshot(webSocket: WebSocket, message: ServerMessage.Snapshot) {
        // A frame of a socket let go meanwhile (a sign-in to another server)
        // must not seed a cursor or authorise redelivery on the next one.
        var shown: JsObj? = message.state
        val current = synchronized(lock) {
            if (socket !== webSocket) return@synchronized false
            tracker.onSnapshot(message.sessionId, message.throughSeq)
            mirrorOriginForLocked(message.sessionId)?.let { mirrorLink?.snapshot(it, message) }
            seededFromMirror.remove(message.sessionId)
            val state = message.state
            // A state wins over a saved copy still being read and re-bases the tail; details of
            // turns it still trims stay spliced, exactly as the mirror keeps them.
            if (state != null && mirrorLink != null) shown = sessionStore.rebase(message.sessionId, state, message.trimmedBefore)
            // T6.3: a stateless reply proves the copy held here is at head, so it is live now; a
            // state is live once it is published below (never the copy it replaces).
            if (!message.hasState && message.sessionId in attachedThisEpoch) setLiveLocked(message.sessionId, true)
            true
        }
        if (!current) return
        if (!message.hasState) return
        // The tree is the source of truth and is always kept: live events fold onto it.
        // A state whose required session fields the legacy typed model cannot read
        // leaves no typed projection (the screens show nothing rather than a diverged
        // base); pending input still reconciles against the tree, as on the web.
        val tree = message.state
        val published = shown
        val typed = published?.let { sessionStore.adapt(message.sessionId, it) }
        val wrote = ifCurrent(webSocket) {
            sessionStore.setTrimmedBefore(message.sessionId, message.trimmedBefore)
            if (published != null) sessionStore.publish(message.sessionId, published, typed)
            // SYNC_DESIGN §4.1: only a session attached on THIS socket becomes live (never one the
            // server pushed a snapshot for unasked).
            if (message.sessionId in attachedThisEpoch) setLiveLocked(message.sessionId, true)
        }
        if (!wrote || tree == null) return
        // use-tether.ts:953-984 — the DURABLE acknowledgement, read off the raw
        // journal-folded state exactly as the web does (not the typed view): a key it
        // contains was accepted, whatever it lacks was not and is redelivered below.
        val changed = synchronized(lock) {
            if (socket !== webSocket) return
            val result = PendingInput.reconcileWithSnapshot(pendingStore, message.sessionId, tree)
            pendingStore = result.store
            forgetLocked(result.cleared)
            // T7.4: a state that holds the turn confirms a message with attachments as well.
            if (attachmentsInFlight.containsValue(message.sessionId)) {
                val keys = turnKeysOf(tree)
                attachmentsInFlight.entries.removeAll { (key, session) -> session == message.sessionId && key in keys }
            }
            // Only now is redelivery for this session safe on this connection.
            reconciledSessions.add(message.sessionId)
            // use-tether.ts:975 — never redeliver a key already cleared (tombstoned).
            var discarded = false
            for (record in pendingStore.records.filter { it.key in clearedKeys }) {
                pendingStore = PendingInput.discardKey(pendingStore, record.key).store
                discarded = true
            }
            result.cleared.isNotEmpty() || discarded
        }
        if (changed) persistPending()
        drainPending()
    }

    private fun onEvent(webSocket: WebSocket, message: ServerMessage.Event) {
        val event = message.event
        var buffered = false
        var resyncAfter: Long? = null
        var seqlessCleared: CompletableDeferred<Unit>? = null
        val decision = synchronized(lock) {
            // A let-go socket's event: dropped (its cursor and acks are not ours).
            if (socket !== webSocket) return
            tracker.onEvent(message.sessionId, event.seq, canSend = socketOpen).also { decision ->
                // Only what the cursor folds is mirrored, in frame order (§2.3).
                if (decision == CursorTracker.Decision.Fold) {
                    seqlessCleared = mirrorOriginForLocked(message.sessionId)?.let { mirrorLink?.event(it, message.sessionId, event) }
                    if (event.seq != null && mirrorLink != null) sessionStore.countTail(message.sessionId)
                    // Its saved copy is being read: fold this on top of it when it lands.
                    if (mirrorLink != null) buffered = sessionStore.bufferIfHydrating(message.sessionId, JsCodec.fromJson(event.raw) as JsObj)
                }
                // §3.1 rule 1a applies to the gap resync too (on a gap the cursor is decision.afterSeq).
                if (decision is CursorTracker.Decision.Resync) {
                    resyncAfter = afterSeqForLocked(message.sessionId)
                    // T6.3: events are missing until the resync snapshot lands.
                    setLiveLocked(message.sessionId, false)
                }
            }
        }
        when (decision) {
            CursorTracker.Decision.Ignore,
            CursorTracker.Decision.Drop,
            CursorTracker.Decision.AwaitSnapshot,
            -> return
            is CursorTracker.Decision.Resync -> {
                sendFrameOn(webSocket, ClientMessage.Attach(message.sessionId, resyncAfter))
                return
            }
            CursorTracker.Decision.Fold -> Unit
        }
        // A seqless fold (impossible at v129, §2.3) is shown only once the mirror no longer
        // claims to cover this session: a death right after it then refetches instead of
        // restoring a copy without it. Bounded, so a stuck disk cannot wedge the socket.
        seqlessCleared?.let { cleared ->
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(SEQLESS_CLEAR_WAIT_MS) { cleared.await() } }
        }
        // Live acknowledgement — key alone, never kind.
        val ackedKey = when (event.type) {
            "turn_started" -> event.raw.str("idempotencyKey")
            "queued_message_added" -> event.raw.str("queueId")
            else -> null
        }
        if (ackedKey != null) {
            // use-tether.ts:1022-1040 — acked on the turn STARTING: whatever the turn's
            // outcome later (completed, interrupted, outcome_unknown) it is never re-sent.
            val removed = synchronized(lock) {
                if (socket !== webSocket) return
                attachmentsInFlight.remove(ackedKey)
                val result = PendingInput.ackKey(pendingStore, ackedKey)
                pendingStore = result.store
                if (result.removed) forgetLocked(listOf(ackedKey))
                result.removed
            }
            if (removed) persistPending()
        }
        if (buffered) return
        val tree = synchronized(lock) { sessionStore.tree(message.sessionId) } ?: return
        val next = try {
            reduce(tree, JsCodec.fromJson(event.raw) as JsObj)
        } catch (e: RuntimeException) {
            // The fold is a line port of events.mjs and, like it, assumes the server's full
            // projection shape (a JS reduce throws on the same malformed base). Never let that
            // take down the socket thread: drop the diverged base and ask for a FULL snapshot
            // (no afterSeq — a cursor-at-head attach would come back stateless).
            val current = ifCurrent(webSocket) {
                sessionStore.drop(message.sessionId)
                setLiveLocked(message.sessionId, false)
                // The reducer is the arbiter (§10 C5): the mirrored base goes too.
                mirrorOriginLocked()?.let { mirrorLink?.drop(it, message.sessionId) }
                forgetSavedCopyLocked(message.sessionId)
            }
            if (current) sendFrameOn(webSocket, ClientMessage.Attach(message.sessionId, null))
            return
        }
        // An unchanged projection is the same object (T2.1 Revision 6): nothing to publish.
        var stored = next
        if (next !== tree) {
            val typed = sessionStore.adapt(message.sessionId, next)
            ifCurrent(webSocket) { stored = sessionStore.publish(message.sessionId, next, typed) }
        }
        // Low-1 (r4): the checkpoint is of the tree that was STORED (the capped one), the object
        // checkpointDue compares against, so a cap never makes checkpoints skip.
        event.seq?.let { seq -> maybeCheckpoint(webSocket, message.sessionId, seq, event.type, stored) }
    }

    /**
     * §2.4 local checkpoint: once the tail since the base passes [JournalMirror.checkpointEvery]
     * events (or [JournalMirror.checkpointAtTurnEnd] at a `turn_end`), the folded [tree] through
     * [seq] becomes the mirrored base. The mirror accepts it only if its cursor is exactly [seq]
     * (so a tree that folded anything the DB does not hold is never stored).
     */
    private fun maybeCheckpoint(webSocket: WebSocket, sessionId: String, seq: Long, type: String, tree: JsObj) {
        val link = mirrorLink ?: return
        ifCurrent(webSocket) {
            val origin = mirrorOriginLocked() ?: return@ifCurrent
            if (!sessionStore.checkpointDue(sessionId, type, tree, link.mirror.checkpointEvery, link.mirror.checkpointAtTurnEnd)) return@ifCurrent
            link.mirror.checkpoint(origin, sessionId, seq, tree)
        }
    }

    /**
     * v115 lazy-loaded turns (use-tether.ts `turns-detail`): the full turn projections
     * replace the trimmed stubs in `turnsById`; nothing else changes. Ignored for a
     * session with no projection yet.
     */
    private fun onTurnsDetail(webSocket: WebSocket, message: ServerMessage.TurnsDetail) {
        val tree = synchronized(lock) { sessionStore.tree(message.sessionId) } ?: return
        val turnsById = tree["turnsById"] as? JsObj ?: JsObj.EMPTY
        val next = tree.put("turnsById", turnsById.spread(message.turns))
        val typed = sessionStore.adapt(message.sessionId, next)
        ifCurrent(webSocket) {
            sessionStore.publish(message.sessionId, next, typed)
            mirrorOriginForLocked(message.sessionId)?.let { mirrorLink?.turnsDetail(it, message.sessionId, message.turns, tree) }
            if (mirrorLink != null) sessionStore.addDetails(message.sessionId, message.turns)
        }
    }

    /**
     * Run [write] (a write of the published per-server state) under [lock], and
     * only while [webSocket] is still the current socket: a frame that passed
     * the listener's check just before a sign-in switch (and is still being
     * handled) must not repopulate what the switch cleared. True = written.
     */
    private inline fun ifCurrent(webSocket: WebSocket, write: () -> Unit): Boolean = synchronized(lock) {
        if (socket !== webSocket) return@synchronized false
        write()
        true
    }

    /** Caller holds [lock] (see [ifCurrent]). */
    private fun upsertSessionLocked(session: AgentSession) {
        sessionsState.value = (listOf(session) + sessionsState.value.filter { it.id != session.id })
            .sortedByDescending { it.updatedAt }
        listedSessionIds.add(session.id)
        mirrorOriginLocked()?.let { mirrorLink?.sessions(it, listOf(session), full = false) }
    }

    // ------------------------------------------------------------------
    // Durable send (§5.6)
    // ------------------------------------------------------------------

    override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
        // T7.4: attachments never enter the durable outbox (no redelivery, no queue, no persistence):
        // they go through [sendAttachments] alone, which sends them once on the live socket.
        if (attachments.isNotEmpty()) {
            emitError(ATTACHMENTS_NOT_SENT_COPY)
            return
        }
        recordAndDrain(PendingInput.KIND_SEND, sessionId, text)
    }

    override fun sendAttachments(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
        mention: com.tether.app.protocol.DelegateMention?,
        expectedOrigin: String?,
        expectedEpoch: Long?,
    ): AttachmentSendResult {
        if (attachments.isEmpty()) return AttachmentSendResult.Empty
        if (sessionId.isEmpty()) return AttachmentSendResult.Locked
        // The frame is built and measured before the lock is taken (it can be megabytes); nothing in
        // it depends on the state the lock guards, and the key is fresh.
        val key = PendingInput.newKey()
        val frame = ClientMessage.Send(sessionId, text, key, attachments, mention).encode()
        val frameBytes = AttachmentFrame.utf8Length(frame)
        if (frameBytes > AttachmentFrame.MAX_SEND_FRAME_BYTES) return AttachmentSendResult.TooLarge
        val result = synchronized(lock) {
            val link = AttachmentLink(
                socketBound = socket != null,
                socketOrigin = socketOrigin,
                socketOpen = socketOpen,
                handshakeDone = handshakeDone,
                pendingLoaded = pendingLoaded,
                halted = haltedLocked(),
                pendingOrigin = pendingOrigin,
                sessionLive = sessionId in liveThisEpoch,
            )
            attachmentLinkRefusal(link, expectedOrigin)?.let { return@synchronized it }
            // ta-2ew (R3): a first message bound to its create's socket goes on that socket only.
            if (expectedEpoch != null && expectedEpoch != epoch) return@synchronized AttachmentSendResult.NotConnected
            val ws = socket ?: return@synchronized AttachmentSendResult.NotConnected
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized AttachmentSendResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty() || session.runtimeArchived) return@synchronized AttachmentSendResult.Locked
            val tree = sessionStore.tree(sessionId) ?: return@synchronized AttachmentSendResult.NotLive
            if (tree["activeTurnId"] is JsStr) return@synchronized AttachmentSendResult.Busy
            if (pendingStore.records.any { it.sessionId == sessionId }) return@synchronized AttachmentSendResult.PendingAhead
            if (mention != null && !CommandGuard.mentionOffered(session, mention, providerCatalogState.value)) return@synchronized AttachmentSendResult.NotOffered
            // OkHttp closes the socket rather than queue past its bound: a frame that would pass it
            // now is not handed over. Frames sent outside this lock can still grow the queue before
            // the send below (rare): OkHttp then refuses it, closing the socket; nothing is sent.
            attachmentQueueRefusal(ws.queueSize(), frameBytes)?.let { return@synchronized it }
            if (!ws.send(frame)) return@synchronized AttachmentSendResult.NotConnected
            attachmentsInFlight[key] = sessionId
            while (attachmentsInFlight.size > ATTACHMENTS_IN_FLIGHT_REMEMBERED) attachmentsInFlight.remove(attachmentsInFlight.keys.first())
            AttachmentSendResult.Sent
        }
        // Web #135: an attachment frame is large and never persisted, so a half-open socket
        // swallowing it is the worst case — probe right away.
        if (result == AttachmentSendResult.Sent) probeLink()
        return result
    }

    override fun queueAdd(sessionId: String, text: String) {
        recordAndDrain(PendingInput.KIND_QUEUE, sessionId, text)
    }

    /**
     * ta-8cv r2 (security F1): the draft composer's first message, recorded in the SAME step that
     * checks, under the lock, that the outbox, the live socket and [expectedOrigin] are one server and
     * the socket is still the create's ([expectedEpoch]); a sign-in switch (login / pair, on IO)
     * therefore cannot slip between the check and the record and file it in another server's outbox.
     */
    override fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean {
        var evicted: List<PendingRecord> = emptyList()
        val recorded = synchronized(lock) {
            if (pendingOrigin != expectedOrigin || socketOrigin != expectedOrigin || socket == null) return@synchronized false
            if (epoch != expectedEpoch || haltedLocked()) return@synchronized false
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized false
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty() || session.runtimeArchived) return@synchronized false
            evicted = recordLocked(PendingInput.KIND_SEND, sessionId, text, null, null)
            true
        }
        if (recorded) afterRecord(evicted)
        return recorded
    }

    /**
     * T7.3: a delegated send (v103 `send.mention`), recorded like any send (use-tether.ts sendText with a
     * mention is the same durable path). r2: in ONE step under the lock, the same one that records it:
     * drawn for the server the outbox belongs to ([expectedOrigin] = [pendingOrigin], and the socket's,
     * when there is one: a switch can never slip between the check and the record); the session listed
     * and neither read-only, handed off nor archived; the mention one the catalog that server pushed
     * offers it. Otherwise nothing is recorded.
     */
    override fun sendDelegated(sessionId: String, text: String, attachments: List<Attachment>, mention: com.tether.app.protocol.DelegateMention, expectedOrigin: String?): MentionResult {
        // T7.4: a delegation that carries attachments is [sendAttachments]'s (never the outbox's).
        if (attachments.isNotEmpty()) {
            emitError(ATTACHMENTS_NOT_SENT_COPY)
            return MentionResult.Locked
        }
        var evicted: List<PendingRecord> = emptyList()
        val result = synchronized(lock) {
            val owner = pendingOrigin
            if (expectedOrigin == null || owner != expectedOrigin || (socketOrigin != null && socketOrigin != expectedOrigin)) return@synchronized MentionResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized MentionResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty() || session.runtimeArchived) return@synchronized MentionResult.Locked
            if (!CommandGuard.mentionOffered(session, mention, providerCatalogState.value)) return@synchronized MentionResult.NotOffered
            evicted = recordLocked(PendingInput.KIND_SEND, sessionId, text, null, mention)
            MentionResult.Sent
        }
        if (result != MentionResult.Sent) return result
        afterRecord(evicted)
        return result
    }

    /** use-tether.ts:630 filePending: mint, record, persist, then one drain puts it on the wire. */
    private fun recordAndDrain(
        kind: String,
        sessionId: String,
        text: String,
        attachments: List<Attachment>? = null,
        mention: com.tether.app.protocol.DelegateMention? = null,
    ) {
        val evicted = synchronized(lock) { recordLocked(kind, sessionId, text, attachments, mention) }
        afterRecord(evicted)
    }

    /** Mint a key and file the record in the pending store. Caller holds [lock]; returns the evicted records. */
    private fun recordLocked(
        kind: String,
        sessionId: String,
        text: String,
        attachments: List<Attachment>?,
        mention: com.tether.app.protocol.DelegateMention?,
    ): List<PendingRecord> {
        val result = PendingInput.addRecord(pendingStore, PendingInput.newKey(), kind, sessionId, text, clock(), attachments, mention)
        pendingStore = result.store
        forgetLocked(result.evicted.map { it.key })
        return result.evicted
    }

    /** The outside-the-lock half of a record: the eviction notice, the persist, one drain. */
    private fun afterRecord(evicted: List<PendingRecord>) {
        if (evicted.isNotEmpty()) {
            emitError("${evicted.size} unsent message(s) were dropped — too many are waiting to send.")
        }
        persistPending()
        drainPending()
    }

    // use-tether.ts:1583-1600 — both withdrawal paths reconcile the pending store FIRST:
    // the server no-ops a queue-edit / queue-remove for a queueId it never accepted.
    override fun queueEdit(sessionId: String, queueId: String, text: String) {
        val changed = synchronized(lock) {
            val next = PendingInput.editText(pendingStore, queueId, text)
            (next !== pendingStore).also { pendingStore = next }
        }
        if (changed) persistPending()
        sendFrame(ClientMessage.QueueEdit(sessionId, queueId, text))
    }

    override fun queueRemove(sessionId: String, queueId: String) {
        val discarded = synchronized(lock) {
            val result = PendingInput.discardKey(pendingStore, queueId)
            if (result.removed) forgetLocked(listOf(queueId))
            pendingStore = result.store
            result.removed
        }
        if (discarded) persistPending()
        sendFrame(ClientMessage.QueueRemove(sessionId, queueId))
    }

    /**
     * use-tether.ts:570 drainPending: everything [PendingInput.sendableRecords] lets
     * out over THIS connection, oldest-first, each under its own record key — so a
     * redelivery is always the SAME idempotencyKey / queueId (server dedupe,
     * session-manager.mjs:2520) and never a second new turn. Waits for the
     * handshake and for the persisted store to be restored. Only on a socket
     * opened for the store's own origin, and only on the socket the frames were
     * computed for: if it goes meanwhile, the frames are dropped (they count as
     * one attempt and wait for that origin's next snapshot), never handed to
     * whatever socket is live next (ta-s8q).
     */
    private fun drainPending() {
        val ws: WebSocket
        val frames: List<ClientMessage>
        synchronized(lock) {
            if (!pendingLoaded || !socketOpen || !handshakeDone) return
            if (pendingOrigin == null || pendingOrigin != socketOrigin) return
            ws = socket ?: return
            val sendable = PendingInput.sendableRecords(pendingStore, reconciledSessions)
            if (sendable.isEmpty()) return
            pendingStore = PendingInput.markSent(pendingStore, sendable.map { it.key }, clock())
            frames = sendable.map { record ->
                if (record.kind == PendingInput.KIND_SEND) {
                    ClientMessage.Send(record.sessionId, record.text, record.key, record.attachments, record.mention)
                } else {
                    ClientMessage.QueueAdd(record.sessionId, record.key, record.text)
                }
            }
        }
        raceHook?.invoke(RacePoint.DrainComputed, frames)
        for (frame in frames) sendFrameOn(ws, frame)
        persistPending()
    }

    private suspend fun sweeperLoop() {
        while (scope.isActive) {
            delay(sweepIntervalMs)
            if (synchronized(lock) { stopped }) continue
            val now = clock()
            // Half-open detection: socket OPEN + oldest in-flight record older
            // than 8 s + no inbound frame of ANY kind for 8 s -> force-close.
            val toCancel = synchronized(lock) {
                if (
                    socketOpen &&
                    PendingInput.oldestInFlightAge(pendingStore, now) > PendingInput.UNACKED_CLOSE_MS &&
                    now - lastInboundAt > PendingInput.UNACKED_CLOSE_MS
                ) {
                    socket
                } else {
                    null
                }
            }
            toCancel?.let { dropSocket(it) }
            val unsent = synchronized(lock) {
                val result = PendingInput.expireRecords(pendingStore, now)
                pendingStore = result.store
                forgetLocked(result.unsent.map { it.key })
                result.unsent
            }
            if (unsent.isNotEmpty()) {
                emitError(undeliveredMessage(unsent))
                persistPending()
            }
            drainPending()
        }
    }

    private fun undeliveredMessage(unsent: List<PendingRecord>): String {
        val first = unsent.first()
        val preview = if (first.text.length > 120) first.text.take(120) + "…" else first.text
        return if (unsent.size == 1) {
            "This message could not be delivered and was not sent: \"$preview\""
        } else {
            "${unsent.size} messages could not be delivered. The first was: \"$preview\""
        }
    }

    /**
     * Cold start (use-tether.ts:1225-1232): adopt the tombstones, then union the
     * persisted records under what this process already holds, never adopting a
     * cleared key. Restored records count as possibly transmitted
     * ([PendingInput.restoredFromPreviousProcess]). A store this process set
     * aside for the same origin ([overlay]) sits between the two: it is exact
     * (a record it holds with tries 0 was never transmitted), so it keeps its
     * counts and attachments. The T1.3 expiry (age, retry cap) runs right here,
     * in the same critical section, so nothing past it can drain first
     * (sendableRecords itself has no age check). Caller holds [lock]; [raw] is
     * [pendingOrigin]'s slot. Returns the expired records, for the notice.
     */
    private fun restorePendingLocked(raw: String?, overlay: SetAsideStore?): List<PendingRecord> {
        val persistedCleared = PendingInput.clearedFromPersisted(raw)
        val cleared = LinkedHashSet(persistedCleared)
        overlay?.let { cleared.addAll(it.cleared) }
        cleared.addAll(clearedKeys)
        clearedKeys = LinkedHashSet(cleared.toList().takeLast(PendingInput.MAX_TOMBSTONES))
        var mine = pendingStore
        if (overlay != null) mine = PendingInput.mergeStores(mine, overlay.store, clearedKeys)
        val restored = PendingInput.restoredFromPreviousProcess(PendingInput.fromPersisted(raw), clock())
        pendingStore = PendingInput.mergeStores(mine, restored, clearedKeys)
        val expiry = PendingInput.expireRecords(pendingStore, clock())
        pendingStore = expiry.store
        forgetLocked(expiry.unsent.map { it.key })
        pendingLoaded = true
        return expiry.unsent
    }

    /** use-tether.ts:360 forget: tombstone removed keys, oldest falling off first. Caller holds [lock]. */
    private fun forgetLocked(keys: Collection<String>) {
        if (keys.isEmpty()) return
        clearedKeys.addAll(keys)
        if (clearedKeys.size > PendingInput.MAX_TOMBSTONES) {
            clearedKeys = LinkedHashSet(clearedKeys.toList().takeLast(PendingInput.MAX_TOMBSTONES))
        }
    }

    private val persistMutex = Mutex()

    // Guarded by persistMutex: the version each origin's slot holds.
    private val persistedVersions = HashMap<String, Long>()

    /**
     * Write the store after EVERY change, into its own origin's slot. One
     * DataStore edit replaces the whole payload atomically (temp file +
     * rename), so a crash mid-write leaves the previous complete store, never a
     * torn one. Writes are serialized and conflated: each writes the newest
     * version of the store AND its origin as they are when it runs (so a write
     * queued before a sign-in switch writes nothing of the old store into the
     * new slot), and an older snapshot can never land after a newer one in the
     * same slot (which could resurrect an acked record or drop a new one).
     */
    private fun persistPending() {
        synchronized(lock) {
            if (!pendingLoaded) return
            pendingVersion++
        }
        scope.launch {
            persistMutex.withLock {
                val snapshot = synchronized(lock) {
                    val origin = pendingOrigin
                    if (!pendingLoaded || origin == null) return@withLock
                    PersistSnapshot(origin, pendingVersion, pendingStore, clearedKeys.toList())
                }
                if (snapshot.version <= (persistedVersions[snapshot.origin] ?: 0L)) return@withLock
                try {
                    settings.writePendingInput(snapshot.origin, PendingInput.toPersistable(snapshot.store, snapshot.cleared))
                    persistedVersions[snapshot.origin] = snapshot.version
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed write must never break sending; the in-memory store
                    // still redelivers for the life of the process.
                }
            }
        }
    }

    private class PersistSnapshot(val origin: String, val version: Long, val store: PendingStore, val cleared: List<String>)

    /**
     * Persist a store set aside by an origin switch into ITS origin's slot, in
     * the same serialized, versioned order as [persistPending]. A store that
     * was never merged with its slot (filed before the process bound it) is
     * merged with it here, never written over it. Skipped once stop() wiped
     * everything after the switch.
     */
    private fun persistSetAside(write: SetAsideWrite) {
        scope.launch {
            persistMutex.withLock {
                if (synchronized(lock) { pendingWipe } != write.wipe) return@withLock
                if (write.version <= (persistedVersions[write.origin] ?: 0L)) return@withLock
                try {
                    var store = write.store
                    var cleared = write.cleared
                    if (write.mergeWithDisk) {
                        val raw = settings.readPendingInput(write.origin)
                        cleared = (PendingInput.clearedFromPersisted(raw) + cleared).distinct()
                            .takeLast(PendingInput.MAX_TOMBSTONES)
                        store = PendingInput.mergeStores(store, PendingInput.fromPersisted(raw), cleared)
                    }
                    settings.writePendingInput(write.origin, PendingInput.toPersistable(store, cleared))
                    persistedVersions[write.origin] = write.version
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The set-aside copy in memory still holds for this process.
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // T11.1 workspace files (/api/files/*)
    // ------------------------------------------------------------------

    /**
     * Over [authHttp] (never follows a redirect), with the server and the credential read
     * together under [lock] for each call: a call can only ever carry a credential to the origin
     * it was adopted for, whatever sign-in happens in between.
     */
    override val files: WorkspaceFiles = HttpWorkspaceFiles(authHttp, authority = {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        when {
            base == null || credential == null -> FilesAuthority.SignedOut
            blockedBeforeConnect(base) -> FilesAuthority.LocalNetworkBlocked
            else -> FilesAuthority.Paired(base) { request -> request.authorize(credential, base) }
        }
    })

    /** T6.2: `/api/tool-media/…`, over [authHttp] with the same per-call (server, credential) read as [files]. */
    override val toolMedia: ToolMediaSource = HttpToolMedia(authHttp, authority = {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        when {
            base == null || credential == null -> FilesAuthority.SignedOut
            blockedBeforeConnect(base) -> FilesAuthority.LocalNetworkBlocked
            else -> FilesAuthority.Paired(base) { request -> request.authorize(credential, base) }
        }
    })

    /**
     * T15.3: `/api/overview/host` and `/api/overview/usage`, over [authHttp] with the same per-call
     * (server, credential) read as [files]: a reading can only be asked of, and tagged with, the
     * origin its credential belongs to.
     */
    override val overviewMetrics: OverviewMetricsSource = HttpOverviewMetrics(authHttp, authority = {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        when {
            base == null || credential == null -> FilesAuthority.SignedOut
            blockedBeforeConnect(base) -> FilesAuthority.LocalNetworkBlocked
            else -> FilesAuthority.Paired(base) { request -> request.authorize(credential, base) }
        }
    })

    /**
     * ta-9q2: Settings' Claude accounts (`GET /api/claude-accounts`, `/sync`, `/<id>/status`), over
     * [authHttp] with the same per-call (server, credential) read as [files]. GET only: the
     * owner-grade writes (403 to a device token) are never sent.
     */
    override val claudeAccounts: ClaudeAccountsSource = HttpClaudeAccounts(authHttp, authority = {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        when {
            base == null || credential == null -> FilesAuthority.SignedOut
            blockedBeforeConnect(base) -> FilesAuthority.LocalNetworkBlocked
            else -> FilesAuthority.Paired(base) { request -> request.authorize(credential, base) }
        }
    })

    /**
     * T10.4: Settings → Devices, over [authHttp] with the same per-call (server, credential) read as
     * [files], plus which kind of sign-in that credential is (read in the same lock, so the two
     * always agree). Each call goes only to the server the screen names. r2 (security F2): each call
     * carries a handle on its credential; when the screen learns that credential is dead (this phone
     * revoked), [handleCredentialRejected] signs out at once, and only if it is still the one in force.
     */
    override val deviceSecurity: DeviceSecuritySource = HttpDeviceSecurity(authHttp, onRejected = { handle ->
        val credential = handle.credential as? Credential
        if (credential != null) {
            // Off the caller's thread (the screen calls from main): the mirror's shred is a Keystore call.
            scope.launch(Dispatchers.IO) {
                handleCredentialRejected(credential, if (credential is Credential.Cookie) SignedOutReason.SessionExpired else SignedOutReason.DeviceUnpaired)
            }
        }
    }, authority = {
        val (base, credential) = synchronized(lock) { baseUrlValue to credentialValue }
        val files = when {
            base == null || credential == null -> FilesAuthority.SignedOut
            blockedBeforeConnect(base) -> FilesAuthority.LocalNetworkBlocked
            else -> FilesAuthority.Paired(base) { request -> request.authorize(credential, base) }
        }
        SecurityAuthority(
            files,
            when (credential) {
                is Credential.DeviceToken -> AppSignIn.DeviceToken
                is Credential.Cookie -> AppSignIn.SessionCookie
                null -> null
            },
            credential?.let(::SignInHandle),
        )
    })

    // ------------------------------------------------------------------
    // Fire-and-forget commands
    // ------------------------------------------------------------------

    /**
     * Subscribe [sessionId]. Idempotent per connection epoch: once a session was
     * attached on the current socket (by this call or by the ready re-attach),
     * another attach() sends nothing. Before the handshake it only subscribes;
     * the ready handler attaches it (after `hello`).
     */
    override fun attach(sessionId: String) {
        attachNow(sessionId, expectedOrigin = null)
    }

    /**
     * ta-2ew (R1): the check that [origin] is the server in force and the subscription are one step
     * under the lock, and the `attach` frame goes on the socket of that step only ([sendFrameOn]): a
     * sign-in switch either comes first (refused here) or after (its clear drops the subscription,
     * and the frame is never handed to the next server's socket).
     */
    override fun attachIfConfigured(sessionId: String, origin: String): Boolean = attachNow(sessionId, expectedOrigin = origin)

    /** [attach]; with [expectedOrigin], only while it is the configured server (false: nothing done). */
    private fun attachNow(sessionId: String, expectedOrigin: String?): Boolean {
        var on: WebSocket? = null
        val afterSeq = synchronized(lock) {
            if (expectedOrigin != null && originStandingLocked(expectedOrigin) != OriginStanding.Configured) return false
            on = socket
            subscribed.add(sessionId)
            // §3.1 rule 5: "most recently opened" orders the capped ready re-attach.
            mirrorOrigin?.let { origin ->
                mirrorLink?.opened(origin, sessionId)
                lastOpenedAt[sessionId] = clock()
            }
            if (!socketOpen || !handshakeDone || !attachedThisEpoch.add(sessionId)) {
                // Offline (or attached already): the saved copy, if there is one (§4.2).
                null
            } else {
                publishAttachedLocked()
                afterSeqForLocked(sessionId) to true
            }
        }
        requestHydration(sessionId)
        if (afterSeq == null) return true
        val frame = ClientMessage.Attach(sessionId, afterSeq.first)
        // The socket that was live in the step above, never a later one (a replacement re-attaches
        // the subscription on its own ready).
        if (expectedOrigin == null) sendFrame(frame) else on?.let { sendFrameOn(it, frame) }
        return true
    }

    /**
     * T13.2 r2: the one path an INTERRUPT takes to the wire, under the same rules as [stopCommand].
     * Under the lock, in order: a live, handshaken socket of a running (not halted) client; the key
     * drawn for THIS server ([expectedOrigin] = the socket's origin); the session confirmed live on it
     * ([liveThisEpoch]: a saved or catching-up copy's stale "busy" never interrupts a real turn);
     * listed, and neither read-only nor handed off (fail closed); T6.7: the key's turn
     * ([expectedTurnId]) still the open active turn of the session's current projection (the
     * reducer's own [isOpenCurrentTurn]); then enqueued on that socket. Nothing is retried, held or
     * persisted.
     */
    override fun interrupt(sessionId: String, expectedOrigin: String?, expectedTurnId: String): InterruptResult {
        if (sessionId.isEmpty()) return InterruptResult.Locked
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized InterruptResult.NotConnected
            if (expectedOrigin != origin) return@synchronized InterruptResult.NotLive
            if (sessionId !in liveThisEpoch) return@synchronized InterruptResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized InterruptResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty()) return@synchronized InterruptResult.Locked
            // T6.7: the frame names no turn, so the server would stop whichever one runs now.
            if (!isCurrentTurn(sessionStore.tree(sessionId), expectedTurnId)) return@synchronized InterruptResult.NotCurrentTurn
            if (!ws.send(ClientMessage.Interrupt(sessionId).encode())) return@synchronized InterruptResult.NotConnected
            val bound = interruptsBound.getOrPut(sessionId) { ArrayDeque() }
            if (bound.size >= INTERRUPTS_REMEMBERED) bound.removeFirst()
            bound.addLast(expectedTurnId)
            InterruptResult.Sent
        }
        if (result == InterruptResult.NotConnected) emitError("The secure link is reconnecting. The turn was not interrupted.")
        return result
    }

    /** T6.7: [turnId] is [tree]'s open active turn (`activeTurnId`, its status not "done"). */
    private fun isCurrentTurn(tree: JsObj?, turnId: String): Boolean =
        tree != null && turnId.isNotEmpty() && isOpenCurrentTurn(tree, JsStr(turnId))

    /**
     * T6.7: per session, the turns this socket's sent interrupts were bound to, oldest first (bounded;
     * a new socket starts clean). An `interrupt_result` that names another turn means the server
     * stopped a turn that began after the tap: said, never acted on. Guarded by [lock].
     */
    private val interruptsBound = HashMap<String, ArrayDeque<String>>()

    /**
     * `interrupt_result` (use-tether.ts:1163-1170): `cancel_requested` / `turn_end` carry the state,
     * so only a failure is shown (the server's text, cleaned and attributed); `no_active_turn` is a
     * stale tap, not a fault. T6.7: a `requested` for a turn other than the one the tap was bound to
     * is said in the client's words.
     */
    private fun onInterruptResult(webSocket: WebSocket, message: ServerMessage.InterruptResult) {
        var reachedLater = false
        if (message.status == "failed") {
            // r2: checked current and emitted in one step (a socket let go in between says nothing).
            if (!emitServerErrorIfCurrent(webSocket, message.error, fallback = INTERRUPT_NOT_DELIVERED)) return
            // r3: the turn stays "cancelling"; its interrupt controls unlock so the operator can retry.
            message.turnId?.takeIf { it.isNotEmpty() }?.let { turnId ->
                synchronized(lock) {
                    if (socket === webSocket && message.sessionId.isNotEmpty()) failedInterruptsState.value = failedInterruptsState.value + (message.sessionId to turnId)
                }
            }
        }
        synchronized(lock) {
            if (socket !== webSocket) return
            val bound = interruptsBound[message.sessionId]
            if (bound != null && bound.isNotEmpty()) {
                val turnId = message.turnId
                if (turnId == null || !bound.remove(turnId)) {
                    bound.removeFirst()
                    reachedLater = message.status == "requested" && turnId != null
                }
                if (bound.isEmpty()) interruptsBound.remove(message.sessionId)
            }
        }
        if (reachedLater) emitError(INTERRUPT_REACHED_LATER_TURN)
    }

    /**
     * T6.7: a server's error text, cleaned ([LabelText.error]) and kept apart from the client's own
     * words ([TetherClient.serverErrors]); nothing visible left: the client's [fallback], if any.
     * r2: only while [webSocket] is the current socket, checked and emitted under [lock] in one step
     * (no window for a server switch between them), and tagged with that socket's origin. False when
     * the socket was already let go (nothing emitted).
     */
    private fun emitServerErrorIfCurrent(webSocket: WebSocket, text: String?, fallback: String? = null): Boolean =
        synchronized(lock) {
            val origin = socketOrigin
            if (socket !== webSocket || origin == null) return@synchronized false
            val cleaned = LabelText.error(text)
            if (cleaned.isNotEmpty()) serverErrorsFlow.tryEmit(ServerErrorText(cleaned, origin)) else fallback?.let(::emitError)
            true
        }

    override fun fetchTurns(sessionId: String, fromIndex: Int, toIndex: Int) {
        sendFrame(ClientMessage.FetchTurns(sessionId, fromIndex, toIndex))
    }

    override fun approval(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        choiceId: String?,
        decision: String?,
        grantedPermissions: GrantedPermissions?,
    ): ConsentResult {
        // The wire type refuses both-or-neither and a grant without a choice; refuse before it throws.
        if ((choiceId == null) == (decision == null) || (grantedPermissions != null && choiceId == null)) {
            return ConsentResult.InvalidChoice
        }
        val message = ClientMessage.Approval(sessionId, requestId, choiceId, decision, grantedPermissions)
        return transmitConsent(sessionId, requestId, expectedFingerprint, ConsentGuard::pendingApproval) { _, request ->
            ConsentGuard.checkApproval(request, choiceId, decision, grantedPermissions) ?: message
        }
    }

    override fun answerQuestion(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        picks: List<ConsentGuard.QuestionPick>,
        skipped: Set<Int>,
    ): ConsentResult =
        transmitConsent(sessionId, requestId, expectedFingerprint, ConsentGuard::pendingQuestion) { tree, request ->
            when {
                // An answer already on record (question_answered, from this or another device) closes it.
                ConsentGuard.isAnswered(tree, requestId) -> ConsentResult.NotPending
                else -> ConsentGuard.buildAnswers(request, picks, skipped)?.let { reply ->
                    ClientMessage.Question(sessionId, requestId, reply.answers, reply.response)
                } ?: ConsentResult.InvalidChoice
            }
        }

    /**
     * T6.3 (SYNC_DESIGN §5.1 I2/I3, §5.4): the one path an operator decision takes to the wire.
     * Under the lock, in order: a live, handshaken socket; the session listed, attached and
     * confirmed on it ([liveThisEpoch]); not read-only or handed off (an unlisted session is refused
     * too: fail closed); the request pending in the active turn with the SAME fingerprint the card
     * rendered (a re-raised or replaced request never matches); not already decided here; an offered
     * choice ([check]); then claimed in the ledger and enqueued on that socket. A frame the socket
     * refuses releases its claim (nothing left the device). Nothing is retried, held or persisted,
     * and nothing about the decision is logged.
     */
    private inline fun transmitConsent(
        sessionId: String,
        requestId: String,
        expectedFingerprint: String,
        pending: (JsObj?, String) -> JsObj?,
        /** The frame to send ([ClientMessage]), or the refusal ([ConsentResult]). */
        decide: (JsObj?, JsObj) -> Any,
    ): ConsentResult {
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone) return@synchronized ConsentResult.NotConnected
            if (sessionId !in liveThisEpoch) return@synchronized ConsentResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized ConsentResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty()) return@synchronized ConsentResult.Locked
            val tree = sessionStore.tree(sessionId)
            val turnId = ConsentGuard.activeTurnId(tree) ?: return@synchronized ConsentResult.NotPending
            val request = pending(tree, requestId) ?: return@synchronized ConsentResult.NotPending
            val fingerprint = ConsentGuard.fingerprint(origin, turnId, request)
            if (fingerprint != expectedFingerprint) return@synchronized ConsentResult.NotPending
            val entry = ConsentLedger.Entry(origin, sessionId, turnId, requestId, fingerprint, epoch)
            if (consentLedger.contains(entry)) return@synchronized ConsentResult.AlreadyDecided
            val text = when (val decided = decide(tree, request)) {
                is ConsentResult -> return@synchronized decided
                is ClientMessage -> decided.encode()
                else -> return@synchronized ConsentResult.InvalidChoice
            }
            if (!consentLedger.claim(entry, ::stillPendingLocked)) return@synchronized ConsentResult.AlreadyDecided
            if (!ws.send(text)) {
                consentLedger.release(entry)
                return@synchronized ConsentResult.NotConnected
            }
            publishConsentLocked()
            ConsentResult.Sent
        }
        // use-tether.ts:325-328: the web's words when the link is down; the decision is not kept.
        if (result == ConsentResult.NotConnected) emitError("The secure link is reconnecting. Your input was not sent.")
        return result
    }

    /**
     * I1: may a ledger entry still be pending? Only entries of the current server can be checked
     * against a projection; any other server's are kept (treated as pending). Caller holds [lock].
     */
    private fun stillPendingLocked(entry: ConsentLedger.Entry): Boolean {
        if (entry.origin != socketOrigin) return true
        val tree = sessionStore.tree(entry.sessionId) ?: return true
        if (ConsentGuard.activeTurnId(tree) != entry.activeTurnId) return false
        val request = ConsentGuard.pendingApproval(tree, entry.requestId) ?: ConsentGuard.pendingQuestion(tree, entry.requestId) ?: return false
        return ConsentGuard.fingerprint(entry.origin, entry.activeTurnId, request) == entry.fingerprint
    }

    /** Publish the decided / unconfirmed keys of the current socket's server. Caller holds [lock]. */
    private fun publishConsentLocked() {
        val origin = if (handshakeDone) socketOrigin else null
        decidedState.value = consentLedger.keysFor(origin)
        unconfirmedState.value = consentLedger.unconfirmedFor(origin, epoch)
    }

    /**
     * T6.4: the one path a background command's STOP takes to the wire. Under the lock, in order: a
     * live, handshaken socket of a running (not halted) client; the key drawn for THIS server
     * ([expectedOrigin] = the socket's origin); the session confirmed live on it; not read-only or handed off (an
     * unlisted session is refused: fail closed); the session's current projection lists the command
     * as running; then enqueued on that socket. Nothing is retried, held or persisted.
     */
    override fun stopCommand(sessionId: String, commandId: String, expectedOrigin: String?): StopCommandResult {
        if (sessionId.isEmpty() || commandId.isEmpty()) return StopCommandResult.NotRunning
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized StopCommandResult.NotConnected
            // Bound to the server that drew the row: a key composed for another origin never stops here.
            if (expectedOrigin != origin) return@synchronized StopCommandResult.NotLive
            if (sessionId !in liveThisEpoch) return@synchronized StopCommandResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized StopCommandResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty()) return@synchronized StopCommandResult.Locked
            if (!isRunningCommand(sessionStore.tree(sessionId), commandId)) return@synchronized StopCommandResult.NotRunning
            if (!ws.send(ClientMessage.StopCommand(sessionId, commandId).encode())) return@synchronized StopCommandResult.NotConnected
            StopCommandResult.Sent
        }
        if (result == StopCommandResult.NotConnected) emitError("The secure link is reconnecting. The command was not stopped.")
        return result
    }

    /**
     * T7.3: the one path a `!` command RUN takes to the wire. Under the lock, in order: a live,
     * handshaken socket of a running (not halted) client; the composer drawn for THIS server
     * ([expectedOrigin] = the socket's origin); the session confirmed live on it; listed, and neither
     * read-only, handed off nor archived (fail closed); the provider offered command mode by the
     * server, a command of the right shape, and no running turn for a foreground run
     * ([CommandGuard.checkRun]); then enqueued on that socket with a fresh idempotency key. Nothing is
     * retried, held or persisted (use-tether.ts runCommand is a direct send too).
     */
    override fun runCommand(sessionId: String, command: String, background: Boolean, expectedOrigin: String?): RunCommandResult {
        if (sessionId.isEmpty()) return RunCommandResult.Locked
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized RunCommandResult.NotConnected
            if (expectedOrigin != origin) return@synchronized RunCommandResult.NotLive
            if (sessionId !in liveThisEpoch) return@synchronized RunCommandResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized RunCommandResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty()) return@synchronized RunCommandResult.Locked
            CommandGuard.checkRun(session, providersState.value, sessionStore.tree(sessionId), command, background)?.let { return@synchronized it }
            val frame = ClientMessage.RunCommand(sessionId, command, PendingInput.newKey(), if (background) true else null)
            if (!ws.send(frame.encode())) return@synchronized RunCommandResult.NotConnected
            RunCommandResult.Sent
        }
        if (result == RunCommandResult.NotConnected) emitError("Not connected — reconnecting. Try the command again in a moment.")
        return result
    }

    /**
     * T7.3: the one path a Background (Ctrl+B) takes to the wire, under [runCommand]'s link rules,
     * bound to the turn the key was drawn for: [expectedTurnId] must still be the session's open
     * FOREGROUND command turn in its current projection. Nothing is retried, held or persisted.
     */
    override fun backgroundCommand(sessionId: String, expectedOrigin: String?, expectedTurnId: String): BackgroundCommandResult {
        if (sessionId.isEmpty()) return BackgroundCommandResult.Locked
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized BackgroundCommandResult.NotConnected
            if (expectedOrigin != origin) return@synchronized BackgroundCommandResult.NotLive
            if (sessionId !in liveThisEpoch) return@synchronized BackgroundCommandResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized BackgroundCommandResult.Locked
            if (session.readOnly || !session.handedOffTo.isNullOrEmpty() || session.runtimeArchived) return@synchronized BackgroundCommandResult.Locked
            CommandGuard.checkBackground(sessionStore.tree(sessionId), expectedTurnId)?.let { return@synchronized it }
            if (!ws.send(ClientMessage.BackgroundCommand(sessionId).encode())) return@synchronized BackgroundCommandResult.NotConnected
            BackgroundCommandResult.Sent
        }
        if (result == BackgroundCommandResult.NotConnected) emitError("The secure link is reconnecting. The command was not moved to the background.")
        return result
    }

    /** T7.3: use-tether.ts:787 `providers-snapshot` (a read). */
    override fun requestProviderCatalog(): Boolean = sendFrame(ClientMessage.ProvidersSnapshotRequest)

    override fun refreshProviders(key: String, expectedEpoch: Long): ProviderRefreshResult = synchronized(lock) {
        val ws = socket
        if (ws == null || socketOrigin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized ProviderRefreshResult.NotConnected
        if (expectedEpoch != epoch) return@synchronized ProviderRefreshResult.NotConnected
        if (key.isEmpty() || key.length > NewSessionGuard.KEY_MAX) return@synchronized ProviderRefreshResult.NotOffered
        val entry = liveCatalogLocked()?.singleOrNull { it.key == key } ?: return@synchronized ProviderRefreshResult.NotOffered
        if (!refreshThrottle.admit(key, epoch, clock(), entry)) return@synchronized ProviderRefreshResult.Throttled
        if (!ws.send(ClientMessage.RefreshProviders(listOf(key)).encode())) return@synchronized ProviderRefreshResult.NotConnected
        ProviderRefreshResult.Sent
    }

    /**
     * T6.6: the one path a notice's dismissal takes to the wire. Under the lock, in order: a live,
     * handshaken socket of a running (not halted) client; the X drawn for THIS server; the session
     * listed and confirmed live on it (read-only and handed-off sessions may dismiss: the server
     * allows it); a bounded key the session's CURRENT projection still shows (the reducer's own
     * [projectionHasNoticeKey]); not already sent on this connection; then enqueued on that socket.
     * Nothing is retried, held or persisted.
     */
    override fun dismissNotice(sessionId: String, dismissKey: String, expectedOrigin: String?): NoticeResult {
        if (sessionId.isEmpty() || dismissKey.isEmpty() || dismissKey.length > DISMISS_KEY_MAX) return NoticeResult.NotShown
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized NoticeResult.NotConnected
            if (expectedOrigin != origin) return@synchronized NoticeResult.NotLive
            if (sessionId !in liveThisEpoch) return@synchronized NoticeResult.NotLive
            sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized NoticeResult.Locked
            if (!projectionHasNoticeKey(sessionStore.tree(sessionId), dismissKey)) return@synchronized NoticeResult.NotShown
            val sent = Triple(epoch, sessionId, dismissKey)
            if (sent in dismissalsSent) return@synchronized NoticeResult.AlreadySent
            if (!ws.send(ClientMessage.DismissNotice(sessionId, dismissKey).encode())) return@synchronized NoticeResult.NotConnected
            if (dismissalsSent.size >= DISMISSALS_REMEMBERED) dismissalsSent.remove(dismissalsSent.first())
            dismissalsSent.add(sent)
            NoticeResult.Sent
        }
        if (result == NoticeResult.NotConnected) emitError("The secure link is reconnecting. The notice was not dismissed.")
        return result
    }

    /** T6.6: the dismissals sent, per connection epoch (bounded; a new connection starts clean). Guarded by [lock]. */
    private val dismissalsSent = LinkedHashSet<Triple<Long, String, String>>()

    /** `backgroundCommands` holds [commandId] with status "running" (the fold's own projection). */
    private fun isRunningCommand(tree: JsObj?, commandId: String): Boolean =
        (tree?.get("backgroundCommands") as? JsArr)?.any { value ->
            val command = value as? JsObj
            (command?.get("commandId") as? JsStr)?.value == commandId &&
                (command["status"] as? JsStr)?.value == "running"
        } == true

    /**
     * ta-895 / ta-8cv: the one path a new session takes to the wire. Under the lock, in order: a
     * live, handshaken socket of a running (not halted) client, the one the draft was composed on
     * ([NewSessionRequest.linkEpoch]); the row drawn for THIS server ([expectedOrigin] = the
     * socket's origin); the row resolved again against the catalog THIS socket delivered, or, with
     * none in, the base providers its `ready` listed, and the web's frame built on it
     * ([NewSessionGuard.resolve], [CreateFrame.build]); then `create` enqueued on that socket.
     * Nothing is retried, held or persisted (never the outbox).
     */
    override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized NewSessionResult.NotConnected
            if (request.linkEpoch != epoch) return@synchronized NewSessionResult.NotConnected
            if (expectedOrigin != origin) return@synchronized NewSessionResult.NotLive
            val frame = NewSessionGuard.resolve(request, liveCatalogLocked(), providersState.value) ?: return@synchronized NewSessionResult.NotOffered
            if (!ws.send(frame.encode())) return@synchronized NewSessionResult.NotConnected
            NewSessionResult.Sent
        }
        if (result == NewSessionResult.NotConnected) emitError("The secure link is reconnecting. The session was not created.")
        return result
    }

    /**
     * ta-23f: `worktree-inspect` for the draft composer. Under the lock: a live, handshaken socket of a
     * running client, still the one the composer asked on ([expectedEpoch]); a non-empty [cwd]; a
     * [requestId] within the server's bound. A read: never queued, never resent.
     */
    override fun inspectWorktree(cwd: String, requestId: String, expectedEpoch: Long): Boolean {
        if (cwd.isEmpty() || cwd.length > WorktreeSourceInfo.MAX_PATH) return false
        if (requestId.isEmpty() || requestId.length > INSPECT_REQUEST_ID_MAX) return false
        return synchronized(lock) {
            val ws = socket
            if (ws == null || socketOrigin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized false
            if (expectedEpoch != epoch) return@synchronized false
            ws.send(ClientMessage.WorktreeInspect(cwd, requestId).encode())
        }
    }

    /** ta-895: [providerCatalogState] when the CURRENT socket delivered it, else null. Caller holds [lock]. */
    private fun liveCatalogLocked(): List<ProviderCatalogEntry>? {
        val origin = socketOrigin ?: return null
        if (catalogEpoch != epoch || catalogOrigin != origin) return null
        return providerCatalogState.value
    }

    override fun resumeHistory(historyId: String, cwd: String) {
        sendFrame(ClientMessage.Resume(historyId, cwd))
    }

    /** T5.2: use-tether.ts:1489-1495 — the profile rides along only when the history names one. */
    override fun resume(history: HistorySession): Boolean =
        sendFrame(ClientMessage.Resume(history.historyId, history.cwd, history.profileId?.takeIf { it.isNotEmpty() }))

    override fun discover(cwd: String) {
        sendFrame(ClientMessage.Discover(cwd))
    }

    override fun browse(cwd: String?) {
        sendFrame(ClientMessage.Browse(cwd))
    }

    /**
     * T7.2: the one path a session control takes to the wire. Under the lock, in order: a live,
     * handshaken socket of a running (not halted) client; the control drawn for THIS server
     * ([expectedOrigin] = the socket's origin); the session confirmed live on it; listed, and neither
     * read-only nor handed off (fail closed) — the one exception (T6.6 r2): a handed-off source may
     * still decline its limit prompt / cancel its scheduled resume
     * ([SessionControlsGuard.allowedWhileHandedOff]), since that resume would otherwise start a turn
     * there after the handoff; the value one the session's current state offers, and
     * a most-permissive posture confirmed ([SessionControlsGuard.check]); then enqueued on that
     * socket. Nothing is retried, held or persisted.
     */
    override fun sessionControl(sessionId: String, control: SessionControl, expectedOrigin: String?): ControlResult {
        if (sessionId.isEmpty()) return ControlResult.NotOffered
        val result = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized ControlResult.NotConnected
            if (expectedOrigin != origin) return@synchronized ControlResult.NotLive
            if (sessionId !in liveThisEpoch) return@synchronized ControlResult.NotLive
            val session = sessionsState.value.firstOrNull { it.id == sessionId } ?: return@synchronized ControlResult.Locked
            if (session.readOnly) return@synchronized ControlResult.Locked
            if (!session.handedOffTo.isNullOrEmpty() && !SessionControlsGuard.allowedWhileHandedOff(control)) return@synchronized ControlResult.Locked
            val codex = codexControlsState.value[sessionId]?.snapshot
            val opencode = opencodeControlsState.value[sessionId]?.snapshot
            SessionControlsGuard.check(session, sessionControlsState.value[sessionId], codex, opencode, control, sessionStore.tree(sessionId))?.let { return@synchronized it }
            val frame = SessionControlsGuard.frame(sessionId, control, java.util.UUID.randomUUID().toString())
            if (!ws.send(frame.encode())) return@synchronized ControlResult.NotConnected
            // use-tether.ts executeCodexControl: the panel shows the action in flight until its result.
            if (SessionControlsGuard.isCodexAction(control)) {
                codexControlsState.value = codexControlsState.value + (sessionId to ProviderControlsState(codex, true, null))
            } else if (SessionControlsGuard.isOpencodeAction(control)) {
                opencodeControlsState.value = opencodeControlsState.value + (sessionId to ProviderControlsState(opencode, true, null))
            }
            ControlResult.Sent
        }
        if (result == ControlResult.NotConnected) emitError("The secure link is reconnecting. The setting was not changed.")
        return result
    }

    /** T7.2: use-tether.ts:1693 requestCodexControls (a read). Busy until the snapshot lands. */
    override fun requestCodexControls(sessionId: String): Boolean = synchronized(lock) {
        val sent = sendFrame(ClientMessage.CodexControlsRequest(sessionId))
        val current = codexControlsState.value[sessionId]
        codexControlsState.value = codexControlsState.value +
            (sessionId to ProviderControlsState(current?.snapshot, sent, if (sent) null else "The provider catalogs were not requested."))
        sent
    }

    override fun requestOpencodeControls(sessionId: String): Boolean = synchronized(lock) {
        val sent = sendFrame(ClientMessage.OpencodeControlsRequest(sessionId))
        val current = opencodeControlsState.value[sessionId]
        opencodeControlsState.value = opencodeControlsState.value +
            (sessionId to ProviderControlsState(current?.snapshot, sent, if (sent) null else "The provider catalogs were not requested."))
        sent
    }

    override fun requestGitFileDiff(sessionId: String, path: String): Boolean {
        requestedGitFileDiffs.add(sessionId to path)
        return sendFrame(ClientMessage.GitDiffFileRequest(sessionId, path)).also { sent ->
            if (!sent) requestedGitFileDiffs.remove(sessionId to path)
        }
    }

    override fun requestWorktreeDiff(sessionId: String): Boolean =
        sendFrame(ClientMessage.WorktreeDiffRequest(sessionId))

    override fun requestWorktreeScripts(sessionId: String): Boolean =
        sendFrame(ClientMessage.WorktreeScriptsRequest(sessionId))

    override fun requestChangeRequest(sessionId: String, refresh: Boolean): Boolean =
        sendFrame(ClientMessage.ChangeRequestFetch(sessionId, refresh.takeIf { it }))

    override fun requestSessionControls(sessionId: String) {
        sendFrame(ClientMessage.SessionControlsRequest(sessionId))
    }

    override fun requestWarmSessionControls(sessionId: String) {
        sendFrame(ClientMessage.SessionControlsRequest(sessionId, warm = true))
    }

    override fun pin(sessionId: String, pinned: Boolean) {
        sendFrame(ClientMessage.Pin(sessionId, pinned))
    }

    override fun rename(sessionId: String, name: String) {
        sendFrame(ClientMessage.Rename(sessionId, name))
    }

    override fun archive(sessionId: String) {
        sendFrame(ClientMessage.Archive(sessionId))
    }

    /**
     * T13.2 r2: End session. Under the lock: a live, handshaken socket of a running (not halted)
     * client; the key drawn for THIS server (r3: [expectedOrigin] = the socket's origin, so an End
     * armed before a server switch never ends a same-id session on the new one); the session listed
     * on it; and, for a key drawn from the session's own copy ([requireLive]), confirmed live on it
     * ([liveThisEpoch]). Nothing is retried, held or persisted.
     */
    override fun kill(sessionId: String, expectedOrigin: String?, requireLive: Boolean) {
        if (sessionId.isEmpty()) return
        val sent = synchronized(lock) {
            val ws = socket
            val origin = socketOrigin
            if (ws == null || origin == null || !socketOpen || !handshakeDone || haltedLocked()) return@synchronized false
            if (expectedOrigin != origin) return@synchronized null
            if (requireLive && sessionId !in liveThisEpoch) return@synchronized null
            if (sessionsState.value.none { it.id == sessionId }) return@synchronized null
            ws.send(ClientMessage.Kill(sessionId).encode())
        }
        if (sent == false) emitError("The secure link is reconnecting. The session was not ended.")
    }

    // T5.1 sidebar sync (SidebarSync.kt): the exact frames, sent on the current handshaken socket.
    override fun discoverWorkspace(cwd: String, lastSeen: Map<String, Long>, watch: List<String>): Boolean =
        sendFrame(SidebarSync.discover(cwd, lastSeen, watch))

    override fun markSeen(historyId: String, seenAt: Long): Boolean = sendFrame(SidebarSync.markSeen(historyId, seenAt))

    override fun setSessionOrder(cwd: String, order: List<String>): Boolean =
        sendFrame(SidebarSync.setSessionOrder(cwd, order)).also { sent -> if (sent) sidebarSync.applyLocalOrder(cwd, order) }

    override fun requestServerSettings(): Boolean = sendFrame(ClientMessage.ServerSettingsRequest)

    override fun setPinnedWorkspaces(pinned: List<String>): Boolean = sendFrame(SidebarSync.setPinnedWorkspaces(pinned))

    override fun requestAdvancedSettings(): Boolean = sendFrame(ClientMessage.AdvancedSettingsRequest)

    // ta-dh1 r2: the choke point: a plain patch never carries a key that sets what the server runs.
    override fun setServerSettings(patch: JsonObject, origin: String): Boolean =
        patch.isNotEmpty() && !ServerSettingsPatch.touchesWhatRuns(patch) && sendFrameFor(origin, ClientMessage.SetServerSettings(patch))

    override fun setConfirmedEngineValue(write: ConfirmedEngineWrite, origin: String): Boolean =
        write.patch.isNotEmpty() && sendFrameFor(origin, ClientMessage.SetServerSettings(write.patch))

    override fun setAdvancedSettings(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean = sendFrameFor(origin, message)

    override fun detectEngines(origin: String): Boolean = sendFrameFor(origin, ClientMessage.DetectEngines)

    override val serverSettingsReplies: StateFlow<Long> = sidebarSync.serverSettingsReplies

    override val providerProfiles: StateFlow<ProvidersList?> = sidebarSync.providerProfiles

    override fun requestProviders(): Boolean {
        synchronized(lock) { providersWanted = true }
        return sendFrame(ClientMessage.ProvidersRequest)
    }

    // ta-q6p: the choke point for set-providers. The check reads the newest list under the same
    // lock the frames are folded under, so no broadcast can land between the check and the send.
    // r2: the list must have come on THIS socket (epoch), and no earlier write may still be
    // waiting for its broadcast (in flight, bounded).
    override fun setProviders(write: ProvidersWrite, origin: String): ProvidersRefusal? {
        val text = write.message.encode()
        synchronized(lock) {
            val newest = sidebarSync.providerProfiles.value
            ProvidersPatch.refusal(write, newest)?.let { return it }
            if (newest == null || newest.epoch != epoch) return ProvidersRefusal.Stale
            val ws = (if (socketOpen && handshakeDone && socketOrigin == origin) socket else null) ?: return ProvidersRefusal.NotConnected
            if (providersInFlight.overdue(newest)) ws.send(ClientMessage.ProvidersRequest.encode())
            providersInFlight.refusal(newest)?.let { return it }
            if (!ws.send(text)) return ProvidersRefusal.NotConnected
            providersInFlight.sent(write, newest)
            // r3: if no list containing it comes in time, ask for the list (the reply lifts the guard).
            scheduler.schedule(providersInFlight.timeoutMs) { askForOverdueProviders() }
            return null
        }
    }

    override fun providersWriteStatus(): ProvidersWriteStatus = synchronized(lock) { providersInFlight.status(sidebarSync.providerProfiles.value) }

    /** r3: the sent write is overdue: ask this socket for the registry; the reply lifts the in-flight guard. */
    private fun askForOverdueProviders() {
        synchronized(lock) {
            val ws = (if (socketOpen && handshakeDone) socket else null) ?: return
            if (providersInFlight.overdue(sidebarSync.providerProfiles.value)) ws.send(ClientMessage.ProvidersRequest.encode())
        }
    }

    // ta-q6p r2: Settings asked for the registry in this sign-in: every handshake asks again. Guarded by [lock].
    private var providersWanted = false

    // ta-q6p r2 (verifier F1): the sent write waiting for its broadcast (on the client's clock).
    private val providersInFlight = ProvidersInFlight(now = { clock() })

    // T5.3 search (SearchSync.kt).
    override fun search(cwd: String, query: String): Boolean = searchSync.search(cwd, query)

    override fun clearSearchResults() = searchSync.clearSearchResults()

    override fun runGlobalSearch(params: GlobalSearchParams): Boolean = searchSync.runGlobalSearch(params)

    override fun clearGlobalSearch() = searchSync.clearGlobalSearch()

    // T15.1 v131 Overview feed (OverviewSync.kt): the only two frames it ever sends.
    override fun subscribeOverview(subscription: com.tether.app.protocol.overview.OverviewSubscription): Boolean =
        synchronized(lock) { overviewSync.subscribe(subscription) { sendOverviewLocked(socket, it) } }

    override fun unsubscribeOverview() = synchronized(lock) { overviewSync.unsubscribe { sendOverviewLocked(socket, it) } }

    /**
     * An Overview frame, on [expected] only while it is the current socket, open and past the
     * handshake (never on an un-handshaken socket, never on one that replaced it). Caller holds [lock].
     */
    private fun sendOverviewLocked(expected: WebSocket?, message: ClientMessage): Boolean {
        if (expected == null || socket !== expected || !socketOpen || !handshakeDone) return false
        return expected.send(message.encode())
    }

    // ------------------------------------------------------------------
    // v109 node registry
    // ------------------------------------------------------------------

    override suspend fun addNode(origin: String, credential: NodeCredential, label: String?, baseUrl: String?): NodeRequestOutcome =
        when (val fields = NodeRegistryRules.nodeAdd(credential, label, baseUrl)) {
            is NodeAddFields.Refused -> refuseNodeRequest(fields.message, fields.emit)
            // The frame (and the credential in it) is built inside the send and
            // not referenced after it: nothing here outlives ws.send().
            is NodeAddFields.Ok -> nodeRequest(origin) { requestId ->
                ClientMessage.NodeAdd(credential.value, fields.label, fields.baseUrl, requestId)
            }
        }

    override suspend fun removeNode(origin: String, nodeId: String): NodeRequestOutcome {
        NodeRegistryRules.nodeIdProblem("node-remove", nodeId)?.let { return refuseNodeRequest(it, emit = true) }
        return nodeRequest(origin) { requestId -> ClientMessage.NodeRemove(nodeId, requestId) }
    }

    override suspend fun probeNode(origin: String, nodeId: String): NodeRequestOutcome {
        NodeRegistryRules.nodeIdProblem("node-probe", nodeId)?.let { return refuseNodeRequest(it, emit = true) }
        return nodeRequest(origin) { requestId -> ClientMessage.NodeProbe(nodeId, requestId) }
    }

    private fun refuseNodeRequest(message: String, emit: Boolean): NodeRequestOutcome {
        if (emit) emitError(message)
        return NodeRequestOutcome.Invalid(message)
    }

    /**
     * One node request: a fresh requestId, ONE send on the live socket, then the
     * first of (its `node-result` / a correlated `error`, the socket going away,
     * the timeout). Never queued, never retried (use-tether.ts sends once too).
     * The waiter is always removed, including when the caller is cancelled.
     */
    private suspend fun nodeRequest(origin: String, build: (requestId: String) -> ClientMessage): NodeRequestOutcome {
        val requestId = nodeRequestIds()
        val waiter = CompletableDeferred<NodeRequestOutcome>()
        val sent = transmitNodeRequest(origin, requestId, waiter, build)
        if (sent == null) {
            emitError(NodeRegistryRules.NOT_SENT_MESSAGE)
            return NodeRequestOutcome.NotSent
        }
        val timeout = scheduler.schedule(nodeRequestTimeoutMs) { waiter.complete(NodeRequestOutcome.TimedOut) }
        try {
            if (!sent) {
                // OkHttp refused it (the socket is closing): nothing went out, unless
                // the link-loss path already settled this waiter.
                if (waiter.complete(NodeRequestOutcome.NotSent)) emitError(NodeRegistryRules.NOT_SENT_MESSAGE)
            }
            // Resumed on Default, never inline on the thread that completes the
            // waiter (which may hold [lock]: detachSocketLocked).
            return withContext(Dispatchers.Default) { waiter.await() }
        } finally {
            timeout.cancel()
            synchronized(lock) { nodeRequests.remove(requestId) }
        }
    }

    /**
     * Register [waiter] and enqueue the frame on the socket that is live RIGHT
     * NOW, both under [lock], so a sign-in / drop cannot slip in between the
     * check and the send (the frame would go to the old socket and the request
     * be reported lost). OkHttp's send() only enqueues: it neither blocks nor
     * calls back (probeLink sends under the lock the same way). Null = no live
     * socket (nothing registered); false = OkHttp refused it. Not a suspend
     * function: the encoded frame (node-add's credential) dies with this call.
     * T10.3: only a socket opened for [origin] (the server the caller drew its
     * screen from) counts as live: after a switch, the frame goes nowhere.
     */
    private fun transmitNodeRequest(
        origin: String,
        requestId: String,
        waiter: CompletableDeferred<NodeRequestOutcome>,
        build: (requestId: String) -> ClientMessage,
    ): Boolean? {
        val text = build(requestId).encode()
        synchronized(lock) {
            val ws = (if (socketOpen && handshakeDone && socketOrigin == origin) socket else null) ?: return null
            nodeRequests[requestId] = waiter
            return ws.send(text)
        }
    }

    /** `node-result`: always the new [nodeResult] (as on the web); also ends its own request, if still waiting. */
    private fun onNodeResult(webSocket: WebSocket, message: ServerMessage.NodeResult) {
        val result = NodeActionResult(message.ok, message.nodeId, message.message, clock())
        if (!ifCurrent(webSocket) { nodeResultState.value = result }) return
        message.requestId?.let { completeNodeRequestOn(webSocket, it, NodeRequestOutcome.Answered(result)) }
    }

    /**
     * Test seam for race windows that timing alone cannot force: invoked at each
     * [RacePoint] (a frame admitted by the listener's socket check and about to
     * be handled; pending frames computed and about to be sent; a probe verdict
     * past its staleness check and about to act; a sign-in past its start()
     * and about to kick the connection). Null in production.
     */
    @Volatile
    internal var raceHook: ((RacePoint, Any?) -> Unit)? = null

    /** Test seam: the bound on a hydration read (production: [MIRROR_HYDRATE_TIMEOUT_MS]). */
    @Volatile
    internal var mirrorHydrateTimeoutMs: Long = MIRROR_HYDRATE_TIMEOUT_MS

    /** Test seam: how long a sign-in waits for a sign-out's store clear to land (ta-jt9 L-1). */
    @Volatile
    internal var signOutClearWaitMs: Long = LOGOUT_CALL_TIMEOUT_MS

    /** Test seam: the pause between start()'s settings reads (production: [SETTINGS_READ_RETRY_MS]). */
    @Volatile
    internal var settingsReadRetryMs: Long = SETTINGS_READ_RETRY_MS

    /** Test seam: the bound on a mirror bind (production: [MIRROR_BIND_TIMEOUT_MS]). */
    @Volatile
    internal var mirrorBindTimeoutMs: Long = MIRROR_BIND_TIMEOUT_MS

    // ta-jt9 L-A2: the boot purge's outcome (see [awaitBootPurge]); true = decided.
    private val bootPurge = CompletableDeferred<Boolean>()

    /** Test seam: the boot purge's outcome (true = decided, and wiped if it purged). */
    internal val bootPurgeOutcome: kotlinx.coroutines.Deferred<Boolean> get() = bootPurge

    init {
        // ta-jt9 L-A, whether or not anything calls start(). Declared after every property: the
        // coroutine may run on another thread before the constructor returns.
        if (mirrorForWipe == null) {
            bootPurge.complete(true)
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    purgeMirrorIfSignedOut()
                    bootPurge.complete(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Undecided: completed false below, so this process runs without a mirror.
                } finally {
                    bootPurge.complete(false)
                }
            }
        }
    }

    /** Test seam: node requests still waiting for an answer (must return to 0: nothing leaks). */
    internal fun pendingNodeRequestCount(): Int = synchronized(lock) { nodeRequests.size }

    /**
     * An unknown / already-settled requestId is ignored. T10.3 r2: only while [webSocket] is the
     * current socket (checked and removed under one lock), so a frame of a socket already let go
     * can never end a request of the one that replaced it.
     */
    private fun completeNodeRequestOn(webSocket: WebSocket, requestId: String, outcome: NodeRequestOutcome) {
        val waiter = synchronized(lock) { if (socket !== webSocket) null else nodeRequests.remove(requestId) } ?: return
        waiter.complete(outcome)
    }

    /** Test seam (T10.3 r2): each node request's id (production: a fresh random one). */
    @Volatile
    internal var nodeRequestIds: () -> String = { "node-" + UUID.randomUUID() }

    /**
     * Another server's registry and event log, or ones seen before a sign-out, must never show.
     * (The web reloads the page on every sign-in, which starts both over.)
     */
    private fun clearSignInViews() {
        // ta-t7l r2: the settings frames carry the server's password and proxy token in plaintext:
        // a sign-out (or a sign-in anew) drops them.
        sidebarSync.clearSettings()
        synchronized(lock) { providersWanted = false }
        nodesState.value = emptyList()
        nodeResultState.value = null
        serverProtocolVersionState.value = null
        eventLogState.update { EventLog(generation = it.generation + 1) }
        // T15.1: signed out (Lock) or signed in anew: no overview data or subscription survives it.
        synchronized(lock) { overviewSync.clear() }
    }

    /**
     * ta-t7l r2: the one way into [ConnectionState.AuthRequired]. The settings frames (the server's
     * password and proxy token in plaintext) are dropped first: a client that may not read this
     * server any more holds nothing of its settings.
     */
    private fun enterAuthRequired() {
        sidebarSync.clearSettings()
        connectionState.value = ConnectionState.AuthRequired
    }

    /**
     * Ordinary frames go out only on a LIVE connection: open and past `ready` +
     * `hello`. Encoded outside the lock; enqueued under it, on the socket that
     * is live at that moment (see transmitNodeRequest).
     */
    private fun sendFrame(message: ClientMessage): Boolean {
        val text = message.encode()
        synchronized(lock) {
            val ws = (if (socketOpen && handshakeDone) socket else null) ?: return false
            return ws.send(text)
        }
    }

    /**
     * ta-t7l: [sendFrame], but only on a live socket opened for [origin] (checked under the lock
     * the send takes): a settings write drawn from one server is dropped after a switch.
     */
    private fun sendFrameFor(origin: String, message: ClientMessage): Boolean {
        val text = message.encode()
        synchronized(lock) {
            val ws = (if (socketOpen && handshakeDone && socketOrigin == origin) socket else null) ?: return false
            return ws.send(text)
        }
    }

    /**
     * [sendFrame], but only on [expected]: a frame computed for one socket (a
     * pending record, the attach of its session) is dropped rather than handed
     * to a socket that replaced it, possibly one to another server.
     */
    private fun sendFrameOn(expected: WebSocket, message: ClientMessage): Boolean {
        val text = message.encode()
        synchronized(lock) {
            if (socket !== expected || !socketOpen || !handshakeDone) return false
            return expected.send(text)
        }
    }

    private fun emitError(message: String) {
        errorsFlow.tryEmit(message)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The URL alone shows the server is local and the OS would block it: don't touch the network. */
    private fun blockedBeforeConnect(base: HttpUrl): Boolean =
        localNetworkAccess.isRestricted() && LocalNetworkHosts.isLocalHost(base.host)

    /**
     * A connect attempt failed at the transport level. Was it the local-network
     * block? Covers names that resolve to a LAN address (split-horizon DNS), which
     * [blockedBeforeConnect] cannot see. Resolves only when access is restricted
     * and the failure is a transport one, so it adds nothing to the normal path.
     * Must run off the main thread (every caller is on Dispatchers.IO).
     */
    private fun blockedAfterFailure(base: HttpUrl, error: IOException): Boolean {
        if (!localNetworkAccess.isRestricted() || !LocalNetworkDenial.isTransportFailure(error)) return false
        val targetIsLocal = LocalNetworkHosts.isLocalHost(base.host) || try {
            httpClient.dns.lookup(base.host).any { LocalNetworkHosts.isLocalAddress(it.address) }
        } catch (_: IOException) {
            false
        }
        return LocalNetworkDenial.isBlockedByPermission(restricted = true, targetIsLocal = targetIsLocal, error = error)
    }

    private fun normalizeBaseUrl(raw: String): HttpUrl? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()
    }

    /**
     * Attach whatever credential is in force. This is the ONLY place the two auth
     * modes differ, so nothing downstream has to know which one is in use.
     *
     * ta-41x: a session cookie is an ambient credential, and the server accepts a write made with
     * one only from its own origin (tether#216). So a cookie always travels with the [server]'s
     * [consoleOrigin], the same Origin the `/ws` upgrade sends: every cookie request looks like
     * the console's own, and none relies on the server's allowance for a request with no Origin.
     * [server] is the base the request is addressed to (every caller resolves its URL on it). A
     * device token is not ambient (no page can attach it) and is sent as before.
     */
    private fun Request.Builder.authorize(credential: Credential?, server: HttpUrl): Request.Builder = when (credential) {
        // ta-96z: ONE Cookie header, under the name the server issued this cookie as, and never
        // both names: one credential, presented exactly as it was issued.
        is Credential.Cookie -> header("Cookie", "${credential.name}=${credential.value}").header("Origin", consoleOrigin(server))
        is Credential.DeviceToken -> header("Authorization", "Bearer ${credential.value}")
        null -> this
    }

    /** What /healthz tells an unauthenticated client: the native window + pairing capability. */
    private class Health(val protocolVersion: Int?, val nativeProtocolFloor: Int?, val pairing: Boolean) {
        /**
         * No protocolVersion at all = a probe this client cannot read; the WS
         * handshake (ready + hello) decides then. Otherwise a missing floor is a
         * pre-v129 server, too old for this app.
         */
        fun incompatibility(): Incompatibility? =
            if (protocolVersion == null) null else Compatibility.evaluate(protocolVersion, nativeProtocolFloor)
    }

    @Throws(IOException::class)
    private fun probeHealth(base: HttpUrl): Health {
        // /healthz is unauthenticated, but send the credential when one exists:
        // a deployment that puts the probe behind its own gate still answers.
        // ONLY to the server that credential belongs to: login()/pair() probe a
        // server the user just typed, which must never see another one's cookie
        // or device token.
        val credential = synchronized(lock) {
            credentialValue?.takeIf { baseUrlValue?.let { sameOrigin(it, base) } == true }
        }
        val request = Request.Builder()
            .url(base.resolve("/healthz")!!)
            .authorize(credential, base)
            .build()
        authHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("healthz returned HTTP ${response.code}")
            val obj = parseJsonObject(response)
            return Health(
                protocolVersion = obj?.get("protocolVersion")?.jsonPrimitive?.content?.toIntOrNull(),
                nativeProtocolFloor = obj?.get("nativeProtocolFloor")?.jsonPrimitive?.content?.toIntOrNull(),
                // Absent flag = an older server, which cannot pair.
                pairing = obj?.get("pairing")?.jsonPrimitive?.content == "true",
            )
        }
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun parseJsonObject(response: Response): JsonObject? = try {
        val text = response.body.string()
        // T6.2: a deeply nested body would overflow the parser's stack (ServerMessage.MAX_FRAME_DEPTH).
        if (ServerMessage.nestsDeeperThan(text, ServerMessage.MAX_FRAME_DEPTH)) null
        else com.tether.app.protocol.TetherJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** A string field of a JSON object; null when absent or not a string (never throws). */
    private fun JsonObject?.stringField(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * A login refusal that did not come from Tether, with what the screen may show to tell a
     * proxy from Tether in one try: the status, the auth scheme of any challenge (never the
     * realm) and the `Server` product, each only when it is a plain token. No body is kept.
     */
    private fun gatewayRefusal(response: Response): LoginResult.GatewayRefused = LoginResult.GatewayRefused(
        status = response.code,
        scheme = response.header("WWW-Authenticate")?.trim()?.substringBefore(' ')?.takeIf { AUTH_SCHEME.matches(it) },
        server = response.header("Server")?.trim()?.substringBefore(' ')?.takeIf { SERVER_PRODUCT.matches(it) },
    )

    private fun parseJsonField(response: Response, field: String): String? =
        parseJsonObject(response)?.get(field)?.jsonPrimitive?.content
}
