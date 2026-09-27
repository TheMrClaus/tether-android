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
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.reduce.reduce
import com.tether.app.protocol.str
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
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
import kotlinx.coroutines.withContext
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
 * close 4001 --> AuthRequired, credential cleared (terminal)
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
) : TetherClient {

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
    private var pendingLoaded = false
    private val reconciledSessions = HashSet<String>()

    // --- flows ---
    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val sessionsState = MutableStateFlow<List<AgentSession>>(emptyList())
    private val providersState = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val workspaceRootState = MutableStateFlow<String?>(null)
    private val projectionsState = MutableStateFlow<Map<String, SessionProjection>>(emptyMap())
    private val historiesState = MutableStateFlow<List<HistorySession>>(emptyList())
    private val directoriesState = MutableStateFlow<DirectoryListing?>(null)
    private val sessionControlsState = MutableStateFlow<Map<String, ServerMessage.SessionControls>>(emptyMap())
    private val trimmedBeforeState = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val errorsFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val configuredState = MutableStateFlow(false)

    override val connection: StateFlow<ConnectionState> = connectionState
    override val sessions: StateFlow<List<AgentSession>> = sessionsState
    override val providers: StateFlow<List<ProviderInfo>> = providersState
    override val workspaceRoot: StateFlow<String?> = workspaceRootState
    override val projections: StateFlow<Map<String, SessionProjection>> = projectionsState
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
    }

    // ------------------------------------------------------------------
    // Auth / lifecycle
    // ------------------------------------------------------------------

    override suspend fun login(baseUrl: String, password: String): LoginResult = withContext(Dispatchers.IO) {
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

        // 2. Password login (JSON form).
        val body = """{"password":${kotlinx.serialization.json.JsonPrimitive(password)}}"""
        val loginResponse = try {
            httpClient.newCall(
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
                429 -> return@withContext LoginResult.RateLimited(
                    parseJsonField(response, "error") ?: "Too many attempts. Try again in a few minutes.",
                )
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
            httpClient.newCall(
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
                else -> return@withContext PairResult.Unreachable("claim returned HTTP ${response.code}")
            }
        }
    }

    /** Persist a freshly-obtained credential and (re)start the connection loop. */
    private suspend fun adoptCredential(base: HttpUrl, credential: Credential) {
        settings.setServer(base.toString().trimEnd('/'), credential)
        synchronized(lock) {
            baseUrlValue = base
            credentialValue = credential
            stopped = false
            // A fresh login is a user action: it also clears a version halt.
            versionHalt = null
            backoff.reset()
        }
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
            val base = settings.baseUrl.first()
            // Whichever credential the install holds — a password cookie from a
            // pre-pairing version still resolves here, so upgrading never logs
            // an existing user out.
            val credential = settings.credential.first()
            val persisted = settings.readPendingInput()
            synchronized(lock) {
                if (base != null && baseUrlValue == null) baseUrlValue = base.toHttpUrlOrNull()
                if (credential != null && credentialValue == null) credentialValue = credential
                settingsLoaded = true
                if (!pendingLoaded) {
                    pendingLoaded = true
                    if (pendingStore.records.isEmpty()) pendingStore = PendingInput.fromPersisted(persisted)
                }
            }
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
            val authenticated = try {
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
            if (!authenticated) {
                synchronized(lock) { connecting = false }
                connectionState.value = ConnectionState.AuthRequired
                return@launch
            }
            openSocket(base, credential)
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

    private fun authProbe(base: HttpUrl, credential: Credential): Boolean {
        val request = Request.Builder()
            .url(base.resolve("/api/auth/session")!!)
            .authorize(credential)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("auth probe returned HTTP ${response.code}")
            return parseJsonField(response, "authenticated") == "true"
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
        val ws = httpClient.newWebSocket(request, listener)
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
            if (code == CLOSE_DEVICE_REVOKED) handleDeviceRevoked(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == CLOSE_DEVICE_REVOKED) {
                handleDeviceRevoked(webSocket)
                return
            }
            handleSocketGone(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleSocketGone(webSocket)
        }
    }

    /**
     * Close code 4001 = the owner revoked this device from a browser. Terminal,
     * NOT a transient drop: the stored token is dead, so reconnecting with it
     * would only spin. Drop the credential and fall back to the login/pairing
     * screen (the base URL survives — only the credential is gone).
     */
    private fun handleDeviceRevoked(webSocket: WebSocket?) {
        synchronized(lock) {
            // onClosing then onClosed both carry 4001; the first one through wins
            // and clears `socket`, so the second is a no-op.
            if (webSocket != null && socket !== webSocket) return
            stopped = true
            cancelTimersLocked()
            detachSocketLocked()
            connecting = false
            credentialValue = null
        }
        connectionState.value = ConnectionState.AuthRequired
        emitError("This device was unpaired from the server. Pair it again to reconnect.")
        scope.launch {
            try {
                settings.clearCredential()
            } catch (_: Exception) {
                // Worst case the dead token survives a restart; start() then lands
                // on AuthRequired at the first auth probe anyway.
            }
        }
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
                    synchronized(lock) {
                        pendingStore = PendingInput.forgetSession(pendingStore, message.session.id)
                    }
                    persistPending()
                }
                upsertSession(message.session)
            }
            is ServerMessage.Histories -> historiesState.value = message.sessions
            is ServerMessage.Directories -> directoriesState.value = message.listing
            is ServerMessage.Snapshot -> onSnapshot(message)
            is ServerMessage.Event -> onEvent(message)
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
            // Claimed for this epoch BEFORE Connected is published: an attach()
            // from a collector that reacts to Connected is then a no-op instead
            // of a second attach (T0.3 verify).
            attachedThisEpoch.addAll(ids)
            toAttach = ids.map { it to tracker.cursorFor(it) }
        }
        connectionState.value = ConnectionState.Connected
        for ((sessionId, afterSeq) in toAttach) {
            sendFrame(ClientMessage.Attach(sessionId, afterSeq))
        }
        message.workspaceRoot?.let {
            sendFrame(ClientMessage.Browse(it))
            sendFrame(ClientMessage.Discover(it))
        }
        // Fresh input filed while the socket was not yet live goes out now; an
        // already-transmitted record still waits for its session's snapshot.
        drainPending()
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
        // A state the legacy typed model cannot decode: the old projection is no
        // longer what the cursor describes, so drop it rather than fold live
        // events onto a diverged base (T2.1D swaps in the raw-state projection).
        val state = message.projection ?: run {
            projectionsState.value = projectionsState.value - message.sessionId
            return
        }
        val cleared: List<String>
        synchronized(lock) {
            val result = PendingInput.reconcileWithSnapshot(pendingStore, message.sessionId, state)
            pendingStore = result.store
            cleared = result.cleared
            // Only now is redelivery for this session safe on this connection.
            reconciledSessions.add(message.sessionId)
        }
        projectionsState.value = projectionsState.value + (message.sessionId to state)
        if (cleared.isNotEmpty()) persistPending()
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
            val removed = synchronized(lock) {
                val result = PendingInput.ackKey(pendingStore, ackedKey)
                pendingStore = result.store
                result.removed
            }
            if (removed) persistPending()
        }
        val current = projectionsState.value
        val projection = current[message.sessionId] ?: return
        projectionsState.value = current + (message.sessionId to reduce(projection, event))
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

    private fun recordAndDrain(kind: String, sessionId: String, text: String, attachments: List<Attachment>? = null) {
        val key = UUID.randomUUID().toString()
        val evicted = synchronized(lock) {
            val result = PendingInput.addRecord(pendingStore, key, kind, sessionId, text, clock(), attachments)
            pendingStore = result.store
            result.evicted
        }
        for (record in evicted) {
            emitError(undeliveredMessage(listOf(record)))
        }
        persistPending()
        drainPending()
    }

    override fun queueEdit(sessionId: String, queueId: String, text: String) {
        synchronized(lock) { pendingStore = PendingInput.editText(pendingStore, queueId, text) }
        persistPending()
        sendFrame(ClientMessage.QueueEdit(sessionId, queueId, text))
    }

    override fun queueRemove(sessionId: String, queueId: String) {
        synchronized(lock) { pendingStore = PendingInput.discardKey(pendingStore, queueId).store }
        persistPending()
        sendFrame(ClientMessage.QueueRemove(sessionId, queueId))
    }

    private fun drainPending() {
        val frames: List<ClientMessage>
        synchronized(lock) {
            if (!socketOpen || !handshakeDone) return
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

    private fun persistPending() {
        val snapshot = synchronized(lock) { pendingStore }
        scope.launch {
            try {
                settings.writePendingInput(PendingInput.toPersisted(snapshot))
            } catch (_: Exception) {
                // No persistence just means nothing to redeliver after a restart.
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
        val credential = synchronized(lock) { credentialValue }
        val request = Request.Builder()
            .url(base.resolve("/healthz")!!)
            .authorize(credential)
            .build()
        httpClient.newCall(request).execute().use { response ->
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

    private fun parseJsonObject(response: Response): JsonObject? = try {
        com.tether.app.protocol.TetherJson.parseToJsonElement(response.body.string()) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun parseJsonField(response: Response, field: String): String? =
        parseJsonObject(response)?.get(field)?.jsonPrimitive?.content
}
