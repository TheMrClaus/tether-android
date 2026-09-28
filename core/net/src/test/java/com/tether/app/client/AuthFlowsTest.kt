package com.tether.app.client

import com.tether.app.protocol.TetherJson
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T1.4 auth flows against a MockWebServer "Tether": password login and pairing
 * error surfaces, logout per credential type, session expiry, the no-redirect /
 * no-cross-origin credential rules, and the owner-grade sessions API.
 */
class AuthFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val h = ConnectionHarness()
    private val other = MockWebServer()
    private val loggedOut = CopyOnWriteArrayList<Pair<String, Credential>>()

    @After
    fun tearDown() {
        h.close()
        runCatching { other.shutdown() }
    }

    private val base get() = h.server.url("/").toString().trimEnd('/')

    /** Like ConnectionHarness.newClient, plus the logout hook and an optional device token. */
    private fun newClient(cookie: String? = null, token: String? = null): RealTetherClient {
        h.server.start()
        h.settings = InMemorySettings(
            initialBaseUrl = if (cookie != null || token != null) base else null,
            initialCookie = cookie,
            initialDeviceToken = token,
        )
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
            onLogout = { url, credential -> loggedOut += url to credential },
        )
        return h.client
    }

    private fun take(): RecordedRequest = h.server.takeRequest(10, TimeUnit.SECONDS)!!

    private fun json(request: RecordedRequest): JsonObject =
        TetherJson.parseToJsonElement(request.body.readUtf8()) as JsonObject

    private fun connected(cookie: String? = null, token: String? = null) {
        newClient(cookie, token)
        h.enqueueConnect()
        h.client.start()
        h.handshake(h.nextSocket())
        take() // probe
        take() // upgrade
    }

    /** start() with a gateway-refused probe: credential loaded in memory, no socket. */
    private fun loadedButIdle(cookie: String? = null, token: String? = null) {
        newClient(cookie, token)
        h.server.enqueue(MockResponse().setResponseCode(401))
        h.client.start()
        h.await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        take()
    }

    private fun health(pairing: Boolean = false, floor: Int = 129) = MockResponse().setBody(
        """{"ok":true,"protocolVersion":129,"nativeProtocolFloor":$floor${if (pairing) ""","pairing":true""" else ""}}""",
    )

    private fun <T> awaitValue(block: suspend () -> T, predicate: (T) -> Boolean): T = runBlocking {
        withTimeout(10_000) {
            while (true) {
                val v = block()
                if (predicate(v)) return@withTimeout v
                kotlinx.coroutines.delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    // ------------------------------------------------------------------
    // Password login
    // ------------------------------------------------------------------

    /**
     * ta-s4r: the login request next to the web's (use-login-flow.ts submitPassword:
     * `fetch("/api/auth/login", {method: "POST", headers: {"content-type": "application/json"},
     * body: JSON.stringify({username, password})})`). server.mjs readBody JSON-parses any
     * non-form body and compares both fields as-is (sha256 + timingSafeEqual), with no Origin
     * or CSRF check on this route, so only the two strings matter; they must arrive unaltered.
     */
    @Test
    fun loginSendsTheSameBytesAsTheWebFormWithNothingAltered() {
        newClient()
        // The deployed server's /healthz (protocol 131, native floor 129): the pre-flight passes.
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"protocolVersion":131,"nativeProtocolFloor":129,"pairing":true}"""))
        h.server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("set-cookie", "tether_session=s; Path=/; HttpOnly"))
        val password = "  Pä\"ss\\wörd é "
        assertEquals(LoginResult.Success, runBlocking { h.client.login(base, password, "Operator") })
        take()
        val login = take()
        assertEquals("POST", login.method)
        assertEquals("/api/auth/login", login.path)
        assertEquals("application/json; charset=utf-8", login.getHeader("Content-Type"))
        assertNull("a sign-in carries no earlier credential", login.getHeader("Cookie"))
        assertNull(login.getHeader("Authorization"))
        // JSON.stringify({username, password}) of the same strings, byte for byte.
        assertEquals(
            "{\"username\":\"Operator\",\"password\":\"  Pä\\\"ss\\\\wörd é \"}",
            login.body.readUtf8(),
        )
    }

    @Test
    fun a401ThatIsNotTethersOwnIsAGatewayRefusalNotABadPassword() {
        newClient()
        // Basic auth in front: a challenge and an HTML page.
        h.server.enqueue(health())
        h.server.enqueue(
            MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"lab\"")
                .setBody("<html>401 Authorization Required</html>"),
        )
        assertEquals(LoginResult.GatewayRefused(401, "Basic"), runBlocking { h.client.login(base, "pw", "operator") })
        // No challenge, but not Tether's `{error}` body either.
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        assertEquals(LoginResult.GatewayRefused(401, null), runBlocking { h.client.login(base, "pw", "operator") })
        // A challenge that is not a scheme token is not echoed onto the screen.
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "<script>").setBody("{\"error\":\"x\"}"))
        assertEquals(LoginResult.GatewayRefused(401, null), runBlocking { h.client.login(base, "pw", "operator") })
        // The Server product is kept (a plain token only) so the screen can name the gateway.
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setHeader("Server", "nginx/1.27.1 (Ubuntu)").setBody("<html/>"))
        assertEquals(LoginResult.GatewayRefused(401, null, "nginx/1.27.1"), runBlocking { h.client.login(base, "pw", "operator") })
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setHeader("Server", "<b>x</b>").setBody("<html/>"))
        assertEquals(LoginResult.GatewayRefused(401, null, null), runBlocking { h.client.login(base, "pw", "operator") })
        // A JSON body whose `error` is not a string is not Tether's (and never throws).
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401},"status":"KO"}"""))
        assertEquals(LoginResult.GatewayRefused(401, null), runBlocking { h.client.login(base, "pw", "operator") })
        // A gateway's 403 page is a gateway refusal too; Tether's 403 JSON keeps its words.
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(403).setBody("<html>Forbidden</html>"))
        assertEquals(LoginResult.GatewayRefused(403, null), runBlocking { h.client.login(base, "pw", "operator") })
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Nope."}"""))
        assertEquals(LoginResult.Unreachable("Nope."), runBlocking { h.client.login(base, "pw", "operator") })
        // Tether's own refusal keeps Tether's words.
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Those credentials are not correct."}"""))
        assertEquals(LoginResult.BadPassword("Those credentials are not correct."), runBlocking { h.client.login(base, "pw", "operator") })
    }

    @Test
    fun theSignInProbeAndTheLoginGoToTheSameOriginAndRoot() {
        // A typed base path or trailing slashes never split the two: both resolve the absolute
        // path against the same origin, so the probe that showed the username field is
        // answered by the same server the password goes to.
        newClient()
        val typed = h.server.url("/some/base//").toString()
        h.server.enqueue(MockResponse().setBody("""{"authenticated":false,"usernameRequired":true}"""))
        assertEquals(true, runBlocking { h.client.signInRequirements(typed) }?.usernameRequired)
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Those credentials are not correct."}"""))
        runBlocking { h.client.login(typed, "pw", "operator") }
        assertEquals("/api/auth/session", take().path)
        assertEquals("/healthz", take().path)
        assertEquals("/api/auth/login", take().path)
    }

    @Test
    fun loginSendsUsernameAndPasswordAsTheWebFormDoes() {
        newClient()
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Those credentials are not correct."}"""))
        val result = runBlocking { h.client.login(base, "pw", "operator") }
        assertEquals(LoginResult.BadPassword("Those credentials are not correct."), result)
        take()
        val login = take()
        assertEquals("/api/auth/login", login.path)
        val body = json(login)
        assertEquals("operator", body["username"]!!.jsonPrimitive.content)
        assertEquals("pw", body["password"]!!.jsonPrimitive.content)
        assertNull(runBlocking { h.settings.credential.first() })
    }

    @Test
    fun loginRateLimitedFromLoginGuard() {
        newClient()
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many attempts. Try again in a few minutes."}"""))
        assertEquals(
            LoginResult.RateLimited("Too many attempts. Try again in a few minutes."),
            runBlocking { h.client.login(base, "pw") },
        )
    }

    @Test
    fun loginPasswordDisabledIsItsOwnSurface() {
        newClient()
        h.server.enqueue(health())
        h.server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":"Password sign-in is turned off for this console. Use a passkey.","code":"password_login_disabled"}"""),
        )
        val result = runBlocking { h.client.login(base, "pw") }
        assertTrue(result is LoginResult.PasswordDisabled)
    }

    @Test
    fun loginAgainstADownServerIsUnreachable() {
        other.start()
        val dead = other.url("/").toString()
        other.shutdown()
        newClient()
        assertTrue(runBlocking { h.client.login(dead, "pw") } is LoginResult.Unreachable)
    }

    @Test
    fun loginOutsideTheNativeWindowNeverPostsThePassword() {
        newClient()
        h.server.enqueue(health(floor = PROTOCOL_FLOOR_ABOVE_US))
        val result = runBlocking { h.client.login(base, "pw") }
        assertTrue(result is LoginResult.VersionMismatch)
        assertEquals(IncompatibleReason.ClientTooOld, (result as LoginResult.VersionMismatch).incompatibility.reason)
        assertEquals(1, h.server.requestCount)
    }

    @Test
    fun loginNeverFollowsARedirectWithThePassword() {
        other.start()
        newClient()
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/api/auth/login")))
        val result = runBlocking { h.client.login(base, "pw") }
        assertTrue(result is LoginResult.Unreachable)
        assertTrue((result as LoginResult.Unreachable).message.contains("redirected"))
        assertEquals(0, other.requestCount)
        assertNull(runBlocking { h.settings.credential.first() })
    }

    @Test
    fun healthzNeverCarriesOneServersCredentialToAnother() {
        loadedButIdle(cookie = "cookie-for-a")
        other.start()
        other.enqueue(health())
        other.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        runBlocking { h.client.login(other.url("/").toString(), "pw") }
        val healthz = other.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/healthz", healthz.path)
        assertNull(healthz.getHeader("Cookie"))
        assertNull(healthz.getHeader("Authorization"))
    }

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    @Test
    fun pairingClaimStoresTheDeviceTokenWithoutPresentingACredential() {
        loadedButIdle(cookie = "old-cookie")
        h.server.enqueue(health(pairing = true))
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"token":"tthr_minted","device":{"id":"d1"}}"""))
        h.server.enqueue(MockResponse().setResponseCode(401)) // the next probe; not under test
        assertEquals(PairResult.Success, runBlocking { h.client.pair(base, " ab12-cd34 ", "Pixel") })
        assertEquals(Credential.DeviceToken("tthr_minted"), runBlocking { h.settings.credential.first() })
        take() // healthz
        val claim = take()
        assertEquals("/api/devices/claim", claim.path)
        assertNull(claim.getHeader("Cookie"))
        assertNull(claim.getHeader("Authorization"))
        val body = json(claim)
        assertEquals("ab12-cd34", body["code"]!!.jsonPrimitive.content)
        assertEquals("Pixel", body["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun pairingErrorSurfaces() {
        newClient()
        h.server.enqueue(health(pairing = true))
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"That pairing code is not valid or has expired."}"""))
        assertEquals(PairResult.Rejected("That pairing code is not valid or has expired."), runBlocking { h.client.pair(base, "X", "L") })
        h.server.enqueue(health(pairing = true))
        h.server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many pairing attempts. Try again in a few minutes."}"""))
        assertTrue(runBlocking { h.client.pair(base, "X", "L") } is PairResult.RateLimited)
        h.server.enqueue(health(pairing = true))
        h.server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Too many paired devices."}"""))
        assertEquals(PairResult.Rejected("Too many paired devices."), runBlocking { h.client.pair(base, "X", "L") })
        h.server.enqueue(health(pairing = false))
        assertTrue(runBlocking { h.client.pair(base, "X", "L") } is PairResult.NotSupported)
        h.server.enqueue(health(pairing = true, floor = PROTOCOL_FLOOR_ABOVE_US))
        assertTrue(runBlocking { h.client.pair(base, "X", "L") } is PairResult.VersionMismatch)
        assertNull(runBlocking { h.settings.credential.first() })
    }

    // ------------------------------------------------------------------
    // Logout
    // ------------------------------------------------------------------

    @Test
    fun cookieLogoutRevokesServerSideAndForgetsLocallyKeepingTheUrl() {
        connected(cookie = "cookie-1")
        h.server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("Set-Cookie", "tether_session=; Max-Age=0"))
        assertEquals(LogoutResult.Revoked, runBlocking { h.client.logout() })

        val logout = take()
        assertEquals("POST", logout.method)
        assertEquals("/api/auth/logout", logout.path)
        assertEquals("tether_session=cookie-1", logout.getHeader("Cookie"))
        assertNull(runBlocking { h.settings.credential.first() })
        assertEquals(base, runBlocking { h.settings.baseUrl.first() })
        assertEquals(ConnectionState.AuthRequired, h.client.connection.value)
        assertNull(h.client.signedOutReason.value)
        assertEquals(1000, h.serverCloses.poll(10, TimeUnit.SECONDS))
        assertEquals(listOf(base to Credential.Cookie("cookie-1")), loggedOut.toList())
        // Nothing reconnects after a logout.
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun deviceTokenLogoutIsLocalOnlyAndNeverSendsTheToken() {
        connected(token = "tthr_device")
        val before = h.server.requestCount
        assertEquals(LogoutResult.LocalOnly, runBlocking { h.client.logout() })
        assertEquals(before, h.server.requestCount)
        assertNull(runBlocking { h.settings.credential.first() })
        assertEquals(base, runBlocking { h.settings.baseUrl.first() })
        assertEquals(ConnectionState.AuthRequired, h.client.connection.value)
        // The push hook still gets the forgotten token to unregister with.
        assertEquals(listOf(base to Credential.DeviceToken("tthr_device")), loggedOut.toList())
    }

    @Test
    fun cookieLogoutTheServerDidNotConfirmStillForgetsLocally() {
        connected(cookie = "cookie-1")
        // (SocketPolicy.DISCONNECT_AT_START only applies to a NEW socket; the
        // logout reuses the pooled probe connection, so use a failing status.)
        h.server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(LogoutResult.ServerNotReached, runBlocking { h.client.logout() })
        assertNull(runBlocking { h.settings.credential.first() })
        assertEquals(ConnectionState.AuthRequired, h.client.connection.value)
    }

    // ------------------------------------------------------------------
    // Session expiry
    // ------------------------------------------------------------------

    @Test
    fun expiredCookieLandsOnLoginWithSessionExpiredAndTheUrlKept() {
        newClient(cookie = "expired")
        h.server.enqueue(MockResponse().setBody("""{"authenticated":false,"usernameRequired":false}"""))
        h.client.start()
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        assertEquals(SignedOutReason.SessionExpired, h.await(h.client.signedOutReason) { it != null })
        awaitValue({ h.settings.credential.first() }) { it == null }
        assertEquals(base, runBlocking { h.settings.baseUrl.first() })
        assertEquals(base, h.await(h.client.serverUrl) { it != null })
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
        assertEquals("tether_session=expired", take().getHeader("Cookie"))
    }

    @Test
    fun rejectedDeviceTokenIsDeviceUnpaired() {
        newClient(token = "tthr_revoked")
        h.server.enqueue(MockResponse().setBody("""{"authenticated":false}"""))
        h.client.start()
        assertEquals(SignedOutReason.DeviceUnpaired, h.await(h.client.signedOutReason) { it != null })
        awaitValue({ h.settings.credential.first() }) { it == null }
    }

    /**
     * T1.4 final verify: `start()` must take the URL and credential from ONE
     * [SettingsStore.session] read. This store's `session()` is consistent (server B with
     * B's token) but its raw flows are torn (B's URL, A's token), as a half-finished server
     * switch would look to two separate reads. Only the pair may ever reach the server.
     */
    @Test
    fun startPresentsTheSessionPairNeverSeparatelyReadFlows() {
        h.server.start()
        val consistent = InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_B")
        val torn = object : SettingsStore by consistent {
            override val deviceToken = kotlinx.coroutines.flow.flowOf<String?>("tthr_A")
            override val cookie = kotlinx.coroutines.flow.flowOf<String?>(null)
            override val credential = kotlinx.coroutines.flow.flowOf<Credential?>(Credential.DeviceToken("tthr_A"))
        }
        h.settings = consistent
        h.client = RealTetherClient(
            settings = torn,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
        h.client.start()
        val probe = take()
        assertEquals("Bearer tthr_B", probe.getHeader("Authorization"))
    }

    @Test
    fun gatewayRefusalKeepsTheCredentialAndDoesNotLoop() {
        newClient(token = "tthr_ok")
        h.server.enqueue(MockResponse().setResponseCode(401).setBody("<html>sso</html>"))
        h.client.start()
        assertEquals(SignedOutReason.GatewayRefused, h.await(h.client.signedOutReason) { it != null })
        assertEquals(ConnectionState.AuthRequired, h.client.connection.value)
        assertEquals(Credential.DeviceToken("tthr_ok"), runBlocking { h.settings.credential.first() })
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun probe403IsAGatewayRefusalToo() {
        newClient(cookie = "cookie")
        h.server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden"}"""))
        h.client.start()
        assertEquals(SignedOutReason.GatewayRefused, h.await(h.client.signedOutReason) { it != null })
        assertEquals(ConnectionState.AuthRequired, h.client.connection.value)
        assertEquals(Credential.Cookie("cookie"), runBlocking { h.settings.credential.first() })
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
    }

    /**
     * T1.4 review (blocking): a URL that moved without its credential (a torn
     * write) must never make the client present server A's credential to
     * server B. Real encrypted store on disk + the real client.
     */
    @Test
    fun aTornUrlWriteNeverSendsOneServersCredentialToAnother() {
        other.start() // server B
        h.server.start() // server A
        val cipher = AesGcmCredentialCipher(JvmKeySource())
        val dir = tmp.newFolder("files")
        runBlocking {
            val job = Job()
            val a = DataStoreSettings.create(dir, CoroutineScope(Dispatchers.IO + job), cipher)
            a.setServer(base, Credential.Cookie("server-a-cookie"))
            job.cancelAndJoin()
            // The torn state: B's URL over A's sealed cookie.
            val raw = Job()
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + raw)) {
                File(dir, DataStoreSettings.SETTINGS_FILE)
            }.edit { it[stringPreferencesKey("base_url")] = other.url("/").toString().trimEnd('/') }
            raw.cancelAndJoin()
        }
        val settings = DataStoreSettings.create(dir, h.scope, cipher)
        h.client = RealTetherClient(settings = settings, httpClient = OkHttpClient(), scope = h.scope, scheduler = h.scheduler)
        h.client.start()
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        assertNull(runBlocking { settings.credential.first() })
        assertEquals(0, other.requestCount) // B saw nothing, let alone A's cookie
        assertEquals(0, h.server.requestCount)
    }

    /** A software AES key standing in for the Keystore (no AndroidKeyStore on the JVM). */
    private class JvmKeySource : CredentialKeySource {
        private var key: javax.crypto.SecretKey? = null
        override fun existingKey() = key
        override fun getOrCreateKey() = key ?: javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }
            .generateKey().also { key = it }
        override fun destroyKey() {
            key = null
        }
    }

    @Test
    fun probeRedirectIsNotFollowedWithTheCredential() {
        other.start()
        newClient(cookie = "secret-cookie")
        h.server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/login")))
        h.client.start()
        assertEquals(SignedOutReason.GatewayRefused, h.await(h.client.signedOutReason) { it != null })
        assertEquals(0, other.requestCount)
    }

    @Test
    fun probeThatIsNotTetherIsTransientNotAVerdict() {
        newClient(cookie = "cookie")
        h.server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        h.client.start()
        h.scheduler.await(::isReconnectDelay)
        assertEquals(ConnectionState.Disconnected, h.client.connection.value)
        assertEquals(Credential.Cookie("cookie"), runBlocking { h.settings.credential.first() })
        assertNull(h.client.signedOutReason.value)
    }

    @Test
    fun closeCode4002IsSessionExpiredAndClearsTheCookie() {
        newClient(cookie = "cookie")
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        ws.close(4002, "session revoked")
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        assertEquals(SignedOutReason.SessionExpired, h.await(h.client.signedOutReason) { it != null })
        awaitValue({ h.settings.credential.first() }) { it == null }
        assertEquals(base, runBlocking { h.settings.baseUrl.first() })
        assertTrue(h.scheduler.pending().none { isReconnectDelay(it.delayMs) })
    }

    @Test
    fun aFreshLoginClearsTheSignedOutReason() {
        newClient(cookie = "expired")
        h.server.enqueue(MockResponse().setBody("""{"authenticated":false}"""))
        h.client.start()
        h.await(h.client.signedOutReason) { it == SignedOutReason.SessionExpired }
        awaitValue({ h.settings.credential.first() }) { it == null }
        h.server.enqueue(health())
        h.server.enqueue(MockResponse().addHeader("Set-Cookie", "tether_session=fresh; Path=/").setBody("""{"ok":true}"""))
        h.server.enqueue(MockResponse().setResponseCode(401)) // next probe, not under test
        assertEquals(LoginResult.Success, runBlocking { h.client.login(base, "pw") })
        assertEquals(Credential.Cookie("fresh"), runBlocking { h.settings.credential.first() })
        h.await(h.client.signedOutReason) { it != SignedOutReason.SessionExpired }
    }

    // ------------------------------------------------------------------
    // Sign-in requirements probe + sessions API
    // ------------------------------------------------------------------

    @Test
    fun signInRequirementsProbeSendsNoCredential() {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(
            MockResponse().setBody(
                """{"authenticated":false,"usernameRequired":true,"passkeyCount":2,"passwordLoginEnabled":false,"passkeysUsable":false}""",
            ),
        )
        val req = runBlocking { h.client.signInRequirements(base) }
        assertEquals(SignInRequirements(usernameRequired = true, passwordLoginEnabled = false, passkeyCount = 2, passkeysUsable = false), req)
        val probe = take()
        assertEquals("/api/auth/session", probe.path)
        assertNull(probe.getHeader("Cookie"))
        assertNull(probe.getHeader("Authorization"))
        // Older server: the fields are absent -> password on, no username.
        h.server.enqueue(MockResponse().setBody("""{"authenticated":false}"""))
        assertEquals(
            SignInRequirements(usernameRequired = false, passwordLoginEnabled = true, passkeyCount = 0, passkeysUsable = false),
            runBlocking { h.client.signInRequirements(base) },
        )
        h.server.enqueue(MockResponse().setResponseCode(500))
        assertNull(runBlocking { h.client.signInRequirements(base) })
    }

    @Test
    fun sessionsListParsesTheWebModel() {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(
            MockResponse().setBody(
                """{"sessions":[
                   {"id":"s1","method":"password","createdAt":1,"lastSeenAt":2,"expiresAt":3,"userAgent":"UA","current":true},
                   {"id":"s2","method":"weird","createdAt":4,"lastSeenAt":5,"expiresAt":6,"userAgent":7},
                   {"method":"passkey"}]}""",
            ),
        )
        val result = runBlocking { h.client.listSignInSessions() }
        assertEquals(
            SignInSessionsResult.Sessions(
                listOf(
                    SignInSession("s1", "password", 1, 2, 3, "UA", current = true),
                    SignInSession("s2", "password", 4, 5, 6, "", current = false),
                ),
            ),
            result,
        )
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/auth/sessions", req.path)
        assertEquals("tether_session=cookie", req.getHeader("Cookie"))
    }

    @Test
    fun sessionsRevokeEncodesTheIdAndRevokeOthersCounts() {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"disconnected":0}"""))
        assertEquals(SignInSessionsResult.Revoked(1), runBlocking { h.client.revokeSignInSession("a/b c") })
        val one = take()
        assertEquals("DELETE", one.method)
        assertEquals("/api/auth/sessions/a%2Fb%20c", one.path)
        h.server.enqueue(MockResponse().setBody("""{"revoked":3,"disconnected":1}"""))
        assertEquals(SignInSessionsResult.Revoked(3), runBlocking { h.client.revokeOtherSignInSessions() })
        assertEquals("/api/auth/sessions", take().path)
        h.server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"No such session."}"""))
        assertEquals(SignInSessionsResult.NotFound, runBlocking { h.client.revokeSignInSession("gone") })
    }

    @Test
    fun sessionsAreOwnerGradeOnlyAndADeviceTokenIsNeverSent() {
        loadedButIdle(token = "tthr_device")
        val before = h.server.requestCount
        assertEquals(SignInSessionsResult.OwnerGradeRequired, runBlocking { h.client.listSignInSessions() })
        assertEquals(SignInSessionsResult.OwnerGradeRequired, runBlocking { h.client.revokeOtherSignInSessions() })
        assertEquals(before, h.server.requestCount)
    }

    @Test
    fun sessionsWithoutACredentialAreNotSignedIn() {
        newClient()
        assertEquals(SignInSessionsResult.NotSignedIn, runBlocking { h.client.listSignInSessions() })
        assertNotNull(h.client)
    }

    private companion object {
        /** A /healthz floor above this app's protocol: the server needs a newer app. */
        const val PROTOCOL_FLOOR_ABOVE_US = 10_000
    }
}
