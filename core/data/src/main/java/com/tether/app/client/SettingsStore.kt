package com.tether.app.client

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * Persisted client configuration. The DataStore implementation keeps the server
 * URL and pending input in `tether_settings.preferences_pb` and the credentials
 * (the tether_session cookie and the `tthr_` device token, both bearer
 * credentials) sealed with a Keystore AES-GCM key in a separate file under
 * `credentials/` — see [DataStoreSettings].
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

/**
 * DataStore-backed settings with the credentials sealed at rest.
 *
 * - [dataStore] (`tether_settings.preferences_pb`): base URL + pending input.
 *   Versions before T1.4 also kept `session_cookie` / `device_token` here in
 *   PLAINTEXT; the first load migrates them (see [loadLocked]) and deletes them.
 * - [credentialStore] (`credentials/tether_credentials.preferences_pb`): each
 *   credential sealed by [cipher] (AES-256-GCM under a non-exportable Keystore
 *   key in production), Base64, with the slot name as AAD.
 *
 * Failure policy — fail closed, never crash, never log a value:
 * - a blob that does not open (corrupt, truncated, wrong slot, key invalidated
 *   or missing) reads as "no credential", i.e. logged out, and is deleted; an
 *   unusable key is destroyed so the next login seals under a fresh one;
 * - a credential that cannot be sealed is kept in memory for this process only
 *   (the user signs in again after a restart) — never written in plaintext;
 * - migration deletes the plaintext whether or not sealing succeeded.
 *
 * The decrypted values live in memory after the first load, so the Keystore is
 * touched once per process, not per flow emission.
 */
class DataStoreSettings(
    private val dataStore: DataStore<Preferences>,
    private val credentialStore: DataStore<Preferences>,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsStore {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val pendingKey = stringPreferencesKey("pending_input")

    // Pre-T1.4 plaintext slots in [dataStore]. Read once for migration, then deleted.
    private val legacyCookieKey = stringPreferencesKey(SLOT_COOKIE)
    private val legacyDeviceTokenKey = stringPreferencesKey(SLOT_DEVICE_TOKEN)

    // Sealed slots in [credentialStore].
    private val sealedCookieKey = stringPreferencesKey(SLOT_COOKIE)
    private val sealedDeviceTokenKey = stringPreferencesKey(SLOT_DEVICE_TOKEN)

    private data class Credentials(val cookie: String?, val deviceToken: String?)

    private val mutex = Mutex()

    // null = not loaded yet. Every read waits for the first load.
    private val credentials = MutableStateFlow<Credentials?>(null)

    override val baseUrl: Flow<String?> = dataStore.data.map { it[baseUrlKey] }

    override val cookie: Flow<String?> = credentialFlow { it.cookie }

    override val deviceToken: Flow<String?> = credentialFlow { it.deviceToken }

    private fun credentialFlow(pick: (Credentials) -> String?): Flow<String?> = flow {
        ensureLoaded()
        emitAll(credentials.filterNotNull().map(pick))
    }.distinctUntilChanged()

    private suspend fun ensureLoaded() {
        if (credentials.value != null) return
        mutex.withLock { if (credentials.value == null) loadLocked() }
    }

    /** Open the sealed slots and migrate any plaintext left by an older version. Caller holds [mutex]. */
    private suspend fun loadLocked() = withContext(ioDispatcher) {
        val sealed = credentialStore.data.first()
        var cookieValue = openSlot(sealed[sealedCookieKey], SLOT_COOKIE)
        var tokenValue = openSlot(sealed[sealedDeviceTokenKey], SLOT_DEVICE_TOKEN)

        val legacy = dataStore.data.first()
        val legacyCookie = legacy[legacyCookieKey]?.takeIf { it.isNotEmpty() }
        val legacyToken = legacy[legacyDeviceTokenKey]?.takeIf { it.isNotEmpty() }
        // A sealed value wins over a plaintext one: it can only exist if a
        // migration already sealed it (the plaintext is a leftover of a crash
        // between the two writes) or a newer login replaced it.
        if (cookieValue == null && tokenValue == null) {
            cookieValue = legacyCookie
            tokenValue = legacyToken
        }

        // Rewrite the sealed file to exactly what is in force: undecryptable
        // blobs are dropped, migrated plaintext is sealed (or, if sealing fails,
        // held in memory only).
        val cookieBlob = cookieValue?.let { sealSlot(it, SLOT_COOKIE) }
        val tokenBlob = tokenValue?.let { sealSlot(it, SLOT_DEVICE_TOKEN) }
        credentialStore.edit { prefs ->
            if (cookieBlob != null) prefs[sealedCookieKey] = cookieBlob else prefs.remove(sealedCookieKey)
            if (tokenBlob != null) prefs[sealedDeviceTokenKey] = tokenBlob else prefs.remove(sealedDeviceTokenKey)
        }
        // Only after the sealed copy is on disk: delete the plaintext.
        if (legacy.contains(legacyCookieKey) || legacy.contains(legacyDeviceTokenKey)) {
            dataStore.edit {
                it.remove(legacyCookieKey)
                it.remove(legacyDeviceTokenKey)
            }
        }
        credentials.value = Credentials(cookieValue, tokenValue)
    }

    private fun openSlot(encoded: String?, slot: String): String? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            val blob = Base64.getDecoder().decode(encoded)
            String(cipher.open(blob, aadFor(slot)), Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (e: CredentialCipherException) {
            if (e.keyUnusable) cipher.destroyKey()
            null
        } catch (_: IllegalArgumentException) {
            null // not Base64: corrupt
        }
    }

    /** Sealed + Base64, or null when sealing is impossible (then: memory only). */
    private fun sealSlot(value: String, slot: String): String? {
        val plaintext = value.toByteArray(Charsets.UTF_8)
        repeat(2) { attempt ->
            try {
                return Base64.getEncoder().encodeToString(cipher.seal(plaintext, aadFor(slot)))
            } catch (e: CredentialCipherException) {
                // One retry under a fresh key when the old one is dead.
                if (!e.keyUnusable || attempt == 1) return null
                cipher.destroyKey()
            }
        }
        return null
    }

    private fun aadFor(slot: String): ByteArray = "$AAD_PREFIX$slot".toByteArray(Charsets.UTF_8)

    override suspend fun setServer(baseUrl: String, credential: Credential) {
        mutex.withLock {
            if (credentials.value == null) loadLocked()
            dataStore.edit {
                it[baseUrlKey] = baseUrl
                it.remove(legacyCookieKey)
                it.remove(legacyDeviceTokenKey)
            }
            // Writing one credential clears the other: two live credentials would
            // make "which one is in force" ambiguous on the next launch.
            val next = when (credential) {
                is Credential.Cookie -> Credentials(credential.value, null)
                is Credential.DeviceToken -> Credentials(null, credential.value)
            }
            withContext(ioDispatcher) {
                val cookieBlob = next.cookie?.let { sealSlot(it, SLOT_COOKIE) }
                val tokenBlob = next.deviceToken?.let { sealSlot(it, SLOT_DEVICE_TOKEN) }
                credentialStore.edit { prefs ->
                    if (cookieBlob != null) prefs[sealedCookieKey] = cookieBlob else prefs.remove(sealedCookieKey)
                    if (tokenBlob != null) prefs[sealedDeviceTokenKey] = tokenBlob else prefs.remove(sealedDeviceTokenKey)
                }
            }
            credentials.value = next
        }
    }

    override suspend fun clearCredential() {
        mutex.withLock {
            credentials.value = Credentials(null, null)
            credentialStore.edit { it.clear() }
            dataStore.edit {
                it.remove(legacyCookieKey)
                it.remove(legacyDeviceTokenKey)
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            credentials.value = Credentials(null, null)
            credentialStore.edit { it.clear() }
            dataStore.edit {
                it.remove(baseUrlKey)
                it.remove(legacyCookieKey)
                it.remove(legacyDeviceTokenKey)
                it.remove(pendingKey)
            }
        }
    }

    override suspend fun readPendingInput(): String? = dataStore.data.first()[pendingKey]

    override suspend fun writePendingInput(raw: String) {
        dataStore.edit { it[pendingKey] = raw }
    }

    companion object {
        const val SETTINGS_FILE = "tether_settings.preferences_pb"

        /** Directory (under filesDir) holding only sealed credentials; excluded from backup/transfer. */
        const val CREDENTIALS_DIR = "credentials"
        const val CREDENTIALS_FILE = "tether_credentials.preferences_pb"

        const val SLOT_COOKIE = "session_cookie"
        const val SLOT_DEVICE_TOKEN = "device_token"
        private const val AAD_PREFIX = "tether.credential.v1|"

        /**
         * Build the file-backed store. Production:
         * `DataStoreSettings.create(context.filesDir, appScope, AesGcmCredentialCipher(KeystoreCredentialKeySource()))`.
         */
        fun create(dir: File, scope: CoroutineScope, cipher: CredentialCipher): DataStoreSettings {
            val credentialsDir = File(dir, CREDENTIALS_DIR).apply { mkdirs() }
            return DataStoreSettings(
                dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, SETTINGS_FILE) },
                credentialStore = PreferenceDataStoreFactory.create(
                    // An unreadable credential file is "logged out", not a crash.
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                ) { File(credentialsDir, CREDENTIALS_FILE) },
                cipher = cipher,
            )
        }
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
