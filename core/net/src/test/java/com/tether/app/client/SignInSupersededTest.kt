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
        val loginArrived = CountDownLatch(1)
        val claimArrived = CountDownLatch(1)
        val verifyArrived = CountDownLatch(1)

        /** The Cookie header of each `POST /api/auth/logout`. */
        val logouts = ConcurrentLinkedQueue<String>()

        /** Path and Authorization of each `DELETE /api/devices/<id>`. */
        val deviceRevokes = ConcurrentLinkedQueue<Pair<String, String>>()

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

        private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        private fun cookie(value: String) = "tether_session=$value; Path=/; HttpOnly; SameSite=Strict"

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path == "/healthz" -> ok("""{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129,"pairing":true}""")
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
                    ok("""{"ok":true,"token":"$deviceToken","device":{"id":"$deviceId","label":"Pixel"}}""")
                }
                path == "/api/auth/logout" -> {
                    logouts += request.getHeader("Cookie").orEmpty()
                    ok("""{"ok":true}""")
                }
                request.method == "DELETE" && path.startsWith("/api/devices/") -> {
                    deviceRevokes += path to request.getHeader("Authorization").orEmpty()
                    ok("""{"ok":true,"serviceSessions":0,"disconnected":0}""")
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

    private fun client(settings: SettingsStore = InMemorySettings()) = RealTetherClient(
        settings = settings,
        httpClient = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build(),
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
}
