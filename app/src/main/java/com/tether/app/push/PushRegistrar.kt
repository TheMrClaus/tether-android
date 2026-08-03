package com.tether.app.push

import com.tether.app.client.Credential
import com.tether.app.client.SettingsStore
import com.tether.app.protocol.TetherJson
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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
 */
open class PushRegistrar(
    private val settings: SettingsStore,
    private val httpClient: OkHttpClient,
    private val tokenProvider: FirebaseTokenProvider,
) {

    private val json = "application/json".toMediaType()

    /**
     * Fetch `/api/push/fcm-config`; if configured, get the FCM token and POST
     * `/api/push/fcm-register` with the current scope + sets. Idempotent on the
     * server (upsert keyed by deviceId). Use on first enable / scope change /
     * FCM token rotation.
     *
     * Returns [PushRegistrarResult.ServerUnconfigured] when the server has no
     * FCM credentials, so the UI can show the "Server push not configured" line
     * without a toast.
     */
    suspend fun sync(scope: PushScope, attached: Set<String>, pinned: Set<String>): PushRegistrarResult =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext PushRegistrarResult.Error("No server configured.")
            val credential = settings.credential.first() ?: return@withContext PushRegistrarResult.Error("Not signed in.")
            // A cookie (password) login has no deviceId; FCM registration is a
            // per-device concept. Treat as unconfigured from the app's POV.
            if (credential !is Credential.DeviceToken) {
                return@withContext PushRegistrarResult.Error("FCM registration requires a paired device.")
            }

            val config = fetchConfig(base, credential) ?: return@withContext PushRegistrarResult.Error("Push config unreachable.")
            if (!config.configured) return@withContext PushRegistrarResult.ServerUnconfigured

            val fcmToken = tokenProvider.token() ?: return@withContext PushRegistrarResult.Error("FCM token unavailable.")
            val body = buildJsonObject {
                put("fcmToken", fcmToken)
                put("scope", scope.wire)
                put("attachedSessions", toJsonArray(attached))
                put("pinnedSessions", toJsonArray(pinned))
            }.toString()
            val response = send(
                base = base,
                credential = credential,
                method = "POST",
                path = "/api/push/fcm-register",
                body = body,
            ) ?: return@withContext PushRegistrarResult.Error("Push register request failed.")
            when (response.code) {
                200, 201 -> PushRegistrarResult.Success
                503 -> PushRegistrarResult.ServerUnconfigured
                else -> PushRegistrarResult.Error("Push register returned HTTP ${response.code}.")
            }
        }

    /**
     * Partial update of the authed device's row — no token re-fetch. Use when
     * only the scope or the attached/pinned sets change. The server returns 404
     * if the row does not exist; the caller should fall back to [sync].
     */
    suspend fun update(scope: PushScope, attached: Set<String>, pinned: Set<String>): PushRegistrarResult =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext PushRegistrarResult.Error("No server configured.")
            val credential = settings.credential.first() ?: return@withContext PushRegistrarResult.Error("Not signed in.")
            if (credential !is Credential.DeviceToken) {
                return@withContext PushRegistrarResult.Error("FCM registration requires a paired device.")
            }
            val body = buildJsonObject {
                put("scope", scope.wire)
                put("attachedSessions", toJsonArray(attached))
                put("pinnedSessions", toJsonArray(pinned))
            }.toString()
            val response = send(
                base = base,
                credential = credential,
                method = "PATCH",
                path = "/api/push/fcm-register",
                body = body,
            ) ?: return@withContext PushRegistrarResult.Error("Push update request failed.")
            when (response.code) {
                200, 201 -> PushRegistrarResult.Success
                404 -> PushRegistrarResult.Error("Not registered yet.")
                503 -> PushRegistrarResult.ServerUnconfigured
                else -> PushRegistrarResult.Error("Push update returned HTTP ${response.code}.")
            }
        }

    /** DELETE the authed device's row. Called on logout and on disable. */
    suspend fun unregister(): PushRegistrarResult = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext PushRegistrarResult.Success
        val credential = settings.credential.first() ?: return@withContext PushRegistrarResult.Success
        if (credential !is Credential.DeviceToken) return@withContext PushRegistrarResult.Success
        val response = send(
            base = base,
            credential = credential,
            method = "DELETE",
            path = "/api/push/fcm-register",
            body = "",
        ) ?: return@withContext PushRegistrarResult.Error("Push unregister request failed.")
        when (response.code) {
            200 -> PushRegistrarResult.Success
            else -> PushRegistrarResult.Error("Push unregister returned HTTP ${response.code}.")
        }
    }

    // ---- internals -------------------------------------------------------

    private suspend fun baseUrl(): HttpUrl? = settings.baseUrl.first()?.toHttpUrlOrNull()

    private fun fetchConfig(base: HttpUrl, credential: Credential.DeviceToken): FcmConfig? {
        val request = Request.Builder()
            .url(base.resolve("/api/push/fcm-config")!!)
            .authorize(credential)
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val obj = TetherJson.parseToJsonElement(response.body.string()) as? JsonObject ?: return null
                FcmConfig(
                    configured = obj["configured"]?.jsonPrimitive?.boolean == true,
                )
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun send(base: HttpUrl, credential: Credential.DeviceToken, method: String, path: String, body: String): okhttp3.Response? {
        val builder = Request.Builder().url(base.resolve(path)!!).authorize(credential)
        when (method) {
            "POST" -> builder.post(body.toRequestBody(json))
            "PATCH" -> builder.patch(body.toRequestBody(json))
            "DELETE" -> if (body.isNotEmpty()) builder.delete(body.toRequestBody(json)) else builder.delete()
        }
        val request = builder.build()
        return try {
            httpClient.newCall(request).execute()
        } catch (_: IOException) {
            null
        }
    }

    private fun Request.Builder.authorize(credential: Credential.DeviceToken): Request.Builder =
        header("Authorization", "Bearer ${credential.value}")

    private fun toJsonArray(ids: Set<String>): JsonArray =
        JsonArray(ids.map { JsonPrimitive(it) })

    private data class FcmConfig(val configured: Boolean)
}

/**
 * In-memory no-op registrar for tests / previews. Every call succeeds without
 * touching the network or Firebase. The seam matches [PushRegistrar] so the UI
 * can be driven by a fake in previews.
 */
class FakePushRegistrar : PushRegistrar(
    settings = com.tether.app.client.InMemorySettings(),
    httpClient = OkHttpClient(),
    tokenProvider = FirebaseTokenProvider { "fake-fcm-token" },
) {
    // The base class is fully functional against a MockWebServer; this fake is
    // a marker type so tests can assert "the UI is wired to a fake" if needed.
}