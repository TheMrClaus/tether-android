package com.tether.app.push

import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PushRegistrar] round-trips against a [MockWebServer]: config probe → POST
 * register → PATCH update → DELETE unregister. The Firebase token call is
 * stubbed via [FirebaseTokenProvider] so no Play Services initialisation is
 * needed. The bearer-token auth header and the JSON body shapes are asserted
 * against the server's `/api/push/fcm-register` contract.
 */
class PushRegistrarTest {

    private val server = MockWebServer()
    private val token = "fake-device-token-not-a-credential"
    private val settings = InMemorySettings()

    @Before
    fun setUp() {
        server.start()
        // InMemorySettings.setServer is suspend but only mutates StateFlows —
        // runBlocking here is safe and confined to the test thread.
        runBlocking {
            settings.setServer(server.url("/").toString(), Credential.DeviceToken(token))
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun registrar(token: String? = "fake-fcm-token-not-a-credential"): PushRegistrar =
        PushRegistrar(
            settings = settings,
            httpClient = OkHttpClient(),
            tokenProvider = FirebaseTokenProvider { token },
        )

    @Test
    fun syncPostsRegisterWithTokenScopeAndSets() = runBlocking {
        // 1. config probe → configured: true
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null}"""))
        // 2. POST register → 201
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true,"created":true}"""))

        val result = registrar().sync(PushScope.Attached, setOf("s1", "s2"), setOf("p1"), syncHints = false)

        assertEquals(PushRegistrarResult.Success, result)

        val configReq = server.takeRequest()
        assertEquals("/api/push/fcm-config", configReq.path)
        assertEquals("Bearer $token", configReq.getHeader("Authorization"))

        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/api/push/fcm-register", postReq.path)
        val body = postReq.body.readUtf8()
        assertTrue(body.contains("\"fcmToken\":\"fake-fcm-token-not-a-credential\""))
        assertTrue(body.contains("\"scope\":\"attached\""))
        assertTrue(body.contains("\"attachedSessions\":[\"s1\",\"s2\"]"))
        assertTrue(body.contains("\"pinnedSessions\":[\"p1\"]"))
        // The POST replaces the server row, so the opt-in is never left out.
        assertTrue(body.contains("\"syncHints\":false"))
    }

    /** Counts calls whose exchange completed; an unclosed response never gets here. */
    private class CallEnds : okhttp3.EventListener() {
        val ended = java.util.concurrent.atomic.AtomicInteger()
        override fun callEnd(call: okhttp3.Call) {
            ended.incrementAndGet()
        }
    }

    @Test
    fun everyResponseIsClosed() = runBlocking {
        val ends = CallEnds()
        val r = PushRegistrar(settings, OkHttpClient.Builder().eventListener(ends).build(), FirebaseTokenProvider { "fake-fcm-token" })
        // Bodies the registrar never reads: only close() ends these calls.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("unread register body"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("unread update body"))
        r.sync(PushScope.All, emptySet(), emptySet(), syncHints = false)
        r.update(PushScope.All, emptySet(), emptySet(), syncHints = false)
        assertEquals("config + register + update all ended", 3, ends.ended.get())
    }

    @Test
    fun aSixteenThousandDeepConfigIsUnavailableNotAStackOverflow() {
        // Under the 16 KB cap, 16k levels deep. Run where a real start runs it: a 1 MB-stack thread.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[".repeat(8_000) + "{\"a\":".repeat(1) + "[".repeat(7_990)))
        var result: PushRegistrarResult? = null
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                result = runBlocking(kotlinx.coroutines.Dispatchers.Unconfined) { registrar().sync(PushScope.All, emptySet(), emptySet(), syncHints = false) }
            } catch (t: Throwable) {
                failure = t
            }
        }, "one-mb", 1024L * 1024L)
        thread.start()
        thread.join(20_000)
        failure?.let { throw it }
        assertEquals(PushRegistrarResult.Error("Push config unreachable."), result)
    }

    @Test
    fun aMalformedConfigIsUnavailableNotACrash() = runBlocking {
        val bodies = listOf(
            "<!doctype html><html><body>Sign in to the proxy</body></html>", // auth proxy, 200
            "{\"configured\":", // truncated
            "[true]", // JSON, not an object
            "{\"configured\":true,\"pad\":\"" + "x".repeat(20_000) + "\"}", // over the size cap
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val result = registrar().sync(PushScope.All, emptySet(), emptySet(), syncHints = false)
            assertEquals(body.take(40), PushRegistrarResult.Error("Push config unreachable."), result)
            server.takeRequest() // only the probe: nothing is registered
        }
        assertEquals(bodies.size, server.requestCount)
        // A wrongly typed flag reads as "not configured": still no exception.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"configured\":{\"x\":1}}"))
        assertEquals(PushRegistrarResult.ServerUnconfigured, registrar().sync(PushScope.All, emptySet(), emptySet(), syncHints = false))
    }

    @Test
    fun syncSendsTheStoredSyncHintsOptIn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        assertEquals(PushRegistrarResult.Success, registrar().sync(PushScope.All, emptySet(), emptySet(), syncHints = true))
        server.takeRequest() // config probe
        assertTrue(server.takeRequest().body.readUtf8().contains("\"syncHints\":true"))
    }

    @Test
    fun updateSendsTheStoredSyncHintsOptIn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        registrar().update(PushScope.All, emptySet(), emptySet(), syncHints = true)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"syncHints\":true"))
    }

    @Test
    fun syncReturnsServerUnconfiguredWhenConfigReportsFalse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":false,"reason":"not configured"}"""))
        val result = registrar().sync(PushScope.All, emptySet(), emptySet(), syncHints = false)
        assertEquals(PushRegistrarResult.ServerUnconfigured, result)
    }

    @Test
    fun syncReturnsErrorWhenFirebaseTokenIsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"reason":null}"""))
        val result = registrar(token = null).sync(PushScope.All, emptySet(), emptySet(), syncHints = false)
        assertTrue(result is PushRegistrarResult.Error)
    }

    @Test
    fun updatePatchesScopeAndSets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val result = registrar().update(PushScope.Pinned, emptySet(), setOf("p1"), syncHints = false)
        assertEquals(PushRegistrarResult.Success, result)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/push/fcm-register", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"scope\":\"pinned\""))
        assertTrue(body.contains("\"pinnedSessions\":[\"p1\"]"))
        // PATCH must not re-send the fcm token.
        assertTrue(!body.contains("fcmToken"))
    }

    @Test
    fun updateReturnsErrorOn404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"No FCM registration for this device."}"""))
        val result = registrar().update(PushScope.All, emptySet(), emptySet(), syncHints = false)
        assertTrue(result is PushRegistrarResult.Error)
    }

    @Test
    fun unregisterDeletesTheRow() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"removed":true}"""))
        val result = registrar().unregister()
        assertEquals(PushRegistrarResult.Success, result)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/push/fcm-register", req.path)
    }

    @Test
    fun syncRejectsCookieCredential() = runBlocking {
        val cookieSettings = InMemorySettings()
        cookieSettings.setServer(server.url("/").toString(), Credential.Cookie("cookie-value"))
        val r = PushRegistrar(
            settings = cookieSettings,
            httpClient = OkHttpClient(),
            tokenProvider = FirebaseTokenProvider { "tok" },
        )
        val result = r.sync(PushScope.All, emptySet(), emptySet(), syncHints = false)
        assertTrue(result is PushRegistrarResult.Error)
    }

    @Test
    fun noCallFollowsARedirectWithTheDeviceToken() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/api/push/fcm-config")))
            assertTrue(registrar().sync(PushScope.All, emptySet(), emptySet(), syncHints = false) is PushRegistrarResult.Error)
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/api/push/fcm-register")))
            assertTrue(registrar().unregister() is PushRegistrarResult.Error)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun logoutUnregisterUsesTheForgottenTokenAndSkipsCookies() = runBlocking {
        val cleared = InMemorySettings() // logout already forgot the credential
        val r = PushRegistrar(cleared, OkHttpClient(), FirebaseTokenProvider { "fcm" })
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertEquals(PushRegistrarResult.Success, r.unregister(server.url("/").toString(), Credential.DeviceToken(token)))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        val before = server.requestCount
        assertEquals(PushRegistrarResult.Success, r.unregister(server.url("/").toString(), Credential.Cookie("c")))
        assertEquals(before, server.requestCount)
    }

    /**
     * ta-jt9 I-B: logout bounds its hook with a coroutine timeout, which cannot end a blocking
     * `execute()`. The unregister call is bounded itself (LOGOUT_CALL_TIMEOUT_MS), whatever the
     * client's own timeouts: a server that never answers cannot hold the logout for long.
     */
    @Test
    fun theLogoutUnregisterIsBoundedWhenTheServerNeverAnswers() = runBlocking {
        val patient = OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        val r = PushRegistrar(InMemorySettings(), patient, FirebaseTokenProvider { "fcm" })
        server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(30, java.util.concurrent.TimeUnit.SECONDS))
        val started = System.nanoTime()
        val result = r.unregister(server.url("/").toString(), Credential.DeviceToken(token))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("$result", result is PushRegistrarResult.Error)
        assertTrue("the unregister waited $elapsedMs ms", elapsedMs < com.tether.app.client.LOGOUT_CALL_TIMEOUT_MS + 2_000)
        assertEquals("DELETE", server.takeRequest().method)
    }
}