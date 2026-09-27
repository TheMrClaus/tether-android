package com.tether.app.client

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("{\"records\":[]}", first.store.readPendingInput())
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
        first.store.writePendingInput("{}")
        first.store.clearCredential()
        assertNull(first.store.credential.first())
        assertEquals("https://a.example", first.store.baseUrl.first())
        first.close()
        val second = open()
        assertNull(second.store.credential.first())
        assertEquals("https://a.example", second.store.baseUrl.first())
        second.store.clear()
        assertNull(second.store.baseUrl.first())
        assertNull(second.store.readPendingInput())
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

    /** Sealing always fails (Keystore unavailable); opening never succeeds either. */
    private class FailingCipher : CredentialCipher {
        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = throw CredentialCipherException("keystore unavailable")
        override fun open(blob: ByteArray, aad: ByteArray): ByteArray = throw CredentialCipherException("keystore unavailable")
        override fun destroyKey() = Unit
    }
}
