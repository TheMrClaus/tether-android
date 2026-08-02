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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persisted client configuration. The DataStore implementation is app-private
 * (plain Preferences DataStore under filesDir).
 *
 * NOTE: the tether_session cookie is a 7-day bearer credential; storing it in
 * plain DataStore relies on Android app sandboxing only. Migrating to
 * encrypted storage is flagged as deferred hardening.
 */
interface SettingsStore {
    /** Normalized server origin, e.g. "https://tether.example.com" — null until login. */
    val baseUrl: Flow<String?>

    /** Raw tether_session cookie VALUE (still URL-encoded) — null until login. */
    val cookie: Flow<String?>

    suspend fun setServer(baseUrl: String, cookie: String)

    suspend fun clear()

    suspend fun readPendingInput(): String?

    suspend fun writePendingInput(raw: String)
}

class DataStoreSettings(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val cookieKey = stringPreferencesKey("session_cookie")
    private val pendingKey = stringPreferencesKey("pending_input")

    override val baseUrl: Flow<String?> = dataStore.data.map { it[baseUrlKey] }
    override val cookie: Flow<String?> = dataStore.data.map { it[cookieKey] }

    override suspend fun setServer(baseUrl: String, cookie: String) {
        dataStore.edit {
            it[baseUrlKey] = baseUrl
            it[cookieKey] = cookie
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(baseUrlKey)
            it.remove(cookieKey)
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
) : SettingsStore {
    private val baseUrlState = MutableStateFlow(initialBaseUrl)
    private val cookieState = MutableStateFlow(initialCookie)
    private var pending: String? = null

    override val baseUrl: Flow<String?> = baseUrlState
    override val cookie: Flow<String?> = cookieState

    override suspend fun setServer(baseUrl: String, cookie: String) {
        baseUrlState.value = baseUrl
        cookieState.value = cookie
    }

    override suspend fun clear() {
        baseUrlState.value = null
        cookieState.value = null
        pending = null
    }

    override suspend fun readPendingInput(): String? = pending

    override suspend fun writePendingInput(raw: String) {
        pending = raw
    }
}
