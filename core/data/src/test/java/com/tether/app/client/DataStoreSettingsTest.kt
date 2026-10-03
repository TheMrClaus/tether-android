package com.tether.app.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The encrypted credential store end to end on real DataStore files: migration
 * from the pre-T1.4 plaintext layout, the fail-closed policy, precedence.
 * "Restart" = cancel the store's scope and open a new store on the same files.
 */
class DataStoreSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cookie = "s3ss10n-id.c00k1e-secret-value"
    private val token = "tthr_d3v1ce-t0ken-secret-0123456789abcdef"
    private val keys = SoftwareKeySource()

    private val settingsFile get() = File(tmp.root, DataStoreSettings.SETTINGS_FILE)
    private val credentialsFile get() = File(File(tmp.root, DataStoreSettings.CREDENTIALS_DIR), DataStoreSettings.CREDENTIALS_FILE)

    private class Opened(val store: DataStoreSettings, val job: Job)

    private fun open(cipher: CredentialCipher = AesGcmCredentialCipher(keys)): Opened {
        val job = Job()
        val store = DataStoreSettings.create(tmp.root, CoroutineScope(Dispatchers.IO + job), cipher)
        return Opened(store, job)
    }

    private suspend fun Opened.close() = job.cancelAndJoin()

    /** Read or write a raw preferences file (the store must be closed). */
    private suspend fun <T> raw(file: File, block: suspend (androidx.datastore.core.DataStore<Preferences>) -> T): T {
        val job = Job()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return try {
            block(ds)
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun writeLegacy(cookieValue: String? = null, tokenValue: String? = null) = raw(settingsFile) { ds ->
        ds.edit {
            it[stringPreferencesKey("base_url")] = "https://tether.example.com"
            if (cookieValue != null) it[stringPreferencesKey("session_cookie")] = cookieValue
            if (tokenValue != null) it[stringPreferencesKey("device_token")] = tokenValue
            it[stringPreferencesKey("pending_input")] = "{\"records\":[]}"
        }
    }

    private fun bytesOf(file: File): String = if (file.exists()) String(file.readBytes(), Charsets.ISO_8859_1) else ""

    private fun assertNoPlaintextOnDisk() {
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            val content = bytesOf(f)
            assertFalse("cookie in plaintext in ${f.name}", content.contains(cookie))
            assertFalse("token in plaintext in ${f.name}", content.contains(token))
        }
    }

    @Test
    fun migratesPlaintextCookieThenDeletesThePlaintext() = runBlocking {
        writeLegacy(cookieValue = cookie)
        assertTrue(bytesOf(settingsFile).contains(cookie)) // the pre-T1.4 layout really is plaintext

        val first = open()
        assertEquals(Credential.Cookie(cookie), first.store.credential.first())
        assertEquals("https://tether.example.com", first.store.baseUrl.first())
        assertEquals("{\"records\":[]}", first.store.readPendingInput(LEGACY_ORIGIN))
        first.close()

        val legacy = raw(settingsFile) { it.data.first() }
        assertNull(legacy[stringPreferencesKey("session_cookie")])
        assertNull(legacy[stringPreferencesKey("device_token")])
        assertEquals("https://tether.example.com", legacy[stringPreferencesKey("base_url")])
        val sealed = raw(credentialsFile) { it.data.first() }
        assertNotNull(sealed[stringPreferencesKey("session_cookie")])
        assertNoPlaintextOnDisk()

        // And it survives a restart, now from the sealed copy.
        val second = open()
        assertEquals(Credential.Cookie(cookie), second.store.credential.first())
        second.close()
    }

    @Test
    fun migratesPlaintextDeviceToken() = runBlocking {
        writeLegacy(tokenValue = token)
        val first = open()
        assertEquals(Credential.DeviceToken(token), first.store.credential.first())
        first.close()
        assertNoPlaintextOnDisk()
        val second = open()
        assertEquals(Credential.DeviceToken(token), second.store.credential.first())
        second.close()
    }

    @Test
    fun precedenceCookieOverTokenIsUnchangedByMigration() = runBlocking {
        writeLegacy(cookieValue = cookie, tokenValue = token)
        val first = open()
        assertEquals(Credential.Cookie(cookie), first.store.credential.first())
        assertEquals(token, first.store.deviceToken.first())
        first.close()
        assertNoPlaintextOnDisk()
        val second = open()
        assertEquals(Credential.Cookie(cookie), second.store.credential.first())
        second.close()
    }

    @Test
    fun migrationDeletesPlaintextEvenWhenSealingFails() = runBlocking {
        writeLegacy(cookieValue = cookie)
        val first = open(FailingCipher())
        // Usable for this process only...
        assertEquals(Credential.Cookie(cookie), first.store.credential.first())
        first.close()
        // ...never left in plaintext, and gone after a restart (logged out).
        assertNoPlaintextOnDisk()
        val second = open()
        assertNull(second.store.credential.first())
        assertEquals("https://tether.example.com", second.store.baseUrl.first())
        second.close()
    }

    @Test
    fun corruptBlobReadsAsLoggedOutAndIsDeleted() = runBlocking {
        val first = open()
        first.store.setServer("https://a.example", Credential.DeviceToken(token))
        first.close()
        raw(credentialsFile) { ds ->
            ds.edit { prefs ->
                val blob = java.util.Base64.getDecoder().decode(prefs[stringPreferencesKey("device_token")]!!)
                blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x55).toByte()
                prefs[stringPreferencesKey("device_token")] = java.util.Base64.getEncoder().encodeToString(blob)
            }
        }
        val second = open()
        assertNull(second.store.credential.first())
        // The server URL survives: the login screen can prefill it.
        assertEquals("https://a.example", second.store.baseUrl.first())
        second.close()
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])
    }

    @Test
    fun notBase64ReadsAsLoggedOut() = runBlocking {
        raw(credentialsFile) { ds -> ds.edit { it[stringPreferencesKey("session_cookie")] = "%%% not base64 %%%" } }
        val store = open()
        assertNull(store.store.credential.first())
        store.close()
    }

    @Test
    fun unreadableCredentialFileReadsAsLoggedOut() = runBlocking {
        credentialsFile.parentFile!!.mkdirs()
        credentialsFile.writeBytes(byteArrayOf(0x7f, 0x13, 0x00, 0x42, 0x66))
        val store = open()
        assertNull(store.store.credential.first())
        store.store.setServer("https://a.example", Credential.Cookie(cookie))
        assertEquals(Credential.Cookie(cookie), store.store.credential.first())
        store.close()
    }

    @Test
    fun invalidatedKeyReadsAsLoggedOutAndTheNextLoginUsesAFreshKey() = runBlocking {
        val first = open()
        first.store.setServer("https://a.example", Credential.Cookie(cookie))
        first.close()
        keys.destroyKey() // e.g. Keystore reset / key permanently invalidated

        val second = open()
        assertNull(second.store.credential.first())
        second.store.setServer("https://a.example", Credential.DeviceToken(token))
        second.close()
        assertEquals(2, keys.created)

        val third = open()
        assertEquals(Credential.DeviceToken(token), third.store.credential.first())
        third.close()
        assertNoPlaintextOnDisk()
    }

    @Test
    fun sealFailureKeepsTheCredentialInMemoryOnly() = runBlocking {
        val first = open(FailingCipher())
        first.store.setServer("https://a.example", Credential.Cookie(cookie))
        assertEquals(Credential.Cookie(cookie), first.store.credential.first())
        first.close()
        assertNoPlaintextOnDisk()
        val second = open()
        assertNull(second.store.credential.first())
        second.close()
    }

    @Test
    fun writingOneCredentialClearsTheOther() = runBlocking {
        val first = open()
        first.store.setServer("https://a.example", Credential.Cookie(cookie))
        first.store.setServer("https://a.example", Credential.DeviceToken(token))
        assertEquals(Credential.DeviceToken(token), first.store.credential.first())
        assertNull(first.store.cookie.first())
        first.close()
        val second = open()
        assertEquals(Credential.DeviceToken(token), second.store.credential.first())
        assertNull(second.store.cookie.first())
        second.close()
    }

    @Test
    fun clearCredentialKeepsTheServerUrlAndClearDropsEverything() = runBlocking {
        val first = open()
        first.store.setServer("https://a.example", Credential.Cookie(cookie))
        first.store.writePendingInput(ORIGIN_A, "{}")
        first.store.clearCredential()
        assertNull(first.store.credential.first())
        assertEquals("https://a.example", first.store.baseUrl.first())
        first.close()
        val second = open()
        assertNull(second.store.credential.first())
        assertEquals("https://a.example", second.store.baseUrl.first())
        second.store.clear()
        assertNull(second.store.baseUrl.first())
        assertNull(second.store.readPendingInput(ORIGIN_A))
        second.close()
    }

    @Test
    fun clearAlsoRemovesAnUnmigratedPlaintextCredential() = runBlocking {
        writeLegacy(cookieValue = cookie)
        val store = open(FailingCipher())
        store.store.clearCredential()
        store.close()
        assertNoPlaintextOnDisk()
    }

    // ------------------------------------------------------------------
    // T1.4 review: never pair one server's credential with another's URL
    // ------------------------------------------------------------------

    /** A DataStore whose next writes can be made to fail (a crash / full disk mid-sequence). */
    private class FlakyDataStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        /** Successful writes still allowed before every further write fails; -1 = never fail. */
        @Volatile var allowWrites = -1
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (allowWrites == 0) throw java.io.IOException("simulated crash before this write")
            if (allowWrites > 0) allowWrites--
            return inner.updateData(transform)
        }
    }

    private class OpenedFlaky(val store: DataStoreSettings, val settings: FlakyDataStore, val creds: FlakyDataStore, val job: Job)

    private fun openFlaky(cipher: CredentialCipher = AesGcmCredentialCipher(keys)): OpenedFlaky {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        credentialsFile.parentFile!!.mkdirs()
        val settings = FlakyDataStore(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })
        val creds = FlakyDataStore(PreferenceDataStoreFactory.create(scope = scope) { credentialsFile })
        return OpenedFlaky(DataStoreSettings(settings, creds, cipher), settings, creds, job)
    }

    private val serverA = "https://a.example"
    private val serverB = "https://b.example:8443"

    @Test
    fun crashBeforeTheUrlMovesLeavesTheOldServerWithNoCredential() = runBlocking {
        val first = openFlaky()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.settings.allowWrites = 0 // the URL write for B never happens
        runCatching { first.store.setServer(serverB, Credential.DeviceToken(token)) }
        first.job.cancelAndJoin()

        val second = open()
        assertEquals(serverA, second.store.baseUrl.first())
        // A's cookie was deleted BEFORE the URL was to move; B's token never landed.
        assertNull(second.store.credential.first())
        second.close()
    }

    @Test
    fun crashBetweenUrlAndCredentialWritesLeavesTheNewServerWithNoCredential() = runBlocking {
        val first = openFlaky()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.creds.allowWrites = 1 // step 1 (delete A's blob) lands, step 3 (B's blob) does not
        runCatching { first.store.setServer(serverB, Credential.DeviceToken(token)) }
        first.job.cancelAndJoin()

        val second = open()
        assertEquals(serverB, second.store.baseUrl.first())
        assertNull(second.store.credential.first()) // never A's cookie against B
        second.close()
    }

    @Test
    fun aBlobNeverOpensAgainstAnotherOrigin() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.close()
        // The torn write the old ordering allowed: URL moved, A's blob stayed.
        raw(settingsFile) { ds -> ds.edit { it[stringPreferencesKey("base_url")] = serverB } }

        val second = open()
        assertNull(second.store.credential.first())
        second.close()
        // ...and the blob is gone, not just unread.
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("session_cookie")])
        // Same origin spelled differently still opens (scheme/host case, default port).
        val third = open()
        third.store.setServer("https://A.example", Credential.Cookie(cookie))
        third.close()
        raw(settingsFile) { ds -> ds.edit { it[stringPreferencesKey("base_url")] = "https://a.example:443" } }
        val fourth = open()
        assertEquals(Credential.Cookie(cookie), fourth.store.credential.first())
        fourth.close()
    }

    @Test
    fun slotOnlyAadBlobsAreDeadNotUpgraded() = runBlocking {
        // An unreleased early-T1.4 blob (AAD = slot only). Re-sealing it for the
        // URL on disk would accept the torn URL-B + credential-A state, so it is
        // deleted and reads as logged out.
        val cipher = AesGcmCredentialCipher(keys)
        val v1 = java.util.Base64.getEncoder().encodeToString(
            cipher.seal(token.toByteArray(), "tether.credential.v1|device_token".toByteArray()),
        )
        raw(settingsFile) { ds -> ds.edit { it[stringPreferencesKey("base_url")] = serverA } }
        raw(credentialsFile) { ds -> ds.edit { it[stringPreferencesKey("device_token")] = v1 } }

        val first = open()
        assertNull(first.store.credential.first())
        first.close()
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])
        assertEquals(0, keys.destroyed) // a bad blob is not a bad key
    }

    @Test
    fun hostsWithUnderscoresKeepTheirCredential() = runBlocking {
        val first = open()
        first.store.setServer("http://my_nas.lan:4173", Credential.Cookie(cookie))
        first.close()
        val second = open()
        assertEquals(Credential.Cookie(cookie), second.store.credential.first())
        second.close()
    }

    // ------------------------------------------------------------------
    // One atomic (URL, credential) read
    // ------------------------------------------------------------------

    /** Suspends the next write until [release] — a server switch frozen half-way. */
    private class GatedDataStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var armed = false
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (armed) {
                armed = false
                reached.complete(Unit)
                release.await()
            }
            return inner.updateData(transform)
        }
    }

    @Test
    fun sessionNeverObservesAServerSwitchHalfWay() = kotlinx.coroutines.test.runTest {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        credentialsFile.parentFile!!.mkdirs()
        val gated = GatedDataStore(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })
        val creds = PreferenceDataStoreFactory.create(scope = scope) { credentialsFile }
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val store = DataStoreSettings(gated, creds, AesGcmCredentialCipher(keys), ioDispatcher = dispatcher)
        store.setServer(serverA, Credential.Cookie(cookie))
        assertEquals(Session(serverA, Credential.Cookie(cookie)), store.session())

        gated.armed = true // freeze the switch at "move the URL"
        val switching = async(dispatcher) { store.setServer(serverB, Credential.DeviceToken(token)) }
        gated.reached.await()
        val reader = async(dispatcher) { store.session() }
        testScheduler.runCurrent()
        // The reader cannot see the frozen, half-switched state: it waits.
        assertFalse(reader.isCompleted)
        gated.release.complete(Unit)
        switching.await()
        assertEquals(Session(serverB, Credential.DeviceToken(token)), reader.await())
        job.cancelAndJoin()
    }

    // ------------------------------------------------------------------
    // Unexplained key failures rotate after N launches
    // ------------------------------------------------------------------

    @Test
    fun aKeyThatKeepsFailingForNoReasonIsRotatedAfterThreeLaunches() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.DeviceToken(token))
        first.close()
        keys.failure = java.security.InvalidKeyException("keystore says no, every time")
        repeat(DataStoreSettings.SUSPECT_ROTATE_AFTER - 1) {
            val launch = open()
            assertNull(launch.store.credential.first())
            launch.close()
            assertEquals(0, keys.destroyed) // not yet: could still be transient
            assertNotNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])
        }
        val last = open()
        assertNull(last.store.credential.first())
        last.close()
        assertEquals(1, keys.destroyed)
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])
        assertNull(raw(settingsFile) { it.data.first() }[androidx.datastore.preferences.core.intPreferencesKey("credential_key_suspect_failures")])
        // The fresh key works again.
        keys.failure = null
        val again = open()
        again.store.setServer(serverA, Credential.Cookie(cookie))
        again.close()
        val check = open()
        assertEquals(Credential.Cookie(cookie), check.store.credential.first())
        check.close()
    }

    @Test
    fun oneSuccessfulReadResetsTheSuspectCount() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.DeviceToken(token))
        first.close()
        repeat(2) {
            keys.failure = java.security.InvalidKeyException("flaky")
            val bad = open()
            assertNull(bad.store.credential.first())
            bad.close()
            keys.failure = null
            val good = open()
            assertEquals(Credential.DeviceToken(token), good.store.credential.first())
            good.close()
        }
        assertEquals(0, keys.destroyed) // never three in a row
        assertNull(raw(settingsFile) { it.data.first() }[androidx.datastore.preferences.core.intPreferencesKey("credential_key_suspect_failures")])
    }

    @Test
    fun anUnrecoverableKeyCausedByATransientKeystoreErrorIsNotDestroyed() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.close()
        keys.failure = java.security.UnrecoverableKeyException("wrapped").apply {
            initCause(java.security.KeyStoreException("system busy"))
        }
        val second = open()
        assertNull(second.store.credential.first())
        second.close()
        assertEquals(0, keys.destroyed)
        keys.failure = null
        val third = open()
        assertEquals(Credential.Cookie(cookie), third.store.credential.first())
        third.close()
    }

    @Test
    fun noCredentialWithoutAParsableServer() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.close()
        raw(settingsFile) { ds -> ds.edit { it.remove(stringPreferencesKey("base_url")) } }
        val second = open()
        assertNull(second.store.credential.first())
        second.close()
    }

    // ------------------------------------------------------------------
    // Logout that cannot reach the credential file: tombstone
    // ------------------------------------------------------------------

    @Test
    fun aClearThatCannotDeleteTheBlobLeavesATombstone() = runBlocking {
        val first = openFlaky()
        first.store.setServer(serverA, Credential.DeviceToken(token))
        first.creds.allowWrites = 0 // both delete attempts fail
        first.store.clearCredential()
        assertNull(first.store.credential.first())
        first.job.cancelAndJoin()
        // The sealed blob really survived on disk...
        assertNotNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])

        // ...but the next launch reads logged out and finishes the delete.
        val second = open()
        assertNull(second.store.credential.first())
        assertEquals(serverA, second.store.baseUrl.first())
        second.close()
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])
        assertNull(raw(settingsFile) { it.data.first() }[androidx.datastore.preferences.core.booleanPreferencesKey("credentials_cleared")])

        // And a later login is not shadowed by the old tombstone.
        val third = open()
        third.store.setServer(serverA, Credential.Cookie(cookie))
        third.close()
        val fourth = open()
        assertEquals(Credential.Cookie(cookie), fourth.store.credential.first())
        fourth.close()
    }

    @Test
    fun aClearRetriesOnceBeforeFallingBackToTheTombstone() = runBlocking {
        val first = openFlaky()
        first.store.setServer(serverA, Credential.DeviceToken(token))
        // Same files, through a credential store whose FIRST write fails.
        val once = OnceFailing(first.creds)
        val store = DataStoreSettings(first.settings, once, AesGcmCredentialCipher(keys))
        assertEquals(Credential.DeviceToken(token), store.credential.first())
        store.clearCredential()
        assertEquals(2, once.calls) // failed once, retried once
        first.job.cancelAndJoin()
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("device_token")])
        // The retry worked, so no tombstone was needed.
        assertNull(raw(settingsFile) { it.data.first() }[androidx.datastore.preferences.core.booleanPreferencesKey("credentials_cleared")])
    }

    private class OnceFailing(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var calls = 0
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            calls++
            if (calls == 1) throw java.io.IOException("first attempt fails")
            return inner.updateData(transform)
        }
    }

    // ------------------------------------------------------------------
    // Transient Keystore errors: no destroy, retry on the next read
    // ------------------------------------------------------------------

    @Test
    fun aTransientKeystoreErrorKeepsKeyAndBlobAndRetriesOnTheNextRead() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.DeviceToken(token))
        first.close()

        keys.failure = java.security.KeyStoreException("keystore busy")
        val second = open()
        assertNull(second.store.credential.first()) // unavailable this time
        assertEquals(0, keys.destroyed)
        assertNotNull(keys.key)
        keys.failure = null
        // The next read (e.g. the next start()) retries and gets it back.
        assertEquals(Credential.DeviceToken(token), second.store.credential.first())
        second.close()
        assertEquals(1, keys.created)
    }

    @Test
    fun aTransientErrorAtLaunchLeavesTheBlobOnDisk() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.close()
        keys.failure = java.security.ProviderException("daemon restarting")
        val second = open()
        assertNull(second.store.credential.first())
        second.close()
        keys.failure = null
        val third = open()
        assertEquals(Credential.Cookie(cookie), third.store.credential.first())
        third.close()
    }

    /**
     * ta-jt9 L-A1: [SettingsStore.storedCredentialState] keeps "cannot read it now" apart from
     * "nothing stored": the boot purge deletes data only on Absent.
     */
    @Test
    fun aTransientKeystoreErrorIsUnknownNotAbsentAndAReadThatWorksIsPresentAgain() = runBlocking {
        val first = open()
        assertEquals(StoredCredentialState.Absent, first.store.storedCredentialState())
        first.store.setServer(serverA, Credential.DeviceToken(token))
        assertEquals(StoredCredentialState.Present, first.store.storedCredentialState())
        first.close()

        keys.failure = java.security.KeyStoreException("keystore busy")
        val second = open()
        assertNull("reads as no credential", second.store.credential.first())
        assertEquals(StoredCredentialState.Unknown, second.store.storedCredentialState())
        keys.failure = null
        assertEquals(StoredCredentialState.Present, second.store.storedCredentialState())
        assertEquals(Credential.DeviceToken(token), second.store.credential.first())
        second.store.clearCredential()
        assertEquals(StoredCredentialState.Absent, second.store.storedCredentialState())
        second.close()
    }

    /** A dead key is not a transient error: the blob is deleted and nothing is stored. */
    @Test
    fun anUnrecoverableKeyIsAbsent() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.close()
        keys.failure = java.security.UnrecoverableKeyException("gone")
        val second = open()
        assertEquals(StoredCredentialState.Absent, second.store.storedCredentialState())
        second.close()
    }

    /** ta-jt9 L-X: compare-and-clear. Only the expected credential goes; any other one stays. */
    @Test
    fun clearCredentialIfClearsOnlyTheExpectedCredential() = runBlocking {
        val opened = open()
        opened.store.setServer(serverA, Credential.Cookie(cookie))
        assertFalse(opened.store.clearCredentialIf(Credential.Cookie("another-cookie")))
        assertFalse(opened.store.clearCredentialIf(Credential.DeviceToken(cookie)))
        assertEquals(Credential.Cookie(cookie), opened.store.credential.first())
        assertTrue(opened.store.clearCredentialIf(Credential.Cookie(cookie)))
        assertNull(opened.store.credential.first())
        assertEquals(serverA, opened.store.baseUrl.first())
        assertFalse("nothing left to clear", opened.store.clearCredentialIf(Credential.Cookie(cookie)))
        opened.close()
        val reopened = open()
        assertNull("the clear reached disk", reopened.store.credential.first())
        reopened.close()
    }

    /**
     * ta-coik.1 r4 (ta-5csf I2): taking a sign-in back drops its credential AND puts the URL back to the
     * one stored before (or none), in one step; for any other credential it does nothing at all.
     */
    @Test
    fun revertServerIfRestoresThePreviousUrlOnlyForTheExpectedCredential() = runBlocking {
        val opened = open()
        opened.store.setServer(serverA, Credential.Cookie(cookie))
        assertFalse(opened.store.revertServerIf(Credential.Cookie("another-cookie"), "https://previous.example.com"))
        assertEquals(serverA, opened.store.baseUrl.first())
        assertEquals(Credential.Cookie(cookie), opened.store.credential.first())
        assertTrue(opened.store.revertServerIf(Credential.Cookie(cookie), "https://previous.example.com"))
        assertNull(opened.store.credential.first())
        assertEquals("https://previous.example.com", opened.store.baseUrl.first())
        opened.close()
        val reopened = open()
        assertNull("the take-back reached disk", reopened.store.credential.first())
        assertEquals("https://previous.example.com", reopened.store.baseUrl.first())
        // None stored before: none after.
        reopened.store.setServer(serverA, Credential.DeviceToken("tthr_token"))
        assertTrue(reopened.store.revertServerIf(Credential.DeviceToken("tthr_token"), null))
        assertNull(reopened.store.baseUrl.first())
        assertNull(reopened.store.credential.first())
        reopened.close()
    }

    /**
     * ta-jt9 L-X: the check and the clear are ONE step under the store's lock. A setServer that
     * is queued behind the compare-and-clear lands after it, and one queued before it makes it
     * a no-op; never "checked old, then cleared new".
     */
    @Test
    fun clearCredentialIfAndASetServerNeverInterleave() = runBlocking {
        repeat(20) { round ->
            val opened = open()
            opened.store.setServer(serverA, Credential.Cookie("old-$round"))
            val clear = async(Dispatchers.IO) { opened.store.clearCredentialIf(Credential.Cookie("old-$round")) }
            val login = async(Dispatchers.IO) { opened.store.setServer(serverA, Credential.Cookie("new-$round")) }
            val cleared = clear.await()
            login.await()
            assertEquals("round $round (cleared=$cleared)", Credential.Cookie("new-$round"), opened.store.credential.first())
            opened.store.clear()
            opened.close()
        }
    }

    @Test
    fun theInMemoryStoreHasTheSameTwoRules() = runBlocking {
        val store = InMemorySettings(initialBaseUrl = serverA, initialCookie = cookie)
        assertEquals(StoredCredentialState.Present, store.storedCredentialState())
        assertFalse(store.clearCredentialIf(Credential.Cookie("another-cookie")))
        assertEquals(Credential.Cookie(cookie), store.credential.first())
        assertTrue(store.clearCredentialIf(Credential.Cookie(cookie)))
        assertEquals(StoredCredentialState.Absent, store.storedCredentialState())
        assertEquals(serverA, store.baseUrl.first())
    }

    @Test
    fun anUnrecoverableKeyIsDestroyedAndReadsAsLoggedOut() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(cookie))
        first.close()
        keys.failure = java.security.UnrecoverableKeyException("gone")
        val second = open()
        assertNull(second.store.credential.first())
        second.close()
        assertEquals(1, keys.destroyed)
        keys.failure = null
        assertNull(raw(credentialsFile) { it.data.first() }[stringPreferencesKey("session_cookie")])
    }

    @Test
    fun originNormalization() {
        assertEquals("https://a.example:443", DataStoreSettings.originOf("https://A.Example/"))
        assertEquals("http://10.0.2.2:4290", DataStoreSettings.originOf("http://10.0.2.2:4290"))
        assertEquals("http://h:80", DataStoreSettings.originOf("http://h"))
        assertEquals("http://my_nas.lan:4173", DataStoreSettings.originOf("http://my_nas.lan:4173"))
        assertNull(DataStoreSettings.originOf("ftp://h"))
        assertNull(DataStoreSettings.originOf("not a url"))
        assertNull(DataStoreSettings.originOf(null))
    }

    /**
     * T1.3: the pending store is one value replaced by one DataStore edit (scratch
     * file + rename). A process killed mid-write leaves a half-written scratch file
     * and the previous COMMITTED payload — never a torn mix of the two.
     */
    @Test
    fun pendingInputSurvivesAProcessKilledMidWriteWhole() = runBlocking {
        val committed = """{"v":2,"records":[{"key":"k1","kind":"send","sessionId":"s1","text":"a","sentAt":0,"tries":1,"firstQueuedAt":1}],"cleared":[]}"""
        val next = """{"v":2,"records":[],"cleared":["k1"]}"""
        val first = open()
        first.store.setServer("https://a.example", Credential.Cookie(cookie))
        first.store.writePendingInput(ORIGIN_A, committed)
        first.close()

        // The killed write: the scratch file holds a prefix of the next payload.
        File(tmp.root, DataStoreSettings.SETTINGS_FILE + ".tmp").writeBytes(next.toByteArray().copyOf(next.length / 2))
        val second = open()
        assertEquals(committed, second.store.readPendingInput(ORIGIN_A))
        assertEquals(Credential.Cookie(cookie), second.store.credential.first())
        // ...and the next complete write replaces it whole.
        second.store.writePendingInput(ORIGIN_A, next)
        second.close()
        val third = open()
        assertEquals(next, third.store.readPendingInput(ORIGIN_A))
        third.close()
    }

    /**
     * The settings DataStore with every committed edit recorded, and an optional
     * process death at the [dieAt]-th commit (it never lands).
     */
    private class CommitLog(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        val commits = java.util.concurrent.CopyOnWriteArrayList<Preferences>()
        @Volatile var dieAt = -1
        private var count = 0
        override val data = inner.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (++count == dieAt) throw java.io.IOException("process killed mid-change")
            return inner.updateData(transform).also { commits += it }
        }
    }

    private class Logged(val store: DataStoreSettings, val log: CommitLog, val job: Job)

    private fun openLogged(): Logged {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val log = CommitLog(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })
        val credentials = PreferenceDataStoreFactory.create(scope = scope) {
            File(File(tmp.root, DataStoreSettings.CREDENTIALS_DIR).apply { mkdirs() }, DataStoreSettings.CREDENTIALS_FILE)
        }
        return Logged(DataStoreSettings(log, credentials, AesGcmCredentialCipher(keys)), log, job)
    }

    private val pendingKey = stringPreferencesKey("pending_input|$ORIGIN_A")
    private val payloadA = """{"v":2,"records":[{"key":"k1","kind":"send","sessionId":"s1","text":"a","sentAt":0,"tries":1,"firstQueuedAt":1}],"cleared":[]}"""
    private val payloadB = """{"v":2,"records":[{"key":"k2","kind":"send","sessionId":"s1","text":"b","sentAt":0,"tries":0,"firstQueuedAt":2}],"cleared":["k1"]}"""

    /** T1.3: one pending write = ONE commit of the complete value — never a partial or empty step. */
    @Test
    fun aPendingWriteIsOneCommitOfTheCompleteValue() = runBlocking {
        val opened = openLogged()
        opened.store.writePendingInput(ORIGIN_A, payloadA)
        val before = opened.log.commits.size
        opened.store.writePendingInput(ORIGIN_A, payloadB)
        val steps = opened.log.commits.drop(before).map { it[pendingKey] }
        assertEquals("one logical change, one commit: $steps", listOf(payloadB), steps)
        assertTrue(opened.log.commits.all { it[pendingKey] == payloadA || it[pendingKey] == payloadB })
        opened.job.cancelAndJoin()
    }

    /** T1.3: a process killed at ANY commit of a pending write reloads the old or the new value. */
    @Test
    fun aProcessKilledAtAnyCommitOfAPendingWriteReloadsOldOrNew() = runBlocking {
        for (k in 1..3) {
            settingsFile.delete()
            val opened = openLogged()
            opened.store.writePendingInput(ORIGIN_A, payloadA)
            opened.log.dieAt = opened.log.commits.size + k
            runCatching { opened.store.writePendingInput(ORIGIN_A, payloadB) }
            opened.job.cancelAndJoin()
            val reloaded = open()
            val value = reloaded.store.readPendingInput(ORIGIN_A)
            assertTrue("killed at commit $k of the write: reloaded $value", value == payloadA || value == payloadB)
            reloaded.close()
        }
    }

    // ------------------------------------------------------------------
    // ta-s8q: one durable-send slot per server origin; the 0.6.0 single slot
    // ------------------------------------------------------------------

    @Test
    fun serverOriginIgnoresCaseDefaultPortAndTrailingSlashButNotAnotherPort() {
        assertEquals("https://host:443", serverOrigin("https://Host:443/"))
        assertEquals(serverOrigin("https://Host:443/"), serverOrigin("https://host"))
        assertEquals(serverOrigin("https://host"), serverOrigin("https://HOST/some/path?q=1"))
        assertNotEquals(serverOrigin("https://host"), serverOrigin("https://host:8443"))
        assertNotEquals(serverOrigin("https://host"), serverOrigin("http://host"))
        assertNotEquals(serverOrigin("https://host"), serverOrigin("https://other"))
        assertEquals(PendingSlots.keyFor("https://host:443"), PendingSlots.keyFor(serverOrigin("https://Host/")!!))
    }

    @Test
    fun anIpv6OriginKeepsItsBracketsAndTheCredentialBindingKeepsItsSpelling() {
        assertEquals("http://[fd00::5]:3000", serverOrigin("http://[FD00::5]:3000/"))
        assertEquals("http://[::1]:80", serverOrigin("http://[::1]"))
        assertEquals("pending_input|http://[fd00::5]:3000", PendingSlots.keyFor("http://[fd00::5]:3000"))
        // The credential AAD is unchanged: credentials sealed by earlier builds still open.
        assertEquals("http://fd00::5:3000", DataStoreSettings.originOf("http://[fd00::5]:3000"))
    }

    @Test
    fun anIpv6ServerSignsInMigratesAndKeepsItsSlot() = runBlocking {
        writeLegacyPending("http://[fd00::5]:3000", payloadA)
        val first = open()
        // setServer migrates the 0.6.0 slot in its URL edit: it must not throw here.
        first.store.setServer("http://[fd00::5]:3000", Credential.Cookie(cookie))
        assertEquals(payloadA, first.store.readPendingInput(ORIGIN_V6))
        first.store.writePendingInput(ORIGIN_V6, payloadB)
        first.close()
        val second = open()
        assertEquals(Credential.Cookie(cookie), second.store.credential.first())
        assertEquals(payloadB, second.store.readPendingInput(ORIGIN_V6))
        second.close()
    }

    @Test
    fun theInMemoryStoreHandlesAnIpv6ServerTheSameWay() = runBlocking {
        val store = InMemorySettings(initialBaseUrl = "http://[fd00::5]:3000", initialLegacyPendingInput = payloadA)
        store.setServer("http://[fd00::5]:3000", Credential.Cookie(cookie))
        assertEquals(payloadA, store.readPendingInput(ORIGIN_V6))
        store.writePendingInput(ORIGIN_V6, payloadB)
        assertEquals(payloadB, store.readPendingInput(ORIGIN_V6))
    }

    @Test
    fun aSlotIsOnlyEverNamedByACanonicalOrigin() {
        for (bad in listOf("https://Host", "https://host", "https://host:443/", "host", "")) {
            assertTrue(bad, runCatching { PendingSlots.keyFor(bad) }.isFailure)
        }
    }

    @Test
    fun eachOriginHasItsOwnSlot() = runBlocking {
        val first = open()
        first.store.setServer("https://a.example", Credential.Cookie(cookie))
        first.store.writePendingInput(ORIGIN_A, payloadA)
        first.store.setServer("https://b.example", Credential.Cookie(cookie))
        // The URL moved: B's slot is empty, A's is untouched.
        assertNull(first.store.readPendingInput(ORIGIN_B))
        first.store.writePendingInput(ORIGIN_B, payloadB)
        first.close()
        val second = open()
        assertEquals(payloadA, second.store.readPendingInput(ORIGIN_A))
        assertEquals(payloadB, second.store.readPendingInput(ORIGIN_B))
        // A full clear drops every server's slot.
        second.store.clear()
        assertNull(second.store.readPendingInput(ORIGIN_A))
        assertNull(second.store.readPendingInput(ORIGIN_B))
        second.close()
        val left = raw(settingsFile) { it.data.first() }.asMap().keys.map { it.name }
        assertTrue("pending slots left after clear: $left", left.none { PendingSlots.isPendingKey(it) })
    }

    private suspend fun writeLegacyPending(baseUrl: String?, payload: String) = raw(settingsFile) { ds ->
        ds.edit {
            if (baseUrl != null) it[stringPreferencesKey("base_url")] = baseUrl
            it[stringPreferencesKey(PendingSlots.LEGACY_KEY)] = payload
        }
    }

    @Test
    fun theLegacySlotBelongsToTheServerConfiguredNextToIt() = runBlocking {
        writeLegacyPending("https://A.example/", payloadA)
        val store = open()
        assertEquals(payloadA, store.store.readPendingInput(ORIGIN_A))
        assertNull(store.store.readPendingInput(ORIGIN_B))
        assertNull(store.store.readUnattributedPendingInput())
        store.close()
        val prefs = raw(settingsFile) { it.data.first() }
        assertNull("the legacy slot is moved, not copied", prefs[stringPreferencesKey(PendingSlots.LEGACY_KEY)])
    }

    @Test
    fun aSignInToAnotherServerBeforeFirstAccessStillAttributesTheLegacySlotToTheOldOne() = runBlocking {
        writeLegacyPending("https://a.example", payloadA)
        val store = open()
        // The very first thing this process does is sign in to B.
        store.store.setServer("https://b.example", Credential.Cookie(cookie))
        assertNull(store.store.readPendingInput(ORIGIN_B))
        assertEquals(payloadA, store.store.readPendingInput(ORIGIN_A))
        store.close()
    }

    @Test
    fun aLegacySlotWithNoServerIsKeptUnattributedAndNeverReadAsAServers() = runBlocking {
        writeLegacyPending(baseUrl = null, payloadA)
        val store = open()
        assertEquals(payloadA, store.store.readUnattributedPendingInput())
        store.store.setServer("https://a.example", Credential.Cookie(cookie))
        assertNull(store.store.readPendingInput(ORIGIN_A))
        assertEquals(payloadA, store.store.readUnattributedPendingInput())
        store.close()
    }

    @Test
    fun aLegacySlotNeverOverwritesAnExistingSlot() = runBlocking {
        // Only possible after a downgrade and a second upgrade.
        raw(settingsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey("base_url")] = "https://a.example"
                it[stringPreferencesKey(PendingSlots.keyFor(ORIGIN_A))] = payloadB
                it[stringPreferencesKey(PendingSlots.LEGACY_KEY)] = payloadA
            }
        }
        val store = open()
        assertEquals(payloadB, store.store.readPendingInput(ORIGIN_A))
        // Never merged into A's slot: set aside as unattributed, and consumed.
        assertEquals(payloadA, store.store.readUnattributedPendingInput())
        store.close()
        assertNull(raw(settingsFile) { it.data.first() }[stringPreferencesKey(PendingSlots.LEGACY_KEY)])
    }

    @Test
    fun theLegacySlotIsMigratedOnceAndNeverLaterHandedToTheNextServer() = runBlocking {
        // A's slot is taken, so the 0.6.0 payload cannot go there...
        raw(settingsFile) { ds ->
            ds.edit {
                it[stringPreferencesKey("base_url")] = "https://a.example"
                it[stringPreferencesKey(PendingSlots.keyFor(ORIGIN_A))] = payloadB
                it[stringPreferencesKey(PendingSlots.LEGACY_KEY)] = payloadA
            }
        }
        val store = open()
        // ...and moving on to B and then C must never attribute it to B.
        store.store.setServer("https://b.example", Credential.Cookie(cookie))
        store.store.setServer("https://c.example", Credential.Cookie(cookie))
        assertNull(store.store.readPendingInput(ORIGIN_B))
        assertNull(store.store.readPendingInput("https://c.example:443"))
        assertEquals(payloadA, store.store.readUnattributedPendingInput())
        store.store.removeUnattributedPendingInput()
        assertNull(store.store.readUnattributedPendingInput())
        store.close()
    }

    @Test
    fun slotOriginsAreListedAndRemovable() = runBlocking {
        val store = open()
        store.store.writePendingInput(ORIGIN_A, payloadA)
        store.store.writePendingInput(ORIGIN_V6, payloadB)
        assertEquals(setOf(ORIGIN_A, ORIGIN_V6), store.store.pendingInputOrigins())
        store.store.removePendingInput(ORIGIN_A)
        assertEquals(setOf(ORIGIN_V6), store.store.pendingInputOrigins())
        assertNull(store.store.readPendingInput(ORIGIN_A))
        store.close()
    }

    /** Sealing always fails (Keystore unavailable); opening never succeeds either. */
    private class FailingCipher : CredentialCipher {
        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = throw CredentialCipherException("keystore unavailable")
        override fun open(blob: ByteArray, aad: ByteArray): ByteArray = throw CredentialCipherException("keystore unavailable")
        override fun destroyKey() = Unit
    }

    private companion object {
        const val ORIGIN_A = "https://a.example:443"
        const val ORIGIN_B = "https://b.example:443"
        const val LEGACY_ORIGIN = "https://tether.example.com:443"
        const val ORIGIN_V6 = "http://[fd00::5]:3000"
    }
}
