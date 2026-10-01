package com.tether.app.client

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ta-96z: tether#224 issues the session cookie as `__Host-tether_session` to a browser over
 * HTTPS and as `tether_session` to everyone else (lib/console-cookie.mjs). The app reads
 * either from its login response, prefers `__Host-`, refuses an ambiguous response, stores the
 * name with the value, and presents the cookie back under that name only, on every HTTP call
 * and on the `/ws` upgrade. A credential stored before the name existed is the legacy name.
 */
class SessionCookieNameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private val base get() = h.server.url("/").toString().trimEnd('/')
    private val loginUrl = "https://tether.example.com/api/auth/login".toHttpUrl()

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun parsed(vararg setCookie: String) = sessionCookieFrom(loginUrl, setCookie.toList())

    private fun found(value: String, name: String) = SessionCookieResult.Found(Credential.Cookie(value, name))

    private val legacy = Credential.Cookie.LEGACY_NAME
    private val host = Credential.Cookie.HOST_NAME

    /**
     * A throwaway CA-less certificate for the MockWebServer "Tether", trusted by the client
     * alone: `__Host-` is only ever issued (and only accepted) over https.
     */
    private val tls by lazy {
        val cert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val server = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server to client
    }

    private fun httpClient(https: Boolean): OkHttpClient =
        if (!https) OkHttpClient() else OkHttpClient.Builder().sslSocketFactory(tls.second.sslSocketFactory(), tls.second.trustManager).build()

    private fun signedOutClient(https: Boolean = false): RealTetherClient {
        if (https) {
            h.server.useHttps(tls.first.sslSocketFactory(), tunnelProxy = false)
            // The `/ws` upgrade is an HTTP/1.1 request.
            h.server.protocols = listOf(Protocol.HTTP_1_1)
        }
        h.server.start()
        h.settings = InMemorySettings()
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = httpClient(https),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        return h.client
    }

    /** A real sign-in whose login response carries [setCookie]; returns its result. */
    private fun login(vararg setCookie: String, https: Boolean = false): LoginResult {
        signedOutClient(https)
        h.server.enqueue(MockResponse().setBody(HEALTH_137))
        val response = MockResponse().setBody("""{"ok":true}""")
        setCookie.forEach { response.addHeader("Set-Cookie", it) }
        h.server.enqueue(response)
        val result = runBlocking { h.client.login(base, "pw", "operator") }
        assertEquals("/healthz", take().path)
        assertEquals("/api/auth/login", take().path)
        return result
    }

    private fun consoleOriginOfMock() = "${h.server.url("/").scheme}://${h.server.hostName}:${h.server.port}"

    /**
     * After a successful [login]: the probe and the `/ws` upgrade both carry exactly one
     * Cookie header, `<name>=<value>`, and the upgrade keeps its Origin (ta-41x).
     */
    private fun assertConnectsWith(expected: String) {
        h.enqueueConnect()
        h.handshake(h.nextSocket())
        val probe = take()
        assertEquals("/api/auth/session", probe.path)
        assertEquals(listOf(expected), probe.headers.values("Cookie"))
        val upgrade = take()
        assertEquals("/ws", upgrade.path)
        assertEquals(listOf(expected), upgrade.headers.values("Cookie"))
        assertEquals(probe.getHeader("Origin"), upgrade.getHeader("Origin"))
        assertEquals(consoleOriginOfMock(), upgrade.getHeader("Origin"))
    }

    // ------------------------------------------------------------------
    // Reading the login response
    // ------------------------------------------------------------------

    @Test
    fun onlyTheLegacyName() {
        assertEquals(found("s1", legacy), parsed("tether_session=s1; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800"))
    }

    @Test
    fun onlyTheHostName() {
        assertEquals(found("h1", host), parsed("__Host-tether_session=h1; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800; Secure"))
    }

    @Test
    fun bothNamesPreferHostInEitherOrder() {
        val l = "tether_session=s1; Path=/; Max-Age=60"
        val hh = "__Host-tether_session=h1; Path=/; Max-Age=60; Secure"
        assertEquals(found("h1", host), parsed(l, hh))
        assertEquals(found("h1", host), parsed(hh, l))
    }

    @Test
    fun aNameSetTwiceToDifferentThingsIsAmbiguous() {
        assertEquals(SessionCookieResult.Ambiguous, parsed("tether_session=a; Path=/", "tether_session=b; Path=/"))
        assertEquals(SessionCookieResult.Ambiguous, parsed("__Host-tether_session=a; Secure; Path=/", "__Host-tether_session=b; Secure; Path=/"))
        // Never resolved by falling back to the other name either.
        assertEquals(
            SessionCookieResult.Ambiguous,
            parsed("__Host-tether_session=a; Secure; Path=/", "__Host-tether_session=b; Secure; Path=/", "tether_session=c"),
        )
        assertEquals(SessionCookieResult.Ambiguous, parsed("__Host-tether_session=a; Secure; Path=/", "tether_session=b", "tether_session=c"))
        // A value and a deletion of the same name: which one stands depends on order.
        assertEquals(SessionCookieResult.Ambiguous, parsed("tether_session=a", "tether_session=; Max-Age=0"))
        // The same value twice says one thing.
        assertEquals(found("a", legacy), parsed("tether_session=a; Path=/", "tether_session=a; HttpOnly"))
    }

    @Test
    fun lookalikeNamesAreOtherCookies() {
        val lookalikes = arrayOf(
            "tether_session_x=a",
            "x__Host-tether_session=b",
            "__Host-tether_session_x=c",
            "Tether_Session=d",
            "__host-tether_session=e",
            "xtether_session=f",
            "tether_session",
            "__Host-tether_session",
        )
        assertEquals(SessionCookieResult.Missing, parsed(*lookalikes))
        assertEquals(found("real", legacy), parsed(*lookalikes, "tether_session=real"))
        assertEquals(found("real", host), parsed("__Host-tether_session=real; Secure; Path=/", *lookalikes))
        // The value runs to the first `;` exactly, with the surrounding whitespace trimmed.
        assertEquals(found("v=1", legacy), parsed("tether_session= v=1 ;tether_session=x; Path=/"))
    }

    @Test
    fun aDeletionIssuesNothing() {
        assertEquals(SessionCookieResult.Missing, parsed("tether_session=; Max-Age=0"))
        assertEquals(SessionCookieResult.Missing, parsed("tether_session=gone; Max-Age=0"))
        assertEquals(SessionCookieResult.Missing, parsed("tether_session=gone; Max-Age=-1"))
        assertEquals(SessionCookieResult.Missing, parsed("tether_session="))
        assertEquals(SessionCookieResult.Missing, parsed("tether_session=gone; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        // logout's clearedSessionCookies() next to a fresh cookie of the other name.
        assertEquals(found("s1", legacy), parsed("__Host-tether_session=; Path=/; Max-Age=0; Secure", "tether_session=s1"))
        assertEquals(found("h1", host), parsed("tether_session=; Path=/; Max-Age=0", "__Host-tether_session=h1; Secure; Path=/"))
    }

    @Test
    fun theHostPrefixRulesAreEnforced() {
        val ok = "__Host-tether_session=h1; Secure; Path=/"
        assertEquals(found("h1", host), parsed(ok))
        val broken = listOf(
            "__Host-tether_session=h1; Path=/", // not Secure
            "__Host-tether_session=h1; Secure", // no Path: the default path is /api/auth
            "__Host-tether_session=h1; Secure; Path=/api", // not /
            "__Host-tether_session=h1; Secure; Path=/; Domain=tether.example.com", // not host-only
        )
        for (b in broken) {
            assertEquals(b, SessionCookieResult.Missing, parsed(b))
            // Skipped like a lookalike: the legacy cookie next to it stands...
            assertEquals(b, found("s1", legacy), parsed(b, "tether_session=s1"))
            // ...and it never makes a valid __Host- cookie ambiguous.
            assertEquals(b, found("h2", host), parsed(b.replace("h1", "x9"), "__Host-tether_session=h2; Secure; Path=/"))
        }
        // Over plain HTTP not even a well-formed one counts.
        val http = "http://tether.example.com/api/auth/login".toHttpUrl()
        assertEquals(SessionCookieResult.Missing, sessionCookieFrom(http, listOf(ok)))
        assertEquals(found("s1", legacy), sessionCookieFrom(http, listOf(ok, "tether_session=s1")))
    }

    @Test
    fun aHeaderWhoseParseThrowsIsSkipped() {
        // A parent-domain Domain makes OkHttp consult its public-suffix list; off-device it
        // cannot be loaded and Cookie.parse throws IllegalStateException (seen on this JVM).
        val parent = "tether_session=p; Domain=example.com"
        assertEquals(SessionCookieResult.Missing, parsed(parent))
        assertEquals(found("s1", legacy), parsed(parent, "tether_session=s1"))
        assertEquals(found("h1", host), parsed("__Host-tether_session=x; Secure; Path=/; Domain=example.com", "__Host-tether_session=h1; Secure; Path=/"))
    }

    // ------------------------------------------------------------------
    // End to end: sign in, store, present
    // ------------------------------------------------------------------

    @Test
    fun aLegacyLoginIsStoredAndSentAsTheLegacyName() {
        assertEquals(LoginResult.Success, login("tether_session=leg4cy; Path=/; HttpOnly"))
        assertEquals(Credential.Cookie("leg4cy", legacy), runBlocking { h.settings.session().credential })
        assertConnectsWith("tether_session=leg4cy")
    }

    @Test
    fun aHostLoginIsStoredAndSentAsTheHostNameOnEveryPath() {
        assertEquals(
            LoginResult.Success,
            login("__Host-tether_session=h0st; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800; Secure", https = true),
        )
        assertEquals(Credential.Cookie("h0st", host), runBlocking { h.settings.session().credential })
        assertConnectsWith("__Host-tether_session=h0st")
        // An HTTP call on the live session: the same one header, and the console Origin (ta-41x).
        h.server.enqueue(
            MockResponse().setBody("""{"current":"/w","parent":null,"breadcrumbs":[{"name":"w","path":"/w"}],"entries":[]}"""),
        )
        runBlocking { h.client.files.list("/w") }
        val list = take()
        assertEquals(listOf("__Host-tether_session=h0st"), list.headers.values("Cookie"))
        assertEquals(consoleOriginOfMock(), list.getHeader("Origin"))
        // Sign-out revokes the very cookie the server issued.
        h.server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals(LogoutResult.Revoked, runBlocking { h.client.logout() })
        val logout = take()
        assertEquals("/api/auth/logout", logout.path)
        assertEquals(listOf("__Host-tether_session=h0st"), logout.headers.values("Cookie"))
        assertNull(runBlocking { h.settings.credential.first() })
    }

    @Test
    fun bothNamesSignInUnderHostAndNeverSendTheLegacyOne() {
        assertEquals(LoginResult.Success, login("tether_session=leg4cy; Path=/", "__Host-tether_session=h0st; Path=/; Secure", https = true))
        assertEquals(Credential.Cookie("h0st", host), runBlocking { h.settings.session().credential })
        assertConnectsWith("__Host-tether_session=h0st")
    }

    @Test
    fun anAmbiguousLoginFailsClosedAndStoresNothing() {
        assertEquals(
            LoginResult.Unreachable("The server returned conflicting session cookies."),
            login("__Host-tether_session=a; Secure; Path=/", "__Host-tether_session=b; Secure; Path=/", https = true),
        )
        assertNull(runBlocking { h.settings.session().credential })
        assertNull(runBlocking { h.settings.baseUrl.first() })
        assertNull("nothing connects", h.server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun lookalikesOrADeletionAloneAreNoSessionCookie() {
        assertEquals(
            LoginResult.Unreachable("The server did not return a session cookie."),
            login("tether_session_x=a", "x__Host-tether_session=b", "__Host-tether_session=; Max-Age=0; Secure", "tether_session=gone; Max-Age=0"),
        )
        assertNull(runBlocking { h.settings.session().credential })
        assertNull("nothing connects", h.server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aHostCookieFromAPlainHttpServerIsSkipped() {
        assertEquals(LoginResult.Success, login("__Host-tether_session=h0st; Path=/; Secure", "tether_session=leg4cy; Path=/"))
        assertEquals(Credential.Cookie("leg4cy", legacy), runBlocking { h.settings.session().credential })
        assertConnectsWith("tether_session=leg4cy")
    }

    @Test
    fun aHostCookieAloneFromAPlainHttpServerIsNoSessionCookie() {
        assertEquals(
            LoginResult.Unreachable("The server did not return a session cookie."),
            login("__Host-tether_session=h0st; Path=/; Secure"),
        )
        assertNull(runBlocking { h.settings.session().credential })
    }

    @Test
    fun aParentDomainCookieNeverCrashesSignIn() {
        // The real login path, to a server named under a parent domain (resolved to the mock),
        // so `Domain=example.com` domain-matches and reaches the public-suffix lookup that
        // throws off-device. The throwing header is skipped; the good one signs in.
        h.server.start()
        val mock = InetAddress.getByName(h.server.hostName)
        h.settings = InMemorySettings()
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient.Builder().dns(Dns { name -> if (name == "tether.example.com") listOf(mock) else Dns.SYSTEM.lookup(name) }).build(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.server.enqueue(MockResponse().setBody(HEALTH_137))
        h.server.enqueue(
            MockResponse().setBody("""{"ok":true}""")
                .addHeader("Set-Cookie", "tether_session=p; Domain=example.com")
                .addHeader("Set-Cookie", "tether_session=leg4cy; Path=/"),
        )
        val result = runBlocking { h.client.login("http://tether.example.com:${h.server.port}", "pw", "operator") }
        assertEquals(LoginResult.Success, result)
        assertEquals(Credential.Cookie("leg4cy", legacy), runBlocking { h.settings.session().credential })
    }

    @Test
    fun aStoredHostCredentialIsSentBackUnderItsName() {
        h.server.start()
        h.settings = InMemorySettings(initialBaseUrl = base, initialCookie = "h0st", initialCookieName = host)
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.client.start()
        assertConnectsWith("__Host-tether_session=h0st")
    }

    // ------------------------------------------------------------------
    // Upgrade: a credential sealed by a build from before ta-96z
    // ------------------------------------------------------------------

    private class SoftKeys : CredentialKeySource {
        private var key: SecretKey? = null
        override fun existingKey(): SecretKey? = key
        override fun getOrCreateKey(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun destroyKey() {
            key = null
        }
    }

    @Test
    fun aCredentialStoredBeforeTheUpgradeStillConnectsAsTheLegacyName() = runBlocking {
        h.server.start()
        val keys = SoftKeys()
        val cipher = AesGcmCredentialCipher(keys)
        val origin = DataStoreSettings.originOf(base)!!
        // Exactly the files a pre-ta-96z build leaves: the base URL, and the bare cookie value
        // sealed under the `session_cookie|origin` AAD. No name anywhere.
        val blob = Base64.getEncoder().encodeToString(
            cipher.seal("0ld-c00kie".toByteArray(), "tether.credential.v2|session_cookie|$origin".toByteArray()),
        )
        suspend fun write(file: File, block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
            val job = Job()
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }.edit(block)
            job.cancelAndJoin()
        }
        write(File(tmp.root, DataStoreSettings.SETTINGS_FILE)) { it[stringPreferencesKey("base_url")] = base }
        File(tmp.root, DataStoreSettings.CREDENTIALS_DIR).mkdirs()
        write(File(File(tmp.root, DataStoreSettings.CREDENTIALS_DIR), DataStoreSettings.CREDENTIALS_FILE)) {
            it[stringPreferencesKey(DataStoreSettings.SLOT_COOKIE)] = blob
        }

        val storeJob = Job()
        val store = DataStoreSettings.create(tmp.root, CoroutineScope(Dispatchers.IO + storeJob), cipher)
        assertEquals(Credential.Cookie("0ld-c00kie", legacy), store.credential.first())
        h.client = RealTetherClient(
            settings = store,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.client.start()
        assertConnectsWith("tether_session=0ld-c00kie")
        assertEquals(StoredCredentialState.Present, store.storedCredentialState())
        storeJob.cancelAndJoin()
    }
}
