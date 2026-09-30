package com.tether.app.client

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ta-96z: the session cookie is stored with the name the server issued it under
 * (`tether_session`, or tether#224's `__Host-tether_session`), inside the one sealed cookie
 * slot. A credential sealed by a build from before ta-96z has no name: it reads as the legacy
 * name, and its blob is left exactly as it is, so the upgrade signs nobody out.
 */
class SessionCookieNameStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val value = "s3ss10n-t0ken-secret-value"
    private val keys = SoftwareKeySource()
    private val serverA = "https://a.example"
    private val credentialsFile get() = File(File(tmp.root, DataStoreSettings.CREDENTIALS_DIR), DataStoreSettings.CREDENTIALS_FILE)
    private val settingsFile get() = File(tmp.root, DataStoreSettings.SETTINGS_FILE)
    private val cookieKey = stringPreferencesKey(DataStoreSettings.SLOT_COOKIE)

    /** The AAD every build since T1.4 seals the cookie slot under for [serverA]. */
    private val aadA = "tether.credential.v2|session_cookie|https://a.example:443".toByteArray()

    private class Opened(val store: DataStoreSettings, val job: Job)

    private fun open(): Opened {
        val job = Job()
        return Opened(DataStoreSettings.create(tmp.root, CoroutineScope(Dispatchers.IO + job), AesGcmCredentialCipher(keys)), job)
    }

    private suspend fun Opened.close() = job.cancelAndJoin()

    private suspend fun <T> raw(file: File, block: suspend (androidx.datastore.core.DataStore<Preferences>) -> T): T {
        val job = Job()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return try {
            block(ds)
        } finally {
            job.cancelAndJoin()
        }
    }

    /** Write the files exactly as a build from before ta-96z leaves them after a password sign-in. */
    private suspend fun sealAsBefore(plaintext: String): String {
        val blob = Base64.getEncoder().encodeToString(AesGcmCredentialCipher(keys).seal(plaintext.toByteArray(), aadA))
        raw(settingsFile) { ds -> ds.edit { it[stringPreferencesKey("base_url")] = serverA } }
        raw(credentialsFile) { ds -> ds.edit { it[cookieKey] = blob } }
        return blob
    }

    private suspend fun sealedPlaintext(): String {
        val blob = raw(credentialsFile) { it.data.first() }[cookieKey]!!
        return String(AesGcmCredentialCipher(keys).open(Base64.getDecoder().decode(blob), aadA))
    }

    @Test
    fun aCredentialStoredBeforeTheNameExistedStillSignsInAsTheLegacyName() = runBlocking {
        val blob = sealAsBefore(value)
        val first = open()
        assertEquals(Credential.Cookie(value, Credential.Cookie.LEGACY_NAME), first.store.credential.first())
        assertEquals(Session(serverA, Credential.Cookie(value, "tether_session")), first.store.session())
        assertEquals(StoredCredentialState.Present, first.store.storedCredentialState())
        first.close()
        // Not migrated by rewriting: the blob is byte for byte what the old build wrote.
        assertEquals(blob, raw(credentialsFile) { it.data.first() }[cookieKey])
        val second = open()
        assertEquals(Credential.Cookie(value), second.store.credential.first())
        second.close()
    }

    @Test
    fun aHostCookieKeepsItsNameAcrossARestart() = runBlocking {
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(value, Credential.Cookie.HOST_NAME))
        assertEquals(Credential.Cookie(value, "__Host-tether_session"), first.store.credential.first())
        first.close()
        assertEquals("__Host-tether_session;$value", sealedPlaintext())
        val second = open()
        assertEquals(Credential.Cookie(value, Credential.Cookie.HOST_NAME), second.store.credential.first())
        assertEquals(value, second.store.cookie.first())
        second.close()
    }

    @Test
    fun aLegacyCookieIsStillSealedInThePreviousFormat() = runBlocking {
        // So a build from before ta-96z (a downgrade) reads it unchanged.
        val first = open()
        first.store.setServer(serverA, Credential.Cookie(value))
        first.close()
        assertEquals(value, sealedPlaintext())
    }

    @Test
    fun aStoredCookieUnderAnyOtherNameIsDeadAndDeleted() = runBlocking {
        for (bad in listOf("other;$value", "tether_session_x;$value", "x__Host-tether_session;$value", "__Host-tether_session;", ";$value")) {
            sealAsBefore(bad)
            val opened = open()
            assertNull(bad, opened.store.credential.first())
            assertEquals(bad, StoredCredentialState.Absent, opened.store.storedCredentialState())
            opened.close()
            assertNull(bad, raw(credentialsFile) { it.data.first() }[cookieKey])
        }
    }

    @Test
    fun clearCredentialIfComparesTheNameToo() = runBlocking {
        val opened = open()
        opened.store.setServer(serverA, Credential.Cookie(value, Credential.Cookie.HOST_NAME))
        assertFalse(opened.store.clearCredentialIf(Credential.Cookie(value)))
        assertEquals(Credential.Cookie(value, Credential.Cookie.HOST_NAME), opened.store.credential.first())
        assertTrue(opened.store.clearCredentialIf(Credential.Cookie(value, Credential.Cookie.HOST_NAME)))
        assertNull(opened.store.credential.first())
        opened.close()
    }

    @Test
    fun theInMemoryStoreKeepsTheNameToo() = runBlocking {
        val legacy = InMemorySettings(initialBaseUrl = serverA, initialCookie = value)
        assertEquals(Credential.Cookie(value, Credential.Cookie.LEGACY_NAME), legacy.credential.first())
        val host = InMemorySettings(initialBaseUrl = serverA, initialCookie = value, initialCookieName = Credential.Cookie.HOST_NAME)
        assertEquals(Credential.Cookie(value, Credential.Cookie.HOST_NAME), host.session().credential)
        legacy.setServer(serverA, Credential.Cookie("v2", Credential.Cookie.HOST_NAME))
        assertEquals(Credential.Cookie("v2", Credential.Cookie.HOST_NAME), legacy.credential.first())
        assertEquals("v2", legacy.cookie.first())
    }

    @Test
    fun theStoredFormRoundTripsAndOnlyTheTwoNamesExist() {
        for (c in listOf(
            Credential.Cookie(value),
            Credential.Cookie(value, Credential.Cookie.HOST_NAME),
            Credential.Cookie("a;b", Credential.Cookie.LEGACY_NAME),
            Credential.Cookie("a;b", Credential.Cookie.HOST_NAME),
        )) {
            assertEquals(c, Credential.Cookie.fromStored(c.toStored()))
        }
        assertEquals(value, Credential.Cookie(value).toStored())
        assertNull(Credential.Cookie.fromStored(""))
        assertThrows(IllegalArgumentException::class.java) { Credential.Cookie(value, "other") }
        assertThrows(IllegalArgumentException::class.java) { Credential.Cookie(value, "__host-tether_session") }
        assertEquals("Cookie(***)", Credential.Cookie(value, Credential.Cookie.HOST_NAME).toString())
    }
}
