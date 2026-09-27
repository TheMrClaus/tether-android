package com.tether.app.client

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    data class Cookie(val value: String) : Credential {
        /** Redacted: a credential must never reach a log, a crash report or a string template. */
        override fun toString(): String = "Cookie(***)"
    }

    /** Paired device: a `tthr_…` bearer token from POST /api/devices/claim. */
    data class DeviceToken(val value: String) : Credential {
        override fun toString(): String = "DeviceToken(***)"
    }
}

/** A consistent (server, credential) pair; see [SettingsStore.session]. */
data class Session(val baseUrl: String?, val credential: Credential?)

/**
 * The canonical server ORIGIN: `scheme://host:port` as OkHttp canonicalises a URL
 * (scheme and host lower-cased, IDN in punycode, the default port written out,
 * any path, query or trailing slash ignored, `_` allowed in the host). So
 * `https://Host:443/` and `https://host` are one origin; `https://host:8443`,
 * `http://host` and `https://other` are three others. Null for anything that is
 * not an http(s) URL.
 *
 * It is the identity everything bound to one server is keyed by: the sealed
 * credential's AAD and the durable-send slots ([SettingsStore.readPendingInput]).
 */
fun serverOrigin(baseUrl: String?): String? {
    val url = baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
    return "${url.scheme}://${url.host}:${url.port}"
}

/**
 * The durable-send slots in the settings file, one per server origin, and the
 * migration of the single slot app 0.6.0 wrote. Shared by [DataStoreSettings]
 * and [InMemorySettings] so both apply exactly the same rule.
 *
 * - `pending_input|<origin>`: that server's `{v:2, records, cleared}` payload.
 * - `pending_input` (0.6.0): ONE slot, not keyed by server. It was only ever
 *   filled for the server configured at the time, so it belongs to the origin
 *   of the `base_url` stored next to it. It is moved there once, in the same
 *   atomic edit that reads that URL (and before any edit that moves the URL).
 *   With no parsable `base_url` it moves to [UNATTRIBUTED_KEY]: kept on the
 *   device, never loaded, never sent. If the target slot already exists (only
 *   possible after a downgrade and a second upgrade) the legacy value is left
 *   where it is, inert: never merged into a slot it may not belong to.
 */
object PendingSlots {
    const val LEGACY_KEY = "pending_input"
    const val UNATTRIBUTED_KEY = "pending_input_unattributed"
    private const val ORIGIN_PREFIX = "pending_input|"

    /** The slot of [origin], which must already be canonical ([serverOrigin] of itself). */
    fun keyFor(origin: String): String {
        require(serverOrigin(origin) == origin) { "not a canonical server origin" }
        return ORIGIN_PREFIX + origin
    }

    /** Every durable-send key (for a full wipe). */
    fun isPendingKey(name: String): Boolean =
        name == LEGACY_KEY || name == UNATTRIBUTED_KEY || name.startsWith(ORIGIN_PREFIX)

    /**
     * Move the 0.6.0 slot to its owner. [get]/[set]/[remove] address ONE
     * consistent snapshot (a DataStore edit, or state under a lock) that also
     * holds `base_url`, read through [baseUrl].
     */
    fun migrateLegacy(
        baseUrl: String?,
        get: (String) -> String?,
        set: (String, String) -> Unit,
        remove: (String) -> Unit,
    ) {
        val legacy = get(LEGACY_KEY) ?: return
        val target = serverOrigin(baseUrl)?.let(::keyFor) ?: UNATTRIBUTED_KEY
        if (get(target) != null) return
        set(target, legacy)
        remove(LEGACY_KEY)
    }
}

/** Cookie wins over a device token (the narrower, older grant); empty = absent. */
fun credentialInForce(cookie: String?, deviceToken: String?): Credential? = when {
    !cookie.isNullOrEmpty() -> Credential.Cookie(cookie)
    !deviceToken.isNullOrEmpty() -> Credential.DeviceToken(deviceToken)
    else -> null
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
        get() = combine(cookie, deviceToken, ::credentialInForce)

    /**
     * The server URL and the credential in force, read as ONE consistent pair.
     * Every path that SENDS a credential uses this: reading [baseUrl] and
     * [credential] separately can straddle a server switch and pair URL A with
     * credential B.
     */
    suspend fun session(): Session

    /** Persist the server and the credential that authenticates against it. */
    suspend fun setServer(baseUrl: String, credential: Credential)

    /**
     * Drop the credential but keep the server URL: used when the server tells us
     * the credential is dead (device revoked), where re-pairing is the next step.
     */
    suspend fun clearCredential()

    suspend fun clear()

    /**
     * The durable-send store of ONE server: the web's `tether:pendingInput`
     * payload (`{v:2, records, cleared}`, lib/pending-input.mjs toPersistable),
     * kept per canonical [origin] ([serverOrigin]) exactly as the web keeps it
     * per origin in localStorage. Input written for server A can therefore
     * never be read back as server B's, whatever the configured URL says.
     * Lives in the backup-excluded settings file: unsent prompts never leave
     * the device. A 0.6.0 single-slot payload is attributed on first access
     * (see [PendingSlots]). [origin] must be canonical.
     */
    suspend fun readPendingInput(origin: String): String?

    /** Replaces [origin]'s whole payload atomically: a crash leaves the old one or the new one, never a mix. */
    suspend fun writePendingInput(origin: String, raw: String)

    /**
     * A 0.6.0 payload that no server could be attributed to (no server was
     * configured next to it): kept, never loaded into a live store, never sent.
     */
    suspend fun readUnattributedPendingInput(): String?
}

/**
 * DataStore-backed settings with the credentials sealed at rest.
 *
 * - [dataStore] (`tether_settings.preferences_pb`): base URL + pending input,
 *   and the logout tombstone. Versions before T1.4 also kept `session_cookie` /
 *   `device_token` here in PLAINTEXT; the first load migrates them (see
 *   [loadLocked]) and deletes them.
 * - [credentialStore] (`credentials/tether_credentials.preferences_pb`): each
 *   credential sealed by [cipher] (AES-256-GCM under a non-exportable Keystore
 *   key in production), Base64. The AAD is `slot|origin`: a blob only opens for
 *   the slot AND the server origin it was sealed for, so a URL that changed
 *   without its credential (a torn write) reads as logged out instead of
 *   presenting server A's credential to server B.
 *
 * Write order in [setServer] — never a moment on disk where the new URL pairs
 * with the old credential: (1) delete the old credential, (2) move the URL,
 * (3) seal the new credential for the new origin. A crash between any two steps
 * leaves "signed out", never a cross-origin pair.
 *
 * Failure policy — fail closed, never crash, never log a value:
 * - a blob that does not open (corrupt, truncated, wrong slot/origin, key
 *   missing / permanently invalidated) reads as logged out and is deleted; a
 *   dead key is destroyed so the next login seals under a fresh one;
 * - a TRANSIENT Keystore error reads as logged out for now but leaves the key
 *   and the blob alone; the next read (e.g. the next start()) retries;
 * - a credential that cannot be sealed is kept in memory for this process only
 *   (the user signs in again after a restart) — never written in plaintext;
 * - a clear that cannot reach the credential file (logout) is retried once, then
 *   recorded as a tombstone in [dataStore] that [loadLocked] honours;
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

    // The 0.6.0 single pending slot was attributed to its server (this process).
    @Volatile
    private var pendingMigrated = false

    /** Set when a logout could not delete the sealed credentials: they are dead regardless. */
    private val clearedTombstoneKey = booleanPreferencesKey("credentials_cleared")

    // Pre-T1.4 plaintext slots in [dataStore]. Read once for migration, then deleted.
    private val legacyCookieKey = stringPreferencesKey(SLOT_COOKIE)
    private val legacyDeviceTokenKey = stringPreferencesKey(SLOT_DEVICE_TOKEN)

    // Sealed slots in [credentialStore].
    private val sealedCookieKey = stringPreferencesKey(SLOT_COOKIE)
    private val sealedDeviceTokenKey = stringPreferencesKey(SLOT_DEVICE_TOKEN)

    /**
     * Consecutive launches whose credential read hit a [CipherFailure.Suspect]
     * error. At [SUSPECT_ROTATE_AFTER] the key is rotated (destroyed, blobs
     * dropped), so a key that is broken for good stops forcing a sign-in on every
     * launch. Reset by any successful open or seal.
     */
    private val suspectFailuresKey = intPreferencesKey("credential_key_suspect_failures")

    private data class Credentials(val cookie: String?, val deviceToken: String?) {
        override fun toString(): String = "Credentials(cookie=${if (cookie == null) "null" else "***"}, " +
            "deviceToken=${if (deviceToken == null) "null" else "***"})"
    }

    private sealed interface Slot {
        data object Empty : Slot
        data class Opened(val value: String, val reseal: Boolean) : Slot {
            override fun toString(): String = "Opened(***, reseal=$reseal)"
        }
        /** Undecryptable for good: delete it. */
        data object Dead : Slot
        /** Keystore not usable right now: leave the blob, retry later. [suspect] counts toward rotation. */
        data class Unavailable(val suspect: Boolean) : Slot
    }

    private val mutex = Mutex()

    // null = not loaded yet. Every read waits for the first load.
    private val credentials = MutableStateFlow<Credentials?>(null)

    // A transient Keystore failure left a blob unopened: the next read retries.
    @Volatile
    private var retryPending = false

    // This process already counted its suspect failure (count once per launch).
    private var suspectCounted = false

    private var lastSealFailure: CipherFailure? = null

    /**
     * Count this launch's suspect failure (at most once); at the threshold,
     * destroy the key and reset the counter. True = the key was just rotated.
     * Caller holds [mutex].
     */
    private suspend fun noteSuspectFailureLocked(): Boolean {
        if (suspectCounted) return false
        suspectCounted = true
        val n = (dataStore.data.first()[suspectFailuresKey] ?: 0) + 1
        val rotate = n >= SUSPECT_ROTATE_AFTER
        if (rotate) cipher.destroyKey()
        runCatching {
            dataStore.edit { if (rotate) it.remove(suspectFailuresKey) else it[suspectFailuresKey] = n }
        }
        return rotate
    }

    override val baseUrl: Flow<String?> = dataStore.data.map { it[baseUrlKey] }

    override val cookie: Flow<String?> = credentialFlow { it.cookie }

    override val deviceToken: Flow<String?> = credentialFlow { it.deviceToken }

    private fun credentialFlow(pick: (Credentials) -> String?): Flow<String?> = flow {
        ensureLoaded()
        emitAll(credentials.filterNotNull().map(pick))
    }.distinctUntilChanged()

    private suspend fun ensureLoaded() {
        if (credentials.value != null && !retryPending) return
        mutex.withLock { if (credentials.value == null || retryPending) loadLocked() }
    }

    /** Open the sealed slots and migrate any plaintext left by an older version. Caller holds [mutex]. */
    private suspend fun loadLocked() = withContext(ioDispatcher) {
        retryPending = false
        val prefs = dataStore.data.first()
        val sealed = credentialStore.data.first()

        if (prefs[clearedTombstoneKey] == true) {
            // A logout whose clear never reached the credential file: honour it.
            credentials.value = Credentials(null, null)
            val cleared = runCatching { removeSealedSlots() }.isSuccess
            runCatching {
                dataStore.edit {
                    it.remove(legacyCookieKey)
                    it.remove(legacyDeviceTokenKey)
                    if (cleared) it.remove(clearedTombstoneKey)
                }
            }
            return@withContext
        }

        val origin = originOf(prefs[baseUrlKey])
        var cookieSlot = openSlot(sealed[sealedCookieKey], SLOT_COOKIE, origin)
        var tokenSlot = openSlot(sealed[sealedDeviceTokenKey], SLOT_DEVICE_TOKEN, origin)

        // Rotation of a key that keeps failing for no stated reason, counted once
        // per launch and persisted across launches.
        val suspect = listOf(cookieSlot, tokenSlot).any { it is Slot.Unavailable && it.suspect }
        if (suspect && noteSuspectFailureLocked()) {
            // Rotated: everything sealed under the old key is dead.
            if (cookieSlot is Slot.Unavailable) cookieSlot = Slot.Dead
            if (tokenSlot is Slot.Unavailable) tokenSlot = Slot.Dead
        }
        val resetCounter = listOf(cookieSlot, tokenSlot).any { it is Slot.Opened } && (prefs[suspectFailuresKey] ?: 0) > 0

        // Pre-T1.4 plaintext: written in ONE edit together with its base URL, so
        // it belongs to [origin]. A sealed or temporarily unreadable value wins
        // (the plaintext is then a leftover of a crash mid-migration).
        val legacy = prefs.contains(legacyCookieKey) || prefs.contains(legacyDeviceTokenKey)
        val nothingSealed = listOf(cookieSlot, tokenSlot).none { it is Slot.Opened || it is Slot.Unavailable }
        if (legacy && nothingSealed && origin != null) {
            prefs[legacyCookieKey]?.takeIf { it.isNotEmpty() }?.let { cookieSlot = Slot.Opened(it, reseal = true) }
            prefs[legacyDeviceTokenKey]?.takeIf { it.isNotEmpty() }?.let { tokenSlot = Slot.Opened(it, reseal = true) }
        }

        // Bring the sealed file in line: dead blobs go, migrated values are sealed
        // for the origin (or held in memory only), unavailable ones stay as they are.
        val cookieWrite = rewriteFor(cookieSlot, SLOT_COOKIE, origin)
        val tokenWrite = rewriteFor(tokenSlot, SLOT_DEVICE_TOKEN, origin)
        val unavailable = cookieSlot is Slot.Unavailable || tokenSlot is Slot.Unavailable
        if (cookieWrite != null || tokenWrite != null) {
            runCatching {
                credentialStore.edit { p ->
                    cookieWrite?.let { w -> if (w.isEmpty()) p.remove(sealedCookieKey) else p[sealedCookieKey] = w }
                    tokenWrite?.let { w -> if (w.isEmpty()) p.remove(sealedDeviceTokenKey) else p[sealedDeviceTokenKey] = w }
                }
            }
        }
        // Only after the sealed copy is on disk: delete the plaintext.
        if (legacy || resetCounter) {
            runCatching {
                dataStore.edit {
                    if (legacy) {
                        it.remove(legacyCookieKey)
                        it.remove(legacyDeviceTokenKey)
                    }
                    if (resetCounter) it.remove(suspectFailuresKey)
                }
            }
        }
        retryPending = unavailable
        credentials.value = Credentials(
            (cookieSlot as? Slot.Opened)?.value,
            (tokenSlot as? Slot.Opened)?.value,
        )
    }

    /** null = leave the slot as it is; "" = delete it; otherwise the new sealed value. */
    private fun rewriteFor(slot: Slot, name: String, origin: String?): String? = when (slot) {
        Slot.Empty, is Slot.Unavailable -> null
        Slot.Dead -> ""
        is Slot.Opened -> if (!slot.reseal) null else origin?.let { sealSlot(slot.value, name, it) } ?: ""
    }

    private fun openSlot(encoded: String?, slot: String, origin: String?): Slot {
        if (encoded.isNullOrEmpty()) return Slot.Empty
        // A credential with no (parsable) server cannot be bound to one: dead.
        if (origin == null) return Slot.Dead
        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return Slot.Dead // not Base64: corrupt
        }
        // Only the origin-bound AAD. A blob sealed under any other AAD (another
        // origin, or the slot-only AAD of unreleased early T1.4 builds) does not
        // open and is deleted: re-sealing it for "whatever URL is on disk" would
        // accept exactly the torn URL-B-plus-credential-A state.
        return tryOpen(blob, aadFor(slot, origin))
    }

    private fun tryOpen(blob: ByteArray, aad: ByteArray): Slot = try {
        String(cipher.open(blob, aad), Charsets.UTF_8).takeIf { it.isNotEmpty() }
            ?.let { Slot.Opened(it, reseal = false) } ?: Slot.Dead
    } catch (e: CredentialCipherException) {
        when (e.failure) {
            CipherFailure.Transient -> Slot.Unavailable(suspect = false)
            CipherFailure.Suspect -> Slot.Unavailable(suspect = true)
            CipherFailure.KeyDead -> {
                cipher.destroyKey()
                Slot.Dead
            }
            CipherFailure.BadBlob -> Slot.Dead
        }
    }

    /** Sealed + Base64, or null when sealing is impossible (then: memory only). */
    private fun sealSlot(value: String, slot: String, origin: String): String? {
        val plaintext = value.toByteArray(Charsets.UTF_8)
        repeat(2) { attempt ->
            try {
                return Base64.getEncoder().encodeToString(cipher.seal(plaintext, aadFor(slot, origin)))
            } catch (e: CredentialCipherException) {
                lastSealFailure = e.failure
                // One retry under a fresh key when the old one is dead; a transient
                // error never destroys the key.
                if (!e.keyUnusable || attempt == 1) return null
                cipher.destroyKey()
            }
        }
        return null
    }

    private fun aadFor(slot: String, origin: String): ByteArray = "$AAD_PREFIX$slot|$origin".toByteArray(Charsets.UTF_8)

    private suspend fun removeSealedSlots() {
        credentialStore.edit {
            it.remove(sealedCookieKey)
            it.remove(sealedDeviceTokenKey)
        }
    }

    override suspend fun setServer(baseUrl: String, credential: Credential) {
        mutex.withLock {
            if (credentials.value == null || retryPending) loadLocked()
            retryPending = false
            withContext(ioDispatcher) {
                // (1) The old credential goes first — in memory and on disk — so
                //     no reader, and no crash, ever pairs it with the new URL. If
                //     this fails the URL is not touched (the caller keeps the new
                //     credential in memory only).
                credentials.value = Credentials(null, null)
                removeSealedSlots()
                // (2) Move the URL. The old credential is gone from disk, so a
                //     logout tombstone is obsolete too. A 0.6.0 pending slot is
                //     attributed to the OLD URL in this same edit, so it can
                //     never be read as the new server's.
                dataStore.edit {
                    migrateLegacyPending(it)
                    it[baseUrlKey] = baseUrl
                    it.remove(legacyCookieKey)
                    it.remove(legacyDeviceTokenKey)
                    it.remove(clearedTombstoneKey)
                }
                // (3) Seal for the NEW origin. Writing one credential clears the
                //     other: two live credentials would make "which one is in
                //     force" ambiguous on the next launch.
                val next = when (credential) {
                    is Credential.Cookie -> Credentials(credential.value, null)
                    is Credential.DeviceToken -> Credentials(null, credential.value)
                }
                val origin = originOf(baseUrl)
                fun sealNext(): Pair<String?, String?> = Pair(
                    origin?.let { o -> next.cookie?.let { sealSlot(it, SLOT_COOKIE, o) } },
                    origin?.let { o -> next.deviceToken?.let { sealSlot(it, SLOT_DEVICE_TOKEN, o) } },
                )
                lastSealFailure = null
                var (cookieBlob, tokenBlob) = sealNext()
                val sealed = cookieBlob != null || tokenBlob != null
                // A key that works resets the rotation counter; one that fails for
                // no stated reason counts (once per launch) and may be rotated,
                // after which the seal is retried under the fresh key.
                if (!sealed && lastSealFailure == CipherFailure.Suspect && noteSuspectFailureLocked()) {
                    val retried = sealNext()
                    cookieBlob = retried.first
                    tokenBlob = retried.second
                }
                credentials.value = next
                if (cookieBlob != null || tokenBlob != null) {
                    credentialStore.edit { p ->
                        cookieBlob?.let { p[sealedCookieKey] = it }
                        tokenBlob?.let { p[sealedDeviceTokenKey] = it }
                    }
                    runCatching { dataStore.edit { it.remove(suspectFailuresKey) } }
                }
            }
        }
    }

    override suspend fun clearCredential() {
        mutex.withLock { forgetCredentialsLocked(extra = {}) }
    }

    override suspend fun clear() {
        mutex.withLock {
            forgetCredentialsLocked { prefs ->
                prefs.remove(baseUrlKey)
                // Every server's unsent input, and any 0.6.0 leftover.
                prefs.asMap().keys.filter { PendingSlots.isPendingKey(it.name) }.forEach { prefs.remove(it) }
            }
        }
    }

    /**
     * Forget in memory at once; delete the sealed blobs (one retry); if the
     * credential file cannot be written, leave a tombstone in [dataStore] so the
     * next load reads "logged out" anyway. Throws only if neither file can be written.
     */
    private suspend fun forgetCredentialsLocked(extra: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        credentials.value = Credentials(null, null)
        retryPending = false
        withContext(ioDispatcher) {
            val removed = runCatching { removeSealedSlots() }.isSuccess || runCatching { removeSealedSlots() }.isSuccess
            dataStore.edit {
                it.remove(legacyCookieKey)
                it.remove(legacyDeviceTokenKey)
                if (removed) it.remove(clearedTombstoneKey) else it[clearedTombstoneKey] = true
                extra(it)
            }
        }
    }

    /**
     * URL and credential from ONE snapshot, under the same lock every writer
     * holds — a server switch can never be observed half-way (URL A with
     * credential B). Use this, not [baseUrl] + [credential], before sending a
     * credential anywhere.
     */
    override suspend fun session(): Session = mutex.withLock {
        if (credentials.value == null || retryPending) loadLocked()
        val c = credentials.value ?: Credentials(null, null)
        Session(dataStore.data.first()[baseUrlKey], credentialInForce(c.cookie, c.deviceToken))
    }

    override suspend fun readPendingInput(origin: String): String? {
        val key = stringPreferencesKey(PendingSlots.keyFor(origin))
        ensurePendingMigrated()
        return dataStore.data.first()[key]
    }

    override suspend fun writePendingInput(origin: String, raw: String) {
        val key = stringPreferencesKey(PendingSlots.keyFor(origin))
        ensurePendingMigrated()
        dataStore.edit { it[key] = raw }
    }

    override suspend fun readUnattributedPendingInput(): String? {
        ensurePendingMigrated()
        return dataStore.data.first()[stringPreferencesKey(PendingSlots.UNATTRIBUTED_KEY)]
    }

    /**
     * Attribute the 0.6.0 single slot once per process, in ONE edit that reads
     * the `base_url` stored next to it (see [PendingSlots]). Under [mutex], so
     * it cannot interleave with a [setServer] moving the URL. A failure leaves
     * the flag unset and the next access retries; [setServer] migrates in its
     * own URL edit regardless.
     */
    private suspend fun ensurePendingMigrated() {
        if (pendingMigrated) return
        mutex.withLock {
            if (pendingMigrated) return
            withContext(ioDispatcher) {
                // Nothing to attribute (every install but a 0.6.0 upgrade): no edit at all.
                if (dataStore.data.first().contains(stringPreferencesKey(PendingSlots.LEGACY_KEY))) {
                    dataStore.edit { migrateLegacyPending(it) }
                }
            }
            pendingMigrated = true
        }
    }

    private fun migrateLegacyPending(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        PendingSlots.migrateLegacy(
            baseUrl = prefs[baseUrlKey],
            get = { prefs[stringPreferencesKey(it)] },
            set = { k, v -> prefs[stringPreferencesKey(k)] = v },
            remove = { prefs.remove(stringPreferencesKey(it)) },
        )
    }

    companion object {
        const val SETTINGS_FILE = "tether_settings.preferences_pb"

        /** Directory (under filesDir) holding only sealed credentials; excluded from backup/transfer. */
        const val CREDENTIALS_DIR = "credentials"
        const val CREDENTIALS_FILE = "tether_credentials.preferences_pb"

        const val SLOT_COOKIE = "session_cookie"
        const val SLOT_DEVICE_TOKEN = "device_token"
        private const val AAD_PREFIX = "tether.credential.v2|"

        /** Launches in a row with an unexplained key failure before the key is rotated. */
        const val SUSPECT_ROTATE_AFTER = 3

        /**
         * `scheme://host:port` as OkHttp canonicalises it (lower-case host, IDN
         * in punycode, default port explicit, `_` allowed — `my_nas.lan` works)
         * — the identity a credential is bound to. Null for anything that is not
         * an http(s) URL.
         */
        fun originOf(baseUrl: String?): String? = serverOrigin(baseUrl)

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

/**
 * In-memory store for tests and previews. [initialLegacyPendingInput] seeds a
 * 0.6.0 single-slot payload, attributed on first access exactly as
 * [DataStoreSettings] does ([PendingSlots]).
 */
class InMemorySettings(
    initialBaseUrl: String? = null,
    initialCookie: String? = null,
    initialDeviceToken: String? = null,
    initialLegacyPendingInput: String? = null,
) : SettingsStore {
    private val baseUrlState = MutableStateFlow(initialBaseUrl)
    private val cookieState = MutableStateFlow(initialCookie)
    private val deviceTokenState = MutableStateFlow(initialDeviceToken)

    // Pending slots by key (PendingSlots naming), guarded by [lock].
    private val pending = HashMap<String, String>().apply {
        if (initialLegacyPendingInput != null) put(PendingSlots.LEGACY_KEY, initialLegacyPendingInput)
    }

    /** Caller holds [lock]. */
    private fun migrateLocked() = PendingSlots.migrateLegacy(
        baseUrl = baseUrlState.value,
        get = { pending[it] },
        set = { k, v -> pending[k] = v },
        remove = { pending.remove(it) },
    )

    // Every write and [session] take this, so a (URL, credential) pair is never torn.
    private val lock = Any()

    override val baseUrl: Flow<String?> = baseUrlState
    override val cookie: Flow<String?> = cookieState
    override val deviceToken: Flow<String?> = deviceTokenState

    override suspend fun session(): Session = synchronized(lock) {
        Session(baseUrlState.value, credentialInForce(cookieState.value, deviceTokenState.value))
    }

    override suspend fun setServer(baseUrl: String, credential: Credential) {
        synchronized(lock) {
            // Same order as DataStoreSettings: the old credential goes before the URL moves.
            cookieState.value = null
            deviceTokenState.value = null
            migrateLocked()
            baseUrlState.value = baseUrl
            when (credential) {
                is Credential.Cookie -> cookieState.value = credential.value
                is Credential.DeviceToken -> deviceTokenState.value = credential.value
            }
        }
    }

    override suspend fun clearCredential() {
        synchronized(lock) {
            cookieState.value = null
            deviceTokenState.value = null
        }
    }

    override suspend fun clear() {
        synchronized(lock) {
            baseUrlState.value = null
            cookieState.value = null
            deviceTokenState.value = null
            pending.clear()
        }
    }

    override suspend fun readPendingInput(origin: String): String? {
        val key = PendingSlots.keyFor(origin)
        return synchronized(lock) {
            migrateLocked()
            pending[key]
        }
    }

    override suspend fun writePendingInput(origin: String, raw: String) {
        val key = PendingSlots.keyFor(origin)
        synchronized(lock) {
            migrateLocked()
            pending[key] = raw
        }
    }

    override suspend fun readUnattributedPendingInput(): String? = synchronized(lock) {
        migrateLocked()
        pending[PendingSlots.UNATTRIBUTED_KEY]
    }
}
