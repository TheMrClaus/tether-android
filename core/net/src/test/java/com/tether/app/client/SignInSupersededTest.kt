package com.tether.app.client

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.1 r3 (security re-review, Low): a sign-in that lands after another one has already won, or
 * after the user signed out, or after a sign-in to another server, is not adopted, and the session the
 * server minted for it is revoked there. With the autofill offer a pick can land while a slow password
 * attempt is still at the console (the web lets both run, use-login-flow.ts): the first to be adopted
 * wins. Each case has its positive control (an ordinary single sign-in is adopted and nothing is
 * revoked). Two consoles over TLS on localhost (passkeys need https), each holding chosen requests.
 */
class SignInSupersededTest {
    private val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = Console("a")
    private val b = Console("b")

    /** One Tether console: answers like server.mjs, holds a sign-in at [loginGate] / [claimGate] when set. */
    private inner class Console(tag: String) : Dispatcher() {
        val server = MockWebServer()
        val passwordCookie = "${tag.repeat(32)}.cGFzc3dvcmQ"
        val passkeyCookie = "${tag.repeat(32)}.YXBwLXBhc3NrZXk"
        val deviceToken = "tdt_${tag.repeat(43)}"
        val deviceId = "0123456789abcde${tag}"

        @Volatile var loginGate: CountDownLatch? = null
        @Volatile var loginStatus = 200
        @Volatile var claimGate: CountDownLatch? = null
        @Volatile var verifyGate: CountDownLatch? = null

        /** ta-uchk L2: what `POST /api/auth/logout` and `DELETE /api/devices/<id>` answer. */
        @Volatile var revokeStatus = 200

        /** ta-uchk L2: the claim answer names no device id; `GET /api/devices` marks the caller's device `current` (or not). */
        @Volatile var claimOmitsDeviceId = false
        @Volatile var listMarksCurrent = true
        val loginArrived = CountDownLatch(1)
        val claimArrived = CountDownLatch(1)
        val verifyArrived = CountDownLatch(1)

        /** The Cookie header of each `POST /api/auth/logout`. */
        val logouts = ConcurrentLinkedQueue<String>()

        /** Path and Authorization of each `DELETE /api/devices/<id>`. */
        val deviceRevokes = ConcurrentLinkedQueue<Pair<String, String>>()

        /** The Authorization header of each `GET /api/devices`. */
        val deviceLists = ConcurrentLinkedQueue<String>()

        val base: String get() = server.url("/").toString().trimEnd('/')

        fun start() {
            server.dispatcher = this
            server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.start()
        }

        fun releaseAll() {
            loginGate?.countDown()
            claimGate?.countDown()
            verifyGate?.countDown()
        }

        private fun revokeAnswer(body: String): MockResponse = when {
            revokeStatus != 200 -> MockResponse().setResponseCode(revokeStatus).setBody("""{"error":"no"}""")
            else -> ok(body)
        }

        private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        private fun cookie(value: String) = "tether_session=$value; Path=/; HttpOnly; SameSite=Strict"

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path == "/healthz" -> ok("""{"ok":true,"protocolVersion":143,"nativeProtocolFloor":129,"pairing":true}""")
                path == "/api/auth/login" -> {
                    loginArrived.countDown()
                    loginGate?.await(15, TimeUnit.SECONDS)
                    if (loginStatus == 200) ok("""{"ok":true}""").addHeader("Set-Cookie", cookie(passwordCookie))
                    else ok("""{"error":"Those credentials are not correct."}""").setResponseCode(401)
                }
                path == "/api/auth/passkey/login/options" -> ok(PasskeyFixtures.loginOptionsJson(request.requestUrl!!.host))
                path == "/api/auth/passkey/login/verify" -> {
                    verifyArrived.countDown()
                    verifyGate?.await(15, TimeUnit.SECONDS)
                    ok("""{"ok":true}""").addHeader("Set-Cookie", cookie(passkeyCookie))
                }
                path == "/api/devices/claim" -> {
                    claimArrived.countDown()
                    claimGate?.await(15, TimeUnit.SECONDS)
                    if (claimOmitsDeviceId) ok("""{"ok":true,"token":"$deviceToken","device":{"label":"Pixel"}}""")
                    else ok("""{"ok":true,"token":"$deviceToken","device":{"id":"$deviceId","label":"Pixel"}}""")
                }
                path == "/api/auth/logout" -> {
                    logouts += request.getHeader("Cookie").orEmpty()
                    revokeAnswer("""{"ok":true}""")
                }
                request.method == "GET" && path == "/api/devices" -> {
                    deviceLists += request.getHeader("Authorization").orEmpty()
                    if (listMarksCurrent) ok("""{"devices":[{"id":"other-device"},{"id":"$deviceId","current":true}],"pairings":[]}""")
                    else ok("""{"devices":[{"id":"other-device"}],"pairings":[]}""")
                }
                request.method == "DELETE" && path.startsWith("/api/devices/") -> {
                    deviceRevokes += path to request.getHeader("Authorization").orEmpty()
                    revokeAnswer("""{"ok":true,"serviceSessions":0,"disconnected":0}""")
                }
                path == "/api/auth/session" ->
                    if (request.getHeader("Cookie") != null || request.getHeader("Authorization") != null) ok("""{"authenticated":true}""")
                    else ok("""{"authenticated":false,"passkeyCount":1,"passkeysUsable":true}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
            }
        }
    }

    @After fun tearDown() {
        a.releaseAll()
        b.releaseAll()
        scope.cancel()
        runCatching { a.server.shutdown() }
        runCatching { b.server.shutdown() }
    }

    /** ta-uchk L2: the number of `POST /api/auth/logout` calls the client MADE (the cut ones too). */
    private val logoutCalls = java.util.concurrent.atomic.AtomicInteger(0)

    /** ta-uchk L2: while true, every `POST /api/auth/logout` fails on the wire (no answer: an IOException). */
    @Volatile private var cutLogouts = false

    private fun client(settings: SettingsStore = InMemorySettings()) = RealTetherClient(
        settings = settings,
        httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/api/auth/logout") {
                    logoutCalls.incrementAndGet()
                    if (cutLogouts) throw java.io.IOException("cut")
                }
                chain.proceed(chain.request())
            }
            .build(),
        scope = scope,
    )

    private fun stored(settings: SettingsStore): Credential? = runBlocking { settings.credential.first() }
    private fun storedUrl(settings: SettingsStore): String? = runBlocking { settings.baseUrl.first() }
    private fun Credential?.cookieValue(): String? = (this as? Credential.Cookie)?.value

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(10_000) { while (!condition()) kotlinx.coroutines.delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("never: $what", e)
        }
    }

    /** A password sign-in to [console] that is held at the console until the test releases it. */
    private fun heldPasswordSignIn(client: RealTetherClient, console: Console) = run {
        console.loginGate = CountDownLatch(1)
        val attempt = scope.async { client.login(console.base, "correct horse", "") }
        assertTrue("the password attempt reached the console", console.loginArrived.await(10, TimeUnit.SECONDS))
        attempt
    }

    private fun passkeyWins(client: RealTetherClient, console: Console) =
        assertEquals(LoginResult.Success, runBlocking { client.passkeyLogin(console.base, RecordingPasskeys()) })

    // ---- positive controls --------------------------------------------------------------------

    @Test fun aSinglePasswordSignInIsAdoptedAndNothingIsRevoked() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        assertEquals(LoginResult.Success, runBlocking { client.login(a.base, "correct horse", "") })
        assertEquals(a.passwordCookie, stored(settings).cookieValue())
        assertEquals(OriginStanding.Configured, client.originStanding(a.base))
        assertTrue(a.logouts.isEmpty())
    }

    @Test fun aSinglePasskeySignInIsAdoptedAndAPairingAfterItToo() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        passkeyWins(client, a)
        assertEquals(a.passkeyCookie, stored(settings).cookieValue())
        // A later sign-in, begun after the first was adopted, is adopted in turn (nothing is late).
        assertEquals(PairResult.Success, runBlocking { client.pair(a.base, "ABCD-EFGH", "Pixel") })
        assertEquals(Credential.DeviceToken(a.deviceToken), stored(settings))
        assertTrue(a.logouts.isEmpty() && a.deviceRevokes.isEmpty())
    }

    // ---- a late second sign-in ----------------------------------------------------------------

    @Test fun aLatePasswordSignInAfterAPasskeyWinIsNotAdoptedAndIsRevoked() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val late = heldPasswordSignIn(client, a)
        passkeyWins(client, a)
        assertEquals(a.passkeyCookie, stored(settings).cookieValue())
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { late.await() })
        // The passkey sign-in stands, on disk and in memory.
        assertEquals(a.passkeyCookie, stored(settings).cookieValue())
        assertEquals(OriginStanding.Configured, client.originStanding(a.base))
        // The password session the console minted is revoked there, with its own cookie only.
        awaitTrue("the late session is revoked") { a.logouts.isNotEmpty() }
        assertEquals(listOf("tether_session=${a.passwordCookie}"), a.logouts.toList())
    }

    @Test fun aLateRefusalChangesNothing() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        a.loginStatus = 401
        val late = heldPasswordSignIn(client, a)
        passkeyWins(client, a)
        a.loginGate!!.countDown()
        assertEquals(LoginResult.BadPassword("Those credentials are not correct."), runBlocking { late.await() })
        assertEquals(a.passkeyCookie, stored(settings).cookieValue())
        assertEquals(OriginStanding.Configured, client.originStanding(a.base))
        assertTrue("nothing to revoke", a.logouts.isEmpty())
    }

    @Test fun aLatePairingIsNotAdoptedAndItsDeviceIsRevokedWithItsOwnToken() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        a.claimGate = CountDownLatch(1)
        val late = scope.async { client.pair(a.base, "ABCD-EFGH", "Pixel") }
        assertTrue(a.claimArrived.await(10, TimeUnit.SECONDS))
        passkeyWins(client, a)
        a.claimGate!!.countDown()
        assertEquals(PairResult.Superseded, runBlocking { late.await() })
        assertEquals(a.passkeyCookie, stored(settings).cookieValue())
        awaitTrue("the late device is revoked") { a.deviceRevokes.isNotEmpty() }
        assertEquals(listOf("/api/devices/${a.deviceId}" to "Bearer ${a.deviceToken}"), a.deviceRevokes.toList())
    }

    // ---- a sign-out within the window -----------------------------------------------------------

    @Test fun aSignOutWithinTheWindowStaysSignedOut() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val late = heldPasswordSignIn(client, a)
        passkeyWins(client, a)
        runBlocking { client.logout() }
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { late.await() })
        assertNull("still signed out", stored(settings))
        assertEquals(OriginStanding.SignedOut, client.originStanding(a.base))
        awaitTrue("both sessions revoked") { a.logouts.size == 2 }
        assertEquals(setOf("tether_session=${a.passkeyCookie}", "tether_session=${a.passwordCookie}"), a.logouts.toSet())
    }

    /**
     * The sign-out alone (no other sign-in in between) is what stops it: a sign-in begun while the store's
     * own sign-in was in force, then a sign-out, then the 200.
     */
    @Test fun aSignInBegunBeforeASignOutIsNotAdoptedAfterIt() {
        a.start()
        val settings = InMemorySettings(initialBaseUrl = a.base, initialCookie = a.passkeyCookie)
        val client = client(settings)
        client.start()
        awaitTrue("the stored sign-in is in force") { client.originStanding(a.base) == OriginStanding.Configured }
        val late = heldPasswordSignIn(client, a)
        runBlocking { client.logout() }
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { late.await() })
        assertNull(stored(settings))
        assertEquals(OriginStanding.SignedOut, client.originStanding(a.base))
        awaitTrue("the late session is revoked") { a.logouts.any { it == "tether_session=${a.passwordCookie}" } }
    }

    /** A sign-out that lands while the winning sign-in is still writing the store: the sign-out stands. */
    @Test fun aSignOutDuringTheStoreWriteStandsAndTheWriteIsTakenBack() {
        a.start()
        b.start()
        // ta-coik.1 r4 (ta-5csf I2): a server URL was stored before (the login screen's prefill).
        val inner = InMemorySettings(initialBaseUrl = b.base)
        val writing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val settings = object : SettingsStore by inner {
            override suspend fun setServer(baseUrl: String, credential: Credential) {
                writing.complete(Unit)
                proceed.await()
                inner.setServer(baseUrl, credential)
            }
        }
        val client = client(settings)
        val signIn = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { writing.await() } }
        runBlocking { client.logout() }
        proceed.complete(Unit)
        assertEquals(LoginResult.Superseded, runBlocking { signIn.await() })
        assertNull("the store write is taken back", stored(inner))
        assertEquals("and the URL it moved, back to the one stored before", b.base, storedUrl(inner))
        assertEquals(OriginStanding.SignedOut, client.originStanding(a.base))
        awaitTrue("the session is revoked") { a.logouts.any { it == "tether_session=${a.passwordCookie}" } }
    }

    /**
     * One adoption at a time: a sign-in begun after another was claimed (so not late) waits for that
     * adoption to finish writing, then is adopted in turn. Disk and memory end on the same sign-in.
     */
    @Test fun twoAdoptionsNeverInterleaveSoDiskAndMemoryAgree() {
        a.start()
        b.start()
        val inner = InMemorySettings()
        val writing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val settings = object : SettingsStore by inner {
            override suspend fun setServer(baseUrl: String, credential: Credential) {
                if (first.getAndSet(false)) {
                    writing.complete(Unit)
                    proceed.await()
                }
                inner.setServer(baseUrl, credential)
            }
        }
        val client = client(settings)
        val toA = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { writing.await() } }
        // A is claimed and writing the store; a sign-in to B begins now and lands meanwhile.
        val toB = scope.async { client.login(b.base, "correct horse", "") }
        awaitTrue("B's sign-in was answered") { b.server.requestCount >= 2 }
        // Time for B's adoption to run, were it not made to wait for A's.
        Thread.sleep(750)
        proceed.complete(Unit)
        assertEquals(LoginResult.Success, runBlocking { toA.await() })
        assertEquals(LoginResult.Success, runBlocking { toB.await() })
        assertEquals(b.base, storedUrl(inner))
        assertEquals(b.passwordCookie, stored(inner).cookieValue())
        assertEquals(OriginStanding.Configured, client.originStanding(b.base))
    }

    // ---- a sign-in to another server within the window -----------------------------------------

    /** A password sign-in to [console] held at the console; the caller releases [Console.loginGate]. */
    private fun startHeld(client: RealTetherClient, console: Console) = heldPasswordSignIn(client, console)

    /**
     * ta-coik.1 r4 (verifier R3-1, security L1 / ta-5csf): overlapping sign-ins to two servers. The one
     * the user started LAST decides, even when the older one's 200 lands first: the older is not
     * adopted, and its session is revoked at its own server.
     */
    @Test fun aNewerSignInToAnotherServerWinsWhenTheOlderFinishesFirst() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val toA = startHeld(client, a)
        val toB = startHeld(client, b)
        a.loginGate!!.countDown()
        assertEquals("A, older, answered first", LoginResult.Superseded, runBlocking { toA.await() })
        assertNull("nothing adopted while B is pending", stored(settings))
        b.loginGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { toB.await() })
        assertEquals(b.base, storedUrl(settings))
        assertEquals(b.passwordCookie, stored(settings).cookieValue())
        assertEquals(OriginStanding.Configured, client.originStanding(b.base))
        awaitTrue("A's session is revoked at A") { a.logouts.toList() == listOf("tether_session=${a.passwordCookie}") }
        assertTrue(b.logouts.isEmpty())
    }

    /** Positive control of the same rule: the newer one answers first and is adopted; the older is revoked. */
    @Test fun aNewerSignInToAnotherServerWinsWhenItFinishesFirst() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val toA = startHeld(client, a)
        val toB = startHeld(client, b)
        b.loginGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { toB.await() })
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { toA.await() })
        assertEquals(b.base, storedUrl(settings))
        assertEquals(b.passwordCookie, stored(settings).cookieValue())
        awaitTrue("A's session is revoked at A") { a.logouts.toList() == listOf("tether_session=${a.passwordCookie}") }
    }

    /** A newer sign-in elsewhere that FAILED is not pending any more: the older one may still land. */
    @Test fun aNewerSignInElsewhereThatFailedDoesNotHoldTheOlderOneBack() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val toA = startHeld(client, a)
        b.loginStatus = 401
        assertEquals(LoginResult.BadPassword("Those credentials are not correct."), runBlocking { client.login(b.base, "wrong", "") })
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { toA.await() })
        assertEquals(a.base, storedUrl(settings))
        assertTrue(a.logouts.isEmpty())
    }

    /**
     * Same server, in parallel (a password and a passkey, as the autofill pick allows): first-wins stays.
     * Here the OLDER one (the password) answers first and is adopted; the newer passkey is revoked.
     */
    @Test fun sameServerAttemptsKeepFirstWinsWhenTheOlderFinishesFirst() {
        a.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val password = startHeld(client, a)
        a.verifyGate = CountDownLatch(1)
        val passkey = scope.async { client.passkeyLogin(a.base, RecordingPasskeys()) }
        assertTrue(a.verifyArrived.await(10, TimeUnit.SECONDS))
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { password.await() })
        a.verifyGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { passkey.await() })
        assertEquals(a.passwordCookie, stored(settings).cookieValue())
        awaitTrue("the passkey session is revoked") { a.logouts.toList() == listOf("tether_session=${a.passkeyCookie}") }
    }

    @Test fun aSwitchToAnotherServerWithinTheWindowStaysThere() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val late = heldPasswordSignIn(client, a)
        passkeyWins(client, a)
        // The user moves to server B (a sign-in there, adopted: the positive control).
        assertEquals(LoginResult.Success, runBlocking { client.login(b.base, "correct horse", "") })
        assertEquals(b.base, storedUrl(settings))
        a.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { late.await() })
        // Still on B, on disk and in memory; A's late session revoked at A.
        assertEquals(b.base, storedUrl(settings))
        assertEquals(b.passwordCookie, stored(settings).cookieValue())
        assertEquals(OriginStanding.Configured, client.originStanding(b.base))
        assertEquals(OriginStanding.OtherServer, client.originStanding(a.base))
        awaitTrue("A's late session is revoked") { a.logouts.any { it == "tether_session=${a.passwordCookie}" } }
        assertTrue("nothing of B's revoked", b.logouts.isEmpty())
    }

    // ---- ta-uchk: the sign-out that lands while the store is written ---------------------------

    /**
     * ta-uchk L1: the store write LANDS, then the user signs out, and a start() begun after that sign-out
     * reads the attempt's credential from the store and adopts it. When the attempt then finds it was
     * signed out, that adoption is undone (and it stays recorded as forgotten) even if the take-back of
     * the store write fails too.
     */
    private fun aStartQueuedDuringTheAttemptAdoptsItsCredential(revertFails: Boolean) {
        a.start()
        val inner = InMemorySettings()
        val holding = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val afterWrite = CompletableDeferred<Unit>()
        val gateSessions = java.util.concurrent.atomic.AtomicBoolean(false)
        val reverts = java.util.concurrent.atomic.AtomicInteger(0)
        val settings = object : SettingsStore by inner {
            // The write is held, then LANDS, then is held again before the attempt looks at what happened.
            override suspend fun setServer(baseUrl: String, credential: Credential) {
                holding.complete(Unit)
                proceed.await()
                inner.setServer(baseUrl, credential)
                written.complete(Unit)
                afterWrite.await()
            }

            // A start() that began after the sign-out reads the store only once the write has landed.
            override suspend fun session(): Session {
                if (gateSessions.get()) written.await()
                return inner.session()
            }

            override suspend fun revertServerIf(expected: Credential, previousBaseUrl: String?): Boolean {
                reverts.incrementAndGet()
                if (revertFails) throw java.io.IOException("store down")
                return inner.revertServerIf(expected, previousBaseUrl)
            }
        }
        val client = client(settings).also { it.revokeRetryMs = 10 }
        val signIn = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { holding.await() } }
        runBlocking { client.logout() }
        assertEquals("signed out", OriginStanding.SignedOut, client.originStanding(a.base))
        // The start() that began after the logout reads the attempt's credential from the store.
        gateSessions.set(true)
        client.start()
        proceed.complete(Unit)
        awaitTrue("the queued start() adopted the attempt's credential") { client.originStanding(a.base) == OriginStanding.Configured }
        afterWrite.complete(Unit)
        assertEquals(LoginResult.Superseded, runBlocking { signIn.await() })
        assertEquals("the user's sign-out stands in memory", OriginStanding.SignedOut, client.originStanding(a.base))
        awaitTrue("the login screen shows") { client.connection.value is ConnectionState.AuthRequired }
        if (revertFails) {
            assertEquals("the take-back was tried twice", 2, reverts.get())
            assertEquals("the store still holds it (the take-back failed)", a.passwordCookie, stored(inner).cookieValue())
        } else {
            assertNull("the store write is taken back", stored(inner))
        }
        // Another start() (an activity re-created) adopts nothing, whatever the store still holds.
        client.start()
        Thread.sleep(400)
        assertEquals(OriginStanding.SignedOut, client.originStanding(a.base))
        assertTrue("never Connected", client.connection.value !is ConnectionState.Connected)
    }

    @Test fun aStartQueuedDuringTheAttemptDoesNotKeepItsCredentialAfterTheLogout() =
        aStartQueuedDuringTheAttemptAdoptsItsCredential(revertFails = false)

    @Test fun aStartQueuedDuringTheAttemptDoesNotKeepItsCredentialEvenWhenTheTakeBackFails() =
        aStartQueuedDuringTheAttemptAdoptsItsCredential(revertFails = true)

    // ---- ta-uchk L3 / L4: the take-back of the store write ------------------------------------

    /** A store whose [setServer] is held (before it writes) until the test lets it go, and whose take-back is [revert]. */
    private inner class WriteHeld(val inner: InMemorySettings, val revert: suspend (SettingsStore, Credential, String?) -> Boolean) : SettingsStore by inner {
        val writing = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val reverts = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun setServer(baseUrl: String, credential: Credential) {
            writing.complete(Unit)
            proceed.await()
            inner.setServer(baseUrl, credential)
        }

        override suspend fun revertServerIf(expected: Credential, previousBaseUrl: String?): Boolean {
            reverts.incrementAndGet()
            return revert(inner, expected, previousBaseUrl)
        }
    }

    /**
     * ta-uchk L3: a stop() while the store is written wipes the store, the server URL the attempt would
     * "put back" with it. The take-back must not bring that URL back.
     */
    @Test fun aTakeBackNeverRestoresAServerUrlAStopWiped() {
        a.start()
        b.start()
        val inner = InMemorySettings(initialBaseUrl = b.base)
        val settings = WriteHeld(inner) { s, c, p -> s.revertServerIf(c, p) }
        val client = client(settings)
        val signIn = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { settings.writing.await() } }
        client.stop()
        awaitTrue("stop()'s wipe landed") { storedUrl(inner) == null }
        settings.proceed.complete(Unit)
        assertEquals(LoginResult.Superseded, runBlocking { signIn.await() })
        assertNull("the credential is taken back", stored(inner))
        assertNull("and the URL the wipe removed does not come back", storedUrl(inner))
        assertEquals(OriginStanding.SignedOut, client.originStanding(a.base))
    }

    /** Positive control of L3: with no stop(), the URL stored before is put back (as aSignOutDuringTheStoreWrite...). */
    @Test fun aTakeBackAfterALogoutStillRestoresTheServerUrlStoredBefore() {
        a.start()
        b.start()
        val inner = InMemorySettings(initialBaseUrl = b.base)
        val settings = WriteHeld(inner) { s, c, p -> s.revertServerIf(c, p) }
        val client = client(settings)
        val signIn = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { settings.writing.await() } }
        runBlocking { client.logout() }
        settings.proceed.complete(Unit)
        assertEquals(LoginResult.Superseded, runBlocking { signIn.await() })
        assertNull(stored(inner))
        assertEquals(b.base, storedUrl(inner))
    }

    /** ta-uchk L4: one failed take-back is tried once more and lands. */
    @Test fun aFailedTakeBackIsTriedOnceMore() {
        a.start()
        val inner = InMemorySettings()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val settings = WriteHeld(inner) { s, c, p -> if (calls.getAndIncrement() == 0) throw java.io.IOException("store down") else s.revertServerIf(c, p) }
        val client = client(settings)
        val signIn = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { settings.writing.await() } }
        runBlocking { client.logout() }
        settings.proceed.complete(Unit)
        assertEquals(LoginResult.Superseded, runBlocking { signIn.await() })
        assertEquals(2, settings.reverts.get())
        assertNull("the second try landed", stored(inner))
    }

    /**
     * ta-uchk L4: a double failure is not swallowed. The credential stays in the store (nothing can be
     * done about that), so it is recorded as forgotten: no start() brings it back into memory.
     */
    @Test fun aDoubleTakeBackFailureKeepsTheCredentialForgotten() {
        a.start()
        val inner = InMemorySettings()
        val settings = WriteHeld(inner) { _, _, _ -> throw java.io.IOException("store down") }
        val client = client(settings)
        val signIn = scope.async { client.login(a.base, "correct horse", "") }
        runBlocking { withTimeout(10_000) { settings.writing.await() } }
        runBlocking { client.logout() }
        settings.proceed.complete(Unit)
        assertEquals(LoginResult.Superseded, runBlocking { signIn.await() })
        assertEquals("tried twice, then given up", 2, settings.reverts.get())
        assertEquals("it is still in the store", a.passwordCookie, stored(inner).cookieValue())
        client.start()
        Thread.sleep(500)
        assertEquals("but never adopted from it", OriginStanding.SignedOut, client.originStanding(a.base))
    }

    // ---- ta-uchk L2: the revoke of a superseded sign-in ----------------------------------------

    private fun lateSignInRevoked(console: Console): RealTetherClient {
        val client = client().also { it.revokeRetryMs = 10 }
        val late = heldPasswordSignIn(client, console)
        passkeyWins(client, console)
        console.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { late.await() })
        return client
    }

    @Test fun aRevokeThatAnswersNon2xxIsTriedOnceMore() {
        a.start()
        a.revokeStatus = 500
        lateSignInRevoked(a)
        awaitTrue("tried twice") { a.logouts.size == 2 }
        Thread.sleep(300)
        assertEquals("and no more than twice", 2, a.logouts.size)
    }

    @Test fun aRevokeThatNeverAnswersIsTriedOnceMore() {
        a.start()
        cutLogouts = true
        lateSignInRevoked(a)
        Thread.sleep(500)
        assertEquals("tried twice, no more", 2, logoutCalls.get())
        assertTrue("neither reached the console", a.logouts.isEmpty())
    }

    @Test fun aRevokeThatWorksIsNotRepeated() {
        a.start()
        lateSignInRevoked(a)
        awaitTrue("revoked") { a.logouts.size == 1 }
        Thread.sleep(300)
        assertEquals(1, a.logouts.size)
    }

    @Test fun aDeviceRevokeThatAnswersNon2xxIsTriedOnceMore() {
        a.start()
        a.revokeStatus = 503
        val client = client().also { it.revokeRetryMs = 10 }
        a.claimGate = CountDownLatch(1)
        val late = scope.async { client.pair(a.base, "ABCD-EFGH", "Pixel") }
        assertTrue(a.claimArrived.await(10, TimeUnit.SECONDS))
        passkeyWins(client, a)
        a.claimGate!!.countDown()
        assertEquals(PairResult.Superseded, runBlocking { late.await() })
        awaitTrue("tried twice") { a.deviceRevokes.size == 2 }
    }

    /** A claim answer without a device id: the id is asked of the server (`current`), then the device is revoked. */
    @Test fun aDeviceTokenWithoutAnIdIsRevokedThroughTheDeviceList() {
        a.start()
        a.claimOmitsDeviceId = true
        val client = client().also { it.revokeRetryMs = 10 }
        a.claimGate = CountDownLatch(1)
        val late = scope.async { client.pair(a.base, "ABCD-EFGH", "Pixel") }
        assertTrue(a.claimArrived.await(10, TimeUnit.SECONDS))
        passkeyWins(client, a)
        a.claimGate!!.countDown()
        assertEquals(PairResult.Superseded, runBlocking { late.await() })
        awaitTrue("the late device is revoked") { a.deviceRevokes.isNotEmpty() }
        assertEquals(listOf("/api/devices/${a.deviceId}" to "Bearer ${a.deviceToken}"), a.deviceRevokes.toList())
        assertEquals(listOf("Bearer ${a.deviceToken}"), a.deviceLists.toList())
    }

    /** No id and none to be found: nothing is revoked, and nothing breaks (the sign-in that stands is untouched). */
    @Test fun aDeviceTokenWithoutAnIdThatTheServerDoesNotNameChangesNothing() {
        a.start()
        a.claimOmitsDeviceId = true
        a.listMarksCurrent = false
        val settings = InMemorySettings()
        val client = client(settings)
        a.claimGate = CountDownLatch(1)
        val late = scope.async { client.pair(a.base, "ABCD-EFGH", "Pixel") }
        assertTrue(a.claimArrived.await(10, TimeUnit.SECONDS))
        passkeyWins(client, a)
        a.claimGate!!.countDown()
        assertEquals(PairResult.Superseded, runBlocking { late.await() })
        awaitTrue("the list was asked") { a.deviceLists.isNotEmpty() }
        Thread.sleep(300)
        assertTrue("no revoke without an id", a.deviceRevokes.isEmpty())
        assertEquals(a.passkeyCookie, stored(settings).cookieValue())
    }

    // ---- ta-uchk: the modal passkey and the autofill pick release their sign-in ticket ---------

    /**
     * ta-coik.1 r4 test gap: a passkey attempt to another server that ends EARLY (refused before any
     * ceremony) must release its ticket: otherwise it stays "a newer sign-in to another server, still
     * pending" and the older password sign-in is never adopted.
     */
    @Test fun r4NewerPasskeyRefusedEarlyReleasesItsTicket() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val older = startHeld(client, b)
        val refused = runBlocking { client.passkeyLogin(a.base, RecordingPasskeys(available = false)) }
        assertTrue("the modal passkey was refused before any ceremony: $refused", refused is LoginResult.PasskeyFailed)
        b.loginGate!!.countDown()
        assertEquals("the refused newer attempt holds nothing back", LoginResult.Success, runBlocking { older.await() })
        assertEquals(b.base, storedUrl(settings))
    }

    /** The same through the ceremony: the user dismisses the prompt (the modal path's own `finally`). */
    @Test fun aDismissedModalPasskeyToAnotherServerReleasesItsTicket() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val older = startHeld(client, b)
        val dismissed = runBlocking { client.passkeyLogin(a.base, RecordingPasskeys(answer = PasskeyCeremony.Dismissed)) }
        assertEquals(LoginResult.PasskeyDismissed, dismissed)
        b.loginGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { older.await() })
        assertEquals(b.base, storedUrl(settings))
    }

    /** An autofill pick (passkeyLoginFinish) is a newer attempt to another server; ended early, it releases its ticket. */
    @Test fun anAutofillPickToAnotherServerThatEndsEarlyReleasesItsTicket() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val older = startHeld(client, b)
        val request = (runBlocking { client.passkeyLoginStart(a.base) } as PasskeyLoginStart.Ready).request
        assertEquals(LoginResult.PasskeyDismissed, runBlocking { client.passkeyLoginFinish(request, PasskeyCeremony.Dismissed) })
        b.loginGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { older.await() })
        assertEquals(b.base, storedUrl(settings))
    }

    /** Positive control: the same autofill pick, still PENDING at its console, does hold the older attempt back. */
    @Test fun anAutofillPickToAnotherServerThatIsStillPendingDecides() {
        a.start()
        b.start()
        val settings = InMemorySettings()
        val client = client(settings)
        val older = startHeld(client, b)
        val request = (runBlocking { client.passkeyLoginStart(a.base) } as PasskeyLoginStart.Ready).request
        a.verifyGate = CountDownLatch(1)
        val pick = scope.async { client.passkeyLoginFinish(request, PasskeyCeremony.Done(PasskeyFixtures.AUTHENTICATION_RESPONSE)) }
        assertTrue(a.verifyArrived.await(10, TimeUnit.SECONDS))
        b.loginGate!!.countDown()
        assertEquals(LoginResult.Superseded, runBlocking { older.await() })
        a.verifyGate!!.countDown()
        assertEquals(LoginResult.Success, runBlocking { pick.await() })
        assertEquals(a.base, storedUrl(settings))
    }
}
