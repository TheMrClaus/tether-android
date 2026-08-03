package com.tether.app.client

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
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
 */
class RealTetherClient(
    private val settings: SettingsStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconnectDelayMs: Long = 1_800,
    private val sweepIntervalMs: Long = 2_000,
) : TetherClient {

    private val lock = Any()

    // --- connection state (guarded by lock) ---
    private var stopped = false
    private var connecting = false
    private var socket: WebSocket? = null
    private var socketOpen = false
    private var reconnectJob: Job? = null
    private var sweeperJob: Job? = null
    private var baseUrlValue: HttpUrl? = null

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

        // 1. Cheapest pre-flight: /healthz carries protocolVersion unauthenticated.
        val health = try {
            probeHealth(normalized)
        } catch (e: IOException) {
            return@withContext LoginResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        if (health.protocolVersion != null && health.protocolVersion != PROTOCOL_VERSION) {
            return@withContext LoginResult.VersionMismatch(health.protocolVersion)
        }

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
            return@withContext PairResult.Unreachable(e.message ?: "The server could not be reached.")
        }
        if (health.protocolVersion != null && health.protocolVersion != PROTOCOL_VERSION) {
            return@withContext PairResult.VersionMismatch(health.protocolVersion)
        }
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
        }
        start()
        reconnectIfIdle()
    }

    override fun start() {
        synchronized(lock) {
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
        synchronized(lock) {
            stopped = true
            reconnectJob?.cancel()
            reconnectJob = null
            sweeperJob?.cancel()
            sweeperJob = null
            socket?.cancel()
            socket = null
            socketOpen = false
            connecting = false
            baseUrlValue = null
            credentialValue = null
        }
        connectionState.value = ConnectionState.Disconnected
        // stop() is logout: drop the persisted base URL + credential so the UI's
        // `configured` flow flips false and the setup screen returns. A later
        // successful login()/pair() resets `stopped` and restarts the loop.
        scope.launch { settings.clear() }
    }

    override fun reconnectIfIdle() {
        synchronized(lock) {
            if (stopped || connecting || socket != null) return
            reconnectJob?.cancel()
            reconnectJob = null
        }
        connectNow()
    }

    // ------------------------------------------------------------------
    // Connection loop
    // ------------------------------------------------------------------

    private fun connectNow() {
        val base: HttpUrl
        val credential: Credential
        synchronized(lock) {
            if (stopped || connecting || socket != null) return
            val b = baseUrlValue
            val c = credentialValue
            if (b == null || c == null) {
                connectionState.value = ConnectionState.AuthRequired
                return
            }
            connecting = true
            base = b
            credential = c
        }
        connectionState.value = ConnectionState.Connecting
        scope.launch(Dispatchers.IO) {
            // §5.3: check auth before each connect.
            val authenticated = try {
                authProbe(base, credential)
            } catch (_: IOException) {
                synchronized(lock) { connecting = false }
                connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
                return@launch
            }
            if (!authenticated) {
                synchronized(lock) { connecting = false }
                connectionState.value = ConnectionState.AuthRequired
                return@launch
            }
            openSocket(base, credential)
        }
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
            if (stopped) {
                true
            } else {
                socket = ws
                false
            }
        }
        if (cancel) ws.cancel()
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (stopped) return
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(reconnectDelayMs)
                connectNow()
            }
        }
    }

    private inner class SocketListener : WebSocketListener() {
        @Volatile
        var expected: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (webSocket !== socket && webSocket !== expected) return
                socket = webSocket
                socketOpen = true
                connecting = false
                // §5.4: do NOT drain pending sends; reset in-flight and wait for ready.
                pendingStore = PendingInput.resetInFlight(pendingStore)
                reconciledSessions.clear()
                tracker.clearResyncFlags()
            }
            lastInboundAt = clock()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            lastInboundAt = clock()
            handleFrame(ServerMessage.parse(text))
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
            reconnectJob?.cancel()
            reconnectJob = null
            socket = null
            socketOpen = false
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
            socket = null
            socketOpen = false
            connecting = false
            if (stopped) return
        }
        connectionState.value = ConnectionState.Disconnected
        scheduleReconnect()
    }

    // ------------------------------------------------------------------
    // Frame handling
    // ------------------------------------------------------------------

    private fun handleFrame(message: ServerMessage) {
        when (message) {
            is ServerMessage.Ready -> onReady(message)
            is ServerMessage.VersionMismatch -> permanentVersionStop(message.requiredVersion)
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
            is ServerMessage.SearchResults, is ServerMessage.Log, is ServerMessage.Unknown,
            -> Unit
        }
    }

    private fun onReady(message: ServerMessage.Ready) {
        if (message.protocolVersion != PROTOCOL_VERSION) {
            permanentVersionStop(message.protocolVersion)
            return
        }
        sessionsState.value = message.sessions.sortedByDescending { it.updatedAt }
        providersState.value = message.providers
        workspaceRootState.value = message.workspaceRoot
        connectionState.value = ConnectionState.Connected
        // §5.5: re-attach every subscribed session and every session that has
        // pending outbound input, from its last good cursor.
        val toAttach: List<Pair<String, Long?>>
        synchronized(lock) {
            val ids = LinkedHashSet<String>()
            ids.addAll(subscribed)
            ids.addAll(tracker.attachedSessions())
            pendingStore.records.mapTo(ids) { it.sessionId }
            toAttach = ids.map { it to tracker.cursorFor(it) }
        }
        for ((sessionId, afterSeq) in toAttach) {
            sendFrame(ClientMessage.Attach(sessionId, afterSeq))
        }
        message.workspaceRoot?.let {
            sendFrame(ClientMessage.Browse(it))
            sendFrame(ClientMessage.Discover(it))
        }
    }

    private fun permanentVersionStop(requiredVersion: Int) {
        connectionState.value = ConnectionState.VersionMismatch(requiredVersion)
        val ws = synchronized(lock) {
            stopped = true
            reconnectJob?.cancel()
            reconnectJob = null
            socket
        }
        ws?.close(1000, null)
    }

    private fun onSnapshot(message: ServerMessage.Snapshot) {
        val cleared: List<String>
        synchronized(lock) {
            tracker.onSnapshot(message.sessionId, message.throughSeq)
            val result = PendingInput.reconcileWithSnapshot(pendingStore, message.sessionId, message.state)
            pendingStore = result.store
            cleared = result.cleared
            // Only now is redelivery for this session safe on this connection.
            reconciledSessions.add(message.sessionId)
        }
        projectionsState.value = projectionsState.value + (message.sessionId to message.state)
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
            if (!socketOpen) return
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
            toCancel?.cancel()
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

    override fun attach(sessionId: String) {
        synchronized(lock) { subscribed.add(sessionId) }
        sendFrame(ClientMessage.Attach(sessionId, synchronized(lock) { tracker.cursorFor(sessionId) }))
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

    private fun sendFrame(message: ClientMessage): Boolean {
        val ws = synchronized(lock) { if (socketOpen) socket else null } ?: return false
        return ws.send(message.encode())
    }

    private fun emitError(message: String) {
        errorsFlow.tryEmit(message)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    /** What /healthz tells an unauthenticated client: wire version + pairing capability. */
    private class Health(val protocolVersion: Int?, val pairing: Boolean)

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
