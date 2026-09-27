package com.tether.app.client

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.HELLO_CLIENT_ANDROID
import com.tether.app.protocol.PROTOCOL_VERSION
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.str
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

/** Application close code: the server revoked this device (see server.mjs §disconnectDeviceSockets). */
private const val CLOSE_DEVICE_REVOKED = 4001

/** Application close code: the cookie session was revoked (server.mjs §disconnectRevokedSessionSockets). */
private const val CLOSE_SESSION_REVOKED = 4002

/** Upper bound on the best-effort server calls made while signing out. */
private const val LOGOUT_CALL_TIMEOUT_MS = 5_000L

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
     * Bounded by [LOGOUT_CALL_TIMEOUT_MS]; failures are ignored.
     */
    private val onLogout: suspend (baseUrl: String, credential: Credential) -> Unit = { _, _ -> },
) : TetherClient {

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
    // The persisted store was read and merged in (start()); nothing drains or is
    // written before that, so a cold start never overwrites what the last process left.
    private var pendingLoaded = false
    private val reconciledSessions = HashSet<String>()
    // use-tether.ts clearedRef: keys removed for any reason (acked, withdrawn,
    // expired, evicted), persisted with the records; bounded by MAX_TOMBSTONES.
    private var clearedKeys = LinkedHashSet<String>()
    // Every store change bumps this; the writer only ever moves the file forward.
    private var pendingVersion = 0L

    // --- flows ---
    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val sessionsState = MutableStateFlow<List<AgentSession>>(emptyList())
    private val providersState = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val workspaceRootState = MutableStateFlow<String?>(null)
    private val projectionsState = MutableStateFlow<Map<String, SessionProjection>>(emptyMap())

    // T2.1D: the v128 trees are the source of truth; the typed projections are adapted from
    // them by one memoized adapter per session (frame thread only).
    private val projectionTreesState = MutableStateFlow<Map<String, JsObj>>(emptyMap())
    private val adapters = HashMap<String, LegacyProjectionAdapter>()
    private val historiesState = MutableStateFlow<List<HistorySession>>(emptyList())
    private val directoriesState = MutableStateFlow<DirectoryListing?>(null)
    private val sessionControlsState = MutableStateFlow<Map<String, ServerMessage.SessionControls>>(emptyMap())
    private val trimmedBeforeState = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val errorsFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val configuredState = MutableStateFlow(false)
    private val signedOutReasonState = MutableStateFlow<SignedOutReason?>(null)
    private val serverUrlState = MutableStateFlow<String?>(null)

    override val signedOutReason: StateFlow<SignedOutReason?> = signedOutReasonState
    override val serverUrl: StateFlow<String?> = serverUrlState
    override val connection: StateFlow<ConnectionState> = connectionState
    override val sessions: StateFlow<List<AgentSession>> = sessionsState
    override val providers: StateFlow<List<ProviderInfo>> = providersState
    override val workspaceRoot: StateFlow<String?> = workspaceRootState
    override val projections: StateFlow<Map<String, SessionProjection>> = projectionsState
    override val projectionTrees: StateFlow<Map<String, JsObj>> = projectionTreesState
    override val histories: StateFlow<List<HistorySession>> = historiesState
    override val directories: StateFlow<DirectoryListing?> = directoriesState
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = sessionControlsState
    override val errors: SharedFlow<String> = errorsFlow
    override val configured: StateFlow<Boolean> = configuredState
    override val trimmedBefore: StateFlow<Map<String, Int>> = trimmedBeforeState

    init {
        scope.launch {
            combine(settings.baseUrl, settings.credential) { base, credential ->
                !base.isNullOrEmpty() && credential != null
            }.collect { configuredState.value = it }
        }
        scope.launch { settings.baseUrl.collect { serverUrlState.value = it } }
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
                    val cookie = response.headers("set-cookie")
                        .firstOrNull { it.startsWith("tether_session=") }
                        ?.substringAfter("tether_session=")
                        ?.substringBefore(';')
                    if (cookie.isNullOrEmpty()) {
                        return@withContext LoginResult.Unreachable("The server did not return a session cookie.")
                    }
                    adoptCredential(normalized, Credential.Cookie(cookie))
                    return@withContext LoginResult.Success
                }
                401 -> return@withContext LoginResult.BadPassword(
                    parseJsonField(response, "error") ?: "That password is not correct.",
                )
                // lib/login-guard.mjs: failed attempts only, so a correct password
                // is never throttled.
                429 -> return@withContext LoginResult.RateLimited(
                    parseJsonField(response, "error") ?: "Too many attempts. Try again in a few minutes.",
                )
                403 -> {
                    val obj = parseJsonObject(response)
                    val error = obj?.get("error")?.jsonPrimitive?.content
                    return@withContext if (obj?.get("code")?.jsonPrimitive?.content == "password_login_disabled") {
                        LoginResult.PasswordDisabled(error ?: "Password sign-in is turned off for this console.")
                    } else {
                        LoginResult.Unreachable(error ?: "login returned HTTP 403")
                    }
                }
                in 300..399 -> return@withContext LoginResult.Unreachable(REDIRECT_MESSAGE)
                else -> return@withContext LoginResult.Unreachable("login returned HTTP ${response.code}")
            }
        }
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
                    adoptCredential(normalized, Credential.DeviceToken(token))
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
        try {
            settings.setServer(base.toString().trimEnd('/'), credential)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The store could not be written: this sign-in lives in memory for
            // this process only. The store deletes the old credential before it
            // moves the URL, so disk never pairs the new URL with the old one.
        }
        synchronized(lock) {
            baseUrlValue = base
            credentialValue = credential
            stopped = false
            // A fresh login is a user action: it also clears a version halt.
            versionHalt = null
            backoff.reset()
        }
        signedOutReasonState.value = null
        start()
        reconnectIfIdle()
    }

    override fun start() {
        synchronized(lock) {
            // Deliberately NOT clearing versionHalt: start() re-runs on every
            // activity (re)creation, which is not a decision to retry.
            stopped = false
            if (sweeperJob?.isActive != true) {
                sweeperJob = scope.launch { sweeperLoop() }
            }
        }
        scope.launch {
            // ONE snapshot of (URL, credential): two separate reads could straddle
            // a server switch and pair URL A with credential B. Whichever
            // credential the install holds — a password cookie from a pre-pairing
            // version still resolves here, so upgrading never logs anyone out.
            val session = settings.session()
            // An unreadable store is "nothing to redeliver", never a failed start.
            val persisted = try {
                settings.readPendingInput()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            var restored = false
            synchronized(lock) {
                val base = session.baseUrl?.toHttpUrlOrNull()
                if (credentialValue == null && session.credential != null && base != null) {
                    // Adopted as a pair, never the credential under another URL.
                    baseUrlValue = base
                    credentialValue = session.credential
                } else if (baseUrlValue == null && base != null) {
                    baseUrlValue = base
                }
                settingsLoaded = true
                if (!pendingLoaded) {
                    restorePendingLocked(persisted)
                    restored = true
                }
            }
            if (restored) persistPending()
            if (baseUrlValue == null || credentialValue == null) {
                connectionState.value = ConnectionState.AuthRequired
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
            credentialValue = null
            detachSocketLocked()
        }
        ws?.cancel()
        connectionState.value = ConnectionState.Disconnected
        // stop() is logout: drop the persisted base URL + credential so the UI's
        // `configured` flow flips false and the setup screen returns. A later
        // successful login()/pair() resets `stopped` and restarts the loop.
        scope.launch { settings.clear() }
    }

    override suspend fun logout(): LogoutResult {
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
            ws = detachSocketLocked()
            connecting = false
        }
        ws?.close(1000, "logout")
        // A user logout is not a server verdict: no "session expired" copy.
        signedOutReasonState.value = null
        connectionState.value = ConnectionState.AuthRequired

        // 1. Forget locally FIRST: whatever happens next, this phone is signed out.
        //    The server URL stays (login-screen prefill).
        // The store retries its own delete and falls back to a tombstone; one
        // more attempt here covers a store that threw before getting that far.
        if (runCatching { settings.clearCredential() }.isFailure) {
            runCatching { settings.clearCredential() }
        }
        if (base == null || credential == null) return LogoutResult.LocalOnly

        // 2. Integrator hook (push unregister for a device token), bounded.
        try {
            withTimeoutOrNull(LOGOUT_CALL_TIMEOUT_MS) { onLogout(base.toString().trimEnd('/'), credential) }
        } catch (e: CancellationException) {
            throw e
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
                            .authorize(credential)
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
                    method = if (method == "passkey" || method == "service") method else "password",
                    createdAt = num("createdAt"),
                    lastSeenAt = num("lastSeenAt"),
                    expiresAt = num("expiresAt"),
                    userAgent = (record["userAgent"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    current = (record["current"] as? JsonPrimitive)?.content == "true",
                )
            }
            SignInSessionsResult.Sessions(list)
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
        val request = Request.Builder().url(url).authorize(credential)
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

    override fun reconnectIfIdle() {
        val probe = synchronized(lock) {
            if (haltedLocked()) return
            when {
                // An OPEN socket cannot be trusted after a wake or a network
                // change (the half-open case): probe it instead (web #135).
                socketOpen -> true
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
        if (ws != null && !socketOpen) connecting = false
        socketListener?.retired = true
        socketListener = null
        socket = null
        socketOpen = false
        handshakeDone = false
        pingTask?.cancel()
        pingTask = null
        return ws
    }

    private fun connectNow() {
        val base: HttpUrl
        val credential: Credential
        synchronized(lock) {
            if (haltedLocked() || connecting || socket != null) return
            val b = baseUrlValue
            val c = credentialValue
            if (b == null || c == null) {
                // Before start() read the settings (e.g. an early lifecycle
                // signal) "no credential" is not known yet: stay quiet.
                if (settingsLoaded) connectionState.value = ConnectionState.AuthRequired
                return
            }
            connecting = true
            base = b
            credential = c
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
            synchronized(lock) { consecutiveTimeouts = 0 }
            when (verdict) {
                ProbeVerdict.Authenticated -> openSocket(base, credential)
                ProbeVerdict.Rejected -> handleCredentialRejected(
                    credential,
                    if (credential is Credential.Cookie) SignedOutReason.SessionExpired else SignedOutReason.DeviceUnpaired,
                )
                ProbeVerdict.Refused -> {
                    // A gateway, not Tether, said no: keep the credential (it may be
                    // fine once the probe gets through) and wait for a user action,
                    // a network change or the next foreground — no timer loop.
                    val current = synchronized(lock) {
                        connecting = false
                        credentialValue === credential && !haltedLocked()
                    }
                    if (current) {
                        // State first: an observer that sees the reason must see the settled state.
                        connectionState.value = ConnectionState.AuthRequired
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
     * different credential has been adopted since (a login racing the probe).
     */
    private fun handleCredentialRejected(credential: Credential, reason: SignedOutReason) {
        val ws = synchronized(lock) {
            connecting = false
            if (credentialValue !== credential) return
            stopped = true
            cancelTimersLocked()
            credentialValue = null
            detachSocketLocked()
        }
        ws?.cancel()
        // State first: an observer that sees the reason must see the settled state.
        connectionState.value = ConnectionState.AuthRequired
        signedOutReasonState.value = reason
        scope.launch {
            try {
                // Only if the store still holds THIS credential: never wipe a
                // newer login that landed while this verdict was in flight.
                if (settings.credential.first() == credential) settings.clearCredential()
            } catch (_: Exception) {
                // Worst case the dead credential survives a restart; the next
                // probe rejects it again.
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
            .authorize(credential)
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

    private fun openSocket(base: HttpUrl, credential: Credential) {
        // Origin's host(+port) MUST equal the Host header or the server
        // destroys the upgrade with a raw 401. OkHttp never sets it itself.
        val origin = buildString {
            append(base.scheme).append("://").append(base.host)
            if (base.port != HttpUrl.defaultPort(base.scheme)) append(':').append(base.port)
        }
        val request = Request.Builder()
            .url(base.resolve("/ws")!!)
            .authorize(credential)
            .header("Origin", origin)
            .build()
        val listener = SocketListener()
        // No redirects on the credential-bearing upgrade either (see authHttp).
        val ws = authHttp.newWebSocket(request, listener)
        listener.expected = ws
        val cancel = synchronized(lock) {
            if (haltedLocked() || socket != null) {
                connecting = false
                true
            } else {
                socket = ws
                socketListener = listener
                false
            }
        }
        if (cancel) ws.cancel()
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
        @Volatile
        var expected: WebSocket? = null

        /** Set (under lock) once the client let go of this socket. */
        var retired = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val reject = synchronized(lock) {
                if (webSocket !== socket && webSocket !== expected) return
                if (retired || haltedLocked() || (socket != null && socket !== webSocket)) {
                    if (!retired && socket == null) connecting = false
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
                attachedThisEpoch.clear()
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
            if (webSocket !== socket) return
            // Stamped before parsing: even an undecodable frame proves traffic.
            lastInboundAt = clock()
            handleFrame(webSocket, ServerMessage.parse(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (code == CLOSE_DEVICE_REVOKED || code == CLOSE_SESSION_REVOKED) handleRevokedClose(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == CLOSE_DEVICE_REVOKED || code == CLOSE_SESSION_REVOKED) {
                handleRevokedClose(webSocket, code)
                return
            }
            handleSocketGone(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
            is ServerMessage.Created -> upsertSession(message.session)
            is ServerMessage.SessionUpdate -> {
                if (message.session.runtimeArchived) {
                    // use-tether.ts:827 — an archived session refuses every send: drop its records.
                    val changed = synchronized(lock) {
                        val next = PendingInput.forgetSession(pendingStore, message.session.id)
                        if (next === pendingStore) return@synchronized false
                        forgetLocked(pendingStore.records.filter { it.sessionId == message.session.id }.map { it.key })
                        pendingStore = next
                        true
                    }
                    if (changed) persistPending()
                }
                upsertSession(message.session)
            }
            is ServerMessage.Histories -> historiesState.value = message.sessions
            is ServerMessage.Directories -> directoriesState.value = message.listing
            is ServerMessage.Snapshot -> onSnapshot(message)
            is ServerMessage.Event -> onEvent(message)
            is ServerMessage.TurnsDetail -> onTurnsDetail(message)
            is ServerMessage.InterruptResult ->
                if (message.status == "failed") {
                    emitError(message.error ?: "The interrupt request could not be delivered.")
                }
            is ServerMessage.ErrorFrame -> emitError(message.message)
            is ServerMessage.SessionControls ->
                sessionControlsState.value = sessionControlsState.value + (message.sessionId to message)
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
        sessionsState.value = message.sessions.sortedByDescending { it.updatedAt }
        providersState.value = message.providers
        workspaceRootState.value = message.workspaceRoot
        // §5.5: re-attach every subscribed session and every session that has
        // pending outbound input, from its last good cursor.
        val toAttach: List<Pair<String, Long?>>
        synchronized(lock) {
            if (socket !== webSocket) return
            handshakeDone = true
            // A handshake the server accepted is the success that resets backoff.
            backoff.reset()
            val ids = LinkedHashSet<String>()
            ids.addAll(subscribed)
            ids.addAll(tracker.attachedSessions())
            pendingStore.records.mapTo(ids) { it.sessionId }
            // Claimed for this epoch under the lock: an attach() racing this
            // handler (from any thread) is then a no-op instead of a second
            // attach (T0.3 verify).
            attachedThisEpoch.addAll(ids)
            toAttach = ids.map { it to tracker.cursorFor(it) }
        }
        for ((sessionId, afterSeq) in toAttach) {
            sendFrame(ClientMessage.Attach(sessionId, afterSeq))
        }
        message.workspaceRoot?.let {
            sendFrame(ClientMessage.Browse(it))
            sendFrame(ClientMessage.Discover(it))
        }
        // Fresh input filed while the socket was not yet live goes out now, right
        // after the re-attach; an already-transmitted record still waits for its
        // session's snapshot.
        drainPending()
        // Published only once the handshake frames are on the wire: whatever a
        // caller sends after observing Connected is ordered after the re-attach.
        connectionState.value = ConnectionState.Connected
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
    private fun onSnapshot(message: ServerMessage.Snapshot) {
        synchronized(lock) { tracker.onSnapshot(message.sessionId, message.throughSeq) }
        if (!message.hasState) return
        trimmedBeforeState.value = message.trimmedBefore
            ?.let { trimmedBeforeState.value + (message.sessionId to it) }
            ?: (trimmedBeforeState.value - message.sessionId)
        // The tree is the source of truth and is always kept: live events fold onto it.
        // A state whose required session fields the legacy typed model cannot read
        // leaves no typed projection (the screens show nothing rather than a diverged
        // base); pending input still reconciles against the tree, as on the web.
        val tree = message.state ?: return
        publish(message.sessionId, tree, adapt(message.sessionId, tree))
        // use-tether.ts:953-984 — the DURABLE acknowledgement, read off the raw
        // journal-folded state exactly as the web does (not the typed view): a key it
        // contains was accepted, whatever it lacks was not and is redelivered below.
        val changed = synchronized(lock) {
            val result = PendingInput.reconcileWithSnapshot(pendingStore, message.sessionId, tree)
            pendingStore = result.store
            forgetLocked(result.cleared)
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

    private fun onEvent(message: ServerMessage.Event) {
        val event = message.event
        val decision = synchronized(lock) {
            tracker.onEvent(message.sessionId, event.seq, canSend = socketOpen)
        }
        when (decision) {
            CursorTracker.Decision.Ignore,
            CursorTracker.Decision.Drop,
            CursorTracker.Decision.AwaitSnapshot,
            -> return
            is CursorTracker.Decision.Resync -> {
                sendFrame(ClientMessage.Attach(message.sessionId, decision.afterSeq))
                return
            }
            CursorTracker.Decision.Fold -> Unit
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
                val result = PendingInput.ackKey(pendingStore, ackedKey)
                pendingStore = result.store
                if (result.removed) forgetLocked(listOf(ackedKey))
                result.removed
            }
            if (removed) persistPending()
        }
        val tree = projectionTreesState.value[message.sessionId] ?: return
        val next = try {
            reduce(tree, JsCodec.fromJson(event.raw) as JsObj)
        } catch (e: RuntimeException) {
            // The fold is a line port of events.mjs and, like it, assumes the server's full
            // projection shape (a JS reduce throws on the same malformed base). Never let that
            // take down the socket thread: drop the diverged base and ask for a FULL snapshot
            // (no afterSeq — a cursor-at-head attach would come back stateless).
            projectionTreesState.value = projectionTreesState.value - message.sessionId
            projectionsState.value = projectionsState.value - message.sessionId
            sendFrame(ClientMessage.Attach(message.sessionId, null))
            return
        }
        // An unchanged projection is the same object (T2.1 Revision 6): nothing to publish.
        if (next !== tree) publish(message.sessionId, next, adapt(message.sessionId, next))
    }

    /**
     * v115 lazy-loaded turns (use-tether.ts `turns-detail`): the full turn projections
     * replace the trimmed stubs in `turnsById`; nothing else changes. Ignored for a
     * session with no projection yet.
     */
    private fun onTurnsDetail(message: ServerMessage.TurnsDetail) {
        val tree = projectionTreesState.value[message.sessionId] ?: return
        val turnsById = tree["turnsById"] as? JsObj ?: JsObj.EMPTY
        val next = tree.put("turnsById", turnsById.spread(message.turns))
        publish(message.sessionId, next, adapt(message.sessionId, next))
    }

    /**
     * [tree]'s typed view through [sessionId]'s memoized adapter: null when the tree's
     * required session fields do not fit the typed model.
     */
    private fun adapt(sessionId: String, tree: JsObj): SessionProjection? =
        adapters.getOrPut(sessionId) { LegacyProjectionAdapter() }.adapt(tree)

    /** Publish [sessionId]'s projection: the tree, and its typed view (removed when null). */
    private fun publish(sessionId: String, tree: JsObj, typed: SessionProjection?) {
        projectionTreesState.value = projectionTreesState.value + (sessionId to tree)
        projectionsState.value = if (typed != null) {
            projectionsState.value + (sessionId to typed)
        } else {
            projectionsState.value - sessionId
        }
    }

    private fun upsertSession(session: AgentSession) {
        sessionsState.value = (listOf(session) + sessionsState.value.filter { it.id != session.id })
            .sortedByDescending { it.updatedAt }
    }

    // ------------------------------------------------------------------
    // Durable send (§5.6)
    // ------------------------------------------------------------------

    override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
        recordAndDrain(PendingInput.KIND_SEND, sessionId, text, attachments.ifEmpty { null })
        // Web #135: an attachment frame is large and never persisted, so a
        // half-open socket swallowing it is the worst case — probe right away.
        if (attachments.isNotEmpty()) probeLink()
    }

    override fun queueAdd(sessionId: String, text: String) {
        recordAndDrain(PendingInput.KIND_QUEUE, sessionId, text)
    }

    /** use-tether.ts:630 filePending: mint, record, persist, then one drain puts it on the wire. */
    private fun recordAndDrain(kind: String, sessionId: String, text: String, attachments: List<Attachment>? = null) {
        val key = PendingInput.newKey()
        val evicted = synchronized(lock) {
            val result = PendingInput.addRecord(pendingStore, key, kind, sessionId, text, clock(), attachments)
            pendingStore = result.store
            forgetLocked(result.evicted.map { it.key })
            result.evicted
        }
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
     * handshake and for the persisted store to be restored.
     */
    private fun drainPending() {
        val frames: List<ClientMessage>
        synchronized(lock) {
            if (!pendingLoaded || !socketOpen || !handshakeDone) return
            val sendable = PendingInput.sendableRecords(pendingStore, reconciledSessions)
            if (sendable.isEmpty()) return
            pendingStore = PendingInput.markSent(pendingStore, sendable.map { it.key }, clock())
            frames = sendable.map { record ->
                if (record.kind == PendingInput.KIND_SEND) {
                    ClientMessage.Send(record.sessionId, record.text, record.key, record.attachments)
                } else {
                    ClientMessage.QueueAdd(record.sessionId, record.key, record.text)
                }
            }
        }
        for (frame in frames) sendFrame(frame)
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
     * ([PendingInput.restoredFromPreviousProcess]). Caller holds [lock].
     */
    private fun restorePendingLocked(raw: String?) {
        val persistedCleared = PendingInput.clearedFromPersisted(raw)
        val cleared = LinkedHashSet(persistedCleared).apply { addAll(clearedKeys) }
        clearedKeys = LinkedHashSet(cleared.toList().takeLast(PendingInput.MAX_TOMBSTONES))
        val restored = PendingInput.restoredFromPreviousProcess(PendingInput.fromPersisted(raw), clock())
        pendingStore = PendingInput.mergeStores(pendingStore, restored, clearedKeys)
        pendingLoaded = true
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

    // Guarded by persistMutex: the version the file holds.
    private var persistedVersion = 0L

    /**
     * Write the store after EVERY change. One DataStore edit replaces the whole
     * payload atomically (temp file + rename), so a crash mid-write leaves the
     * previous complete store, never a torn one. Writes are serialized and
     * conflated: each writes the newest version, and an older snapshot can never
     * land after a newer one (which could resurrect an acked record or drop a new one).
     */
    private fun persistPending() {
        synchronized(lock) {
            if (!pendingLoaded) return
            pendingVersion++
        }
        scope.launch {
            persistMutex.withLock {
                val (version, store, cleared) = synchronized(lock) {
                    Triple(pendingVersion, pendingStore, clearedKeys.toList())
                }
                if (version <= persistedVersion) return@withLock
                try {
                    settings.writePendingInput(PendingInput.toPersistable(store, cleared))
                    persistedVersion = version
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed write must never break sending; the in-memory store
                    // still redelivers for the life of the process.
                }
            }
        }
    }

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
        val afterSeq = synchronized(lock) {
            subscribed.add(sessionId)
            if (!socketOpen || !handshakeDone || !attachedThisEpoch.add(sessionId)) return
            tracker.cursorFor(sessionId)
        }
        sendFrame(ClientMessage.Attach(sessionId, afterSeq))
    }

    override fun interrupt(sessionId: String) {
        sendFrame(ClientMessage.Interrupt(sessionId))
    }

    override fun approval(sessionId: String, requestId: String, choiceId: String?, decision: String?) {
        sendFrame(ClientMessage.Approval(sessionId, requestId, choiceId, decision))
    }

    override fun answerQuestion(
        sessionId: String,
        requestId: String,
        answers: Map<String, String>,
        response: String?,
    ) {
        sendFrame(ClientMessage.Question(sessionId, requestId, answers, response))
    }

    override fun createSession(provider: String, cwd: String?, name: String?) {
        sendFrame(ClientMessage.Create(provider = provider, cwd = cwd, name = name))
    }

    override fun resumeHistory(historyId: String, cwd: String) {
        sendFrame(ClientMessage.Resume(historyId, cwd))
    }

    override fun discover(cwd: String) {
        sendFrame(ClientMessage.Discover(cwd))
    }

    override fun browse(cwd: String?) {
        sendFrame(ClientMessage.Browse(cwd))
    }

    override fun setMode(sessionId: String, permissionMode: String) {
        sendFrame(ClientMessage.SetMode(sessionId, permissionMode))
    }

    override fun setModel(sessionId: String, model: String): Boolean =
        sendFrame(ClientMessage.SetModel(sessionId, model))

    override fun requestSessionControls(sessionId: String) {
        sendFrame(ClientMessage.SessionControlsRequest(sessionId))
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

    override fun kill(sessionId: String) {
        sendFrame(ClientMessage.Kill(sessionId))
    }

    /** Ordinary frames go out only on a LIVE connection: open and past `ready` + `hello`. */
    private fun sendFrame(message: ClientMessage): Boolean {
        val ws = synchronized(lock) { if (socketOpen && handshakeDone) socket else null } ?: return false
        return ws.send(message.encode())
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
     */
    private fun Request.Builder.authorize(credential: Credential?): Request.Builder = when (credential) {
        is Credential.Cookie -> header("Cookie", "tether_session=${credential.value}")
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
            .authorize(credential)
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
        com.tether.app.protocol.TetherJson.parseToJsonElement(response.body.string()) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun parseJsonField(response: Response, field: String): String? =
        parseJsonObject(response)?.get(field)?.jsonPrimitive?.content
}
