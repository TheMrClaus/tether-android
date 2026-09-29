package com.tether.app.push

import com.tether.app.client.Credential
import com.tether.app.client.LOGOUT_CALL_TIMEOUT_MS
import com.tether.app.client.SettingsStore
import com.tether.app.protocol.TetherJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

/**
 * Outcome of a [PushRegistrar] call. The registrar is best-effort: failures
 * surface to the caller as [Error] so it can post a toast, but never throw.
 */
sealed interface PushRegistrarResult {
    data object Success : PushRegistrarResult
    /** The server reports FCM is not configured — the app shows "not configured". */
    data object ServerUnconfigured : PushRegistrarResult
    data class Error(val message: String) : PushRegistrarResult

    /**
     * This server now names a different Firebase project than the one the
     * device accepted from it. Nothing was registered, and re-pairing (logout,
     * then sign in again) accepts the new project.
     *
     * No screen shows this yet: no push result reaches the UI today (the
     * coordinator only uses it to decide whether to retry), so a user sees push
     * go quiet without a reason.
     * TODO(T12.2): surface it in the push settings as "Push project changed;
     * re-pair to accept."
     */
    data object ProjectChanged : PushRegistrarResult
}

/**
 * Talks to the server's FCM registration endpoints. The row key on the server
 * is the device's `deviceId`, derived from the bearer token the registrar adds
 * to every call — so this class never holds or learns the deviceId itself.
 *
 * All methods are suspend and run on [Dispatchers.IO]. They are best-effort:
 * network failures return [PushRegistrarResult.Error] instead of throwing, so
 * the caller can surface a toast and retry on the next foreground/toggle.
 *
 * `open` so a [FakePushRegistrar] can subclass it for tests/previews without
 * touching the network or Firebase.
 *
 * @param tokenProvider seam over the Firebase token call, so unit tests can
 *   stub it without Play Services. Production passes
 *   [FirebaseTokenProvider.Default].
 * @param firebase brings FirebaseApp up from the server's fcm-config `client`
 *   block before a token is requested. Production passes
 *   [AndroidFirebaseInitializer]; JVM tests default to
 *   [FirebaseInitializer.AlreadyInitialised].
 */
open class PushRegistrar(
    private val settings: SettingsStore,
    httpClient: OkHttpClient,
    private val tokenProvider: FirebaseTokenProvider,
    private val firebase: FirebaseInitializer = FirebaseInitializer.AlreadyInitialised,
) {

    /**
     * Every call here carries the device token, so none follows a redirect: a
     * 3xx to another host must not receive the bearer (T1.4 review; same rule as
     * RealTetherClient.authHttp). Derived here so every caller gets it.
     */
    private val http: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * ta-jt9 I-B: the unregister runs inside logout's bounded hook, but a blocking `execute()`
     * does not end when the coroutine's timeout does. So the call itself is bounded, by the same
     * [LOGOUT_CALL_TIMEOUT_MS] as the logout's own server calls.
     */
    private val unregisterHttp: OkHttpClient = http.newBuilder()
        .callTimeout(LOGOUT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val json = "application/json".toMediaType()

    /**
     * Fetch `/api/push/fcm-config`; if configured, get the FCM token and POST
     * `/api/push/fcm-register` with the current scope + sets. Idempotent on the
     * server (upsert keyed by deviceId). Use on first enable / scope change /
     * FCM token rotation.
     *
     * The POST **replaces** the device's row (tether `lib/fcm-push.mjs` upsert),
     * and an absent `syncHints` is stored as false. So [syncHints], the device's
     * stored opt-in to the v130 sync hint, is always sent explicitly. Otherwise a
     * token rotation would silently opt the device out (T13.4 note from the
     * S13.1 review).
     *
     * Returns [PushRegistrarResult.ServerUnconfigured] when the server has no
     * FCM credentials, so the UI can show the "Server push not configured" line
     * without a toast.
     */
    open suspend fun sync(
        scope: PushScope,
        attached: Set<String>,
        pinned: Set<String>,
        syncHints: Boolean,
    ): PushRegistrarResult =
        withContext(Dispatchers.IO) {
            // One consistent (URL, credential) snapshot: never URL A + token B.
            val session = settings.session()
            val base = session.baseUrl?.toHttpUrlOrNull() ?: return@withContext PushRegistrarResult.Error("No server configured.")
            val credential = session.credential ?: return@withContext PushRegistrarResult.Error("Not signed in.")
            // A cookie (password) login has no deviceId; FCM registration is a
            // per-device concept. Treat as unconfigured from the app's POV.
            if (credential !is Credential.DeviceToken) {
                return@withContext PushRegistrarResult.Error("FCM registration requires a paired device.")
            }

            val config = fetchConfig(base, credential) ?: return@withContext PushRegistrarResult.Error("Push config unreachable.")
            if (!config.configured) return@withContext PushRegistrarResult.ServerUnconfigured
            // FirebaseApp must be up before a token exists. Its options come from
            // this server (fcm-config `client`), bound to its origin; see
            // AndroidFirebaseInitializer for the first-use-wins rules.
            when (firebase.ensure(config.client, originOf(base))) {
                FirebaseSetup.Ready -> Unit
                FirebaseSetup.Unavailable ->
                    return@withContext PushRegistrarResult.Error("Firebase client config unavailable.")
                FirebaseSetup.ProjectChanged -> return@withContext PushRegistrarResult.ProjectChanged
            }

            val fcmToken = tokenProvider.token() ?: return@withContext PushRegistrarResult.Error("FCM token unavailable.")
            val body = buildJsonObject {
                put("fcmToken", fcmToken)
                put("scope", scope.wire)
                put("attachedSessions", toJsonArray(attached))
                put("pinnedSessions", toJsonArray(pinned))
                put("syncHints", syncHints)
            }.toString()
            val response = send(
                base = base,
                credential = credential,
                method = "POST",
                path = "/api/push/fcm-register",
                body = body,
            ) ?: return@withContext PushRegistrarResult.Error("Push register request failed.")
            // Closed on every path, so the connection goes back to the pool.
            response.use {
                when (it.code) {
                    200, 201 -> PushRegistrarResult.Success
                    503 -> PushRegistrarResult.ServerUnconfigured
                    else -> PushRegistrarResult.Error("Push register returned HTTP ${it.code}.")
                }
            }
        }

    /**
     * Partial update of the authed device's row — no token re-fetch. Use when
     * only the scope or the attached/pinned sets change. The server returns 404
     * if the row does not exist; the caller should fall back to [sync].
     * [syncHints] is sent here too, so the row always holds the stored opt-in.
     */
    open suspend fun update(
        scope: PushScope,
        attached: Set<String>,
        pinned: Set<String>,
        syncHints: Boolean,
    ): PushRegistrarResult =
        withContext(Dispatchers.IO) {
            // One consistent (URL, credential) snapshot: never URL A + token B.
            val session = settings.session()
            val base = session.baseUrl?.toHttpUrlOrNull() ?: return@withContext PushRegistrarResult.Error("No server configured.")
            val credential = session.credential ?: return@withContext PushRegistrarResult.Error("Not signed in.")
            if (credential !is Credential.DeviceToken) {
                return@withContext PushRegistrarResult.Error("FCM registration requires a paired device.")
            }
            val body = buildJsonObject {
                put("scope", scope.wire)
                put("attachedSessions", toJsonArray(attached))
                put("pinnedSessions", toJsonArray(pinned))
                put("syncHints", syncHints)
            }.toString()
            val response = send(
                base = base,
                credential = credential,
                method = "PATCH",
                path = "/api/push/fcm-register",
                body = body,
            ) ?: return@withContext PushRegistrarResult.Error("Push update request failed.")
            response.use {
                when (it.code) {
                    200, 201 -> PushRegistrarResult.Success
                    404 -> PushRegistrarResult.Error("Not registered yet.")
                    503 -> PushRegistrarResult.ServerUnconfigured
                    else -> PushRegistrarResult.Error("Push update returned HTTP ${it.code}.")
                }
            }
        }

    /** DELETE the authed device's row. Called on disable. */
    open suspend fun unregister(): PushRegistrarResult = withContext(Dispatchers.IO) {
        val session = settings.session()
        val base = session.baseUrl?.toHttpUrlOrNull() ?: return@withContext PushRegistrarResult.Success
        val credential = session.credential ?: return@withContext PushRegistrarResult.Success
        if (credential !is Credential.DeviceToken) return@withContext PushRegistrarResult.Success
        unregisterWith(base, credential)
    }

    /**
     * Logout: the credential is already gone from [settings] (it is forgotten
     * first, fail-safe), so the caller hands over the one that was in force.
     * Without this the server would keep pushing to a signed-out phone.
     */
    open suspend fun unregister(baseUrl: String, credential: Credential): PushRegistrarResult = withContext(Dispatchers.IO) {
        val base = baseUrl.toHttpUrlOrNull() ?: return@withContext PushRegistrarResult.Success
        if (credential !is Credential.DeviceToken) return@withContext PushRegistrarResult.Success
        unregisterWith(base, credential)
    }

    private fun unregisterWith(base: HttpUrl, credential: Credential.DeviceToken): PushRegistrarResult {
        val response = send(
            base = base,
            credential = credential,
            method = "DELETE",
            path = "/api/push/fcm-register",
            body = "",
            client = unregisterHttp,
        ) ?: return PushRegistrarResult.Error("Push unregister request failed.")
        return response.use {
            when (it.code) {
                200 -> PushRegistrarResult.Success
                else -> PushRegistrarResult.Error("Push unregister returned HTTP ${it.code}.")
            }
        }
    }

    // ---- internals -------------------------------------------------------

    private fun fetchConfig(base: HttpUrl, credential: Credential.DeviceToken): FcmConfig? {
        val request = Request.Builder()
            .url(base.resolve("/api/push/fcm-config")!!)
            .authorize(credential)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                // The real body is ~300 bytes. Anything past the cap is not a
                // config (an HTML login page from an auth proxy, say), and is
                // never read whole into memory.
                val bytes = response.peekBody(MAX_CONFIG_BYTES + 1).bytes()
                if (bytes.size > MAX_CONFIG_BYTES) return null
                val text = bytes.decodeToString()
                // T6.2 R3-M3: kotlinx's tree reader recurses per nested bracket; 16 KB of `[`
                // overflowed the stack on every start while push was on.
                if (com.tether.app.protocol.ServerMessage.nestsDeeperThan(text, com.tether.app.protocol.ServerMessage.MAX_FRAME_DEPTH)) return null
                val obj = TetherJson.parseToJsonElement(text) as? JsonObject ?: return null
                FcmConfig(
                    configured = (obj["configured"] as? JsonPrimitive)?.booleanOrNull == true,
                    client = FirebaseClientConfig.parse(obj.fcmClientObject()),
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            // A 200 that is not JSON (HTML from a proxy, a truncated body):
            // "push config unreachable", never an exception out of the registrar.
            // Before round 3 this escaped and crashed the app on every start.
            null
        }
    }

    private fun send(
        base: HttpUrl,
        credential: Credential.DeviceToken,
        method: String,
        path: String,
        body: String,
        client: OkHttpClient = http,
    ): okhttp3.Response? {
        val builder = Request.Builder().url(base.resolve(path)!!).authorize(credential)
        when (method) {
            "POST" -> builder.post(body.toRequestBody(json))
            "PATCH" -> builder.patch(body.toRequestBody(json))
            "DELETE" -> if (body.isNotEmpty()) builder.delete(body.toRequestBody(json)) else builder.delete()
        }
        val request = builder.build()
        return try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            null
        }
    }

    private fun Request.Builder.authorize(credential: Credential.DeviceToken): Request.Builder =
        header("Authorization", "Bearer ${credential.value}")

    private fun toJsonArray(ids: Set<String>): JsonArray =
        JsonArray(ids.map { JsonPrimitive(it) })

    /** scheme://host:port: what the Firebase binding is keyed on. */
    private fun originOf(base: HttpUrl): String = "${base.scheme}://${base.host}:${base.port}"

    private data class FcmConfig(val configured: Boolean, val client: FirebaseClientConfig?)

    private companion object {
        const val MAX_CONFIG_BYTES = 16L * 1024
    }
}

/**
 * In-memory no-op registrar for tests / previews. Every call succeeds without
 * touching the network or Firebase. The seam matches [PushRegistrar] so the UI
 * can be driven by a fake in previews.
 */
class FakePushRegistrar : PushRegistrar(
    settings = com.tether.app.client.InMemorySettings(),
    httpClient = OkHttpClient(),
    tokenProvider = FirebaseTokenProvider { "fake-fcm-token-not-a-real-credential" },
) {
    // The base class is fully functional against a MockWebServer; this fake is
    // a marker type so tests can assert "the UI is wired to a fake" if needed.
}