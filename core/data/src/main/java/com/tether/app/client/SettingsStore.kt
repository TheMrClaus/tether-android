package com.tether.app.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The one credential the client presents to a server. Exactly one is in force at
 * a time — every request path (HTTP probe + WS upgrade) reads this rather than
 * branching on "do we have a cookie or a token".
 *
 * See specs/protocol-spec.md §1.
 */
sealed interface Credential {
    /** Password login: the raw `tether_session` cookie VALUE (still URL-encoded). */
    data class Cookie(val value: String) : Credential

    /** Paired device: a `tthr_…` bearer token from POST /api/devices/claim. */
    data class DeviceToken(val value: String) : Credential
}

/**
 * Persisted client configuration. The DataStore implementation is app-private
 * (plain Preferences DataStore under filesDir).
 *
 * NOTE: both the tether_session cookie (7-day) and the device token (long-lived,
 * revocable only from a browser session) are bearer credentials; storing them in
 * plain DataStore relies on Android app sandboxing only. Migrating to encrypted
 * storage is flagged as deferred hardening.
 */
interface SettingsStore {
    /** Normalized server origin, e.g. "https://tether.example.com" — null until login/pairing. */
    val baseUrl: Flow<String?>

    /** Raw tether_session cookie VALUE (still URL-encoded) — null unless password-logged-in. */
    val cookie: Flow<String?>

    /** Paired-device bearer token (`tthr_…`) — null unless paired. */
    val deviceToken: Flow<String?>

    /**
     * The persisted credential, whichever kind it is. Cookie wins if both are
     * somehow present (a password login is the older, narrower grant, so
     * preferring it can never silently escalate to the device token).
     */
    val credential: Flow<Credential?>
        get() = combine(cookie, deviceToken) { cookieValue, tokenValue ->
            when {
                !cookieValue.isNullOrEmpty() -> Credential.Cookie(cookieValue)
                !tokenValue.isNullOrEmpty() -> Credential.DeviceToken(tokenValue)
                else -> null
            }
        }

    /** Persist the server and the credential that authenticates against it. */
    suspend fun setServer(baseUrl: String, credential: Credential)

    /**
     * Drop the credential but keep the server URL: used when the server tells us
     * the credential is dead (device revoked), where re-pairing is the next step.
     */
    suspend fun clearCredential()

    suspend fun clear()

    suspend fun readPendingInput(): String?

    suspend fun writePendingInput(raw: String)
}

class DataStoreSettings(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val cookieKey = stringPreferencesKey("session_cookie")
    // New key alongside the existing two: an already-logged-in install keeps its
    // base_url + session_cookie and is NOT signed out by the upgrade.
    private val deviceTokenKey = stringPreferencesKey("device_token")
    private val pendingKey = stringPreferencesKey("pending_input")

    override val baseUrl: Flow<String?> = dataStore.data.map { it[baseUrlKey] }
    override val cookie: Flow<String?> = dataStore.data.map { it[cookieKey] }
    override val deviceToken: Flow<String?> = dataStore.data.map { it[deviceTokenKey] }

    override suspend fun setServer(baseUrl: String, credential: Credential) {
        dataStore.edit {
            it[baseUrlKey] = baseUrl
            // Writing one credential clears the other: two live credentials would
            // make "which one is in force" ambiguous on the next launch.
            when (credential) {
                is Credential.Cookie -> {
                    it[cookieKey] = credential.value
                    it.remove(deviceTokenKey)
                }
                is Credential.DeviceToken -> {
                    it[deviceTokenKey] = credential.value
                    it.remove(cookieKey)
                }
            }
        }
    }

    override suspend fun clearCredential() {
        dataStore.edit {
            it.remove(cookieKey)
            it.remove(deviceTokenKey)
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(baseUrlKey)
            it.remove(cookieKey)
            it.remove(deviceTokenKey)
            it.remove(pendingKey)
        }
    }

    override suspend fun readPendingInput(): String? = dataStore.data.first()[pendingKey]

    override suspend fun writePendingInput(raw: String) {
        dataStore.edit { it[pendingKey] = raw }
    }

    companion object {
        /**
         * Build a file-backed store. Call with the app's filesDir, e.g.
         * `DataStoreSettings.create(context.filesDir, appScope)`.
         */
        fun create(dir: File, scope: CoroutineScope): DataStoreSettings = DataStoreSettings(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "tether_settings.preferences_pb")
            },
        )
    }
}

/** In-memory store for tests and previews. */
class InMemorySettings(
    initialBaseUrl: String? = null,
    initialCookie: String? = null,
    initialDeviceToken: String? = null,
) : SettingsStore {
    private val baseUrlState = MutableStateFlow(initialBaseUrl)
    private val cookieState = MutableStateFlow(initialCookie)
    private val deviceTokenState = MutableStateFlow(initialDeviceToken)
    private var pending: String? = null

    override val baseUrl: Flow<String?> = baseUrlState
    override val cookie: Flow<String?> = cookieState
    override val deviceToken: Flow<String?> = deviceTokenState

    override suspend fun setServer(baseUrl: String, credential: Credential) {
        baseUrlState.value = baseUrl
        when (credential) {
            is Credential.Cookie -> {
                cookieState.value = credential.value
                deviceTokenState.value = null
            }
            is Credential.DeviceToken -> {
                deviceTokenState.value = credential.value
                cookieState.value = null
            }
        }
    }

    override suspend fun clearCredential() {
        cookieState.value = null
        deviceTokenState.value = null
    }

    override suspend fun clear() {
        baseUrlState.value = null
        cookieState.value = null
        deviceTokenState.value = null
        pending = null
    }

    override suspend fun readPendingInput(): String? = pending

    override suspend fun writePendingInput(raw: String) {
        pending = raw
    }
}
